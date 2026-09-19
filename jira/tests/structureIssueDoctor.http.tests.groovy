import structuredoctor.DoctorBoundaryException
import structuredoctor.DoctorAutomationEvidence
import structuredoctor.DoctorHttpDecision
import structuredoctor.DoctorHttpGuard

int passed = 0
int failed = 0
List<String> failures = []

def check = { String name, Object actual, Object expected ->
    if (actual == expected) passed++
    else {
        failed++
        failures << (name + '\n     expected: ' + expected + '\n     actual  : ' + actual)
    }
}
def ok = { String name, boolean condition ->
    if (condition) passed++
    else { failed++; failures << name }
}

DoctorHttpDecision json = DoctorHttpGuard.requireJson(
    'application/json; charset=UTF-8', '{"structureId":9}')
check('JSON request is accepted', json.allowed, true)
check('accepted JSON has no response status', json.status, 0)

DoctorHttpDecision wrongType = DoctorHttpGuard.requireJson(
    'application/x-www-form-urlencoded', 'structureId=9')
check('form request is rejected', wrongType.status, 415)
check('wrong media type is explicit', wrongType.error, 'UNSUPPORTED_MEDIA_TYPE')

DoctorHttpDecision missingType = DoctorHttpGuard.requireJson(null, '{}')
check('missing media type is rejected', missingType.status, 415)

String oversized = 'x' * (DoctorHttpGuard.MAX_JSON_BYTES + 1)
DoctorHttpDecision tooLarge = DoctorHttpGuard.requireJson('application/json', oversized)
check('oversized request is rejected', tooLarge.status, 413)
check('oversized request is explicit', tooLarge.error, 'REQUEST_TOO_LARGE')

DoctorHttpDecision exactLimit = DoctorHttpGuard.requireJson(
    'APPLICATION/JSON', 'x' * DoctorHttpGuard.MAX_JSON_BYTES)
check('exact request limit is accepted', exactLimit.allowed, true)

DoctorHttpDecision analyzeLimit = DoctorHttpGuard.requireJson(
    'application/json', 'x' * (DoctorHttpGuard.MAX_JSON_BYTES + 1),
    DoctorHttpGuard.MAX_ANALYZE_JSON_BYTES)
check('Analyze accepts a bounded export payload above the normal limit',
    analyzeLimit.allowed, true)
DoctorHttpDecision oversizedAnalyze = DoctorHttpGuard.requireJson(
    'application/json', 'x' * (DoctorHttpGuard.MAX_ANALYZE_JSON_BYTES + 1),
    DoctorHttpGuard.MAX_ANALYZE_JSON_BYTES)
check('Analyze export payload remains bounded', oversizedAnalyze.status, 413)
ok('Analyze envelope fits two worst-case quoted 5 MiB export strings',
    DoctorHttpGuard.MAX_ANALYZE_JSON_BYTES >=
        (4 * DoctorAutomationEvidence.MAX_EXPORT_BYTES) + 131_072)

DoctorHttpDecision queryKeys = DoctorHttpGuard.requireQueryKeys(
    ['operationId', 'unexpected'], ['operationId'])
check('unknown query key is rejected', queryKeys.status, 400)
check('unknown query key is explicit', queryKeys.error, 'UNSUPPORTED_QUERY_PARAMETER')

DoctorBoundaryException invalidJson
try { DoctorHttpGuard.parseJsonObject('{broken') }
catch (DoctorBoundaryException failure) { invalidJson = failure }
check('invalid JSON returns bad request', invalidJson?.status, 400)
check('invalid JSON error is bounded', invalidJson?.code, 'INVALID_JSON')

DoctorBoundaryException nonObjectJson
try { DoctorHttpGuard.parseJsonObject('[1,2,3]') }
catch (DoctorBoundaryException failure) { nonObjectJson = failure }
check('non-object JSON returns bad request', nonObjectJson?.status, 400)

