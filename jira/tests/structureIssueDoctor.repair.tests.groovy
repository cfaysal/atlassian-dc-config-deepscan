import structuredoctor.Confirmation
import structuredoctor.CoreRepairCoordinator
import structuredoctor.DisabledRepairInfrastructure
import structuredoctor.DoctorRepairApplication
import structuredoctor.EvidenceRequirement
import structuredoctor.ImpactResult
import structuredoctor.LiveRepairInfrastructure
import structuredoctor.MutationDisposition
import structuredoctor.OperationState
import structuredoctor.RepairApplyRequest
import structuredoctor.RepairAvailability
import structuredoctor.RepairCapability
import structuredoctor.RepairCoordinatorResult
import structuredoctor.RepairInfrastructure
import structuredoctor.RepairKind
import structuredoctor.RepairLockUnavailableException
import structuredoctor.RepairMutationOutcome
import structuredoctor.RepairOperation
import structuredoctor.RepairPackage
import structuredoctor.RepairRefresh
import structuredoctor.RepairStrategy
import structuredoctor.VerificationDisposition
import structuredoctor.VerificationOutcome

int passed = 0
int failed = 0
List<String> failures = []
def check = { String name, Object actual, Object expected ->
    if (actual == expected) passed++
    else { failed++; failures << (name + '\n     expected: ' + expected + '\n     actual  : ' + actual) }
}
def ok = { String name, boolean condition ->
    if (condition) passed++
    else { failed++; failures << name }
}

Map<String, String> fingerprints = [
    'jira-hierarchy': 'h1', 'automation-rule:77': 'a1',
    'jira-issue:1000': 'i1', 'structure:9': 's1', 'generator:21': 'g1'
]
RepairPackage structurePackage = new RepairPackage(
    id: 'package-structure', kind: RepairKind.STRUCTURE,
    strategy: RepairStrategy.RESTRICT_EXTENDER,
    findingGroupIds: ['group-1'], affectedIssueIds: [1000L], generatorIds: [21L],
    beforeState: [enabled: true], afterState: [enabled: false], permanentRowIds: [],
    explanation: 'Restrict one synthetic extender.', warnings: [],
    confirmations: [Confirmation.STRUCTURE_CHANGE],
    requirements: fingerprints.collect { String source, String value ->
        new EvidenceRequirement(source, value, true)
    }, blockers: [], selectable: true)
ImpactResult safeImpact = new ImpactResult(
    complete: true, safeToApply: true, added: [], removed: ['occ-remove'],
    moved: [], unchanged: ['occ-keep'], blockers: [])

class FakeRepairInfrastructure implements RepairInfrastructure {
    RepairAvailability available = RepairAvailability.enabled()
    Map<String, RepairOperation> journal = [:]
    RepairOperation pending
    RepairOperation racedOperation
    int findCalls
    RepairRefresh refreshed
    MutationDisposition mutationDisposition = MutationDisposition.APPLIED
    MutationDisposition restoreDisposition = MutationDisposition.APPLIED
    List<VerificationDisposition> verification = [VerificationDisposition.VERIFIED]
    String lockFailure
    int mutateCalls
    int restoreCalls
    int verifyCalls
    int refreshCalls
    List<String> order = []
    List<Long> lockedIssueIds = []
    RepairPackage persistedPackage

    RepairAvailability availability() { available }
    RepairOperation find(String operationId) {
        findCalls++
        findCalls > 1 && racedOperation != null ? racedOperation : journal[operationId]
    }
    RepairOperation findPending(long structureId, List<Long> issueIds) { pending }
    Object withLocks(long structureId, List<Long> issueIds, Closure<Object> work) {
        if (lockFailure) throw new RepairLockUnavailableException(lockFailure)
        lockedIssueIds = new ArrayList<>(issueIds ?: [])
        work.call()
    }
    RepairRefresh refresh(RepairApplyRequest request) {
        refreshCalls++
        refreshed
    }
    RepairRefresh resumeContext(RepairOperation operation) { refreshed }
    void persist(RepairOperation operation, RepairPackage repairPackage) {
        order << 'persist:' + operation.state
        journal[operation.operationId] = operation
        persistedPackage = repairPackage
    }
    RepairMutationOutcome mutate(RepairPackage repairPackage) {
        order << 'mutate'
        mutateCalls++
        new RepairMutationOutcome(disposition: mutationDisposition,
            revision: 'mutation-r1', message: 'synthetic mutation')
    }
    VerificationOutcome verify(RepairRefresh context, boolean restored) {
        verifyCalls++
        VerificationDisposition value = verification.size() == 1 ?
            verification[0] : verification.remove(0)
        new VerificationOutcome(disposition: value,
            message: restored ? 'synthetic restore verification' : 'synthetic verification')
    }
    RepairMutationOutcome restore(RepairPackage repairPackage) {
        restoreCalls++
        new RepairMutationOutcome(disposition: restoreDisposition,
            revision: 'restore-r1', message: 'synthetic restore')
    }
}

