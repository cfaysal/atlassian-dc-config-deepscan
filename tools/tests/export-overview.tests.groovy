/* Shared contract exercised against each endpoint's actual embedded classes. */
Class overviewClass = null
try { overviewClass = getClass().classLoader.loadClass('ConfigOverview') } catch (ClassNotFoundException ignored) { }
assert overviewClass != null : 'OP-1475: the persistent overview implementation is missing'
Class coordinatorClass = getClass().classLoader.loadClass('OverviewExport')
int checks = 0
def verify = { boolean condition, String description ->
    assert condition : description
    checks++
}
String product = binding.getVariable('product')
def renderDetail = binding.getVariable('renderDetail')
def payload = { String name ->
    [project: [key: name, name: name], space: [key: name, name: name],
     sections: [[kind: 'details', label: 'Details', children: [
         [kind: 'field', label: 'Status', value: 'Current', children: []]]],
         [kind: 'permissions', label: 'Permissions', children: [
         [kind: 'grant', label: 'View', children: []]]]]]
}
def firstDetail = renderDetail(payload('Alpha'))
verify(firstDetail.sections.size() == 2, 'capture each exported section')
firstDetail.sections.each { section ->
    verify(firstDetail.storage.contains(overviewClass.anchor(section.anchor)), 'detail carries its section anchor')
    verify(firstDetail.storage.indexOf(overviewClass.anchor(section.anchor)) <
        firstDetail.storage.indexOf('<ac:parameter ac:name="title">' + section.label),
        'anchor precedes the existing Expand, outside its collapsed body')
}
String original = '<p>KEEP introduction &amp; notes</p><table><tbody><tr><td>KEEP other table</td></tr></tbody></table>'
String a = overviewClass.upsert(original, product, '101', 'Export Alpha', 'DOC', 'Alpha', firstDetail.sections)
verify(a.startsWith(original), 'preserve all unmarked parent content')
verify(a.contains('ac:anchor="' + firstDetail.sections[0].anchor + '"'), 'section uses native anchor link')
verify(a.contains('ri:content-title="Export Alpha" ri:space-key="DOC"'), 'section targets the exported page')
verify(a.contains(firstDetail.sections[0].count), 'use the detail renderer count')
String b = overviewClass.upsert(a, product, '102', 'Export Beta', 'DOC', 'Beta', firstDetail.sections)
verify(overviewClass.read(b, product).rows.size() == 2, 'second exported page appends one row')
String repeat = overviewClass.upsert(b, product, '101', 'Export Alpha', 'DOC', 'Alpha updated', firstDetail.sections)
verify(overviewClass.read(repeat, product).rows.size() == 2, 'same export name updates its row')
verify(repeat.contains('Alpha updated'), 'updated row contains current label')
verify(overviewClass.read(repeat, product).rows[1] == overviewClass.read(b, product).rows[1], 'keep other row verbatim')
String replaced = overviewClass.upsert(repeat, product, '999', 'Export Alpha', 'DOC', 'Replacement', firstDetail.sections)
verify(overviewClass.read(replaced, product).rows.size() == 2, 'same name with a new page ID updates its row')
verify(overviewClass.rowIdentity(overviewClass.read(replaced, product).rows[0], product).id == '999', 'replacement updates links and identity')
String renamed = overviewClass.upsert(replaced, product, '999', 'Renamed export', 'DOC', 'Renamed', firstDetail.sections)
verify(overviewClass.read(renamed, product).rows.size() == 2, 'page ID also deduplicates a renamed export')
def extraSections = firstDetail.sections + [[key: 'extra', column: 'Extra', label: 'Extra', anchor: 'section-extra', count: '3 items']]
String grown = overviewClass.upsert(renamed, product, '103', 'Export Gamma', 'DOC', 'Gamma', extraSections)
verify(overviewClass.read(grown, product).columns.size() == 3, 'new sections extend the header')
verify(overviewClass.read(grown, product).rows.every { it.size() == 4 }, 'older rows receive an empty new column')
String edited = grown.replace('<ac:structured-macro ', '<ac:structured-macro ac:macro-id="editor-id" ')
verify(overviewClass.read(edited, product).rows.size() == 3, 'editor macro IDs retain identity')
String coexisting = overviewClass.upsert(edited, product == 'jira' ? 'confluence' : 'jira', '201', 'Other product', 'DOC', 'Other', firstDetail.sections)
verify(overviewClass.read(coexisting, product).rows.size() == 3, 'product overviews coexist without losing rows')
String hostile = overviewClass.upsert('', product, '104', 'Quotes " & <title>', 'DOC', '<script>bad</script>', firstDetail.sections)
verify(!hostile.contains('<script>'), 'escape row display names')
verify(hostile.contains('&quot;') && hostile.contains('&lt;title&gt;'), 'escape resource attributes')
verify(overviewClass.confirmed(hostile, product, '104', 'Quotes " & <title>', 'DOC', '<script>bad</script>', firstDetail.sections), 'escaped names and titles confirm after readback')
String numericEntities = hostile.replace('&quot;', '&#34;').replace('&lt;', '&#x3C;').replace('&gt;', '&#62;').replace('&amp;', '&#38;')
numericEntities = overviewClass.upsert(numericEntities, product, '904', 'Quotes " & <title>', 'DOC', 'Replacement', firstDetail.sections)
verify(overviewClass.read(numericEntities, product).rows.size() == 1, 'editor entity normalization still matches the export name')
String spaced = overviewClass.upsert('', product, '901', 'Export  Alpha', 'DOC', 'Alpha', firstDetail.sections)
spaced = overviewClass.upsert(spaced, product, '902', 'Export  Alpha', 'DOC', 'Replacement', firstDetail.sections)
verify(overviewClass.read(spaced, product).rows.size() == 1, 'export name comparison preserves significant whitespace')
def refuses = { String body ->
    boolean refused = false
    try { overviewClass.upsert(body, product, '105', 'New', 'DOC', 'New', firstDetail.sections) }
    catch (IllegalArgumentException ignored) { refused = true }
    verify(refused, 'refuse malformed or ambiguous owned content')
}
refuses(a.replace(overviewClass.marker(product, 'end'), ''))
refuses(a + overviewClass.marker(product, 'start'))
refuses(a.replace('<th>', '<th colspan="2">'))
refuses(a.replace('cfcon-overview-' + product + '-row-101', 'missing-row'))
refuses(firstDetail.storage)
refuses(a.replace('ri:space-key="DOC"/>', 'ri:space-key="DOC"/><ri:page ri:content-title="Another" ri:space-key="DOC"/>'))
String duplicate = a.replace('</tbody>', '<tr>' + overviewClass.read(a, product).rows[0].collect { '<td>' + it + '</td>' }.join('') + '</tr></tbody>')
refuses(duplicate)
boolean collision = false
try { overviewClass.upsert(b, product, '101', 'Export Beta', 'DOC', 'Collision', firstDetail.sections) }
catch (IllegalArgumentException ignored) { collision = true }
verify(collision, 'conflicting title and ID matches refuse instead of choosing a row')

