/* =============================================================================
 * Confluence Data Center - Space Configuration Deep Scan
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only: every
 * statement this file issues is a SELECT and every component call is a getter.
 *
 * Version
 *   Declared once as Pc.VERSION below and printed by every output channel: the
 *   HTML report, the JSON and the CSV. The number lives in exactly one place, so
 *   this header cannot drift away from the code.
 *
 * Purpose
 *   One space in, its configuration out. Not the list of screens an
 *   administrator could visit - the values behind them, expanded, each one
 *   linked to the exact administration screen where it is maintained.
 *
 *   Space details. Permissions with their provenance, which is who granted a
 *   permission and when: that appears on no administration screen and is the
 *   reason that section exists. The space property stores apps write into, which
 *   no administration screen shows at all. Look and feel. Templates. Space
 *   categories.
 *
 * What this endpoint deliberately does NOT do
 *   No content counting, no CQL, no content search. This report answers "how is
 *   this space configured", never "how much content is in it". That keeps the run
 *   cheap enough to be harmless on a production instance.
 *
 * Read paths, and the rule that decides between them
 *   SQL for what Confluence stores. API for what Confluence computes.
 *
 *   Sections 1, 2 and part of 3 read the database directly and are labelled
 *   schema-coupled in the report itself, because the Confluence schema is not a
 *   public API. That is not a shortcut. SpacePermissionManager has no method that
 *   returns all permissions of a space - its reads are per subject - and grant
 *   provenance is not on the object at all. Per-space application configuration
 *   in PLUGIN_SETTING is stored under <plugin namespace>:<SPACEKEY> as well as
 *   under the bare key, the PluginSettings API can reach only the bare form, and
 *   it cannot enumerate namespaces at any rate.
 *
 *   Sections 4, 5 and 6 are computed values - a resolved theme, a template list,
 *   the labels on a space - and go through the API.
 *
 *   Every statement is a SELECT with bound parameters. No value is ever
 *   interpolated into SQL. Every statement is preceded by a SHAPE CHECK against
 *   the database catalogue: if a column an upgrade removed is missing, the
 *   section renders UNREADABLE and names the column, because a schema change must
 *   never surface as an empty result.
 *
 * Platform
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameter is untyped, so no jakarta.* or javax.* import is
 *   present. The namespace a ScriptRunner script needs follows the SCRIPTRUNNER
 *   version, not the Confluence version: ScriptRunner 10.x and above use
 *   jakarta.ws.rs.*, 8.x to 9.x use javax.ws.rs.*. This file runs on either line
 *   without being edited.
 *
 *   Measured on Confluence DC 10.2.14 with groovyrunner 10.16.0: the ScriptRunner
 *   Import-Package header does NOT predict what a script can reach. The boundary
 *   that exists is that Confluence PRODUCT classes resolve directly from a
 *   script, internal and dmz included, while bundled PLUGIN classes do not. Only
 *   types measured ACQUIRABLE on the instance are imported statically here.
 *   Everything else - the SAL rdbms types, ColourSchemeManager,
 *   ApplicationProperties, every value object - is loaded by name and called
 *   reflectively, so a type that moved cannot stop this file from compiling. A
 *   file that cannot compile answers nothing.
 *
 * Parameters
 *   space=<KEY>               none      the space to report on. Without it the
 *                                       endpoint renders the space picker.
 *   format=html|json|csv      default html
 *   depth=full|collapsed      default collapsed   collapsed opens the sections
 *                                                  and leaves every card inside
 *                                                  them closed; full opens all
 *   values=true|false         default false  property VALUES are not read at all
 *                                            unless this is set. See below.
 *
 * Reporting discipline
 *   A failed read is never rendered as an empty child list. A node with no
 *   children and a node whose children could not be read look different in the
 *   report, and the reason travels inside the node rather than only in the log.
 *
 *   Any capped list announces its cap inside the node that carries it. The
 *   reachability probe that preceded this file got that wrong: it returned
 *   exactly 25 permission rows, which was its cap, and said nothing.
 *
 *   A deep link that is not backed by primary evidence is never guessed. Such a
 *   node carries no link and states the navigation path in plain words instead.
 *   The provenance of every link shape used here is recorded in Dl below.
 *
 *   No state-changing URL is ever emitted, in any format. The watch and unwatch
 *   web-items carry an XSRF token and removespace is the Delete Space screen;
 *   none of them appears here, and the offline suite asserts it.
 *
 *   Property VALUES are not read unless values=true is passed, and even then a
 *   key whose name matches secret, token, password, apikey or credential renders
 *   as REDACTED - a state of its own, neither absent nor unreadable. The values
 *   are XStream-serialised objects that apps put secrets into, and this report is
 *   meant to be exported.
 * ========================================================================== */

/* Every import below is a type MEASURED on the instance, either acquirable as a
 * component or proven by the reachability probe that preceded this file. Nothing
 * else is named statically anywhere in this file. */
import com.atlassian.bandana.BandanaManager

import com.atlassian.confluence.labels.SpaceLabelManager
import com.atlassian.confluence.pages.templates.PageTemplateManager
import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.confluence.themes.ThemeManager

import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import org.codehaus.groovy.runtime.InvokerHelper

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@BaseScript CustomEndpointDelegate delegate

/* =============================================================================
 * Utility - deliberately free of any Confluence type so it stays unit-testable
 * ========================================================================== */

class Pc {

    static final String NA = "—"

    /* The single place the report version lives. The file header points here and
     * every output channel prints this constant, so a report always names the
     * build that produced it. */
    static final String VERSION = "0.1"

    /* Node states. A node is not just present or absent: it can be present but
     * unreadable, and the report has to keep those apart.
     *
     * REDACTED is the fifth, and it is not a variant of the other four. A
     * property value withheld on purpose is neither missing nor broken, and a
     * reader who cannot tell those apart will go looking for a failure that never
     * happened - or worse, take a redaction for an absence and conclude the app
     * stores nothing there. */
    static final String READ = "read"
    static final String UNREADABLE = "unreadable"
    static final String ABSENT = "absent"
    static final String TRUNCATED = "truncated"
    static final String REDACTED = "redacted"

    /* ---- escaping and formatting --------------------------------------- */

    static String html(Object value) {
        if (value == null) {
            return ""
        }
        return value.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    static String csv(Object value) {
        if (value == null) {
            return "\"\""
        }
        return "\"" + value.toString().replace("\"", "\"\"") + "\""
    }

    static String text(Object value) {
        if (value == null) {
            return null
        }
        String out = value.toString().trim()
        return out.isEmpty() ? null : out
    }

    static String orNa(Object value) {
        String out = text(value)
        return out == null ? NA : out
    }

    /* URL-encoding for a path segment. A space key is uppercase alphanumeric in
     * practice, but a personal space key is not - it is the user key, and an
     * imported space key is not guaranteed to be either. A raw key in a path is
     * how a link silently breaks. */
    static String urlPath(String value) {
        if (value == null) {
            return ""
        }
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (Exception ignored) {
            return value
        }
    }

    static String urlQuery(String value) {
        if (value == null) {
            return ""
        }
        try {
            return URLEncoder.encode(value, "UTF-8")
        } catch (Exception ignored) {
            return value
        }
    }

    /* The base URL without its trailing slash, so every caller can concatenate a
     * path that starts with one and no link ends up with a double slash. */
    static String trimBase(String baseUrl) {
        String out = text(baseUrl)
        if (out == null) {
            return ""
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1)
        }
        return out
    }

    /* ---- query parameters ---------------------------------------------- */

    /* queryParams is the JAX-RS MultivaluedMap, and naming that type would drag
     * either javax.ws.rs or jakarta.ws.rs into this file - the one dependency this
     * endpoint exists without, so that a single file runs on ScriptRunner 8 and
     * on ScriptRunner 10. The call is therefore made through the invoker, which is
     * both namespace-neutral and resolvable by the static type checker. */
    static String stringParam(Object queryParams, String name, String defaultValue) {
        Object raw = queryParams == null ? null : duck(queryParams, "getFirst", name)
        if (raw == null) {
            return defaultValue
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? defaultValue : value
    }

    static boolean booleanParam(Object queryParams, String name, boolean defaultValue) {
        String value = stringParam(queryParams, name, null)
        if (value == null) {
            return defaultValue
        }
        value = value.toLowerCase(Locale.ROOT)
        if (value in ["true", "1", "yes", "on"]) {
            return true
        }
        if (value in ["false", "0", "no", "off"]) {
            return false
        }
        return defaultValue
    }

    /* Self-referencing report link. Keys with a null value drop out, so the URL
     * only ever carries the parameters that differ from the defaults. */
    static String link(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>(base)
        if (overrides != null) {
            merged.putAll(overrides)
        }
        StringBuilder out = new StringBuilder("?")
        boolean first = true
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (entry.value == null) {
                continue
            }
            if (!first) {
                out.append("&")
            }
            out.append(urlQuery(entry.key)).append("=").append(urlQuery(entry.value.toString()))
            first = false
        }
        return out.length() == 1 ? "?" : out.toString()
    }

    /* ---- Confluence-shaped values, without a Confluence type ------------- */

    /* SPACES.SPACETYPE. The two stored values are read verbatim out of the named
     * queries in Space.hbm.xml on 10.2.14, which compare space.spaceType against
     * the literals 'global' and 'personal'. An unknown value is reported as
     * itself rather than folded into one of the two. */
    static String spaceType(String raw) {
        String value = text(raw)
        if (value == null) {
            return NA
        }
        if ("global".equalsIgnoreCase(value)) {
            return "Global space"
        }
        if ("personal".equalsIgnoreCase(value)) {
            return "Personal space"
        }
        return "Unknown (" + value + ")"
    }

    /* SPACES.SPACESTATUS. Measured on the instance: the column holds CURRENT or
     * ARCHIVED and nothing else. */
    static String spaceStatus(String raw) {
        String value = text(raw)
        if (value == null) {
            return NA
        }
        if ("CURRENT".equalsIgnoreCase(value)) {
            return "Current"
        }
        if ("ARCHIVED".equalsIgnoreCase(value)) {
            return "Archived"
        }
        return "Unknown (" + value + ")"
    }

    /* The value SPACEPERMISSIONS.PERMALLUSERSSUBJECT carries for a grant to every
     * logged-in user. Read out of the bytecode of SpacePermission$AccessSubject on
     * 10.2.14, where ALL_AUTHENTICATED_USERS is constructed with the literal
     * "authenticated-users" and ANONYMOUS_USERS is constructed with null. That is
     * also why three null subject columns are the anonymous grant and not a broken
     * row: SpacePermission.isAnonymousPermission() is exactly "not a user, not a
     * group, and the subject column equals null". */
    static final String AUTHENTICATED_SUBJECT = "authenticated-users"

    /* The four subjects a grant row can carry, in the order the columns decide
     * them. All three columns null is the anonymous subject and a real grant; it
     * was never a broken row and is never rendered as one. */
    static String subject(String group, String userKey, String userName, String allUsers) {
        if (text(group) != null) {
            return "Group: " + text(group)
        }
        if (text(userKey) != null) {
            return "User: " + userLabel(userName, userKey)
        }
        if (text(allUsers) == null) {
            return "Anonymous access"
        }
        if (AUTHENTICATED_SUBJECT.equalsIgnoreCase(text(allUsers))) {
            return "All logged-in users"
        }
        return "Subject: " + text(allUsers)
    }

    /* A resolved name where there is one, the raw key where there is not, never a
     * blank. A blank cell cannot be told apart from a value nobody managed to
     * read, and an unresolvable key is a real answer: the user was deleted from
     * the directory and the grant outlived them. */
    static String userLabel(String resolved, String key) {
        String name = text(resolved)
        String raw = text(key)
        if (name != null && raw != null) {
            return name + " (" + raw + ")"
        }
        if (name != null) {
            return name
        }
        if (raw != null) {
            return raw + " - this user key resolves to no entry in user_mapping"
        }
        return NA
    }

    /* The deny-list that decides whether a property value may be printed at all.
     * It matches on the KEY name, because the key is the only thing this report
     * reads before it decides, and reading a value in order to find out whether
     * it may be read would defeat the purpose. */
    static final List<String> SECRET_MARKERS = ["secret", "token", "password", "apikey", "credential"]

    static boolean sensitive(String key) {
        String value = key == null ? "" : key.toLowerCase(Locale.ROOT)
        for (String marker : SECRET_MARKERS) {
            if (value.contains(marker)) {
                return true
            }
        }
        return false
    }

    /* A machine kind turned into a column header. Every node carries one, so the
     * headers come from the data rather than from a hand-maintained list that would
     * drift the moment a section gains a level. The prefix of the level above is
     * removed because the table already says it: spacePermissionGrantDetail under
     * spacePermissionGrant reads as "Detail", not as the whole chain again. */
    static String humanKind(String kind, String prefix) {
        String rest = text(kind)
        if (rest == null) {
            return "Item"
        }
        /* Compared word by word rather than character by character. A container is
         * routinely the plural of what it holds, and startsWith sees nothing in
         * common past the "s". On word boundaries the shared part is obvious. */
        List<String> mine = camelWords(rest)
        List<String> theirs = camelWords(text(prefix))
        int shared = 0
        while (shared < mine.size() && shared < theirs.size()
               && mine.get(shared).equalsIgnoreCase(theirs.get(shared))) {
            shared++
        }
        /* A kind that is entirely contained in the level above keeps its own name.
         * An empty header would say less than a repetitive one. */
        List<String> words = (shared == 0 || shared >= mine.size())
            ? mine : mine.subList(shared, mine.size())
        if (words.isEmpty()) {
            return "Item"
        }
        StringBuilder out = new StringBuilder()
        for (String word : words) {
            if (out.length() > 0) {
                out.append(' ')
            }
            out.append(word.toLowerCase())
        }
        String joined = out.toString()
        return String.valueOf(Character.toUpperCase(joined.charAt(0))) + joined.substring(1)
    }

    private static List<String> camelWords(String value) {
        List<String> words = new ArrayList<String>()
        if (value == null) {
            return words
        }
        StringBuilder word = new StringBuilder()
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i)
            if (Character.isUpperCase(c) && word.length() > 0) {
                words.add(word.toString())
                word = new StringBuilder()
            }
            word.append(c)
        }
        if (word.length() > 0) {
            words.add(word.toString())
        }
        return words
    }

    /* "1 grants" is the kind of detail that makes a reader distrust the numbers
     * next to it. */
    static String plural(int count, String noun) {
        return String.valueOf(count) + " " + noun + (count == 1 ? "" : "s")
    }

    static String flag(boolean value) {
        return value ? "yes" : "no"
    }

    /* A timestamp read out of a JDBC row arrives as a string already. It is
     * printed as it came rather than reformatted: a parse that fails silently and
     * yields a plausible wrong date is worse than a machine-shaped one that is
     * right. Only the fractional seconds are dropped, because they are noise in a
     * configuration report and their presence differs by database. */
    static String stamp(Object value) {
        String raw = text(value)
        if (raw == null) {
            return NA
        }
        int dot = raw.indexOf('.')
        return dot > 0 ? raw.substring(0, dot) : raw
    }

    /* Reading a method that may not exist on this Confluence line. Everything this
     * report shows about a theme, a template or a label comes off objects whose
     * types are deliberately not named in this file, so the read has to ask the
     * object whether it answers a method rather than declare which interface it
     * must implement. A miss returns null and the caller falls back. */
    static Object duck(Object target, String method, Object argument) {
        return duckAll(target, method, argument == null ? new Object[0] : ([argument] as Object[]))
    }

    /* Written against InvokerHelper rather than as target."$name"() on purpose.
     * A dynamic method name is invisible to the static type checker and shows up
     * as an error in the ScriptRunner editor, which is the one place an
     * administrator reads this file before running it. This form does the same
     * thing and stays checkable. */
    static Object duckAll(Object target, String method, Object[] arguments) {
        if (target == null) {
            return null
        }
        try {
            return InvokerHelper.invokeMethod(target, method, arguments)
        } catch (MissingMethodException ignored) {
            return null
        } catch (Exception ignored) {
            return null
        }
    }

    /* The first of several getters that answers. A value object read reflectively
     * has no contract, so the caller names the candidates in the order it prefers
     * them and takes the first non-empty answer. */
    static String firstText(Object target, List<String> methods) {
        for (String method : methods) {
            String value = text(duck(target, method, null))
            if (value != null) {
                return value
            }
        }
        return null
    }

    static String timestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
    }
}

/* =============================================================================
 * The HTTP response, built without naming a JAX-RS namespace
 *
 * The namespace a ScriptRunner script needs follows the ScriptRunner version, not
 * the Confluence version: 10.x and above use jakarta.ws.rs.*, 8.x to 9.x use
 * javax.ws.rs.*. Importing either one would tie this file to one of the two lines.
 * The class is therefore resolved at runtime and the builder chain is driven
 * through the invoker.
 *
 * Keeping that in one class rather than at each of the call sites has a second
 * effect that matters more than the tidiness: the whole thing becomes testable
 * off-instance, because a fake response class is enough to prove the chain is
 * built in the right order with the right arguments.
 * ========================================================================== */

class Http {

    static final String HTML = "text/html; charset=UTF-8"
    static final String JSON = "application/json; charset=UTF-8"
    static final String CSV = "text/csv; charset=UTF-8"