File root = new File(System.getProperty('repoRoot', '.')).canonicalFile
String controller = new File(root, 'jira/structureIssueDoctor.groovy').getText('UTF-8')
String renderer = new File(root, 'jira/structuredoctor/DoctorRenderer.groovy').getText('UTF-8')
String contracts = new File(root, 'jira/structuredoctor/DoctorContracts.groovy').getText('UTF-8')
List<Map<String, String>> endpoints = [
    [name: 'structureIssueDoctor', method: 'GET'],
    [name: 'structureIssueDoctorAnalyze', method: 'POST'],
    [name: 'structureIssueDoctorPlan', method: 'POST'],
    [name: 'structureIssueDoctorApply', method: 'POST'],
    [name: 'structureIssueDoctorStatus', method: 'GET'],
    [name: 'structureIssueDoctorFix', method: 'POST']
]

def section = { String endpoint ->
    int start = controller.indexOf(endpoint + '(httpMethod:')
    if (start < 0) return ''
    int end = controller.length()
    endpoints.each { Map<String, String> candidate ->
        int next = controller.indexOf(candidate.name + '(httpMethod:', start + endpoint.length())
        if (next > start && next < end) end = next
    }
    controller.substring(start, end)
}

endpoints.each { Map<String, String> endpoint ->
    String declaration = endpoint.name + "(httpMethod: '${endpoint.method}', " +
        'groups: ["jira-administrators"])'
    check(endpoint.name + ' has exactly one administrator declaration',
        controller.count(declaration), 1)
    String body = section(endpoint.name)
    ok(endpoint.name + ' explicitly rejects a missing user',
        body.contains('getLoggedInUser()') && body.contains('AUTHENTICATION_REQUIRED'))
    ok(endpoint.name + ' does not declare an empty group list',
        !body.contains('groups: []'))
}

['structureIssueDoctorAnalyze', 'structureIssueDoctorPlan',
 'structureIssueDoctorApply', 'structureIssueDoctorFix'].each { String endpoint ->
    String body = section(endpoint)
    ok(endpoint + ' accepts the request object for media-type enforcement',
        body.substring(0, Math.min(body.length(), 300)).contains('Object httpRequest'))
    ok(endpoint + ' enforces the JSON request boundary',
        body.contains('requireJsonRequest.call'))
    ok(endpoint + ' rejects query-string configuration',
        body.contains('requireQueryKeys.call(queryParams, [])'))
}

['structureIssueDoctor', 'structureIssueDoctorStatus'].each { String endpoint ->
    String body = section(endpoint)
    ok(endpoint + ' GET route does not invoke a target mutator',
        !['.apply(', '.resume(', '.repair(', '.mutate(', 'issueService.update('].any {
            String token -> body.contains(token)
        })
}

ok('browser does not bypass Atlassian request-token protection',
    !renderer.contains('X-Atlassian-Token') && !controller.contains('X-Atlassian-Token'))
ok('controller serializes JSON only with JsonOutput',
    controller.contains('JsonOutput.toJson(payload)'))
ok('responses are private and non-sniffable',
    controller.contains("['Cache-Control', 'no-store, private']") &&
        controller.contains("['X-Content-Type-Options', 'nosniff']"))
ok('controller has no anonymous Doctor declaration',
    !controller.contains('structureIssueDoctor(httpMethod: \'GET\')'))
ok('browser submits no executable configuration keys',
    !['jql', 'generatorParameters', 'fieldValue', 'ruleConfiguration'].any {
        String key -> renderer.contains(key)
    })
ok('controller never logs JQL',
    !controller.readLines().any { String line ->
        line.toLowerCase().contains('log.') && line.toLowerCase().contains('jql')
    })
String hierarchyContract = contracts.substring(
    contracts.indexOf('interface HierarchyProvider'),
    contracts.indexOf('interface StructureSnapshotProvider'))
String automationContract = contracts.substring(
    contracts.indexOf('interface AutomationDataProvider'),
    contracts.indexOf('interface ProposalSource'))
List<String> writeVerbs = ['save', 'update', 'delete', 'mutate', 'write']
ok('Jira hierarchy contract is read-only',
    !writeVerbs.any { String verb -> hierarchyContract.contains(verb) })
ok('Automation contract is read-only',
    !writeVerbs.any { String verb -> automationContract.contains(verb) })

println "Structure Doctor HTTP tests: ${passed} passed, ${failed} failed"
if (!failures.isEmpty()) {
    failures.each { println '\nFAIL: ' + it }
    System.exit(1)
}
