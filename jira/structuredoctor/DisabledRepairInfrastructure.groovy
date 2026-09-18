package structuredoctor

final class DisabledRepairInfrastructure implements RepairInfrastructure {
    private final RepairAvailability state

    DisabledRepairInfrastructure(Collection<RepairCapability> missing =
        RepairCapability.values().toList()) {
        state = RepairAvailability.disabled(missing)
    }

    RepairAvailability availability() { state }
    RepairOperation find(String ignored) { null }
    RepairOperation findPending(long ignored, List<Long> ignoredIssues) { null }
    Object withLocks(long ignored, List<Long> ignoredIssues, Closure<Object> ignoredWork) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    RepairRefresh refresh(RepairApplyRequest ignored) { null }
    RepairRefresh resumeContext(RepairOperation ignored) { null }
    void persist(RepairOperation ignored, RepairPackage ignoredPackage) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    RepairMutationOutcome mutate(RepairPackage ignored) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    VerificationOutcome verify(RepairRefresh ignored, boolean ignoredRestored) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
    RepairMutationOutcome restore(RepairPackage ignored) {
        throw new UnsupportedOperationException('APPLY_UNAVAILABLE')
    }
}
