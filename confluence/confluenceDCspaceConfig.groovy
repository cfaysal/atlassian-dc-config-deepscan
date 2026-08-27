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
 *
 * Confluence page export (POST on the same endpoint URL)
 *   The report writes itself into a page of THIS instance. This endpoint runs
 *   inside Confluence, so it writes through the local page APIs and makes NO
 *   outbound call at all, in any format. There is no cross-instance export here
 *   and there must not be one.
 *
 *   Three stages, one POST each, and none of them runs before the export button
 *   is pressed: pick a space, pick or name a parent page, write. Rendering the
 *   report itself performs no lookup.
 *
 *   The Remark column belongs to the administrator. It is read back from the
 *   existing page and carried over verbatim. If that read fails for ANY reason
 *   nothing is written at all and the answer is 409, because a remark that
 *   cannot be read is a remark that must not be overwritten.
 *
 *   A page that does not carry this export's marker is never overwritten,
 *   whatever its title. The marker and the title prefix differ from the Jira
 *   sibling's on purpose: both tools can write into the same space, and the
 *   remark parser scans every table on the page it is about to replace.
 *
 *   The write is read back. What the answer reports - page id, version, position
 *   - is what the stored page carries afterwards, never what the save claimed.
 *   Where the read-back itself fails the verdict is "unknown", never a claimed
 *   or a denied write.
 *
 *   Property VALUES reach the page only when the run that produced the payload
 *   was given values=true, and a key on the deny-list is withheld a second time
 *   on the way in. The page is readable by everyone who can read the space, so
 *   this gate is enforced in the export itself and not only in the report.
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

/* The export write path. Every type below is named statically because the
 * Confluence sibling of this endpoint - the App Footprint report - runs this
 * exact set on this instance line, so they are measured rather than assumed. */
import com.atlassian.confluence.api.model.Expansion
import com.atlassian.confluence.api.model.content.Space as ApiSpace
import com.atlassian.confluence.api.model.content.SpaceStatus as ApiSpaceStatus
import com.atlassian.confluence.api.model.pagination.PageResponse
import com.atlassian.confluence.api.model.pagination.SimplePageRequest
import com.atlassian.confluence.api.service.content.SpaceService as ApiSpaceService
import com.atlassian.confluence.api.service.content.SpaceService.SpaceFinder
import com.atlassian.confluence.content.service.PageService
import com.atlassian.confluence.content.service.SpaceService
import com.atlassian.confluence.core.BodyContent
import com.atlassian.confluence.core.BodyType
import com.atlassian.confluence.core.DefaultSaveContext
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.confluence.search.service.ContentTypeEnum
import com.atlassian.confluence.search.v2.BooleanOperator
import com.atlassian.confluence.search.v2.Index
import com.atlassian.confluence.search.v2.SearchFieldMappings
import com.atlassian.confluence.search.v2.SearchManager
import com.atlassian.confluence.search.v2.SearchQuery
import com.atlassian.confluence.search.v2.query.BooleanQuery
import com.atlassian.confluence.search.v2.query.ContentTypeQuery
import com.atlassian.confluence.search.v2.query.InSpaceQuery
import com.atlassian.confluence.search.v2.query.TextFieldQuery
import com.atlassian.confluence.search.v2.query.WildcardTextFieldQuery
import com.atlassian.confluence.spaces.Space
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal

import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
        /* The values flag is the one report parameter the export has to know: a run
         * that was not allowed to read a property value and a space whose properties
         * are empty produce the same tree. It is read back out of the active
         * parameters rather than passed separately, so it can never disagree with
         * what the toolbar above shows. */
        out.append(exportCard(report, activeParams != null && activeParams.get("values") != null))
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

    /* The export is staged behind its own button on purpose. Rendering the report
     * reads nothing: the click lists the spaces of this instance, choosing a space
     * opens the parent search, and only then can a page be written. Each stage is
     * one POST to this same endpoint, and none of them leaves this instance -
     * there is no cross-instance path in this file.
     *
     * withValues is passed in rather than read out of the payload. A run that was
     * not allowed to read a property value and a space whose properties are empty
     * produce the same tree, and the export gate has to tell those two apart. */
    static String exportCard(Report report, boolean withValues) {
        Map<String, Object> model = report.toMap()
        Map<String, Object> options = new LinkedHashMap<String, Object>()
        options.put("values", Boolean.valueOf(withValues))
        model.put("options", options)
        String payload = Pc.html(JsonOutput.toJson(model))
        String defaultTitle = Pc.html(Cx.title(report.spaceKey))
        return """<div class="export-card">
    <div class="export-title">Export to a Confluence page</div>
    <div class="export-note">
        Writes this configuration report into a page of this instance and updates that same page on
        every later run. The <strong>Remark</strong> column stays untouched: it is read back from the
        existing page and carried over verbatim. If that read fails, nothing is written at all. A remark
        whose configuration item has disappeared is kept in a second table rather than dropped. A page
        that does not carry this export's marker is never overwritten. Nothing is read until the button
        below is pressed, and nothing is sent anywhere outside this instance.
    </div>
    <div class="export-grid">
        <button id="exportOpen" class="button" type="button" onclick="openExport()">Export to a page</button>
    </div>
    <div id="exportSettings" class="export-settings hidden">
        <div id="exportSpaceStage">
            <div class="export-grid">
                <label class="export-field">Space - search by name or key
                    <input id="exportSpaceQuery" class="wide" type="search" autocomplete="off"
                           placeholder="Type at least ${Cx.MIN_SEARCH_CHARS} characters..." oninput="searchSpaces()"
                           onkeydown="pickFirstHit(event, 'exportSpaceResults')">
                </label>
                <div class="export-chosen" id="exportSpaceChosen">No space selected.</div>
            </div>
            <div id="exportSpaceResults" class="export-results"></div>
        </div>
        <div id="exportPageStage" class="export-stage hidden">
            <div class="export-grid">
                <label class="export-field">Parent page - search by title (optional)
                    <input id="exportParentQuery" class="wide" type="search" autocomplete="off"
                           placeholder="Type at least ${Cx.MIN_SEARCH_CHARS} characters..." oninput="parentTyped()"
                           onkeydown="pickFirstHit(event, 'exportParentResults')">
                </label>
                <div class="export-chosen" id="exportParentChosen">No parent page: the page is created at the top level of the space.</div>
            </div>
            <div id="exportParentResults" class="export-results"></div>
            <div class="export-grid">
                <label class="export-field">Page title
                    <input id="exportTitle" class="wide" type="text" value="${defaultTitle}">
                </label>
                <button id="exportRun" class="button" type="button" onclick="exportToPage()">Generate the page</button>
            </div>
        </div>
    </div>
    <div id="exportStatus" class="export-status muted">Not written yet.</div>
    <input id="exportPayload" type="hidden" value="${payload}">
    <input id="exportSpace" type="hidden" value="">
    <input id="exportParent" type="hidden" value="">
</div>
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
.export-settings { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border-subtle); }
.export-stage { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--border-subtle); }
.export-status { margin-top: 10px; font-size: 12px; }
.export-status.good { color: var(--green); }
.export-status.warn { color: var(--yellow); }
.export-status.bad { color: var(--red); }
.export-card button.button { height: 34px; }
.export-card button.button[disabled] { opacity: .55; cursor: not-allowed; }
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

/* The export is staged: nothing above ran a lookup, so every stage below asks the
   POST branch of this same endpoint for exactly what it needs, and no further.
   Three stages, not four: this endpoint writes into its own instance, so there is
   no target to pick and no request ever leaves this Confluence. */
var exportSpaceList = [];
var exportPageSeq = 0;
var exportPageTimer = null;

function el(id) { return document.getElementById(id); }
function say(cssClass, text) {
    var node = el('exportStatus');
    node.className = 'export-status ' + cssClass;
    node.textContent = text;
    return node;
}
function exportPost(payload) {
    return fetch(window.location.pathname, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
        body: JSON.stringify(payload)
    }).then(function (response) {
        return response.json().then(function (parsed) { return { ok: response.ok, status: response.status, body: parsed }; });
    });
}
function hit(label, onPick) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'export-hit';
    button.textContent = label;
    button.onclick = onPick;
    return button;
}
function emptyNote(text) {
    var note = document.createElement('div');
    note.className = 'export-empty';
    note.textContent = text;
    return note;
}
/* Enter picks the first hit. The results are buttons in document order, so the
   first one in the box is the first match. Enter with no hit does nothing, the
   default is always suppressed so Enter can never submit or reload the page, and
   picking a hit with the mouse keeps working unchanged. */
function pickFirstHit(event, boxId) {
    if (event.key !== 'Enter') { return; }
    event.preventDefault();
    var first = el(boxId).querySelector('.export-hit');
    if (first) { first.click(); }
}

/* Stage 1. The first lookup of the whole report: the spaces of this instance. */
function openExport() {
    el('exportOpen').disabled = true;
    el('exportSettings').classList.remove('hidden');
    say('muted', 'Reading the spaces of this instance...');
    exportPost({ action: 'spaces' }).then(function (result) {
        var body = result.body || {};
        if (!result.ok || body.ok !== true) {
            el('exportSettings').classList.add('hidden');
            el('exportOpen').disabled = false;
            say('bad', body.error || 'The space list could not be read.');
            return;
        }
        exportSpaceList = body.spaces || [];
        say('muted', String(exportSpaceList.length) + ' current space(s). Type at least ' +
            '${Cx.MIN_SEARCH_CHARS} characters to search by name or key.');
    }).catch(function (error) {
        el('exportSettings').classList.add('hidden');
        el('exportOpen').disabled = false;
        say('bad', 'The space list could not be read: ' + error);
    });
}

/* Stage 2. Search, not a dropdown: only matches are ever put into the page. */
function searchSpaces() {
    var query = el('exportSpaceQuery').value.trim().toLowerCase();
    var box = el('exportSpaceResults');
    box.innerHTML = '';
    if (query.length < ${Cx.MIN_SEARCH_CHARS}) { return; }
    var shown = 0;
    for (var i = 0; i < exportSpaceList.length && shown < ${Cx.SEARCH_LIMIT}; i++) {
        var space = exportSpaceList[i];
        if (space.name.toLowerCase().indexOf(query) < 0 && space.key.toLowerCase().indexOf(query) < 0) { continue; }
        box.appendChild(hit(space.name + '  (' + space.key + ')', chooseSpace(space)));
        shown++;
    }
    if (shown === 0) { box.appendChild(emptyNote('No current space matches "' + query + '".')); }
}
function chooseSpace(space) {
    return function () {
        el('exportSpace').value = space.key;
        el('exportSpaceQuery').value = space.name;
        el('exportSpaceResults').innerHTML = '';
        el('exportSpaceChosen').textContent = 'Space: ' + space.name + ' (' + space.key + ')';
        /* A parent search that is still running belongs to the previous space, so
           it is discarded here as well - otherwise its answer would drop a list of
           foreign pages into the field of the space just picked. */
        if (exportPageTimer) { window.clearTimeout(exportPageTimer); }
        exportPageSeq++;
        el('exportParent').value = '';
        el('exportParentQuery').value = '';
        el('exportParentResults').innerHTML = '';
        el('exportParentChosen').textContent = 'No parent page: the page is created at the top level of the space.';
        el('exportPageStage').classList.remove('hidden');
        say('muted', 'Space ' + space.key + ' selected. Pick a parent page or leave it empty, then generate.');
    };
}

/* Stage 3. The parent field has no button: typing is what starts the search,
   after a short idle pause rather than on every keystroke. The list that comes
   back STAYS until an entry is picked or the field falls below the minimum - a
   list that disappears while it is being read cannot confirm anything. Out-of-order
   answers are dropped, so a slow answer to an older term never replaces the list
   of the current one. */
function parentTyped() {
    /* Editing the term drops the picked parent, so a stale id can never travel
       with a title the administrator has since changed. What travels then is the
       title, and the generating run adopts or creates that page. */
    el('exportParent').value = '';
    var query = el('exportParentQuery').value.trim();
    el('exportParentChosen').textContent = query
        ? 'Parent page "' + query + '": pick it below if it is listed, otherwise it is created when the page is generated.'
        : 'No parent page: the page is created at the top level of the space.';
    if (exportPageTimer) { window.clearTimeout(exportPageTimer); }
    if (query.length < ${Cx.MIN_SEARCH_CHARS}) {
        /* Bumping the sequence here discards an answer that is still in flight,
           so an empty field never fills back up on its own. */
        exportPageSeq++;
        el('exportParentResults').innerHTML = '';
        return;
    }
    exportPageTimer = window.setTimeout(searchParents, ${Cx.SEARCH_IDLE_MS});
}
function searchParents() {
    var query = el('exportParentQuery').value.trim();
    var box = el('exportParentResults');
    if (query.length < ${Cx.MIN_SEARCH_CHARS}) { box.innerHTML = ''; return; }
    var seq = ++exportPageSeq;
    exportPost({ action: 'pages', spaceKey: el('exportSpace').value, query: query }).then(function (result) {
        if (seq !== exportPageSeq) { return; }
        var body = result.body || {};
        box.innerHTML = '';
        if (!result.ok || body.ok !== true) {
            box.appendChild(emptyNote(body.error || 'The page search failed.'));
            return;
        }
        var pages = body.pages || [];
        if (pages.length === 0) {
            box.appendChild(emptyNote('Not found - will be created'));
            return;
        }
        for (var i = 0; i < pages.length; i++) {
            box.appendChild(hit(pages[i].title + '  #' + pages[i].id, chooseParent(pages[i])));
        }
        if (body.truncated === true) {
            box.appendChild(emptyNote('More pages match than are listed here. Type more of the title to narrow it down.'));
        }
    }).catch(function (error) {
        if (seq === exportPageSeq) {
            box.innerHTML = '';
            box.appendChild(emptyNote('The page search failed: ' + error));
        }
    });
}
function chooseParent(page) {
    return function () {
        el('exportParent').value = page.id;
        el('exportParentQuery').value = page.title;
        el('exportParentResults').innerHTML = '';
        el('exportParentChosen').textContent = 'Parent page: ' + page.title + ' (id ' + page.id + ')';
    };
}

/* Stage 4 of the interaction, stage 3 of the endpoint: the write. */
function exportToPage() {
    var button = el('exportRun');
    function fail(text) { say('bad', text); }
    var payload;
    try { payload = JSON.parse(el('exportPayload').value); }
    catch (error) { fail('Export payload could not be read: ' + error); return; }
    payload.spaceKey = el('exportSpace').value;
    /* Either the id of a page that was picked, or the title that was typed and
       never picked - never both. The server refuses a request that carries two
       parent instructions, so the choice is made here and only here. */
    payload.parentPageId = el('exportParent').value.trim();
    payload.parentTitle = payload.parentPageId ? '' : el('exportParentQuery').value.trim();
    payload.title = el('exportTitle').value.trim();
    if (!payload.spaceKey) { fail('Select a space first.'); return; }
    if (!payload.title) { fail('Enter a page title first.'); return; }
    button.disabled = true;
    say('muted', 'Writing page...');
    exportPost(payload).then(function (result) {
        button.disabled = false;
        var body = result.body || {};
        if (!result.ok || body.ok !== true) {
            fail('Nothing was written (' + result.status + '): ' + (body.error || 'unknown error'));
            return;
        }
        /* Found and created are reported apart. An administrator who reads "found"
           believes the parent was already there and stops looking for the page this
           run has just made. */
        var parent = '';
        if (body.parentAction === 'created') {
            parent = ' Parent page created: "' + body.parentTitle + '" (id ' + body.parentPageId + ').';
        } else if (body.parentAction === 'found') {
            parent = ' Parent page found: "' + body.parentTitle + '" (id ' + body.parentPageId + ').';
        }
        /* A parent that was named and not applied is said plainly, and the line
           stops reading as a plain success. A silent mismatch is the worst outcome
           here: the run looks like it worked and the report is not where it was
           put. The three states are compared as strings on purpose - "unknown" is
           not a failure and is never reported as one. */
        var tone = 'good';
        if (body.parentApplied === 'false') {
            tone = 'bad';
            parent += ' PARENT NOT APPLIED. ' +
                (body.parentAppliedReason || 'The page was not moved under the parent page.');
        } else if (body.parentApplied === 'unknown') {
            tone = 'warn';
            parent += ' PARENT NOT CONFIRMED. ' +
                (body.parentAppliedReason || 'The position could not be read back.');
        }
        var status = say(tone, 'Page ' + body.action + ': "' + body.title + '" in ' + body.spaceKey +
            ' (version ' + body.pageVersion + '). Remark read: ' + body.remarkRead +
            ', carried over: ' + body.remarksCarried + ' of ' + body.remarksRead +
            ', without a matching item: ' + body.orphanedRemarks + '.' + parent);
        if (body.pageUrl) {
            var link = document.createElement('a');
            link.href = body.pageUrl;
            link.target = '_blank';
            link.rel = 'noopener';
            link.textContent = ' Open the page';
            status.appendChild(link);
        }
    }).catch(function (error) {
        button.disabled = false;
        fail('Request failed, nothing was written: ' + error);
    });
}
</script>
"""
    }
}

