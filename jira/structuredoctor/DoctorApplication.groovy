package structuredoctor

final class DoctorApplication {
    static final int DEFAULT_AUDIT_DAYS = 30
    private final StructureCatalogProvider catalog
    private final HierarchyProvider hierarchyProvider
    private final StructureSnapshotProvider structureProvider
    private final JiraDataProvider jiraProvider
    private final AutomationDataProvider liveAutomation
    private final AutomationDataProvider jsonAutomation
    private final ProposalSource proposals
    private final Closure<Long> issueKeyResolver
    private final DoctorAutomationEvidence automationEvidence
    private final Map<String, DoctorAnalysis> analyses =
        Collections.synchronizedMap(new LinkedHashMap<String, DoctorAnalysis>())
    DoctorApplication(StructureCatalogProvider catalog,
                      HierarchyProvider hierarchyProvider,
                      StructureSnapshotProvider structureProvider,
                      JiraDataProvider jiraProvider,
                      AutomationDataProvider liveAutomation,
                      AutomationDataProvider jsonAutomation,
                      ProposalSource proposals,
                      Closure<Long> issueKeyResolver = null) {
        this.catalog = catalog
        this.hierarchyProvider = hierarchyProvider
        this.structureProvider = structureProvider
        this.jiraProvider = jiraProvider
        this.liveAutomation = liveAutomation
        this.jsonAutomation = jsonAutomation
        this.automationEvidence = new DoctorAutomationEvidence(
            liveAutomation, jsonAutomation)
        this.proposals = proposals
        this.issueKeyResolver = issueKeyResolver
    }
    ReadResult<List<StructureChoice>> listStructures() {
        catalog == null ? ReadResult.unavailable('Structure catalog is unavailable') :
            catalog.listStructures()
    }
    DoctorAnalysis analyze(AnalyzeRequest request) {
        validateAnalyze(request)
        ReadResult<List<StructureChoice>> visibleStructures = listStructures()
        if (!visibleStructures.complete()) throw new DoctorBoundaryException(503, 'STRUCTURE_CATALOG_UNAVAILABLE')
        boolean visible = (visibleStructures.value ?: []).any { it.id == request.structureId }
        if (!visible) throw new DoctorBoundaryException(404, 'STRUCTURE_NOT_VISIBLE')
        int days = request.requestedAuditDays ?: DEFAULT_AUDIT_DAYS
        Long displayIssueId = request.issueKeyFilter ?
            issueKeyResolver?.call(request.issueKeyFilter) : null
        if (request.issueKeyFilter && displayIssueId == null) throw new DoctorBoundaryException(404, 'ISSUE_NOT_VISIBLE')
        ReadResult<HierarchySnapshot> hierarchyRead = hierarchyProvider == null ?
            ReadResult.unavailable('Jira hierarchy provider is unavailable') :
            hierarchyProvider.readHierarchy()
        ReadResult<StructureSnapshot> structureRead = structureProvider == null ?
            ReadResult.unavailable('Structure snapshot provider is unavailable') :
            structureProvider.readStructure(request.structureId)
        Set<Long> issueIds = (structureRead.value?.occurrences ?: [])*.issueId as Set<Long>
        ReadResult<List<IssueRelationSnapshot>> jiraRead = jiraProvider == null ?
            ReadResult.unavailable('Jira relationship provider is unavailable') :
            jiraProvider.readIssues(issueIds)
        StructureSnapshot snapshot = merge(structureRead, hierarchyRead, jiraRead)
        AnalysisScope scope = scope(snapshot)

        DoctorAutomationReads automationReads = automationEvidence.read(
            request, scope, issueIds, days)
        ReadResult<List<AutomationRuleSnapshot>> rules = automationReads.rules
        ReadResult<List<AutomationAuditSnapshot>> audit = automationReads.audit

        HierarchyAnalysis hierarchyAnalysis = new CoreHierarchyAnalyzer().analyze(snapshot)
        DuplicateAnalysis duplicateAnalysis = new CoreDuplicateAnalyzer().analyze(snapshot)
        AutomationAnalysis automationAnalysis = new CoreAutomationAnalyzer().analyze(
            rules, audit, new AutomationAnalysisContext(
                scope: scope, hierarchy: snapshot?.hierarchy,
                hierarchyTargets: [], structureConsumedValues: []))
        List<Finding> analyzedFindings = []
        analyzedFindings.addAll(hierarchyAnalysis.findings ?: [])
        analyzedFindings.addAll(duplicateAnalysis.findings ?: [])
        List<CausalClaim> causalClaims = analyzedFindings.collect { Finding finding ->
            new CoreCausalityEngine().claim(new CausalContext(
                findingId: finding.id, edges: [],
                configurationConflict: finding.type == FindingType.CONFLICTING_PARENT,
                auditCoverage: audit.coverage,
                requirements: finding.requirements ?: [],
                blockers: finding.blockers ?: []))
        }
        List<SourceCoverage> coverage = [
            coverage('jira-hierarchy', hierarchyRead, 'LIVE'),
            coverage('structure-snapshot', structureRead, 'LIVE'),
            coverage('jira-data', jiraRead, 'LIVE'),
            coverage('automation-rules', rules, automationReads.ruleProvider),
            coverage('automation-audit', audit, automationReads.auditProvider)
        ]
        List<String> blockers = coverage.findAll { it.state != ReadState.COMPLETE }
            .collect { SourceCoverage item -> item.source }
        blockers.addAll(hierarchyAnalysis.blockers ?: [])
        blockers.addAll(duplicateAnalysis.blockers ?: [])
        blockers.addAll(automationAnalysis.blockers ?: [])
        String dependencyFingerprint = dependencyFingerprint(snapshot, rules, audit)
        String snapshotId = 'analysis-' + CoreCanonical.sha256([
            structureId: request.structureId,
            fingerprint: dependencyFingerprint,
            states: coverage.collectEntries { [(it.source): it.state.name()] },
            auditDays: days
        ]).substring(0, 24)
        DoctorAnalysis result = new DoctorAnalysis(
            snapshotId: snapshotId, structureId: request.structureId,
            issueKeyFilter: request.issueKeyFilter, displayIssueId: displayIssueId,
            requestedAuditDays: days,
            snapshot: snapshot, dependencyFingerprint: dependencyFingerprint,
            coverage: coverage,
            hierarchy: hierarchyAnalysis, duplicates: duplicateAnalysis,
            automation: automationAnalysis, causalClaims: causalClaims,
            complete: blockers.isEmpty(), blockers: blockers.unique().sort())
        synchronized (analyses) {
            if (analyses.size() >= 100 && !analyses.containsKey(snapshotId)) {
                String removed = analyses.keySet().iterator().next()
                analyses.remove(removed)
                automationEvidence.remove(removed)
            }
            analyses.put(snapshotId, result)
            automationEvidence.remember(snapshotId, automationReads)
        }
        result
    }
    ProposalPlan plan(PlanRequest request) {
        if (request == null || !request.snapshotId) {
            return blocked('invalid-snapshot-id')
        }
        DoctorAnalysis prior = analyses.get(request.snapshotId)
        if (prior == null) {
            return blocked('unknown-snapshot')
        }
        ReadResult<HierarchySnapshot> hierarchyRead = hierarchyProvider.readHierarchy()
        ReadResult<StructureSnapshot> structureRead = structureProvider.readStructure(prior.structureId)
        Set<Long> ids = (structureRead.value?.occurrences ?: [])*.issueId as Set<Long>
        ReadResult<List<IssueRelationSnapshot>> jiraRead = jiraProvider.readIssues(ids)
        StructureSnapshot current = merge(structureRead, hierarchyRead, jiraRead)
        if (current?.planningFingerprint() == null ||
            current.planningFingerprint() != prior.snapshot?.planningFingerprint()) {
            return blocked('stale-snapshot')
        }
        AnalysisScope currentScope = scope(current)
        boolean useJsonRules = prior.coverage.find {
            it.source == 'automation-rules'
        }?.provider == 'JSON_FALLBACK'
        boolean useJsonAudit = prior.coverage.find {
            it.source == 'automation-audit'
        }?.provider == 'JSON_FALLBACK'
        DoctorAutomationReads automationReads = automationEvidence.readForPlan(
            request.snapshotId, useJsonRules, useJsonAudit, currentScope, ids,
            prior.requestedAuditDays)
        ReadResult<List<AutomationRuleSnapshot>> rules = automationReads.rules
        ReadResult<List<AutomationAuditSnapshot>> audit = automationReads.audit
        if (dependencyFingerprint(current, rules, audit) != prior.dependencyFingerprint) {
            return blocked('stale-snapshot')
        }
        DuplicateAnalysis duplicates = new CoreDuplicateAnalyzer().analyze(current)
        RepairSelection selection = new RepairSelection(
            findingGroupIds: request.findingGroupIds ?: [],
            retainOccurrenceByGroup: request.retainOccurrenceByGroup ?: [:],
            selectedPermanentRowIds: request.selectedPermanentRowIds ?: [])
        new CoreProposalPlanner().plan(current, duplicates, selection, proposals)
    }
    DoctorAnalysis analysis(String snapshotId) {
        analyses.get(snapshotId)
    }
    static AnalyzeRequest parseAnalyzeRequest(Map<String, Object> payload) {
        DoctorRequests.parseAnalyze(payload)
    }
    static PlanRequest parsePlanRequest(Map<String, Object> payload) {
        DoctorRequests.parsePlan(payload)
    }

