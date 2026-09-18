import structuredoctor.CausalContext
import structuredoctor.CausalEdge
import structuredoctor.CausalEdgeType
import structuredoctor.CausalClaim
import structuredoctor.CoreCausalityEngine
import structuredoctor.Coverage
import structuredoctor.EvidenceGrade
import structuredoctor.EvidenceRequirement

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

def edge = { CausalEdgeType type, boolean present, boolean direct = false ->
    new CausalEdge(
        id: 'edge-' + type.name().toLowerCase(),
        type: type,
        fromId: 'from-' + type.name().toLowerCase(),
        toId: 'to-' + type.name().toLowerCase(),
        evidenceId: present ? 'evidence-' + type.name().toLowerCase() : null,
        present: present,
        direct: direct
    )
}

List<CausalEdgeType> chainTypes = [
    CausalEdgeType.AUTOMATION_TO_JIRA_DATA,
    CausalEdgeType.JIRA_DATA_TO_GENERATOR,
    CausalEdgeType.GENERATOR_TO_OCCURRENCE,
    CausalEdgeType.OCCURRENCE_TO_FINDING
]
Coverage fullAudit = Coverage.bounded(30L, 30L, false, '2026-08-20', '2026-09-18')
Coverage partialAudit = Coverage.bounded(30L, 7L, true, '2026-09-12', '2026-09-18')
EvidenceRequirement ruleRequirement = new EvidenceRequirement(
    'automation-rule:541', 'rule-541-r1', true)

def context = { List<CausalEdge> edges, boolean configurationConflict,
                Coverage auditCoverage, List<String> blockers = [] ->
    new CausalContext(
        findingId: 'finding-1',
        edges: edges,
        configurationConflict: configurationConflict,
        auditCoverage: auditCoverage,
        requirements: [ruleRequirement],
        blockers: blockers
    )
}

CoreCausalityEngine engine = new CoreCausalityEngine()
CausalClaim configurationClaim = engine.claim(context(
    chainTypes.collect { CausalEdgeType type -> edge(type, false) },
    true,
    null))
check('configuration-only conflict grade',
    configurationClaim.grade, EvidenceGrade.CONFIGURATION_CONFLICT)

CausalClaim possibleClaim = engine.claim(context([
    edge(CausalEdgeType.AUTOMATION_TO_JIRA_DATA, true, false),
    edge(CausalEdgeType.JIRA_DATA_TO_GENERATOR, false),
    edge(CausalEdgeType.GENERATOR_TO_OCCURRENCE, true),
    edge(CausalEdgeType.OCCURRENCE_TO_FINDING, true)
], false, partialAudit))
check('static partial chain is possible', possibleClaim.grade, EvidenceGrade.POSSIBLE_CAUSE)
ok('missing edge stays visible',
    possibleClaim.missingEvidence.contains('JIRA_DATA_TO_GENERATOR'))

CausalClaim probableClaim = engine.claim(context(
    chainTypes.collect { CausalEdgeType type -> edge(type, true, false) },
    false,
    partialAudit))
check('aligned chain without direct execution is probable',
    probableClaim.grade, EvidenceGrade.PROBABLE_CAUSE)

List<CausalEdge> confirmedEdges = chainTypes.collect { CausalEdgeType type ->
    edge(type, true, type == CausalEdgeType.AUTOMATION_TO_JIRA_DATA)
}
CausalClaim confirmedClaim = engine.claim(context(
    confirmedEdges, false, fullAudit))
check('direct execution plus complete chain is confirmed',
    confirmedClaim.grade, EvidenceGrade.CONFIRMED_CAUSE)

CausalClaim staticCompleteClaim = engine.claim(context(
    chainTypes.collect { CausalEdgeType type -> edge(type, true, false) },
    false,
    fullAudit))
ok('static rule matching never confirms a cause',
    staticCompleteClaim.grade != EvidenceGrade.CONFIRMED_CAUSE)

CausalClaim incompleteAuditClaim = engine.claim(context(
    confirmedEdges, false, partialAudit, ['automation-audit-coverage']))
check('partial audit cannot confirm direct-looking chain',
    incompleteAuditClaim.grade, EvidenceGrade.PROBABLE_CAUSE)
check('claim preserves blockers',
    incompleteAuditClaim.blockers, ['automation-audit-coverage'])
check('claim preserves exact coverage',
    incompleteAuditClaim.auditCoverage.actual, 7L)
check('claim preserves requirements',
    incompleteAuditClaim.requirements*.source, ['automation-rule:541'])
check('claim includes exactly the bounded edge chain',
    confirmedClaim.edgeIds, confirmedEdges*.id)

CausalClaim repeatedConfirmedClaim = engine.claim(context(
    confirmedEdges, false, fullAudit))
check('causal claim ID is deterministic',
    repeatedConfirmedClaim.id, confirmedClaim.id)

boolean duplicateEdgeRejected = false
try {
    engine.claim(context(confirmedEdges + confirmedEdges[0], false, fullAudit))
} catch (IllegalArgumentException expected) {
    duplicateEdgeRejected = true
}
ok('duplicate causal edge type is rejected', duplicateEdgeRejected)

File causalFixture = new File(System.getProperty('repoRoot', '.'),
    'jira/tests/fixtures/structuredoctor/causality-scenarios.json')
ok('causality scenarios fixture exists', causalFixture.isFile())
if (causalFixture.isFile()) {
    Map<String, Object> causalFixtureData = (Map<String, Object>) new groovy.json.JsonSlurper()
        .parse(causalFixture)
    check('causality fixture covers every evidence grade',
        ((List<Map<String, Object>>) causalFixtureData.scenarios)*.grade,
        ['CONFIGURATION_CONFLICT', 'POSSIBLE_CAUSE', 'PROBABLE_CAUSE', 'CONFIRMED_CAUSE'])
}

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
