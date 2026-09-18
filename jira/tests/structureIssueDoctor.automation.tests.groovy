import structuredoctor.AnalysisScope
import structuredoctor.AuditRequest
import structuredoctor.AutomationAnalysis
import structuredoctor.AutomationAnalysisContext
import structuredoctor.AutomationAuditSnapshot
import structuredoctor.AutomationConflictType
import structuredoctor.AutomationRuleSnapshot
import structuredoctor.CoreAutomationAnalyzer
import structuredoctor.Coverage
import structuredoctor.HierarchyLevel
import structuredoctor.HierarchySnapshot
import structuredoctor.JsonAutomationProvider
import structuredoctor.ReadResult
import structuredoctor.ReadState

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

File fixtureRoot = new File(System.getProperty('repoRoot', '.'),
    'jira/tests/fixtures/structuredoctor')
byte[] validRules = new File(fixtureRoot, 'automation-rules-valid.json').bytes
byte[] partialAudit = new File(fixtureRoot, 'automation-audit-partial.json').bytes
AnalysisScope selectedScope = new AnalysisScope(
    projectIds: [101L],
    issueTypeIds: [200L],
    fieldIds: ['field:parent', 'field:source-a', 'field:source-b'],
    linkTypeIds: [900L]
)

JsonAutomationProvider provider = new JsonAutomationProvider(validRules, partialAudit, 65536)
ReadResult<List<AutomationRuleSnapshot>> ruleRead = provider.readRules(selectedScope)
check('valid rule export is complete', ruleRead.state, ReadState.COMPLETE)
check('valid export normalizes two enabled rules', ruleRead.value*.ruleId, [541L, 562L])
check('rule target writes are normalized', ruleRead.value[0].writes, ['field:parent'])
check('source-to-target mapping is normalized',
    ruleRead.value[0].sourcesByTarget, ['field:parent': ['field:source-a']])
check('ordered components are preserved',
    ruleRead.value[0].orderedComponents, ['TRIGGER', 'CONDITION', 'ACTION'])
AnalysisScope unrelatedScope = selectedScope.copyWith(projectIds: [999L])
check('rule provider filters non-overlapping scope',
    provider.readRules(unrelatedScope).value, [])

ReadResult<List<AutomationAuditSnapshot>> auditRead = provider.readAudit(
    new AuditRequest(ruleIds: [541L, 562L], issueIds: [1000L], requestedDays: 30))
check('partial audit export stays incomplete', auditRead.state, ReadState.INCOMPLETE)
check('partial audit requested coverage', auditRead.coverage.requested, 30L)
check('partial audit actual coverage', auditRead.coverage.actual, 7L)
check('partial audit keeps matching entries', auditRead.value*.ruleId, [541L])

byte[] unknownVersion = '{"exportVersion":"99","rules":[]}'.getBytes('UTF-8')
check('unknown export version is rejected',
    new JsonAutomationProvider(unknownVersion, null, 1024).readRules(selectedScope).state,
    ReadState.FAILED)
check('invalid JSON is rejected',
    new JsonAutomationProvider('{'.getBytes('UTF-8'), null, 1024)
        .readRules(selectedScope).state,
    ReadState.FAILED)
check('missing rules field is rejected',
    new JsonAutomationProvider('{"exportVersion":"1"}'.getBytes('UTF-8'), null, 1024)
        .readRules(selectedScope).state,
    ReadState.FAILED)
check('unknown top-level key is rejected',
    new JsonAutomationProvider(
        '{"exportVersion":"1","rules":[],"execute":true}'.getBytes('UTF-8'), null, 1024)
        .readRules(selectedScope).state,
    ReadState.FAILED)
check('oversized rule export is rejected',
    new JsonAutomationProvider(new byte[1025], null, 1024)
        .readRules(selectedScope).state,
    ReadState.FAILED)

byte[] officialRules = new File(fixtureRoot, 'automation-rules-official.json').bytes
ReadResult<List<AutomationRuleSnapshot>> officialRuleRead =
    new JsonAutomationProvider(officialRules, null, 65536).readRules(selectedScope)
