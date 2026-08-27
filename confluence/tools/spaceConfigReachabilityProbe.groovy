/* =============================================================================
 * Confluence Data Center - ScriptRunner reachability and acquisition probe
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only.
 *
 * Version 0.4. The endpoint name has not changed since 0.1, so replacing the
 * file in the script root is the whole deployment.
 *
 * WHY 0.2 DID NOT COMPILE, because the cause matters more than the fix
 *   It statically imported com.atlassian.plugin.ContainerManagedPlugin. The type
 *   exists, but as com.atlassian.plugin.module.ContainerManagedPlugin, in
 *   atlassian-plugins-api-9.1.7.jar. The package name was written from memory
 *   instead of read off the instance.
 *
 *   The deeper fault is that 0.2 named an unverified type STATICALLY at all.
 *   0.1 compiled under every outcome precisely because every type under probe
 *   was a string. A probe that cannot start is worth less than no probe, since
 *   it answers nothing while looking like a broken subject rather than a broken
 *   instrument. 0.3 restores the rule: the only static imports are types this
 *   endpoint has already MEASURED as reachable, and everything else is loaded by
 *   name and called reflectively.
 *
 * What 0.1 measured, and what still stands
 *   The ScriptRunner Import-Package header does not predict what a script can
 *   reach. Five of seven packages absent from it resolved anyway. The boundary
 *   that exists:
 *     Confluence PRODUCT classes resolve directly, internal and dmz included.
 *     Bundled PLUGIN classes do not, and need the exporting plugin classloader.
 *   Resolution and acquisition are different questions: five types resolved and
 *   still handed back no component, one of them a control.
 *
 * What 0.3 asks
 *   A - ACQUISITION per route. ComponentLocator first; for a plugin-owned type,
 *       the exporting plugin container, asked for beans of each interface the
 *       implementation itself declares. The interface is read off the class, not
 *       guessed: a guessed bean type proves nothing when it returns empty.
 *   B - THE PROPERTY SECTION, functionally, across BOTH enumerable stores:
 *       Bandana under the space context, and PluginSettings under the space key.
 *       KEYS ONLY. Values are never read, rendered or logged - they are
 *       XStream-serialised objects and apps store secrets in them.
 *   C - INHERITANCE, empirically: space key sets against the global one, so the
 *       bytecode reading that getKeys does not inherit is checked against the
 *       running instance rather than only against the decompiler.
 *   D - BLUEPRINT STATE without touching the plugin, by reading the store the
 *       plugin writes rather than asking the plugin. That is what a
 *       configuration scanner should do anyway.
 *
 * Parameters
 *   space=<KEY>   optional. Without it the first current spaces in key order.
 *   limit=<n>     optional, default 5, capped at 25.
 *
 * Platform
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameters are untyped, so no jakarta.* or javax.* import appears.
 * ========================================================================== */

/* Every import below is a type this endpoint measured as reachable in 0.1. */
import com.atlassian.bandana.BandanaManager

import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.confluence.spaces.SpaceStatus

import com.atlassian.plugin.Plugin
import com.atlassian.plugin.PluginAccessor
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import org.codehaus.groovy.runtime.InvokerHelper

@BaseScript CustomEndpointDelegate delegate

class Probe {

    static final String VERSION = "0.4"
    static final int KEY_SAMPLE = 12
    static final int SPACE_LIMIT_MAX = 25

    /* Measured 2026-08-27: com.atlassian.plugin.module.ContainerManagedPlugin in
     * atlassian-plugins-api-9.1.7.jar. Held as a string, never imported. */
    static final String CONTAINER_MANAGED = "com.atlassian.plugin.module.ContainerManagedPlugin"
    static final String SETTINGS_FACTORY =
        "com.atlassian.confluence.api.service.settings.ExtendedPluginSettingsFactory"

