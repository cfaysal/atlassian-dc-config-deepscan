/* Shared contract of the export log, exercised against each endpoint's actual
 * embedded ExportLog class. The settings store is a fake with the two methods the
 * class calls by name; the platform lookup is exercised by the transaction test. */
Class logClass = getClass().classLoader.loadClass('ExportLog')
int checks = 0
def verify = { boolean condition, String description ->
    assert condition : description
    checks++
}
String product = binding.getVariable('product')
File repo = new File(System.getProperty('repoRoot', '.'))

class LogFactory {
    Object settings
    Object createGlobalSettings() { settings }
}
class LogSettings {
    Map<String, Object> values = [:]
    Set<String> failing = [] as Set
    Object get(String key) {
        if (failing.contains(key)) { throw new IllegalStateException('read refused') }
        values[key]
    }
    Object put(String key, Object value) { values[key] = value }
}

/* Keys */
verify(logClass.settingsKey(product, 'OPS') == 'cfcon.cfgexport.' + product + '.OPS', 'a short key stays readable')
String longKey = 'K' * 200
String hashed = logClass.settingsKey(product, longKey)
verify(hashed.length() <= 100, 'a long item key never makes a settings key longer than 100 characters')
verify(hashed == logClass.settingsKey(product, longKey), 'the hashed key is stable')
verify(hashed != logClass.settingsKey(product, longKey + 'X'), 'different long keys do not collide')
verify(logClass.settingsKey('jira', 'OPS') != logClass.settingsKey('confluence', 'OPS'), 'the products keep apart')

/* Write and read back */
LogSettings settings = new LogSettings()
Map entry = logClass.entry(product, 'OPS', '2026-10-15 10:52 UTC', 'cfaysal',
    [target: 'Confluence', spaceKey: 'DOC', pageId: 123, title: 'Config - OPS', parentPageId: '500',
     parentTitle: 'Config overview', ignored: 'x'])
verify(!entry.containsKey('ignored'), 'only the declared fields are kept')
verify(entry.pageId == '123', 'ids are stored as text')
Map written = logClass.write(settings, product, 'OPS', entry)
verify(written.state == 'recorded', 'a written entry that reads back is recorded')
verify(written.label == 'exported 2026-10-15 to DOC / Config overview', 'the label names day, space and parent: ' + written.label)
verify(written.detail.contains('by cfaysal') && written.detail.contains('page "Config - OPS"') &&
    written.detail.contains('Confluence, space DOC'), 'the detail names who, where and which page: ' + written.detail)
Map read = logClass.read(settings, product, ['OPS', 'NEW', null])
verify(read.entries.keySet() == ['OPS'] as Set, 'only logged items come back')
verify(read.failures.isEmpty(), 'an item without an entry is not a failure')

/* A value that is there and cannot be read is a failure, never "not exported" */
settings.values[logClass.settingsKey(product, 'BAD')] = '{not json'
settings.values[logClass.settingsKey(product, 'ALIEN')] = groovy.json.JsonOutput.toJson(
    [product: product, key: 'OTHER', exportedAt: '2026-01-01 00:00 UTC'])
settings.values[logClass.settingsKey(product, 'ODD')] = 42
settings.failing.add(logClass.settingsKey(product, 'DOWN'))
Map mixed = logClass.read(settings, product, ['OPS', 'BAD', 'ALIEN', 'ODD', 'DOWN'])
verify(mixed.entries.keySet() == ['OPS'] as Set, 'unreadable entries are not shown as entries')
verify(mixed.failures.size() == 4, 'every unreadable entry is a failure: ' + mixed.failures)
verify(mixed.failures.any { it.startsWith('ALIEN:') && it.contains('another item') }, 'an entry for another item is refused')
verify(mixed.failures.any { it.startsWith('DOWN:') && it.contains('read refused') }, 'a refused read names its reason')
String line = logClass.failureLine(mixed.failures, 'item')
verify(line.contains('4 item(s)') && line.contains('not marked'), 'the failure line says how many and what it means')
verify(logClass.failureLine([], 'item') == null, 'no failures, no line')
List<String> many = (1..9).collect { 'K' + it + ': x' }
verify(logClass.failureLine(many, 'item').endsWith('; ...'), 'a long failure list is cut and says so')

/* A write the store does not keep is not "recorded" */
LogSettings forgetful = new LogSettings() {
    Object put(String key, Object value) { null }
}
verify(logClass.write(forgetful, product, 'OPS', entry).state == 'unknown', 'an entry not seen again is unknown, not recorded')
LogSettings refusing = new LogSettings() {
    Object put(String key, Object value) { throw new IllegalStateException('write refused') }
}
Map refused = logClass.write(refusing, product, 'OPS', entry)
verify(refused.state == 'failed' && refused.reason.contains('write refused'), 'a refused write is failed with its reason')

/* Opening the store */
Map missing = logClass.open([new GroovyClassLoader()], { Class type -> new Object() })
verify(!missing.ok && missing.error.contains('not visible'), 'a store type the script cannot see is reported')
GroovyClassLoader withSal = new GroovyClassLoader(getClass().classLoader)
withSal.parseClass('package com.atlassian.sal.api.pluginsettings\ninterface PluginSettingsFactory { Object createGlobalSettings() }')
verify(logClass.open([null, withSal], { Class type -> null }).error.contains('no component'), 'a missing component is reported')
verify(logClass.open([withSal], { Class type -> throw new IllegalStateException('lookup down') }).error.contains('lookup down'),
    'a failing lookup is reported')
Map opened = logClass.open([withSal], { Class type -> new LogFactory(settings: settings) })
verify(opened.ok && opened.settings.is(settings), 'an opened store hands back its global settings')
verify(!logClass.open([withSal], { Class type -> new LogFactory() }).ok, 'a store without global settings is reported')

def helpers = { String endpoint ->
    String source = new File(repo, endpoint).getText('UTF-8')
    int start = source.indexOf('class ExportLog {')
    source.substring(source.lastIndexOf('/*', start), source.indexOf('\n}\n', start))
}
verify(helpers('jira/jiraDCprojectConfig.groovy') == helpers('confluence/confluenceDCspaceConfig.groovy'),
    'both endpoints ship an identical export log')
println 'EXPORT LOG ' + product + ': ' + checks + ' checks passed'