def requestFor = { String operationId = '00000000-0000-4000-8000-000000000001',
                   RepairPackage repairPackage = structurePackage,
                   Map<String, String> expected = fingerprints ->
    new RepairApplyRequest(
        operationId: operationId, structureId: 9L,
        repairPackageId: repairPackage.id,
        affectedIssueIds: repairPackage.affectedIssueIds,
        actorKey: 'synthetic-admin',
        expectedFingerprints: expected,
        confirmations: [Confirmation.STRUCTURE_CHANGE])
}
def infrastructureFor = {
    FakeRepairInfrastructure fake = new FakeRepairInfrastructure()
    fake.refreshed = new RepairRefresh(
        repairPackage: structurePackage,
        currentFingerprints: fingerprints,
        impact: safeImpact,
        targetPermitted: true)
    fake
}

RepairCoordinatorResult disabled = new CoreRepairCoordinator(
    new DisabledRepairInfrastructure([
        RepairCapability.STRUCTURE_PREVIEW,
        RepairCapability.CLUSTER_JOURNAL
    ])).apply(requestFor())
check('disabled status', disabled.status, 409)
check('disabled code', disabled.code, 'APPLY_UNAVAILABLE')
check('disabled names capabilities only', disabled.blockers,
    ['CLUSTER_JOURNAL', 'STRUCTURE_PREVIEW'])

DoctorRepairApplication defaultRepairApplication = new DoctorRepairApplication()
Map<String, Object> disabledPayload = [
    snapshotId: 'analysis-1', repairPackageId: 'package-1',
    operationId: '00000000-0000-4000-8000-000000000010',
    confirmStructureChange: true, confirmJiraDataChange: false
]
RepairCoordinatorResult defaultDisabled = defaultRepairApplication.apply(
    disabledPayload, 'synthetic-admin')
check('application writer is disabled by default',
    defaultDisabled.code, 'APPLY_UNAVAILABLE')
check('default disabled reports every missing capability class',
    defaultDisabled.blockers.size(), RepairCapability.values().size())
boolean applyInjectionRejected = false
try {
    defaultRepairApplication.apply(disabledPayload + [generatorParameters: [enabled: false]],
        'synthetic-admin')
} catch (IllegalArgumentException ignored) {
    applyInjectionRejected = true
}
ok('Apply rejects browser configuration injection', applyInjectionRejected)

LiveRepairInfrastructure noOperations = new LiveRepairInfrastructure(
    RepairCapability.values().toList(), [:])
ok('declared capabilities without operations stay disabled',
    !noOperations.availability().enabled)
Map<String, Closure<?>> wiredOperations = [
    find: { String ignored -> null },
    findPending: { long ignored, List<Long> ignoredIssues -> null },
    withLocks: { long ignored, List<Long> ignoredIssues, Closure<?> work -> work.call() },
    refresh: { RepairApplyRequest ignored -> null },
    resumeContext: { RepairOperation ignored -> null },
    persist: { RepairOperation ignored, RepairPackage ignoredPackage -> null },
    mutate: { RepairPackage ignored -> null },
    verify: { RepairRefresh ignored, boolean restored -> null },
    restore: { RepairPackage ignored -> null }
]
LiveRepairInfrastructure fullyWired = new LiveRepairInfrastructure(
    RepairCapability.values().toList(), wiredOperations)
ok('live writer enables only with all capability classes and operations',
    fullyWired.availability().enabled)