    static Class resolveResponseClass() {
        try {
            return Class.forName("jakarta.ws.rs.core.Response")
        } catch (ClassNotFoundException ignored) {
            return Class.forName("javax.ws.rs.core.Response")
        }
    }

    /* status 200 goes through ok(entity), anything else through
     * status(code).entity(entity), because that is the shape JAX-RS offers. */
    static Object build(Class responseClass, int status, String entity,
                        String contentType, Map<String, String> headers) {
        Object builder
        if (status == 200) {
            builder = Pc.duckAll(responseClass, "ok", [entity] as Object[])
        } else {
            builder = Pc.duckAll(responseClass, "status", [Integer.valueOf(status)] as Object[])
            builder = Pc.duckAll(builder, "entity", [entity] as Object[])
        }
        builder = Pc.duckAll(builder, "type", [contentType] as Object[])
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder = Pc.duckAll(builder, "header",
                    [header.getKey(), header.getValue()] as Object[])
            }
        }
        return Pc.duckAll(builder, "build", new Object[0])
    }

    static Object ok(Class responseClass, String entity, String contentType) {
        return build(responseClass, 200, entity, contentType, null)
    }
}

/* =============================================================================
 * Deep links
 *
 * Every shape below is templated from the literal <link> body of a shipped
 * web-item, read out of the 827 jars of a running Confluence DC 10.2.14 on
 * 2026-08-27. The descriptor jar, the web-section and the web-item id are named
 * per entry, so each shape can be checked against its own evidence rather than
 * against a memory of what Confluence URLs look like.
 *
 * THERE IS NO PREFIX RULE. Among the 54 space-admin, space-tools and space-export
 * web-items on this instance, the key travels as ?key=, as a path segment
 * (/plugins/servlet/space-tool-backup/<KEY>), and not at all - the audit log is
 * addressed by space ID. Anything derived from "the pattern" rather than from a
 * link body would be a guess, and a guess is what this class exists to avoid.
 *
 * NOT HERE, ON PURPOSE. removespace is the Delete Space screen. The watch and
 * unwatch items carry an XSRF token. No state-changing address is built by this
 * class in any form, and the offline suite asserts that over every builder.
 *
 * A shape that could not be evidenced is not invented. The builder returns null
 * and the caller states the navigation path in words instead.
 * ========================================================================== */

class Dl {

    /* Kept out of the constructor arguments of every builder so the whole class
     * stays a pure function of its inputs and the test suite can drive it without
     * a Confluence instance. */
    String base

    Dl(String baseUrl) {
        this.base = Pc.trimBase(baseUrl)
    }

    /* The shared shape: an action under the context path with the space key in a
     * key parameter. Every web-item that uses it writes exactly
     * ...?key=$htmlUtil.urlEncode($helper.spaceKey), so the encoding is part of
     * the evidenced shape and not an embellishment. */
    private String keyed(String path, String spaceKey) {
        String key = Pc.text(spaceKey)
        if (key == null || base.isEmpty()) {
            return null
        }
        return base + path + "?key=" + Pc.urlQuery(key)
    }

    /* Evidence: CORE com.atlassian.confluence.confluence-10.2.14.jar,
     * section system.space.tools/overview, web-item id "spacedetails",
     * <link>/spaces/viewspacesummary.action?key=$htmlUtil.urlEncode($helper.spaceKey)</link>
     * The same body appears again under id "spacedetails-personal", so the shape
     * covers a personal space without a second builder. */
    String spaceSummary(String spaceKey) {
        return keyed("/spaces/viewspacesummary.action", spaceKey)
    }

    /* Evidence: CORE jar, section system.space.admin/spaceops, web-item id
     * "editspace",
     * <link>/spaces/editspace.action?key=$htmlUtil.urlEncode($helper.spaceKey)</link>
     * This is the edit form for the details above. It changes nothing until the
     * administrator submits it, which is why it is a legitimate destination and
     * removespace, sitting in the same section, is not. */
    String editSpaceDetails(String spaceKey) {
        return keyed("/spaces/editspace.action", spaceKey)
    }

    /* Evidence: CORE jar, section system.space.tools/permissions, web-item id
     * "spacepermissions",
     * <link>/spaces/spacepermissions.action?key=$htmlUtil.urlEncode($helper.spaceKey)</link>
     * The same body is shipped a second time under system.space.admin/security. */
    String spacePermissions(String spaceKey) {
        return keyed("/spaces/spacepermissions.action", spaceKey)
    }

    /* Evidence: com.atlassian.confluence.plugins.confluence-templates jar,
     * section system.space.admin/spaceops, web-item id "space-templates",
     * <link>/pages/templates2/listpagetemplates.action?key=$htmlUtil.urlEncode($helper.spaceKey)</link>
     * Note the templates2 segment: templates1 was a different screen and the 2 is
     * part of the shipped literal, not a version number to be tidied away. */
    String spaceTemplates(String spaceKey) {
        return keyed("/pages/templates2/listpagetemplates.action", spaceKey)
    }

    /* Evidence: CORE jar, section system.space.tools/lookandfeel, web-item id
     * "choosetheme",
     * <link>/spaces/choosetheme.action?key=$htmlUtil.urlEncode($helper.spaceKey)</link> */
    String spaceTheme(String spaceKey) {
        return keyed("/spaces/choosetheme.action", spaceKey)
    }

    /* Evidence: CORE jar, section system.space.tools/lookandfeel, web-item id
     * "colorscheme",
     * <link>/spaces/lookandfeel.action?key=$htmlUtil.urlEncode($helper.spaceKey)</link>
     * The action is called lookandfeel and the item is called colorscheme. The
     * address follows the shipped link body, not the label above it. */
    String spaceColourScheme(String spaceKey) {
        return keyed("/spaces/lookandfeel.action", spaceKey)
    }

    /* Evidence: CORE jar, section system.space.admin/spaceops, web-item id
     * "editspacelabels",
     * <link>/spaces/editspacelabels.action?key=$htmlUtil.urlEncode($helper.spaceKey)</link>
     * Space categories are labels on the space, which is why the screen that
     * maintains them is called editspacelabels. */
    String spaceCategories(String spaceKey) {
        return keyed("/spaces/editspacelabels.action", spaceKey)
    }

    /* Evidence: com.atlassian.audit.atlassian-audit-plugin-3.1 jar, section
     * system.space.tools/audit-log, web-item id
     * "audit-log.menu-item.space.admin.confluence",
     * <link>/plugins/servlet/audit/resource/Space,$space.id</link>
     *
     * The one shape on this instance that is keyed by the space ID rather than by
     * the space key, and the reason this class templates each shape from its own
     * link body. The comma is part of the resource address; it is not a
     * separator this file invented, and it is not URL-encoded in the shipped
     * literal either. */
    String spaceAuditLog(Object spaceId) {
        String id = Pc.text(spaceId)
        if (id == null || base.isEmpty()) {
            return null
        }
        return base + "/plugins/servlet/audit/resource/Space," + Pc.urlPath(id)
    }

    /* ---- the notes that stand in for a link that does not exist ---------- */

    /* Measured, not assumed: there is no archive action and no archive web-item
     * in the core struts configuration or in any bundled descriptor of 10.2.14.
     * Archiving is reached from the space itself, so this node ships without a
     * link and says where to go instead. */
    static String archiveUnavailableNote() {
        return "Space tools > Overview. Archiving a space has no addressable screen on " +
            "Confluence 10.2.14: no archive action and no archive web-item ships in the " +
            "core configuration or in any bundled descriptor, so no link is offered here."
    }

    /* The three property stores have no administration screen at all. That is the
     * point of the section, and it is also why nothing in it can carry a link. */
    static String propertyStoreUnavailableNote() {
        return "No administration screen shows this. The values are written by apps into " +
            "the plugin settings, Bandana and content property stores, and are readable " +
            "only through this report or the database."
    }
}

/* =============================================================================
 * The report tree
 *
 * One recursive node type carries the whole report. Everything the renderers
 * need is on it, so a new section is a new subtree and never a new renderer.
 * ========================================================================== */

class Nd {

    /* Machine-readable node type, e.g. "spacePermissionGrant", "spaceProperty".
     * Used by the JSON consumer and by the CSV path column, never shown as a
     * label. */
    String kind = "node"

    /* What the administrator reads. */
    String label

    /* Optional scalar value of this node. */
    String value

    /* The Confluence-internal id, when the object has one. Printed because it is
     * what an administrator needs when they go looking in the database. */
    String id

    /* Absolute URL, or null. */
    String deepLink

    /* Set exactly when deepLink is null and the node could have had one. Says in
     * plain words where the item is maintained. */
    String linkNote

    /* read, unreadable, absent, truncated or redacted. A node whose children
     * could not be read is NOT the same as a node without children, and this
     * field is the only thing keeping those apart. */
    String state = Pc.READ

    /* Suppressed read errors at this node. They travel with the node, so a
     * failure surfaces exactly where it happened rather than in a global list
     * nobody reads. */
    List<String> diagnostics = new ArrayList<String>()

    /* Things worth saying that are NOT failures: a cap that was reached, a store
     * a key was also found in, a permission subject encoding the report chose to
     * name rather than guess. They are kept apart from diagnostics so the report
     * never announces suppressed reads that did not happen. A report that cries
     * wolf about its own reliability is worse than one that says nothing, because
     * the next real failure is then read as noise. */
    List<String> notes = new ArrayList<String>()

    List<Nd> children = new ArrayList<Nd>()

    Nd(String kind, String label) {
        this.kind = kind
        this.label = label
    }

    static Nd of(String kind, String label) {
        return new Nd(kind, label)
    }

    Nd val(Object value) {
        this.value = Pc.text(value)
        return this
    }

    Nd ident(Object id) {
        this.id = Pc.text(id)
        return this
    }

    /* What the link is called. Left unset it reads "open in Confluence" on a
     * section and "open" on a node, which is right when the link lands exactly on
     * the item. When it can only land nearby, that difference belongs in the text
     * a reader clicks. */
    String linkLabel

    Nd linkAs(String label) {
        this.linkLabel = Pc.text(label)
        return this
    }

    /* A link is attached with the note that applies when it is absent, so the two
     * can never drift apart: passing a null link without a note is what produces
     * an unexplained missing link. */
    Nd link(String url, String noteIfMissing) {
        String candidate = Pc.text(url)
        if (candidate == null) {
            this.deepLink = null
            this.linkNote = Pc.text(noteIfMissing)
        } else {
            this.deepLink = candidate
            this.linkNote = null
        }
        return this
    }

    Nd add(Nd child) {
        if (child != null) {
            children.add(child)
        }
        return this
    }

    Nd addAll(Collection<Nd> items) {
        if (items != null) {
            for (Nd item : items) {
                add(item)
            }
        }
        return this
    }

    Nd note(String text) {
        String out = Pc.text(text)
        if (out != null) {
            notes.add(out)
        }
        return this
    }

    /* The failure reason belongs to the node, not only to the log. */
    Nd failed(String reason) {
        this.state = Pc.UNREADABLE
        if (Pc.text(reason) != null) {
            diagnostics.add(reason)
        }
        return this
    }

    Nd absent(String reason) {
        this.state = Pc.ABSENT
        if (Pc.text(reason) != null) {
            this.value = reason
        }
        return this
    }

    /* A value withheld on purpose. Neither absent nor unreadable: the read
     * succeeded and the report is choosing not to print what it found. */
    Nd redacted(String reason) {
        this.state = Pc.REDACTED
        if (Pc.text(reason) != null) {
            this.value = reason
        }
        return this
    }

    /* A cap that was reached says so IN the node, with the number. The probe that
     * preceded this file returned exactly 25 permission rows - its own cap - and
     * said nothing, so the output was indistinguishable from a space with 25
     * grants. A cap nobody can see is a silent lie about the population. */
    Nd cappedAt(int cap, String what) {
        this.state = Pc.TRUNCATED
        return note("Only the first " + String.valueOf(cap) + " " + what + " are listed. " +
            "This is this report's own cap, not the number that exists. The full " +
            "population is larger and was not read.")
    }

    boolean isReadable() {
        return Pc.READ.equals(state)
    }

    /* The distinct ids of one node kind anywhere below here. */
    Set<String> idsOfKind(String wanted) {
        Set<String> found = new LinkedHashSet<String>()
        collectIds(wanted, found)
        return found
    }

    private void collectIds(String wanted, Set<String> found) {
        if (wanted.equals(kind)) {
            found.add(id == null ? label : id)
        }
        for (Nd child : children) {
            child.collectIds(wanted, found)
        }
    }

    int countDescendants() {
        int total = children.size()
        for (Nd child : children) {
            total += child.countDescendants()
        }
        return total
    }

    /* Every note and diagnostic in the subtree, prefixed with the path it sits
     * on, grouped by text rather than listed one per node. A note that is true of
     * one item is a finding; the same sentence repeated four hundred times is a
     * property of the instance, and printing it four hundred times buries every
     * other finding on the page. Nothing is lost by grouping: the path of every
     * occurrence is kept, and the note itself still sits on its own node. */
    void collectNotes(String path, Map<String, List<String>> into) {
        String here = path == null || path.isEmpty() ? Pc.orNa(label) : path + " > " + Pc.orNa(label)
        for (String entry : notes) {
            group(into, entry, here)
        }
        for (Nd child : children) {
            child.collectNotes(here, into)
        }
    }

    void collectDiagnostics(String path, Map<String, List<String>> into) {
        String here = path == null || path.isEmpty() ? Pc.orNa(label) : path + " > " + Pc.orNa(label)
        for (String entry : diagnostics) {
            group(into, entry, here)
        }
        for (Nd child : children) {
            child.collectDiagnostics(here, into)
        }
    }

    static void group(Map<String, List<String>> into, String text, String path) {
        List<String> where = into.get(text)
        if (where == null) {
            where = new ArrayList<String>()
            into.put(text, where)
        }
        where.add(path)
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("kind", kind)
        out.put("label", label)
        if (value != null) {
            out.put("value", value)
        }
        if (id != null) {
            out.put("id", id)
        }
        if (deepLink != null) {
            out.put("deepLink", deepLink)
        }
        if (linkNote != null) {
            out.put("linkNote", linkNote)
        }
        if (linkLabel != null) {
            out.put("linkLabel", linkLabel)
        }
        out.put("state", state)
        if (!diagnostics.isEmpty()) {
            out.put("diagnostics", new ArrayList<String>(diagnostics))
        }
        if (!notes.isEmpty()) {
            out.put("notes", new ArrayList<String>(notes))
        }
        if (!children.isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<Map<String, Object>>()
            for (Nd child : children) {
                kids.add(child.toMap())
            }
            out.put("children", kids)
        }
        return out
    }
}

/* =============================================================================
 * The report
 * ========================================================================== */

class Report {

    String version = Pc.VERSION
    String generatedAt = Pc.timestamp()
    long executionMs = 0L

    String instanceTitle
    String instanceBaseUrl
    String confluenceVersion

    String spaceKey
    String spaceName
    String spaceId
    String spaceType

    /* Top-level sections, each the root of one subtree. */
    List<Nd> sections = new ArrayList<Nd>()

    /* Failures that could not be attached to any single node, e.g. a component
     * that could not be obtained at all. Deliberately small: a diagnostic that
     * belongs to a node lives on that node. */
    List<String> globalDiagnostics = new ArrayList<String>()

    Nd section(String kind, String label) {
        Nd node = Nd.of(kind, label)
        sections.add(node)
        return node
    }

    int nodeCount() {
        int total = 0
        for (Nd node : sections) {
            total += 1 + node.countDescendants()
        }
        return total
    }

    /* Only UNREADABLE counts. ABSENT is a measured absence - a space with no
     * categories, a space with no explicit grants - and counting those as
     * failures would make a healthy space report dozens of reads that never
     * failed. REDACTED is a decision this report took on purpose and is not a
     * failure either. Claiming failures that did not happen is the same defect as
     * hiding ones that did. */
    int unreadableCount() {
        int total = 0
        for (Nd node : sections) {
            total += countUnreadable(node)
        }
        return total
    }

    private int countUnreadable(Nd node) {
        int total = Pc.UNREADABLE.equals(node.state) ? 1 : 0
        for (Nd child : node.children) {
            total += countUnreadable(child)
        }
        return total
    }

    int unlinkedCount() {
        int total = 0
        for (Nd node : sections) {
            total += countUnlinked(node)
        }
        return total
    }

    private int countUnlinked(Nd node) {
        int total = node.linkNote != null ? 1 : 0
        for (Nd child : node.children) {
            total += countUnlinked(child)
        }
        return total
    }

    Map<String, List<String>> notesByText() {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>()
        for (Nd node : sections) {
            node.collectNotes("", out)
        }
        return out
    }

    Map<String, List<String>> diagnosticsByText() {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>()
        /* A global diagnostic belongs to the run rather than to a node, so it
         * carries no path. The empty path is what says so. */
        for (String entry : globalDiagnostics) {
            Nd.group(out, entry, "")
        }
        for (Nd node : sections) {
            node.collectDiagnostics("", out)
        }
        return out
    }

