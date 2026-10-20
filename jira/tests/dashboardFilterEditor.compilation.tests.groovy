// Full-script compilation under a static checker, with minimal platform boundary stubs.
// These stubs test ScriptRunner DSL/type-check integration, not actual Jira API compatibility.
import groovy.transform.TypeChecked
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer

def platform = new GroovyClassLoader(getClass().classLoader)
[
    'com.atlassian.jira.component.ComponentAccessor': 'class ComponentAccessor { static Object jiraAuthenticationContext; static Object globalPermissionManager; static Object ofBizDelegator; static Map components = [:]; static Object getComponent(Class type) { components[type] } }',
    'com.atlassian.query.Query': 'interface Query {}',
    'com.atlassian.jira.issue.search.SearchRequest': 'class SearchRequest {}',
    'com.atlassian.jira.bc.issue.search.SearchService': 'interface SearchService { String getJqlString(com.atlassian.query.Query query); Object parseQuery(Object user, String jql); Object validateQuery(Object user, com.atlassian.query.Query query) }',
    'com.adaptavist.hapi.jira.users.Users': 'class Users { static Object loggedInUser; static Object getLoggedInUser() { loggedInUser } }',
    'com.atlassian.jira.issue.search.SearchRequestManager': 'interface SearchRequestManager { Object getSearchRequestById(Long id); Object update(Object user, SearchRequest filter) }',
    'com.atlassian.jira.permission.GlobalPermissionKey': 'class GlobalPermissionKey { static final Object ADMINISTER = new Object() }',
    'com.atlassian.beehive.ClusterLockService': 'interface ClusterLockService {}',
    'jakarta.ws.rs.core.MultivaluedMap': 'interface MultivaluedMap<K,V> { V getFirst(K key) }',
    'jakarta.servlet.http.HttpSession': 'interface HttpSession { Object getAttribute(String key); void setAttribute(String key, Object value) }',
    'jakarta.servlet.http.HttpServletRequest': 'interface HttpServletRequest { HttpSession getSession(boolean create); String getHeader(String key); String getContentType() }',
    'jakarta.ws.rs.core.Response': '''class Response {
        static Builder status(int code) { new Builder(code:code) }
        static class Builder {
            int code; Object content
            Builder type(String value) { this }
            Builder header(String key, String value) { this }
            Builder entity(Object value) { content = value; this }
            Map build() { [status:code, entity:content] }
        }
    }''',
    'com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate': '''
abstract class CustomEndpointDelegate extends Script {
    final Map<String, Map> routes = [:]
    Object methodMissing(String name, Object args) {
        Object[] values = (Object[]) args
        routes[name] = [config: values[0], handler: values[1]]
        null
    }
}'''
].each { name, code ->
    String pkg = name.substring(0, name.lastIndexOf('.'))
    platform.parseClass('package ' + pkg + '\n' + code)
}
def config = new CompilerConfiguration()
config.addCompilationCustomizers(new ASTTransformationCustomizer(TypeChecked))
def checked = new GroovyClassLoader(platform, config)
String path = args ? args[0] : 'jira/dashboardFilterEditor.groovy'
Class scriptType = checked.parseClass(new File(path))
def script = scriptType.getDeclaredConstructor().newInstance()
script.run()
assert script.routes.keySet() == ['dashboardFilterEditor', 'dashboardFilterEditorSave'] as Set
assert script.routes.dashboardFilterEditor.config.httpMethod == 'GET'
assert script.routes.dashboardFilterEditorSave.config.httpMethod == 'POST'
script.routes.each { name, route ->
    assert route.config.groups == ['jira-administrators']
    assert route.handler.maximumNumberOfParameters == 3
}
println 'PASS: complete script compiles with TypeChecked and registers both admin routes'

