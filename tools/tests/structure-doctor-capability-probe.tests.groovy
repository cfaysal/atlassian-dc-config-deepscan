import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.control.CompilePhase

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
File probe = new File(repository, 'tools/structure-doctor-capability-probe.groovy')

ok('capability probe exists', probe.isFile())

if (probe.isFile()) {
    String source = probe.getText('UTF-8')
    List<Object> nodes = new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, source)
    List<String> methodNames = nodes
        .findAll { Object node -> node instanceof ClassNode }
        .collectMany { ClassNode node -> node.methods*.name }
        .unique()
        .sort()

    List<String> writePrefixes = ['set', 'update', 'save', 'delete', 'create', 'enable', 'disable', 'publish']
    List<String> forbiddenMethods = methodNames.findAll { String name ->
        writePrefixes.any { String prefix -> name.toLowerCase(Locale.ROOT).startsWith(prefix) }
    }

    ok('probe declares no write-like method', forbiddenMethods.isEmpty())
    ok('probe reports unavailable reads', source.contains('UNAVAILABLE'))
    ok('probe reports failed reads', source.contains('FAILED'))
    ok('probe reports empty signature matches', source.contains('NO_MATCHING_METHODS'))
    ok('probe does not import HTTP clients', !source.matches('(?s).*import\\s+(java\\.net|groovyx\\.net|org\\.apache\\.http).*'))
}

println 'PASSED: ' + passed
println 'FAILED: ' + failed
failures.each { String failure -> println '  FAIL ' + failure }
if (failed > 0) {
    System.exit(1)
}
println 'ALL TESTS PASSED'