    /* The flat form every output channel other than the tree uses. It is a
     * summary, and it says so: a text that occurred more than once carries its
     * count and the first place it occurred rather than being repeated. The tree
     * in the same payload still carries each note on its own node. */
    static List<String> summarise(Map<String, List<String>> grouped) {
        List<String> out = new ArrayList<String>()
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            List<String> where = entry.getValue()
            String first = where.isEmpty() ? "" : Pc.orNa(where.get(0))
            if (where.size() == 1) {
                out.add(first.isEmpty() || Pc.NA.equals(first) ? entry.getKey() : first + ": " + entry.getKey())
                continue
            }
            out.add(entry.getKey() + " [" + String.valueOf(where.size()) + " items, first: " + first + "]")
        }
        return out
    }

    List<String> allNotes() {
        return summarise(notesByText())
    }

    List<String> allDiagnostics() {
        return summarise(diagnosticsByText())
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("reportVersion", version)
        out.put("generatedAt", generatedAt)
        out.put("executionMs", Long.valueOf(executionMs))
        Map<String, Object> instance = new LinkedHashMap<String, Object>()
        instance.put("title", instanceTitle)
        instance.put("baseUrl", instanceBaseUrl)
        instance.put("confluenceVersion", confluenceVersion)
        out.put("instance", instance)
        Map<String, Object> space = new LinkedHashMap<String, Object>()
        space.put("key", spaceKey)
        space.put("name", spaceName)
        space.put("id", spaceId)
        space.put("type", spaceType)
        out.put("space", space)
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>()
        for (Nd node : sections) {
            nodes.add(node.toMap())
        }
        out.put("sections", nodes)
        out.put("diagnostics", allDiagnostics())
        out.put("notes", allNotes())
        Map<String, Object> totals = new LinkedHashMap<String, Object>()
        totals.put("nodes", Integer.valueOf(nodeCount()))
        totals.put("unreadable", Integer.valueOf(unreadableCount()))
        totals.put("unlinked", Integer.valueOf(unlinkedCount()))
        out.put("totals", totals)
        return out
    }
}

/* =============================================================================
 * Renderers - pure functions of the report, so the suite can drive them offline
 * ========================================================================== */

class Render {

    static String json(Report report) {
        return JsonOutput.prettyPrint(JsonOutput.toJson(report.toMap()))
    }

    /* One row per node. A tree in CSV is never pretty, but this is the shape that
     * goes into a spreadsheet and into an audit record. */
    static String csv(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("path;kind;label;value;id;state;deepLink;linkNote;diagnostics\n")
        for (Nd node : report.sections) {
            csvNode(out, node, "")
        }
        return out.toString()
    }

    private static void csvNode(StringBuilder out, Nd node, String parentPath) {
        String path = parentPath.isEmpty() ? Pc.orNa(node.label) : parentPath + " > " + Pc.orNa(node.label)
        out.append(Pc.csv(path)).append(";")
        out.append(Pc.csv(node.kind)).append(";")
        out.append(Pc.csv(node.label)).append(";")
        out.append(Pc.csv(node.value)).append(";")
        out.append(Pc.csv(node.id)).append(";")
        out.append(Pc.csv(node.state)).append(";")
        out.append(Pc.csv(node.deepLink)).append(";")
        out.append(Pc.csv(node.linkNote)).append(";")
        out.append(Pc.csv(node.diagnostics.isEmpty() ? null : node.diagnostics.join(" | "))).append("\n")
        for (Nd child : node.children) {
            csvNode(out, child, path)
        }
    }

    /* ---- HTML ----------------------------------------------------------- */

    static String html(Report report, Map<String, Object> activeParams, boolean expandAll) {
        StringBuilder out = new StringBuilder()
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        out.append("<meta charset=\"UTF-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<title>Space configuration - ").append(Pc.html(report.spaceKey)).append("</title>\n")
        out.append(style())
        out.append("</head>\n<body>\n<div class=\"page\">\n")
        out.append(header(report))
        out.append(instanceCard(report))
        out.append(summaryCards(report))
        out.append(toolbar(activeParams, expandAll))
        out.append(diagnosticsCard(report))
        out.append(notesCard(report))
        for (Nd node : report.sections) {
            out.append(section(node, expandAll))
        }
        out.append(footer(report))
        out.append("</div>\n").append(script()).append("</body>\n</html>\n")
        return out.toString()
    }