/* =============================================================================
 * Confluence page export - storage format and remark carry-over
 *
 * The report can be written into a page of this instance and that same page is
 * updated on every later run. Two rules make that safe enough to point at a
 * production space:
 *
 *   A page is only ever updated when it carries the marker below. A page this
 *   export did not create is never rewritten, whatever its title. The marker and
 *   the title prefix differ from the Jira sibling's on purpose: both tools can
 *   write into the same space, and the parser below scans every table on the
 *   page it is about to replace.
 *
 *   The Remark column belongs to the administrator, not to this export. It is
 *   read back from the existing page and carried over verbatim, and if that read
 *   fails for any reason NOTHING is written. A remark that cannot be read is a
 *   remark that must not be overwritten.
 *
 * Everything here is a pure function of its input, so the whole export is
 * exercised by the offline suite without a page ever being written.
 * ========================================================================== */

class Cx {

    /* Bumping this string orphans every existing page, which is the point: a page
     * written by an older, differently shaped export is not silently adopted. */
    static final String MARKER = "cfcon-space-config-export/1"

    static final String COL_PATH = "Path"
    static final String COL_ITEM = "Item"
    static final String COL_VALUE = "Value"
    static final String COL_STATE = "State"
    static final String COL_LINK = "In Confluence"
    static final String COL_REMARK = "Remark"

    static final String DEFAULT_TITLE_PREFIX = "Confluence space configuration - "

    static final int MAX_PAYLOAD_CHARS = 4000000
    static final int MAX_TITLE_CHARS = 255

    /* A deep scan of a large space runs into five figures of rows: a permission
     * grid carries one row per grant plus one per provenance detail, and a space
     * can hold many grants. Past this cap the page stops being readable and starts
     * failing to save, so the table is cut - and the cut is stated on the page,
     * because a shortened table that looks complete is worse than no table. */
    static final int MAX_ROWS = 5000

    /* Search stages. The space search shows at most this many matches and ignores
     * a shorter term, so a single keystroke never renders the whole instance. */
    static final int SEARCH_LIMIT = 25
    static final int MIN_SEARCH_CHARS = 2

    /* Idle pause before a typed parent title is searched for. The field has no
     * button, so the search is what typing does - but not once per keystroke:
     * that is a call per character and a list rebuilt faster than it can be read. */
    static final int SEARCH_IDLE_MS = 300

    /* Upper bound on the words a title search is built from. Every token becomes
     * one clause of an AND query, and an unbounded clause count is an unbounded
     * query. Beyond this the extra words add nothing: the first few already cut
     * the result set down to what fits on the screen. */
    static final int MAX_TITLE_TOKENS = 8

    static String title(String spaceKey) {
        String key = spaceKey == null ? "" : spaceKey.trim()
        String candidate = DEFAULT_TITLE_PREFIX + (key.isEmpty() ? "unknown space" : key)
        return candidate.length() > MAX_TITLE_CHARS ? candidate.substring(0, MAX_TITLE_CHARS) : candidate
    }

