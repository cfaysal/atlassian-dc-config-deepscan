import structuredoctor.Confirmation
import structuredoctor.CoreImpactSimulator
import structuredoctor.CoreProposalPlanner
import structuredoctor.DuplicateAnalysis
import structuredoctor.DuplicateGroup
import structuredoctor.DuplicateOccurrence
import structuredoctor.EvidenceRequirement
import structuredoctor.Finding
import structuredoctor.FindingType
import structuredoctor.GeneratorSnapshot
import structuredoctor.HierarchyLevel
import structuredoctor.HierarchySnapshot
import structuredoctor.ImpactResult
import structuredoctor.IssueRelationSnapshot
import structuredoctor.OccurrenceSnapshot
import structuredoctor.ProposalCandidate
import structuredoctor.ProposalPlan
import structuredoctor.ProposalSource
import structuredoctor.ReadResult
import structuredoctor.RepairKind
import structuredoctor.RepairPackage
import structuredoctor.RepairSelection
import structuredoctor.RepairStrategy
import structuredoctor.SimulationRequest
import structuredoctor.StructureSnapshot

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

HierarchySnapshot hierarchy = new HierarchySnapshot(
    levels: [
        new HierarchyLevel(rank: 2L, levelId: 'upper', name: 'Upper', issueTypeIds: [300L]),
        new HierarchyLevel(rank: 1L, levelId: 'lower', name: 'Lower', issueTypeIds: [200L])
    ],
    fingerprint: 'hierarchy-fp'
)
GeneratorSnapshot generator = new GeneratorSnapshot(
    generatorId: 21L,
    moduleKey: 'synthetic:extender',
    type: 'EXTENDER',
    order: 1,
    enabled: true,
    parameters: [scope: 'synthetic'],
    revision: 'generator-r1',
    complete: true
)
def occurrence = { String id, long issueId, Long parentId, String provenance = 'ADVANCED_ROADMAPS' ->
    new OccurrenceSnapshot(
        occurrenceId: id,
        issueId: issueId,
        rowId: 'row-' + id,
        parentPath: parentId == null ? [] : [parentId],
        parentIssueId: parentId,
        depth: parentId == null ? 0 : 1,
        position: 0,
        provenance: provenance,
        creatorId: provenance == 'PERMANENT' ? null : '21',
        provenanceComplete: true
    )
}
OccurrenceSnapshot keepOne = occurrence('keep-1', 1000L, 2000L)
OccurrenceSnapshot removeOne = occurrence('remove-1', 1000L, 2001L, 'JIRA_LINK')
OccurrenceSnapshot keepTwo = occurrence('keep-2', 1001L, 2000L)
OccurrenceSnapshot removeTwo = occurrence('remove-2', 1001L, 2001L, 'JIRA_LINK')
List<IssueRelationSnapshot> relations = [
    new IssueRelationSnapshot(issueId: 2000L, issueTypeId: 300L,
        nativeParentId: null, leadingParentIds: [], revisions: [issue: 'p1']),
    new IssueRelationSnapshot(issueId: 2001L, issueTypeId: 300L,
        nativeParentId: null, leadingParentIds: [], revisions: [issue: 'p2']),
    new IssueRelationSnapshot(issueId: 1000L, issueTypeId: 200L,
        nativeParentId: 2000L, leadingParentIds: [2000L], revisions: [issue: 'i1']),
    new IssueRelationSnapshot(issueId: 1001L, issueTypeId: 200L,
        nativeParentId: 2000L, leadingParentIds: [2000L], revisions: [issue: 'i2'])
]
StructureSnapshot beforeSnapshot = new StructureSnapshot(
    structureId: 9L,
    revision: 'structure-r1',
    hierarchy: hierarchy,
    generators: [generator],
    occurrences: [keepOne, removeOne, keepTwo, removeTwo],
    relations: relations,
    fingerprint: 'structure-fp-r1',
    complete: true
)
StructureSnapshot afterSnapshot = beforeSnapshot.copyWith(
    revision: 'structure-r2',
    occurrences: [keepOne, keepTwo],
    fingerprint: 'structure-fp-r2')