    private static String header(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"page-header\"><div>")
        out.append("<h1 class=\"page-title\">").append(Pc.html(Pc.orNa(report.spaceName)))
        out.append(" <span class=\"muted\">(").append(Pc.html(Pc.orNa(report.spaceKey))).append(")</span></h1>")
        out.append("<div class=\"page-subtitle\">Complete configuration of this space. ")
        out.append("Every item is expanded to its own configuration and linked to the screen where it is maintained.")
        out.append("</div></div></div>\n")
        return out.toString()
    }

    private static String instanceCard(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"instance\">")
        out.append("<div><strong>Instance</strong> ").append(Pc.html(Pc.orNa(report.instanceTitle))).append("</div>")
        /* The base URL is what identifies the instance once the report has left it.
         * An exported page or a printed PDF that names a title and a version but not
         * the address describes some Confluence, not this one. */
        String baseUrl = Pc.text(report.instanceBaseUrl)
        out.append("<div><strong>Address</strong> ")
        if (baseUrl == null) {
            out.append("<span class=\"state state-unreadable\">could not be read</span>")
        } else {
            out.append("<a href=\"").append(Pc.html(baseUrl))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">").append(Pc.html(baseUrl)).append("</a>")
        }
        out.append("</div>")
        out.append("<div><strong>Confluence</strong> ").append(Pc.html(Pc.orNa(report.confluenceVersion))).append("</div>")
        out.append("<div><strong>Report</strong> v").append(Pc.html(report.version)).append("</div>")
        out.append("<div><strong>Generated</strong> ").append(Pc.html(report.generatedAt)).append("</div>")
        out.append("<div><strong>Runtime</strong> ").append(Pc.html(String.valueOf(report.executionMs))).append(" ms</div>")
        out.append("</div>\n")
        return out.toString()
    }

    private static String summaryCards(Report report) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"summary-grid\">")
        out.append(card(String.valueOf(report.sections.size()), "sections"))
        out.append(card(String.valueOf(report.nodeCount()), "configuration items"))
        out.append(card(String.valueOf(report.unreadableCount()), "could not be read"))
        out.append(card(String.valueOf(report.unlinkedCount()), "without a deep link"))
        out.append("</div>\n")
        return out.toString()
    }

    private static String card(String value, String label) {
        return "<div class=\"summary-card\"><div class=\"summary-value\">" + Pc.html(value) +
            "</div><div class=\"summary-label\">" + Pc.html(label) + "</div></div>"
    }

    private static String toolbar(Map<String, Object> activeParams, boolean expandAll) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"actions\">")
        /* Expanding and collapsing happens in the page. The link below is the
         * bookmarkable variant of the same thing, for a URL that should open fully
         * expanded for somebody else. */
        out.append("<button class=\"button\" type=\"button\" onclick=\"expandAll(true)\">Expand all</button>")
        out.append("<button class=\"button\" type=\"button\" onclick=\"expandAll(false)\">Collapse all</button>")
        out.append("<button id=\"viewTree\" class=\"button on\" type=\"button\" onclick=\"setView('tree')\">Tree</button>")
        out.append("<button id=\"viewTable\" class=\"button\" type=\"button\" onclick=\"setView('table')\">Table</button>")
        /* There is deliberately no button that turns depth=full ON, and none that
         * turns values ON. One click was enough to pin "everything expanded" into
         * the URL, into a bookmark and into every later visit; for property values
         * the same click would pin app configuration that may contain a secret
         * into a link somebody forwards. Both parameters still exist for a URL
         * typed on purpose, and when one is active the way back out of it is
         * right here. */
        if (expandAll) {
            out.append(button(Pc.link(activeParams, [depth: null]),
                "Leave always-expanded mode", true))
        }
        if (activeParams.get("values") != null) {
            out.append(button(Pc.link(activeParams, [values: null]),
                "Stop showing property values", true))
        }
        out.append(button(Pc.link(activeParams, [format: "json"]), "JSON", false))
        out.append(button(Pc.link(activeParams, [format: "csv"]), "CSV", false))
        out.append(button(Pc.link([:], [:]), "Pick another space", false))
        out.append("</div>\n")
        return out.toString()
    }

    private static String button(String href, String label, boolean on) {
        return "<a class=\"button" + (on ? " on" : "") + "\" href=\"" + Pc.html(href) + "\">" +
            Pc.html(label) + "</a>"
    }

    /* Both cards summarise rather than enumerate. A report that lists one line per
     * occurrence is unreadable the moment a single sentence is true of hundreds of
     * items. One line per distinct text, with how many items it is true of and the
     * first few of them, is the same information in a form somebody can read. */
    private static final int MAX_CARD_ENTRIES = 200
    private static final int MAX_CARD_PATHS = 3

    private static String diagnosticsCard(Report report) {
        return summaryCard("diag diag-warn", report.diagnosticsByText(),
            "Each one is also marked at the item it belongs to. " +
            "A suppressed read is not a measured absence.",
            "read was suppressed", "reads were suppressed")
    }

    /* Kept apart from the diagnostics card on purpose. These are observations, not
     * failures, and mixing them makes a report announce suppressed reads that
     * never happened. */
    private static String notesCard(Report report) {
        return summaryCard("diag diag-info", report.notesByText(),
            "Nothing failed to read here. Each one is also marked at the item it belongs to.",
            "observation", "observations")
    }

    private static String summaryCard(String css, Map<String, List<String>> grouped,
                                      String lead, String singular, String plural) {
        if (grouped.isEmpty()) {
            return ""
        }
        int total = 0
        for (List<String> where : grouped.values()) {
            total += where.size()
        }
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"").append(css).append("\"><strong>")
        out.append(String.valueOf(total)).append(" ").append(total == 1 ? singular : plural)
        if (grouped.size() < total) {
            out.append(", ").append(String.valueOf(grouped.size())).append(" of them distinct")
        }
        out.append(".</strong> ").append(lead).append("<ul>")
        int shown = 0
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            if (shown >= MAX_CARD_ENTRIES) {
                break
            }
            shown++
            List<String> where = entry.getValue()
            out.append("<li>")
            if (where.size() > 1) {
                out.append("<strong>").append(String.valueOf(where.size())).append("&#215;</strong> ")
            }
            out.append(Pc.html(entry.getKey()))
            out.append(pathList(where))
            out.append("</li>")
        }
        if (grouped.size() > shown) {
            out.append("<li class=\"muted\">").append(String.valueOf(grouped.size() - shown))
            out.append(" further kinds are not listed here. Every one of them is still marked ")
            out.append("at its own item, and all of them are in the JSON output.</li>")
        }
        out.append("</ul></div>\n")
        return out.toString()
    }

    /* Where a text occurred. A global entry carries no path, and an empty list is
     * then correct rather than missing. */
    private static String pathList(List<String> where) {
        List<String> named = new ArrayList<String>()
        for (String path : where) {
            if (path != null && !path.trim().isEmpty()) {
                named.add(path)
            }
        }
        if (named.isEmpty()) {
            return ""
        }
        StringBuilder out = new StringBuilder("<div class=\"muted\">")
        int shown = 0
        for (String path : named) {
            if (shown >= MAX_CARD_PATHS) {
                break
            }
            if (shown > 0) {
                out.append("; ")
            }
            out.append(Pc.html(path))
            shown++
        }
        if (named.size() > shown) {
            out.append(" and ").append(String.valueOf(named.size() - shown)).append(" more")
        }
        return out.append("</div>").toString()
    }

    /* A section is closed until it is asked for. What matters while a section is
     * closed is written on its header, so a closed section is never mistaken for
     * an empty or a broken one. */
    private static String section(Nd node, boolean expandAll) {
        StringBuilder out = new StringBuilder()
        boolean open = expandAll
        out.append("<div class=\"section\">")
        out.append("<div class=\"section-head\" role=\"button\" tabindex=\"0\" aria-expanded=\"")
        out.append(open ? "true" : "false").append("\">")
        out.append("<button class=\"twisty section-twisty\" type=\"button\" tabindex=\"-1\">")
        out.append(open ? "&#9662;" : "&#9656;").append("</button>")
        out.append("<h2 class=\"section-title\">").append(Pc.html(Pc.orNa(node.label))).append("</h2>")
        if (!node.children.isEmpty()) {
            /* The section counts itself, the way the headline figure does. Counting
             * only the descendants made every section header one short of what the
             * page above it claimed. Inside the tree the count on an inner node
             * stays the number of things it hides, which is what a reader of a
             * collapsed node asks. */
            out.append("<span class=\"section-count muted\">")
            out.append(String.valueOf(1 + node.countDescendants())).append(" items</span>")
        }
        /* A section that could not be read says so on its header, where it stays
         * visible while the section is closed. Behind the fold, a failed read turns
         * into something the reader has to go looking for. */
        if (!node.isReadable()) {
            out.append("<span class=\"state state-").append(Pc.html(node.state)).append("\">")
            out.append(Pc.html(stateLabel(node.state))).append("</span>")
        } else if (node.children.isEmpty()) {
            out.append("<span class=\"muted empty\">nothing configured</span>")
        }
        if (node.deepLink != null) {
            out.append("<a class=\"jump\" href=\"").append(Pc.html(node.deepLink))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">")
            out.append(Pc.html(node.linkLabel == null ? "open in Confluence" : node.linkLabel)).append("</a>")
        }
        out.append("</div>")

        out.append("<div class=\"section-body").append(open ? "" : " hidden").append("\">")
        if (node.value != null) {
            out.append("<div class=\"section-value\">")
            out.append(valueHtml(node.value, SECTION_VALUE_CLAMP)).append("</div>")
        }
        if (node.linkNote != null) {
            out.append("<div class=\"linknote\">").append(Pc.html(node.linkNote)).append("</div>")
        }
        for (String entry : node.notes) {
            out.append("<div class=\"section-note\">").append(Pc.html(entry)).append("</div>")
        }
        for (String entry : node.diagnostics) {
            out.append("<div class=\"node-diag\">").append(Pc.html(entry)).append("</div>")
        }
        if (node.children.isEmpty()) {
            if (node.isReadable()) {
                out.append("<div class=\"muted empty\">Nothing configured here.</div>")
            }
        } else {
            out.append("<ul class=\"tree view-tree\">")
            for (Nd child : node.children) {
                out.append(treeNode(child, expandAll, 1))
            }
            out.append("</ul>")
            out.append(sectionTable(node, expandAll))
        }
        out.append("</div>")
        out.append("</div>\n")
        return out.toString()
    }

    /* The same subtree as a flat table. A tree is the honest shape of this data
     * and stays the default, but a table is what gets scanned for one value,
     * sorted in a spreadsheet and pasted into a hand-over document. Both are
     * rendered into the page and the toggle only switches which one is shown, so
     * neither view can go stale against the other. */
    private static String sectionTable(Nd node, boolean expandAll) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"view-table hidden\">")
        /* Children that carry a value of their own rather than a subtree share one
         * table under the section's own name. Every other child gets a table to
         * itself, because its subtree is a set of records of one kind and that is
         * what a table can actually hold. */
        List<Nd> loose = new ArrayList<Nd>()
        List<Nd> branches = new ArrayList<Nd>()
        for (Nd child : node.children) {
            if (child.children.isEmpty() || !child.isReadable()) {
                loose.add(child)
            } else {
                branches.add(child)
            }
        }
        /* The section heading is already on the page, so the pooled children need
         * no heading of their own. Every branch names itself once, and everything
         * below it is nested under that name rather than prefixed with it. */
        if (!loose.isEmpty()) {
            out.append(subTable(loose, node.kind))
        }
        for (Nd branch : branches) {
            out.append(recordOpen(0, expandAll))
            out.append(tableHeading(Pc.orNa(branch.label), 0, branch.countDescendants()))
            emitTables(branch.children, 0, branch.kind, expandAll, out)
            out.append("</details>")
        }
        if (loose.isEmpty() && branches.isEmpty()) {
            out.append("<p class=\"linknote\">Nothing to tabulate.</p>")
        }
        out.append("</div>")
        return out.toString()
    }

    /* How deep the cut goes. One table per record, not one table listing every
     * record with its name repeated down the first column: the name only means
     * something inside its own record, and a collection table over all of them
     * cannot be read as documentation.
     *
     * Split when every member is a container AND at least one of them contains a
     * container of its own. A row threshold would have been the obvious
     * alternative and the worse one: it needs a number per section and drifts the
     * moment a section grows. */
    private static boolean shouldSplit(List<Nd> members) {
        boolean anyLeaf = false
        boolean anyContainer = false
        boolean holdsContainer = false
        for (Nd member : members) {
            if (member.children.isEmpty() || !member.isReadable()) {
                anyLeaf = true
                continue
            }
            anyContainer = true
            for (Nd child : member.children) {
                if (!child.children.isEmpty()) {
                    holdsContainer = true
                }
            }
        }
        /* Mixed membership is the clearest case of all: a container sitting among
         * leaves is a different kind of record than its neighbours, and a table may
         * hold one kind. */
        if (anyLeaf && anyContainer) {
            return true
        }
        return anyContainer && holdsContainer
    }

    /* Emits the tables for one group. The group's own name is printed once, as a
     * heading, and never again - not on every row, and not as a breadcrumb on
     * every table heading. Each level names itself exactly once. */
    private static void emitTables(List<Nd> members, int depth, String ownerKind,
                                   boolean expandAll, StringBuilder out) {
        if (members.isEmpty()) {
            return
        }
        if (!shouldSplit(members)) {
            out.append(subTable(members, ownerKind))
            return
        }
        /* A member that cannot be read, or that has nothing below it, has no table
         * of its own to fill. Those are pooled rather than dropped - a failed read
         * must never disappear because of where it happened to sit. They come
         * first because they are the record's own properties; the groups follow. */
        List<Nd> pooled = new ArrayList<Nd>()
        for (Nd member : members) {
            if (member.children.isEmpty() || !member.isReadable()) {
                pooled.add(member)
            }
        }
        if (!pooled.isEmpty()) {
            out.append(subTable(pooled, ownerKind))
        }
        for (Nd member : members) {
            if (member.children.isEmpty() || !member.isReadable()) {
                continue
            }
            /* Wrapped, not merely indented. An indent is a claim that these things
             * belong together; an element that encloses them is the thing itself,
             * and it lets a single rule run down the whole record so the reader
             * sees where it starts and ends without counting pixels. */
            int level = depth + 1
            out.append(recordOpen(level, expandAll))
            out.append(tableHeading(Pc.orNa(member.label), level, member.countDescendants()))
            emitTables(member.children, level, member.kind, expandAll, out)
            out.append("</details>")
        }
    }

    /* Each record encloses its own tables and can be collapsed. Only the record
     * level starts collapsed. The group above it stays open, or a reader would
     * need two clicks to see anything, and the group below it stays open, because
     * opening a record should show what hangs off it. Expand all still reaches
     * every one of them. */
    private static String recordOpen(int level, boolean expandAll) {
        boolean open = expandAll || level != 1
        StringBuilder out = new StringBuilder()
        out.append("<details class=\"rec lvl-").append(String.valueOf(level > 2 ? 2 : level))
        out.append("\"").append(open ? " open" : "").append(">")
        return out.toString()
    }

    /* The heading is the summary, so it is also the click target. Written as a
     * heading element inside it, so the document still has an outline for anyone
     * navigating by structure. Capped at h6 because HTML has no deeper heading and
     * a fabricated one would not be one. */
    private static String tableHeading(String label, int depth, int items) {
        int level = 4 + depth
        String tag = "h" + String.valueOf(level > 6 ? 6 : level)
        StringBuilder out = new StringBuilder()
        out.append("<summary class=\"table-head lvl-")
        out.append(String.valueOf(depth > 2 ? 2 : depth)).append("\">")
        out.append("<span class=\"twisty tw-closed\">&#9656;</span>")
        out.append("<span class=\"twisty tw-open\">&#9662;</span>")
        out.append("<").append(tag).append(">").append(Pc.html(label))
        out.append(" <span class=\"muted\">").append(Pc.plural(items, "item")).append("</span>")
        out.append("</").append(tag).append("></summary>")
        return out.toString()
    }

    /* One table over one set of like records. members are the records; everything
     * below each of them becomes columns, not a longer string in one cell. */
    private static String subTable(List<Nd> members, String ownerKind) {
        List<Nd> rows = new ArrayList<Nd>()
        List<List<Nd>> chains = new ArrayList<List<Nd>>()
        for (Nd member : members) {
            collectRows(member, new ArrayList<Nd>(), chains, rows)
        }
        if (rows.isEmpty()) {
            return ""
        }
        int depth = 0
        for (List<Nd> chain : chains) {
            if (chain.size() > depth) {
                depth = chain.size()
            }
        }
        StringBuilder out = new StringBuilder()
        /* No heading of its own. The caller printed the name of the group this
         * table belongs to, once, and repeating it here would be the third place
         * the same word appears. */
        out.append("<table class=\"flat\"><thead><tr>")
        for (int level = 0; level < depth; level++) {
            out.append("<th class=\"col-level\">")
            out.append(Pc.html(levelHeader(chains, level, ownerKind))).append("</th>")
        }
        out.append("<th class=\"col-value\">Value</th>")
        out.append("<th class=\"col-state\">State</th>")
        out.append("<th class=\"col-link\">In Confluence</th>")
        out.append("</tr></thead><tbody>")
        for (int i = 0; i < rows.size(); i++) {
            Nd row = rows.get(i)
            List<Nd> chain = chains.get(i)
            out.append("<tr>")
            for (int level = 0; level < depth; level++) {
                out.append("<td class=\"col-level\">")
                if (chain.size() > level) {
                    /* Written out on every row on purpose. A repeated ancestor down
                     * a column is what a sort, a filter and a pivot consume; the
                     * same repetition inside one text cell was the defect. An empty
                     * cell here means the level does not apply to this record, not
                     * that something was not read - the State column says that. */
                    out.append(Pc.html(Pc.orNa(chain.get(level).label)))
                }
                out.append("</td>")
            }
            /* A failed read records its reason as a diagnostic rather than a value,
             * and the tree prints it at the node. A table row that said only "could
             * not be read" would be poorer than the tree at the one point where it
             * matters most, so the reason takes the value cell when there is no
             * value. What is never allowed is a blank cell: blank cannot be told
             * apart from a value nobody managed to read, which is the distinction
             * this whole report rests on. */
            String cell = valueHtml(row.value)
            if (cell.isEmpty() && !row.diagnostics.isEmpty()) {
                cell = valueHtml(String.join("; ", row.diagnostics))
            }
            out.append("<td class=\"col-value\">")
            out.append(cell.isEmpty() ? Pc.html(Pc.NA) : cell).append("</td>")
            out.append("<td class=\"col-state\">")
            if (!row.isReadable()) {
                out.append("<span class=\"state state-").append(Pc.html(row.state)).append("\">")
                out.append(Pc.html(stateLabel(row.state))).append("</span>")
            }
            out.append("</td><td class=\"col-link\">")
            if (row.deepLink != null) {
                out.append("<a href=\"").append(Pc.html(row.deepLink))
                out.append("\" target=\"_blank\" rel=\"noreferrer\">")
                out.append(Pc.html(row.linkLabel == null ? "open" : row.linkLabel)).append("</a>")
            } else if (row.linkNote != null) {
                out.append("<span class=\"node-nolink\" title=\"").append(Pc.html(row.linkNote))
                out.append("\">no link</span>")
            }
            out.append("</td></tr>")
        }
        out.append("</tbody></table>")
        return out.toString()
    }

    /* The header of one level. Level zero is the record itself, so the table's own
     * name is the honest header for it. Below that the kind decides, and a level
     * whose nodes do not agree on a kind is a mixed level: "Aspect" is what such a
     * level is, and inventing a more specific word for it would be a guess. */
    private static String levelHeader(List<List<Nd>> chains, int level, String ownerKind) {
        String kind = null
        String parent = null
        boolean mixed = false
        boolean parentMixed = false
        for (List<Nd> chain : chains) {
            if (chain.size() <= level) {
                continue
            }
            String here = chain.get(level).kind
            if (kind == null) {
                kind = here
            } else if (!kind.equals(here)) {
                mixed = true
            }
            /* The first level has no ancestor inside the chain, but it does have
             * one: the container this table belongs to. Without it the header of a
             * split-off group came out as the whole chain again, because there was
             * nothing left to strip against. */
            if (level == 0) {
                if (parent == null) {
                    parent = ownerKind
                }
            } else {
                String above = chain.get(level - 1).kind
                if (parent == null) {
                    parent = above
                } else if (!parent.equals(above)) {
                    parentMixed = true
                }
            }
        }
        if (kind == null || mixed) {
            return "Aspect"
        }
        return Pc.humanKind(kind, parentMixed ? null : parent)
    }

    /* One row per configuration value. A container contributes no row of its own:
     * its label becomes a column on the rows beneath it, and a summary value such
     * as "12 grants" is an aggregate that a table computes rather than stores.
     *
     * The exception is not negotiable. A node that could not be read stays a row
     * even when it has children, because a failure that vanishes because it
     * happened to sit on a branch is precisely the absence-that-was-never-measured
     * this report exists to prevent. */
    private static void collectRows(Nd node, List<Nd> trail, List<List<Nd>> chains, List<Nd> rows) {
        List<Nd> here = new ArrayList<Nd>(trail)
        here.add(node)
        boolean leaf = node.children.isEmpty()
        if (leaf || !node.isReadable()) {
            rows.add(node)
            chains.add(here)
        }
        if (leaf) {
            return
        }
        for (Nd child : node.children) {
            collectRows(child, here, chains, rows)
        }
    }

    private static String treeNode(Nd node, boolean expandAll, int level) {
        StringBuilder out = new StringBuilder()
        boolean hasChildren = !node.children.isEmpty()
        /* Collapsed is the default. A deep scan of a real space is hundreds of
         * nodes, and a page that opens with all of them expanded is a wall, not a
         * report. Every twisty is one click, and Expand all is one click for the
         * whole page. */
        boolean collapse = hasChildren && !expandAll
        out.append("<li class=\"node node-").append(Pc.html(node.kind)).append("\">")
        out.append("<div class=\"node-line\">")
        if (hasChildren) {
            out.append("<button class=\"twisty\" type=\"button\" aria-expanded=\"")
            out.append(collapse ? "false" : "true").append("\">")
            out.append(collapse ? "&#9656;" : "&#9662;").append("</button>")
        } else {
            out.append("<span class=\"twisty-spacer\"></span>")
        }
        out.append("<span class=\"node-label\">").append(Pc.html(Pc.orNa(node.label))).append("</span>")
        if (hasChildren) {
            /* A collapsed node that does not say how much it hides invites the
             * reader to assume it hides nothing. */
            out.append("<span class=\"node-count muted\">")
            out.append(String.valueOf(node.countDescendants())).append("</span>")
        }
        if (node.value != null) {
            out.append("<span class=\"node-value\">").append(valueHtml(node.value)).append("</span>")
        }
        if (node.id != null) {
            out.append("<span class=\"node-id mono\">id ").append(Pc.html(node.id)).append("</span>")
        }
        if (node.deepLink != null) {
            out.append("<a class=\"node-link\" href=\"").append(Pc.html(node.deepLink))
            out.append("\" target=\"_blank\" rel=\"noreferrer\">")
            out.append(Pc.html(node.linkLabel == null ? "open" : node.linkLabel)).append("</a>")
        } else if (node.linkNote != null) {
            out.append("<span class=\"node-nolink\" title=\"").append(Pc.html(node.linkNote))
            out.append("\">no link</span>")
        }
        if (!node.isReadable()) {
            out.append("<span class=\"state state-").append(Pc.html(node.state)).append("\">")
            out.append(Pc.html(stateLabel(node.state))).append("</span>")
        }
        out.append("</div>")
        for (String entry : node.diagnostics) {
            out.append("<div class=\"node-diag\">").append(Pc.html(entry)).append("</div>")
        }
        for (String entry : node.notes) {
            out.append("<div class=\"node-note\">").append(Pc.html(entry)).append("</div>")
        }
        if (hasChildren) {
            out.append("<ul class=\"tree").append(collapse ? " hidden" : "").append("\">")
            for (Nd child : node.children) {
                out.append(treeNode(child, expandAll, level + 1))
            }
            out.append("</ul>")
        }
        out.append("</li>")
        return out.toString()
    }

    /* A configuration value is whatever an administrator or another app put
     * there. A serialised app setting is routinely several thousand characters
     * with no space in them; rendered raw that has no break opportunity and runs
     * straight out of the card, taking the layout with it.
     *
     * Long values are therefore clamped into a details element. Nothing is
     * dropped - the full text is one click away and is in the JSON and the CSV
     * either way - but the page stays readable. Truncating without saying so
     * would be the other, worse answer. */
    static final int VALUE_CLAMP = 200

    /* A section heading is a heading. Two hundred characters of value push the
     * table another line down the page before the reader has seen a single row. */
    static final int SECTION_VALUE_CLAMP = 120

    static String valueHtml(Object value) {
        return valueHtml(value, VALUE_CLAMP)
    }

    static String valueHtml(Object value, int clamp) {
        String text = Pc.text(value)
        if (text == null) {
            return ""
        }
        if (text.length() <= clamp) {
            return Pc.html(text)
        }
        StringBuilder out = new StringBuilder()
        out.append("<details class=\"long\"><summary>")
        out.append("<span class=\"clamped\">").append(Pc.html(text.substring(0, clamp)))
        out.append("&#8230;</span>")
        out.append("<span class=\"more\">show all (").append(String.valueOf(text.length()))
        out.append(" characters)</span></summary>")
        out.append("<div class=\"long-body\">").append(Pc.html(text)).append("</div>")
        out.append("</details>")
        return out.toString()
    }

    static String stateLabel(String state) {
        if (Pc.UNREADABLE.equals(state)) {
            return "could not be read"
        }
        if (Pc.ABSENT.equals(state)) {
            return "not configured"
        }
        if (Pc.TRUNCATED.equals(state)) {
            return "shortened"
        }
        if (Pc.REDACTED.equals(state)) {
            return "withheld"
        }
        return "read"
    }

    private static String footer(Report report) {
        return "<div class=\"footer muted\">Space configuration report v" + Pc.html(report.version) +
            ". Read-only: producing this report changes nothing and contacts nothing outside this instance.</div>\n"
    }

    /* The picker. Rendered by the same endpoint when no space was named, so the
     * administrator never has to know a space key by heart. */
    static String picker(Report shell, List<Map<String, String>> spaces, String selfPath, int total) {
        StringBuilder out = new StringBuilder()
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<title>Space configuration</title>\n").append(style())
        out.append("</head>\n<body>\n<div class=\"page\">\n")
        out.append("<div class=\"page-header\"><div>")
        out.append("<h1 class=\"page-title\">Space configuration</h1>")
        out.append("<div class=\"page-subtitle\">Pick a space. The report expands every configuration ")
        out.append("item of that space and links each one to the screen where it is maintained.</div>")
        out.append("</div></div>\n")
        out.append(instanceCard(shell))
        out.append(spacePicker(spaces, selfPath, total))
        if (!shell.globalDiagnostics.isEmpty()) {
            out.append("<div class=\"diag diag-warn\"><strong>Some reads were suppressed.</strong><ul>")
            for (String entry : shell.globalDiagnostics) {
                out.append("<li>").append(Pc.html(entry)).append("</li>")
            }
            out.append("</ul></div>\n")
        }
        out.append("</div>\n").append(pickerScript()).append("</body>\n</html>\n")
        return out.toString()
    }

    /* How many space rows are visible at once. An instance with five thousand
     * spaces turns a dropdown into a scroll hunt, so the list is searched rather
     * than scrolled. Every space that was read is already in the page, which is
     * what makes the search instant and keeps it from costing a request per
     * keystroke. The cap here is on what is SHOWN; the cap on what was READ is a
     * separate number and the count line names both, so a filtered list can never
     * be mistaken for the whole one. */
    static final int SPACE_ROWS = 40

    static String spacePicker(List<Map<String, String>> spaces, String selfPath, int total) {
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"export-card\">")
        out.append("<div class=\"export-title\">Choose a space</div>")
        out.append("<div class=\"export-grid\">")
        out.append("<label class=\"export-field\">Search by key or name")
        out.append("<input id=\"spaceQuery\" class=\"wide\" type=\"search\" autocomplete=\"off\" ")
        out.append("placeholder=\"Type a space key or part of a name...\" ")
        out.append("oninput=\"filterSpaces()\" onkeydown=\"pickFirstSpace(event)\"></label>")
        out.append("<div class=\"export-chosen\" id=\"spaceCount\">")
        out.append(Pc.html(countLine(spaces.size(), spaces.size())))
        out.append("</div></div>")

        /* The read cap belongs next to the list, not in a log. A picker that
         * silently holds the first two thousand of five thousand spaces is a
         * picker that hides the space somebody is looking for. */
        if (total > spaces.size()) {
            out.append("<div class=\"diag diag-warn\"><strong>This list is not complete.</strong> ")
            out.append("The instance holds ").append(String.valueOf(total))
            out.append(" spaces and this page carries the first ").append(String.valueOf(spaces.size()))
            out.append(", which is this report's own cap. A space beyond it is reached by putting ")
            out.append("its key in the space parameter of this URL.</div>")
        }

        out.append("<div id=\"spaceResults\" class=\"export-results project-list\">")
        int shown = 0
        for (Map<String, String> space : spaces) {
            String key = Pc.orNa(space.get("key"))
            String name = Pc.orNa(space.get("name"))
            String type = Pc.text(space.get("type"))
            String status = Pc.text(space.get("status"))
            boolean visible = shown < SPACE_ROWS
            if (visible) {
                shown++
            }
            out.append("<a class=\"export-hit").append(visible ? "" : " hidden").append("\" href=\"")
            out.append(Pc.html(selfPath)).append("?space=").append(Pc.html(Pc.urlQuery(key)))
            out.append("\" data-find=\"")
            out.append(Pc.html((key + " " + name).toLowerCase(Locale.ROOT)))
            out.append("\"><strong>").append(Pc.html(key)).append("</strong> ")
            out.append(Pc.html(name))
            /* A personal space and an archived space are told apart here rather
             * than after the click. Both exist in numbers on a real instance and
             * both look like an ordinary space in a list that does not say. */
            if ("personal".equalsIgnoreCase(type)) {
                out.append(" <span class=\"tag\">personal</span>")
            }
            if ("ARCHIVED".equalsIgnoreCase(status)) {
                out.append(" <span class=\"tag\">archived</span>")
            }
            out.append("</a>")
        }
        out.append("</div>")
        out.append("<div id=\"spaceEmpty\" class=\"export-empty hidden\">No space matches that search.</div>")

        out.append("<div class=\"export-note\">The report reads configuration only: no content is ")
        out.append("counted and no search is run, so the run is harmless on a production instance.</div>")
        out.append("</div>\n")
        return out.toString()
    }

    /* Rendered on the server for the first paint and recomputed in the browser on
     * every keystroke. The two must agree, so the wording lives here and the
     * script below mirrors it; the offline suite checks this one, which is the one
     * a reader without JavaScript ever sees. */
    static String countLine(int matching, int total) {
        if (matching == 0) {
            return "no match out of " + String.valueOf(total) + " spaces"
        }
        String tail = matching > SPACE_ROWS ? ", showing the first " + String.valueOf(SPACE_ROWS) : ""
        if (matching == total) {
            return String.valueOf(total) + " spaces" + tail
        }
        return String.valueOf(matching) + " of " + String.valueOf(total) + " spaces match" + tail
    }

    /* The picker carries its own script rather than the report's: it has no tree
     * to fold, and shipping the whole thing here would mean two pages sharing code
     * only one of them can use. */
    private static String pickerScript() {
        return """<script>
var SPACE_ROWS = ${SPACE_ROWS};

function spaceCountLine(matching, total) {
    if (matching === 0) { return 'no match out of ' + total + ' spaces'; }
    var tail = matching > SPACE_ROWS ? ', showing the first ' + SPACE_ROWS : '';
    if (matching === total) { return total + ' spaces' + tail; }
    return matching + ' of ' + total + ' spaces match' + tail;
}

function filterSpaces() {
    var query = (document.getElementById('spaceQuery').value || '').trim().toLowerCase();
    var rows = document.querySelectorAll('#spaceResults .export-hit');
    var matching = 0;
    var shown = 0;
    for (var i = 0; i < rows.length; i++) {
        var isHit = query === '' || rows[i].getAttribute('data-find').indexOf(query) >= 0;
        if (isHit) { matching++; }
        var show = isHit && shown < SPACE_ROWS;
        if (show) { shown++; }
        rows[i].classList.toggle('hidden', !show);
    }
    document.getElementById('spaceCount').textContent = spaceCountLine(matching, rows.length);
    document.getElementById('spaceEmpty').classList.toggle('hidden', matching !== 0);
}

/* Enter opens the first hit. Typing a key you already know should not need the
   mouse for the last step. */
function pickFirstSpace(event) {
    if (event.key !== 'Enter') { return; }
    event.preventDefault();
    var first = document.querySelector('#spaceResults .export-hit:not(.hidden)');
    if (first) { window.location.href = first.getAttribute('href'); }
}
</script>
"""
    }

    private static String style() {
        return """<style>
:root {
    --page-bg: #f7f8f9;
    --surface: #ffffff;
    --surface-subtle: #f7f8f9;
    --text: #172b4d;
    --text-subtle: #626f86;
    --border: #dcdfe4;
    --border-subtle: #ebecf0;
    --blue: #0c66e4;
    --blue-soft: #e9f2ff;
    --green: #216e4e;
    --yellow: #7f5f01;
    --yellow-soft: #fff7d6;
    --yellow-border: #f5cd47;
    --red: #ae2e24;
    --red-soft: #ffeceb;
    --red-border: #ffd5d2;
    --purple-border: #b8acf6;
    --shadow: 0 1px 2px rgba(9, 30, 66, .08), 0 1px 3px rgba(9, 30, 66, .06);
}
* { box-sizing: border-box; }
body {
    margin: 0;
    background: var(--page-bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    font-size: 14px;
    line-height: 1.45;
}
.page { max-width: 1580px; margin: 0 auto; padding: 28px 32px 40px; }
.mono { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-size: 12px; }
.muted { color: var(--text-subtle); }
.hidden { display: none !important; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 20px; }
.page-title { margin: 0 0 4px; font-size: 22px; font-weight: 600; letter-spacing: -.01em; }
.page-subtitle { color: var(--text-subtle); font-size: 13px; max-width: 900px; }
.instance {
    background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 10px 14px; margin: 12px 0 20px; box-shadow: var(--shadow);
    display: flex; flex-wrap: wrap; gap: 6px 28px;
}
.instance div { font-size: 13px; }
.instance strong { font-weight: 600; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
.button {
    display: inline-block; padding: 6px 12px; border-radius: 4px; border: 1px solid var(--border);
    background: var(--surface); color: var(--text); text-decoration: none; font-size: 13px; font-weight: 500;
    cursor: pointer;
}
.button:hover { background: var(--surface-subtle); }
.button.on { background: var(--blue-soft); border-color: var(--blue); color: var(--blue); }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-bottom: 16px; }
.summary-card { background: var(--surface); border: 1px solid var(--border); border-radius: 6px; padding: 14px 16px; box-shadow: var(--shadow); }
.summary-value { font-size: 24px; font-weight: 600; letter-spacing: -.02em; }
.summary-label { color: var(--text-subtle); font-size: 12px; margin-top: 2px; }
.diag { border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; font-size: 13px;
    background: var(--blue-soft); border: 1px solid var(--purple-border); }
.diag-warn { background: var(--yellow-soft); border-color: var(--yellow-border); }
.diag ul { margin: 8px 0 0; padding-left: 20px; }
.diag li { margin: 2px 0; }
.section { background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 14px 18px 16px; margin-bottom: 16px; box-shadow: var(--shadow); }
.section-head { display: flex; align-items: baseline; gap: 12px; }
.section-title { margin: 0; font-size: 16px; font-weight: 600; }
.section-value { color: var(--text-subtle); font-size: 13px; margin-top: 2px; }
/* A section-level observation sits under the heading rather than in the tree.
   The schema-coupled label and the measured behaviour of a manager belong to the
   whole section, and a reader who collapses the tree must still see them. */
.section-note { color: var(--text-subtle); font-size: 12px; margin-top: 6px;
    background: var(--surface-subtle); border: 1px solid var(--border-subtle);
    border-radius: 4px; padding: 4px 8px; }
.jump, .node-link { font-size: 12px; color: var(--blue); text-decoration: none; }
.jump:hover, .node-link:hover { text-decoration: underline; }
.linknote { color: var(--text-subtle); font-size: 12px; margin-top: 4px; font-style: italic; }
.empty { font-size: 13px; margin-top: 8px; }
ul.tree { list-style: none; margin: 8px 0 0; padding-left: 0; }
ul.tree ul.tree { margin: 0; padding-left: 20px; border-left: 1px solid var(--border-subtle); }
.node-line { display: flex; align-items: baseline; gap: 8px; padding: 2px 0; flex-wrap: wrap; }
.node-label { font-weight: 500; }
.node-value { color: var(--text-subtle); font-size: 13px; }
.node-id { color: var(--text-subtle); }
.node-nolink { font-size: 12px; color: var(--yellow); border-bottom: 1px dotted var(--yellow); cursor: help; }
.node-diag { font-size: 12px; color: var(--red); background: var(--red-soft);
    border: 1px solid var(--red-border); border-radius: 4px; padding: 2px 8px; margin: 2px 0 2px 22px; }
.node-note { font-size: 12px; color: var(--text-subtle); background: var(--surface-subtle);
    border: 1px solid var(--border-subtle); border-radius: 4px; padding: 2px 8px; margin: 2px 0 2px 22px; }
.state { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .03em; }
.state-unreadable { color: var(--red); }
.state-absent { color: var(--text-subtle); }
.state-truncated { color: var(--yellow); }
/* Withheld is its own colour because it is its own state. Sharing the grey of
   "not configured" would tell the reader the app stores nothing there, which is
   the opposite of what a redaction means. */
.state-redacted { color: var(--blue); }
.tag { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: .03em;
    color: var(--text-subtle); border: 1px solid var(--border); border-radius: 3px;
    padding: 0 4px; margin-left: 6px; }
.twisty { border: 0; background: transparent; cursor: pointer; color: var(--text-subtle);
    font-size: 12px; padding: 0; width: 14px; }
.twisty-spacer { display: inline-block; width: 14px; }
.footer { font-size: 12px; margin-top: 24px; }
.section-count { font-size: 12px; }
.node-count { font-size: 11px; color: var(--text-subtle); background: var(--surface-subtle);
    border: 1px solid var(--border-subtle); border-radius: 8px; padding: 0 6px; }
table.flat { border-collapse: collapse; width: 100%; margin-top: 10px; font-size: 13px; }
table.flat th, table.flat td { border: 1px solid var(--border-subtle); padding: 5px 8px;
    text-align: left; vertical-align: top; }
table.flat th { background: var(--surface-subtle); font-weight: 600; position: sticky; top: 0; }
table.flat tr:nth-child(even) td { background: var(--surface-subtle); }
.view-table { overflow-x: auto; }
.project-list { max-height: 460px; overflow-y: auto; margin-top: 10px; }
.project-list .export-hit { display: block; }
.project-list .export-hit strong { display: inline-block; min-width: 90px; font-family: ui-monospace,
    SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; }
.section-head { cursor: pointer; user-select: none; gap: 10px; }
.section-head:hover .section-title { color: var(--blue); }
.section-head .twisty { font-size: 13px; }
.section-title { flex: 0 1 auto; }
/* Addressed by class, never by column position. These rules used to name
   nth-child(4) and nth-child(5); when a column was removed, State slid from the
   fourth position to the third, silently lost its nowrap and started rendering as
   "NOT CONFIGURE / D" with the break in the middle of the word. A positional
   selector cannot tell that it is now pointing at a different column, so it goes on
   styling the wrong one without any sign. A class moves with its cell. */
table.flat th.col-state, table.flat td.col-state,
table.flat th.col-link, table.flat td.col-link { white-space: nowrap; width: 1%; }
table.flat td.col-link { text-align: right; }
table.flat td.col-level { white-space: normal; font-size: 12px; }
table.flat td.col-value { min-width: 140px; }
/* A record encloses its own tables. The indentation lives on the enclosing element,
   not on an adjacent-sibling selector: that one only ever reached the single table
   that happened to follow the heading, and silently missed everything after it. */
details.rec { margin: 0; }
details.rec.lvl-1, details.rec.lvl-2 { padding-left: 14px; border-left: 2px solid var(--border-subtle); }
/* Proximity does the grouping, so the gaps must disagree with each other. What
   belongs together sits closer than what does not - equal gaps at both levels was
   why the nesting could be read past. */
details.rec.lvl-1 { margin-top: 26px; }
details.rec.lvl-2 { margin-top: 8px; margin-bottom: 14px; }
details.rec[open] > summary { margin-bottom: 2px; }

summary.table-head { cursor: pointer; list-style: none; padding: 3px 0; }
summary.table-head::-webkit-details-marker { display: none; }
summary.table-head h4, summary.table-head h5, summary.table-head h6 {
    display: inline; margin: 0; font-weight: 600; }
summary.table-head .muted { font-weight: 400; font-size: 12px; color: var(--text-subtle); }
/* The same two glyphs as the tree, swapped rather than rotated, and carried in the
   markup instead of a CSS content string. Written as a CSS escape it was read by
   Groovy first, where a backslash followed by digits is an octal escape - the page
   ended up with a control character and the literal text "B8". A glyph that has to
   survive two escaping layers is a glyph in the wrong place. */
summary.table-head .tw-open { display: none; }
details.rec[open] > summary.table-head .tw-open { display: inline-block; }
details.rec[open] > summary.table-head .tw-closed { display: none; }
summary.table-head:hover { background: var(--surface-subtle); }
.table-head.lvl-0 h4 { font-size: 15px; }
.table-head.lvl-1 h5 { font-size: 13px; }
.table-head.lvl-2 h6 { font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
details.rec > table.flat { margin-bottom: 4px; }
.section-value, .node-value, .linknote, table.flat td, .node-diag {
    overflow-wrap: anywhere; word-break: break-word;
}
.node-line { min-width: 0; }
details.long { display: inline; }
details.long > summary { cursor: pointer; list-style: none; display: inline; }
details.long > summary::-webkit-details-marker { display: none; }
details.long .more { color: var(--blue); font-size: 12px; margin-left: 6px; white-space: nowrap; }
details.long[open] .clamped { display: none; }
details.long .long-body { margin-top: 6px; padding: 8px 10px; background: var(--surface-subtle);
    border: 1px solid var(--border-subtle); border-radius: 4px; font-size: 12px;
    max-height: 320px; overflow: auto; }
.export-card {
    background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 14px 18px; margin-bottom: 18px; box-shadow: var(--shadow);
}
.export-title { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
.export-note { color: var(--text-subtle); font-size: 13px; max-width: 1080px; }
.export-grid { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; margin-top: 12px; }
.export-field {
    display: flex; flex-direction: column; gap: 4px; color: var(--text-subtle);
    font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .035em;
}
.export-field input, .export-field select {
    height: 34px; padding: 0 10px; border: 1px solid var(--border); border-radius: 4px;
    background: var(--surface); color: var(--text); font-size: 14px; font-weight: 400;
    text-transform: none; letter-spacing: 0;
}
.export-field input.wide { min-width: 320px; }
.export-chosen { align-self: flex-end; padding-bottom: 8px; color: var(--text-subtle); font-size: 12px; }
.export-results { margin-top: 8px; max-width: 680px; }
.export-hit {
    display: block; width: 100%; margin-bottom: 4px; padding: 6px 10px; text-align: left;
    border: 1px solid var(--border); border-radius: 4px; background: var(--surface-subtle);
    color: var(--text); font-size: 13px; cursor: pointer; text-decoration: none;
}
.export-hit:hover { border-color: var(--blue); background: var(--blue-soft); }
.export-empty { color: var(--text-subtle); font-size: 12px; font-style: italic; }
</style>
"""
    }

    /* The only script on the page. It collapses and expands, nothing else: no
     * fetch, no external resource, no state that outlives the tab. */
    private static String script() {
        return """<script>
function toggleSection(section, force) {
    if (!section) { return; }
    var body = section.querySelector(':scope > .section-body');
    if (!body) { return; }
    var open = force === undefined ? body.classList.contains('hidden') : force;
    body.classList.toggle('hidden', !open);
    var head = section.querySelector(':scope > .section-head');
    if (head) { head.setAttribute('aria-expanded', open ? 'true' : 'false'); }
    var chevron = section.querySelector(':scope > .section-head > .twisty');
    if (chevron) { chevron.innerHTML = open ? '&#9662;' : '&#9656;'; }
}

document.addEventListener('click', function (event) {
    /* The whole section header is the hit area, which is why the link inside it has
       to be excluded by hand: clicking "open in Confluence" must open Confluence,
       not fold the section away underneath the click. */
    var head = event.target.closest('.section-head');
    if (head && !event.target.closest('a')) {
        toggleSection(head.closest('.section'));
        return;
    }
    var button = event.target.closest('.twisty');
    if (!button) { return; }
    var item = button.closest('li.node');
    if (!item) { return; }
    var list = item.querySelector(':scope > ul.tree');
    if (!list) { return; }
    var open = list.classList.toggle('hidden') === false;
    button.setAttribute('aria-expanded', open ? 'true' : 'false');
    button.innerHTML = open ? '&#9662;' : '&#9656;';
});

function setTwisty(item, open) {
    var list = item.querySelector(':scope > ul.tree');
    if (!list) { return; }
    list.classList.toggle('hidden', !open);
    var button = item.querySelector(':scope > .node-line > .twisty');
    if (button) {
        button.setAttribute('aria-expanded', open ? 'true' : 'false');
        button.innerHTML = open ? '&#9662;' : '&#9656;';
    }
}

document.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter' && event.key !== ' ') { return; }
    var head = event.target.closest ? event.target.closest('.section-head') : null;
    if (!head) { return; }
    event.preventDefault();
    toggleSection(head.closest('.section'));
});

function expandAll(open) {
    var sections = document.querySelectorAll('.section');
    for (var s = 0; s < sections.length; s++) { toggleSection(sections[s], open); }
    var items = document.querySelectorAll('li.node');
    for (var i = 0; i < items.length; i++) { setTwisty(items[i], open); }
    /* The table view collapses too, or the button would mean one thing in one view
       and nothing in the other. */
    var records = document.querySelectorAll('details.rec');
    for (var r = 0; r < records.length; r++) { records[r].open = open; }
}

/* Both views are already in the page. Switching only changes which one is shown,
   so the table can never drift away from the tree it was built from. */
function setView(name) {
    var wantTable = name === 'table';
    var trees = document.querySelectorAll('.view-tree');
    var tables = document.querySelectorAll('.view-table');
    for (var i = 0; i < trees.length; i++) { trees[i].classList.toggle('hidden', wantTable); }
    for (var j = 0; j < tables.length; j++) { tables[j].classList.toggle('hidden', !wantTable); }
    var treeButton = document.getElementById('viewTree');
    var tableButton = document.getElementById('viewTable');
    if (treeButton) { treeButton.classList.toggle('on', !wantTable); }
    if (tableButton) { tableButton.classList.toggle('on', wantTable); }
}
</script>
"""
    }
}