    /* [label, class, owning plugin key or null for a product class, group] */
    static final List<List<String>> ACQUIRE = [
        ["JSON space property store",
         "com.atlassian.confluence.api.service.content.SpacePropertyService", null, "OPEN"],
        ["effective space permissions",
         "com.atlassian.confluence.security.denormalisedpermissions.impl.space.EffectiveSpacePermissionsCalculator",
         null, "OPEN"],
        ["plugin container marker interface", CONTAINER_MANAGED, null, "OPEN"],
        ["space blueprint state",
         "com.atlassian.confluence.plugins.createcontent.PluginSettingsSpaceBlueprintStateController",
         "com.atlassian.confluence.plugins.confluence-create-content-plugin", "OPEN"],
        ["sidebar links",
         "com.atlassian.confluence.plugins.ia.impl.DefaultSidebarLinkManager",
         "com.atlassian.confluence.plugins.confluence-space-ia", "OPEN"],
        ["per-space plugin settings", SETTINGS_FACTORY, null, "OPEN"],

        ["control - space permissions read side",
         "com.atlassian.confluence.security.SpacePermissionManager", null, "CONTROL"],
        ["control - space reader",
         "com.atlassian.confluence.spaces.SpaceManager", null, "CONTROL"],
        ["control - bandana manager",
         "com.atlassian.bandana.BandanaManager", null, "CONTROL"],
    ]

    static Class resolveResponseClass() {
        try {
            return Class.forName("jakarta.ws.rs.core.Response")
        } catch (ClassNotFoundException ignored) {
            return Class.forName("javax.ws.rs.core.Response")
        }
    }

    static Object duck(Object target, String method, Object[] arguments) {
        return InvokerHelper.invokeMethod(target, method, arguments)
    }

    static Object ok(Class responseClass, String entity, String contentType) {
        Object builder = duck(responseClass, "ok", [entity] as Object[])
        builder = duck(builder, "type", [contentType] as Object[])
        return duck(builder, "build", new Object[0])
    }

    static String why(Throwable error) {
        if (error == null) {
            return null
        }
        String message = error.getMessage()
        String detail = error.getClass().getSimpleName() + (message == null ? "" : ": " + message)
        return detail.length() > 300 ? detail.substring(0, 300) + " [clamped]" : detail
    }

    static String param(Object queryParams, String name, String fallback) {
        try {
            Object value = duck(queryParams, "getFirst", [name] as Object[])
            String text = (value == null) ? null : value.toString().trim()
            return (text == null || text.isEmpty()) ? fallback : text
        } catch (Throwable ignored) {
            return fallback
        }
    }

    static List<String> namesOf(Object collection, int cap) {
        List<String> names = new ArrayList<String>()
        for (Object each : (Collection) collection) {
            if (names.size() >= cap) {
                break
            }
            names.add(each == null ? "null" : each.getClass().getName())
        }
        return names
    }

    /* ---- A. acquisition ---------------------------------------------------- */

    static Class<?> load(PluginAccessor pluginAccessor, String className, String pluginKey) {
        if (pluginKey == null) {
            return Class.forName(className)
        }
        Plugin plugin = pluginAccessor.getPlugin(pluginKey)
        ClassLoader loader = (plugin == null) ? null : plugin.getClassLoader()
        return (loader == null) ? Class.forName(className)
                                : Class.forName(className, false, loader)
    }

    /* Reflective throughout: the marker interface itself is one of the things
     * under test, so it is never named statically. */
    static List<Map> beansViaPlugin(PluginAccessor pluginAccessor, String pluginKey, Class<?> type) {
        List<Map> attempts = new ArrayList<Map>()
        Plugin plugin = pluginAccessor.getPlugin(pluginKey)
        if (plugin == null) {
            attempts.add([iface: null, outcome: "PLUGIN NOT INSTALLED", detail: pluginKey])
            return attempts
        }
        Class<?> marker = null
        try {
            marker = Class.forName(CONTAINER_MANAGED)
        } catch (Throwable error) {
            attempts.add([iface: null, outcome: "MARKER UNREACHABLE", detail: why(error)])
            return attempts
        }
        if (!marker.isInstance(plugin)) {
            attempts.add([iface: null, outcome: "PLUGIN NOT CONTAINER MANAGED",
                          detail: plugin.getClass().getName()])
            return attempts
        }
        Object accessor
        try {
            accessor = duck(plugin, "getContainerAccessor", new Object[0])
        } catch (Throwable error) {
            attempts.add([iface: null, outcome: "NO CONTAINER ACCESSOR", detail: why(error)])
            return attempts
        }
        List<Class<?>> types = new ArrayList<Class<?>>()
        types.add(type)
        for (Class<?> each : type.getInterfaces()) {
            types.add(each)
        }
        for (Class<?> candidate : types) {
            try {
                Object beans = duck(accessor, "getBeansOfType", [candidate] as Object[])
                int size = (beans == null) ? 0 : ((Collection) beans).size()
                attempts.add([iface: candidate.getName(),
                              outcome: size == 0 ? "NONE" : "FOUND " + size,
                              detail: size == 0 ? null : namesOf(beans, 3).join(", ")])
            } catch (Throwable error) {
                attempts.add([iface: candidate.getName(), outcome: "UNREADABLE", detail: why(error)])
            }
        }
        return attempts
    }