def duplicateChoice = { OccurrenceSnapshot value, int ordinal ->
    new DuplicateOccurrence(
        occurrenceId: value.occurrenceId,
        retainChoiceId: 'retain:' + value.occurrenceId,
        ordinal: ordinal,
        rowId: value.rowId,
        parentPath: value.parentPath,
        parentIssueId: value.parentIssueId,
        provenance: value.provenance,
        creatorId: value.creatorId,
        hierarchyValid: true,
        nativeHierarchy: ordinal == 1,
        recommended: ordinal == 1,
        explanations: []
    )
}
DuplicateGroup groupOne = new DuplicateGroup(
    id: 'g1', issueId: 1000L,
    occurrences: [duplicateChoice(keepOne, 1), duplicateChoice(removeOne, 2)],
    recommendedOccurrenceId: 'keep-1', selectable: true,
    explanations: [], blockers: [])
DuplicateGroup groupTwo = new DuplicateGroup(
    id: 'g2', issueId: 1001L,
    occurrences: [duplicateChoice(keepTwo, 1), duplicateChoice(removeTwo, 2)],
    recommendedOccurrenceId: 'keep-2', selectable: true,
    explanations: [], blockers: [])
DuplicateAnalysis duplicates = new DuplicateAnalysis(
    groups: [groupOne, groupTwo], findings: [], complete: true, blockers: [])
EvidenceRequirement generatorRequirement = new EvidenceRequirement(
    'structure-generator:21', 'generator-r1', true)

ProposalCandidate sharedGeneratorCandidate = new ProposalCandidate(
    sourceId: 'server-candidate-1',
    strategy: RepairStrategy.RESTRICT_EXTENDER,
    kind: RepairKind.STRUCTURE,
    findingGroupIds: ['g1', 'g2'],
    affectedIssueIds: [1000L, 1001L],
    generatorIds: [21L],
    beforeState: [scope: 'broad'],
    afterState: [scope: 'hierarchy-only'],
    permanentRowIds: [],
    explanation: 'Restrict the overlapping extender',
    warnings: [],
    requirements: [generatorRequirement],
    blockers: []
)
ProposalCandidate jiraCandidate = new ProposalCandidate(
    sourceId: 'server-candidate-2',
    strategy: RepairStrategy.REPAIR_JIRA_PARENT,
    kind: RepairKind.JIRA_DATA,
    findingGroupIds: ['g1'],
    affectedIssueIds: [1000L],
    generatorIds: [],
    beforeState: [parent: null],
    afterState: [parent: 2000L],
    permanentRowIds: [],
    explanation: 'Set the unambiguous native parent',
    warnings: ['Jira data can affect other Structures, plans, and reports'],
    requirements: [new EvidenceRequirement('jira-issue:1000', 'i1', true)],
    blockers: []
)

class FakeProposalSource implements ProposalSource {
    ReadResult<List<ProposalCandidate>> result
    @Override
    ReadResult<List<ProposalCandidate>> readCandidates(StructureSnapshot ignored) { result }
}

CoreProposalPlanner planner = new CoreProposalPlanner()
RepairSelection emptySelection = new RepairSelection(
    findingGroupIds: [], retainOccurrenceByGroup: [:], selectedPermanentRowIds: [])
ProposalPlan emptyPlan = planner.plan(
    beforeSnapshot, duplicates, emptySelection,
    new FakeProposalSource(result: ReadResult.complete([sharedGeneratorCandidate])))
check('nothing selected means nothing planned', emptyPlan.packages, [])

RepairSelection groupOneSelection = new RepairSelection(
    findingGroupIds: ['g1'],
    retainOccurrenceByGroup: [g1: 'keep-1'],
    selectedPermanentRowIds: [])
ProposalPlan sharedPlan = planner.plan(
    beforeSnapshot, duplicates, groupOneSelection,
    new FakeProposalSource(result: ReadResult.complete([sharedGeneratorCandidate])))
check('shared generator proposal stays atomic',
    sharedPlan.packages[0].findingGroupIds, ['g1', 'g2'])
check('Structure package requires explicit confirmation',
    sharedPlan.packages[0].confirmations, [Confirmation.STRUCTURE_CHANGE])
check('server candidate supplies exact after state',
    sharedPlan.packages[0].afterState, [scope: 'hierarchy-only'])
check('package dependencies include the Structure planning fingerprint',
    sharedPlan.packages[0].requirements*.source.contains('structure:9'), true)
