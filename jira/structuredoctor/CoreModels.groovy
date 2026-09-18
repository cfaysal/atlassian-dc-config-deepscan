package structuredoctor

import groovy.transform.Immutable

@Immutable(copyWith = true)
class HierarchyLevel {
    long rank
    String levelId
    String name
    List<Long> issueTypeIds
}

@Immutable(copyWith = true)
class HierarchySnapshot {
    List<HierarchyLevel> levels
    String fingerprint
}

@Immutable(copyWith = true)
class GeneratorSnapshot {
    long generatorId
    String moduleKey
    String type
    int order
    boolean enabled
    Map<String, Object> parameters
    String revision
    boolean complete
}

@Immutable(copyWith = true)
class OccurrenceSnapshot {
    String occurrenceId
    long issueId
    String rowId
    List<Long> parentPath
    Long parentIssueId
    int depth
    int position
    String provenance
    String creatorId
    boolean provenanceComplete
}

@Immutable(copyWith = true)
class IssueRelationSnapshot {
    long issueId
    long issueTypeId
    Long nativeParentId
    List<Long> leadingParentIds
    Map<String, String> revisions
}

@Immutable(copyWith = true)
class StructureSnapshot {
    long structureId
    String revision
    HierarchySnapshot hierarchy
    List<GeneratorSnapshot> generators
    List<OccurrenceSnapshot> occurrences
    List<IssueRelationSnapshot> relations
    String fingerprint
    boolean complete

    String planningFingerprint() {
        CoreCanonical.planningFingerprint(this)
    }
}

@Immutable(copyWith = true)
class AnalysisScope {
    List<Long> projectIds
    List<Long> issueTypeIds
    List<String> fieldIds
    List<Long> linkTypeIds
}

@Immutable(copyWith = true)
class AutomationRuleSnapshot {
    long ruleId
    boolean enabled
    List<Long> projectIds
    List<Long> issueTypeIds
    List<String> reads
    List<String> writes
    String revision
    boolean complete
}

@Immutable(copyWith = true)
class AutomationAuditSnapshot {
    long ruleId
    long issueId
    String occurredAt
    String action
    String revision
}

@Immutable(copyWith = true)
class AuditRequest {
    List<Long> ruleIds
    List<Long> issueIds
    int requestedDays
}

enum FindingType {
    DUPLICATE,
    MISSING_PARENT,
    CONFLICTING_PARENT,
    INVALID_LEVEL,
    ORPHAN,
    WRONG_PATH,
    GENERATOR_OVERLAP,
    AUTOMATION_CONFLICT
}

@Immutable(copyWith = true)
class Finding {
    String id
    FindingType type
    long issueId
    List<String> occurrenceIds
    String summary
    List<EvidenceRequirement> requirements
    List<String> blockers
}

@Immutable(copyWith = true)
class HierarchyAnalysis {
    List<Finding> findings
    boolean complete
    List<String> blockers

    boolean clean() {
        complete && findings.isEmpty()
    }
}

enum EvidenceGrade {
    CONFIGURATION_CONFLICT,
    POSSIBLE_CAUSE,
    PROBABLE_CAUSE,
    CONFIRMED_CAUSE
}

@Immutable(copyWith = true)
class CausalClaim {
    String id
    String findingId
    EvidenceGrade grade
    List<String> edgeIds
    List<String> presentEvidence
    List<String> missingEvidence
}
