import structuredoctor.Coverage
import structuredoctor.CoreSupport
import structuredoctor.AnalysisScope
import structuredoctor.AuditRequest
import structuredoctor.AutomationAuditSnapshot
import structuredoctor.AutomationDataProvider
import structuredoctor.AutomationRuleSnapshot
import structuredoctor.CausalClaim
import structuredoctor.DoctorClock
import structuredoctor.EvidenceRequirement
import structuredoctor.EvidenceGrade
import structuredoctor.Finding
import structuredoctor.FindingType
import structuredoctor.GeneratorMutation
import structuredoctor.GeneratorSnapshot
import structuredoctor.HierarchyLevel
import structuredoctor.HierarchyProvider
import structuredoctor.HierarchySnapshot
import structuredoctor.ImpactResult
import structuredoctor.IssueRelationSnapshot
import structuredoctor.JiraDataMutation
import structuredoctor.JiraDataMutationGateway
import structuredoctor.JiraDataProvider
import structuredoctor.MutationReceipt
import structuredoctor.OccurrenceSnapshot
import structuredoctor.OperationState
import structuredoctor.ReadResult
import structuredoctor.ReadState
import structuredoctor.RepairJournal
import structuredoctor.RepairKind
import structuredoctor.RepairOperation
import structuredoctor.RepairPackage
import structuredoctor.StructureLock
import structuredoctor.StructureMutationGateway
import structuredoctor.StructureSnapshot
import structuredoctor.StructureSnapshotProvider

int passed = 0
int failed = 0
List<String> failures = []

def check = { String name, Object actual, Object expected ->
    if (actual == expected) {
        passed++
    } else {
        failed++
        failures << (name + '\n     expected: ' + expected + '\n     actual  : ' + actual)
    }
}

def ok = { String name, boolean condition ->
    if (condition) {
        passed++
    } else {
        failed++
        failures << name
    }
}

ReadResult<List<String>> empty = ReadResult.complete([])
ReadResult<List<String>> incomplete = ReadResult.incomplete(['one'], 'row cap reached')
ReadResult<List<String>> failedRead = ReadResult.failed('forest read failed')
ReadResult<List<String>> unavailable = ReadResult.unavailable('automation audit')

check('complete empty state', empty.state, ReadState.COMPLETE)
check('complete empty value', empty.value, [])
check('incomplete state', incomplete.state, ReadState.INCOMPLETE)
check('incomplete preserves partial value', incomplete.value, ['one'])
check('incomplete reason', incomplete.reason, 'row cap reached')
check('failed state', failedRead.state, ReadState.FAILED)
check('failed reason', failedRead.reason, 'forest read failed')
check('unavailable state', unavailable.state, ReadState.UNAVAILABLE)
check('unavailable reason', unavailable.reason, 'automation audit')
ok('failed differs from empty', failedRead.state != empty.state)
ok('unavailable differs from failed', unavailable.state != failedRead.state)
check('complete predicate', empty.complete(), true)
check('incomplete predicate', incomplete.complete(), false)

Coverage fullCoverage = Coverage.bounded(30L, 30L, false, '2026-08-20', '2026-09-18')
Coverage partialCoverage = Coverage.bounded(30L, 7L, true, '2026-09-12', '2026-09-18')
check('full coverage is complete', fullCoverage.complete(), true)
check('partial coverage is incomplete', partialCoverage.complete(), false)
check('partial coverage keeps requested count', partialCoverage.requested, 30L)
check('partial coverage keeps actual count', partialCoverage.actual, 7L)

EvidenceRequirement requirement = new EvidenceRequirement('jira-hierarchy', 'abc123', true)
check('requirement source', requirement.source, 'jira-hierarchy')
check('requirement fingerprint', requirement.fingerprint, 'abc123')
check('requirement required', requirement.required, true)

boolean blankReasonRejected = false
try {
    ReadResult.failed('   ')
} catch (IllegalArgumentException expected) {
    blankReasonRejected = true
}
ok('blank failure reason is rejected', blankReasonRejected)

boolean invalidCoverageRejected = false
try {
    Coverage.bounded(5L, 6L, false, null, null)
} catch (IllegalArgumentException expected) {
    invalidCoverageRejected = true
}
ok('actual coverage cannot exceed requested coverage', invalidCoverageRejected)