    private static StructureSnapshot merge(ReadResult<StructureSnapshot> structureRead,
                                           ReadResult<HierarchySnapshot> hierarchyRead,
                                           ReadResult<List<IssueRelationSnapshot>> jiraRead) {
        StructureSnapshot base = structureRead?.value
        if (base == null) return null
        new StructureSnapshot(
            structureId: base.structureId, revision: base.revision,
            hierarchy: hierarchyRead?.value, generators: base.generators ?: [],
            occurrences: base.occurrences ?: [], relations: jiraRead?.value ?: [],
            fingerprint: base.fingerprint,
            complete: base.complete && structureRead.complete() && hierarchyRead?.complete() &&
                jiraRead?.complete())
    }

    private static AnalysisScope scope(StructureSnapshot snapshot) {
        new AnalysisScope(projectIds: [],
            issueTypeIds: (snapshot?.relations ?: [])*.issueTypeId.unique().sort(),
            fieldIds: [], linkTypeIds: [])
    }

    private static SourceCoverage coverage(String source, ReadResult<?> read, String provider) {
        new SourceCoverage(source: source, state: read?.state ?: ReadState.FAILED,
            reason: read?.reason, coverage: read?.coverage, provider: provider)
    }

    private static String dependencyFingerprint(
        StructureSnapshot snapshot,
        ReadResult<List<AutomationRuleSnapshot>> rules,
        ReadResult<List<AutomationAuditSnapshot>> audit) {
        String structureFingerprint = snapshot?.planningFingerprint()
        if (structureFingerprint == null || !rules?.complete() || !audit?.complete()) return null
        CoreCanonical.sha256([
            structure: structureFingerprint,
            rules: (rules.value ?: []).collect { AutomationRuleSnapshot rule ->
                [id: rule.ruleId, revision: rule.revision, complete: rule.complete]
            },
            audit: (audit.value ?: []).collect { AutomationAuditSnapshot entry ->
                [ruleId: entry.ruleId, issueId: entry.issueId, occurredAt: entry.occurredAt,
                 action: entry.action, target: entry.target, successful: entry.successful,
                 revision: entry.revision]
            },
            coverage: audit.coverage == null ? null : [
                requested: audit.coverage.requested, actual: audit.coverage.actual,
                capped: audit.coverage.capped, from: audit.coverage.fromInclusive,
                to: audit.coverage.toInclusive]
        ])
    }

    private static ProposalPlan blocked(String reason) {
        new ProposalPlan(packages: [], complete: false, blockers: [reason])
    }

    private static void validateAnalyze(AnalyzeRequest request) {
        if (request == null || request.structureId <= 0L) {
            throw new IllegalArgumentException('A positive Structure ID is required')
        }
        int days = request.requestedAuditDays ?: DEFAULT_AUDIT_DAYS
        if (days < 1 || days > 365) throw new IllegalArgumentException('Audit days must be 1 to 365')
        if (request.issueKeyFilter && !(request.issueKeyFilter ==~ /[A-Za-z][A-Za-z0-9_]*-[0-9]+/)) {
            throw new IllegalArgumentException('Issue key filter is invalid')
        }
    }

}