    static Map acquireOne(PluginAccessor pluginAccessor, List<String> candidate) {
        Map row = [
            label: candidate[0], type: candidate[1], plugin: candidate[2], group: candidate[3],
            resolved: "NOT RESOLVED", resolvedWhy: null,
            componentLocator: "NOT ATTEMPTED", componentLocatorWhy: null,
            pluginContainer: null,
        ]
        Class<?> type
        try {
            type = load(pluginAccessor, candidate[1], candidate[2])
            row.resolved = "RESOLVED"
        } catch (Throwable error) {
            row.resolvedWhy = why(error)
            return row
        }
        try {
            Object component = ComponentLocator.getComponent(type)
            row.componentLocator = (component == null) ? "NULL" : "PRESENT: " + component.getClass().getName()
        } catch (Throwable error) {
            row.componentLocator = "UNREADABLE"
            row.componentLocatorWhy = why(error)
        }
        if (candidate[2] != null) {
            row.pluginContainer = beansViaPlugin(pluginAccessor, candidate[2], type)
        }
        return row
    }

    /* ---- B, C, D. the property stores, keys only ---------------------------- */

    static Map settingsKeys(Object settingsFactory, String spaceKey) {
        Map row = [keyCount: null, sample: null, blueprintKeys: null, why: null]
        if (settingsFactory == null) {
            row.why = "ExtendedPluginSettingsFactory not acquired"
            return row
        }
        try {
            Object settings = duck(settingsFactory, "createSettingsForKey", [spaceKey] as Object[])
            Object keys = duck(settings, "getKeys", new Object[0])
            List<String> all = new ArrayList<String>()
            for (Object each : (Iterable) keys) {
                all.add(String.valueOf(each))
            }
            row.keyCount = all.size()
            row.sample = all.take(KEY_SAMPLE)
            row.blueprintKeys = all.findAll { it.toLowerCase().contains("blueprint") }.take(5)
        } catch (Throwable error) {
            row.why = why(error)
        }
        return row
    }