check('HTML null', CoreSupport.html(null), '')
check('HTML escapes all markup', CoreSupport.html('<a href="x">&\'</a>'),
    '&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;')
check('HTML escapes ampersand first', CoreSupport.html('&lt;'), '&amp;lt;')

Map<String, Object> safeMap = (Map<String, Object>) CoreSupport.jsonSafe([
    number: 7,
    nested: [true, 'value'],
    array: ['a', 'b'] as Object[],
    other: new File('text')
])
check('JSON map number', safeMap.number, 7)
check('JSON iterable', safeMap.nested, [true, 'value'])
check('JSON array', safeMap.array, ['a', 'b'])
check('JSON other becomes text', safeMap.other, 'text')

class FakeQueryParams {
    Map<String, Object> values = [:]
    Object getFirst(String name) { values.get(name) }
}

FakeQueryParams queryParams = new FakeQueryParams(values: [structureId: ' 123 '])
check('query value trims', CoreSupport.queryValue(queryParams, 'structureId'), '123')
check('query value missing', CoreSupport.queryValue(queryParams, 'missing'), null)
check('query value null container', CoreSupport.queryValue(null, 'structureId'), null)

HierarchyLevel topLevel = new HierarchyLevel(
    rank: 3L,
    levelId: 'level-3',
    name: 'Goal',
    issueTypeIds: [10001L]
)
HierarchySnapshot hierarchy = new HierarchySnapshot(
    levels: [topLevel],
    fingerprint: 'hierarchy-fingerprint'
)
GeneratorSnapshot generator = new GeneratorSnapshot(
    generatorId: 11L,
    moduleKey: 'synthetic:inserter',
    type: 'INSERTER',
    order: 0,
    enabled: true,
    parameters: [scope: 'synthetic'],
    revision: 'g1',
    complete: true
)
OccurrenceSnapshot occurrence = new OccurrenceSnapshot(
    occurrenceId: 'occ-1',
    issueId: 10001L,
    rowId: 'row-1',
    parentPath: [20001L],
    parentIssueId: 20001L,
    depth: 1,
    position: 0,
    provenance: 'generator-11',
    creatorId: '11',
    provenanceComplete: true
)
IssueRelationSnapshot relation = new IssueRelationSnapshot(
    issueId: 10001L,
    issueTypeId: 10002L,
    nativeParentId: 20001L,
    leadingParentIds: [20001L],
    revisions: [issue: 'i1']
)
StructureSnapshot snapshot = new StructureSnapshot(
    structureId: 1L,
    revision: 's1',
    hierarchy: hierarchy,
    generators: [generator],
    occurrences: [occurrence],
    relations: [relation],
    fingerprint: 'snapshot-fingerprint',
    complete: true
)

check('snapshot keeps Structure identity', snapshot.structureId, 1L)
check('snapshot keeps hierarchy', snapshot.hierarchy.fingerprint, 'hierarchy-fingerprint')
check('occurrence keeps physical row', snapshot.occurrences[0].rowId, 'row-1')

boolean immutableList = false
try {
    topLevel.issueTypeIds.add(99999L)
} catch (UnsupportedOperationException expected) {
    immutableList = true
}
ok('hierarchy issue types are immutable', immutableList)

AutomationRuleSnapshot rule = new AutomationRuleSnapshot(
    ruleId: 541L,
    enabled: true,
    projectIds: [101L],
    issueTypeIds: [10002L],
    reads: ['source-field'],
    writes: ['parent-field'],
    revision: 'rule-1',
    complete: true
)
AutomationAuditSnapshot audit = new AutomationAuditSnapshot(
    ruleId: 541L,
    issueId: 10001L,
    occurredAt: '2026-09-18T10:00:00Z',
    action: 'FIELD_WRITE',
    revision: 'audit-1'
)
AnalysisScope scope = new AnalysisScope(
    projectIds: [101L],
    issueTypeIds: [10002L],
    fieldIds: ['customfield_10001'],
    linkTypeIds: [10000L]
)
check('rule keeps writes', rule.writes, ['parent-field'])
check('audit keeps issue', audit.issueId, 10001L)
check('scope keeps field IDs', scope.fieldIds, ['customfield_10001'])

