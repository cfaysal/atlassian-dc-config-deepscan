/* =============================================================================
 * Confluence Data Center - ScriptRunner classloader reachability probe
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only.
 *
 * Why this exists
 *   A class proven present in a jar on disk is not necessarily resolvable from
 *   inside a ScriptRunner script. The script classloader hangs off the
 *   ScriptRunner bundle, and that bundle resolves only what its OSGi headers
 *   allow. Measured on this instance 2026-08-27: groovyrunner 10.16.0 declares
 *   NO DynamicImport-Package header at all, and its Import-Package names 476
 *   packages. Six packages the space configuration deep scan needs are absent
 *   from that list, and a seventh is a sibling package of two that are present.
 *
 *   The manifest proves how the bundle is wired. It does not prove a script
 *   cannot reach a type, because ScriptRunner compiles through a chaining
 *   classloader whose reach the manifest does not describe. So the question is
 *   settled by asking the running instance, not by reading the header.
 *
 *   The Jira sibling lost a live-discovery round to exactly this on Service
 *   Management (OP-1002): four packages resolved, three raised
 *   ClassNotFoundException, and the split looked like three broken class names
 *   rather than one import boundary.
 *
 * Reporting discipline
 *   PER PACKAGE, never one pass or fail for the run. A partial failure and a
 *   total failure must not look alike.
 *
 *   The candidate list carries a CONTROL group of packages that ARE in
 *   Import-Package. If a control fails, the harness is broken and no verdict
 *   about an at-risk package may be read out of this run. A probe whose
 *   controls have never been green proves nothing.
 *
 *   Three questions are asked separately, because they have different answers:
 *     direct    - does Class.forName resolve it from the script own loader
 *     viaPlugin - can any installed plugin classloader load it, and which
 *     component - does ComponentLocator hand back an instance of it
 *   A type can resolve and still not be obtainable as a component.
 *
 *   A failed read carries its reason in the row, never only into the log.
 *
 * Deliberate non-goals
 *   No space is read, no content is touched, nothing is written. No type under
 *   probe is ever named statically: every one is a string, so this file compiles
 *   whether or not any of them resolves.
 *
 * Platform
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameters are untyped, so no jakarta.* or javax.* import appears.
 * ========================================================================== */

import com.atlassian.plugin.Plugin
import com.atlassian.plugin.PluginAccessor
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import org.codehaus.groovy.runtime.InvokerHelper

@BaseScript CustomEndpointDelegate delegate

class Probe {

    static final String VERSION = "0.1"

    /* [package, fully qualified class, role, group]
     * AT-RISK: absent from the measured Import-Package of groovyrunner 10.16.0.
     * CONTROL: present in it. A failing control invalidates the whole run. */
    static final List<List<String>> CANDIDATES = [
        ["com.atlassian.confluence.setup.bandana",
         "com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext",
         "space property context - without it BandanaManager.getKeys cannot be called",
         "AT-RISK"],
        ["com.atlassian.confluence.content",
         "com.atlassian.confluence.content.CustomContentManager",
         "custom content types present in a space",
         "AT-RISK"],
        ["com.atlassian.confluence.plugins.createcontent",
         "com.atlassian.confluence.plugins.createcontent.PluginSettingsSpaceBlueprintStateController",
         "blueprints disabled on a space",
         "AT-RISK"],
        ["com.atlassian.confluence.dmz.pages",
         "com.atlassian.confluence.dmz.pages.PageManagerInternal",
         "restricted page and undefined link counts",
         "AT-RISK"],
        ["com.atlassian.confluence.internal.security",
         "com.atlassian.confluence.internal.security.SpacePermissionManagerInternal",
         "effective, denormalised space permissions",
         "AT-RISK"],
        ["com.atlassian.confluence.api.service.settings",
         "com.atlassian.confluence.api.service.settings.ExtendedPluginSettingsFactory",
         "per-space plugin settings with key enumeration",
         "AT-RISK"],
        ["com.atlassian.confluence.plugins.ia.impl",
         "com.atlassian.confluence.plugins.ia.impl.DefaultSidebarLinkManager",
         "sidebar links - ia and ia.service ARE imported, ia.impl is not",
         "AT-RISK"],

        ["com.atlassian.confluence.spaces",
         "com.atlassian.confluence.spaces.SpaceManager",
         "control - the space reader itself",
         "CONTROL"],
        ["com.atlassian.bandana",
         "com.atlassian.bandana.BandanaManager",
         "control - the manager whose context class is at risk",
         "CONTROL"],
        ["com.atlassian.confluence.security",
         "com.atlassian.confluence.security.SpacePermissionManager",
         "control - the permission grid",
         "CONTROL"],
        ["com.atlassian.confluence.pages.templates",
         "com.atlassian.confluence.pages.templates.PageTemplateManager",
         "control - space and global templates",
         "CONTROL"],
        ["com.atlassian.confluence.themes",
         "com.atlassian.confluence.themes.ThemeManager",
         "control - look and feel",
         "CONTROL"],
        ["com.atlassian.confluence.labels",
         "com.atlassian.confluence.labels.SpaceLabelManager",
         "control - space categories",
         "CONTROL"],
        ["com.atlassian.confluence.renderer",
         "com.atlassian.confluence.renderer.ShortcutLinksManager",
         "control - space shortcuts",
         "CONTROL"],
        ["com.atlassian.confluence.api.service.content",
         "com.atlassian.confluence.api.service.content.SpacePropertyService",
         "control - the JSON space property store",
         "CONTROL"],
    ]

