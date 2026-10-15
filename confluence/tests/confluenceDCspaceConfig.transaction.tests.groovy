import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import groovy.transform.TypeChecked

File repo = new File(System.getProperty('repoRoot', '.'))
String source = new File(repo, 'confluence/confluenceDCspaceConfig.groovy').getText('UTF-8')
String imports = '''
import groovy.json.JsonOutput
import org.codehaus.groovy.runtime.InvokerHelper
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.lang.reflect.Method
'''
int start = source.indexOf('class Pc {')
int banner = source.indexOf(' * END OF THE CONFLUENCE-FREE BLOCK', start)
assert start >= 0 && banner > start
GroovyClassLoader loader = new GroovyClassLoader(getClass().classLoader)
loader.parseClass(imports + source.substring(start, source.lastIndexOf('/*', banner)), 'ActualHelpers.groovy')
// SAL's documented public signatures; no dependency on a particular Confluence jar.
loader.parseClass('''package com.atlassian.sal.api.transaction
interface TransactionCallback<T> { T doInTransaction() }
interface TransactionTemplate { <T> T execute(TransactionCallback<T> action) }
''')
loader.parseClass(new File(repo, 'confluence/tests/fixtures/page-export-platform.groovy'))
int txStart = source.indexOf('class PageExportRejected ')
assert txStart >= 0 : 'Transaction helper start marker missing'
int txEnd = source.indexOf('/* ---- End page export transaction ---- */', txStart)
assert txEnd > txStart : 'Transaction helper end marker missing'
String helpers = source.substring(txStart, txEnd)
// New adapter is statically checked against SAL/ComponentLocator contract stubs.
CompilerConfiguration config = new CompilerConfiguration()
config.addCompilationCustomizers(new ASTTransformationCustomizer(TypeChecked))
new GroovyClassLoader(loader, config).parseClass(imports + helpers, 'CheckedTransaction.groovy')
loader.parseClass(imports + helpers, 'ActualTransaction.groovy')
String branch = source.substring(source.indexOf('    /* ---- Page export: validate'))
assert branch.trim().endsWith('}')
branch = branch.substring(0, branch.lastIndexOf('}'))
loader.parseClass(imports + '''
class ActualExportBranch {
    Object answer(Map response) { new TestResponse(response) }
    Object refuse(int status, String stage, String error) {
        new TestResponse([ok: false, written: false, status: status, stage: stage, error: error])
    }
    Object export(Map<String, Object> request) {
        long started = System.currentTimeMillis()
        Class responseClass = Object
''' + branch + '\n} }', 'ActualExportBranch.groovy')
// SAL's documented settings factory, so the export log of the actual branch can be
// exercised against a store; without a component behind it the log reports failure.
loader.parseClass('''package com.atlassian.sal.api.pluginsettings
interface PluginSettingsFactory { Object createGlobalSettings() }
''')
new GroovyShell(loader).evaluate('''
def payload = { -> [spaceKey: 'DOC', title: 'Config export Alpha', parentPageId: '', parentTitle: 'Config overview',
    space: [key: 'ALPHA', name: 'Alpha'], sections: [[kind: 'details', label: 'Details', children:
        [[kind: 'field', label: 'Status', value: 'Current', children: []]]]]] }
Class templateType = Class.forName('com.atlassian.sal.api.transaction.TransactionTemplate')
def wire = { Store store ->
    Object template = [execute: { callback -> store.transaction { callback.doInTransaction() } }].asType(templateType)
    ComponentLocator.components = [(PageManager): new PageManager(store: store),
        (PageService): new PageService(store: store), (SpaceService): new SpaceService(), (templateType): template]
}
def handler = new ActualExportBranch()
Store store = new Store()
wire(store)
Map first = handler.export(payload())
assert first.ok && first.written && first.parentAction == 'created' : first
assert first.overview.state == 'updated' : first
assert first.parentApplied == 'true' : first
assert store.commits == 1 && store.writes.every { it.transaction } : store.writes
assert ConfigOverview.read(store.byId(first.parentPageId as long).body, 'confluence', 'DOC').rows.size() == 1
store.nextRequest()
Map again = payload()
again.parentPageId = first.parentPageId
again.parentTitle = ''
Map second = handler.export(again)
assert second.ok && second.parentAction == 'found' && second.overview.state == 'updated' : second
assert store.pages.size() == 2
assert ConfigOverview.read(store.byId(first.parentPageId as long).body, 'confluence', 'DOC').rows.size() == 1
store.nextRequest()
Map another = payload()
another.title = 'Config export Beta'
another.space = [key: 'BETA', name: 'Beta']
another.parentPageId = first.parentPageId
another.parentTitle = ''
assert handler.export(another).overview.state == 'updated'
assert ConfigOverview.read(store.byId(first.parentPageId as long).body, 'confluence', 'DOC').rows.size() == 2
println 'PASS: first-run table, repeat upsert, second export and parent relationship'

/* OP-1488: the overview rows are gone, the detail pages are not. One save in the
 * transaction restores both rows; a page without the marker and a trashed export
 * are skipped with a reason, and a second rebuild writes nothing. */
long overviewId = first.parentPageId as long
store.save(new Page(version: 1, space: new Space(key: 'DOC'), title: 'Manual notes', body: '<p>Manual notes</p>'))
store.save(new Page(version: 1, space: new Space(key: 'DOC'), title: 'Config export Old', current: false,
    body: store.pages.values().find { it.title == 'Config export Alpha' }.body))
store.pages[overviewId].children = store.pages.values().findAll { it.id != overviewId }.toList()
store.pages[overviewId].body = Cx.PARENT_BODY
store.nextRequest()
int writesBefore = store.writes.size()
int commitsBefore = store.commits
Map rebuilt = handler.export([action: 'rebuild-overview', spaceKey: 'DOC', parentPageId: first.parentPageId])
assert rebuilt.ok && rebuilt.overview.state == 'updated' && rebuilt.complete == true && rebuilt.children == 4 : rebuilt
assert rebuilt.counts == [added: 2, present: 0, skipped: 2, unread: 0, unconfirmed: 0] : rebuilt
assert rebuilt.pages.find { it.title == 'Manual notes' }.reason.contains('export marker') : rebuilt
assert rebuilt.pages.find { it.title == 'Config export Old' }.reason.contains('not current') : rebuilt
assert store.writes.size() == writesBefore + 1 && store.writes.last().overview && store.writes.last().transaction : store.writes
assert store.commits == commitsBefore + 1
assert ConfigOverview.read(store.pages[overviewId].body, 'confluence', 'DOC').rows.size() == 2
store.nextRequest()
Map unchanged = handler.export([action: 'rebuild-overview', spaceKey: 'DOC', parentPageId: first.parentPageId])
assert unchanged.ok && unchanged.overview.state == 'unchanged' && unchanged.counts.present == 2 : unchanged
assert store.writes.size() == writesBefore + 1
println 'PASS: rebuild restores missing overview rows in one transactional save and then writes nothing'

Map<Long, String> committedBodies = store.pages.collectEntries { id, page -> [(id): page.body] }
store.nextRequest()
store.failCommit = true
Map failedUpdate = handler.export(again)
assert !failedUpdate.ok && !failedUpdate.written : failedUpdate
assert store.pages.collectEntries { id, page -> [(id): page.body] } == committedBodies
println 'PASS: failed commit also preserves previously committed pages'

for (String failure : ['commit', 'overview conflict', 'parent readback']) {
    Store failing = new Store(failCommit: failure == 'commit', conflictOnOverview: failure == 'overview conflict',
        hideNewParent: failure == 'parent readback')
    wire(failing)
    Map response = handler.export(payload())
    assert !response.ok && !response.written : [failure: failure, response: response]
    assert failing.pages.isEmpty() && failing.rollbacks == 1 : [failure: failure, pages: failing.pages]
}
println 'PASS: commit failure, genuine overview conflict and post-save refusal roll back without success'

Store topLevel = new Store()
wire(topLevel)
Map withoutParent = payload()
withoutParent.parentTitle = ''
Map topLevelResult = handler.export(withoutParent)
assert topLevelResult.ok && topLevelResult.overview.state == 'skipped' : topLevelResult
assert topLevel.pages.size() == 1 && topLevel.commits == 1
Store malformed = new Store()
wire(malformed)
Page parent = new Page(version: 1, space: new Space(key: 'DOC'), title: 'Config overview',
    body: '<p>Manual notes</p><ac:structured-macro ac:name="anchor"><ac:parameter ac:name="">cfcon-overview-confluence-start</ac:parameter></ac:structured-macro>')
malformed.save(parent)
malformed.writes.clear()
malformed.nextRequest()
Map separateProblem = handler.export(payload())
assert separateProblem.ok && separateProblem.written && separateProblem.overview.state == 'failed' : separateProblem
assert malformed.pages[parent.id].body == parent.body && malformed.pages.size() == 2
println 'PASS: no-parent export and pure overview validation retain their behavior'

Store guarded = new Store()
wire(guarded)
Page foreign = new Page(version: 1, space: new Space(key: 'DOC'), title: 'Config export Alpha', body: '<p>Manual text</p>')
guarded.save(foreign)
guarded.writes.clear()
Map refused = handler.export(payload())
assert !refused.ok && refused.stage == 'read' : refused
assert guarded.writes.isEmpty() && guarded.pages.size() == 1
assert guarded.pages[foreign.id].body == '<p>Manual text</p>'
ComponentLocator.components.remove(templateType)
Map unavailable = handler.export(payload())
assert !unavailable.ok && guarded.writes.isEmpty() : unavailable
Map invalid = payload()
invalid.parentPageId = '123'
assert handler.export(invalid).stage == 'validate'
assert guarded.writes.isEmpty()
println 'PASS: foreign detail/remarks guard, unavailable transaction and conflicting parent input'

/* The export log: written by the export itself, read back before it counts, and
 * never the reason an export fails. */
class FakeSettings {
    Map<String, Object> values = [:]
    boolean refuse = false
    Object get(String key) { values[key] }
    Object put(String key, Object value) {
        if (refuse) { throw new IllegalStateException('settings refused') }
        values[key] = value
    }
}
class FakeSettingsFactory {
    FakeSettings settings = new FakeSettings()
    Object createGlobalSettings() { settings }
}
Class settingsType = Class.forName('com.atlassian.sal.api.pluginsettings.PluginSettingsFactory', true, ComponentLocator.classLoader)
Store unlogged = new Store()
wire(unlogged)
Map noStore = handler.export(payload())
assert noStore.ok && noStore.written && noStore.exportLog.state == 'failed' : noStore
assert noStore.exportLog.reason.contains('no component') : noStore
Store logged = new Store()
wire(logged)
FakeSettingsFactory settingsFactory = new FakeSettingsFactory()
ComponentLocator.components[settingsType] = settingsFactory
Map loggedExport = handler.export(payload())
assert loggedExport.ok && loggedExport.exportLog.state == 'recorded' : loggedExport
Map logEntry = loggedExport.exportLog.entry
assert logEntry.key == 'ALPHA' && logEntry.product == 'confluence' && logEntry.spaceKey == 'DOC' : logEntry
assert logEntry.pageId == loggedExport.pageId && logEntry.title == 'Config export Alpha' : logEntry
assert logEntry.parentTitle == 'Config overview' && logEntry.parentPageId == loggedExport.parentPageId : logEntry
assert settingsFactory.settings.values.keySet() == ['cfcon.cfgexport.confluence.ALPHA'] as Set : settingsFactory.settings.values
assert ExportLog.read(settingsFactory.settings, 'confluence', ['ALPHA', 'BETA']).entries.keySet() == ['ALPHA'] as Set
settingsFactory.settings.refuse = true
logged.nextRequest()
Map refusedLog = handler.export(payload())
assert refusedLog.ok && refusedLog.written && refusedLog.exportLog.state == 'failed' : refusedLog
assert refusedLog.exportLog.reason.contains('settings refused') : refusedLog
Store rolledBack = new Store(failCommit: true)
wire(rolledBack)
FakeSettingsFactory untouchedSettings = new FakeSettingsFactory()
ComponentLocator.components[settingsType] = untouchedSettings
Map lostExport = handler.export(payload())
assert !lostExport.ok && !lostExport.written : lostExport
assert untouchedSettings.settings.values.isEmpty() : 'a rolled-back export must leave no log entry: ' + untouchedSettings.settings.values
println 'PASS: the export log is written after the commit, read back, and never fails the export'
''', 'ExportLifecycleTests.groovy')