    /* Whole words of a title search, in the order they were typed. Everything
     * that is neither a letter nor a digit separates, which is what a tokenised
     * title field does anyway, and which also means no character with a meaning
     * in the query language can survive into a term. Umlauts and every other
     * non-ASCII letter are letters and are kept. The caller appends the one
     * trailing star it wants; a term can therefore never start with one. */
    static List<String> titleTokens(String query) {
        List<String> tokens = new ArrayList<String>()
        if (query == null) {
            return tokens
        }
        StringBuilder current = new StringBuilder()
        for (int i = 0; i < query.length() && tokens.size() < MAX_TITLE_TOKENS; i++) {
            char character = query.charAt(i)
            if (Character.isLetterOrDigit(character)) {
                current.append(character)
                continue
            }
            if (current.length() > 0) {
                tokens.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.length() > 0 && tokens.size() < MAX_TITLE_TOKENS) {
            tokens.add(current.toString())
        }
        return tokens
    }

    /* A request carries either the id of a picked parent or the title of one to
     * be created, never both. The refusal text is a constant so the offline
     * suite can assert on the contract rather than on a copy of the sentence. */
    static final String PARENT_BOTH = "The request carries a parent page id and a parent page title at the same time. " +
        "Exactly one of them is expected: the id of a page that was picked, or the title of a page to create. " +
        "Nothing is written."

    static String parentProblem(String parentId, String parentTitle, String reportTitle) {
        String id = parentId == null ? "" : parentId.trim()
        String parent = parentTitle == null ? "" : parentTitle.trim()
        String report = reportTitle == null ? "" : reportTitle.trim()
        if (!id.isEmpty() && !parent.isEmpty()) {
            return PARENT_BOTH
        }
        if (parent.length() > MAX_TITLE_CHARS) {
            return "The parent page title exceeds " + String.valueOf(MAX_TITLE_CHARS) + " characters."
        }
        /* A page cannot be its own parent, and Confluence titles are unique per
         * space, so the two titles being equal has no outcome that works. Caught
         * here rather than halfway through: otherwise the container page is
         * created first and the report write then fails on the duplicate title,
         * leaving a page behind that nothing was ever filed under. */
        if (!parent.isEmpty() && parent.equalsIgnoreCase(report)) {
            return "The parent page and the report page carry the same title \"" + parent +
                "\". A page cannot be its own parent. Nothing is written."
        }
        return ""
    }

    /* ---- Parent position ---------------------------------------------------- */

    /* A parent named in THIS run - picked from the search or created from a typed
     * title - is an instruction and is carried out even when the report page
     * already exists. A run that names no parent does not touch the position, so
     * a page an administrator moved by hand stays moved. */
    static final String MOVE_REQUESTED = "move"
    static final String MOVE_NOT_REQUESTED = "not-requested"
    static final String MOVE_ALREADY_THERE = "already-there"

    /* Pure decision, no instance needed, so the offline suite checks the rule and
     * not a run that happened to behave. An unknown current position resolves to
     * "move": carrying out the instruction is the safe direction, and only a
     * positive match skips. The skip exists so an unchanged repeat run does not
     * rewrite the page into the position it already holds. */
    static String moveDecision(String requestedParentId, String currentParentId) {
        String requested = requestedParentId == null ? "" : requestedParentId.trim()
        if (requested.isEmpty()) {
            return MOVE_NOT_REQUESTED
        }
        String current = currentParentId == null ? "" : currentParentId.trim()
        if (!current.isEmpty() && current.equals(requested)) {
            return MOVE_ALREADY_THERE
        }
        return MOVE_REQUESTED
    }

    /* Three states and no fourth. They are strings rather than a JSON boolean with
     * a special case, because a browser that writes if (!body.parentApplied) reads
     * a mixed boolean-or-string field as a success - which is exactly the silent
     * mismatch this measurement exists to prevent. */
    static final String PARENT_APPLIED_TRUE = "true"
    static final String PARENT_APPLIED_FALSE = "false"
    static final String PARENT_APPLIED_UNKNOWN = "unknown"

    /* The direct parent out of an ancestor chain, kept apart from the case where
     * no chain arrived at all. Ancestors run from the root of the space downwards,
     * so the direct parent is the last entry that names an id.
     *
     * measured=true with a null parentId means the read answered and the page sits
     * at the top level of the space - a real measurement. measured=false means no
     * chain was readable, which measures nothing and must never be read as "the
     * page has no parent".
     *
     * It takes the ids rather than the pages, so the rule stays free of Confluence
     * types and the offline suite can check it. */
    static Map<String, Object> innermostAncestor(List<String> ancestorIds) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("measured", Boolean.FALSE)
        out.put("parentId", null)
        if (ancestorIds == null) {
            return out
        }
        out.put("measured", Boolean.TRUE)
        for (int i = ancestorIds.size() - 1; i >= 0; i--) {
            String id = ancestorIds.get(i)
            if (id != null && !id.trim().isEmpty()) {
                out.put("parentId", id.trim())
                return out
            }
        }
        return out
    }

    /* The verdict on the position, and it is a measurement or it is nothing.
     *
     * "true"    - the read-back answered and named the requested parent.
     * "false"   - the read-back answered and named something else, or nothing.
     * "unknown" - the read-back itself did not answer.
     *
     * A failed or empty read is never reported as a successful move, and never as
     * a failed one either: neither was measured, so neither is claimed. applied
     * stays null when this run named no parent, because then there is no question
     * to answer and the position was deliberately left alone.
     *
     * A move that returned without throwing is a report about itself and is
     * deliberately not an input here. moveError only sharpens the wording of a
     * verdict that was measured either way. */
    static Map<String, Object> parentOutcome(String requestedParentId, boolean readBackOk,
                                             String actualParentId, String moveError) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("applied", null)
        out.put("reason", null)

        String requested = requestedParentId == null ? "" : requestedParentId.trim()
        if (requested.isEmpty()) {
            return out
        }

        String failure = moveError == null ? "" : moveError.trim()

        if (!readBackOk) {
            out.put("applied", PARENT_APPLIED_UNKNOWN)
            out.put("reason", "The report page was written, but its position could not be read back" +
                (failure.isEmpty() ? "" : " and the move reported \"" + failure + "\"") +
                ", so whether it sits under the parent page was not measured. Open the parent page and " +
                "check before relying on this run.")
            return out
        }

        String actual = actualParentId == null ? "" : actualParentId.trim()
        if (actual.equals(requested)) {
            out.put("applied", PARENT_APPLIED_TRUE)
            return out
        }