check('package dependencies include the Jira hierarchy fingerprint',
    sharedPlan.packages[0].requirements*.source.contains('jira-hierarchy'), true)
check('package dependencies include the generator revision',
    sharedPlan.packages[0].requirements*.source.contains('generator:21'), true)
check('package dependencies include affected Jira issue revisions',
    sharedPlan.packages[0].requirements*.source.containsAll(
        ['jira-issue:1000', 'jira-issue:1001']), true)

ProposalPlan jiraPlan = planner.plan(
    beforeSnapshot, duplicates, groupOneSelection,
    new FakeProposalSource(result: ReadResult.complete([jiraCandidate])))
check('Jira data package needs both confirmations',
    jiraPlan.packages[0].confirmations,
    [Confirmation.STRUCTURE_CHANGE, Confirmation.JIRA_DATA_CHANGE])
check('Jira-wide warning remains visible',
    jiraPlan.packages[0].warnings,
    ['Jira data can affect other Structures, plans, and reports'])

RepairSelection invalidRetainSelection = groupOneSelection.copyWith(
    retainOccurrenceByGroup: [g1: 'forged-occurrence'])
ProposalPlan invalidRetainPlan = planner.plan(
    beforeSnapshot, duplicates, invalidRetainSelection,
    new FakeProposalSource(result: ReadResult.complete([sharedGeneratorCandidate])))
check('forged retain occurrence blocks planning',
    invalidRetainPlan.blockers.contains('invalid-retain-occurrence:g1'), true)

RepairSelection unknownGroupSelection = groupOneSelection.copyWith(
    findingGroupIds: ['unknown'], retainOccurrenceByGroup: [unknown: 'keep-1'])
ProposalPlan unknownGroupPlan = planner.plan(
    beforeSnapshot, duplicates, unknownGroupSelection,
    new FakeProposalSource(result: ReadResult.complete([sharedGeneratorCandidate])))
check('unknown finding group blocks planning',
    unknownGroupPlan.blockers.contains('unknown-finding-group:unknown'), true)
check('browser selection has identifiers only',
    RepairSelection.declaredFields.findAll { !it.synthetic }*.name as Set<String>,
    ['findingGroupIds', 'retainOccurrenceByGroup', 'selectedPermanentRowIds'] as Set<String>)

ProposalPlan failedSourcePlan = planner.plan(
    beforeSnapshot, duplicates, groupOneSelection,
    new FakeProposalSource(result: ReadResult.failed('synthetic source failure')))
check('failed ProposalSource blocks planning',
    failedSourcePlan.blockers.contains('proposal-source'), true)

RepairPackage packageValue = sharedPlan.packages[0]
Map<String, String> currentPackageFingerprints = packageValue.requirements.collectEntries {
    EvidenceRequirement requirement -> [(requirement.source): requirement.fingerprint]
}
Finding duplicateFindingOne = new Finding(
    id: 'g1', type: FindingType.DUPLICATE, issueId: 1000L,
    occurrenceIds: ['keep-1', 'remove-1'], summary: 'duplicate',
    requirements: [generatorRequirement], blockers: [])
Finding duplicateFindingTwo = new Finding(
    id: 'g2', type: FindingType.DUPLICATE, issueId: 1001L,
    occurrenceIds: ['keep-2', 'remove-2'], summary: 'duplicate',
    requirements: [generatorRequirement], blockers: [])

SimulationRequest safeRequest = new SimulationRequest(
    beforeSnapshot: beforeSnapshot,
    afterSnapshot: afterSnapshot,
    repairPackage: packageValue,
    retainOccurrenceByGroup: [g1: 'keep-1', g2: 'keep-2'],
    selectedFindingIds: ['g1', 'g2'],
    approvedNewFindingIds: [],
    beforeFindings: [duplicateFindingOne, duplicateFindingTwo],
    afterFindings: [],
    currentFingerprints: currentPackageFingerprints,
    selectedPermanentRowIds: [],
    capabilityAvailable: true
)
CoreImpactSimulator simulator = new CoreImpactSimulator()
ImpactResult safeImpact = simulator.simulate(safeRequest)
check('complete De-Dupe simulation is safe', safeImpact.safeToApply, true)
check('simulation preserves selected occurrences', safeImpact.unchanged, ['keep-1', 'keep-2'])
check('simulation removes only duplicate occurrences', safeImpact.removed, ['remove-1', 'remove-2'])