/* =============================================================================
 * END OF THE CONFLUENCE-FREE BLOCK
 *
 * Everything above this line is free of Confluence types on purpose: the offline
 * suite compiles exactly that block together with its tests, so the suite always
 * exercises the shipped source instead of a copy that can drift. Everything below
 * touches Confluence and can only be verified on an instance.
 * ========================================================================== */

/* =============================================================================
 * The database read path
 *
 * SQL is the route of last resort and it is taken only where the Java API
 * demonstrably cannot answer. Three things on this report have no API:
 *
 *   the provenance of a permission grant - who granted it and when - which is on
 *   no screen and on no object;
 *   the full grant list of one space, because SpacePermissionManager reads per
 *   subject and has no method returning all permissions of a space;
 *   the namespaces of the plugin settings store, which the PluginSettings API
 *   cannot enumerate at all and which on this instance carry per-space app
 *   configuration under <plugin namespace>:<SPACEKEY>.
 *
 * The access shape is the one the reachability probe measured:
 * TransactionalExecutorFactory, then createReadOnly(), then execute(callback).
 * NO SAL rdbms type is ever named statically. The callback interface is loaded by
 * name and implemented with a JDK proxy, so this file compiles where the package
 * is absent instead of failing to start.
 *
 * Every statement is a SELECT and every value travels as a bound parameter.
 * Nothing is concatenated into SQL.
 * ========================================================================== */