FakeRepairInfrastructure firstInfra = infrastructureFor()
CoreRepairCoordinator firstCoordinator = new CoreRepairCoordinator(firstInfra)
RepairCoordinatorResult first = firstCoordinator.apply(requestFor())
check('first apply status', first.status, 200)
check('first apply verified', first.operation.state, OperationState.VERIFIED)
check('first apply transitions', first.operation.history, [
    OperationState.ANALYZED, OperationState.PLANNED, OperationState.CONFIRMED,
    OperationState.APPLIED, OperationState.VERIFYING, OperationState.VERIFIED])
check('mutation occurs once', firstInfra.mutateCalls, 1)
check('affected issues are protected under the lock', firstInfra.lockedIssueIds, [1000L])
ok('journal precedes mutation', firstInfra.order.indexOf('persist:CONFIRMED') <
    firstInfra.order.indexOf('mutate'))
check('bounded package is persisted', firstInfra.persistedPackage.id, structurePackage.id)

RepairCoordinatorResult replay = firstCoordinator.apply(requestFor())
check('exact replay returns verified operation', replay.operation.state, OperationState.VERIFIED)
check('exact replay is marked', replay.replayed, true)
check('exact replay does not mutate', firstInfra.mutateCalls, 1)

FakeRepairInfrastructure racedInfra = infrastructureFor()
racedInfra.racedOperation = first.operation
RepairCoordinatorResult racedReplay = new CoreRepairCoordinator(racedInfra).apply(requestFor())
check('operation is reread under the lock', racedReplay.replayed, true)
check('raced replay does not mutate twice', racedInfra.mutateCalls, 0)

RepairCoordinatorResult conflictingReplay = firstCoordinator.apply(
    requestFor('00000000-0000-4000-8000-000000000001', structurePackage,
        fingerprints + ['structure:9': 'different']))
check('conflicting replay rejected', conflictingReplay.code, 'OPERATION_ID_CONFLICT')
check('conflicting replay does not mutate', firstInfra.mutateCalls, 1)

fingerprints.each { String source, String ignored ->
    FakeRepairInfrastructure staleInfra = infrastructureFor()
    Map<String, String> changed = new LinkedHashMap<>(fingerprints)
    changed[source] = 'changed'
    staleInfra.refreshed = staleInfra.refreshed.copyWith(currentFingerprints: changed)
    RepairCoordinatorResult stale = new CoreRepairCoordinator(staleInfra).apply(requestFor())
    check('stale ' + source + ' rejected', stale.code, 'STALE_PLAN')
    ok('stale ' + source + ' named', stale.blockers.contains('stale:' + source))
    check('stale ' + source + ' does not mutate', staleInfra.mutateCalls, 0)
}

['STRUCTURE_LOCK_UNAVAILABLE', 'ISSUE_LOCK_UNAVAILABLE'].each { String lockCode ->
    FakeRepairInfrastructure locked = infrastructureFor()
    locked.lockFailure = lockCode
    RepairCoordinatorResult result = new CoreRepairCoordinator(locked).apply(requestFor())
    check(lockCode + ' rejected', result.code, lockCode)
    check(lockCode + ' does not mutate', locked.mutateCalls, 0)
}

FakeRepairInfrastructure pendingBlockInfra = infrastructureFor()
pendingBlockInfra.pending = first.operation.copyWith(
    operationId: '00000000-0000-4000-8000-000000000099',
    state: OperationState.PENDING,
    history: first.operation.history[0..4] + OperationState.PENDING)
RepairCoordinatorResult pendingBlock = new CoreRepairCoordinator(pendingBlockInfra).apply(
    requestFor('00000000-0000-4000-8000-000000000002'))
check('pending operation blocks new apply', pendingBlock.code, 'PENDING_OPERATION')
check('pending operation prevents mutation', pendingBlockInfra.mutateCalls, 0)

FakeRepairInfrastructure pendingInfra = infrastructureFor()
pendingInfra.verification = [VerificationDisposition.PENDING]
CoreRepairCoordinator pendingCoordinator = new CoreRepairCoordinator(pendingInfra)
RepairCoordinatorResult pending = pendingCoordinator.apply(
    requestFor('00000000-0000-4000-8000-000000000003'))