        out.put("applied", PARENT_APPLIED_FALSE)
        out.put("reason", "The report page was written, but it does not sit under the parent page that was " +
            "requested: it sits " + (actual.isEmpty() ? "at the top level of the space" : "under page " + actual) +
            "." + (failure.isEmpty() ? "" : " The move reported \"" + failure + "\"."))
        return out
    }

    /* Body of a parent page this export creates. Minimal on purpose: it says what
     * the page is for and where it came from, and it holds no report data, which
     * lives on the child page and is rewritten on every run. */
    static final String PARENT_BODY = "<p>Container page for the Confluence space configuration export. " +
        "It was created by that export because the chosen parent page did not exist yet. " +
        "The report itself is the child page below; this page carries no report data and is never rewritten.</p>"

    /* ---- Remark read -------------------------------------------------------- */

    /* Confluence hands empty cells back self-closed after an editor round trip,
     * so both forms are matched. The self-closing alternative has to come first,
     * otherwise <td/> is consumed by the open-tag branch and swallows the row. */
    static final java.util.regex.Pattern TBODY = java.util.regex.Pattern.compile("(?s)<tbody[^>]*>(.*?)</tbody>")
    static final java.util.regex.Pattern ROW = java.util.regex.Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>")
    static final java.util.regex.Pattern CELL = java.util.regex.Pattern.compile("(?s)<t[hd][^>]*/>|<t[hd][^>]*>(.*?)</t[hd]>")
    static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("<[^>]+>")

    static String plainText(String cellHtml) {
        if (cellHtml == null) {
            return ""
        }
        String value = TAG.matcher(cellHtml).replaceAll(" ")
        value = value.replace("&nbsp;", " ").replace("&#160;", " ").replace("\u00A0", " ")
        value = value.replace("&lt;", "<").replace("&gt;", ">")
        value = value.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        value = value.replace("&amp;", "&")
        return value.replaceAll("\\s+", " ").trim()
    }

    /* Whitespace, a non-breaking space and the wrappers an editor leaves behind
     * carry no remark. Anything else that is still a tag does: a status lozenge,
     * an image, an emoticon, a link. */
    static final java.util.regex.Pattern LAYOUT_TAG =
        java.util.regex.Pattern.compile("(?i)</?(?:p|br|div|span)(?:\\s[^>]*)?/?>")

    /* A cell is empty only when it holds neither text nor element content.
     * Deciding that on the plain text alone dropped every cell whose markup
     * carries no text node, and the row was not even counted as read. */
    static boolean isEmptyCell(String cellHtml) {
        if (cellHtml == null) {
            return true
        }
        if (!plainText(cellHtml).isEmpty()) {
            return false
        }
        String rest = LAYOUT_TAG.matcher(cellHtml).replaceAll("")
        rest = rest.replace("&nbsp;", "").replace("&#160;", "").replace("\u00A0", "")
        return rest.trim().isEmpty()
    }

    /* The placeholder written into a row that carries no remark: a grey status
     * lozenge the administrator edits instead of building one. The Confluence
     * status macro takes a colour and a title and carries no body. */
    static final String REMARK_SEED =
        "<ac:structured-macro ac:name=\"status\" ac:schema-version=\"1\">" +
        "<ac:parameter ac:name=\"colour\">Grey</ac:parameter>" +
        "<ac:parameter ac:name=\"title\">TBD</ac:parameter>" +
        "</ac:structured-macro>"

    static final java.util.regex.Pattern MACRO_ID =
        java.util.regex.Pattern.compile("\\s+ac:macro-id=\"[^\"]*\"")

    /* The seed is this export's own markup, never an administrator's note, so it
     * reads back as no remark. An editor round trip stamps a macro-id onto every
     * macro and may wrap the cell in a paragraph, so the comparison is made on the
     * normalised form. Change the colour or the title and it is a remark again,
     * carried over verbatim like any other. */
    static boolean isRemarkSeed(String cellHtml) {
        if (cellHtml == null) {
            return false
        }
        String value = cellHtml.trim()
        if (value.startsWith("<p>") && value.endsWith("</p>")) {
            value = value.substring(3, value.length() - 4).trim()
        }
        value = MACRO_ID.matcher(value).replaceAll("")
        return value.replaceAll(">\\s+<", "><").trim() == REMARK_SEED
    }

    static List<String> cellsOf(String rowHtml) {
        List<String> cells = new ArrayList<String>()
        if (rowHtml == null) {
            return cells
        }
        java.util.regex.Matcher matcher = CELL.matcher(rowHtml)
        while (matcher.find()) {
            String inner = matcher.group(1)
            cells.add(inner == null ? "" : inner)
        }
        return cells
    }

    static int headerIndex(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (plainText(header.get(i)).equalsIgnoreCase(name)) {
                return i
            }
        }
        return -1
    }

    /* Exception class plus message. Both the remark read and the write path
     * report a failure in exactly this wording. */
    static String errorDetail(Throwable error) {
        String detail = error.getClass().getSimpleName()
        String message = error.getMessage()
        if (message != null && !message.trim().isEmpty()) {
            detail = detail + ": " + message.trim()
        }
        return detail
    }

    /* The read is a set of regular expressions over the page markup, and a regular
     * expression cannot see nesting. A table placed inside a Remark cell therefore
     * ends the enclosing table early: every remark below it is dropped, seeded again
     * on the next write, and an administrator's text is gone without anything having
     * reported a failure. That is the one outcome this export must never produce, so
     * nesting is detected before the read starts and refused. */
    static boolean hasNestedTableBody(String storage) {
        int depth = 0
        int at = 0
        while (at < storage.length()) {
            int open = storage.indexOf("<tbody", at)
            int close = storage.indexOf("</tbody", at)
            if (open < 0 && close < 0) {
                return false
            }
            if (open >= 0 && (close < 0 || open < close)) {
                depth++
                if (depth > 1) {
                    return true
                }
                at = open + 6
            } else {
                depth--
                at = close + 7
            }
        }
        return false
    }

    /* Reads every remark table on the page, not just the first one: the
     * orphaned-remark table is a second table and its notes have to survive as
     * well. Anything unexpected is FAILED, never an empty success. */
    static RemarkRead parseRemarks(String storage) {
        RemarkRead read = new RemarkRead()

        try {
            if (storage == null || storage.trim().isEmpty()) {
                return read.fail("The existing page has an empty body. It was not produced by this export, so it is not overwritten.")
            }
            if (!storage.contains(MARKER)) {
                return read.fail("The existing page does not carry the export marker \"" + MARKER + "\". It was not produced by this export, so it is not overwritten.")
            }
            if (hasNestedTableBody(storage)) {
                return read.fail("A table is nested inside another table on the existing page. This read " +
                    "works on the page markup and cannot tell which row such a remark belongs to, so " +
                    "nothing is written. Take the nested table out of the Remark cell and export again.")
            }

            int tablesMatched = 0
            java.util.regex.Matcher bodyMatcher = TBODY.matcher(storage)

            while (bodyMatcher.find()) {
                List<String> rows = new ArrayList<String>()
                java.util.regex.Matcher rowMatcher = ROW.matcher(bodyMatcher.group(1))
                while (rowMatcher.find()) {
                    rows.add(rowMatcher.group(1))
                }
                if (rows.isEmpty()) {
                    continue
                }

                List<String> header = cellsOf(rows.get(0))
                int keyIndex = headerIndex(header, COL_PATH)
                int remarkIndex = headerIndex(header, COL_REMARK)
                if (keyIndex < 0 || remarkIndex < 0) {
                    continue
                }
                tablesMatched++

                int required = Math.max(keyIndex, remarkIndex) + 1
                for (int i = 1; i < rows.size(); i++) {
                    List<String> cells = cellsOf(rows.get(i))
                    if (cells.isEmpty()) {
                        continue
                    }
                    if (cells.size() < required) {
                        return read.fail("Row " + String.valueOf(i) + " of a remark table carries " + String.valueOf(cells.size()) +
                            " cell(s) where " + String.valueOf(required) + " are needed. The table structure was changed; nothing is written.")
                    }

                    String key = plainText(cells.get(keyIndex))
                    String remarkHtml = cells.get(remarkIndex).trim()
                    if (key.isEmpty() || isEmptyCell(remarkHtml) || isRemarkSeed(remarkHtml)) {
                        continue
                    }
                    if (read.remarks.containsKey(key)) {
                        return read.fail("Path \"" + key + "\" carries more than one remark on the existing page. That is ambiguous; nothing is written.")
                    }
                    read.remarks.put(key, remarkHtml)
                }
            }

            if (tablesMatched == 0) {
                return read.fail("No table with the columns \"" + COL_PATH + "\" and \"" + COL_REMARK +
                    "\" was found on the existing page. The read is inconclusive; nothing is written.")
            }

            read.outcome = RemarkRead.PARSED
            return read
        } catch (Throwable error) {
            return read.fail("The remark read failed (" + errorDetail(error) + "); nothing is written.")
        }
    }

    /* ---- Payload accessors (the POST body is JSON, so nothing is assumed) --- */

    static String str(Map<String, Object> source, String key, String fallback) {
        Object raw = source == null ? null : source.get(key)
        if (raw == null) {
            return fallback
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? fallback : value
    }

    static boolean flag(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue()
        }
        return raw != null && raw.toString().trim().equalsIgnoreCase("true")
    }

    static Map<String, Object> sub(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        return raw instanceof Map ? copyMap((Map<?, ?>) raw) : new LinkedHashMap<String, Object>()
    }

    static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        if (source == null) {
            return result
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object rawKey = entry.getKey()
            if (rawKey != null) {
                result.put(rawKey.toString(), entry.getValue())
            }
        }
        return result
    }

    static List<Map<String, Object>> rowsOf(Map<String, Object> source, String key) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>()
        Object raw = source == null ? null : source.get(key)
        if (!(raw instanceof List)) {
            return result
        }
        for (Object element : (List<?>) raw) {
            if (element instanceof Map) {
                result.add(copyMap((Map<?, ?>) element))
            }
        }
        return result
    }

    static List<String> textsOf(Map<String, Object> source, String key) {
        List<String> result = new ArrayList<String>()
        Object raw = source == null ? null : source.get(key)
        if (!(raw instanceof List)) {
            return result
        }
        for (Object element : (List<?>) raw) {
            if (element != null) {
                result.add(element.toString())
            }
        }
        return result
    }

    /* ---- The property value gate -------------------------------------------- */

    /* The sharpest edge in this tool. A space property value is an app's own
     * serialised object and can hold a secret; the exported page is readable by
     * everyone who can read the space. The report itself already withholds values
     * unless values=true was passed and redacts a key on the deny-list, and this
     * gate applies both rules a SECOND time, here, on the way into the page.
     *
     * The reason for the second application is the trust boundary: the payload
     * travels through the browser, so the export cannot treat what comes back as
     * the report's own output. What the gate can enforce is that no value reaches
     * a page unless the request declares values=true, and that a deny-listed key
     * is withheld even if the payload claims otherwise. What it cannot do is
     * invent a secret the administrator does not already hold - the same
     * administrator who may edit any page they can reach. That boundary is stated
     * rather than pretended away. */
    static final String VALUES_NOT_REQUESTED =
        "Not read. The report was run without values=true, so no property value is on this page."
    static final String VALUES_DENIED =
        "Withheld. The key name matches this report's deny-list, so the value was not printed."

    static final String PROPERTY_VALUE_KIND = "spacePropertyDetail"
    static final String PROPERTY_VALUE_LABEL = "Value"

    static boolean isPropertyValue(String kind, String label) {
        return PROPERTY_VALUE_KIND.equals(kind) && PROPERTY_VALUE_LABEL.equals(label)
    }

    /* owner is the label of the node above, which for a property value is the
     * property key - the only thing the deny-list needs. */
    static String valueText(String kind, String label, String owner, String value, boolean valuesRequested) {
        if (!isPropertyValue(kind, label)) {
            return value == null ? "" : value
        }
        if (!valuesRequested) {
            return VALUES_NOT_REQUESTED
        }
        if (Pc.sensitive(owner)) {
            return VALUES_DENIED
        }
        return value == null ? "" : value
    }

    /* ---- Rendering ---------------------------------------------------------- */

    /* The payload is the report the GET branch serialised for this run, so the page
     * shows exactly the tree the administrator saw. It travels through the browser,
     * which means an administrator could tamper with it - the same administrator
     * who may edit any page they can reach anyway. Everything is escaped on the way
     * into storage format, and the remark carry-over is unaffected by it: remarks
     * come from the existing page and are read there. */
    static ExportOutcome render(Map<String, Object> request, RemarkRead read) {
        ExportOutcome outcome = new ExportOutcome()
        outcome.remarksRead = read == null ? 0 : read.remarks.size()

        Map<String, Object> space = sub(request, "space")
        Map<String, Object> instance = sub(request, "instance")
        Map<String, Object> totals = sub(request, "totals")
        Map<String, Object> options = sub(request, "options")
        boolean valuesRequested = flag(options, "values")

        StringBuilder out = new StringBuilder()

        out.append("<p>Complete configuration of space <strong>")
        out.append(esc(str(space, "name", "unknown"))).append("</strong> (")
        out.append(esc(str(space, "key", "?"))).append(") on ")
        out.append(esc(str(instance, "title", "this instance")))
        out.append(", Confluence ").append(esc(str(instance, "confluenceVersion", "unknown version")))
        out.append(". Generated ").append(esc(str(request, "generatedAt", "unknown")))
        out.append(" by the space configuration report v")
        out.append(esc(str(request, "reportVersion", "?"))).append(".</p>")

        out.append("<p>")
        out.append(esc(str(totals, "nodes", "0"))).append(" configuration items, ")
        out.append(esc(str(totals, "unreadable", "0"))).append(" of them unreadable, ")
        out.append(esc(str(totals, "unlinked", "0"))).append(" without a deep link. ")
        out.append("An unreadable item is not an empty one: it is an item whose configuration ")
        out.append("could not be read, and it is marked as such in the State column.</p>")

        /* What the page carries of the property store is said on the page, not
         * only in the run that produced it. A reader who finds no values has to be
         * able to tell "switched off" from "this space has none". */
        out.append("<p>")
        out.append(valuesRequested
            ? "This run was given values=true, so this page carries space property VALUES. A key whose " +
              "name matches secret, token, password, apikey or credential is withheld even so. Everyone " +
              "who can read this space can read this page."
            : "This run was not given values=true, so no space property value is on this page. The keys " +
              "and their stores are listed, the values are not read.")
        out.append("</p>")

        out.append("<p>The <strong>").append(esc(COL_REMARK)).append("</strong> column belongs to you. ")
        out.append("It is read back and carried over on every later run of this export. ")
        out.append("Everything else on this page is overwritten each time.</p>")

        /* Rows first, so the truncation notice can be placed above the tables it
         * applies to rather than below them, where it would be read too late. Rows
         * are grouped by section so that each section gets its own table, but the
         * paths are made unique across the whole page rather than per table: a
         * remark is carried over by its path, and the read refuses a path that
         * carries text more than once anywhere on the page. */
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>()
        List<List<Map<String, Object>>> grouped = new ArrayList<List<Map<String, Object>>>()
        List<Map<String, Object>> sections = rowsOf(request, "sections")
        List<Map<String, Object>> kept = new ArrayList<Map<String, Object>>()
        for (Map<String, Object> section : sections) {
            List<Map<String, Object>> sectionRows = new ArrayList<Map<String, Object>>()
            flatten(section, "", "", sectionRows)
            if (sectionRows.isEmpty()) {
                continue
            }
            grouped.add(sectionRows)
            kept.add(section)
            /* The same row objects, not copies: making the paths unique below has to
             * be visible in both views. */
            rows.addAll(sectionRows)
        }
        makePathsUnique(rows)

        List<Integer> sizes = new ArrayList<Integer>()
        for (List<Map<String, Object>> sectionRows : grouped) {
            sizes.add(Integer.valueOf(sectionRows.size()))
        }
        List<Integer> allowance = shareBudget(sizes, MAX_ROWS)

        List<String> cutSections = new ArrayList<String>()
        for (int i = 0; i < grouped.size(); i++) {
            if (grouped.get(i).size() > allowance.get(i).intValue()) {
                cutSections.add(str(grouped.get(i).get(0), "label", "Section"))
            }
        }
        if (!cutSections.isEmpty()) {
            outcome.warnings.add("Cut to " + String.valueOf(MAX_ROWS) + " of " +
                String.valueOf(rows.size()) + " rows, in: " + cutSections.join(", "))
            out.append("<p><strong>This page is not complete.</strong> It carries ")
            out.append(String.valueOf(MAX_ROWS)).append(" of ").append(String.valueOf(rows.size()))
            out.append(" configuration items. Every section is on the page and keeps its ")
            out.append("heading; the ones whose rows were cut say so on their own heading, and ")
            out.append("those are: ").append(esc(cutSections.join(", "))).append(". ")
            out.append("What is cut is in the report itself and in its CSV and JSON output; it ")
            out.append("is missing from this page, not from the space.</p>")
        }

        Set<String> used = new LinkedHashSet<String>()
        for (int i = 0; i < grouped.size(); i++) {
            List<Map<String, Object>> sectionRows = grouped.get(i)
            int allowed = allowance.get(i).intValue()
            List<Map<String, Object>> visible = sectionRows.size() > allowed
                ? sectionRows.subList(0, allowed) : sectionRows
            boolean sectionCut = visible.size() < sectionRows.size()

            /* The first row of a section is the section node itself, so its label is
             * the heading an administrator recognises from the report. It is taken
             * from the full list rather than from the visible one, because a section
             * that was cut to nothing still has to carry its own name. */
            String heading = str(sectionRows.get(0), "label", "Section")
            out.append(expandOpen(sectionCut
                ? heading + " (" + String.valueOf(visible.size()) + " of " +
                    Pc.plural(sectionRows.size(), "item") + ", the rest is cut)"
                : heading + " (" + Pc.plural(visible.size(), "item") + ")"))
            if (sectionCut) {
                out.append("<p><strong>This section is not complete.</strong> It carries ")
                out.append(String.valueOf(visible.size())).append(" of ")
                out.append(String.valueOf(sectionRows.size())).append(" items.</p>")
            }
            /* A section that reads the database directly says so on the page, not
             * only in the HTML report. Whoever opens this page months later has to
             * see that the section is coupled to a schema which is not a public
             * API, because that is what explains an UNREADABLE section after an
             * upgrade. */
            for (String note : textsOf(kept.get(i), "notes")) {
                out.append("<p><em>").append(esc(note)).append("</em></p>")
            }
            out.append(rowTable(visible, read, used, outcome, valuesRequested))
            out.append(expandClose())
        }

        /* A page with no table at all cannot be read back, and an unreadable page is
         * never overwritten - so an empty report would brick its own export on the
         * next run. The header alone is enough for the read to succeed. */
        if (grouped.isEmpty()) {
            out.append(rowTable(new ArrayList<Map<String, Object>>(), read, used, outcome, valuesRequested))
        }

        /* A remark whose row is gone is not deleted. The configuration it commented
         * on may come back, and even if it does not, an administrator's own words
         * are not this export's to discard. */
        List<String> orphans = new ArrayList<String>()
        if (read != null) {
            for (Map.Entry<String, String> entry : read.remarks.entrySet()) {
                if (!used.contains(entry.getKey())) {
                    orphans.add(entry.getKey())
                }
            }
        }
        if (!orphans.isEmpty()) {
            outcome.orphanKeys.addAll(orphans)
            out.append("<h2>Remarks without a matching item</h2>")
            out.append("<p>These remarks were carried over from the previous version of this page, ")
            out.append("but the configuration item they belong to is no longer in the space. ")
            out.append("They are kept here rather than dropped. Delete a row to be rid of it.</p>")
            out.append(expandOpen("Remarks without a matching item (" +
                Pc.plural(orphans.size(), "remark") + ")"))
            out.append("<table><tbody>")
            out.append(headerRow([COL_PATH, COL_REMARK]))
            for (String key : orphans) {
                out.append("<tr>").append(cell(esc(key)))
                out.append(cell(read.remarks.get(key))).append("</tr>")
            }
            out.append("</tbody></table>")
            out.append(expandClose())
        }

        List<String> notes = textsOf(request, "notes")
        if (!notes.isEmpty()) {
            out.append("<h2>Observations</h2>")
            out.append("<p>Nothing failed to read here. These are things worth knowing about ")
            out.append("the configuration itself.</p>")
            out.append(expandOpen("Observations (" + Pc.plural(notes.size(), "note") + ")"))
            out.append("<ul>")
            for (String entry : notes) {
                out.append("<li>").append(esc(entry)).append("</li>")
            }
            out.append("</ul>")
            out.append(expandClose())
        }

        List<String> diagnostics = textsOf(request, "diagnostics")
        if (!diagnostics.isEmpty()) {
            out.append("<h2>Suppressed reads</h2>")
            out.append("<p>Each entry is a read that failed. It is not an absence of configuration.</p>")
            out.append(expandOpen("Suppressed reads (" + Pc.plural(diagnostics.size(), "failed read") + ")"))
            out.append("<ul>")
            for (String entry : diagnostics) {
                out.append("<li>").append(esc(entry)).append("</li>")
            }
            out.append("</ul>")
            out.append(expandClose())
        }

        out.append("<p><em>").append(esc(MARKER)).append("</em></p>")

        outcome.storage = out.toString()
        return outcome
    }

    /* The path is the carry-over key, so it has to be stable across runs. It is
     * built from labels rather than from ids because an administrator who renames
     * a template or a permission subject expects the remark to follow the name
     * they see, and because the same report has to work on an instance where ids
     * differ. The owner - the label of the node above - travels with the row as
     * well, because the property value gate needs the property key. */
    static void flatten(Map<String, Object> node, String parentPath, String owner,
                        List<Map<String, Object>> rows) {
        if (node == null) {
            return
        }
        String label = str(node, "label", "")
        String path = parentPath.isEmpty() ? label : parentPath + " > " + label
        Map<String, Object> row = new LinkedHashMap<String, Object>()
        row.put("path", path)
        row.put("label", label)
        row.put("kind", str(node, "kind", ""))
        row.put("owner", owner)
        row.put("value", str(node, "value", ""))
        row.put("state", str(node, "state", "read"))
        row.put("deepLink", str(node, "deepLink", null))
        row.put("linkNote", str(node, "linkNote", null))
        rows.add(row)
        for (Map<String, Object> child : rowsOf(node, "children")) {
            flatten(child, path, label, rows)
        }
    }

    /* Two sibling nodes can carry the same label - two grants of the same type under
     * one permission produce the identical path - and the path is the key a remark is
     * carried over by. Left alone, that key stamped one administrator's remark onto
     * every row sharing the path, counted it once per row, and on the run after that
     * the parser found the same path twice with text in both and refused to write
     * anything ever again. Fail-closed, so no text was lost, but the export bricked
     * itself two runs after the first remark. A repeated path therefore gets an
     * ordinal, which is stable as long as the configuration is. */
    static void makePathsUnique(List<Map<String, Object>> rows) {
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>()
        for (Map<String, Object> row : rows) {
            String path = str(row, "path", "")
            Integer count = seen.get(path)
            if (count == null) {
                seen.put(path, Integer.valueOf(1))
                continue
            }
            int next = count.intValue() + 1
            seen.put(path, Integer.valueOf(next))
            row.put("path", path + " #" + String.valueOf(next))
        }
    }

    static String stateText(String state) {
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
        return ""
    }

    static String linkCell(String deepLink, String linkNote) {
        if (deepLink != null && !deepLink.trim().isEmpty()) {
            return "<a href=\"" + esc(deepLink) + "\">open</a>"
        }
        if (linkNote != null && !linkNote.trim().isEmpty()) {
            return esc(linkNote)
        }
        return ""
    }

    /* The row budget belongs to the page, and spending it in section order let the
     * first big section starve every section behind it. A starved section did not
     * appear at all, not even by name, so a reader could not tell a section that is
     * not configured from one that was cut away - which is the single thing this
     * report exists not to do.
     *
     * The budget is shared instead. Every section is offered an equal part; a section
     * that needs less than its part gives the remainder back, and the remainder is
     * offered to the sections that want more. Smallest first, so the giving back
     * happens before the taking. A section that is still cut keeps its heading and
     * says what was cut, on the heading and above its table. */
    static List<Integer> shareBudget(List<Integer> sizes, int total) {
        List<Integer> allowance = new ArrayList<Integer>()
        for (int i = 0; i < sizes.size(); i++) {
            allowance.add(Integer.valueOf(0))
        }
        List<Integer> order = new ArrayList<Integer>()
        for (int i = 0; i < sizes.size(); i++) {
            order.add(Integer.valueOf(i))
        }
        order.sort { Integer left, Integer right ->
            return sizes.get(left.intValue()).intValue() - sizes.get(right.intValue()).intValue()
        }
        int left = total
        int remaining = sizes.size()
        for (Integer index : order) {
            if (remaining <= 0) {
                break
            }
            /* Math.floorDiv, not "/". Groovy divides two ints into a BigDecimal, which
             * the static type checker rejects here and which would otherwise hand out
             * fractional rows. */
            int share = Math.floorDiv(left, remaining)
            int want = sizes.get(index.intValue()).intValue()
            int give = want < share ? want : share
            allowance.set(index.intValue(), Integer.valueOf(give))
            left -= give
            remaining--
        }
        return allowance
    }

    /* One table per section, so each of them can sit inside its own collapsed
     * macro. The remark carry-over is unaffected by the split: the read scans every
     * table on the page and keys on the path, which is unique page-wide.
     *
     * NOTE: this is the flat form - one row per node, containment carried in a path
     * string, an Item column - and the HTML report's table view no longer works that
     * way. It is deliberately kept as the Jira sibling has it, because the path is
     * the carry-over key and changing its shape reshapes every remark an
     * administrator has already written. Bringing the two into line is blocked on
     * proving the carry-over against a live instance first. */
    static String rowTable(List<Map<String, Object>> rows, RemarkRead read,
                           Set<String> used, ExportOutcome outcome, boolean valuesRequested) {
        StringBuilder out = new StringBuilder("<table><tbody>")
        out.append(headerRow([COL_PATH, COL_ITEM, COL_VALUE, COL_STATE, COL_LINK, COL_REMARK]))
        for (Map<String, Object> row : rows) {
            String path = str(row, "path", "")
            String remark = read == null ? null : read.remarks.get(path)
            if (remark != null) {
                used.add(path)
                outcome.remarksCarried++
            }
            out.append("<tr>")
            out.append(cell(esc(path)))
            out.append(cell(esc(str(row, "label", ""))))
            out.append(cell(esc(valueText(str(row, "kind", ""), str(row, "label", ""),
                str(row, "owner", ""), str(row, "value", ""), valuesRequested))))
            out.append(cell(esc(stateText(str(row, "state", Pc.READ)))))
            out.append(cell(linkCell(str(row, "deepLink", null), str(row, "linkNote", null))))
            out.append(cell(remark == null ? REMARK_SEED : remark))
            out.append("</tr>")
        }
        return out.append("</tbody></table>").toString()
    }

    /* This page is long by design, and a long page is read by nobody. Every table
     * therefore sits inside Confluence's own Expand macro, which renders collapsed.
     * It is storage-format markup of a bundled macro, so nothing has to be installed
     * for the page to work, and the body of an expand is indexed like any other
     * content - the page stays searchable while it is closed. The body stays a plain
     * table, which is what the remark read looks for. */
    static String expandOpen(String title) {
        return "<ac:structured-macro ac:name=\"expand\"><ac:parameter ac:name=\"title\">" +
            esc(title) + "</ac:parameter><ac:rich-text-body>"
    }

    static String expandClose() {
        return "</ac:rich-text-body></ac:structured-macro>"
    }

    static String headerRow(List<String> names) {
        StringBuilder out = new StringBuilder("<tr>")
        for (String name : names) {
            out.append("<th>").append(esc(name)).append("</th>")
        }
        return out.append("</tr>").toString()
    }

    static String cell(String html) {
        return "<td>" + (html == null || html.isEmpty() ? "" : html) + "</td>"
    }

    /* Storage format is XHTML, so an unescaped angle bracket from a template name
     * is not a cosmetic problem: it produces a page Confluence refuses to save. */
    static String esc(Object value) {
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
}

