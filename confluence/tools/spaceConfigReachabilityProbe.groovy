/* =============================================================================
 * Confluence Data Center - ScriptRunner reachability, acquisition and SQL probe
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only.
 *
 * Version 0.5. The endpoint name has not changed since 0.1, so replacing the
 * file in the script root is the whole deployment.
 *
 * What earlier passes established, measured on confluence-test
 *   0.1  The ScriptRunner Import-Package header does not predict what a script
 *        can reach. The boundary that exists is: Confluence PRODUCT classes
 *        resolve directly, internal and dmz included; bundled PLUGIN classes do
 *        not and need the exporting plugin classloader.
 *   0.4  Resolution and acquisition are different questions. The plugin
 *        container route works. SpacePropertyService and
 *        EffectiveSpacePermissionsCalculator resolve and yield NO component:
 *        the API service layer is a Spring proxy the chaining classloader
 *        cannot use. BandanaManager.getKeys does not inherit global keys,
 *        confirmed against a positive global control.
 *
 * What direct SQL then showed, and why this pass exists
 *   Per-space application configuration in PLUGIN_SETTING is not stored only
 *   under the bare space key. The real namespaces on this instance are
 *   com.atlassian.confluence.blueprints.plugin-module-state:ENG and the same
 *   for HR, alongside a bare DEV. The form is <plugin namespace>:<SPACEKEY>.
 *
 *   createSettingsForKey(spaceKey) reaches only the bare form. A section built
 *   on it would report "no application configuration" for a space that has
 *   some, and the PluginSettings API cannot enumerate namespaces at all. Only
 *   SQL can.
 *
 *   So this pass asks whether the endpoint may run SQL at all, and then proves
 *   the gap on the instance rather than asserting it: the same space read both
 *   ways, side by side, in one output.
 *
 * The trade-off, stated rather than absorbed
 *   The Confluence schema is not a public API. SQL is the route of last resort,
 *   used only where the Java API demonstrably cannot answer, and any section
 *   resting on it must be labelled schema-coupled in the report. This probe uses
 *   createReadOnly() and every statement here is a SELECT.
 *
 * Reporting discipline
 *   Per item, never one verdict for the run. A failed read carries its reason.
 *   Controls stay: if a control fails, the harness is suspect and no verdict may
 *   be read out of the run.
 *   Only types MEASURED reachable are imported statically. Everything under test
 *   is loaded by name and called reflectively, which is why 0.1 could not fail
 *   to start and 0.2 could.
 *
 * Parameters
 *   spaces=A,B,C  optional, default DEV,ENG,HR - the three spaces this instance
 *                 is known to carry per-space configuration for.
 *
 * Platform
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameters are untyped, so no jakarta.* or javax.* import appears.
 * ========================================================================== */

/* Every import below is a type these probes measured as reachable. */
import com.atlassian.bandana.BandanaManager

import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext

import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import org.codehaus.groovy.runtime.InvokerHelper

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

@BaseScript CustomEndpointDelegate delegate

class Probe {

    static final String VERSION = "0.5"
    static final int ROW_CAP = 25

    static final String EXECUTOR_FACTORY = "com.atlassian.sal.api.rdbms.TransactionalExecutorFactory"
    static final String CONNECTION_CALLBACK = "com.atlassian.sal.api.rdbms.ConnectionCallback"
    static final String SETTINGS_FACTORY =
        "com.atlassian.confluence.api.service.settings.ExtendedPluginSettingsFactory"

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

    static Object component(String className) {
        return ComponentLocator.getComponent(Class.forName(className))
    }

    /* ---- SQL, read only ----------------------------------------------------- */

    /* The callback interface is loaded by name and implemented by a JDK proxy, so
     * this file never names a SAL rdbms type statically and still compiles where
     * the package is absent. */
    static Object withConnection(Object executorFactory, Closure body) {
        Class callbackType = Class.forName(CONNECTION_CALLBACK)
        Object executor = duck(executorFactory, "createReadOnly", new Object[0])
        Object callback = Proxy.newProxyInstance(
            callbackType.getClassLoader(), [callbackType] as Class[],
            new InvocationHandler() {
                Object invoke(Object proxy, Method method, Object[] arguments) {
                    String name = method.getName()
                    if (name == "execute") {
                        return body.call(arguments[0])
                    }
                    if (name == "toString") {
                        return "spaceConfigProbe-callback"
                    }
                    if (name == "hashCode") {
                        return Integer.valueOf(System.identityHashCode(proxy))
                    }
                    if (name == "equals") {
                        return Boolean.valueOf(proxy.is(arguments[0]))
                    }
                    return null
                }
            })
        return duck(executor, "execute", [callback] as Object[])
    }

    static List<Map> query(Connection connection, String sql, List<String> arguments,
                           List<String> columns) {
        List<Map> rows = new ArrayList<Map>()
        PreparedStatement statement = connection.prepareStatement(sql)
        try {
            for (int index = 0; index < arguments.size(); index++) {
                statement.setString(index + 1, arguments.get(index))
            }
            ResultSet results = statement.executeQuery()
            try {
                while (results.next() && rows.size() < ROW_CAP) {
                    Map row = new LinkedHashMap()
                    for (String column : columns) {
                        row.put(column, results.getString(column))
                    }
                    rows.add(row)
                }
            } finally {
                results.close()
            }
        } finally {
            statement.close()
        }
        return rows
    }