    /* Same helper as the Jira sibling: the JAX-RS namespace a ScriptRunner script
     * needs follows the ScriptRunner version, not the product version. */
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
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message)
    }

    /* Which installed plugin, if any, can load this type. Reported rather than
     * assumed: naming a plugin key up front would only prove the guess. */
    static List<String> loaders(PluginAccessor pluginAccessor, String className, int cap) {
        List<String> found = new ArrayList<String>()
        for (Plugin plugin : pluginAccessor.getPlugins()) {
            if (found.size() >= cap) {
                break
            }
            ClassLoader loader = null
            try {
                loader = plugin.getClassLoader()
            } catch (Throwable ignored) {
                continue
            }
            if (loader == null) {
                continue
            }
            try {
                Class.forName(className, false, loader)
                found.add(plugin.getKey())
            } catch (Throwable ignored) {
            }
        }
        return found
    }

    static Map probeOne(PluginAccessor pluginAccessor, List<String> candidate, int cap) {
        Map row = [
            pack        : candidate[0],
            type        : candidate[1],
            role        : candidate[2],
            group       : candidate[3],
            direct      : "NOT ATTEMPTED",
            directWhy   : null,
            viaPlugin   : "NOT ATTEMPTED",
            plugins     : null,
            viaPluginWhy: null,
            component   : "NOT ATTEMPTED",
            componentWhy: null,
        ]
        Class<?> resolved = null
        try {
            resolved = Class.forName(candidate[1])
            row.direct = "RESOLVED"
        } catch (Throwable error) {
            row.direct = "NOT RESOLVED"
            row.directWhy = why(error)
        }
        try {
            List<String> keys = loaders(pluginAccessor, candidate[1], cap)
            row.plugins = keys
            row.viaPlugin = keys.isEmpty() ? "NO PLUGIN LOADS IT" : "LOADABLE"
            if (resolved == null && !keys.isEmpty()) {
                Plugin owner = pluginAccessor.getPlugin(keys.get(0))
                if (owner != null && owner.getClassLoader() != null) {
                    resolved = Class.forName(candidate[1], false, owner.getClassLoader())
                }
            }
        } catch (Throwable error) {
            row.viaPlugin = "UNREADABLE"
            row.viaPluginWhy = why(error)
        }
        if (resolved != null) {
            try {
                Object component = ComponentLocator.getComponent(resolved)
                row.component = (component == null) ? "NULL" : "PRESENT"
            } catch (Throwable error) {
                row.component = "UNREADABLE"
                row.componentWhy = why(error)
            }
        }
        return row
    }

    static Map run(PluginAccessor pluginAccessor, int cap) {
        List<Map> rows = new ArrayList<Map>()
        for (List<String> candidate : CANDIDATES) {
            rows.add(probeOne(pluginAccessor, candidate, cap))
        }
        List<Map> controls = rows.findAll { it.group == "CONTROL" }
        List<Map> failedControls = controls.findAll { it.direct != "RESOLVED" }
        return [
            version         : VERSION,
            pluginCount     : pluginAccessor.getPlugins().size(),
            controlsTotal   : controls.size(),
            controlsFailed  : failedControls.size(),
            verdictReadable : failedControls.isEmpty(),
            verdictNote     : failedControls.isEmpty()
                ? "Controls all resolved. The at-risk verdicts below are readable."
                : "A CONTROL failed. The harness is suspect and NO at-risk verdict may be read out of this run.",
            rows            : rows,
        ]
    }
}

spaceConfigProbe(httpMethod: "GET", groups: ["confluence-administrators"]) { queryParams, body ->
    Class responseClass = Probe.resolveResponseClass()
    PluginAccessor pluginAccessor = ComponentLocator.getComponent(PluginAccessor.class)
    Map result = Probe.run(pluginAccessor, 3)
    return Probe.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(result)),
                    "application/json; charset=UTF-8")
}