    static Map propertySmoke(SpaceManager spaceManager, BandanaManager bandanaManager,
                             Object settingsFactory, String requestedSpace, int limit) {
        Map result = [spaces: new ArrayList<Map>(), globalKeyCount: null, globalWhy: null,
                      inheritance: "UNKNOWN", spaceKeySource: null]
        List<String> keys = new ArrayList<String>()
        if (requestedSpace != null) {
            keys.add(requestedSpace)
            result.spaceKeySource = "space parameter"
        } else {
            try {
                List<String> all = new ArrayList<String>(spaceManager.getAllSpaceKeys(SpaceStatus.CURRENT))
                Collections.sort(all)
                keys.addAll(all.take(limit))
                result.spaceKeySource = "first " + keys.size() + " of " + all.size() + " current spaces, key order"
            } catch (Throwable error) {
                result.spaceKeySource = "UNREADABLE: " + why(error)
                return result
            }
        }

        Set<String> globalKeys = new LinkedHashSet<String>()
        try {
            for (String key : bandanaManager.getKeys(ConfluenceBandanaContext.GLOBAL_CONTEXT)) {
                globalKeys.add(key)
            }
            result.globalKeyCount = globalKeys.size()
        } catch (Throwable error) {
            result.globalWhy = why(error)
        }

        boolean anyOverlap = false
        boolean anyRead = false
        for (String key : keys) {
            Map row = [space: key, bandanaKeyCount: null, bandanaSample: null,
                       overlapWithGlobal: null, bandanaWhy: null, pluginSettings: null]
            try {
                List<String> spaceKeys = new ArrayList<String>()
                for (String each : bandanaManager.getKeys(new ConfluenceBandanaContext(key))) {
                    spaceKeys.add(each)
                }
                row.bandanaKeyCount = spaceKeys.size()
                row.bandanaSample = spaceKeys.take(KEY_SAMPLE)
                if (result.globalKeyCount != null) {
                    int overlap = spaceKeys.findAll { globalKeys.contains(it) }.size()
                    row.overlapWithGlobal = overlap
                    if (overlap > 0) {
                        anyOverlap = true
                    }
                }
                anyRead = true
            } catch (Throwable error) {
                row.bandanaWhy = why(error)
            }
            row.pluginSettings = settingsKeys(settingsFactory, key)
            ((List) result.spaces).add(row)
        }

        if (!anyRead || result.globalKeyCount == null) {
            result.inheritance = "UNKNOWN - not enough was read to say"
        } else if (anyOverlap) {
            result.inheritance = "KEYS OVERLAP GLOBAL - a space listing may be inheriting, do not trust the bytecode reading"
        } else {
            result.inheritance = "NO OVERLAP - consistent with getKeys delegating straight to the persister"
        }
        return result
    }

    static Object settingsFactory() {
        try {
            return ComponentLocator.getComponent(Class.forName(SETTINGS_FACTORY))
        } catch (Throwable ignored) {
            return null
        }
    }

    static Map run(PluginAccessor pluginAccessor, SpaceManager spaceManager,
                   BandanaManager bandanaManager, String requestedSpace, int limit) {
        List<Map> rows = new ArrayList<Map>()
        for (List<String> candidate : ACQUIRE) {
            rows.add(acquireOne(pluginAccessor, candidate))
        }
        List<Map> controls = rows.findAll { it.group == "CONTROL" }
        List<Map> failed = controls.findAll { !((String) it.componentLocator).startsWith("PRESENT") }
        return [
            version        : VERSION,
            pluginCount    : pluginAccessor.getPlugins().size(),
            controlsTotal  : controls.size(),
            controlsFailed : failed.size(),
            verdictReadable: failed.isEmpty(),
            verdictNote    : failed.isEmpty()
                ? "Controls all acquired. The open verdicts below are readable."
                : "A CONTROL was not acquired. The harness is suspect and NO verdict may be read out of this run.",
            acquisition    : rows,
            properties     : propertySmoke(spaceManager, bandanaManager, settingsFactory(),
                                           requestedSpace, limit),
            valuesNote     : "Property VALUES are never read by this probe. They are XStream-serialised objects and apps store secrets in them.",
        ]
    }
}

spaceConfigProbe(httpMethod: "GET", groups: ["confluence-administrators"]) { queryParams, body ->
    Class responseClass = Probe.resolveResponseClass()
    PluginAccessor pluginAccessor = ComponentLocator.getComponent(PluginAccessor.class)
    SpaceManager spaceManager = ComponentLocator.getComponent(SpaceManager.class)
    BandanaManager bandanaManager = ComponentLocator.getComponent(BandanaManager.class)

    String requestedSpace = Probe.param(queryParams, "space", null)
    String limitText = Probe.param(queryParams, "limit", "5")
    int limit = 5
    try {
        limit = Integer.parseInt(limitText)
    } catch (NumberFormatException ignored) {
        limit = 5
    }
    if (limit < 1) {
        limit = 1
    }
    if (limit > Probe.SPACE_LIMIT_MAX) {
        limit = Probe.SPACE_LIMIT_MAX
    }

    Map result = Probe.run(pluginAccessor, spaceManager, bandanaManager, requestedSpace, limit)
    return Probe.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(result)),
                    "application/json; charset=UTF-8")
}