/* What a remark read found, and whether writing is allowed at all.
 *
 * The three outcomes are kept distinguishable on purpose: a failed read must
 * never look like "this page has no remarks yet", because the caller would then
 * render an empty Remark column and overwrite every administrator note. */
class RemarkRead {

    static final String NONE = "none"
    static final String PARSED = "parsed"
    static final String FAILED = "failed"

    String outcome = NONE
    String reason
    String pageId
    int pageVersion

    Map<String, String> remarks = new LinkedHashMap<String, String>()

    /* The single gate every write path has to pass. FAILED never gets through. */
    boolean isWriteAllowed() {
        return outcome == NONE || outcome == PARSED
    }

    RemarkRead fail(String why) {
        outcome = FAILED
        reason = why
        remarks.clear()
        return this
    }

    Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        result.put("outcome", outcome)
        result.put("reason", reason)
        result.put("remarks", Integer.valueOf(remarks.size()))
        result.put("pageId", pageId)
        result.put("pageVersion", Integer.valueOf(pageVersion))
        return result
    }
}

/* Rendered storage format plus what happened to the carried-over remarks. */
class ExportOutcome {
    String storage
    int remarksRead
    int remarksCarried
    List<String> orphanKeys = new ArrayList<String>()
    List<String> warnings = new ArrayList<String>()
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
        String id = namespace + "\u0000" + key
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


/* =============================================================================
 * The export write path
 *
 * Local page APIs only. This endpoint runs inside Confluence, so there is no
 * transport here and no outbound call of any kind: no URL is built, no request
 * factory is asked for, nothing leaves the JVM. The CI job that forbids an
 * undeclared outbound call is therefore absolute on this side - a hit anywhere in
 * this file is a defect, not a section to be declared.
 *
 * Everything below touches Confluence types and can only be verified on an
 * instance. The rules it carries out - the fail-closed remark read, the marker,
 * the row budget, the value gate - are all in Cx above the banner and are covered
 * by the offline suite.
 * ========================================================================== */

class Cw {