check('official Jira Automation export is accepted',
    officialRuleRead.state, ReadState.COMPLETE)
check('official export keeps rule identity', officialRuleRead.value*.ruleId, [9001L])
check('official export normalizes trigger fields without executing scripts',
    officialRuleRead.value[0].reads, ['field:customfield_10001', 'link:900'])
check('opaque script action makes rule evidence incomplete',
    officialRuleRead.value[0].complete, false)
check('official export preserves component order',
    officialRuleRead.value[0].orderedComponents,
    ['TRIGGER', 'BRANCH', 'CONDITION', 'ACTION'])

byte[] cappedAudit = '''{
  "exportVersion":"1",
  "requestedDays":30,
  "actualDays":30,
  "capped":true,
  "fromInclusive":"2026-08-20",
  "toInclusive":"2026-09-18",
  "entries":[]
}'''.getBytes('UTF-8')
check('capped audit export is incomplete',
    new JsonAutomationProvider(validRules, cappedAudit, 65536).readAudit(
        new AuditRequest(ruleIds: [], issueIds: [], requestedDays: 30)).state,
    ReadState.INCOMPLETE)

def automationRule = { long id, List<String> reads, List<String> writes,
                       List<String> clears, Map<String, List<String>> sources,
                       boolean asynchronous, boolean allowRuleTrigger,
                       List<Long> issueTypeIds = [200L] ->
    new AutomationRuleSnapshot(
        ruleId: id,
        enabled: true,
        projectIds: [101L],
        issueTypeIds: issueTypeIds,
        reads: reads,
        writes: writes,
        clears: clears,
        sourcesByTarget: sources,
        trigger: 'ISSUE_UPDATED',
        orderedComponents: ['TRIGGER', 'ACTION'],
        conditions: [],
        asynchronous: asynchronous,
        allowOtherRuleTrigger: allowRuleTrigger,
        actor: 'AUTOMATION',
        revision: 'r-' + id,
        exportVersion: '1',
        complete: true
    )
}

AutomationRuleSnapshot writerA = automationRule(
    541L, ['field:source-a'], ['field:parent'], [],
    ['field:parent': ['field:source-a']], true, true)
AutomationRuleSnapshot writerB = automationRule(
    562L, ['field:source-b'], ['field:parent'], ['field:parent'],
    ['field:parent': ['field:source-b']], false, false)
AutomationRuleSnapshot chainReader = automationRule(
    600L, ['field:parent'], ['link:900'], [],
    ['link:900': ['field:parent']], false, true)
AutomationRuleSnapshot invalidTypeWriter = automationRule(
    700L, ['field:source-a'], ['field:parent'], [],
    ['field:parent': ['field:source-a']], false, false, [200L, 999L])

HierarchySnapshot hierarchy = new HierarchySnapshot(
    levels: [
        new HierarchyLevel(rank: 2L, levelId: 'upper', name: 'Upper', issueTypeIds: [300L]),
        new HierarchyLevel(rank: 1L, levelId: 'lower', name: 'Lower', issueTypeIds: [200L])
    ],
    fingerprint: 'automation-hierarchy-fingerprint'
)
AutomationAnalysisContext context = new AutomationAnalysisContext(
    scope: selectedScope,
    hierarchy: hierarchy,
    hierarchyTargets: ['field:parent'],
    structureConsumedValues: ['field:parent', 'link:900']
)
ReadResult<List<AutomationRuleSnapshot>> analyzerRules = ReadResult.complete(
    [writerA, writerB, chainReader, invalidTypeWriter])
AutomationAnalysis analysis = new CoreAutomationAnalyzer().analyze(
    analyzerRules, auditRead, context)

check('multiple overlapping writers are detected',
    analysis.findings.findAll { it.type == AutomationConflictType.MULTIPLE_WRITERS }.size(), 1)