Map target = [ok: true, id: '10', title: 'Overview', spaceKey: 'DOC', version: 1, storage: original]
int writes = 0
def readTarget = { -> new LinkedHashMap(target) }
def saveTarget = { Map page, String storage ->
    assert page.version == target.version : 'optimistic version used'
    writes++
    target.storage = storage
    target.version++
    [ok: true]
}
def maintain = { String id, String title, Closure reader, Closure writer ->
    coordinatorClass.maintain(product, '10', id, title, 'DOC', 'Label', firstDetail.sections, reader, writer)
}
verify(maintain('101', 'Export Alpha', readTarget, saveTarget).state == 'updated', 'save and independently verify the overview row')
verify(maintain('102', 'Export Beta', readTarget, saveTarget).state == 'updated', 'second transport update verified')
verify(maintain('999', 'Export Alpha', readTarget, saveTarget).state == 'updated', 'same-title replacement verified')
verify(overviewClass.read(target.storage, product).rows.size() == 2, 'transport sequence has only two rows')
verify(maintain('103', 'Export Gamma', { [ok: false, error: 'read denied'] }, saveTarget).state == 'failed', 'failed read never becomes empty content')
verify(writes == 3, 'failed read performs no write')
verify(maintain('103', 'Export Gamma', { [ok: true, id: '11', spaceKey: 'DOC', title: 'Wrong', version: 1, storage: ''] }, saveTarget).state == 'failed', 'wrong target identity performs no write')
verify(maintain('103', 'Export Gamma', readTarget, { page, storage -> [ok: false, refused: true, error: '409 conflict'] }).state == 'failed', 'confirmed refusal preserves existing overview')
verify(maintain('103', 'Export Gamma', readTarget, { page, storage -> [ok: false, error: 'read timeout'] }).state == 'unknown', 'unconfirmed write error does not claim no save')
verify(maintain('103', 'Export Gamma', readTarget, { page, storage -> saveTarget(page, storage); throw new IOException('read timeout') }).state == 'updated', 'readback confirms a save despite a transport exception')
verify(coordinatorClass.maintain(product, '10', '104', 'Whitespace', 'DOC', 'Two  spaces', firstDetail.sections, readTarget, saveTarget).state == 'updated', 'display whitespace does not invalidate a saved row')
int reads = 0
verify(maintain('103', 'Export Gamma', { ++reads == 1 ? readTarget() : [ok: false, error: 'readback unavailable'] }, { page, storage -> [ok: true] }).state == 'unknown', 'failed readback is UNKNOWN')
verify(maintain('103', 'Export Gamma', readTarget, { page, storage -> [ok: true] }).state == 'unknown', 'accepted save without target effect is unconfirmed')
verify(coordinatorClass.maintain(product, null, '1', 'Title', 'DOC', 'Label', [], { assert false }, { assert false }).state == 'skipped', 'no selected parent preserves original export behavior')