check('timeout is pending', pending.status, 202)
check('pending state', pending.operation.state, OperationState.PENDING)
pendingInfra.verification = [VerificationDisposition.VERIFIED]
RepairCoordinatorResult pendingStatus = pendingCoordinator.status(
    pending.operation.operationId, 'synthetic-admin')
check('GET-compatible status leaves pending state unchanged', pendingStatus.operation.state,
    OperationState.PENDING)
check('status does not resume verification', pendingInfra.verifyCalls, 1)
RepairCoordinatorResult resumed = pendingCoordinator.apply(requestFor(
    pending.operation.operationId))
check('pending resumes to verified', resumed.operation.state, OperationState.VERIFIED)
check('pending resume transitions', resumed.operation.history[-2..-1],
    [OperationState.VERIFYING, OperationState.VERIFIED])

FakeRepairInfrastructure resumeRaceInfra = infrastructureFor()
resumeRaceInfra.journal[pending.operation.operationId] = pending.operation
resumeRaceInfra.racedOperation = resumed.operation
CoreRepairCoordinator resumeRaceCoordinator = new CoreRepairCoordinator(resumeRaceInfra)
RepairCoordinatorResult resumeRace = resumeRaceCoordinator.resume(
    pending.operation.operationId, 'synthetic-admin')
check('pending operation is reread under the lock', resumeRace.operation.state,
    OperationState.VERIFIED)
check('concurrent pending resume is a replay', resumeRace.replayed, true)
check('concurrent pending resume does not verify twice', resumeRaceInfra.verifyCalls, 0)

FakeRepairInfrastructure deniedInfra = infrastructureFor()
deniedInfra.refreshed = deniedInfra.refreshed.copyWith(targetPermitted: false)
RepairCoordinatorResult denied = new CoreRepairCoordinator(deniedInfra).apply(
    requestFor('00000000-0000-4000-8000-000000000010'))
check('missing target permission returns forbidden', denied.status, 403)
check('missing target permission is explicit', denied.code, 'TARGET_PERMISSION_REQUIRED')
check('missing target permission prevents mutation', deniedInfra.mutateCalls, 0)

FakeRepairInfrastructure unavailableRefreshInfra = infrastructureFor()
unavailableRefreshInfra.refreshed = null
RepairCoordinatorResult unavailableRefresh = new CoreRepairCoordinator(
    unavailableRefreshInfra).apply(
        requestFor('00000000-0000-4000-8000-000000000011'))
check('unavailable refresh is stale rather than a permission claim',
    unavailableRefresh.code, 'STALE_PLAN')
ok('unavailable refresh names its evidence blocker',
    unavailableRefresh.blockers.contains('repair-refresh'))
check('unavailable refresh prevents mutation', unavailableRefreshInfra.mutateCalls, 0)

FakeRepairInfrastructure mismatchInfra = infrastructureFor()
mismatchInfra.verification = [VerificationDisposition.MISMATCH, VerificationDisposition.VERIFIED]
RepairCoordinatorResult rolledBack = new CoreRepairCoordinator(mismatchInfra).apply(
    requestFor('00000000-0000-4000-8000-000000000004'))
check('proven mismatch rolls back', rolledBack.operation.state, OperationState.ROLLED_BACK)
check('rollback called once', mismatchInfra.restoreCalls, 1)
check('rolled-back status remains non-success',
    new CoreRepairCoordinator(mismatchInfra).status(
        rolledBack.operation.operationId, 'synthetic-admin').status, 409)
check('rolled-back replay remains non-success',
    new CoreRepairCoordinator(mismatchInfra).apply(requestFor(
        rolledBack.operation.operationId)).status, 409)

FakeRepairInfrastructure partialInfra = infrastructureFor()
partialInfra.mutationDisposition = MutationDisposition.PARTIAL
partialInfra.verification = [VerificationDisposition.VERIFIED]
RepairCoordinatorResult partial = new CoreRepairCoordinator(partialInfra).apply(
    requestFor('00000000-0000-4000-8000-000000000005'))
check('partial mutation is rolled back when proven', partial.operation.state, OperationState.ROLLED_BACK)

FakeRepairInfrastructure manualInfra = infrastructureFor()
manualInfra.verification = [VerificationDisposition.MISMATCH]
manualInfra.restoreDisposition = MutationDisposition.PARTIAL
RepairCoordinatorResult manual = new CoreRepairCoordinator(manualInfra).apply(
    requestFor('00000000-0000-4000-8000-000000000006'))
