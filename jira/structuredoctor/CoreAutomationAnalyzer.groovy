package structuredoctor

import groovy.transform.CompileStatic

@CompileStatic
final class CoreAutomationAnalyzer {
    AutomationAnalysis analyze(ReadResult<List<AutomationRuleSnapshot>> ruleRead,
                               ReadResult<List<AutomationAuditSnapshot>> auditRead,
                               AutomationAnalysisContext context) {
        List<String> blockers = []
        if (ruleRead == null || !ruleRead.complete() || ruleRead.value == null) {
            blockers.add('automation-rules')
        }
        if (auditRead == null || !auditRead.complete()) {
            blockers.add('automation-audit-coverage')
        }
        if (context == null || context.scope == null) {
            blockers.add('analysis-scope')
        }
        List<AutomationRuleSnapshot> rules = (ruleRead?.value ?: []).findAll {
            AutomationRuleSnapshot rule -> rule.enabled && relevant(rule, context?.scope)
        }
        if (rules.any { AutomationRuleSnapshot rule -> !rule.complete }) {
            blockers.add('automation-rule-normalization')
        }
        List<AutomationRuleSnapshot> analyzableRules = rules.findAll {
            AutomationRuleSnapshot rule -> rule.complete
        }
        List<AutomationFinding> findings = []
        Map<String, List<AutomationRuleSnapshot>> writers = writerIndex(analyzableRules)
        for (Map.Entry<String, List<AutomationRuleSnapshot>> entry : writers.entrySet()) {
            List<AutomationRuleSnapshot> overlapping = overlappingWriters(entry.value)
            if (overlapping.size() > 1) {
                findings.add(finding(AutomationConflictType.MULTIPLE_WRITERS,
                    overlapping, entry.key, 'Multiple enabled rules write the same target'))
                if (sourceSets(overlapping, entry.key).size() > 1) {
                    findings.add(finding(AutomationConflictType.CONFLICTING_SOURCES,
                        overlapping, entry.key, 'Rules derive the target from different sources'))
                }
                if (overlapping.any { AutomationRuleSnapshot rule -> rule.asynchronous }) {
                    findings.add(finding(AutomationConflictType.ASYNCHRONOUS_RACE,
                        overlapping, entry.key, 'Asynchronous writers have last-writer ambiguity'))
                }
            }
        }
        addClearFindings(analyzableRules, findings)
        addChainFindings(analyzableRules, findings)
        addHierarchyFindings(analyzableRules, context, findings, blockers)
        addStructureConsumerFindings(analyzableRules, context, findings)
        findings.sort { AutomationFinding left, AutomationFinding right ->
            int typeOrder = left.type.name() <=> right.type.name()
            typeOrder != 0 ? typeOrder : left.target <=> right.target
        }
        new AutomationAnalysis(
            findings: findings,
            rules: rules,
            auditCoverage: auditRead?.coverage,
            complete: blockers.isEmpty(),
            blockers: blockers.unique().sort()
        )
    }

    private static void addClearFindings(List<AutomationRuleSnapshot> rules,
                                         List<AutomationFinding> findings) {
        for (AutomationRuleSnapshot rule : rules) {
            for (String target : rule.clears ?: []) {
                findings.add(finding(AutomationConflictType.CLEAR_ON_EMPTY,
                    [rule], target, 'Rule can clear a hierarchy-related target'))
            }
        }
    }

    private static void addChainFindings(List<AutomationRuleSnapshot> rules,
                                         List<AutomationFinding> findings) {
        Set<String> targets = rules.collectMany { AutomationRuleSnapshot rule -> rule.writes ?: [] } as Set<String>
        for (String target : targets) {
            List<AutomationRuleSnapshot> writers = rules.findAll {
                AutomationRuleSnapshot rule -> (rule.writes ?: []).contains(target)
            }
            List<AutomationRuleSnapshot> readers = rules.findAll {
                AutomationRuleSnapshot rule -> rule.allowOtherRuleTrigger &&
                    (rule.reads ?: []).contains(target)
            }
            if (!writers.isEmpty() && !readers.isEmpty()) {
                findings.add(finding(AutomationConflictType.POSSIBLE_CHAIN,
                    uniqueRules(writers + readers), target,
                    'One rule can feed another rule'))
            }
        }
    }