ImpactResult retainedLoss = simulator.simulate(safeRequest.copyWith(
    afterSnapshot: afterSnapshot.copyWith(occurrences: [keepTwo])))
check('retained occurrence loss blocks apply', retainedLoss.safeToApply, false)
check('retained occurrence blocker is explicit',
    retainedLoss.blockers.contains('retained-occurrence-lost:keep-1'), true)

ImpactResult incompletePreview = simulator.simulate(safeRequest.copyWith(
    afterSnapshot: afterSnapshot.copyWith(complete: false)))
check('incomplete preview blocks apply', incompletePreview.safeToApply, false)
check('incomplete preview blocker is explicit',
    incompletePreview.blockers.contains('incomplete-preview'), true)

Finding newFinding = duplicateFindingOne.copyWith(id: 'new-finding', issueId: 9999L)
ImpactResult regressionImpact = simulator.simulate(safeRequest.copyWith(
    afterFindings: [newFinding]))
check('new unapproved finding blocks apply', regressionImpact.safeToApply, false)
check('new finding blocker is explicit',
    regressionImpact.blockers.contains('new-unapproved-finding:new-finding'), true)

ImpactResult staleImpact = simulator.simulate(safeRequest.copyWith(
    currentFingerprints: currentPackageFingerprints + ['structure-generator:21': 'generator-r2']))
check('stale dependency blocks apply', staleImpact.safeToApply, false)
check('stale dependency blocker is explicit',
    staleImpact.blockers.contains('stale-dependency:structure-generator:21'), true)

OccurrenceSnapshot permanent = occurrence('permanent-1', 1002L, 2000L, 'PERMANENT')
RepairPackage permanentPackage = packageValue.copyWith(
    strategy: RepairStrategy.REMOVE_PERMANENT_ROW,
    permanentRowIds: ['permanent-1'])
ImpactResult permanentImpact = simulator.simulate(safeRequest.copyWith(
    beforeSnapshot: beforeSnapshot.copyWith(occurrences: beforeSnapshot.occurrences + permanent),
    repairPackage: permanentPackage))
check('unselected permanent-row removal blocks apply', permanentImpact.safeToApply, false)
check('permanent-row blocker is explicit',
    permanentImpact.blockers.contains('permanent-row-not-selected:permanent-1'), true)

ImpactResult unsupportedImpact = simulator.simulate(safeRequest.copyWith(
    capabilityAvailable: false))
check('unsupported capability blocks apply', unsupportedImpact.safeToApply, false)
check('unsupported capability blocker is explicit',
    unsupportedImpact.blockers.contains('repair-capability-unavailable'), true)

Finding independentFinding = duplicateFindingOne.copyWith(
    id: 'independent-finding', issueId: 7777L)
ImpactResult unselectedChangeImpact = simulator.simulate(safeRequest.copyWith(
    beforeFindings: [duplicateFindingOne, duplicateFindingTwo, independentFinding]))
check('unselected independent finding change blocks apply',
    unselectedChangeImpact.safeToApply, false)
check('unselected finding blocker is explicit',
    unselectedChangeImpact.blockers.contains(
        'unselected-finding-changed:independent-finding'), true)

ImpactResult unknownPopulationImpact = simulator.simulate(safeRequest.copyWith(
    afterSnapshot: afterSnapshot.copyWith(occurrences: null)))
check('unknown preview population blocks apply',
    unknownPopulationImpact.safeToApply, false)
check('unknown population blocker is explicit',
    unknownPopulationImpact.blockers.contains('unknown-after-population'), true)

File proposalFixture = new File(System.getProperty('repoRoot', '.'),
    'jira/tests/fixtures/structuredoctor/proposal-scenarios.json')
ok('proposal scenarios fixture exists', proposalFixture.isFile())
if (proposalFixture.isFile()) {
    Map<String, Object> proposalFixtureData = (Map<String, Object>) new groovy.json.JsonSlurper()
        .parse(proposalFixture)
    check('proposal fixture covers safety cases',
        ((List<Map<String, Object>>) proposalFixtureData.scenarios)*.name,
        [
            'competing-plans',
            'shared-generator',
            'jira-data-change',
            'incomplete-preview',
            'unrelated-loss',
            'new-finding-regression'
        ])
}

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
