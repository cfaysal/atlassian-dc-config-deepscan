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
File renderer = new File(repository, 'jira/structuredoctor/DoctorRenderer.groovy')

ok('secured baseline exists', endpoint.isFile())

if (endpoint.isFile()) {
    String source = endpoint.getText('UTF-8')

    check('one page declaration',
        source.findAll(/(?m)^structureIssueDoctor\(httpMethod: 'GET'/).size(), 1)
    check('one Structure-wide analyze declaration',
        source.findAll(/(?m)^structureIssueDoctorAnalyze\(httpMethod: 'POST'/).size(), 1)
    check('one Structure-wide plan declaration',
        source.findAll(/(?m)^structureIssueDoctorPlan\(httpMethod: 'POST'/).size(), 1)
    check('one transactional Apply declaration',
        source.findAll(/(?m)^structureIssueDoctorApply\(httpMethod: 'POST'/).size(), 1)
    check('one operation Status declaration',
        source.findAll(/(?m)^structureIssueDoctorStatus\(httpMethod: 'GET'/).size(), 1)
    check('one fix declaration',
        source.findAll(/(?m)^structureIssueDoctorFix\(httpMethod: 'POST'/).size(), 1)
    check('all declarations are administrator-only',
        source.findAll(/groups: \["jira-administrators"\]/).size(), 6)
    ok('anonymous declaration is absent', !source.contains('groups: []'))
    ok('handlers reject missing authentication',
        source.findAll(/if \(user == null\)/).size() >= 6)
    ok('legacy confirmation is frozen',
        source.contains("final String FIX_CONFIRMATION = 'SET_PARENT_LINK'"))

    ok('HTML delegates to Jira-free CoreSupport', source.contains('CoreSupport.html(value)'))
    ok('JSON conversion delegates to Jira-free CoreSupport', source.contains('CoreSupport.jsonSafe(value)'))
    ok('query parsing delegates to Jira-free CoreSupport',
        source.contains('CoreSupport.queryValue(queryParams, name)'))

    ok('Analyze requests use a strict server-side parser',
        source.contains('DoctorApplication.parseAnalyzeRequest'))
    ok('Plan requests use a strict server-side parser',
        source.contains('DoctorApplication.parsePlanRequest'))
    ok('invalid Structure IDs are explicit', source.contains("error: 'INVALID_STRUCTURE_ID'"))
    ok('Structure-wide analysis does not require an issue key',
        !source.contains('STRUCTURE_AND_ISSUE_REQUIRED') && renderer.isFile() &&
            renderer.getText('UTF-8').contains('Work-Item-Key (optional)'))
    ok('new Core Apply is visibly disabled',
        renderer.isFile() && renderer.getText('UTF-8').contains('Apply is disabled'))

    ok('global Jira hierarchy is read through the proven Roadmaps API',
        source.contains('com.atlassian.rm.portfolio.publicapi.hierarchy.ExportedHierarchyLevelApi') &&
            source.contains('hierarchyPage(hierarchyApi, (int) count)'))
    ok('Roadmaps hierarchy service uses the proven plugin-bundle fallback',
        source.contains("getAllServiceReferences") &&
            source.contains("getService") &&
            source.contains("ungetService") &&
            source ==~ /(?s).*withPluginService\(\s*ROADMAPS_PLUGIN,\s*HIERARCHY_API.*/)
    ok('hierarchy service helper does not collide with the public Groovy method',
        source.contains('readHierarchyService(hierarchyApi)') &&
            !source.contains(
                'private static ReadResult<HierarchySnapshot> readHierarchy('))
    ok('Structure-wide analysis has a live forest reader',
        source.contains('LiveStructureGateway doctorStructureGateway') &&
            source.contains('DoctorLiveAccess.readStructure'))
    ok('Structure-wide analysis has a live Jira relationship reader',
        source.contains('LiveJiraGateway doctorJira') &&
            source.contains('DoctorLiveAccess.readIssues'))
    ok('unresolved configured parent values make Jira relationship evidence incomplete',
        source.contains('hasParentValue(rawParentValue) && resolved.isEmpty()'))
    ok('hierarchy reader contains no hierarchy mutation invocation',
        !['create', 'update', 'delete'].any { String verb ->
            source.contains("invokeMethod(hierarchyApi, '${verb}'")
        })
}

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