class Db {

    static final String EXECUTOR_FACTORY = "com.atlassian.sal.api.rdbms.TransactionalExecutorFactory"
    static final String CONNECTION_CALLBACK = "com.atlassian.sal.api.rdbms.ConnectionCallback"

    static String why(Throwable error) {
        if (error == null) {
            return null
        }
        String message = Pc.text(error.getMessage())
        String detail = error.getClass().getSimpleName() + (message == null ? "" : " - " + message)
        return detail.length() > 300 ? detail.substring(0, 300) + " [clamped]" : detail
    }

    /* The factory, or null with the reason in the returned map. Acquisition and
     * resolution are different questions and this instance has already shown a
     * type that resolves and yields no component, so the two are kept apart. */
    static Map<String, Object> factory() {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("factory", null)
        out.put("failure", null)
        try {
            Object component = ComponentLocator.getComponent(Class.forName(EXECUTOR_FACTORY))
            if (component == null) {
                out.put("failure", "The SAL read-only executor factory resolved but no component was " +
                    "returned, so no statement was attempted. This is a failed acquisition, not an " +
                    "empty database.")
            } else {
                out.put("factory", component)
            }
        } catch (Throwable error) {
            out.put("failure", "The SAL read-only executor factory could not be obtained: " + why(error) +
                ". No statement was attempted.")
        }
        return out
    }

    /* createReadOnly is not decoration. It is the only thing in this file that
     * makes the read-only claim enforceable rather than a matter of reviewing
     * every statement by eye. */
    static Object withConnection(Object executorFactory, Closure body) {
        Class callbackType = Class.forName(CONNECTION_CALLBACK)
        Object executor = Pc.duckAll(executorFactory, "createReadOnly", new Object[0])
        if (executor == null) {
            throw new IllegalStateException("createReadOnly() returned no executor")
        }
        Object callback = Proxy.newProxyInstance(
            callbackType.getClassLoader(), [callbackType] as Class[],
            new InvocationHandler() {
                Object invoke(Object proxy, Method method, Object[] arguments) {
                    String name = method.getName()
                    if (name == "execute") {
                        return body.call(arguments[0])
                    }
                    if (name == "toString") {
                        return "spaceConfig-callback"
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
        return Pc.duckAll(executor, "execute", [callback] as Object[])
    }

    /* ---- the shape check ------------------------------------------------ */

    /* The Confluence schema is not a public API, so a column this file names can
     * disappear in an upgrade. Read through the database catalogue rather than by
     * running the statement and seeing what happens: a missing column has to
     * produce a section that says which column is missing, not a section that
     * says nothing is configured. A schema change surfacing as an empty result is
     * the exact failure this whole report exists to prevent.
     *
     * Returns the missing columns, or a failure when the catalogue itself could
     * not be read. Those two are different answers and are never merged. */
    static Map<String, Object> shape(Connection connection, String table, List<String> required) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("table", table)
        out.put("missing", new ArrayList<String>())
        out.put("failure", null)
        try {
            Set<String> present = new LinkedHashSet<String>()
            DatabaseMetaData meta = connection.getMetaData()
            /* Identifier case is a property of the database, not of this file.
             * Postgres folds unquoted names to lower case and other engines fold
             * to upper, so both spellings are asked for and the first that
             * answers wins. */
            for (String candidate : [table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)]) {
                ResultSet columns = meta.getColumns(null, null, candidate, null)
                try {
                    while (columns.next()) {
                        String name = columns.getString("COLUMN_NAME")
                        if (name != null) {
                            present.add(name.toLowerCase(Locale.ROOT))
                        }
                    }
                } finally {
                    columns.close()
                }
                if (!present.isEmpty()) {
                    break
                }
            }
            List<String> missing = (List<String>) out.get("missing")
            for (String column : required) {
                if (!present.contains(column.toLowerCase(Locale.ROOT))) {
                    missing.add(column)
                }
            }
        } catch (Throwable error) {
            out.put("failure", "The database catalogue could not be read: " + why(error))
        }
        return out
    }

    /* The shape check turned into the one sentence a section needs, or null when
     * the table is as this file expects it. */
    static String shapeProblem(Map<String, Object> shape) {
        String failure = Pc.text(shape.get("failure"))
        if (failure != null) {
            return failure + " The columns this section reads could not be verified, so nothing was read."
        }
        List<String> missing = (List<String>) shape.get("missing")
        if (missing == null || missing.isEmpty()) {
            return null
        }
        return "The table " + Pc.orNa(shape.get("table")) + " does not carry " +
            (missing.size() == 1 ? "the column " : "the columns ") + missing.join(", ") +
            " on this instance. The Confluence schema is not a public API and an upgrade can " +
            "change it. Nothing was read rather than reporting an empty result."
    }

    /* ---- the statement -------------------------------------------------- */

    /* A read that fails and a read that found nothing return different objects.
     * A fail-soft accessor that answers the same way for both is how a failed
     * read becomes a proven absence, so failure travels WITH the result. */
    static Map<String, Object> query(Connection connection, String sql, List<String> arguments,
                                     List<String> columns, int cap) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>()
        out.put("rows", rows)
        out.put("truncated", Boolean.FALSE)
        out.put("failure", null)
        out.put("cap", Integer.valueOf(cap))
        PreparedStatement statement = null
        try {
            statement = connection.prepareStatement(sql)
            for (int index = 0; index < arguments.size(); index++) {
                statement.setString(index + 1, arguments.get(index))
            }
            ResultSet results = statement.executeQuery()
            try {
                while (results.next()) {
                    if (rows.size() >= cap) {
                        /* The cap is detected by asking for one more row than fits,
                         * so "exactly at the cap" and "more than the cap" are told
                         * apart. A report that cannot tell those apart announces a
                         * truncation that did not happen, or worse, hides one that
                         * did. */
                        out.put("truncated", Boolean.TRUE)
                        break
                    }
                    Map<String, String> row = new LinkedHashMap<String, String>()
                    for (String column : columns) {
                        row.put(column, results.getString(column))
                    }
                    rows.add(row)
                }
            } finally {
                results.close()
            }
        } catch (Throwable error) {
            out.put("failure", "The statement failed: " + why(error))
        } finally {
            if (statement != null) {
                try {
                    statement.close()
                } catch (Throwable ignored) {
                    /* A close that fails changes nothing about the rows already
                     * read and must not turn a successful read into a failed one. */
                }
            }
        }
        return out
    }
}

/* =============================================================================
 * The scan
 *
 * One method per section of the report. Every method returns a node, never
 * throws, and never turns a failed read into an empty result: the reason is
 * attached to the node it belongs to, which is what lets the reader tell
 * "nothing is configured here" from "this could not be read".
 * ========================================================================== */

class Scan {

    /* Caps. Every one of them is announced in the node it applies to. */
    static final int PERMISSION_CAP = 2000
    static final int PROPERTY_CAP = 1000
    static final int PICKER_CAP = 2000
    static final int TEMPLATE_CAP = 500
    static final int CATEGORY_CAP = 200
    static final int VALUE_CLAMP = 200

    static final String SCHEMA_COUPLED =
        "Schema-coupled. This section reads the Confluence database directly, because the " +
        "Java API cannot answer it. The schema is not a public API: an upgrade can change it, " +
        "and if it does this section reports that rather than an empty result."

    String spaceKey
    Dl links

    Scan(String spaceKey, Dl links) {
        this.spaceKey = spaceKey
        this.links = links
    }

    /* The single place a read is allowed to fail. A caller that writes its own
     * try/catch will eventually write one that swallows, and a swallowed read is
     * how a report starts claiming an absence it never measured. Widened to
     * Throwable because a half-present optional app raises linkage errors rather
     * than exceptions, and one of those would otherwise take the whole report
     * down instead of failing a single node. */
    static Nd guard(Nd node, Closure body) {
        try {
            body.call(node)
        } catch (Throwable error) {
            node.failed("Read failed: " + Db.why(error))
        }
        return node
    }

    /* ---- 1. Space details ------------------------------------------------ */

    static final List<String> SPACE_COLUMNS = [
        "spaceid", "spacekey", "spacename", "spacetype", "spacestatus",
        "spacedescid", "homepage", "creator", "creationdate", "lastmodifier", "lastmoddate"]

    /* The user key columns are resolved through user_mapping in the same
     * statement. SPACES.CREATOR is a foreign key to ConfluenceUserImpl and holds
     * a user key, not a name - measured on the instance, where it reads
     * 8aaa81a1...0000 - so printing it raw would be printing an opaque id where
     * the reader expects a person. */
    static final String SPACE_SQL =
        "SELECT s.spaceid, s.spacekey, s.spacename, s.spacetype, s.spacestatus, " +
        "s.spacedescid, s.homepage, s.creator, s.creationdate, s.lastmodifier, s.lastmoddate, " +
        "uc.username AS creatorname, um.username AS modifiername " +
        "FROM spaces s " +
        "LEFT JOIN user_mapping uc ON uc.user_key = s.creator " +
        "LEFT JOIN user_mapping um ON um.user_key = s.lastmodifier " +
        "WHERE s.spacekey = ?"

    static final List<String> SPACE_READ = [
        "spaceid", "spacekey", "spacename", "spacetype", "spacestatus", "spacedescid",
        "homepage", "creator", "creationdate", "lastmodifier", "lastmoddate",
        "creatorname", "modifiername"]

    /* The row itself, so the endpoint can name the space in its title and the
     * later sections can use the ids without a second statement. */
    static Map<String, Object> spaceRow(Connection connection, String spaceKey) {
        Map<String, Object> shape = Db.shape(connection, "spaces", SPACE_COLUMNS)
        String problem = Db.shapeProblem(shape)
        if (problem == null) {
            Map<String, Object> mapping = Db.shape(connection, "user_mapping", ["user_key", "username"])
            problem = Db.shapeProblem(mapping)
        }
        if (problem != null) {
            Map<String, Object> out = new LinkedHashMap<String, Object>()
            out.put("row", null)
            out.put("failure", problem)
            return out
        }
        Map<String, Object> result = Db.query(connection, SPACE_SQL, [spaceKey], SPACE_READ, 2)
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        List<Map<String, String>> rows = (List<Map<String, String>>) result.get("rows")
        out.put("row", rows.isEmpty() ? null : rows.get(0))
        out.put("failure", result.get("failure"))
        return out
    }

    Nd details(Map<String, String> row, String failure) {
        Nd node = Nd.of("spaceDetails", "Details")
        node.link(links.spaceSummary(spaceKey), "Space tools > Overview > Space details.")
        node.note(SCHEMA_COUPLED)
        if (failure != null) {
            return node.failed(failure)
        }
        if (row == null) {
            /* Not the same as a space with no details. The statement ran and
             * matched nothing, which means this key is not in SPACES. */
            return node.absent("No row in SPACES carries this space key.")
        }
        node.add(Nd.of("spaceField", "Key").val(row.get("spacekey")))
        node.add(Nd.of("spaceField", "Name").val(row.get("spacename")))
        node.add(Nd.of("spaceField", "Id").val(row.get("spaceid")))

        /* Personal spaces exist in numbers on a real instance and behave
         * differently from global ones - they are created per user and their key
         * is derived from the user key. A report that does not say which kind of
         * space it is describing invites the reader to apply the wrong rules. */
        node.add(Nd.of("spaceField", "Type").val(Pc.spaceType(row.get("spacetype"))))

        /* The archive screen is the one node here that cannot carry a link: no
         * archive action and no archive web-item ships on 10.2.14. */
        node.add(Nd.of("spaceField", "Status").val(Pc.spaceStatus(row.get("spacestatus")))
            .link(null, Dl.archiveUnavailableNote()))

        node.add(Nd.of("spaceField", "Created by").val(Pc.userLabel(row.get("creatorname"), row.get("creator"))))
        node.add(Nd.of("spaceField", "Created").val(Pc.stamp(row.get("creationdate"))))
        node.add(Nd.of("spaceField", "Last modified by").val(Pc.userLabel(row.get("modifiername"), row.get("lastmodifier"))))
        node.add(Nd.of("spaceField", "Last modified").val(Pc.stamp(row.get("lastmoddate"))))

        /* Both ids are printed because both are how an administrator finds the
         * space again in the database, and the space description id is the join
         * key the property section below needs. */
        Nd home = Nd.of("spaceField", "Home page id")
        String homepage = Pc.text(row.get("homepage"))
        node.add(homepage == null ? home.absent("No home page is set for this space.") : home.val(homepage))

        Nd description = Nd.of("spaceField", "Space description id")
        String descid = Pc.text(row.get("spacedescid"))
        node.add(descid == null
            ? description.absent("This space has no space description row, so it carries no content properties.")
            : description.val(descid))
        return node
    }

    /* ---- 2. Permissions -------------------------------------------------- */

    static final List<String> PERMISSION_COLUMNS = [
        "permid", "spaceid", "permtype", "permgroupname", "permusername",
        "permalluserssubject", "creator", "creationdate", "lastmodifier", "lastmoddate"]

    static final String PERMISSION_SQL =
        "SELECT p.permid, p.permtype, p.permgroupname, p.permusername, p.permalluserssubject, " +
        "p.creator, p.creationdate, p.lastmodifier, p.lastmoddate, " +
        "su.username AS subjectname, cu.username AS creatorname, mu.username AS modifiername " +
        "FROM spacepermissions p " +
        "JOIN spaces s ON s.spaceid = p.spaceid " +
        "LEFT JOIN user_mapping su ON su.user_key = p.permusername " +
        "LEFT JOIN user_mapping cu ON cu.user_key = p.creator " +
        "LEFT JOIN user_mapping mu ON mu.user_key = p.lastmodifier " +
        "WHERE s.spacekey = ? " +
        "ORDER BY p.permtype, p.permgroupname, p.permusername, p.permid"

    static final List<String> PERMISSION_READ = [
        "permid", "permtype", "permgroupname", "permusername", "permalluserssubject",
        "creator", "creationdate", "lastmodifier", "lastmoddate",
        "subjectname", "creatorname", "modifiername"]

    Nd permissions(Connection connection, Object spaceId) {
        Nd node = Nd.of("spacePermissions", "Permissions")
        node.link(links.spacePermissions(spaceKey), "Space tools > Permissions.")
        node.note(SCHEMA_COUPLED)
        node.note("Who granted a permission and when appears on no administration screen. " +
            "It is read here from the grant row itself and is the reason this section reads SQL.")

        if (connection == null) {
            return node.failed("No database connection was available, so no grant was read. " +
                "This is a failed read, not a space without grants.")
        }
        Map<String, Object> shape = Db.shape(connection, "spacepermissions", PERMISSION_COLUMNS)
        String problem = Db.shapeProblem(shape)
        if (problem != null) {
            return node.failed(problem)
        }
        Map<String, Object> result = Db.query(connection, PERMISSION_SQL, [spaceKey],
            PERMISSION_READ, PERMISSION_CAP)
        String failure = Pc.text(result.get("failure"))
        if (failure != null) {
            return node.failed(failure)
        }
        List<Map<String, String>> rows = (List<Map<String, String>>) result.get("rows")
        if (rows.isEmpty()) {
            /* Measured on the instance: 31 of 5038 spaces carry no grant row at
             * all, and ENG is one of them. That is a real configuration state -
             * the space falls back to the defaults - and it has to look different
             * from a read that failed, which is what the state on this node does. */
            return node.absent("No explicit grants are stored for this space. Confluence falls back " +
                "to the space permission defaults, which are a global setting and are not part of " +
                "this space's own configuration. The read succeeded and returned nothing.")
        }
        if (Boolean.TRUE.equals(result.get("truncated"))) {
            node.cappedAt(PERMISSION_CAP, "grants")
        }

        /* Grouped by permission type, which is how the permissions screen is laid
         * out and how an administrator asks the question: who may export this
         * space, not what may this group do. */
        Map<String, Nd> byType = new LinkedHashMap<String, Nd>()
        for (Map<String, String> row : rows) {
            String type = Pc.orNa(row.get("permtype"))
            Nd group = byType.get(type)
            if (group == null) {
                group = Nd.of("spacePermissionType", type)
                byType.put(type, group)
                node.add(group)
            }
            group.add(grant(row))
        }
        for (Map.Entry<String, Nd> entry : byType.entrySet()) {
            entry.getValue().val(Pc.plural(entry.getValue().children.size(), "grant"))
        }

        /* Where the change history of these grants lives. The audit log is
         * addressed by space ID rather than by space key, which is the reason
         * every link shape in this file is templated from its own evidence. */
        Nd audit = Nd.of("spacePermissionAudit", "Change history")
        audit.val("Permission changes are recorded in the Confluence audit log.")
        audit.link(links.spaceAuditLog(spaceId),
            "Space tools > Audit log. The audit log web-item ships with the bundled audit app; " +
            "without the space id this report cannot address it.")
        audit.linkAs("open the audit log")
        node.add(audit)
        return node
    }

