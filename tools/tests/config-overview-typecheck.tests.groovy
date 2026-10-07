import org.codehaus.groovy.control.MultipleCompilationErrorsException

// Exercise the gate's refusal path as well as the clean endpoint checks in CI.
File repo = new File(System.getProperty('repoRoot', '.'))
File checker = new File(repo, 'tools/config-overview-typecheck.groovy')
String source = new File(repo, 'confluence/confluenceDCspaceConfig.groovy').getText('UTF-8')
File broken = File.createTempFile('overview-typecheck-', '.groovy')
try {
    String original = 'String name = (String) marks.get(0).name'
    assert source.contains(original) : 'Regression injection point missing'
    assert source.contains('byte[] decoded =') : 'Decoder injection point missing'
    broken.setText(source.replace(original, 'Object name = marks.get(0).name')
        .replace('byte[] decoded =', 'def decoded ='), 'UTF-8')
    boolean refused = false
    try {
        new GroovyShell(new Binding([args: [broken.absolutePath] as String[]])).evaluate(checker)
    } catch (MultipleCompilationErrorsException error) {
        assert error.message.contains('[Static type checking]') : error.message
        assert error.message.contains('java.lang.String#<init>(java.lang.Object, java.lang.String)') : error.message
        refused = true
    }
    assert refused : 'The gate accepted the original Object-to-String regression'
    broken.setText(source.replace('class ConfigOverview {', 'class RenamedOverview {'), 'UTF-8')
    boolean missingRefused = false
    try {
        new GroovyShell(new Binding([args: [broken.absolutePath] as String[]])).evaluate(checker)
    } catch (IllegalArgumentException error) {
        assert error.message.contains('extraction markers') : error.message
        missingRefused = true
    }
    assert missingRefused : 'The gate silently skipped missing overview helpers'
    println 'OVERVIEW TYPECHECK GATE: regression and missing helpers refused'
} finally {
    assert broken.delete() : 'Could not remove temporary regression source'
}
