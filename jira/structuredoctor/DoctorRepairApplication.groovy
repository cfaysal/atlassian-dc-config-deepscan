package structuredoctor

final class DoctorRepairApplication {
    private static final Set<String> APPLY_KEYS = [
        'snapshotId', 'repairPackageId', 'operationId',
        'confirmStructureChange', 'confirmJiraDataChange'
    ] as Set<String>

    private final RepairInfrastructure infrastructure
    private final CoreRepairCoordinator coordinator
    private final Map<String, Map<String, Object>> plans =
        Collections.synchronizedMap(new LinkedHashMap<String, Map<String, Object>>())

    DoctorRepairApplication(RepairInfrastructure infrastructure =
        new DisabledRepairInfrastructure()) {
        this.infrastructure = infrastructure
        this.coordinator = new CoreRepairCoordinator(infrastructure)
    }

    RepairAvailability availability() {
        infrastructure.availability()
    }

    void registerPlan(DoctorAnalysis analysis, ProposalPlan plan) {
        if (analysis == null || plan == null) return
        synchronized (plans) {
            if (plans.size() >= 100 && !plans.containsKey(analysis.snapshotId)) {
                plans.remove(plans.keySet().iterator().next())
            }
            plans.put(analysis.snapshotId, [analysis: analysis, plan: plan])
        }
    }

    RepairCoordinatorResult apply(Map<String, Object> payload, String actorKey) {
        validatePayload(payload)
        RepairAvailability state = infrastructure.availability()
        if (!state.enabled) return unavailable(state)
        Map<String, Object> registration = plans.get(text(payload.snapshotId, 'snapshotId'))
        if (registration == null) return result(404, 'SNAPSHOT_NOT_FOUND', [])
        DoctorAnalysis analysis = (DoctorAnalysis) registration.analysis
        ProposalPlan plan = (ProposalPlan) registration.plan
        String packageId = text(payload.repairPackageId, 'repairPackageId')
        RepairPackage repairPackage = (plan.packages ?: []).find {
            RepairPackage item -> item.id == packageId
        }
        if (repairPackage == null) return result(404, 'REPAIR_PACKAGE_NOT_FOUND', [])
        Map<String, String> expected = new LinkedHashMap<>()
        for (EvidenceRequirement requirement : repairPackage.requirements ?: []) {
            String previous = expected.put(requirement.source, requirement.fingerprint)
            if (previous != null && previous != requirement.fingerprint) {
                return result(409, 'CONFLICTING_REQUIREMENTS', [requirement.source])
            }
        }
        List<Confirmation> confirmations = []
        if (payload.confirmStructureChange == Boolean.TRUE) {
            confirmations.add(Confirmation.STRUCTURE_CHANGE)
        }
        if (payload.confirmJiraDataChange == Boolean.TRUE) {
            confirmations.add(Confirmation.JIRA_DATA_CHANGE)
        }
        coordinator.apply(new RepairApplyRequest(
            operationId: text(payload.operationId, 'operationId'),
            structureId: analysis.structureId,
            repairPackageId: repairPackage.id,
            affectedIssueIds: repairPackage.affectedIssueIds ?: [],
            actorKey: actor(actorKey), expectedFingerprints: expected,
            confirmations: confirmations))
    }

    RepairCoordinatorResult status(String operationId, String actorKey) {
        coordinator.resume(text(operationId, 'operationId'), actor(actorKey))
    }

    private static void validatePayload(Map<String, Object> payload) {
        if (payload == null || payload.keySet() != APPLY_KEYS) {
            throw new IllegalArgumentException('Apply request contains missing or unsupported keys')
        }
        if (!(payload.confirmStructureChange instanceof Boolean) ||
            !(payload.confirmJiraDataChange instanceof Boolean)) {
            throw new IllegalArgumentException('Apply confirmations must be boolean')
        }
    }

    private static String actor(String value) {
        text(value, 'authenticated actor')
    }

    private static String text(Object value, String name) {
        if (!(value instanceof CharSequence) || !value.toString().trim()) {
            throw new IllegalArgumentException(name + ' is required')
        }
        value.toString().trim()
    }

    private static RepairCoordinatorResult unavailable(RepairAvailability state) {
        new RepairCoordinatorResult(
            status: 409, code: 'APPLY_UNAVAILABLE', operation: null,
            replayed: false, blockers: state.missing*.name().sort())
    }

    private static RepairCoordinatorResult result(int status, String code,
                                                  List<String> blockers) {
        new RepairCoordinatorResult(
            status: status, code: code, operation: null,
            replayed: false, blockers: blockers ?: [])
    }
}