    private Nd grant(Map<String, String> row) {
        String group = Pc.text(row.get("permgroupname"))
        String userKey = Pc.text(row.get("permusername"))
        String allUsers = Pc.text(row.get("permalluserssubject"))

        Nd node = Nd.of("spacePermissionGrant",
            Pc.subject(group, userKey, row.get("subjectname"), allUsers))
        node.ident(row.get("permid"))
        if (group == null && userKey == null && allUsers != null
            && !Pc.AUTHENTICATED_SUBJECT.equalsIgnoreCase(allUsers)) {
            /* A subject encoding this file has not measured. It is named rather
             * than guessed at, and it is a note rather than a diagnostic because
             * nothing failed to read. */
            node.note("This grant carries the subject value " + allUsers + ", which is neither a " +
                "group, a user, nor either of the two values measured on Confluence 10.2.14. " +
                "It is printed as stored rather than interpreted.")
        }
        node.add(Nd.of("spacePermissionGrantDetail", "Granted by")
            .val(Pc.userLabel(row.get("creatorname"), row.get("creator"))))
        node.add(Nd.of("spacePermissionGrantDetail", "Granted")
            .val(Pc.stamp(row.get("creationdate"))))
        node.add(Nd.of("spacePermissionGrantDetail", "Last modified by")
            .val(Pc.userLabel(row.get("modifiername"), row.get("lastmodifier"))))
        node.add(Nd.of("spacePermissionGrantDetail", "Last modified")
            .val(Pc.stamp(row.get("lastmoddate"))))
        return node
    }

    /* ---- 3. Space properties --------------------------------------------- */

    /* KEYS ONLY unless the caller asked for values. The stores hold
     * XStream-serialised objects that apps write, apps put credentials in them,
     * and this report is meant to be read by more people than the one who ran it.
     * Which key names are withheld is decided by Pc.sensitive, which is above the
     * Confluence-free banner so the offline suite can drive it. */

    static final String PLUGIN_SETTING_KEYS_SQL =
        "SELECT ps.namespace, ps.setting_key FROM plugin_setting ps " +
        "WHERE ps.namespace = ? OR ps.namespace LIKE ? ORDER BY ps.namespace, ps.setting_key"

    static final String PLUGIN_SETTING_VALUES_SQL =
        "SELECT ps.namespace, ps.setting_key, ps.setting_value FROM plugin_setting ps " +
        "WHERE ps.namespace = ? OR ps.namespace LIKE ? ORDER BY ps.namespace, ps.setting_key"

    static final String CONTENT_PROPERTY_KEYS_SQL =
        "SELECT cp.propertyname FROM contentproperties cp " +
        "JOIN spaces s ON s.spacedescid = cp.contentid WHERE s.spacekey = ? ORDER BY cp.propertyname"

    static final String CONTENT_PROPERTY_VALUES_SQL =
        "SELECT cp.propertyname, cp.stringval, cp.longval, cp.dateval FROM contentproperties cp " +
        "JOIN spaces s ON s.spacedescid = cp.contentid WHERE s.spacekey = ? ORDER BY cp.propertyname"

    /* One entry per namespace and key, naming every store it was found in. The
     * stores are NOT independent: measured on this instance, the same key comes
     * back from Bandana and from the plugin settings for the same space, because
     * the plugin settings delegate to Bandana while the migration flag is false.
     * Concatenating the lists would report one setting twice and make a reader
     * believe an app had written two. */
    Nd properties(Connection connection, BandanaManager bandana, boolean withValues) {
        Nd node = Nd.of("spaceProperties", "Space properties")
        node.link(null, Dl.propertyStoreUnavailableNote())
        node.note(SCHEMA_COUPLED)
        node.note("Three stores are read and deduplicated on namespace plus key. They overlap: " +
            "the plugin settings delegate to Bandana until the migration flag is set, so one " +
            "setting can legitimately appear in both and is listed once, naming both stores.")
        if (!withValues) {
            node.note("Keys only. Values are read only when the values parameter is set, because " +
                "they are serialised objects that apps put credentials into.")
        }

        Map<String, Map<String, Object>> found = new LinkedHashMap<String, Map<String, Object>>()
        List<String> failures = new ArrayList<String>()
        boolean capped = false

        /* Store 1 - the plugin settings table, read by SQL because the API cannot
         * enumerate namespaces and cannot see the suffixed form at all. Measured:
         * ENG and HR carry com.atlassian.confluence.blueprints.plugin-module-state:<KEY>
         * while createSettingsForKey returns empty for both. */
        if (connection == null) {
            failures.add("No database connection was available, so neither the plugin settings " +
                "nor the content properties of this space were read.")
        } else {
            Map<String, Object> shape = Db.shape(connection, "plugin_setting",
                withValues ? ["namespace", "setting_key", "setting_value"] : ["namespace", "setting_key"])
            String problem = Db.shapeProblem(shape)
            if (problem != null) {
                failures.add(problem)
            } else {
                List<String> columns = withValues
                    ? ["namespace", "setting_key", "setting_value"] : ["namespace", "setting_key"]
                Map<String, Object> result = Db.query(connection,
                    withValues ? PLUGIN_SETTING_VALUES_SQL : PLUGIN_SETTING_KEYS_SQL,
                    [spaceKey, "%:" + spaceKey], columns, PROPERTY_CAP)
                String failure = Pc.text(result.get("failure"))
                if (failure != null) {
                    failures.add("Plugin settings: " + failure)
                } else {
                    if (Boolean.TRUE.equals(result.get("truncated"))) {
                        capped = true
                    }
                    for (Map<String, String> row : (List<Map<String, String>>) result.get("rows")) {
                        record(found, Pc.orNa(row.get("namespace")), Pc.orNa(row.get("setting_key")),
                            "plugin settings", withValues ? row.get("setting_value") : null, withValues)
                    }
                }
            }

            /* Store 3 - the content properties hanging off the space description.
             * The service that reads these, SpacePropertyService, resolves on this
             * instance and yields no component: the API service layer is a Spring
             * proxy the ScriptRunner chaining classloader cannot use. That is a
             * reproduced failure, not a transient one, so this store has no API
             * route at all. */
            Map<String, Object> propertyShape = Db.shape(connection, "contentproperties",
                withValues ? ["propertyname", "contentid", "stringval", "longval", "dateval"]
                           : ["propertyname", "contentid"])
            String propertyProblem = Db.shapeProblem(propertyShape)
            if (propertyProblem != null) {
                failures.add(propertyProblem)
            } else {
                List<String> columns = withValues
                    ? ["propertyname", "stringval", "longval", "dateval"] : ["propertyname"]
                Map<String, Object> result = Db.query(connection,
                    withValues ? CONTENT_PROPERTY_VALUES_SQL : CONTENT_PROPERTY_KEYS_SQL,
                    [spaceKey], columns, PROPERTY_CAP)
                String failure = Pc.text(result.get("failure"))
                if (failure != null) {
                    failures.add("Content properties: " + failure)
                } else {
                    if (Boolean.TRUE.equals(result.get("truncated"))) {
                        capped = true
                    }
                    for (Map<String, String> row : (List<Map<String, String>>) result.get("rows")) {
                        record(found, "space description (contentproperties)",
                            Pc.orNa(row.get("propertyname")), "content properties",
                            withValues ? firstPresent(row) : null, withValues)
                    }
                }
            }
        }

        /* Store 2 - Bandana, in the space context. The context takes the space key
         * as a String and needs no Space object; that constructor was read out of
         * the bytecode rather than assumed. getKeys does NOT inherit the global
         * keys on this version, confirmed against a positive global control, so
         * what comes back belongs to this space.
         *
         * BandanaManager is deprecated since Confluence 9.3 and marked for removal
         * in 11.0. It is used anyway because it is the only enumeration route into
         * this store that exists today, and this comment is here so the next
         * reader knows it was a decision rather than an oversight. */
        if (bandana == null) {
            failures.add("BandanaManager was not available, so the Bandana store of this space " +
                "was not read. This is a failed read, not an empty store.")
        } else {
            try {
                ConfluenceBandanaContext context = new ConfluenceBandanaContext(spaceKey)
                int read = 0
                for (String key : bandana.getKeys(context)) {
                    if (read >= PROPERTY_CAP) {
                        capped = true
                        break
                    }
                    read++
                    String value = null
                    if (withValues && !Pc.sensitive(key)) {
                        value = describe(bandana.getValue(context, key))
                    }
                    record(found, spaceKey, Pc.orNa(key), "Bandana", value, withValues)
                }
            } catch (Throwable error) {
                failures.add("Bandana: " + Db.why(error))
            }
        }

        for (String failure : failures) {
            node.diagnostics.add(failure)
        }
        if (found.isEmpty()) {
            if (!failures.isEmpty()) {
                /* Some store failed AND nothing was found. The empty result is
                 * then not a measurement of anything and must not be printed as
                 * one. */
                return node.failed("No property was read and at least one store could not be read, " +
                    "so this space cannot be reported as having none.")
            }
            return node.absent("No application has written a property for this space in any of the " +
                "three stores. All three were read successfully.")
        }
        if (capped) {
            node.cappedAt(PROPERTY_CAP, "properties per store")
        }
        if (!failures.isEmpty()) {
            /* Partly read. The keys below are real, and the section says plainly
             * that it is not the whole picture. */
            node.note("At least one store could not be read. What is listed below is what the " +
                "readable stores hold, not everything this space carries.")
        }
        for (Map<String, Object> entry : found.values()) {
            node.add(property(entry, withValues))
        }
        return node
    }

    /* The first value column that carries anything. A content property row holds
     * its value in one of three typed columns and the others are null. */
    private static String firstPresent(Map<String, String> row) {
        for (String column : ["stringval", "longval", "dateval"]) {
            String value = Pc.text(row.get(column))
            if (value != null) {
                return column + " = " + value
            }
        }
        return null
    }

    private static void record(Map<String, Map<String, Object>> found, String namespace, String key,
                               String store, String value, boolean withValues) {
        String id = namespace + " " + key
        Map<String, Object> entry = found.get(id)
        if (entry == null) {
            entry = new LinkedHashMap<String, Object>()
            entry.put("namespace", namespace)
            entry.put("key", key)
            entry.put("stores", new LinkedHashSet<String>())
            entry.put("value", null)
            found.put(id, entry)
        }
        ((Set<String>) entry.get("stores")).add(store)
        if (withValues && entry.get("value") == null && value != null) {
            entry.put("value", value)
        }
    }

    private static Nd property(Map<String, Object> entry, boolean withValues) {
        String key = Pc.orNa(entry.get("key"))
        Set<String> stores = (Set<String>) entry.get("stores")
        Nd node = Nd.of("spaceProperty", key)
        node.add(Nd.of("spacePropertyDetail", "Namespace").val(Pc.orNa(entry.get("namespace"))))
        node.add(Nd.of("spacePropertyDetail", "Stored in").val(stores.join(", ")))
        if (stores.size() > 1) {
            node.note("The same key is held in more than one store. That is expected while the " +
                "plugin settings still delegate to Bandana and is listed once rather than twice.")
        }
        Nd value = Nd.of("spacePropertyDetail", "Value")
        if (!withValues) {
            value.absent("Not read. Pass values=true to read property values.")
        } else if (Pc.sensitive(key)) {
            /* Neither absent nor unreadable. The value is there, the read would
             * have worked, and this report is choosing not to print it. */
            value.redacted("Withheld. The key name matches this report's deny-list, so the value " +
                "was not printed.")
        } else {
            String text = Pc.text(entry.get("value"))
            value.val(text == null ? "stored, but the value is empty or could not be rendered" : text)
        }
        node.add(value)
        return node
    }

    /* A type name and a bounded toString, never the object. A value that fails to
     * render says so rather than taking the section down with it. */
    static String describe(Object value) {
        if (value == null) {
            return null
        }
        String type
        try {
            type = value.getClass().getName()
        } catch (Throwable ignored) {
            type = "unknown type"
        }
        String text
        try {
            text = String.valueOf(value)
        } catch (Throwable error) {
            return type + " - the value could not be rendered: " + Db.why(error)
        }
        if (text != null && text.length() > VALUE_CLAMP) {
            text = text.substring(0, VALUE_CLAMP) + " [clamped from " +
                String.valueOf(text.length()) + " characters]"
        }
        return type + " = " + text
    }

    /* ---- 4. Look and feel ------------------------------------------------ */

    /* ColourSchemeManager is NOT on the list of components measured acquirable on
     * this instance, so it is obtained by name and every call goes through the
     * invoker. If the component does not come back, the two nodes that depend on
     * it say so instead of reporting a space that overrides nothing. */
    static final String COLOUR_SCHEME_MANAGER = "com.atlassian.confluence.themes.ColourSchemeManager"

    Nd lookAndFeel(ThemeManager themes, Object space) {
        Nd node = Nd.of("spaceLookAndFeel", "Look and feel")
        node.link(links.spaceTheme(spaceKey), "Space tools > Look and feel > Themes.")

        Nd spaceTheme = Nd.of("spaceLookAndFeelItem", "Space theme")
        Nd globalTheme = Nd.of("spaceLookAndFeelItem", "Global theme")
        node.add(spaceTheme).add(globalTheme)
        guard(spaceTheme) {
            if (themes == null) {
                spaceTheme.failed("ThemeManager was not available.")
                return
            }
            String key = Pc.text(themes.getSpaceThemeKey(spaceKey))
            if (key == null) {
                /* Reported as measured, not interpreted. That an absent space
                 * theme key means the space follows the global theme is the
                 * obvious reading and was NOT verified on the instance, so the
                 * two keys are printed and the reader compares them. */
                spaceTheme.absent("No space-level theme key is stored. Compare with the global " +
                    "theme below; that an absent key means the space follows the global theme is " +
                    "the documented behaviour and was not verified on this instance.")
            } else {
                spaceTheme.val(themeName(themes, key)).ident(key)
            }
        }
        guard(globalTheme) {
            if (themes == null) {
                globalTheme.failed("ThemeManager was not available.")
                return
            }
            String key = Pc.text(themes.getGlobalThemeKey())
            if (key == null) {
                globalTheme.absent("No global theme key is stored, so the instance uses the default theme.")
            } else {
                globalTheme.val(themeName(themes, key)).ident(key)
            }
        }

        Object colours = null
        String colourFailure = null
        try {
            colours = ComponentLocator.getComponent(Class.forName(COLOUR_SCHEME_MANAGER))
            if (colours == null) {
                colourFailure = "ColourSchemeManager resolved but no component was returned, so the " +
                    "colour scheme of this space was not read. This is a failed acquisition, not a " +
                    "space that overrides nothing."
            }
        } catch (Throwable error) {
            colourFailure = "ColourSchemeManager could not be obtained: " + Db.why(error)
        }
        /* Both colour reads take the Space, so a missing Space object is a failed
         * read of the colour scheme and not a space that overrides nothing.
         * Without this the reflective call would go out with no argument at all,
         * miss the method, come back null, and the section would report an
         * absence it never measured. */
        if (colourFailure == null && space == null) {
            colourFailure = "The Space object was not available, so the colour scheme of this " +
                "space was not read."
        }

        Nd setting = Nd.of("spaceLookAndFeelItem", "Colour scheme setting")
        setting.link(links.spaceColourScheme(spaceKey), "Space tools > Look and feel > Colour scheme.")
        node.add(setting)
        if (colourFailure != null) {
            setting.failed(colourFailure)
        } else {
            guard(setting) {
                /* getColourSchemeSetting(Space) is what says WHICH mode is
                 * selected, which is the question a configuration report asks.
                 * The colour values themselves are presentation, not
                 * configuration, and are deliberately not enumerated here. */
                String mode = Pc.text(Pc.duck(colours, "getColourSchemeSetting", space))
                if (mode == null) {
                    setting.absent("No colour scheme setting is stored for this space.")
                } else {
                    setting.val(mode)
                }
            }
        }

        Nd override = Nd.of("spaceLookAndFeelItem", "Space colour scheme override")
        override.link(links.spaceColourScheme(spaceKey), "Space tools > Look and feel > Colour scheme.")
        node.add(override)
        if (colourFailure != null) {
            override.failed(colourFailure)
        } else {
            guard(override) {
                /* The isolated read is the space's OWN values, without the global
                 * scheme merged into it. The merged read would answer "what
                 * colour is this" and would make every space look like it
                 * overrides the global scheme. */
                Object isolated = Pc.duck(colours, "getSpaceColourSchemeIsolated", spaceKey)
                override.val(isolated == null
                    ? "No. This space inherits the global colour scheme."
                    : "Yes. This space stores colour values of its own.")
                if (isolated == null) {
                    override.state = Pc.ABSENT
                }
            }
        }
        return node
    }