check('unverifiable restore needs manual recovery',
    manual.operation.state, OperationState.MANUAL_RECOVERY_REQUIRED)
check('manual-recovery status remains server failure',
    new CoreRepairCoordinator(manualInfra).status(
        manual.operation.operationId, 'synthetic-admin').status, 500)

RepairPackage jiraPackage = structurePackage.copyWith(
    id: 'package-jira', kind: RepairKind.JIRA_DATA,
    confirmations: [Confirmation.STRUCTURE_CHANGE, Confirmation.JIRA_DATA_CHANGE])
FakeRepairInfrastructure jiraInfra = infrastructureFor()
jiraInfra.refreshed = jiraInfra.refreshed.copyWith(repairPackage: jiraPackage)
RepairCoordinatorResult missingJiraConfirmation = new CoreRepairCoordinator(jiraInfra).apply(
    requestFor('00000000-0000-4000-8000-000000000007', jiraPackage))
check('Jira data requires second confirmation',
    missingJiraConfirmation.code, 'CONFIRMATION_REQUIRED')
check('missing Jira confirmation does not mutate', jiraInfra.mutateCalls, 0)

FakeRepairInfrastructure noStructureConfirmationInfra = infrastructureFor()
RepairCoordinatorResult noStructureConfirmation = new CoreRepairCoordinator(
    noStructureConfirmationInfra).apply(requestFor(
        '00000000-0000-4000-8000-000000000008').copyWith(confirmations: []))
check('Structure repair requires explicit confirmation',
    noStructureConfirmation.code, 'CONFIRMATION_REQUIRED')
check('missing Structure confirmation does not mutate',
    noStructureConfirmationInfra.mutateCalls, 0)

FakeRepairInfrastructure confirmedJiraInfra = infrastructureFor()
confirmedJiraInfra.refreshed = confirmedJiraInfra.refreshed.copyWith(
    repairPackage: jiraPackage)
RepairCoordinatorResult confirmedJira = new CoreRepairCoordinator(
    confirmedJiraInfra).apply(requestFor(
        '00000000-0000-4000-8000-000000000009', jiraPackage).copyWith(
            confirmations: [Confirmation.STRUCTURE_CHANGE,
                            Confirmation.JIRA_DATA_CHANGE]))
check('both confirmations allow Jira package',
    confirmedJira.operation.state, OperationState.VERIFIED)

Set<List<OperationState>> allowed = [
    [OperationState.ANALYZED, OperationState.PLANNED],
    [OperationState.PLANNED, OperationState.CONFIRMED],
    [OperationState.CONFIRMED, OperationState.APPLIED],
    [OperationState.APPLIED, OperationState.VERIFYING],
    [OperationState.VERIFYING, OperationState.VERIFIED],
    [OperationState.VERIFYING, OperationState.PENDING],
    [OperationState.VERIFYING, OperationState.ROLLED_BACK],
    [OperationState.VERIFYING, OperationState.MANUAL_RECOVERY_REQUIRED],
    [OperationState.PENDING, OperationState.VERIFYING]
] as Set<List<OperationState>>
[first.operation, pending.operation, resumed.operation, rolledBack.operation,
 partial.operation, manual.operation].each { RepairOperation operation ->
    operation.history.collate(2, 1, false).each { List<OperationState> transition ->
        ok('allowed transition ' + transition, allowed.contains(transition))
    }
}

File repository = new File(System.getProperty('repoRoot', '.')).canonicalFile
['CoreRepairCoordinator.groovy', 'LiveRepairInfrastructure.groovy',
 'DoctorRepairApplication.groovy'].each { String name ->
    String source = new File(repository, 'jira/structuredoctor/' + name).getText('UTF-8')
    ok(name + ' has no global hierarchy mutation dependency',
        !source.contains('HierarchyProvider') && !source.contains('HierarchyMutation'))
    ok(name + ' has no Automation mutation dependency',
        !source.contains('AutomationDataProvider') && !source.contains('AutomationMutation'))
}

println "Structure Doctor repair tests: ${passed} passed, ${failed} failed"
if (!failures.isEmpty()) {
    failures.each { println '\nFAIL: ' + it }
    System.exit(1)
}
