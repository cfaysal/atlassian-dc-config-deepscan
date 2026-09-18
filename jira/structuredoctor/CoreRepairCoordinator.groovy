package structuredoctor

final class CoreRepairCoordinator {
    private static final Map<OperationState, Set<OperationState>> TRANSITIONS = [
        (OperationState.ANALYZED): [OperationState.PLANNED] as Set,
        (OperationState.PLANNED): [OperationState.CONFIRMED] as Set,
        (OperationState.CONFIRMED): [OperationState.APPLIED] as Set,
        (OperationState.APPLIED): [OperationState.VERIFYING] as Set,
        (OperationState.VERIFYING): [OperationState.VERIFIED, OperationState.PENDING,
            OperationState.ROLLED_BACK, OperationState.MANUAL_RECOVERY_REQUIRED] as Set,
        (OperationState.PENDING): [OperationState.VERIFYING] as Set
    ].asImmutable()

    private final RepairInfrastructure infrastructure
    CoreRepairCoordinator(RepairInfrastructure infrastructure) {
        if (infrastructure == null) throw new IllegalArgumentException('infrastructure is required')
        this.infrastructure = infrastructure
    }
    RepairCoordinatorResult apply(RepairApplyRequest request) {
        RepairCoordinatorResult unavailable = unavailable()
        if (unavailable != null) return unavailable
        validate(request)
        String requestFingerprint = requestFingerprint(request)
        RepairOperation existing = infrastructure.find(request.operationId)
        if (existing != null) return replay(existing, requestFingerprint)
        try {
            return (RepairCoordinatorResult) infrastructure.withLocks(
                request.structureId, request.affectedIssueIds ?: [], {
                    RepairOperation raced = infrastructure.find(request.operationId)
                    if (raced != null) return replay(raced, requestFingerprint)
                    RepairOperation pending = infrastructure.findPending(
                        request.structureId, request.affectedIssueIds ?: [])
                    if (pending != null && pending.operationId != request.operationId) {
                        return result(409, 'PENDING_OPERATION', pending, false,
                            [pending.operationId])
                    }
                    RepairRefresh refresh = infrastructure.refresh(request)
                    List<String> blockers = freshnessBlockers(request, refresh)
                    if (!blockers.isEmpty()) {
                        return result(409, 'STALE_PLAN', null, false, blockers)
                    }
                    List<String> confirmationBlockers = confirmationBlockers(
                        request, refresh.repairPackage)
                    if (!confirmationBlockers.isEmpty()) {
                        return result(409, 'CONFIRMATION_REQUIRED', null, false,
                            confirmationBlockers)
                    }
                    return execute(request, requestFingerprint, refresh)
                } as Closure<Object>)
        } catch (RepairLockUnavailableException failure) {
            String code = failure.message in [
                'STRUCTURE_LOCK_UNAVAILABLE', 'ISSUE_LOCK_UNAVAILABLE'] ?
                failure.message : 'LOCK_UNAVAILABLE'
            return result(409, code, null, false, [code])
        }
    }

    RepairCoordinatorResult resume(String operationId, String actorKey) {
        RepairCoordinatorResult unavailable = unavailable()
        if (unavailable != null) return unavailable
        RepairOperation operation = infrastructure.find(operationId)
        if (operation == null) return result(404, 'OPERATION_NOT_FOUND', null, false, [])
        if (operation.actorKey != actorKey) return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
        if (operation.state != OperationState.PENDING) {
            return result(409, 'OPERATION_NOT_PENDING', operation, false, [])
        }
        RepairRefresh context = infrastructure.resumeContext(operation)
        if (context?.repairPackage == null) {
            return result(409, 'RECOVERY_CONTEXT_UNAVAILABLE', operation, false, [])
        }
        try {
            return (RepairCoordinatorResult) infrastructure.withLocks(
                operation.structureId, context.repairPackage.affectedIssueIds ?: [], {
                    RepairOperation current = infrastructure.find(operationId)
                    if (current == null) {
                        return result(404, 'OPERATION_NOT_FOUND', null, false, [])
                    }
                    if (current.actorKey != actorKey) {
                        return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
                    }
                    if (current.state != OperationState.PENDING) {
                        return result(200, current.state.name(), current, true, [])
                    }
                    RepairRefresh refreshed = infrastructure.resumeContext(current)
                    if (refreshed?.repairPackage == null) {
                        return result(409, 'RECOVERY_CONTEXT_UNAVAILABLE', current, false, [])
                    }
                    RepairOperation verifying = advance(current, OperationState.VERIFYING)
                    infrastructure.persist(verifying, refreshed.repairPackage)
                    finishVerification(verifying, refreshed,
                        infrastructure.verify(refreshed, false))
                } as Closure<Object>)
        } catch (RepairLockUnavailableException failure) {
            return result(409, failure.message ?: 'LOCK_UNAVAILABLE', operation,
                false, [failure.message ?: 'LOCK_UNAVAILABLE'])
        }
    }