File repo = new File(System.getProperty('repoRoot', '.'))
/*
 * Confluence application link transport
 * The source-derived transport contract uses a fake authenticated factory.
 * Every request is captured in memory.
 */
if (product == 'jira') {
    String source = new File(repo, 'jira/jiraDCprojectConfig.groovy').getText('UTF-8')
    String transport = source.substring(source.indexOf('Map<String, Object> confluenceOverviewRead('),
        source.indexOf('Map<String, Object> confluenceParentPage('))
    List calls = []
    Map remote = [id: '10', type: 'page', title: 'Overview', space: [key: 'DOC'],
        version: [number: 7], body: [storage: [value: '<p>Existing notes</p>']]]
    Closure call = { factory, method, path, body -> calls.add([method: method.toString(), path: path, body: body]); [ok: true, json: remote] }
    def harness = new GroovyShell(getClass().classLoader, new Binding([confluenceCall: call])).evaluate(
        'import groovy.json.JsonOutput\nclass ApplicationLinkRequestFactory {}\nclass Request { enum MethodType { GET, PUT } }\n' + transport + '\nreturn this')
    def factory = harness.class.classLoader.loadClass('ApplicationLinkRequestFactory').getDeclaredConstructor().newInstance()
    Map readPage = harness.confluenceOverviewRead(factory, '10')
    verify(readPage.storage == '<p>Existing notes</p>' && readPage.version == 7, 'Jira transport reads actual storage/version')
    verify(calls[0].path == '/rest/api/content/10?expand=body.storage,version,space', 'read all required target evidence in one call')
    harness.confluenceOverviewWrite(factory, readPage, 'merged')
    Map update = new groovy.json.JsonSlurper().parseText(calls[1].body)
    verify(update.version.number == 8 && update.body.storage.value == 'merged', 'Jira transport increments the measured version')
    verify(!update.containsKey('ancestors') && update.title == 'Overview', 'overview update leaves page position and title intact')
    remote.body = [:]
    verify(harness.confluenceOverviewRead(factory, '10').ok == false, 'missing remote storage is a failed read')
    String callSource = source.substring(source.indexOf('Map<String, Object> confluenceCall('),
        source.indexOf('Map<String, Object> confluenceSpaces('))
    def failureHarness = new GroovyShell(getClass().classLoader).evaluate('''
import groovy.json.JsonSlurper
class CredentialsRequiredException extends Exception {}
class Request { enum MethodType { GET, PUT } }
class ApplicationLinkRequestFactory {
    String response
    def createRequest(method, url) {
        [addHeader: { a, b -> }, setConnectionTimeout: { a -> }, setSoTimeout: { a -> },
         setRequestBody: { a -> }, execute: { -> response }]
    }
}
''' + callSource + '\nreturn this')
    def failureFactory = failureHarness.class.classLoader.loadClass('ApplicationLinkRequestFactory').getDeclaredConstructor().newInstance()
    def putMethod = failureHarness.class.classLoader.loadClass('Request$MethodType').enumConstants.find { it.toString() == 'PUT' }
    [409, 500, 408].each { int status ->
        failureFactory.response = groovy.json.JsonOutput.toJson([statusCode: status, message: 'Synthetic error'])
        Map failure = failureHarness.confluenceCall(failureFactory, putMethod, '/rest/api/content/10', 'body')
        verify(failure.ok == false && (failure.refused == true) == (status == 409),
            'only a known non-write HTTP response confirms refusal: ' + status)
    }
}
def helpers = { String endpoint ->
    String source = new File(repo, endpoint).getText('UTF-8')
    source.substring(source.indexOf('class ConfigOverview {'), source.indexOf('/* What a remark read', source.indexOf('class ConfigOverview {')))
}
verify(helpers('jira/jiraDCprojectConfig.groovy') == helpers('confluence/confluenceDCspaceConfig.groovy'), 'both endpoints ship identical overview helpers')
println 'OVERVIEW ' + product + ': ' + checks + ' checks passed'
