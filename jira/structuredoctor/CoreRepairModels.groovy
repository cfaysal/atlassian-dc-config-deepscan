package structuredoctor

import groovy.transform.Immutable

enum RepairKind {
    STRUCTURE,
    JIRA_DATA
}

@Immutable(copyWith = true)
class RepairPackage {
    String id
    RepairKind kind
    List<String> findingGroupIds
    List<Long> affectedIssueIds
    List<Long> generatorIds
    Map<String, Object> beforeState
    Map<String, Object> afterState
    List<String> confirmations
    List<EvidenceRequirement> requirements
    boolean selectable
}

@Immutable(copyWith = true)
class ImpactResult {
    boolean complete
    boolean safeToApply
    List<String> added
    List<String> removed
    List<String> moved
    List<String> unchanged
    List<String> blockers
}

enum OperationState {
    ANALYZED,
    PLANNED,
    CONFIRMED,
    APPLIED,
    VERIFYING,
    VERIFIED,
    PENDING,
    ROLLED_BACK,
    MANUAL_RECOVERY_REQUIRED
}

@Immutable(copyWith = true)
class RepairOperation {
    String operationId
    long structureId
    String repairPackageId
    String fingerprint
    String actorKey
    OperationState state
    List<OperationState> history
}

@Immutable(copyWith = true)
class GeneratorMutation {
    List<Long> generatorIds
    Map<String, Object> beforeState
    Map<String, Object> afterState
}

@Immutable(copyWith = true)
class JiraDataMutation {
    long issueId
    String fieldId
    String beforeValue
    String afterValue
}

@Immutable(copyWith = true)
class MutationReceipt {
    boolean applied
    String revision
    String message
}