    /* Who this report describes, read from THIS instance and never taken from the
     * payload the browser sent back. An administrator can edit the payload; they
     * cannot edit what the settings report.
     *
     * The GET branch reads the same three values inline for the HTML report. They
     * are read again here rather than trusted from the payload, which is the whole
     * point: a page that outlives the run has to name the instance it describes.
     * Both reads go through the invoker because neither SettingsManager nor
     * ApplicationProperties is on the list of types measured acquirable here. */
    static Map<String, String> instanceIdentity() {
        Map<String, String> out = new LinkedHashMap<String, String>()
        out.put("baseUrl", null)
        out.put("title", null)
        out.put("confluenceVersion", null)
        try {
            Object applicationProperties =
                ComponentLocator.getComponent(Class.forName("com.atlassian.sal.api.ApplicationProperties"))
            if (applicationProperties != null) {
                out.put("baseUrl", Pc.firstText(applicationProperties, ["getBaseUrl"]))
                out.put("title", Pc.firstText(applicationProperties, ["getDisplayName"]))
                out.put("confluenceVersion", Pc.firstText(applicationProperties, ["getVersion"]))
            }
        } catch (Throwable ignored) {
            /* One unreadable source costs the values it holds and nothing else. The
             * caller falls back to the settings below and, failing that, prints the
             * not-available marker rather than a guess. */
        }
        if (out.get("baseUrl") == null || out.get("title") == null) {
            try {
                Object settings = ComponentLocator.getComponent(
                    Class.forName("com.atlassian.confluence.setup.settings.SettingsManager"))
                Object global = Pc.duck(settings, "getGlobalSettings", null)
                if (out.get("baseUrl") == null) {
                    out.put("baseUrl", Pc.firstText(global, ["getBaseUrl"]))
                }
                if (out.get("title") == null) {
                    out.put("title", Pc.firstText(global, ["getSiteTitle"]))
                }
            } catch (Throwable ignored) {
                /* Same rule as above. */
            }
        }
        return out
    }

    /* The browse address of a written page, built from this instance's own base
     * URL. Nothing is fetched: this is string work over a value that was already
     * read. A base URL that could not be read costs the link and says so; it never
     * costs the write, which has already happened by then. */
    static String pageUrl(String baseUrl, String pageId) {
        String prefix = Pc.trimBase(baseUrl)
        if (prefix == null || prefix.isEmpty() || pageId == null || pageId.trim().isEmpty()) {
            return null
        }
        return prefix + "/pages/viewpage.action?pageId=" + Pc.urlQuery(pageId.trim())
    }

    /* Every current space of this instance, paginated to the end. A response that
     * claims more results without advancing is a failed inventory and is raised as
     * one: an inventory that silently stops short is indistinguishable from an
     * instance with fewer spaces. */
    static Map<String, Object> spaceRows(ApiSpaceService apiSpaceService) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>()
        result.put("ok", Boolean.FALSE)
        result.put("error", null)
        result.put("spaces", rows)

        try {
            int start = 0
            final int pageSize = 100
            SpaceFinder finder = apiSpaceService.find(new Expansion[0])
            finder = finder.withStatus(ApiSpaceStatus.CURRENT)
            while (true) {
                PageResponse<ApiSpace> page = finder.fetchMany(new SimplePageRequest(start, pageSize))
                for (ApiSpace space : page.getResults()) {
                    String key = space == null ? null : space.getKey()
                    if (key == null) {
                        continue
                    }
                    String name = space.getName()
                    Map<String, Object> row = new LinkedHashMap<String, Object>()
                    row.put("key", key)
                    row.put("name", name == null || name.trim().isEmpty() ? key : name)
                    rows.add(row)
                }
                if (!page.hasMore()) {
                    break
                }
                int returned = page.size()
                if (returned <= 0) {
                    throw new IllegalStateException("Space pagination did not advance")
                }
                start += returned
            }
        } catch (Exception error) {
            result.put("error", "The space list could not be read (" + Cx.errorDetail(error) +
                "). That is a failed read, not an instance without spaces.")
            return result
        }

        rows.sort { Map<String, Object> a, Map<String, Object> b ->
            int byName = Cx.str(a, "name", "").compareToIgnoreCase(Cx.str(b, "name", ""))
            if (byName != 0) {
                return byName
            }
            return Cx.str(a, "key", "").compareToIgnoreCase(Cx.str(b, "key", ""))
        }
        result.put("ok", Boolean.TRUE)
        return result
    }

    /* One value out of a search document, which hands its fields back as arrays. */
    static String firstIndexValue(Map<String, String[]> document, String fieldName) {
        String[] values = document == null ? null : document.get(fieldName)
        return values == null || values.length == 0 ? null : values[0]
    }

    /* The parent-page lookup, exact hit first and whole-word title matches after it.
     *
     * The index supplies content ids and nothing else. Title and space of every hit
     * are read back through PageService's id locator, so what the administrator sees
     * comes from the database - an index entry may name a page that was deleted or
     * moved since it was written, and a hit that no longer resolves, or resolves
     * into another space, is dropped rather than offered.
     *
     * A search that throws is reported as a failed search. "No such page" is said
     * only when the search answered and named nothing: the caller answers a miss by
     * creating a page, and a swallowed error would create a duplicate. */
    static Map<String, Object> searchPagesByTitle(SearchManager searchManager, PageService pageService,
                                                  String spaceKey, String query, int limit) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        List<Map<String, Object>> pages = new ArrayList<Map<String, Object>>()
        result.put("ok", Boolean.FALSE)
        result.put("error", null)
        result.put("pages", pages)
        result.put("truncated", Boolean.FALSE)

        Set<String> takenIds = new LinkedHashSet<String>()

        /* The exact hit first. It is the one title lookup here with a documented
         * null contract, and it stays on top so a title that is already correct is
         * always the first thing offered. */
        try {
            Page exact = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, query).getPage()
            if (exact != null && takenIds.add(exact.getIdAsString())) {
                pages.add(pageRow(exact))
            }
        } catch (Exception error) {
            result.put("error", "The page \"" + query + "\" could not be looked up in \"" + spaceKey + "\" (" +
                Cx.errorDetail(error) + "). That is a failed read, not a space without that page.")
            return result
        }

        List<String> tokens = Cx.titleTokens(query)
        if (tokens.isEmpty()) {
            /* Punctuation only - there is no word to search for. The exact hit
             * above, if there was one, still stands. */
            result.put("ok", Boolean.TRUE)
            return result
        }

        final String contentIdField = SearchFieldMappings.CONTENT_ID.getName()
        final Set<String> requestedFields = new LinkedHashSet<String>()
        requestedFields.add(contentIdField)

        /* Four times the visible cap, so hits that resolve to null or into another
         * space can be dropped without emptying the list. Collection stops there;
         * seen keeps counting, which is how truncation is told apart from a result
         * set that simply fits. */
        final int idCap = limit * 4
        final List<String> hitIds = new ArrayList<String>()
        final int[] seen = new int[1]

        try {
            List<SearchQuery> clauses = new ArrayList<SearchQuery>()
            clauses.add(new ContentTypeQuery(ContentTypeEnum.PAGE))
            clauses.add(new InSpaceQuery(spaceKey))
            String titleField = SearchFieldMappings.TITLE.getName()
            if (tokens.size() > 1) {
                clauses.add(new TextFieldQuery(titleField,
                    String.join(" ", tokens.subList(0, tokens.size() - 1)), BooleanOperator.AND))
            }
            clauses.add(new WildcardTextFieldQuery(titleField,
                tokens.get(tokens.size() - 1) + "*", BooleanOperator.AND))

            searchManager.scan(
                Collections.singletonList(Index.CONTENT),
                BooleanQuery.andQuery(clauses.toArray(new SearchQuery[0])),
                requestedFields,
                new java.util.function.Consumer<Map<String, String[]>>() {
                    @Override
                    void accept(Map<String, String[]> document) {
                        String contentId = Cw.firstIndexValue(document, contentIdField)
                        if (contentId == null || contentId.trim().isEmpty()) {
                            return
                        }
                        seen[0]++
                        if (hitIds.size() < idCap) {
                            hitIds.add(contentId.trim())
                        }
                    }
                }
            )
        } catch (Exception error) {
            result.put("error", "The page search in \"" + spaceKey + "\" failed (" + Cx.errorDetail(error) +
                "). That is a failed search, not a space without a matching page.")
            return result
        }

        List<Map<String, Object>> found = new ArrayList<Map<String, Object>>()
        for (String contentId : hitIds) {
            Page page = null
            try {
                page = pageService.getIdPageLocator(Long.parseLong(contentId)).getPage()
            } catch (Exception ignored) {
                /* One unreadable id costs one candidate, never the whole list. The
                 * search itself answered, which is the distinction that matters to
                 * the caller. */
                continue
            }
            if (page == null || !spaceKey.equalsIgnoreCase(String.valueOf(page.getSpaceKey()))) {
                continue
            }
            if (takenIds.add(page.getIdAsString())) {
                found.add(pageRow(page))
            }
        }
        found.sort { Map<String, Object> a, Map<String, Object> b ->
            return Cx.str(a, "title", "").compareToIgnoreCase(Cx.str(b, "title", ""))
        }

        for (Map<String, Object> row : found) {
            if (pages.size() >= limit) {
                result.put("truncated", Boolean.TRUE)
                break
            }
            pages.add(row)
        }
        if (seen[0] > hitIds.size()) {
            result.put("truncated", Boolean.TRUE)
        }

        result.put("ok", Boolean.TRUE)
        return result
    }

    private static Map<String, Object> pageRow(Page page) {
        Map<String, Object> row = new LinkedHashMap<String, Object>()
        row.put("id", page.getIdAsString())
        row.put("title", page.getTitle())
        return row
    }
}