Finding finding = new Finding(
    id: 'finding-1',
    type: FindingType.MISSING_PARENT,
    issueId: 10001L,
    occurrenceIds: ['occ-1'],
    summary: 'Synthetic missing parent',
    requirements: [requirement],
    blockers: []
)
CausalClaim claim = new CausalClaim(
    id: 'claim-1',
    findingId: finding.id,
    grade: EvidenceGrade.POSSIBLE_CAUSE,
    edgeIds: ['edge-1'],
    presentEvidence: ['rule'],
    missingEvidence: ['audit']
)
check('finding type', finding.type, FindingType.MISSING_PARENT)
check('claim grade', claim.grade, EvidenceGrade.POSSIBLE_CAUSE)

RepairPackage repairPackage = new RepairPackage(
    id: 'package-1',
    kind: RepairKind.STRUCTURE,
    findingGroupIds: ['finding-1'],
    affectedIssueIds: [10001L],
    generatorIds: [11L],
    beforeState: [enabled: true],
    afterState: [enabled: false],
    confirmations: ['CONFIRM_STRUCTURE_CHANGE'],
    requirements: [requirement],
    selectable: true
)
ImpactResult impact = new ImpactResult(
    complete: true,
    safeToApply: true,
    added: [],
    removed: ['occ-2'],
    moved: [],
    unchanged: ['occ-1'],
    blockers: []
)
RepairOperation operation = new RepairOperation(
    operationId: '00000000-0000-4000-8000-000000000001',
    structureId: 1L,
    repairPackageId: repairPackage.id,
    fingerprint: 'snapshot-fingerprint',
    actorKey: 'synthetic-admin',
    state: OperationState.PLANNED,
    history: [OperationState.ANALYZED, OperationState.PLANNED]
)
check('repair kind', repairPackage.kind, RepairKind.STRUCTURE)
check('impact removal', impact.removed, ['occ-2'])
check('operation state', operation.state, OperationState.PLANNED)

GeneratorMutation generatorMutation = new GeneratorMutation(
    generatorIds: [11L],
    beforeState: [enabled: true],
    afterState: [enabled: false]
)
JiraDataMutation jiraMutation = new JiraDataMutation(
    issueId: 10001L,
    fieldId: 'customfield_10001',
    beforeValue: null,
    afterValue: 'PARENT-1'
)
MutationReceipt receipt = new MutationReceipt(applied: true, revision: 's2', message: 'applied')
check('generator mutation IDs', generatorMutation.generatorIds, [11L])
check('Jira mutation issue', jiraMutation.issueId, 10001L)
check('receipt revision', receipt.revision, 's2')

Set<String> hierarchyMethods = HierarchyProvider.declaredMethods*.name as Set<String>
Set<String> automationMethods = AutomationDataProvider.declaredMethods*.name as Set<String>
check('hierarchy contract is read-only', hierarchyMethods, ['readHierarchy'] as Set<String>)
check('Automation contract is read-only', automationMethods, ['readRules', 'readAudit'] as Set<String>)
check('Structure snapshot contract is read-only',
    StructureSnapshotProvider.declaredMethods*.name as Set<String>, ['readStructure'] as Set<String>)
check('Jira data contract is read-only',
    JiraDataProvider.declaredMethods*.name as Set<String>, ['readIssues'] as Set<String>)
ok('mutable gateways are separate from hierarchy',
    !StructureMutationGateway.isAssignableFrom(HierarchyProvider))
ok('mutable gateways are separate from Automation',
    !JiraDataMutationGateway.isAssignableFrom(AutomationDataProvider))
check('Structure mutation contract',
    StructureMutationGateway.declaredMethods*.name as Set<String>,
    ['applyGeneratorPackage', 'restoreGeneratorPackage'] as Set<String>)
check('Jira mutation contract',
    JiraDataMutationGateway.declaredMethods*.name as Set<String>,
    ['applyIssueData', 'restoreIssueData'] as Set<String>)
check('journal contract', RepairJournal.declaredMethods*.name as Set<String>,
    ['find', 'write'] as Set<String>)
check('lock contract', StructureLock.declaredMethods*.name as Set<String>,
    ['withLock'] as Set<String>)
check('clock contract', DoctorClock.declaredMethods*.name as Set<String>,
    ['now'] as Set<String>)

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
