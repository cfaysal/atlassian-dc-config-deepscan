package structuredoctor

import groovy.transform.Immutable

enum CausalEdgeType {
    AUTOMATION_TO_JIRA_DATA,
    JIRA_DATA_TO_GENERATOR,
    GENERATOR_TO_OCCURRENCE,
    OCCURRENCE_TO_FINDING
}

@Immutable(copyWith = true)
class CausalEdge {
    String id
    CausalEdgeType type
    String fromId
    String toId
    String evidenceId
    boolean present
    boolean direct
}

@Immutable(copyWith = true)
class CausalContext {
    String findingId
    List<CausalEdge> edges
    boolean configurationConflict
    Coverage auditCoverage
    List<EvidenceRequirement> requirements
    List<String> blockers
}
