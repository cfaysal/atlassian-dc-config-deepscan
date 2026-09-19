import com.atlassian.jira.component.ComponentAccessor
import groovy.json.JsonOutput

import java.lang.reflect.Modifier

def readTokens = [
    'hierarchy', 'level', 'forest', 'generator', 'provenance', 'revision',
    'rule', 'audit', 'history', 'preview', 'lock', 'setting'
]

def productPlugins = [
    [name: 'Structure', key: 'com.almworks.jira.structure'],
    [name: 'Advanced Roadmaps', key: 'com.atlassian.jpo'],
    [name: 'Automation', key: 'com.codebarrel.addons.automation']
]

def publicReadSignatures = { service, tokens ->
    if (service == null) return []
    service.class.methods
        .findAll { method ->
            Modifier.isPublic(method.modifiers) &&
                tokens.any { token -> method.name.toLowerCase(Locale.ROOT).contains(token) }
        }
        .collect { method ->
            method.name + '(' + method.parameterTypes*.name.join(',') + '):' +
                method.returnType.name
        }
        .unique()
        .sort()
}

def safeInvoke = { target, methodName ->
    if (target == null) return null
    def method = target.class.methods.find { candidate ->
        candidate.name == methodName && candidate.parameterCount == 0
    }
    method == null ? null : method.invoke(target)
}

def serviceReport = { label, resolver, tokens ->
    try {
        def service = resolver.call()
        if (service == null) {
            return [label: label, state: 'UNAVAILABLE', reason: 'service not resolved']
        }
        def signatures = publicReadSignatures.call(service, tokens)
        [
            label: label,
            state: signatures.isEmpty() ? 'NO_MATCHING_METHODS' : 'AVAILABLE',
            serviceClass: service.class.name,
            signatures: signatures
        ]
    } catch (Throwable error) {
        [label: label, state: 'FAILED', reason: error.class.name]
    }
}

def pluginReport = { pluginAccessor, product ->
    try {
        def plugin = pluginAccessor.getPlugin(product.key)
        if (plugin == null) {
            return [name: product.name, key: product.key, state: 'UNAVAILABLE']
        }
        def information = safeInvoke.call(plugin, 'getPluginInformation')
        [
            name: product.name,
            key: product.key,
            state: 'AVAILABLE',
            version: safeInvoke.call(information, 'getVersion') ?: 'UNKNOWN'
        ]
    } catch (Throwable error) {
        [name: product.name, key: product.key, state: 'FAILED', reason: error.class.name]
    }
}

def readPluginServices = { pluginAccessor, pluginKey ->
    def plugin = pluginAccessor.getPlugin(pluginKey)
    def bundle = safeInvoke.call(plugin, 'getBundle')
    def context = safeInvoke.call(bundle, 'getBundleContext')
    if (context == null) return []
    def references = context.getAllServiceReferences(null, null)
    if (references == null) return []
    references.collect { reference -> context.getService(reference) }.findAll { it != null }
}

def relevantPluginServices = { pluginAccessor, product, tokens ->
    try {
        def matches = readPluginServices.call(pluginAccessor, product.key).collect { service ->
            def signatures = publicReadSignatures.call(service, tokens)
            signatures.isEmpty() ? null : [
                serviceClass: service.class.name,
                signatures: signatures
            ]
        }.findAll { it != null }
        if (matches.isEmpty()) {
            return [[label: product.name, state: 'NO_MATCHING_METHODS']]
        }
        matches.sort { left, right ->
            String.valueOf(left.serviceClass) <=> String.valueOf(right.serviceClass)
        }
    } catch (Throwable error) {
        [[label: product.name, state: 'FAILED', reason: error.class.name]]
    }
}

def resolvePluginComponent = { pluginAccessor, pluginKey, className ->
    def plugin = pluginAccessor.getPlugin(pluginKey)
    def loader = safeInvoke.call(plugin, 'getClassLoader')
    if (loader == null) return null
    def componentClass = loader.loadClass(className)
    ComponentAccessor.getOSGiComponentInstanceOfType(componentClass)
}

def pluginAccessor = ComponentAccessor.getPluginAccessor()

def productReports = productPlugins.collect { product ->
    pluginReport.call(pluginAccessor, product)
}

def directServices = [
    serviceReport.call('StructureComponents', {
        resolvePluginComponent.call(
            pluginAccessor,
            'com.almworks.jira.structure',
            'com.almworks.jira.structure.api.StructureComponents')
    }, readTokens),
    serviceReport.call('Jira change history', {
        ComponentAccessor.getChangeHistoryManager()
    }, readTokens),
    serviceReport.call('Jira custom fields', {
        ComponentAccessor.getCustomFieldManager()
    }, readTokens),
    serviceReport.call('Jira issues', {
        ComponentAccessor.getIssueManager()
    }, readTokens)
]

def pluginServices = productPlugins.collectMany { product ->
    relevantPluginServices.call(pluginAccessor, product, readTokens)
}

def report = [
    schema: 'cfcon.structure-doctor.capabilities.v1',
    mode: 'READ_ONLY',
    jiraVersion: ComponentAccessor.getApplicationProperties()
        .getString('jira.version') ?: 'UNKNOWN',
    products: productReports,
    directServices: directServices,
    pluginServices: pluginServices,
    guarantees: [
        hierarchyWrites: false,
        automationWrites: false,
        customerDataIncluded: false
    ]
]

return JsonOutput.prettyPrint(JsonOutput.toJson(report))
