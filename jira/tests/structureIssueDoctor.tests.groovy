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

File repository = new File(System.getProperty('repoRoot', '.')).canonicalFile
File endpoint = new File(repository, 'jira/structureIssueDoctor.groovy')

ok('secured baseline exists', endpoint.isFile())

if (endpoint.isFile()) {
    String source = endpoint.getText('UTF-8')

    check('one analyze declaration',
        source.findAll(/(?m)^structureIssueDoctor\(httpMethod: 'GET'/).size(), 1)
    check('one fix declaration',
        source.findAll(/(?m)^structureIssueDoctorFix\(httpMethod: 'POST'/).size(), 1)
    check('both declarations are administrator-only',
        source.findAll(/groups: \["jira-administrators"\]/).size(), 2)
    ok('anonymous declaration is absent', !source.contains('groups: []'))
    ok('handlers reject missing authentication',
        source.findAll(/if \(user == null\)/).size() >= 2)
    ok('legacy confirmation is frozen',
        source.contains("final String FIX_CONFIRMATION = 'SET_PARENT_LINK'"))

    ok('HTML delegates to Jira-free CoreSupport', source.contains('CoreSupport.html(value)'))
    ok('JSON conversion delegates to Jira-free CoreSupport', source.contains('CoreSupport.jsonSafe(value)'))
    ok('query parsing delegates to Jira-free CoreSupport',
        source.contains('CoreSupport.queryValue(queryParams, name)'))

    ok('Structure IDs are parsed as long values', source.contains('Long.valueOf(structureIdText)'))
    ok('invalid Structure IDs are explicit', source.contains("error: 'INVALID_STRUCTURE_ID'"))
    ok('legacy analysis still requires both identifiers',
        source.contains('if (structureId == null || !issueKey)'))
}

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
