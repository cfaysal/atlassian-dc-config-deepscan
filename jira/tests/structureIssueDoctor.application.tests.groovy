import structuredoctor.AnalysisScope
import structuredoctor.AnalyzeRequest
import structuredoctor.AutomationAuditSnapshot
import structuredoctor.AutomationDataProvider
import structuredoctor.AutomationRuleSnapshot
import structuredoctor.DoctorBoundaryException
import structuredoctor.DoctorAnalysis
import structuredoctor.DoctorApplication
import structuredoctor.DoctorRenderer
import structuredoctor.GeneratorSnapshot
import structuredoctor.HierarchyLevel
import structuredoctor.HierarchyProvider
import structuredoctor.HierarchySnapshot
import structuredoctor.IssueRelationSnapshot
import structuredoctor.JiraDataProvider
import structuredoctor.LiveAutomationProvider
import structuredoctor.LiveConfigurationDiscovery
import structuredoctor.LiveJiraGateway
import structuredoctor.LiveStructureGateway
import structuredoctor.LegacyIssueDoctor
import structuredoctor.OccurrenceSnapshot
import structuredoctor.PlanRequest
import structuredoctor.ProposalCandidate
import structuredoctor.ProposalSource
import structuredoctor.ReadResult
import structuredoctor.ReadState
import structuredoctor.RepairKind
import structuredoctor.RepairStrategy
import structuredoctor.StructureChoice
import structuredoctor.StructureSnapshot

int passed = 0
int failed = 0
List<String> failures = []

def check = { String name, Object actual, Object expected ->
    if (actual == expected) passed++
    else {
        failed++
        failures << (name + '\n     expected: ' + expected + '\n     actual  : ' + actual)
    }
}
def ok = { String name, boolean condition ->
    if (condition) passed++
    else { failed++; failures << name }
}

HierarchySnapshot hierarchy = new HierarchySnapshot(
    levels: [
        new HierarchyLevel(rank: 2L, levelId: 'upper', name: 'Upper', issueTypeIds: [300L]),
        new HierarchyLevel(rank: 1L, levelId: 'lower', name: 'Lower', issueTypeIds: [200L])
    ], fingerprint: 'hierarchy-r1')
GeneratorSnapshot generator = new GeneratorSnapshot(
    generatorId: 21L, moduleKey: 'synthetic:extender', type: 'EXTENDER', order: 1,
    enabled: true, parameters: [mode: 'synthetic'], revision: 'generator-r1', complete: true)
def occurrence = { String id, Long parentId, String provenance ->
    new OccurrenceSnapshot(
        occurrenceId: id, issueId: 1000L, rowId: 'row-' + id,
        parentPath: [parentId], parentIssueId: parentId, depth: 1, position: 0,
        provenance: provenance, creatorId: '21', provenanceComplete: true)
}
List<IssueRelationSnapshot> relations = [
    new IssueRelationSnapshot(issueId: 2000L, issueTypeId: 300L,
        nativeParentId: null, leadingParentIds: [], revisions: [issue: 'p1']),
    new IssueRelationSnapshot(issueId: 2001L, issueTypeId: 300L,
        nativeParentId: null, leadingParentIds: [], revisions: [issue: 'p2']),
    new IssueRelationSnapshot(issueId: 1000L, issueTypeId: 200L,
        nativeParentId: 2000L, leadingParentIds: [2000L], revisions: [issue: 'i1'])
]
def snapshotFor = { String revision -> new StructureSnapshot(
    structureId: 9L, revision: revision, hierarchy: hierarchy, generators: [generator],
    occurrences: [occurrence('keep', 2000L, 'ADVANCED_ROADMAPS'),
                  occurrence('remove', 2001L, 'JIRA_LINK')],
    relations: relations, fingerprint: 'forest-' + revision, complete: true)
}

int forestReads = 0
StructureSnapshot currentSnapshot = snapshotFor('r1')
LiveStructureGateway structures = new LiveStructureGateway(
    { ReadResult.complete([new StructureChoice(id: 9L, name: '<Portfolio>')]) },
    { long ignored -> forestReads++; ReadResult.complete(currentSnapshot) })
LiveConfigurationDiscovery configuration = new LiveConfigurationDiscovery(
    { ReadResult.complete(hierarchy) })
LiveJiraGateway jira = new LiveJiraGateway(
    { Collection<Long> ignored -> ReadResult.complete(relations) })
LiveAutomationProvider liveAutomation = new LiveAutomationProvider(
    { AnalysisScope ignored -> ReadResult.unavailable('live rules not proven') },
    { request -> ReadResult.unavailable('live audit not proven') })