check('conflicting source fields are detected',
    analysis.findings.findAll { it.type == AutomationConflictType.CONFLICTING_SOURCES }.size(), 1)
check('clear-on-empty risk is detected',
    analysis.findings.findAll { it.type == AutomationConflictType.CLEAR_ON_EMPTY }.size(), 1)
check('asynchronous last-writer race is detected',
    analysis.findings.findAll { it.type == AutomationConflictType.ASYNCHRONOUS_RACE }.size(), 1)
check('possible rule chain is detected',
    analysis.findAll(AutomationConflictType.POSSIBLE_CHAIN).size(), 1)
check('hierarchy-invalid target issue type is detected',
    analysis.findAll(AutomationConflictType.INVALID_TARGET_LEVEL).size(), 1)
ok('Structure-consumed relation writes are detected',
    analysis.findAll(AutomationConflictType.STRUCTURE_CONSUMED_WRITE).size() >= 1)
check('partial audit coverage keeps analysis incomplete', analysis.complete, false)
check('partial audit blocker is explicit',
    analysis.blockers.contains('automation-audit-coverage'), true)

ReadResult<List<AutomationAuditSnapshot>> fullEmptyAudit = ReadResult.complete(
    [], Coverage.bounded(30L, 30L, false, '2026-08-20', '2026-09-18'))
AutomationAnalysis officialAnalysis = new CoreAutomationAnalyzer().analyze(
    officialRuleRead, fullEmptyAudit, context)
check('incompletely normalized official rule remains visible',
    officialAnalysis.rules*.ruleId, [9001L])
check('opaque official action keeps analysis incomplete',
    officialAnalysis.complete, false)
check('opaque official action has explicit blocker',
    officialAnalysis.blockers.contains('automation-rule-normalization'), true)

Set<String> providerMethods = JsonAutomationProvider.declaredMethods*.name as Set<String>
ok('JSON provider exposes readRules', providerMethods.contains('readRules'))
ok('JSON provider exposes readAudit', providerMethods.contains('readAudit'))
ok('JSON provider exposes no Automation mutation method',
    providerMethods.intersect([
        'writeRule', 'updateRule', 'deleteRule', 'enableRule',
        'disableRule', 'importRule', 'publishRule'
    ] as Set<String>).isEmpty())

File providerSource = new File(System.getProperty('repoRoot', '.'),
    'jira/structuredoctor/JsonAutomationProvider.groovy')
if (providerSource.isFile()) {
    String providerText = providerSource.getText('UTF-8')
    ok('JSON provider source contains no Automation write verbs',
        !(providerText =~ /(?i)\b(updateRule|deleteRule|enableRule|disableRule|importRule|publishRule)\b/).find())
}

String officialFixtureText = new File(fixtureRoot, 'automation-rules-official.json')
    .getText('UTF-8')
ok('official fixture proves script payload is treated as inert data',
    officialFixtureText.contains('throw new IllegalStateException'))

['automationRuleExport1', 'automationRuleExport2'].eachWithIndex {
    String propertyName, int index ->
        String exportPath = System.getProperty(propertyName)
        if (exportPath != null && !exportPath.trim().isEmpty()) {
            ReadResult<List<AutomationRuleSnapshot>> actualExport =
                new JsonAutomationProvider(new File(exportPath).bytes, null, 1048576)
                    .readRules(selectedScope.copyWith(
                        projectIds: [], issueTypeIds: [], fieldIds: [], linkTypeIds: []))
            check('authorized official export ' + (index + 1) + ' parses',
                actualExport.state, ReadState.COMPLETE)
            ok('authorized official export ' + (index + 1) + ' contains rules',
                actualExport.value != null && !actualExport.value.isEmpty())
            println 'AUTHORIZED_EXPORT_' + (index + 1) +
                ' state=' + actualExport.state +
                ' rules=' + (actualExport.value?.size() ?: 0) +
                ' completeRules=' + (actualExport.value?.count {
                    AutomationRuleSnapshot value -> value.complete
                } ?: 0)
        }
}

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
