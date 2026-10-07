import groovy.transform.TypeChecked
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer

// Check the shipped overview helpers against their actual platform-free
// dependencies. Loading those dependencies dynamically keeps unrelated existing
// helper code outside this gate; no dependency signatures are stubbed.
if (args.length != 1) {
    throw new IllegalArgumentException('Usage: config-overview-typecheck.groovy <configuration-endpoint>')
}
File endpoint = new File(args[0])
String source = endpoint.getText('UTF-8')
int dependencyStart = source.indexOf('class Pc {')
int banner = source.indexOf(' * END OF THE ', dependencyStart)
int dependencyEnd = source.lastIndexOf('/*', banner)
int helperStart = source.indexOf('class ConfigOverview {', dependencyStart)
int helperEnd = source.indexOf('/* What a remark read', helperStart)
if (dependencyStart < 0 || banner < 0 || dependencyEnd <= dependencyStart ||
    helperStart < dependencyStart || helperEnd <= helperStart || helperEnd > dependencyEnd) {
    throw new IllegalArgumentException('Configuration helper extraction markers are missing or out of order.')
}
String imports = '''
import groovy.json.JsonOutput
import org.codehaus.groovy.runtime.InvokerHelper
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.lang.reflect.Method
'''
GroovyClassLoader dependencies = new GroovyClassLoader(getClass().classLoader)
dependencies.parseClass(imports + source.substring(dependencyStart, dependencyEnd), 'ConfigurationDependencies.groovy')

CompilerConfiguration config = new CompilerConfiguration()
config.sourceEncoding = 'UTF-8'
config.tolerance = 1000
config.addCompilationCustomizers(new ASTTransformationCustomizer(TypeChecked))
GroovyClassLoader checked = new GroovyClassLoader(dependencies, config)
CompilationUnit unit = new CompilationUnit(config, null, checked)
// Preserve original endpoint line numbers in diagnostics.
unit.addSource(endpoint.name, '\n' * source.substring(0, helperStart).count('\n') + source.substring(helperStart, helperEnd))
unit.compile(Phases.INSTRUCTION_SELECTION)
println 'OVERVIEW TYPECHECK CLEAN  ' + endpoint