Class live = checked.loadClass('DashboardFilterLive')
String html = live.page('test-token')
assert html.contains('Speichern \u00e4ndert')
assert html.contains('Filterpr\u00e4ferenzen')
assert !html.contains('\\u00e4')
assert new File(path).bytes.every { (it & 0xff) < 128 }
Class accessor = platform.loadClass('com.atlassian.jira.component.ComponentAccessor')
platform.loadClass('com.adaptavist.hapi.jira.users.Users').loggedInUser = [key:'test-admin']
accessor.globalPermissionManager = [hasPermission: { key, user -> true }]
Map attributes = [:]
def session = [getAttribute: { key -> attributes[key] }, setAttribute: { key, value -> attributes[key] = value }].asType(platform.loadClass('jakarta.servlet.http.HttpSession'))
def request = [getSession: { boolean create -> session }].asType(platform.loadClass('jakarta.servlet.http.HttpServletRequest'))
def params = [getFirst: { String key -> null }].asType(platform.loadClass('jakarta.ws.rs.core.MultivaluedMap'))
def rendered = script.routes.dashboardFilterEditor.handler.call(params, '', request)
assert rendered.status == 200
assert rendered.entity.contains('const csrf = \'')
assert attributes['dashboardFilterEditor.csrf']
assert rendered.entity.contains(attributes['dashboardFilterEditor.csrf'])
accessor.globalPermissionManager = [hasPermission: { key, user -> false }]
assert script.routes.dashboardFilterEditor.handler.call(params, '', request).status == 403
assert script.routes.dashboardFilterEditorSave.handler.call(params, '{}', request).status == 403
println 'PASS: umlauts render correctly; GET creates CSRF session; both routes reject non-admins'

// Compile the exact JSON-to-Map boundary and save call with checking enabled.
String endpointSource = new File(path).getText('UTF-8')
assert endpointSource.contains('Map input = (Map) parsedInput')
String start = endpointSource.substring(endpointSource.indexOf('        Object parsedInput'), endpointSource.indexOf("        if (params.getFirst('action') == 'validate')"))
Class boundary = checked.parseClass("""
import groovy.json.JsonSlurper
class DashboardSaveBoundaryCheck {
    static Map execute(String body, Closure read, Closure parse, Closure validate, Closure persist) {
${start}
        return DashboardFilterSupport.save(input, read, parse, validate, persist, { Object query -> query.toString() })
    }
}
""")
assert boundary.execute('{"filterId":"1","originalJql":"old","jql":"new"}', { Long id -> null }, { String jql -> [:] }, { q -> null }, { f -> null }).status == 404
try {
    boundary.execute('[]', { -> }, { -> }, { -> }, { -> })
    assert false: 'JSON arrays must be rejected before the save call'
} catch (IllegalArgumentException expected) { }
println 'PASS: exact JSON boundary and save signature compile with checking enabled; non-object JSON rejected'

// Verify the actual GET parameter/session operations without skipping type checks.
String getStart = endpointSource.substring(endpointSource.indexOf('        String action = params.getFirst'), endpointSource.indexOf('        if (action =='))
String sessionStart = endpointSource.substring(endpointSource.indexOf('        HttpSession session = request.getSession(true)'), endpointSource.indexOf('        DashboardFilterLive.response(200, DashboardFilterLive.page'))
Class typedRequestCheck = checked.parseClass("""
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
class DashboardRequestTypeCheck {
    static String execute(MultivaluedMap<String, String> params, HttpServletRequest request) {
${getStart}
${sessionStart}
        return token
    }
}
""")
assert typedRequestCheck.execute(params, request) == attributes['dashboardFilterEditor.csrf']
println 'PASS: query and session operations compile with Jakarta types and checking enabled'

// Type-check the exact membership expression and typed Jira writer callbacks.
String membership = endpointSource.substring(endpointSource.indexOf('            if (dashboard.status'), endpointSource.indexOf("                return DashboardFilterLive.response(409, [error: 'Filter geh"))
String validateCallback = endpointSource.readLines().find { it.contains('{ Query query -> search.validateQuery') }.trim().replaceAll(/,$/, '')
String persistCallback = endpointSource.readLines().find { it.contains('{ SearchRequest filter -> manager.update') }.trim()
persistCallback = persistCallback.replaceAll(/,$/, '')
checked.parseClass("""
import com.atlassian.query.Query
import com.atlassian.jira.issue.search.SearchRequest
import com.atlassian.jira.issue.search.SearchRequestManager
import com.atlassian.jira.bc.issue.search.SearchService
class DashboardWriteTypesCheck {
    static boolean membership(Map dashboard, Long filterId) {
${membership}
            return false
        }
        return true
    }
    static List callbacks(Object user, SearchService search, SearchRequestManager manager) {
        return [${validateCallback}, ${persistCallback}]
    }
}
""")
println 'PASS: exact membership expression and Jira validation/update callbacks compile with checking enabled'

