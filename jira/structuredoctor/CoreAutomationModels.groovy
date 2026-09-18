package structuredoctor

import groovy.transform.Immutable

enum AutomationConflictType {
    MULTIPLE_WRITERS,
    CONFLICTING_SOURCES,
    CLEAR_ON_EMPTY,
    ASYNCHRONOUS_RACE,
    POSSIBLE_CHAIN,
    INVALID_TARGET_LEVEL,
    STRUCTURE_CONSUMED_WRITE
}

@Immutable(copyWith = true)
class AutomationFinding {
    String id
    AutomationConflictType type
    List<Long> ruleIds
    String target
    String summary
    List<EvidenceRequirement> requirements
    List<String> blockers
}

@Immutable(copyWith = true)
class AutomationAnalysisContext {
    AnalysisScope scope
    HierarchySnapshot hierarchy
    List<String> hierarchyTargets
    List<String> structureConsumedValues
}

@Immutable(copyWith = true)
class AutomationAnalysis {
    List<AutomationFinding> findings
    List<AutomationRuleSnapshot> rules
    Coverage auditCoverage
    boolean complete
    List<String> blockers

    List<AutomationFinding> findAll(AutomationConflictType type) {
        findings.findAll { AutomationFinding finding -> finding.type == type }
    }
}