    static Map sqlForSpace(Connection connection, String spaceKey) {
        Map out = [space: spaceKey]
        try {
            out.put("pluginSettingNamespaces", query(connection,
                "SELECT namespace, setting_key FROM plugin_setting " +
                "WHERE namespace = ? OR namespace LIKE ? ORDER BY namespace, setting_key",
                [spaceKey, "%:" + spaceKey], ["namespace", "setting_key"]))
        } catch (Throwable error) {
            out.put("pluginSettingNamespacesWhy", why(error))
        }
        try {
            out.put("spaceDescriptionProperties", query(connection,
                "SELECT cp.propertyname FROM contentproperties cp " +
                "JOIN spaces s ON s.spacedescid = cp.contentid WHERE s.spacekey = ? " +
                "ORDER BY cp.propertyname",
                [spaceKey], ["propertyname"]))
        } catch (Throwable error) {
            out.put("spaceDescriptionPropertiesWhy", why(error))
        }
        try {
            out.put("permissionGrants", query(connection,
                "SELECT p.permtype, p.permgroupname, p.permusername, p.permalluserssubject, " +
                "p.creator, p.creationdate FROM spacepermissions p " +
                "JOIN spaces s ON s.spaceid = p.spaceid WHERE s.spacekey = ? " +
                "ORDER BY p.permtype",
                [spaceKey], ["permtype", "permgroupname", "permusername",
                             "permalluserssubject", "creator", "creationdate"]))
        } catch (Throwable error) {
            out.put("permissionGrantsWhy", why(error))
        }
        return out
    }

    /* ---- the same space, read the other way -------------------------------- */

    static Map apiForSpace(Object settingsFactory, BandanaManager bandanaManager, String spaceKey) {
        Map out = [space: spaceKey]
        if (settingsFactory == null) {
            out.put("pluginSettingsWhy", "ExtendedPluginSettingsFactory not acquired")
        } else {
            try {
                Object settings = duck(settingsFactory, "createSettingsForKey", [spaceKey] as Object[])
                List<String> keys = new ArrayList<String>()
                for (Object each : (Iterable) duck(settings, "getKeys", new Object[0])) {
                    keys.add(String.valueOf(each))
                }
                out.put("pluginSettingsKeys", keys)
            } catch (Throwable error) {
                out.put("pluginSettingsWhy", why(error))
            }
        }
        try {
            List<String> keys = new ArrayList<String>()
            for (String each : bandanaManager.getKeys(new ConfluenceBandanaContext(spaceKey))) {
                keys.add(each)
            }
            out.put("bandanaKeys", keys)
        } catch (Throwable error) {
            out.put("bandanaWhy", why(error))
        }
        return out
    }

    static Map run(BandanaManager bandanaManager, List<String> spaceKeys) {
        Map result = [version: VERSION, spacesProbed: spaceKeys]

        Object settingsFactory
        try {
            settingsFactory = component(SETTINGS_FACTORY)
        } catch (Throwable ignored) {
            settingsFactory = null
        }

        /* CONTROL: a component already proven acquirable. If this fails the
         * harness is broken and nothing below may be read as a verdict. */
        boolean controlOk
        try {
            controlOk = (ComponentLocator.getComponent(BandanaManager.class) != null)
        } catch (Throwable ignored) {
            controlOk = false
        }
        result.put("controlBandanaAcquired", controlOk)
        result.put("controlSettingsFactoryAcquired", settingsFactory != null)

        Object executorFactory
        try {
            executorFactory = component(EXECUTOR_FACTORY)
            result.put("sqlFactory", executorFactory == null
                ? "NULL" : "PRESENT: " + executorFactory.getClass().getName())
        } catch (Throwable error) {
            executorFactory = null
            result.put("sqlFactory", "UNREADABLE")
            result.put("sqlFactoryWhy", why(error))
        }

        List<Map> viaApi = new ArrayList<Map>()
        for (String key : spaceKeys) {
            viaApi.add(apiForSpace(settingsFactory, bandanaManager, key))
        }
        result.put("viaApi", viaApi)

        if (executorFactory == null) {
            result.put("viaSql", null)
            result.put("viaSqlWhy", "the SAL rdbms factory was not acquired, so no statement was attempted")
            result.put("verdict", "SQL UNAVAILABLE - namespace enumeration, grant provenance and the space description property store have no route yet")
            return result
        }

        try {
            Object sql = withConnection(executorFactory, { Connection connection ->
                Map inner = new LinkedHashMap()
                List<Map> perSpace = new ArrayList<Map>()
                for (String key : spaceKeys) {
                    perSpace.add(sqlForSpace(connection, key))
                }
                inner.put("perSpace", perSpace)
                inner.put("catalog", connection.getCatalog())
                return inner
            })
            result.put("viaSql", sql)
            result.put("verdict", "SQL AVAILABLE - compare viaApi against viaSql for the same space")
        } catch (Throwable error) {
            result.put("viaSql", null)
            result.put("viaSqlWhy", why(error))
            result.put("verdict", "SQL FACTORY ACQUIRED BUT THE STATEMENT FAILED - see viaSqlWhy, which is not the same as unavailable")
        }
        return result
    }
}

spaceConfigProbe(httpMethod: "GET", groups: ["confluence-administrators"]) { queryParams, body ->
    Class responseClass = Probe.resolveResponseClass()
    BandanaManager bandanaManager = ComponentLocator.getComponent(BandanaManager.class)

    String requested = Probe.param(queryParams, "spaces", "DEV,ENG,HR")
    List<String> spaceKeys = new ArrayList<String>()
    for (String piece : requested.split(",")) {
        String trimmed = piece.trim()
        if (!trimmed.isEmpty() && spaceKeys.size() < 10) {
            spaceKeys.add(trimmed)
        }
    }

    Map result = Probe.run(bandanaManager, spaceKeys)
    return Probe.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(result)),
                    "application/json; charset=UTF-8")
}