/* =============================================================================
 * REST Endpoint - Confluence page export (POST)
 *
 * Same endpoint name as the report with a different httpMethod. The Adaptavist
 * documentation states that several closures with the same name and different
 * verbs may live in one file, so the report page can POST to its own URL without
 * knowing the REST base path.
 *
 * The closure parameters stay untyped and the response class is resolved at
 * runtime, for the same reason the GET branch does it: naming a JAX-RS type
 * statically would tie this file to one ScriptRunner line.
 *
 * CSRF - UNVERIFIED. The Custom REST Endpoint documentation does not say whether
 * these endpoints sit behind the Confluence XSRF filter, so the report page sends
 * X-Atlassian-Token: no-check, which is required if the filter applies and is
 * harmless if it does not. Reading that header back would need the three-argument
 * HttpServletRequest closure form, and the servlet package this ScriptRunner
 * version passes on Confluence 10 (javax or jakarta) is not documented either, so
 * no header check is attempted here. What IS enforced on the server: the
 * confluence-administrators gate, and the rule that only a page carrying the
 * export marker is ever updated - a forged request can neither replace a foreign
 * page nor drop a remark. TO CONFIRM before relying on more than that: whether
 * the XSRF filter covers ScriptRunner endpoints, and which HttpServletRequest
 * type is passed, so an explicit header check can be added.
 * ========================================================================== */

