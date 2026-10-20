// Run from repository root: groovy jira/tests/dashboardFilterEditor.tests.groovy
import groovy.json.JsonOutput

def source = new File('jira/dashboardFilterEditor.groovy').getText('UTF-8')
def loader = new GroovyClassLoader(getClass().classLoader)
loader.parseClass(source.substring(source.indexOf('class DashboardFilterSupport {'), source.indexOf('// Jira/ScriptRunner bridges')))
def support = loader.loadClass('DashboardFilterSupport')
int checks = 0
def check = { boolean ok -> assert ok; checks++ }
check(support.references([filterId:'120', projectOrFilterId:'filter-121', filter:'120', filterIds:'122, 123']) == [120L,121L,122L,123L] as Set)
check(support.references([projectOrFilterId:'project-123', refresh:'15', arbitraryPluginId:'456']).isEmpty())
check(support.references([filterId:'']).isEmpty())
for (String invalid : ['-1', '0', '1 OR 1=1', '9223372036854775808']) {
    try { support.id(invalid); assert false } catch (IllegalArgumentException expected) { checks++ }
}
check(support.html('</textarea><script>"&') == '&lt;/textarea&gt;&lt;script&gt;&quot;&amp;')
int writes = 0
int parses = 0
def current = new Expando(query: 'project = OLD', name: 'Shared filter', owner: 'other-owner', permissions: ['group'])
current.setQuery = { q -> current.query = q }
def run = { Map input, boolean valid = true, boolean validationError = false, boolean exists = true ->
    support.save(input, { id -> exists ? current : null }, { jql -> parses++; [valid:valid, query:jql, errors:'Invalid JQL'] },
        { query -> [hasAnyErrors: { -> validationError }, toString: { -> 'Query validation failed' }] as Object },
        { filter -> writes++ }, { query -> query as String })
}
def input = [filterId:'120', originalJql:'project = OLD', jql:'project = NEW']
check(run(input + [originalJql:'outdated']).status == 409)
check(writes == 0 && parses == 0 && current.query == 'project = OLD')
check(run(input, false).status == 400)
check(writes == 0 && current.query == 'project = OLD')
check(run(input, true, true).status == 400)
check(writes == 0 && current.query == 'project = OLD')
check(run(input, true, false, false).status == 404)
check(writes == 0)
for (def bad : ['', null, 123, 'x' * 32001]) {
    try { run(input + [jql:bad]); assert false } catch (IllegalArgumentException expected) { checks++ }
}
check(writes == 0)
check(run(input).status == 200)
check(writes == 1 && current.query == 'project = NEW')
check(current.name == 'Shared filter' && current.owner == 'other-owner' && current.permissions == ['group'])
check(run(input).status == 409 && writes == 1)
println "Dashboard filter editor: ${checks} checks passed"

// Query.toString() is a debug representation, not JQL. Never strip braces from user data.
class DebugQueryFixture {
    String jql
    String toString() { '{debug clause: ' + jql + '}' }
}
def oldQuery = new DebugQueryFixture(jql:'project = OLD')
def nextQuery = new DebugQueryFixture(jql:'project = NEW AND summary ~ "{literal}"')
def savedFilter = new Expando(query:oldQuery)
savedFilter.setQuery = { q -> savedFilter.query = q }
int persisted = 0
def serialized = support.save(
    [filterId:'120',originalJql:'project = OLD',jql:nextQuery.jql],
    { Long id -> savedFilter },
    { String text -> [valid:true,query:nextQuery] },
    { query -> new Expando(hasAnyErrors:{ -> false }) },
    { filter -> persisted++ },
    { query -> query.jql })
assert serialized.status == 200 && persisted == 1
assert serialized.jql == 'project = NEW AND summary ~ "{literal}"'
assert !serialized.jql.contains('debug clause')
println 'PASS: serialization uses JQL for conflict checks and saved output; literal braces stay intact'