    private RepairCoordinatorResult execute(RepairApplyRequest request,
                                            String requestFingerprint,
                                            RepairRefresh refresh) {
        RepairOperation operation = new RepairOperation(
            operationId: request.operationId, structureId: request.structureId,
            repairPackageId: request.repairPackageId, fingerprint: requestFingerprint,
            actorKey: request.actorKey, state: OperationState.CONFIRMED,
            history: [OperationState.ANALYZED, OperationState.PLANNED,
                      OperationState.CONFIRMED])
        infrastructure.persist(operation, refresh.repairPackage)
        RepairMutationOutcome mutation = infrastructure.mutate(refresh.repairPackage)
        if (mutation?.disposition == MutationDisposition.NOT_APPLIED || mutation == null) {
            return result(500, 'MUTATION_NOT_APPLIED', operation, false, [])
        }
        operation = advance(operation, OperationState.APPLIED)
        infrastructure.persist(operation, refresh.repairPackage)
        operation = advance(operation, OperationState.VERIFYING)
        infrastructure.persist(operation, refresh.repairPackage)
        if (mutation.disposition == MutationDisposition.PARTIAL) {
            return recover(operation, refresh)
        }
        finishVerification(operation, refresh, infrastructure.verify(refresh, false))
    }

    private RepairCoordinatorResult finishVerification(RepairOperation operation,
                                                        RepairRefresh refresh,
                                                        VerificationOutcome verification) {
        if (verification?.disposition == VerificationDisposition.VERIFIED) {
            return finish(operation, refresh.repairPackage, OperationState.VERIFIED, 200)
        }
        if (verification?.disposition == VerificationDisposition.PENDING) {
            return finish(operation, refresh.repairPackage, OperationState.PENDING, 202)
        }
        recover(operation, refresh)
    }

    private RepairCoordinatorResult recover(RepairOperation operation,
                                             RepairRefresh refresh) {
        RepairMutationOutcome restored = infrastructure.restore(refresh.repairPackage)
        if (restored?.disposition == MutationDisposition.APPLIED) {
            VerificationOutcome verification = infrastructure.verify(refresh, true)
            if (verification?.disposition == VerificationDisposition.VERIFIED) {
                return finish(operation, refresh.repairPackage,
                    OperationState.ROLLED_BACK, 409)
            }
        }
        finish(operation, refresh.repairPackage,
            OperationState.MANUAL_RECOVERY_REQUIRED, 500)
    }

    private RepairCoordinatorResult finish(RepairOperation operation,
                                           RepairPackage repairPackage,
                                           OperationState target,
                                           int status) {
        RepairOperation changed = advance(operation, target)
        infrastructure.persist(changed, repairPackage)
        result(status, target.name(), changed, false, [])
    }

    private RepairCoordinatorResult unavailable() {
        RepairAvailability availability = infrastructure.availability()
        if (availability?.enabled) return null
        List<String> missing = (availability?.missing ?: [])*.name().sort()
        result(409, 'APPLY_UNAVAILABLE', null, false, missing)
    }

    private static RepairCoordinatorResult replay(RepairOperation existing,
                                                  String requestFingerprint) {
        if (existing.fingerprint != requestFingerprint) {
            return result(409, 'OPERATION_ID_CONFLICT', existing, false, [])
        }
        int status = existing.state == OperationState.PENDING ? 202 :
            existing.state == OperationState.CONFIRMED ? 409 : 200
        result(status, status == 409 ? 'OPERATION_INCOMPLETE' : existing.state.name(),
            existing, true, [])
    }

    private static List<String> freshnessBlockers(RepairApplyRequest request,
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

    private static List<String> confirmationBlockers(RepairApplyRequest request,
                                                     RepairPackage repairPackage) {
        Set<Confirmation> supplied = (request.confirmations ?: []) as Set<Confirmation>
        List<Confirmation> required = [Confirmation.STRUCTURE_CHANGE]
        if (repairPackage.kind == RepairKind.JIRA_DATA) required.add(Confirmation.JIRA_DATA_CHANGE)
        required.findAll { !supplied.contains(it) }*.name().sort()
    }

    private static RepairOperation advance(RepairOperation operation, OperationState target) {
        if (!TRANSITIONS.get(operation.state)?.contains(target)) {
            throw new IllegalStateException('invalid repair transition')
        }
        operation.copyWith(state: target, history: operation.history + target)
    }

    private static String requestFingerprint(RepairApplyRequest request) {
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

    private static void validate(RepairApplyRequest request) {
        if (request == null || !(request.operationId ==~
            /[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/)) {
            throw new IllegalArgumentException('valid operationId is required')
        }
        if (request.structureId <= 0L || !request.repairPackageId || !request.actorKey ||
            request.expectedFingerprints == null || request.affectedIssueIds == null) {
            throw new IllegalArgumentException('repair request is incomplete')
        }
    }

    private static RepairCoordinatorResult result(int status, String code,
                                                  RepairOperation operation,
                                                  boolean replayed,
                                                  List<String> blockers) {
        new RepairCoordinatorResult(status: status, code: code, operation: operation,
            replayed: replayed, blockers: blockers ?: [])
    }
}
