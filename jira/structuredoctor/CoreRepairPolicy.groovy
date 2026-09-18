package structuredoctor

final class CoreRepairPolicy {
    static List<String> freshnessBlockers(RepairApplyRequest request,
                                          RepairRefresh refresh) {
        if (refresh?.repairPackage == null || refresh.impact == null) return ['repair-refresh']
        List<String> blockers = []
        if (refresh.repairPackage.id != request.repairPackageId) blockers.add('package-id')
        if (new ArrayList<Long>(refresh.repairPackage.affectedIssueIds ?: []).sort() !=
            new ArrayList<Long>(request.affectedIssueIds ?: []).sort()) {
            blockers.add('affected-issues')
        }
        Set<String> sources = new LinkedHashSet<>(request.expectedFingerprints?.keySet() ?: [])
        sources.addAll(refresh.currentFingerprints?.keySet() ?: [])
        for (String source : sources.sort()) {
            if (request.expectedFingerprints?.get(source) !=
                refresh.currentFingerprints?.get(source)) blockers.add('stale:' + source)
        }
        if (!refresh.repairPackage.selectable || !(refresh.repairPackage.blockers ?: []).isEmpty()) {
            blockers.add('repair-package')
        }
        if (!refresh.impact.complete || !refresh.impact.safeToApply) blockers.add('impact')
        blockers.unique().sort()
    }

    static List<String> confirmationBlockers(RepairApplyRequest request,
                                             RepairPackage repairPackage) {
        Set<Confirmation> supplied = (request.confirmations ?: []) as Set<Confirmation>
        List<Confirmation> required = [Confirmation.STRUCTURE_CHANGE]
        if (repairPackage.kind == RepairKind.JIRA_DATA) required.add(Confirmation.JIRA_DATA_CHANGE)
        required.findAll { !supplied.contains(it) }*.name().sort()
    }

    static String requestFingerprint(RepairApplyRequest request) {
        CoreCanonical.sha256([
            operationId: request.operationId, structureId: request.structureId,
            packageId: request.repairPackageId,
            affectedIssueIds: new ArrayList<Long>(request.affectedIssueIds ?: []).sort(),
            actorKey: request.actorKey,
            expected: request.expectedFingerprints,
            confirmations: (request.confirmations ?: []).collect {
                Confirmation confirmation -> confirmation.name()
            }.sort()
        ])
    }

    static void validate(RepairApplyRequest request) {
        if (request == null || !validOperationId(request.operationId)) {
            throw new IllegalArgumentException('valid operationId is required')
        }
        if (request.structureId <= 0L || !request.repairPackageId || !request.actorKey ||
            request.expectedFingerprints == null || request.affectedIssueIds == null) {
            throw new IllegalArgumentException('repair request is incomplete')
        }
    }

    static boolean validOperationId(String value) {
        value != null && value ==~
            /[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/
    }

    static int operationStatus(OperationState state) {
        switch (state) {
            case OperationState.VERIFIED: return 200
            case OperationState.ROLLED_BACK: return 409
            case OperationState.MUTATION_FAILED: return 500
            case OperationState.MANUAL_RECOVERY_REQUIRED: return 500
            default: return 202
        }
    }

    static RepairCoordinatorResult result(int status, String code,
                                          RepairOperation operation,
                                          boolean replayed,
                                          List<String> blockers) {
        new RepairCoordinatorResult(status: status, code: code, operation: operation,
            replayed: replayed, blockers: blockers ?: [])
    }
}
