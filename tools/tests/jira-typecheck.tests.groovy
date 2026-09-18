int passed = 0
int failed = 0
List<String> failures = []

def ok = { String name, boolean condition ->
    if (condition) {
        passed++
    } else {
        failed++
        failures << name
    }
}

File repository = new File(System.getProperty('repoRoot', '.')).canonicalFile
File script = new File(repository, 'tools/jira-typecheck.jsh')

ok('Jira typecheck exists', script.isFile())

if (script.isFile()) {
    String source = script.getText('UTF-8')
    ['target', 'jiraInstall', 'jiraSharedHome', 'groovyJar', 'groovyJsonJar'].each { String property ->
        ok('requires property ' + property, source.contains('System.getProperty("' + property + '")'))
    }
    ok('does not embed Jira install path', !source.contains('/opt/atlassian/jira'))
    ok('does not embed Jira shared-home path', !source.contains('/var/atlassian/application-data/jira'))
    ok('compiles through instruction selection', source.contains('cu.compile(Phases.INSTRUCTION_SELECTION)'))
    ok('prints classpath count', source.contains('classpath jars:'))
    ok('prints target', source.contains('target:'))
    ok('wraps compilation in one JShell invocation', source.contains('void runTypecheck()'))
    ok('tracks compile failure for the JShell command', source.contains('typecheckExitCode = 1'))
    ok('JShell exits with the tracked result', source.contains('/exit typecheckExitCode'))
    ok('clean result follows compilation inside the invocation',
        source.indexOf('cu.compile(Phases.INSTRUCTION_SELECTION)') < source.indexOf('TYPECHECK CLEAN'))
}

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
