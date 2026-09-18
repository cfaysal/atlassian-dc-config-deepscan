import com.atlassian.jira.component.ComponentAccessor
import com.almworks.jira.structure.api.StructureComponents
import com.onresolve.scriptrunner.runner.customisers.PluginModule
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import groovy.json.JsonOutput

import java.lang.reflect.Method
import java.lang.reflect.Modifier

@WithPlugin('com.almworks.jira.structure')
@PluginModule
StructureComponents structureComponents

final List<String> READ_TOKENS = [
    'hierarchy', 'level', 'forest', 'generator', 'provenance', 'revision',
    'rule', 'audit', 'history', 'preview', 'lock', 'setting'
]

final List<Map<String, String>> PRODUCT_PLUGINS = [
    [name: 'Structure', key: 'com.almworks.jira.structure'],
    [name: 'Advanced Roadmaps', key: 'com.atlassian.jpo'],
    [name: 'Automation', key: 'com.codebarrel.addons.automation']
]

List<String> publicReadSignatures(Object service, List<String> tokens) {
    if (service == null) {
        return []
    }
    service.class.methods
        .findAll { Method method ->
            Modifier.isPublic(method.modifiers) &&
                tokens.any { String token -> method.name.toLowerCase(Locale.ROOT).contains(token) }
        }
        .collect { Method method ->
            method.name + '(' + method.parameterTypes*.name.join(',') + '):' + method.returnType.name
        }
        .unique()
        .sort()
}

Object safeInvoke(Object target, String methodName) {
    if (target == null) {
        return null
    }
    Method method = target.class.methods.find { Method candidate ->
        candidate.name == methodName && candidate.parameterCount == 0
    }
    method == null ? null : method.invoke(target)
}

Map<String, Object> serviceReport(String label, Closure<Object> resolver, List<String> tokens) {
    try {
        Object service = resolver.call()
        if (service == null) {
            return [label: label, state: 'UNAVAILABLE', reason: 'service not resolved']
        }
        List<String> signatures = publicReadSignatures(service, tokens)
        return [
            label: label,
            state: signatures.isEmpty() ? 'NO_MATCHING_METHODS' : 'AVAILABLE',
            serviceClass: service.class.name,
            signatures: signatures
        ]
    } catch (Throwable error) {
        return [label: label, state: 'FAILED', reason: error.class.name]
    }
}

Map<String, Object> pluginReport(Object pluginAccessor, Map<String, String> product) {
    try {
        Object plugin = pluginAccessor.getPlugin(product.key)
        if (plugin == null) {
            return [name: product.name, key: product.key, state: 'UNAVAILABLE']
        }
        return [
            name: product.name,
            key: product.key,
            state: 'AVAILABLE',
            version: plugin.pluginInformation?.version ?: 'UNKNOWN'
        ]
    } catch (Throwable error) {
        return [name: product.name, key: product.key, state: 'FAILED', reason: error.class.name]
    }
}

List<Object> readPluginServices(Object pluginAccessor, String pluginKey) {
    Object plugin = pluginAccessor.getPlugin(pluginKey)
    Object bundle = safeInvoke(plugin, 'getBundle')
    Object context = safeInvoke(bundle, 'getBundleContext')
    if (context == null) {
        return []
    }
    Object references = context.getAllServiceReferences(null, null)
    if (references == null) {
        return []
    }
    references.collect { Object reference -> context.getService(reference) }.findAll { it != null }
}

List<Map<String, Object>> relevantPluginServices(Object pluginAccessor,
                                                  Map<String, String> product,
                                                  List<String> tokens) {
    try {
        List<Object> services = readPluginServices(pluginAccessor, product.key)
        List<Map<String, Object>> matches = services.collect { Object service ->
            List<String> signatures = publicReadSignatures(service, tokens)
            signatures.isEmpty() ? null : [serviceClass: service.class.name, signatures: signatures]
        }.findAll { it != null }
        if (matches.isEmpty()) {
            return [[label: product.name, state: 'NO_MATCHING_METHODS']]
        }
        matches.sort { Map<String, Object> left, Map<String, Object> right ->
            String.valueOf(left.serviceClass) <=> String.valueOf(right.serviceClass)
        }
    } catch (Throwable error) {
        [[label: product.name, state: 'FAILED', reason: error.class.name]]
    }
}

Object pluginAccessor = ComponentAccessor.pluginAccessor

List<Map<String, Object>> productReports = PRODUCT_PLUGINS.collect { Map<String, String> product ->
    pluginReport(pluginAccessor, product)
}

List<Map<String, Object>> directServices = [
    serviceReport('StructureComponents', { structureComponents }, READ_TOKENS),
    serviceReport('Jira change history', { ComponentAccessor.changeHistoryManager }, READ_TOKENS),
    serviceReport('Jira custom fields', { ComponentAccessor.customFieldManager }, READ_TOKENS),
    serviceReport('Jira issues', { ComponentAccessor.issueManager }, READ_TOKENS)
]

List<Map<String, Object>> pluginServices = PRODUCT_PLUGINS.collectMany { Map<String, String> product ->
    relevantPluginServices(pluginAccessor, product, READ_TOKENS)
}

Map<String, Object> report = [
    schema: 'cfcon.structure-doctor.capabilities.v1',
    mode: 'READ_ONLY',
    jiraVersion: ComponentAccessor.applicationProperties.getString('jira.version') ?: 'UNKNOWN',
    products: productReports,
    directServices: directServices,
    pluginServices: pluginServices,
    guarantees: [
        hierarchyWrites: false,
        automationWrites: false,
        customerDataIncluded: false
    ]
]

println JsonOutput.prettyPrint(JsonOutput.toJson(report))
