package structuredoctor

import groovy.transform.Immutable

enum RepairKind {
    STRUCTURE,
    JIRA_DATA
}

enum Confirmation {
    STRUCTURE_CHANGE,
    JIRA_DATA_CHANGE
}

enum RepairStrategy {
    ADJUST_DUPLICATES_FILTER,
    NARROW_INSERTER,
    RESTRICT_EXTENDER,
    REORDER_GENERATORS,
    REMOVE_PERMANENT_ROW,
    REPAIR_JIRA_PARENT
}

@Immutable(copyWith = true)
class RepairPackage {
    String id
    RepairKind kind
    RepairStrategy strategy
    List<String> findingGroupIds
    List<Long> affectedIssueIds
    List<Long> generatorIds
    Map<String, Object> beforeState
    Map<String, Object> afterState
    List<String> permanentRowIds
    String explanation
    List<String> warnings
    List<Confirmation> confirmations
    List<EvidenceRequirement> requirements
    List<String> blockers
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

enum RepairCapability {
    STRUCTURE_PREVIEW,
    REVISION_READ,
    TARGET_PERMISSION,
    MUTATION,
    EXACT_RESTORE,
    STRUCTURE_LOCK,
    ISSUE_COMPARE_AND_SET,
    CLUSTER_JOURNAL,
    RECALCULATION,
    TARGET_VERIFICATION
}

final class RepairAvailability {
    final boolean enabled
    final List<RepairCapability> missing

    RepairAvailability(boolean enabled, List<RepairCapability> missing) {
        this.enabled = enabled
        this.missing = new ArrayList<RepairCapability>(missing ?: [])
            .unique().sort { it.name() }.asImmutable()
    }

    static RepairAvailability enabled() {
        new RepairAvailability(true, [])
    }

    static RepairAvailability disabled(Collection<RepairCapability> missing) {
        new RepairAvailability(false, (missing ?: []) as List<RepairCapability>)
    }
}

@Immutable(copyWith = true)
class RepairApplyRequest {
    String operationId
    long structureId
    String repairPackageId
    List<Long> affectedIssueIds
    String actorKey
    Map<String, String> expectedFingerprints
    List<Confirmation> confirmations
}

@Immutable(copyWith = true)
class RepairRefresh {
    RepairPackage repairPackage
    Map<String, String> currentFingerprints
    ImpactResult impact
    Boolean targetPermitted
}

enum MutationDisposition {
    APPLIED,
    NOT_APPLIED,
    PARTIAL
}

@Immutable(copyWith = true)
class RepairMutationOutcome {
    MutationDisposition disposition
    String revision
    String message
}

enum VerificationDisposition {
    VERIFIED,
    PENDING,
    MISMATCH,
    UNAVAILABLE
}

@Immutable(copyWith = true)
class VerificationOutcome {
    VerificationDisposition disposition
    String message
}

@Immutable(copyWith = true)
class RepairCoordinatorResult {
    int status
    String code
    RepairOperation operation
    boolean replayed
    List<String> blockers
}

final class RepairLockUnavailableException extends RuntimeException {
    RepairLockUnavailableException(String code) {
        super(code)
    }
}
