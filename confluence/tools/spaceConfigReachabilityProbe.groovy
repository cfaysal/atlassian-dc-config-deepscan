/* =============================================================================
 * Confluence Data Center - ScriptRunner reachability and acquisition probe
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only.
 *
 * Version 0.2. The endpoint name is unchanged from 0.1 on purpose, so replacing
 * the file in the script root is the whole deployment and the registered
 * endpoint does not have to be touched.
 *
 * What 0.1 established, measured on confluence-test, groovyrunner 10.16.0
 *   The Import-Package header does NOT predict what a script can reach. Five of
 *   the seven packages absent from it resolved directly anyway. The boundary
 *   that actually exists is a different one:
 *
 *     Confluence PRODUCT classes resolve directly, internal and dmz included.
 *     Bundled PLUGIN classes do not, and need the exporting plugin classloader.
 *
 *   That is the same boundary the Jira sibling hit on Service Management, where
 *   the types also lived in a plugin bundle (OP-1002).
 *
 *   It also moved the open question rather than closing it. Five rows resolved
 *   and still handed back no component. Resolution and acquisition are two
 *   questions, and 0.1 could only answer the first.
 *
 * What 0.2 asks
 *   A - ACQUISITION, per route rather than assuming ComponentLocator is the only
 *       one. For a plugin-owned type the route is the exporting plugin container,
 *       asked for beans of each interface the implementation declares. That way
 *       the interface is discovered from the class rather than guessed.
 *   B - THE PROPERTY SECTION, functionally, because it is the highest-value
 *       section and the one no administration screen shows. Keys only. Values are
 *       never read, never rendered and never logged: they are XStream-serialised
 *       objects and apps put secrets in them.
 *       The space context is built from the KEY, not from a Space object:
 *       ConfluenceBandanaContext(String) exists, javap-verified on 10.2.14, so
 *       the deprecated space getter is not needed.
 *   C - INHERITANCE, empirically. Bytecode shows getKeys delegating straight to
 *       the persister with no parent walk. This compares a space key count with
 *       the global one, so the reading is confirmed against the running instance
 *       instead of only against the decompiler.
 *
 * Reporting discipline
 *   Per item, never one verdict for the run. A failed read carries its reason in
 *   the row. The control group stays: if a control fails, the harness is suspect
 *   and no verdict may be read out of the run.
 *
 *   Note carried over from 0.1: on a row that already resolved directly, the
 *   viaPlugin column is noise, because every plugin classloader delegates to the
 *   product one. It is informative only where the direct read failed.
 *
 * Parameters
 *   space=<KEY>   optional. Without it the first spaces in key order are used.
 *   limit=<n>     optional, default 5, capped at 25. How many spaces to smoke.
 *
 * Platform
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameters are untyped, so no jakarta.* or javax.* import appears.
 * ========================================================================== */

import com.atlassian.bandana.BandanaManager

import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.confluence.spaces.SpaceStatus

import com.atlassian.plugin.ContainerManagedPlugin
import com.atlassian.plugin.Plugin
import com.atlassian.plugin.PluginAccessor
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import org.codehaus.groovy.runtime.InvokerHelper

@BaseScript CustomEndpointDelegate delegate

class Probe {

    static final String VERSION = "0.2"
    static final int KEY_SAMPLE = 12
    static final int SPACE_LIMIT_MAX = 25

    /* [label, class, owning plugin key or null for a product class, group] */
    static final List<List<String>> ACQUIRE = [
        ["JSON space property store",
         "com.atlassian.confluence.api.service.content.SpacePropertyService", null, "OPEN"],
        ["effective space permissions",
         "com.atlassian.confluence.security.denormalisedpermissions.impl.space.EffectiveSpacePermissionsCalculator",
         null, "OPEN"],
        ["space blueprint state",
         "com.atlassian.confluence.plugins.createcontent.PluginSettingsSpaceBlueprintStateController",
         "com.atlassian.confluence.plugins.confluence-create-content-plugin", "OPEN"],
        ["sidebar links",
         "com.atlassian.confluence.plugins.ia.impl.DefaultSidebarLinkManager",
         "com.atlassian.confluence.plugins.confluence-space-ia", "OPEN"],
        ["per-space plugin settings",
         "com.atlassian.confluence.api.service.settings.ExtendedPluginSettingsFactory", null, "OPEN"],

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

    static String param(Map queryParams, String name, String fallback) {
        try {
            Object value = duck(queryParams, "getFirst", [name] as Object[])
            String text = (value == null) ? null : value.toString().trim()
            return (text == null || text.isEmpty()) ? fallback : text
        } catch (Throwable ignored) {
            return fallback
        }
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

    /* The interface is read off the implementation rather than guessed, because a
     * guessed bean type proves nothing when it comes back empty. */
    static List<Map> beansViaPlugin(PluginAccessor pluginAccessor, String pluginKey, Class<?> type) {
        List<Map> attempts = new ArrayList<Map>()
        Plugin plugin = pluginAccessor.getPlugin(pluginKey)
        if (plugin == null) {
            attempts.add([iface: null, outcome: "PLUGIN NOT INSTALLED", detail: pluginKey])
            return attempts
        }
        if (!(plugin instanceof ContainerManagedPlugin)) {
            attempts.add([iface: null, outcome: "PLUGIN NOT CONTAINER MANAGED",
                          detail: plugin.getClass().getName()])
            return attempts
        }
        Object accessor = ((ContainerManagedPlugin) plugin).getContainerAccessor()
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
                              detail: size == 0 ? null
                                    : ((Collection) beans).collect { it.getClass().getName() }.take(3).join(", ")])
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
        Class<?> type = null
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

    /* ---- B and C. the property section, keys only --------------------------- */

    static Map propertySmoke(SpaceManager spaceManager, BandanaManager bandanaManager,
                             String requestedSpace, int limit) {
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
            Map row = [space: key, keyCount: null, sample: null, overlapWithGlobal: null, why: null]
            try {
                List<String> spaceKeys = new ArrayList<String>()
                for (String each : bandanaManager.getKeys(new ConfluenceBandanaContext(key))) {
                    spaceKeys.add(each)
                }
                row.keyCount = spaceKeys.size()
                row.sample = spaceKeys.take(KEY_SAMPLE)
                if (result.globalKeyCount != null) {
                    int overlap = spaceKeys.count { globalKeys.contains(it) }
                    row.overlapWithGlobal = overlap
                    if (overlap > 0) {
                        anyOverlap = true
                    }
                }
                anyRead = true
            } catch (Throwable error) {
                row.why = why(error)
            }
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
            properties     : propertySmoke(spaceManager, bandanaManager, requestedSpace, limit),
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
    int limit = 5
    try {
        limit = Integer.parseInt(Probe.param(queryParams, "limit", "5"))
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
