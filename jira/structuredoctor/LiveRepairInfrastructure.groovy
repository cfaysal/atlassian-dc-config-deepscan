package structuredoctor

final class LiveRepairInfrastructure implements RepairInfrastructure {
    private static final Set<RepairCapability> REQUIRED =
        RepairCapability.values() as Set<RepairCapability>

    private final Map<String, Closure<?>> operations
    private final RepairAvailability state

    LiveRepairInfrastructure(Collection<RepairCapability> provenCapabilities,
                             Map<String, Closure<?>> operations) {
        this.operations = new LinkedHashMap<>(operations ?: [:])
        Set<RepairCapability> proven = new LinkedHashSet<>(provenCapabilities ?: [])
        Set<RepairCapability> missing = new LinkedHashSet<>(REQUIRED)
        missing.removeAll(proven)
        requireOperation(missing, RepairCapability.STRUCTURE_PREVIEW, 'refresh')
        requireOperation(missing, RepairCapability.REVISION_READ, 'refresh')
        requireOperation(missing, RepairCapability.TARGET_PERMISSION, 'refresh')
        requireOperation(missing, RepairCapability.MUTATION, 'mutate')
        requireOperation(missing, RepairCapability.EXACT_RESTORE, 'restore')
        requireOperation(missing, RepairCapability.STRUCTURE_LOCK, 'withLocks')
        requireOperation(missing, RepairCapability.ISSUE_COMPARE_AND_SET, 'withLocks')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'find')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'findPending')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'persist')
        requireOperation(missing, RepairCapability.CLUSTER_JOURNAL, 'resumeContext')
        requireOperation(missing, RepairCapability.RECALCULATION, 'verify')
        requireOperation(missing, RepairCapability.TARGET_VERIFICATION, 'verify')
        state = missing.isEmpty() ? RepairAvailability.enabled() :
            RepairAvailability.disabled(missing)
    }

    @Override
    RepairAvailability availability() { state }

    @Override
    RepairOperation find(String operationId) {
        (RepairOperation) invoke('find', operationId)
    }

    @Override
    RepairOperation findPending(long structureId, List<Long> issueIds) {
        (RepairOperation) invoke('findPending', structureId, issueIds)
    }

    @Override
    Object withLocks(long structureId, List<Long> issueIds, Closure<Object> work) {
        invoke('withLocks', structureId, issueIds, work)
    }

    @Override
    RepairRefresh refresh(RepairApplyRequest request) {
        (RepairRefresh) invoke('refresh', request)
    }

    @Override
    RepairRefresh resumeContext(RepairOperation operation) {
        (RepairRefresh) invoke('resumeContext', operation)
    }

    @Override
    void persist(RepairOperation operation, RepairPackage repairPackage) {
        invoke('persist', operation, repairPackage)
    }

    @Override
    RepairMutationOutcome mutate(RepairPackage repairPackage) {
        (RepairMutationOutcome) invoke('mutate', repairPackage)
    }

    @Override
    VerificationOutcome verify(RepairRefresh context, boolean restored) {
        (VerificationOutcome) invoke('verify', context, restored)
    }

    @Override
    RepairMutationOutcome restore(RepairPackage repairPackage) {
        (RepairMutationOutcome) invoke('restore', repairPackage)
    }

    private Object invoke(String name, Object... arguments) {
        Closure<?> operation = operations.get(name)
        if (operation == null) throw new IllegalStateException('missing repair operation: ' + name)
        operation.call(*arguments)
    }

    private void requireOperation(Set<RepairCapability> missing,
                                  RepairCapability capability,
                                  String operation) {
        if (!operations.containsKey(operation)) missing.add(capability)
    }
}