int fallbackRuleReads = 0
int requestedAuditDays = 0
String auditRevision = 'audit-r1'
AutomationDataProvider fallback = [
    readRules: { AnalysisScope ignored ->
        fallbackRuleReads++
        ReadResult.complete([] as List<AutomationRuleSnapshot>)
    },
    readAudit: { request ->
        requestedAuditDays = request.requestedDays
        ReadResult.complete([new AutomationAuditSnapshot(
            ruleId: 77L, issueId: 1000L, occurredAt: '2026-09-01T10:00:00Z',
            action: 'WRITE', target: 'parent', successful: true,
            revision: auditRevision)] as List<AutomationAuditSnapshot>)
    }
] as AutomationDataProvider
ProposalSource proposals = { StructureSnapshot ignored ->
    ReadResult.complete([new ProposalCandidate(
        sourceId: 'candidate-1', strategy: RepairStrategy.RESTRICT_EXTENDER,
        kind: RepairKind.STRUCTURE, findingGroupIds: [], affectedIssueIds: [1000L],
        generatorIds: [21L], beforeState: [enabled: true], afterState: [enabled: true],
        permanentRowIds: [], explanation: 'Restrict the extender scope.', warnings: [],
        requirements: [], blockers: [])])
} as ProposalSource

DoctorApplication application = new DoctorApplication(
    structures, configuration, structures, jira, liveAutomation, fallback, proposals,
    { String key -> key == 'DEMO-1' ? 1000L : null })

check('page load lists structures', application.listStructures().value*.id, [9L])
check('page load does not scan forest', forestReads, 0)

DoctorAnalysis analysis = application.analyze(new AnalyzeRequest(
    structureId: 9L, issueKeyFilter: null, requestedAuditDays: null,
    ruleExportRef: 'rules-upload-1', auditExportRef: 'audit-upload-1'))
check('explicit analysis scans forest once', forestReads, 1)
check('default audit window', analysis.requestedAuditDays, 30)
check('fallback rules used', fallbackRuleReads, 1)
check('fallback audit window', requestedAuditDays, 30)
check('JSON fallback coverage named',
    analysis.coverage.find { it.source == 'automation-rules' }.provider, 'JSON_FALLBACK')
ok('analysis has snapshot id', analysis.snapshotId.startsWith('analysis-'))
check('no issue key required', analysis.issueKeyFilter, null)
check('duplicate group exposed', analysis.duplicates.groups.size(), 1)
ok('each analyzed finding can expose a bounded causal claim',
    analysis.causalClaims.size() >= 1)
ok('missing causal evidence remains visible',
    analysis.causalClaims[0].missingEvidence.contains('AUTOMATION_TO_JIRA_DATA'))

DoctorAnalysis focused = application.analyze(new AnalyzeRequest(
    structureId: 9L, issueKeyFilter: 'DEMO-1', requestedAuditDays: 7,
    ruleExportRef: null, auditExportRef: null))
check('display filter retained', focused.issueKeyFilter, 'DEMO-1')
check('display filter resolved to numeric ID', focused.displayIssueId, 1000L)
check('display filter does not trim snapshot', focused.snapshot.occurrences.size(), 2)

int readsBeforeInvisible = forestReads
DoctorBoundaryException invisibleStructure
try {
    application.analyze(new AnalyzeRequest(
        structureId: 999L, issueKeyFilter: null, requestedAuditDays: 30,
        ruleExportRef: null, auditExportRef: null))
} catch (DoctorBoundaryException failure) {
    invisibleStructure = failure
}
check('invisible Structure is indistinguishable from absent',
    invisibleStructure?.status, 404)
check('invisible Structure is not scanned', forestReads, readsBeforeInvisible)

DoctorBoundaryException invisibleIssue
try {
    application.analyze(new AnalyzeRequest(
        structureId: 9L, issueKeyFilter: 'DEMO-404', requestedAuditDays: 30,
        ruleExportRef: null, auditExportRef: null))
} catch (DoctorBoundaryException failure) {
    invisibleIssue = failure
}
check('invisible issue filter is indistinguishable from absent',
    invisibleIssue?.status, 404)

def group = analysis.duplicates.groups[0]
def selected = new PlanRequest(
    snapshotId: analysis.snapshotId,
    findingGroupIds: [group.id],
    retainOccurrenceByGroup: [(group.id): group.occurrences[0].occurrenceId],
    selectedPermanentRowIds: [])
def plan = application.plan(selected)
ok('valid plan request accepted', !plan.blockers.any { it.startsWith('invalid-') })

auditRevision = 'audit-r2'
def automationStale = application.plan(selected)
ok('changed Automation evidence makes the plan stale',
    automationStale.blockers.contains('stale-snapshot'))
auditRevision = 'audit-r1'

def unknown = application.plan(selected.copyWith(findingGroupIds: ['unknown'],
    retainOccurrenceByGroup: [unknown: 'occurrence']))