Class searchType = platform.loadClass('com.atlassian.jira.bc.issue.search.SearchService')
int validations = 0
def emptyErrors = new Expando(errorMessages: [], hasAnyErrors: { -> false })
def parseErrors = new Expando(errorMessages: ['Syntax error'])
def queryErrors = new Expando(errorMessages: ['Unknown project'], hasAnyErrors: { -> true })
def service = [parseQuery: { user, jql -> [valid:jql != 'BROKEN', query:null, errors:parseErrors] },
               validateQuery: { user, query -> validations++; validations == 1 ? emptyErrors : queryErrors }].asType(searchType)
accessor.components[searchType] = service
assert live.validateJql(' ').valid == false
assert live.validateJql('BROKEN').errors == ['Syntax error']
assert validations == 0
assert live.validateJql('project = TEST').valid == true
assert live.validateJql('project = MISSING').errors == ['Unknown project']
println 'PASS: validator distinguishes empty input, parse errors, semantic errors and valid queries'

assert html.contains('Version <strong>1.2.0</strong>')
assert !endpointSource.contains('query.toString()')
assert !endpointSource.contains('errors.errors')
assert endpointSource.contains('getJqlString(filter.query)')
println 'PASS: delivery version 1.2.0 contains serializer and MessageSet corrections'

// Exercise the shipped dashboard reader. One broken filter must not hide the other
// sources, and shared filters must retain every referring gadget without duplicate reads.
def entity = { Map values -> new Expando(getLong:{ String key -> values[key] as Long }, getString:{ String key -> values[key] as String }) }
Map<Long, Map> preferences = [
    (43710L):[projectOrFilterId:'filter-120'],
    (70511L):[filterId:'120'],
    (43712L):[projectOrFilterId:'project-123'],
    (43713L):[projectOrFilterId:'jql-project = TEST'],
    (44430L):[filterIds:'999, 121, 888'],
    (44431L):[pluginSource:'456', apiToken:'private']]
Map<Long, String> descriptors = [(43710L):'piechart-gadget.xml', (70511L):'two-dimensional-stats-gadget.xml',
    (43712L):'piechart-gadget.xml', (43713L):'piechart-gadget.xml', (44430L):'filter-results-gadget.xml', (44431L):'custom.xml']
accessor.ofBizDelegator = new Expando(findByAnd:{ String name, Map criteria ->
    switch (name) {
        case 'PortalPage': return [entity([pagename:'Charts'])]
        case 'PortletConfiguration': return descriptors.collect { id, descriptor -> entity([id:id,gadgetXml:'rest/gadgets/' + descriptor]) }
        case 'GadgetUserPreference': return preferences[criteria.portletconfiguration].collect { key, value -> entity([userprefkey:key,userprefvalue:value]) }
        default: throw new IllegalArgumentException(name)
    }
})
List<Long> reads = []
Class managerType = platform.loadClass('com.atlassian.jira.issue.search.SearchRequestManager')
accessor.components[managerType] = [getSearchRequestById:{ Long id ->
    reads.add(id)
    if (id == 999L) throw new IllegalStateException('unreadable')
    if (id == 888L) return null
    new Expando(name:'Filter ' + id, owner:new Expando(displayName:'Owner'), query:null)
}].asType(managerType)
accessor.components[searchType] = [getJqlString:{ query -> 'project = TEST' }].asType(searchType)
Map dashboardData = live.dashboard(10L)
assert dashboardData.status == 200 && dashboardData.gadgets.size() == 6
assert reads == [120L,999L,121L,888L]
assert dashboardData.filters.find { it.id == '120' }.gadgets == ['43710','70511']
assert dashboardData.filters.find { it.id == '999' }.error.contains('IllegalStateException')
assert dashboardData.filters.find { it.id == '121' }.jql == 'project = TEST'
assert dashboardData.filters.find { it.id == '888' }.error
assert dashboardData.gadgets.find { it.id == '43710' }.name == 'Kreisdiagramm'
assert dashboardData.gadgets.find { it.id == '43712' }.projectIds == ['123']
assert dashboardData.gadgets.find { it.id == '43713' }.directJql == ['project = TEST']
Map unknownGadget = dashboardData.gadgets.find { it.id == '44431' }
assert unknownGadget.filterIds.isEmpty() && unknownGadget.note.contains('ungepr')
assert unknownGadget.sourcePreferences.isEmpty()
assert !groovy.json.JsonOutput.toJson(dashboardData).contains('private')
println 'PASS: chart sources, shared filters, per-filter failures and limited source diagnostics survive the live reader'