spaceConfig(
    httpMethod: "POST",
    groups: ["confluence-administrators"]
) { queryParams, body ->

    long started = System.currentTimeMillis()

    Class responseClass = Http.resolveResponseClass()

    def refuse = { int status, String stage, String message ->
        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.FALSE)
        payload.put("written", Boolean.FALSE)
        payload.put("stage", stage)
        payload.put("error", message)
        payload.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.build(responseClass, status,
            JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Http.JSON, null)
    }

    def answer = { Map<String, Object> data ->
        data.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(data)), Http.JSON)
    }

    String requestBody = body == null ? null : body.toString()
    if (requestBody == null || requestBody.trim().isEmpty()) {
        return refuse(400, "request", "The request body is empty. The export payload is expected as JSON.")
    }
    if (requestBody.length() > Cx.MAX_PAYLOAD_CHARS) {
        return refuse(413, "request", "The export payload exceeds " +
            String.valueOf(Cx.MAX_PAYLOAD_CHARS) + " characters.")
    }

    Object parsed = null
    try {
        parsed = new JsonSlurper().parseText(requestBody)
    } catch (Exception error) {
        return refuse(400, "request", "The request body is not valid JSON: " + error.getClass().getSimpleName())
    }
    if (!(parsed instanceof Map)) {
        return refuse(400, "request", "The request body must be a JSON object.")
    }

    Map<String, Object> request = Cx.copyMap((Map<?, ?>) parsed)

    /* ---- Staged lookups ---------------------------------------------------- */

    /* Rendering the report reads nothing. Everything the export form needs arrives
     * here on demand, one stage per request, discriminated by "action": spaces
     * then pages then write. A body without an action is the write, so the write
     * path below keeps the shape and the order it always had. There is no target
     * stage: this endpoint writes into its own instance. */
    String requestedAction = Cx.str(request, "action", "write").toLowerCase(Locale.ROOT)

    if (requestedAction == "spaces") {
        ApiSpaceService apiSpaceService = null
        try {
            apiSpaceService = ComponentLocator.getComponent(ApiSpaceService.class)
        } catch (Throwable error) {
            return refuse(500, "spaces", "The Confluence SpaceService could not be obtained (" +
                Db.why(error) + "), so the space list could not be read. That is a failed read, " +
                "not an instance without spaces.")
        }
        if (apiSpaceService == null) {
            return refuse(500, "spaces", "The Confluence SpaceService could not be resolved, so the " +
                "space list could not be read. That is a failed read, not an instance without spaces.")
        }

        Map<String, Object> listed = Cw.spaceRows(apiSpaceService)
        if (listed.get("ok") != Boolean.TRUE) {
            return refuse(500, "spaces", String.valueOf(listed.get("error")))
        }
        List<Map<String, Object>> spaceRows = (List<Map<String, Object>>) listed.get("spaces")
        if (spaceRows.isEmpty()) {
            return refuse(500, "spaces", "The space inventory answered but named no current space, " +
                "so no space can be picked.")
        }

        Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
        spacePayload.put("ok", Boolean.TRUE)
        spacePayload.put("action", "spaces")
        spacePayload.put("spaces", spaceRows)
        return answer(spacePayload)
    }

    if (requestedAction == "pages") {
        String searchSpace = Cx.str(request, "spaceKey", "")
        String searchTitle = Cx.str(request, "query", "").trim()
        if (searchSpace.isEmpty()) {
            return refuse(400, "pages", "No space was selected, so there is nothing to look in.")
        }
        if (searchTitle.length() < Cx.MIN_SEARCH_CHARS) {
            return refuse(400, "pages", "Type at least " + String.valueOf(Cx.MIN_SEARCH_CHARS) +
                " characters of the page title.")
        }

        PageService pageLookup = null
        SearchManager searchLookup = null
        try {
            pageLookup = ComponentLocator.getComponent(PageService.class)
            searchLookup = ComponentLocator.getComponent(SearchManager.class)
        } catch (Throwable error) {
            return refuse(500, "pages", "A component needed for the page search could not be obtained (" +
                Db.why(error) + "). That is a failed lookup, not a space without that page.")
        }
        if (pageLookup == null || searchLookup == null) {
            return refuse(500, "pages", "A component needed for the page search could not be resolved (" +
                (pageLookup == null ? "PageService" : "SearchManager") + "), so the parent page could " +
                "not be looked up. That is a failed lookup, not a space without that page.")
        }

        Map<String, Object> found = Cw.searchPagesByTitle(searchLookup, pageLookup, searchSpace,
            searchTitle, Cx.SEARCH_LIMIT)
        if (found.get("ok") != Boolean.TRUE) {
            return refuse(500, "pages", String.valueOf(found.get("error")))
        }

        Map<String, Object> pagePayload = new LinkedHashMap<String, Object>()
        pagePayload.put("ok", Boolean.TRUE)
        pagePayload.put("action", "pages")
        pagePayload.put("spaceKey", searchSpace)
        pagePayload.put("pages", found.get("pages"))
        pagePayload.put("truncated", found.get("truncated"))
        return answer(pagePayload)
    }

    /* ---- Page export: validate --------------------------------------------- */

    String spaceKey = Cx.str(request, "spaceKey", "")
    String title = Cx.str(request, "title", "")
    String parentRaw = Cx.str(request, "parentPageId", "").trim()
    String parentTitleRaw = Cx.str(request, "parentTitle", "").trim()

    if (spaceKey.isEmpty()) {
        return refuse(400, "validate", "No space was selected.")
    }
    if (title.isEmpty()) {
        return refuse(400, "validate", "No page title was given.")
    }
    /* Exactly one parent instruction, never two. A picked page and a typed title
     * can disagree, and guessing which one the administrator meant is how a report
     * lands somewhere nobody looks. The request is refused instead. */
    String parentProblem = Cx.parentProblem(parentRaw, parentTitleRaw, title)
    if (!parentProblem.isEmpty()) {
        return refuse(400, "validate", parentProblem)
    }
    if (title.length() > Cx.MAX_TITLE_CHARS) {
        return refuse(400, "validate", "The page title exceeds " +
            String.valueOf(Cx.MAX_TITLE_CHARS) + " characters.")
    }
    if (Cx.rowsOf(request, "sections").isEmpty()) {
        return refuse(400, "validate", "The export payload carries no section. Nothing is written.")
    }

    PageManager pageManager = null
    PageService pageService = null
    SpaceService spaceService = null
    try {
        pageManager = ComponentLocator.getComponent(PageManager.class)
        pageService = ComponentLocator.getComponent(PageService.class)
        spaceService = ComponentLocator.getComponent(SpaceService.class)
    } catch (Throwable error) {
        return refuse(500, "validate", "A required Confluence component could not be obtained: " + Db.why(error))
    }
    if (pageManager == null || pageService == null || spaceService == null) {
        return refuse(500, "validate", "A required Confluence component could not be resolved (" +
            (pageManager == null ? "PageManager" : (pageService == null ? "PageService" : "SpaceService")) + ").")
    }

    Space space = null
    try {
        space = spaceService.getKeySpaceLocator(spaceKey).getSpace()
    } catch (Exception error) {
        return refuse(500, "validate", "The space \"" + spaceKey + "\" could not be read: " +
            Cx.errorDetail(error))
    }
    if (space == null) {
        return refuse(400, "validate", "There is no space with the key \"" + spaceKey + "\".")
    }

    /* Three outcomes, kept apart in the response: no parent, a parent that was
     * found, and a parent this run created. Creating is never reported as finding -
     * an administrator who reads "found" believes the page was already there and
     * stops looking for the one that was just made. */
    Page parentPage = null
    String parentAction = "none"

    if (!parentRaw.isEmpty()) {
        long parentId = 0L
        try {
            parentId = Long.parseLong(parentRaw)
        } catch (NumberFormatException ignored) {
            return refuse(400, "validate", "The parent page ID \"" + parentRaw + "\" is not a number.")
        }
        try {
            parentPage = pageService.getIdPageLocator(parentId).getPage()
        } catch (Exception error) {
            return refuse(500, "validate", "The parent page could not be read: " + Cx.errorDetail(error))
        }
        if (parentPage == null) {
            return refuse(400, "validate", "There is no page with the ID " + parentRaw + ".")
        }
        if (!spaceKey.equalsIgnoreCase(String.valueOf(parentPage.getSpaceKey()))) {
            return refuse(400, "validate", "The parent page " + parentRaw + " sits in space \"" +
                String.valueOf(parentPage.getSpaceKey()) + "\", not in \"" + spaceKey + "\".")
        }
        parentAction = "found"
    }

    /* ---- Remark read ------------------------------------------------------- */

    /* The exact locator returns the persistence Page the remark parser and the
     * write path below both work on. */
    Page existingPage = null
    try {
        existingPage = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, title).getPage()
    } catch (Exception error) {
        return refuse(409, "read", "The existing page could not be read (" + Cx.errorDetail(error) +
            "). Nothing is written, so no remark can be lost.")
    }

    RemarkRead read = new RemarkRead()
    if (existingPage != null) {
        String existingStorage = null
        try {
            existingStorage = existingPage.getBodyAsString()
        } catch (Exception error) {
            return refuse(409, "read", "The body of the existing page could not be read (" +
                Cx.errorDetail(error) + "). Nothing is written, so no remark can be lost.")
        }
        read = Cx.parseRemarks(existingStorage)
        read.pageId = existingPage.getIdAsString()
        read.pageVersion = existingPage.getVersion()
    }

    /* Fail closed. This is the only path to a write and a FAILED read never passes
     * it: no create, no update, reported to the caller as a failure. A page that
     * would lose an administrator's own text is never produced. */
    if (!read.isWriteAllowed()) {
        Map<String, Object> refusal = new LinkedHashMap<String, Object>()
        refusal.put("ok", Boolean.FALSE)
        refusal.put("written", Boolean.FALSE)
        refusal.put("stage", "read")
        refusal.put("error", read.reason)
        refusal.put("remarkRead", read.outcome)
        refusal.put("remarkReadDetail", read.asMap())
        refusal.put("spaceKey", spaceKey)
        refusal.put("title", title)
        refusal.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Http.build(responseClass, 409,
            JsonOutput.prettyPrint(JsonOutput.toJson(refusal)), Http.JSON, null)
    }

    /* ---- Parent page from a typed title ------------------------------------ */

    /* There is no Create button. A title that was typed and never picked is
     * resolved here, in the generating request, which is the only moment at which
     * the answer is still current. It sits AFTER the fail-closed remark read on
     * purpose: a run that is about to be refused with a 409 must not leave a
     * container page behind that nothing was ever filed under.
     *
     * The exact title is re-checked immediately before the create, not only in the
     * search the browser ran earlier. That covers the page somebody else created in
     * between and the administrator who saw a hit, did not click it and generated
     * anyway. Neither produces a second page with the same title. A failed read
     * stays a failed read and never degrades into "no such page", which would be
     * answered by creating a duplicate. */
    if (parentPage == null && !parentTitleRaw.isEmpty()) {
        try {
            parentPage = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, parentTitleRaw).getPage()
        } catch (Exception error) {
            return refuse(500, "parent", "The parent page \"" + parentTitleRaw + "\" could not be looked " +
                "up in \"" + spaceKey + "\" (" + Cx.errorDetail(error) + "). That is a failed read, not a " +
                "space without that page, so nothing was created and nothing is written.")
        }

        if (parentPage != null) {
            parentAction = "found"
        } else {
            try {
                Page container = new Page()
                container.setVersion(1)
                container.setSpace(space)
                container.setTitle(parentTitleRaw)
                container.setBodyContent(new BodyContent(container, Cx.PARENT_BODY, BodyType.XHTML))
                container.setCreator(AuthenticatedUserThreadLocal.get())
                pageManager.saveContentEntity(container, DefaultSaveContext.SUPPRESS_NOTIFICATIONS)
                parentPage = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, parentTitleRaw).getPage()
            } catch (Exception error) {
                return refuse(500, "parent", "The parent page \"" + parentTitleRaw + "\" could not be " +
                    "created in \"" + spaceKey + "\" (" + Cx.errorDetail(error) + "). Nothing is written: a " +
                    "report filed at the top level of the space instead would sit where nobody looks for it.")
            }
            if (parentPage == null) {
                return refuse(500, "parent", "The parent page \"" + parentTitleRaw + "\" is not readable " +
                    "after the save, so the report has no confirmed place to go. Nothing is written.")
            }
            parentAction = "created"
        }
    }

    /* ---- Write ------------------------------------------------------------- */

    /* The instance block on the page is read from this instance, never taken from
     * the payload the browser sent back. */
    Map<String, String> identity = Cw.instanceIdentity()
    Object instanceNode = request.get("instance")
    Map<String, Object> instanceMap = instanceNode instanceof Map
        ? (Map<String, Object>) instanceNode : new LinkedHashMap<String, Object>()
    for (String field : ["baseUrl", "title", "confluenceVersion"]) {
        if (identity.get(field) != null) {
            instanceMap.put(field, identity.get(field))
        }
    }
    request.put("instance", instanceMap)

    ExportOutcome outcome = Cx.render(request, read)

    String action = existingPage == null ? "created" : "updated"
    int writtenVersion = existingPage == null ? 1 : read.pageVersion + 1
    String pageId = read.pageId

    /* The parent named in this run, and what this run does about the position of an
     * existing page. Both are decided before the write so the branches below only
     * carry it out. */
    String requestedParentId = parentPage == null ? null : parentPage.getIdAsString()
    String moveDecision = Cx.MOVE_NOT_REQUESTED
    String moveError = null
    boolean parentReadBackOk = false
    String actualParentId = null

    try {
        if (existingPage == null) {
            Page fresh = new Page()
            fresh.setVersion(1)
            fresh.setSpace(space)
            fresh.setTitle(title)
            fresh.setBodyContent(new BodyContent(fresh, outcome.storage, BodyType.XHTML))
            fresh.setCreator(AuthenticatedUserThreadLocal.get())
            if (parentPage != null) {
                /* Ancestors run from the root of the space downwards, so the parent
                 * is appended last. The create path carries the parent in the entity
                 * itself; the update path below moves an existing page instead. */
                moveDecision = Cx.MOVE_REQUESTED
                fresh.setParentPage(parentPage)
                parentPage.addChild(fresh)
                List<Page> ancestors = new ArrayList<Page>()
                List<Page> parentAncestors = parentPage.getAncestors()
                if (parentAncestors != null) {
                    ancestors.addAll(parentAncestors)
                }
                ancestors.add(parentPage)
                fresh.setAncestors(ancestors)
            }
            pageManager.saveContentEntity(fresh, DefaultSaveContext.SUPPRESS_NOTIFICATIONS)
        } else {
            /* saveContentEntity(obj, origObj, ctx) is the documented history path:
             * the modified as well as the original version of the object are handed
             * over. The fetched entity carries the modification, so its
             * pre-modification state is taken first and passed as the original. The
             * body is all this save carries: the position is a separate operation
             * and is handled right after it. */
            Page original = (Page) existingPage.clone()
            existingPage.setBodyAsString(outcome.storage)
            pageManager.saveContentEntity(existingPage, original, DefaultSaveContext.SUPPRESS_NOTIFICATIONS)

            /* A parent named in this run is applied to a page that already exists,
             * not only to one this run creates. A run that names no parent still
             * does not touch the position, so a page an administrator moved by hand
             * stays moved. movePageAsChild owns the ancestor list; it is not
             * hand-rolled here. */
            Page currentParent = null
            try {
                currentParent = existingPage.getParent()
            } catch (Exception ignored) {
                currentParent = null
            }
            moveDecision = Cx.moveDecision(requestedParentId,
                currentParent == null ? null : currentParent.getIdAsString())
            if (Cx.MOVE_REQUESTED.equals(moveDecision)) {
                try {
                    pageManager.movePageAsChild(existingPage, parentPage)
                } catch (Exception error) {
                    /* The report is written at this point. A failed move costs the
                     * position and is reported as such below; it never costs the
                     * report, and it is never swallowed either. */
                    moveError = Cx.errorDetail(error)
                }
            }
        }

        /* Read back rather than trusting the save. The id and the version that go
         * into the response are the ones the page actually carries afterwards. */
        Page stored = pageService.getTitleAndSpaceKeyPageLocator(space.getKey(), title).getPage()
        if (stored == null) {
            return refuse(500, "write", "The page could not be written: it is not readable after the save.")
        }
        pageId = stored.getIdAsString()
        writtenVersion = stored.getVersion()

        /* The position is read back too. A move that returned without throwing is a
         * report about itself, not a measurement of the tree, and the create path
         * setting an ancestor list on an entity is no different. What goes into the
         * response is the chain the page actually carries afterwards.
         *
         * A chain that cannot be read leaves parentReadBackOk false, which the
         * verdict below turns into "unknown" - never into a move that worked and
         * never into one that failed. */
        try {
            List<Page> storedAncestors = stored.getAncestors()
            List<String> ancestorIds = null
            if (storedAncestors != null) {
                ancestorIds = new ArrayList<String>()
                for (Page ancestor : storedAncestors) {
                    ancestorIds.add(ancestor == null ? null : ancestor.getIdAsString())
                }
            }
            Map<String, Object> chain = Cx.innermostAncestor(ancestorIds)
            parentReadBackOk = chain.get("measured") == Boolean.TRUE
            actualParentId = chain.get("parentId") == null ? null : chain.get("parentId").toString()
        } catch (Exception ignored) {
            parentReadBackOk = false
            actualParentId = null
        }
    } catch (Exception error) {
        return refuse(500, "write", "The page could not be written: " + Cx.errorDetail(error))
    }

    String writtenUrl = Cw.pageUrl(identity.get("baseUrl"), pageId)
    if (writtenUrl == null) {
        outcome.warnings.add("The page was written, but the base URL of this instance could not be read, " +
            "so the result carries no link to it.")
    }

    /* The measured verdict on the position. It is computed from the read-back, not
     * from the fact that a move was attempted, and a run that named no parent gets
     * a null rather than a claim it never made. */
    Map<String, Object> parentVerdict = Cx.parentOutcome(requestedParentId, parentReadBackOk,
        actualParentId, moveError)
    if (parentVerdict.get("reason") != null) {
        outcome.warnings.add(parentVerdict.get("reason").toString())
    }

    Map<String, Object> response = new LinkedHashMap<String, Object>()
    response.put("ok", Boolean.TRUE)
    response.put("written", Boolean.TRUE)
    response.put("action", action)
    response.put("spaceKey", spaceKey)
    response.put("title", title)
    response.put("pageId", pageId)
    response.put("pageVersion", Integer.valueOf(writtenVersion))
    response.put("pageUrl", writtenUrl)
    response.put("parentPageId", parentPage == null ? null : parentPage.getIdAsString())
    response.put("parentAction", parentAction)
    response.put("parentTitle", parentPage == null ? null : parentPage.getTitle())
    response.put("parentPageUrl", parentPage == null
        ? null : Cw.pageUrl(identity.get("baseUrl"), parentPage.getIdAsString()))
    response.put("parentMove", moveDecision)
    response.put("parentApplied", parentVerdict.get("applied"))
    response.put("parentAppliedReason", parentVerdict.get("reason"))
    response.put("remarkRead", read.outcome)
    response.put("remarkReadDetail", read.asMap())
    response.put("remarksRead", Integer.valueOf(outcome.remarksRead))
    response.put("remarksCarried", Integer.valueOf(outcome.remarksCarried))
    response.put("orphanedRemarks", Integer.valueOf(outcome.orphanKeys.size()))
    response.put("orphanedKeys", outcome.orphanKeys)
    response.put("warnings", outcome.warnings)

    return answer(response)
}