ok('unknown identifier rejected', unknown.blockers.contains('unknown-finding-group:unknown'))

def missingRetain = application.plan(selected.copyWith(retainOccurrenceByGroup: [:]))
ok('one retain required', missingRetain.blockers.contains('invalid-retain-occurrence:' + group.id))

currentSnapshot = snapshotFor('r2')
def stale = application.plan(selected)
ok('stale snapshot rejected', stale.blockers.contains('stale-snapshot'))

boolean extraKeyRejected = false
try {
    DoctorApplication.parsePlanRequest([
        snapshotId: analysis.snapshotId, findingGroupIds: [group.id],
        retainOccurrenceByGroup: [(group.id): group.occurrences[0].occurrenceId],
        selectedPermanentRowIds: [], jql: 'project = PRIVATE'])
} catch (IllegalArgumentException ignored) {
    extraKeyRejected = true
}
ok('extra browser configuration rejected', extraKeyRejected)

boolean analyzeInjectionRejected = false
try {
    DoctorApplication.parseAnalyzeRequest([
        structureId: '9', issueKeyFilter: null, requestedAuditDays: 30,
        ruleExportRef: null, auditExportRef: null,
        generatorParameters: [enabled: false]])
} catch (IllegalArgumentException ignored) {
    analyzeInjectionRejected = true
}
ok('Analyze rejects browser generator configuration', analyzeInjectionRejected)

ReadResult<List<IssueRelationSnapshot>> failedJira = ReadResult.failed('jira batch failed')
DoctorApplication failedApplication = new DoctorApplication(
    structures, configuration, structures,
    new LiveJiraGateway({ Collection<Long> ignored -> failedJira }),
    liveAutomation, fallback, proposals, { String ignored -> 1000L })
DoctorAnalysis incomplete = failedApplication.analyze(new AnalyzeRequest(
    structureId: 9L, issueKeyFilter: null, requestedAuditDays: 30,
    ruleExportRef: 'rules-upload-1', auditExportRef: 'audit-upload-1'))
check('failed source preserved',
    incomplete.coverage.find { it.source == 'jira-data' }.state, ReadState.FAILED)
ok('failed source blocks complete analysis', !incomplete.complete)

DoctorRenderer renderer = new DoctorRenderer()
String page = renderer.render(application.listStructures(), analysis, null)
ok('customer structure name escaped', page.contains('&lt;Portfolio&gt;'))
ok('raw customer structure name absent', !page.contains('<Portfolio>'))
ok('apply visibly disabled', page.contains('Apply is disabled'))
ok('analysis and planning endpoints present',
    page.contains('structureIssueDoctorAnalyze') && page.contains('structureIssueDoctorPlan'))
ok('issue key is optional', page.contains('optional'))

LiveConfigurationDiscovery unavailableConfiguration = new LiveConfigurationDiscovery(null)
check('unproven hierarchy is unavailable',
    unavailableConfiguration.readHierarchy().state, ReadState.UNAVAILABLE)

int legacyRepairs = 0
LegacyIssueDoctor legacy = new LegacyIssueDoctor(
    { long id, String key -> [structureId: id, issueKey: key] },
    { long id, String key, String confirmation ->
        legacyRepairs++
        [structureId: id, issueKey: key, confirmation: confirmation]
    })
check('legacy diagnosis delegates unchanged', legacy.analyze(9L, 'DEMO-1').issueKey, 'DEMO-1')
boolean legacyConfirmationRejected = false
try {
    legacy.repair(9L, 'DEMO-1', 'WRONG')
} catch (IllegalArgumentException ignored) {
    legacyConfirmationRejected = true
}
ok('legacy Parent Link confirmation retained', legacyConfirmationRejected)
check('invalid legacy confirmation does not write', legacyRepairs, 0)
check('valid legacy confirmation delegates once',
    legacy.repair(9L, 'DEMO-1', 'SET_PARENT_LINK').confirmation, 'SET_PARENT_LINK')
check('valid legacy confirmation writes once', legacyRepairs, 1)

File repository = new File(System.getProperty('repoRoot', '.')).canonicalFile
['LiveConfigurationDiscovery.groovy', 'LiveAutomationProvider.groovy'].each { String name ->
    String source = new File(repository, 'jira/structuredoctor/' + name).getText('UTF-8')
    ok(name + ' exposes no mutation method',
        !(source =~ /(?m)^\s*(?:Object|void|ReadResult<[^>]+>)\s+(?:set|update|save|delete|create|enable|disable|publish)\w*\s*\(/))
}

println "Structure Doctor application tests: ${passed} passed, ${failed} failed"
if (!failures.isEmpty()) {
    failures.each { println '\nFAIL: ' + it }
    System.exit(1)
}