    private static void addHierarchyFindings(List<AutomationRuleSnapshot> rules,
                                             AutomationAnalysisContext context,
                                             List<AutomationFinding> findings,
                                             List<String> blockers) {
        if (context?.hierarchy == null || context.hierarchy.levels == null) {
            blockers.add('jira-hierarchy')
            return
        }
        Set<Long> knownTypes = context.hierarchy.levels.collectMany {
            HierarchyLevel level -> level.issueTypeIds ?: []
        } as Set<Long>
        Set<String> targets = (context.hierarchyTargets ?: []) as Set<String>
        for (AutomationRuleSnapshot rule : rules) {
            if (!Collections.disjoint(rule.writes ?: [], targets) &&
                (rule.issueTypeIds ?: []).any { Long id -> !knownTypes.contains(id) }) {
                findings.add(finding(AutomationConflictType.INVALID_TARGET_LEVEL,
                    [rule], ((rule.writes ?: []).find { String value -> targets.contains(value) }),
                    'Rule scope includes an issue type outside the configured hierarchy'))
            }
        }
    }

    private static void addStructureConsumerFindings(List<AutomationRuleSnapshot> rules,
                                                     AutomationAnalysisContext context,
                                                     List<AutomationFinding> findings) {
        Set<String> consumed = (context?.structureConsumedValues ?: []) as Set<String>
        for (AutomationRuleSnapshot rule : rules) {
            for (String target : (rule.writes ?: []).findAll { String value -> consumed.contains(value) }) {
                findings.add(finding(AutomationConflictType.STRUCTURE_CONSUMED_WRITE,
                    [rule], target, 'Rule writes a value consumed by the selected Structure'))
            }
        }
    }

    private static Map<String, List<AutomationRuleSnapshot>> writerIndex(
        List<AutomationRuleSnapshot> rules) {
        Map<String, List<AutomationRuleSnapshot>> values = new LinkedHashMap<>()
        for (AutomationRuleSnapshot rule : rules) {
            for (String target : rule.writes ?: []) {
                values.computeIfAbsent(target) { String ignored -> [] }.add(rule)
            }
        }
        values
    }

    private static List<AutomationRuleSnapshot> overlappingWriters(
        List<AutomationRuleSnapshot> rules) {
        rules.findAll { AutomationRuleSnapshot left ->
            rules.any { AutomationRuleSnapshot right ->
                left.ruleId != right.ruleId && scopesOverlap(left, right)
            }
        }
    }

    private static boolean scopesOverlap(AutomationRuleSnapshot left,
                                         AutomationRuleSnapshot right) {
        intersectsOrGlobal(left.projectIds, right.projectIds) &&
            intersectsOrGlobal(left.issueTypeIds, right.issueTypeIds)
    }

    private static boolean relevant(AutomationRuleSnapshot rule, AnalysisScope scope) {
        if (scope == null) {
            return true
        }
        intersectsOrGlobal(rule.projectIds, scope.projectIds) &&
            intersectsOrGlobal(rule.issueTypeIds, scope.issueTypeIds)
    }

    private static boolean intersectsOrGlobal(List<Long> left, List<Long> right) {
        left == null || left.isEmpty() || right == null || right.isEmpty() ||
            !Collections.disjoint(left, right)
    }

    private static Set<List<String>> sourceSets(List<AutomationRuleSnapshot> rules, String target) {
        rules.collect { AutomationRuleSnapshot rule ->
            (rule.sourcesByTarget?.get(target) ?: []).sort()
        } as Set<List<String>>
    }

    private static List<AutomationRuleSnapshot> uniqueRules(List<AutomationRuleSnapshot> rules) {
        Map<Long, AutomationRuleSnapshot> values = new LinkedHashMap<>()
        rules.each { AutomationRuleSnapshot rule -> values.put(rule.ruleId, rule) }
        values.values() as List<AutomationRuleSnapshot>
    }

    private static AutomationFinding finding(AutomationConflictType type,
                                             List<AutomationRuleSnapshot> rules,
                                             String target,
                                             String summary) {
        List<Long> ruleIds = rules*.ruleId.sort()
        List<EvidenceRequirement> requirements = rules.findAll {
            AutomationRuleSnapshot rule -> rule.revision != null && !rule.revision.trim().isEmpty()
        }.collect { AutomationRuleSnapshot rule ->
            new EvidenceRequirement('automation-rule:' + rule.ruleId, rule.revision, true)
        }
        new AutomationFinding(
            id: CoreCanonical.deterministicId('automation-finding', [
                type: type.name(), target: target, ruleIds: ruleIds
            ]),
            type: type,
            ruleIds: ruleIds,
            target: target,
            summary: summary,
            requirements: requirements,
            blockers: []
        )
    }
}