    /* The theme key turned into the name an administrator sees, using the
     * catalogue the ThemeManager itself exposes. A key that matches no descriptor
     * is printed as itself: a theme whose app was uninstalled leaves the key
     * behind, and that is worth seeing rather than hiding. */
    private static String themeName(ThemeManager themes, String key) {
        try {
            Object descriptors = themes.getAvailableThemeDescriptors()
            if (descriptors instanceof Iterable) {
                for (Object descriptor : (Iterable) descriptors) {
                    String complete = Pc.firstText(descriptor, ["getCompleteKey"])
                    if (complete != null && complete.equals(key)) {
                        String name = Pc.firstText(descriptor, ["getName", "getKey"])
                        return name == null ? key : name + " (" + key + ")"
                    }
                }
            }
        } catch (Throwable ignored) {
            /* The catalogue is a convenience. Losing it costs the display name and
             * nothing else, and the key below is the real answer either way. */
        }
        return key
    }

    /* ---- 5. Templates ---------------------------------------------------- */

    /* MEASURED, and the reason this section does not call the obvious method:
     * javap -c of DefaultPageTemplateManager on 10.2.14 shows getPageTemplates(Space)
     * building a new list, adding space.getPageTemplates() and then adding
     * getGlobalPageTemplates() to the same list. It returns the space templates
     * PLUS EVERY GLOBAL TEMPLATE, with nothing on the returned objects to say
     * which is which except isGlobalPageTemplate(). On an instance with a large
     * global template set that produces a phantom list under every space.
     *
     * So the two lists are read from their own sources and reported apart:
     * Space.getPageTemplates() is the space's own bag, mapped one-to-many on
     * SPACEID in Space.hbm.xml, and getGlobalPageTemplates() is the global set. */
    Nd templates(PageTemplateManager templates, Object space) {
        Nd node = Nd.of("spaceTemplates", "Templates")
        node.link(links.spaceTemplates(spaceKey), "Space tools > Content tools > Templates.")
        node.note("Space templates and global templates are listed apart. On Confluence 10.2.14 " +
            "PageTemplateManager.getPageTemplates(Space) returns the space templates plus every " +
            "global template in one list - measured in the bytecode - so it is not used here.")

        Nd own = Nd.of("spaceTemplateGroup", "Templates of this space")
        node.add(own)
        guard(own) {
            if (space == null) {
                own.failed("The Space object was not available, so the templates of this space were " +
                    "not read.")
                return
            }
            Object list = Pc.duck(space, "getPageTemplates", null)
            if (!(list instanceof Collection)) {
                own.failed("Space.getPageTemplates() did not return a collection on this instance.")
                return
            }
            int count = fill(own, (Collection) list, "spaceTemplate")
            if (count == 0) {
                own.absent("This space has no templates of its own.")
            } else {
                own.val(Pc.plural(count, "template"))
            }
        }

        Nd global = Nd.of("spaceTemplateGroup", "Global templates available in this space")
        node.add(global)
        guard(global) {
            if (templates == null) {
                global.failed("PageTemplateManager was not available.")
                return
            }
            Collection list = templates.getGlobalPageTemplates()
            if (list == null) {
                global.failed("getGlobalPageTemplates() returned nothing at all, which is not the " +
                    "same as an instance without global templates.")
                return
            }
            int count = fill(global, list, "globalTemplate")
            if (count == 0) {
                global.absent("This instance has no global templates.")
            } else {
                global.val(Pc.plural(count, "template") + ", available in every space")
            }
        }
        return node
    }

    private static int fill(Nd parent, Collection list, String kind) {
        int count = 0
        for (Object template : list) {
            if (count >= TEMPLATE_CAP) {
                parent.cappedAt(TEMPLATE_CAP, "templates")
                break
            }
            count++
            String name = Pc.firstText(template, ["getName", "getTitle"])
            Nd node = Nd.of(kind, name == null ? "Template without a name" : name)
            node.ident(Pc.firstText(template, ["getId"]))
            String description = Pc.firstText(template, ["getDescription"])
            if (description != null) {
                node.val(description)
            }
            /* A template an app contributed is not maintained on the templates
             * screen and editing it there does not survive a reinstall of the
             * app. Naming its plugin is the difference between a template an
             * administrator owns and one they only appear to. */
            String plugin = Pc.firstText(template, ["getPluginKey", "getReferencingPluginKey"])
            if (plugin != null) {
                node.add(Nd.of(kind + "Detail", "Provided by the app").val(plugin))
            }
            parent.add(node)
        }
        return count
    }

    /* ---- 6. Space categories --------------------------------------------- */

    Nd categories(SpaceLabelManager labels, Object space) {
        Nd node = Nd.of("spaceCategories", "Space categories")
        node.link(links.spaceCategories(spaceKey), "Space tools > Overview > Space categories.")
        node.note("Space categories are labels on the space itself, which is why the screen that " +
            "maintains them is called Edit space labels.")
        return guard(node) {
            if (labels == null) {
                node.failed("SpaceLabelManager was not available.")
                return
            }
            if (space == null) {
                node.failed("The Space object was not available, so the categories of this space " +
                    "were not read.")
                return
            }
            Object list = Pc.duck(labels, "getLabelsOnSpace", space)
            if (!(list instanceof Collection)) {
                node.failed("getLabelsOnSpace did not return a collection on this instance.")
                return
            }
            int count = 0
            for (Object label : (Collection) list) {
                if (count >= CATEGORY_CAP) {
                    node.cappedAt(CATEGORY_CAP, "categories")
                    break
                }
                count++
                String name = Pc.firstText(label, ["getName", "getDisplayTitle"])
                Nd entry = Nd.of("spaceCategory", name == null ? "Category without a name" : name)
                entry.ident(Pc.firstText(label, ["getId"]))
                String namespace = Pc.firstText(label, ["getNamespace"])
                if (namespace != null) {
                    entry.val(namespace)
                }
                node.add(entry)
            }
            if (count == 0) {
                node.absent("No category is set on this space.")
            }
        }
    }
}

/* =============================================================================
 * REST Endpoint
 *
 * The two-argument closure form is the one MEASURED on this instance: the
 * reachability probe that preceded this file runs as a GET with (queryParams,
 * body) on groovyrunner 10.16.0 and Confluence 10.2.14. The body is unused and
 * unread here.
 * ========================================================================== */

spaceConfig(
    httpMethod: "GET",
    groups: ["confluence-administrators"]
) { queryParams, body ->

    long started = System.currentTimeMillis()

    /* ---- JAX-RS Response, resolved at runtime (javax / jakarta neutral) --- */

    Class responseClass = Http.resolveResponseClass()

    /* ---- Parameters ------------------------------------------------------ */

    String spaceKey = Pc.stringParam(queryParams, "space", null)
    String format = Pc.stringParam(queryParams, "format", "html").toLowerCase(Locale.ROOT)
    String depth = Pc.stringParam(queryParams, "depth", "collapsed").toLowerCase(Locale.ROOT)
    boolean withValues = Pc.booleanParam(queryParams, "values", false)
    boolean expandAll = depth == "full"

    Map<String, Object> activeParams = [
        space: spaceKey,
        format: format == "html" ? null : format,
        depth: expandAll ? "full" : null,
        values: withValues ? "true" : null
    ] as LinkedHashMap

    /* ---- Instance identity ----------------------------------------------- */

    Report report = new Report()

    /* SAL's ApplicationProperties answers the address, the version and the
     * display name in one component. It is not on the list of types measured
     * acquirable on this instance, so it is obtained by name and read through the
     * invoker, with the Confluence settings as the fallback for the two values it
     * shares with them. */
    try {
        Object applicationProperties =
            ComponentLocator.getComponent(Class.forName("com.atlassian.sal.api.ApplicationProperties"))
        if (applicationProperties != null) {
            report.instanceBaseUrl = Pc.firstText(applicationProperties, ["getBaseUrl"])
            report.instanceTitle = Pc.firstText(applicationProperties, ["getDisplayName"])
            report.confluenceVersion = Pc.firstText(applicationProperties, ["getVersion"])
        }
    } catch (Throwable error) {
        report.globalDiagnostics.add("The application properties could not be read: " + Db.why(error))
    }

    if (report.instanceBaseUrl == null || report.instanceTitle == null) {
        try {
            Object settings = ComponentLocator.getComponent(
                Class.forName("com.atlassian.confluence.setup.settings.SettingsManager"))
            Object global = Pc.duck(settings, "getGlobalSettings", null)
            if (report.instanceBaseUrl == null) {
                report.instanceBaseUrl = Pc.firstText(global, ["getBaseUrl"])
            }
            if (report.instanceTitle == null) {
                report.instanceTitle = Pc.firstText(global, ["getSiteTitle"])
            }
        } catch (Throwable error) {
            report.globalDiagnostics.add("The global settings could not be read: " + Db.why(error))
        }
    }

    /* Without a base URL there is no deep link to build. Dl returns null for every
     * shape, every node falls back to its navigation note, and the report says
     * what happened rather than emitting relative addresses that work only from
     * one page. */
    if (Pc.text(report.instanceBaseUrl) == null) {
        report.globalDiagnostics.add("The base URL of this instance could not be read, so no deep " +
            "link could be built. Every item below states its navigation path in words instead.")
    }
    Dl links = new Dl(report.instanceBaseUrl)

    /* ---- The read-only database executor ---------------------------------- */

    Map<String, Object> executor = Db.factory()
    Object executorFactory = executor.get("factory")
    String executorFailure = Pc.text(executor.get("failure"))

    /* ---- No space named: render the picker -------------------------------- */

    if (spaceKey == null) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>()
        int total = 0
        if (executorFactory == null) {
            /* An empty space list and a failed read must not look alike. The
             * picker says which of the two happened. */
            report.globalDiagnostics.add("The space list could not be read: " + executorFailure)
        } else {
            try {
                Db.withConnection(executorFactory) { Connection connection ->
                    Map<String, Object> shape = Db.shape(connection, "spaces",
                        ["spacekey", "spacename", "spacetype", "spacestatus"])
                    String problem = Db.shapeProblem(shape)
                    if (problem != null) {
                        report.globalDiagnostics.add("The space list could not be read. " + problem)
                        return null
                    }
                    Map<String, Object> counted = Db.query(connection,
                        "SELECT COUNT(*) AS total FROM spaces", [], ["total"], 1)
                    List<Map<String, String>> countRows =
                        (List<Map<String, String>>) counted.get("rows")
                    if (!countRows.isEmpty()) {
                        try {
                            total = Integer.parseInt(Pc.orNa(countRows.get(0).get("total")))
                        } catch (NumberFormatException ignored) {
                            total = 0
                        }
                    }
                    Map<String, Object> listed = Db.query(connection,
                        "SELECT s.spacekey, s.spacename, s.spacetype, s.spacestatus FROM spaces s " +
                        "ORDER BY s.spacename, s.spacekey",
                        [], ["spacekey", "spacename", "spacetype", "spacestatus"], Scan.PICKER_CAP)
                    String failure = Pc.text(listed.get("failure"))
                    if (failure != null) {
                        report.globalDiagnostics.add("The space list could not be read. " + failure)
                        return null
                    }
                    for (Map<String, String> row : (List<Map<String, String>>) listed.get("rows")) {
                        Map<String, String> entry = new LinkedHashMap<String, String>()
                        entry.put("key", row.get("spacekey"))
                        entry.put("name", row.get("spacename"))
                        entry.put("type", row.get("spacetype"))
                        entry.put("status", row.get("spacestatus"))
                        rows.add(entry)
                    }
                    return null
                }
            } catch (Throwable error) {
                report.globalDiagnostics.add("The space list could not be read: " + Db.why(error) +
                    ". This is a failed read, not an instance without spaces.")
            }
        }
        if (total < rows.size()) {
            total = rows.size()
        }
        report.executionMs = System.currentTimeMillis() - started
        String page = Render.picker(report, rows, "", total)
        return Http.ok(responseClass, page, Http.HTML)
    }

    /* ---- Components ------------------------------------------------------- */

    /* Each one is acquired on its own. A component that is missing costs the
     * sections that need it and nothing else, and the report says which. */
    SpaceManager spaceManager = null
    BandanaManager bandanaManager = null
    PageTemplateManager pageTemplateManager = null
    ThemeManager themeManager = null
    SpaceLabelManager spaceLabelManager = null

    try {
        spaceManager = ComponentLocator.getComponent(SpaceManager.class)
    } catch (Throwable error) {
        report.globalDiagnostics.add("SpaceManager could not be obtained: " + Db.why(error))
    }
    try {
        bandanaManager = ComponentLocator.getComponent(BandanaManager.class)
    } catch (Throwable error) {
        report.globalDiagnostics.add("BandanaManager could not be obtained: " + Db.why(error))
    }
    try {
        pageTemplateManager = ComponentLocator.getComponent(PageTemplateManager.class)
    } catch (Throwable error) {
        report.globalDiagnostics.add("PageTemplateManager could not be obtained: " + Db.why(error))
    }
    try {
        themeManager = ComponentLocator.getComponent(ThemeManager.class)
    } catch (Throwable error) {
        report.globalDiagnostics.add("ThemeManager could not be obtained: " + Db.why(error))
    }
    try {
        spaceLabelManager = ComponentLocator.getComponent(SpaceLabelManager.class)
    } catch (Throwable error) {
        report.globalDiagnostics.add("SpaceLabelManager could not be obtained: " + Db.why(error))
    }

    Object space = null
    try {
        space = spaceManager == null ? null : spaceManager.getSpace(spaceKey)
    } catch (Throwable error) {
        report.globalDiagnostics.add("The space object could not be read: " + Db.why(error))
    }

    /* ---- The three database-backed sections, in one read-only connection --- */

    Scan scan = new Scan(spaceKey, links)
    Map<String, Object> fromSql = new LinkedHashMap<String, Object>()

    if (executorFactory == null) {
        report.globalDiagnostics.add(executorFailure)
    } else {
        try {
            Db.withConnection(executorFactory) { Connection connection ->
                Map<String, Object> found = Scan.spaceRow(connection, spaceKey)
                Map<String, String> row = (Map<String, String>) found.get("row")
                String failure = Pc.text(found.get("failure"))
                fromSql.put("row", row)
                fromSql.put("rowFailure", failure)
                fromSql.put("details", scan.details(row, failure))
                fromSql.put("permissions",
                    scan.permissions(connection, row == null ? null : row.get("spaceid")))
                fromSql.put("properties", scan.properties(connection, bandanaManager, withValues))
                return null
            }
        } catch (Throwable error) {
            /* The connection itself failed. The three sections are still built, in
             * the state that says so - not left out, which would read as three
             * sections nobody configured. */
            report.globalDiagnostics.add("The read-only database connection failed: " + Db.why(error))
        }
    }

    /* ---- Identity, from the database first and the object second ----------- */

    Map<String, String> spaceRow = (Map<String, String>) fromSql.get("row")
    if (spaceRow != null) {
        report.spaceKey = spaceRow.get("spacekey")
        report.spaceName = spaceRow.get("spacename")
        report.spaceId = spaceRow.get("spaceid")
        report.spaceType = spaceRow.get("spacetype")
    } else if (space != null) {
        report.spaceKey = Pc.firstText(space, ["getKey"])
        report.spaceName = Pc.firstText(space, ["getName"])
        report.spaceId = Pc.firstText(space, ["getId"])
    }
    if (Pc.text(report.spaceKey) == null) {
        report.spaceKey = spaceKey
    }

    /* Neither route found the space. Whether that means it does not exist or that
     * nothing could be read is a real difference and the answer says which. */
    if (spaceRow == null && space == null) {
        boolean readFailed = Pc.text(fromSql.get("rowFailure")) != null || executorFactory == null
        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.FALSE)
        payload.put("error", readFailed
            ? "The space " + spaceKey + " could not be read. This is a failed read: whether the " +
              "space exists is UNKNOWN, and it is not reported as missing."
            : "No space with key " + spaceKey + " exists on this instance. Both the database and " +
              "the space manager were read successfully and neither holds it.")
        payload.put("diagnostics", report.globalDiagnostics)
        payload.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.build(responseClass, readFailed ? 500 : 404,
            JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Http.JSON, null)
    }

    /* ---- The sections, in reading order ------------------------------------ */

    report.sections.add(fromSql.get("details") == null
        ? scan.details(null, executorFailure == null
            ? "The database connection failed before the space details were read."
            : executorFailure)
        : (Nd) fromSql.get("details"))

    report.sections.add(fromSql.get("permissions") == null
        ? scan.permissions(null, null)
        : (Nd) fromSql.get("permissions"))

    report.sections.add(fromSql.get("properties") == null
        ? scan.properties(null, bandanaManager, withValues)
        : (Nd) fromSql.get("properties"))

    report.sections.add(scan.lookAndFeel(themeManager, space))
    report.sections.add(scan.templates(pageTemplateManager, space))
    report.sections.add(scan.categories(spaceLabelManager, space))

    report.executionMs = System.currentTimeMillis() - started

    /* ---- Emit --------------------------------------------------------------- */

    if (format == "json") {
        return Http.ok(responseClass, Render.json(report), Http.JSON)
    }
    if (format == "csv") {
        Map<String, String> headers = new LinkedHashMap<String, String>()
        headers.put("Content-Disposition",
            "attachment; filename=\"space-config-" + Pc.urlPath(report.spaceKey) + ".csv\"")
        return Http.build(responseClass, 200, Render.csv(report), Http.CSV, headers)
    }
    return Http.ok(responseClass, Render.html(report, activeParams, expandAll), Http.HTML)
}
