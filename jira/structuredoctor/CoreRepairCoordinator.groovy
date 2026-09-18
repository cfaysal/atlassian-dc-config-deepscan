package structuredoctor

import static structuredoctor.CoreRepairPolicy.confirmationBlockers
import static structuredoctor.CoreRepairPolicy.freshnessBlockers
import static structuredoctor.CoreRepairPolicy.operationStatus
import static structuredoctor.CoreRepairPolicy.requestFingerprint
import static structuredoctor.CoreRepairPolicy.result
import static structuredoctor.CoreRepairPolicy.validOperationId
import static structuredoctor.CoreRepairPolicy.validate

final class CoreRepairCoordinator {
    private static final Map<OperationState, Set<OperationState>> TRANSITIONS = [
        (OperationState.ANALYZED): [OperationState.PLANNED] as Set,
        (OperationState.PLANNED): [OperationState.CONFIRMED] as Set,
        (OperationState.CONFIRMED): [OperationState.APPLIED,
            OperationState.MUTATION_FAILED,
            OperationState.MANUAL_RECOVERY_REQUIRED] as Set,
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
        if (existing != null) {
            RepairCoordinatorResult previous = replay(existing, requestFingerprint)
            if (existing.state != OperationState.PENDING ||
                previous.code == 'OPERATION_ID_CONFLICT') return previous
            return resume(existing.operationId, request.actorKey)
        }
        try {
            return (RepairCoordinatorResult) infrastructure.withLocks(
                request.structureId, request.affectedIssueIds ?: [], {
                    RepairOperation raced = infrastructure.find(request.operationId)
                    if (raced != null) {
                        RepairCoordinatorResult previous = replay(raced, requestFingerprint)
                        if (raced.state != OperationState.PENDING ||
                            previous.code == 'OPERATION_ID_CONFLICT') return previous
                        return resumeLocked(raced, request.actorKey)
                    }
                    RepairOperation pending = infrastructure.findPending(
                        request.structureId, request.affectedIssueIds ?: [])
                    if (pending != null && pending.operationId != request.operationId) {
                        return result(409, 'PENDING_OPERATION', pending, false,
                            [pending.operationId])
                    }
                    RepairRefresh refresh = infrastructure.refresh(request)
                    List<String> blockers = freshnessBlockers(request, refresh)
                    if (blockers.contains('repair-refresh')) {
                        return result(409, 'STALE_PLAN', null, false, blockers)
                    }
                    if (refresh?.targetPermitted != Boolean.TRUE) {
                        return result(403, 'TARGET_PERMISSION_REQUIRED', null, false, [])
                    }
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
        if (!validOperationId(operationId)) {
            throw new IllegalArgumentException('valid operationId is required')
        }
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
                    resumeLocked(current, actorKey)
                } as Closure<Object>)
        } catch (RepairLockUnavailableException failure) {
            return result(409, failure.message ?: 'LOCK_UNAVAILABLE', operation,
                false, [failure.message ?: 'LOCK_UNAVAILABLE'])
        }
    }

    RepairCoordinatorResult status(String operationId, String actorKey) {
        RepairCoordinatorResult unavailable = unavailable()
        if (unavailable != null) return unavailable
        if (!validOperationId(operationId)) {
            throw new IllegalArgumentException('valid operationId is required')
        }
        RepairOperation operation = infrastructure.find(operationId)
        if (operation == null) return result(404, 'OPERATION_NOT_FOUND', null, false, [])
        if (operation.actorKey != actorKey) {
            return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
        }
        result(operationStatus(operation.state), operation.state.name(),
            operation, false, [])
    }

    private RepairCoordinatorResult resumeLocked(RepairOperation operation,
                                                 String actorKey) {
        if (operation.actorKey != actorKey) {
            return result(403, 'OPERATION_ACTOR_MISMATCH', null, false, [])
        }
        RepairRefresh refreshed = infrastructure.resumeContext(operation)
        if (refreshed?.repairPackage == null) {
            return result(409, 'RECOVERY_CONTEXT_UNAVAILABLE', operation, false, [])
        }
        if (refreshed.targetPermitted != Boolean.TRUE) {
            return result(403, 'TARGET_PERMISSION_REQUIRED', operation, false, [])
        }
        RepairOperation verifying = advance(operation, OperationState.VERIFYING)
        infrastructure.persist(verifying, refreshed.repairPackage)
        finishVerification(verifying, refreshed, infrastructure.verify(refreshed, false))
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
        RepairMutationOutcome mutation
        try {
            mutation = infrastructure.mutate(refresh.repairPackage)
        } catch (RuntimeException ignored) {
            return finish(operation, refresh.repairPackage,
                OperationState.MANUAL_RECOVERY_REQUIRED, 500)
        }
        if (mutation == null) {
            return finish(operation, refresh.repairPackage,
                OperationState.MANUAL_RECOVERY_REQUIRED, 500)
        }
        if (mutation.disposition == MutationDisposition.NOT_APPLIED) {
            return finish(operation, refresh.repairPackage,
                OperationState.MUTATION_FAILED, 500)
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
        int status = operationStatus(existing.state)
        result(status, existing.state.name(), existing, true, [])
    }

    private static RepairOperation advance(RepairOperation operation, OperationState target) {
        if (!TRANSITIONS.get(operation.state)?.contains(target)) {
            throw new IllegalStateException('invalid repair transition')
        }
        operation.copyWith(state: target, history: operation.history + target)
    }

}
