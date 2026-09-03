/* =============================================================================
 * Confluence Data Center - Space information
 *
 * What this endpoint answers: how does one space look. The space header, how
 * many pages it holds, and the pages themselves with who made them and who
 * touched them last. Read from storage, never computed.
 *
 * What it reports
 *   Without a parameter: a search box and nothing else. Opening the endpoint
 *   reads ONE number, the count of spaces, and delivers no space list at all.
 *   An earlier draft shipped every space to the browser to drive the search:
 *   293 ms and 269822 bytes on the reference instance, spent before the reader
 *   had typed a character, and almost all of it thrown away.
 *
 *   find=<text>: the suggestions, answered per keystroke as JSON. Ranked in SQL
 *   so the cap cuts by the order the reader sees - exact key, then key prefix,
 *   then name prefix, then anything else.
 *
 *   space=<KEY>: the space header (name, key, creator, created), the page count
 *   and the trash count as two separate figures, and the page list - title,
 *   creator, created, last modified, last modified by.
 *
 * The one house rule this file breaks, and why
 *   Its sibling makes no request after the page is loaded (spaceConfig
 *   2426-2498). The search box here does, once per typed word. That rule exists
 *   so a REPORT still works after it has been saved or mailed; a search box is
 *   not a report and nobody mails one. Its cost here would be delivering the
 *   whole estate to answer a question about one space. The rule still holds for
 *   the per-space report, which makes no request after load.
 *
 * People are named, not keyed
 *   CONTENT.CREATOR, CONTENT.LASTMODIFIER and SPACES.CREATOR store a user KEY.
 *   USER_MAPPING turns that into a user name and CWD_USER into a display name,
 *   so the report shows "Display Name (username)" and falls back a layer at a
 *   time. Each fallback is a different fact: a user removed from the directory
 *   keeps their USER_MAPPING row, so the user name outlives the display name,
 *   and saying so is the difference between who it was and what is left of it.
 *
 *   The display name is read as a scalar AGGREGATE rather than a join. CWD_USER
 *   keys a person by user name AND directory, so an instance with LDAP beside
 *   the internal directory can hold one user name twice, and a join would then
 *   emit the same page twice. A page list that grows rows because of how users
 *   are configured is worse than one without display names.
 *
 * The page filter, measured rather than assumed
 *   CONTENT holds every page state in one table. Measured on Confluence 10.2.15
 *   over contenttype PAGE:
 *     current + prevver NOT NULL + spaceid NULL  ->      25  historical versions
 *     current + prevver NULL     + spaceid set   -> 1005070  live pages
 *     deleted + prevver NULL     + spaceid set   ->      12  trash
 *     draft                                      ->      40  drafts
 *   So a live page is: contenttype PAGE, content_status current, prevver IS
 *   NULL, and a spaceid. Historical versions carried spaceid NULL there, so the
 *   join on spaceid and the prevver test each exclude them INDEPENDENTLY. Both
 *   guards stay: neither behaviour is documented by Atlassian, and a wrong
 *   filter here does not fail loudly - it returns a plausible wrong number.
 *
 * Typing, and why it is not decoration
 *   Every map is declared with its type arguments and every value read back out
 *   of one is cast at the point of use. Without it the static type checker
 *   resolves every field access to java.lang.Object, no method on it resolves,
 *   and the file lights up red in the editor while still parsing and running.
 *
 *   ONE type error is expected and cannot be removed: the endpoint registration
 *   spaceInfo(LinkedHashMap, Closure) at the foot of this file. ScriptRunner
 *   dispatches that DSL through methodMissing at runtime, so no static checker
 *   can see it. The sibling carries the identical false positive twice.
 *
 * Discipline
 *   Read-only: every statement a SELECT through the SAL read-only executor.
 *   Every value bound, nothing interpolated into SQL, constants included.
 *   No string literal inside COALESCE against a text column (Oracle ORA-12704).
 *   A failed read is never rendered as an empty result.
 *   Every capped list announces its cap, the ordering it cut by, and the way to
 *   what was cut - as an address, not as prose.
 *   javax / jakarta neutral: the Response class is resolved at runtime.
 *
 * Parameters
 *   space=<KEY>          none      without it and without find, the search box
 *   find=<text>          none      suggestions as JSON, from 2 characters
 *   format=html|json     default html
 *   limit=<n>            default 5000, hard maximum 20000, page list only
 * ========================================================================== */

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

@BaseScript CustomEndpointDelegate delegate

class P {

    static final String VERSION = "0.1"
    static final String NA = "n/a"

    static Object duck(Object target, String method, Object[] arguments) {
        if (target == null) {
            return null
        }
        return InvokerHelper.invokeMethod(target, method, arguments)
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

    static String urlQuery(String value) {
        return value == null ? "" : URLEncoder.encode(value, "UTF-8")
    }

    /* queryParams is the JAX-RS MultivaluedMap. Naming that type would drag
     * javax.ws.rs or jakarta.ws.rs into this file, which is the one dependency
     * this endpoint exists without, so the call goes through the invoker. */
    static String param(Object queryParams, String name, String fallback) {
        Object raw = queryParams == null ? null : duck(queryParams, "getFirst", [name] as Object[])
        if (raw == null) {
            return fallback
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? fallback : value
    }

    /* A person, named the way a person is named, with the identifier that
     * actually addresses them kept alongside.
     *
     * Three layers, and each fallback is a different fact rather than a nicer
     * string. The display name lives in the directory (CWD_USER) and is what an
     * administrator recognises. The user name lives in USER_MAPPING and is what
     * they type into a picker or a permission screen. The user key is what
     * CONTENT.CREATOR and SPACES.CREATOR actually store - values such as
     * 8aaa81a1...0000 - and printing it alone would print an opaque id where the
     * reader expects a person.
     *
     * A missing display name is NOT an error: a user removed from the directory
     * keeps their USER_MAPPING row, so the user name survives the display name.
     * Saying which layer answered is the difference between "this is who it was"
     * and "this is all that is left of who it was". */
    static String user(String display, String name, String key) {
        String shown = text(display)
        String login = text(name)
        if (shown != null && login != null) {
            /* Some directories set the display name to the user name. Printing
             * "cfaysal (cfaysal)" would be noise, so the pair collapses. */
            return shown.equalsIgnoreCase(login) ? shown : shown + " (" + login + ")"
        }
        if (shown != null) {
            return shown
        }
        if (login != null) {
            return login + " (no display name in the user directory)"
        }
        String raw = text(key)
        if (raw == null) {
            return NA
        }
        return raw + " (this user key resolves to no entry in user_mapping)"
    }

    static String spaceType(Object raw) {
        String value = text(raw)
        if (value == null) {
            return NA
        }
        return value.equalsIgnoreCase("personal") ? "Personal" : "Global"
    }

    /* One place that reads a string out of a row, so no call site has to cast. */
    static String cell(Map<String, String> row, String column) {
        return row == null ? null : row.get(column)
    }
}

class Rest {

    static final String HTML = "text/html; charset=UTF-8"
    static final String JSON = "application/json; charset=UTF-8"

    static Class responseClass() {
        try {
            return Class.forName("jakarta.ws.rs.core.Response")
        } catch (ClassNotFoundException ignored) {
            return Class.forName("javax.ws.rs.core.Response")
        }
    }

    static Object build(Class type, int status, String entity, String contentType) {
        Object builder
        if (status == 200) {
            builder = P.duck(type, "ok", [entity] as Object[])
        } else {
            builder = P.duck(type, "status", [Integer.valueOf(status)] as Object[])
            builder = P.duck(builder, "entity", [entity] as Object[])
        }
        builder = P.duck(builder, "type", [contentType] as Object[])
        return P.duck(builder, "build", new Object[0])
    }
}

/* The result of one statement. A class rather than a map, so every field has a
 * declared type and no call site needs a cast: a failed read and an empty read
 * must be distinguishable without anyone remembering to check the right key. */
class Rows {

    List<Map<String, String>> rows = new ArrayList<Map<String, String>>()
    boolean truncated = false
    String failure = null
    int cap = 0

    boolean isReadable() {
        return failure == null
    }

    boolean isEmpty() {
        return rows.isEmpty()
    }

    int size() {
        return rows.size()
    }

    Map<String, String> first() {
        return rows.isEmpty() ? null : rows.get(0)
    }
}

class Sql {

    static final String EXECUTOR_FACTORY = "com.atlassian.sal.api.rdbms.TransactionalExecutorFactory"
    static final String CONNECTION_CALLBACK = "com.atlassian.sal.api.rdbms.ConnectionCallback"

    static String why(Throwable error) {
        if (error == null) {
            return null
        }
        String message = P.text(error.getMessage())
        String detail = error.getClass().getSimpleName() + (message == null ? "" : " - " + message)
        return detail.length() > 300 ? detail.substring(0, 300) + " [clamped]" : detail
    }

    /* The factory, or null with the reason. An acquisition that failed and an
     * instance that holds nothing are different answers and are never merged. */
    static Map<String, Object> factory() {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("factory", null)
        out.put("failure", null)
        try {
            Object component = ComponentLocator.getComponent(Class.forName(EXECUTOR_FACTORY))
            if (component == null) {
                out.put("failure", "The SAL read-only executor factory resolved but returned no " +
                    "component. No statement was attempted.")
            } else {
                out.put("factory", component)
            }
        } catch (Throwable error) {
            out.put("failure", "The SAL read-only executor factory could not be obtained: " +
                why(error) + ". No statement was attempted.")
        }
        return out
    }

    /* createReadOnly is what makes the read-only claim enforceable rather than a
     * matter of reviewing every statement by eye. */
    static Object withConnection(Object executorFactory, Closure body) {
        Class callbackType = Class.forName(CONNECTION_CALLBACK)
        Object executor = P.duck(executorFactory, "createReadOnly", new Object[0])
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
                        return "spaceInfo-callback"
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
        return P.duck(executor, "execute", [callback] as Object[])
    }

    /* The Confluence schema is not a public API, so a column named here can
     * vanish in an upgrade. Read the catalogue rather than running the statement
     * and seeing what happens: a missing column has to produce a message naming
     * it, never a report that says nothing is there. */
    static String shape(Connection connection, String table, List<String> required) {
        try {
            Set<String> present = new LinkedHashSet<String>()
            DatabaseMetaData meta = connection.getMetaData()
            /* Identifier case is a property of the database, not of this file.
             * Postgres folds unquoted names down, other engines fold up, so both
             * spellings are asked for and the first that answers wins. */
            List<String> candidates = new ArrayList<String>()
            candidates.add(table.toLowerCase(Locale.ROOT))
            candidates.add(table.toUpperCase(Locale.ROOT))
            for (String candidate : candidates) {
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
            List<String> missing = new ArrayList<String>()
            for (String column : required) {
                if (!present.contains(column.toLowerCase(Locale.ROOT))) {
                    missing.add(column)
                }
            }
            if (missing.isEmpty()) {
                return null
            }
            return "The table " + table + " does not carry " +
                (missing.size() == 1 ? "the column " : "the columns ") + missing.join(", ") +
                " on this instance. Nothing was read rather than reporting an empty result."
        } catch (Throwable error) {
            return "The database catalogue could not be read: " + why(error) +
                " The columns could not be verified, so nothing was read."
        }
    }

    /* A read that failed and a read that found nothing return different states on
     * the same object. An accessor that answers the same way for both is how a
     * failed read turns into a proven absence. */
    static Rows query(Connection connection, String statementText, List<String> arguments,
                      List<String> columns, int cap) {
        Rows out = new Rows()
        out.cap = cap
        PreparedStatement statement = null
        try {
            statement = connection.prepareStatement(statementText)
            for (int index = 0; index < arguments.size(); index++) {
                statement.setString(index + 1, arguments.get(index))
            }
            ResultSet results = statement.executeQuery()
            try {
                while (results.next()) {
                    /* Ask for one more row than fits, so "exactly at the cap" and
                     * "more than the cap" are told apart. A report that cannot
                     * tell those apart announces a truncation that did not
                     * happen, or hides one that did. */
                    if (out.rows.size() >= cap) {
                        out.truncated = true
                        break
                    }
                    Map<String, String> row = new LinkedHashMap<String, String>()
                    for (String column : columns) {
                        row.put(column, results.getString(column))
                    }
                    out.rows.add(row)
                }
            } finally {
                results.close()
            }
        } catch (Throwable error) {
            out.failure = "The statement failed: " + why(error)
        } finally {
            if (statement != null) {
                try {
                    statement.close()
                } catch (Throwable ignored) {
                    /* A close that fails changes nothing about the rows already
                     * read and must not turn a successful read into a failed
                     * one. */
                }
            }
        }
        return out
    }
}

class Q {

    /* Bound, never pasted. A constant is no exception to that rule. */
    static final String PAGE_TYPE = "PAGE"
    static final String CURRENT = "current"
    static final String DELETED = "deleted"

    static final int DEFAULT_CAP = 5000
    static final int MAX_CAP = 20000
    static final String PAGE_ORDER = "last modified date, newest first, then content id"

    static final List<String> CONTENT_COLUMNS = [
        "contentid", "contenttype", "title", "creator", "creationdate",
        "lastmodifier", "lastmoddate", "prevver", "content_status", "spaceid"]

    static final List<String> SPACE_COLUMNS = [
        "spaceid", "spacekey", "spacename", "spacetype", "spacestatus", "creator", "creationdate"]

    static final List<String> MAPPING_COLUMNS = ["user_key", "username"]

    static final List<String> DIRECTORY_COLUMNS = ["lower_user_name", "display_name"]

    /* The display name is fetched as a scalar AGGREGATE, not as a join, and the
     * distinction is not stylistic.
     *
     * USER_MAPPING keys a person by user name; CWD_USER keys them by user name
     * AND directory. A Confluence instance with an LDAP directory alongside the
     * internal one can therefore hold the same user name twice. A plain join
     * would then emit that page twice, and a page list that silently grows rows
     * because of how users are configured is worse than one without display
     * names at all. MAX over the matches returns exactly one value whatever the
     * directory count, so the row count of the outer statement cannot change.
     *
     * The cost of that safety: on an instance where one user name really does
     * exist in two directories with two different display names, the one shown
     * is the alphabetically last rather than the one Confluence itself would
     * resolve through directory order. The report says so rather than implying
     * a precision it does not have.
     *
     * Measured on the reference instance: 4911 users in ONE directory with no
     * duplicate user name, and the page list for the largest space went from
     * 65 ms to 76 ms with both subqueries added. */
    static final String CREATOR_DISPLAY =
        "(SELECT MAX(dc.display_name) FROM cwd_user dc " +
        "WHERE dc.lower_user_name = LOWER(uc.username)) AS creatordisplay"

    static final String MODIFIER_DISPLAY =
        "(SELECT MAX(dm.display_name) FROM cwd_user dm " +
        "WHERE dm.lower_user_name = LOWER(um.username)) AS modifierdisplay"

    static final String SPACE_SQL =
        "SELECT s.spacekey, s.spacename, s.spacetype, s.spacestatus, " +
        "s.creator, s.creationdate, uc.username AS creatorname, " + CREATOR_DISPLAY + " " +
        "FROM spaces s " +
        "LEFT JOIN user_mapping uc ON uc.user_key = s.creator " +
        "WHERE s.spacekey = ?"

    static final List<String> SPACE_READ = [
        "spacekey", "spacename", "spacetype", "spacestatus", "creator", "creationdate",
        "creatorname", "creatordisplay"]

    /* One pass, two figures. COUNT(CASE ... THEN 1 END) returns 0 over zero rows
     * without any COALESCE, so a space with no page yields a measured zero and no
     * string literal comes near a text column. */
    static final String COUNT_SQL =
        "SELECT COUNT(CASE WHEN c.content_status = ? THEN 1 END) AS livepages, " +
        "COUNT(CASE WHEN c.content_status = ? THEN 1 END) AS trashpages " +
        "FROM content c " +
        "JOIN spaces s ON s.spaceid = c.spaceid " +
        "WHERE s.spacekey = ? AND c.contenttype = ? AND c.prevver IS NULL " +
        "AND (c.content_status = ? OR c.content_status = ?)"

    static final List<String> COUNT_READ = ["livepages", "trashpages"]

    /* contentid DESC is the tiebreak that makes the ordering total, so a second
     * run cuts at the same place. No LIMIT and no NULLS LAST: neither is portable
     * across PostgreSQL and Oracle; the cap is enforced while reading. */
    static final String PAGES_SQL =
        "SELECT c.contentid, c.title, c.creator, c.creationdate, c.lastmodifier, c.lastmoddate, " +
        "uc.username AS creatorname, um.username AS modifiername, " +
        CREATOR_DISPLAY + ", " + MODIFIER_DISPLAY + " " +
        "FROM content c " +
        "JOIN spaces s ON s.spaceid = c.spaceid " +
        "LEFT JOIN user_mapping uc ON uc.user_key = c.creator " +
        "LEFT JOIN user_mapping um ON um.user_key = c.lastmodifier " +
        "WHERE s.spacekey = ? AND c.contenttype = ? AND c.content_status = ? AND c.prevver IS NULL " +
        "ORDER BY c.lastmoddate DESC, c.contentid DESC"

    static final List<String> PAGES_READ = [
        "contentid", "title", "creator", "creationdate", "lastmodifier", "lastmoddate",
        "creatorname", "modifiername", "creatordisplay", "modifierdisplay"]

    /* How many spaces exist at all. One trivial statement, so that opening the
     * endpoint costs almost nothing and still tells the reader the size of what
     * they are searching. */
    static final String SPACE_TOTAL_SQL = "SELECT COUNT(*) AS spaces FROM spaces s"

    static final List<String> SPACE_TOTAL_READ = ["spaces"]

    /* The suggestion list, answered per keystroke rather than by shipping the
     * estate to the browser.
     *
     * The endpoint used to run a GROUP BY over every page of the instance and
     * embed all 5038 spaces in the page: 293 ms and 269822 bytes on the
     * reference instance, before the reader had typed anything. Almost all of it
     * was thrown away, because a picker shows a dozen rows. This statement is
     * measured at 80 ms for the broad prefix "ent", counts included.
     *
     * The ranking is done in SQL, so the cap below cuts by the order the reader
     * sees. Without it, typing DEV buries the space actually called DEV under
     * every name that happens to contain those letters.
     *
     * Every LIKE pattern is BOUND, never pasted: the percent signs are built in
     * Groovy and travel as a parameter. A literal inside the statement against a
     * text column is the ORA-12704 trap that already broke the sibling on a
     * customer instance.
     *
     * No status restriction. An archived space is still a space an
     * administrator asks about, and filtering it out here would mean that typing
     * its exact key offers nothing - a picker that hides what you named is worse
     * than one that shows it and says what it is. */
    static final String SUGGEST_SQL =
        "SELECT s.spacekey, s.spacename, s.spacestatus, " +
        "(SELECT COUNT(*) FROM content c WHERE c.spaceid = s.spaceid " +
        "AND c.contenttype = ? AND c.content_status = ? AND c.prevver IS NULL) AS pages " +
        "FROM spaces s " +
        "WHERE LOWER(s.spacekey) LIKE ? OR LOWER(s.spacename) LIKE ? " +
        "ORDER BY CASE WHEN LOWER(s.spacekey) = ? THEN 0 " +
        "WHEN LOWER(s.spacekey) LIKE ? THEN 1 " +
        "WHEN LOWER(s.spacename) LIKE ? THEN 2 ELSE 3 END, LOWER(s.spacekey)"

    static final List<String> SUGGEST_READ = ["spacekey", "spacename", "spacestatus", "pages"]

    /* Shown at once. One more than this is read, so "exactly this many" and
     * "more than this" are told apart and the extra can be announced. */
    static final int SUGGEST_CAP = 12

    /* Below this, no statement is issued at all. A single character matches most
     * of the estate, which is a slow way of answering nothing useful. */
    static final int MIN_QUERY = 2
}

class View {

    static String head(String title) {
        StringBuilder out = new StringBuilder()
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        out.append("<title>").append(P.html(title)).append("</title>\n<style>\n")
        out.append("body{font:14px/1.5 -apple-system,Segoe UI,Arial,sans-serif;margin:0;")
        out.append("padding:24px;background:#f7f8fa;color:#172b4d}\n")
        out.append("h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:24px 0 8px}\n")
        out.append(".sub{color:#5e6c84;margin:0 0 20px}\n")
        out.append(".card{background:#fff;border:1px solid #dfe1e6;border-radius:6px;")
        out.append("padding:16px;margin-bottom:16px}\n")
        out.append("table{border-collapse:collapse;width:100%}\n")
        out.append("th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #ebecf0;")
        out.append("vertical-align:top}\n")
        out.append("th{background:#f4f5f7;font-weight:600;white-space:nowrap}\n")
        out.append("td.num{text-align:right;font-variant-numeric:tabular-nums}\n")
        out.append(".warn{background:#fffae6;border:1px solid #ffc400;border-radius:4px;")
        out.append("padding:10px 12px;margin-bottom:12px}\n")
        out.append(".bad{background:#ffebe6;border:1px solid #ff5630;border-radius:4px;")
        out.append("padding:10px 12px;margin-bottom:12px}\n")
        out.append(".muted{color:#5e6c84}\ninput{padding:6px 8px;border:1px solid #dfe1e6;")
        out.append("border-radius:4px;width:280px}\na{color:#0052cc}\n")
        out.append(".pick{position:relative;max-width:560px}\n")
        out.append(".pick input{width:100%;font-size:15px;padding:9px 10px}\n")
        out.append(".sug{position:absolute;left:0;right:0;top:100%;z-index:5;background:#fff;")
        out.append("border:1px solid #dfe1e6;border-radius:0 0 4px 4px;box-shadow:0 4px 8px ")
        out.append("rgba(9,30,66,.15);max-height:340px;overflow:auto}\n")
        out.append(".sug .row{display:flex;gap:10px;align-items:baseline;padding:7px 10px;")
        out.append("cursor:pointer;border-bottom:1px solid #f4f5f7}\n")
        out.append(".sug .row:hover,.sug .row.on{background:#deebff}\n")
        out.append(".sug .k{font-weight:600;min-width:104px}\n")
        out.append(".sug .nm{flex:1;color:#172b4d}\n")
        out.append(".sug .ct{color:#5e6c84;white-space:nowrap;font-variant-numeric:tabular-nums}\n")
        out.append(".sug .more{padding:7px 10px;color:#5e6c84}\n")
        out.append(".sug .st{background:#dfe1e6;border-radius:3px;padding:0 5px;font-size:11px;")
        out.append("margin-left:6px;color:#42526e;text-transform:lowercase}\n")
        out.append("</style>\n</head>\n<body>\n")
        return out.toString()
    }

    static String foot(long elapsed) {
        return "<p class=\"muted\">Space information v" + P.VERSION +
            " - read-only - " + elapsed + " ms</p>\n</body>\n</html>"
    }

    /* A failed read says so and renders NO table. An empty table under a banner
     * reads as "this space holds nothing", which is a different claim. */
    static String failure(String stage, String reason) {
        return "<div class=\"bad\"><strong>" + P.html(stage) + " could not be read.</strong> " +
            P.html(reason) + "</div>\n"
    }

    /* The filter runs over the rows already delivered, so the page makes no
     * request after it is loaded and keeps working once it has been saved. */
    static String filterScript(String inputId, String bodyId, String countId, String noun) {
        StringBuilder out = new StringBuilder()
        out.append("<script>\nfunction f_").append(bodyId).append("(){")
        out.append("var t=document.getElementById('").append(inputId).append("').value.toLowerCase(),")
        out.append("r=document.getElementById('").append(bodyId).append("').rows,m=0;\n")
        out.append("for(var i=0;i<r.length;i++){var s=!t||r[i].getAttribute('data-find')")
        out.append(".indexOf(t)>-1;r[i].style.display=s?'':'none';if(s)m++;}\n")
        out.append("document.getElementById('").append(countId).append("').textContent=")
        out.append("t?(m+' of '+r.length+' ").append(noun).append(" match'):")
        out.append("(r.length+' ").append(noun).append("');}\n")
        out.append("f_").append(bodyId).append("();\n</script>\n")
        return out.toString()
    }
}

spaceInfo(
    httpMethod: "GET",
    groups: ["confluence-administrators"]
) { queryParams, body ->

    long started = System.currentTimeMillis()
    Class rest = Rest.responseClass()

    String spaceKey = P.param(queryParams, "space", null)
    String format = P.param(queryParams, "format", "html").toLowerCase(Locale.ROOT)

    /* Too short a needle is refused before a connection is opened at all, so the
     * cheapest possible answer costs no statement. */
    String find = P.param(queryParams, "find", null)
    if (find != null) {
        find = find.trim()
        if (find.length() < Q.MIN_QUERY) {
            Map<String, Object> shortPayload = new LinkedHashMap<String, Object>()
            shortPayload.put("ok", Boolean.TRUE)
            shortPayload.put("tooShort", Boolean.TRUE)
            shortPayload.put("minimum", Integer.valueOf(Q.MIN_QUERY))
            shortPayload.put("hits", new ArrayList<Map<String, Object>>())
            return Rest.build(rest, 200,
                JsonOutput.prettyPrint(JsonOutput.toJson(shortPayload)), Rest.JSON)
        }
    }

    int cap = Q.DEFAULT_CAP
    String rawLimit = P.param(queryParams, "limit", null)
    if (rawLimit != null) {
        try {
            cap = Math.max(1, Math.min(Q.MAX_CAP, Integer.parseInt(rawLimit)))
        } catch (NumberFormatException ignored) {
            /* An unreadable limit falls back to the default rather than refusing
             * the request; the page states the cap it actually used. */
            cap = Q.DEFAULT_CAP
        }
    }

    def refuse = { int status, String stage, String reason ->
        if (format == "json") {
            Map<String, Object> payload = new LinkedHashMap<String, Object>()
            payload.put("ok", Boolean.FALSE)
            payload.put("stage", stage)
            payload.put("error", reason)
            payload.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
            return Rest.build(rest, status,
                JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Rest.JSON)
        }
        return Rest.build(rest, status,
            View.head("Space information") + "<h1>Space information</h1>\n" +
            View.failure(stage, reason) + View.foot(System.currentTimeMillis() - started),
            Rest.HTML)
    }

    Map<String, Object> executor = Sql.factory()
    Object executorFactory = executor.get("factory")
    if (executorFactory == null) {
        return refuse(500, "The database executor", String.valueOf(executor.get("failure")) +
            " That is a failed acquisition, not an instance without spaces.")
    }

    Map<String, Object> result
    try {
        result = (Map<String, Object>) Sql.withConnection(executorFactory) { Connection connection ->

            Map<String, Object> answer = new LinkedHashMap<String, Object>()

            String problem = Sql.shape(connection, "content", Q.CONTENT_COLUMNS)
            if (problem == null) {
                problem = Sql.shape(connection, "spaces", Q.SPACE_COLUMNS)
            }
            if (problem == null) {
                problem = Sql.shape(connection, "user_mapping", Q.MAPPING_COLUMNS)
            }
            if (problem == null) {
                problem = Sql.shape(connection, "cwd_user", Q.DIRECTORY_COLUMNS)
            }
            if (problem != null) {
                answer.put("ok", Boolean.FALSE)
                answer.put("stage", "The database schema")
                answer.put("error", problem)
                return answer
            }

            /* The suggestion request. It is the same endpoint rather than a
             * second one so that the group gate, the read-only executor and the
             * shape check are the ones already established here. */
            if (find != null) {
                String needle = find.toLowerCase(Locale.ROOT)
                List<String> suggestArgs = [
                    Q.PAGE_TYPE, Q.CURRENT,
                    "%" + needle + "%", "%" + needle + "%",
                    needle, needle + "%", needle + "%"]
                Rows hits = Sql.query(connection, Q.SUGGEST_SQL, suggestArgs,
                    Q.SUGGEST_READ, Q.SUGGEST_CAP)
                if (!hits.isReadable()) {
                    answer.put("ok", Boolean.FALSE)
                    answer.put("stage", "The space search")
                    answer.put("error", hits.failure +
                        " That is a failed read, not a search without matches.")
                    return answer
                }
                answer.put("ok", Boolean.TRUE)
                answer.put("kind", "suggest")
                answer.put("hits", hits)
                return answer
            }

            if (spaceKey == null) {
                /* Opening the endpoint reads ONE number and nothing else. The
                 * space list is not delivered here: it is answered per keystroke
                 * by the branch above. */
                Rows total = Sql.query(connection, Q.SPACE_TOTAL_SQL,
                    new ArrayList<String>(), Q.SPACE_TOTAL_READ, 2)
                answer.put("ok", Boolean.TRUE)
                answer.put("kind", "picker")
                answer.put("total", total)
                return answer
            }

            List<String> headerArgs = [spaceKey]
            Rows header = Sql.query(connection, Q.SPACE_SQL, headerArgs, Q.SPACE_READ, 2)
            if (!header.isReadable()) {
                answer.put("ok", Boolean.FALSE)
                answer.put("stage", "The space row")
                answer.put("error", header.failure + " Whether this space exists is UNKNOWN.")
                return answer
            }
            if (header.isEmpty()) {
                /* The statement ran and matched nothing. That is a different
                 * answer from a read that failed, and it is the only one of the
                 * two that justifies a 404. */
                answer.put("ok", Boolean.FALSE)
                answer.put("notFound", Boolean.TRUE)
                answer.put("stage", "The space")
                answer.put("error", "No row in SPACES carries the key \"" + spaceKey +
                    "\". The statement ran and matched nothing.")
                return answer
            }

            List<String> countArgs = [Q.CURRENT, Q.DELETED, spaceKey, Q.PAGE_TYPE, Q.CURRENT, Q.DELETED]
            Rows counts = Sql.query(connection, Q.COUNT_SQL, countArgs, Q.COUNT_READ, 2)

            List<String> pageArgs = [spaceKey, Q.PAGE_TYPE, Q.CURRENT]
            Rows pages = Sql.query(connection, Q.PAGES_SQL, pageArgs, Q.PAGES_READ, cap)

            answer.put("ok", Boolean.TRUE)
            answer.put("kind", "space")
            answer.put("space", header.first())
            answer.put("counts", counts)
            answer.put("pages", pages)
            return answer
        }
    } catch (Throwable error) {
        return refuse(500, "The read-only connection", Sql.why(error) +
            ". That is a failed read, not an empty instance.")
    }

    if (result == null) {
        return refuse(500, "The read-only executor",
            "The executor returned no result at all, so nothing was read. " +
            "That is a failed read, not an empty instance.")
    }
    if (!Boolean.TRUE.equals(result.get("ok"))) {
        int status = Boolean.TRUE.equals(result.get("notFound")) ? 404 : 500
        return refuse(status, String.valueOf(result.get("stage")), String.valueOf(result.get("error")))
    }

    long elapsed = System.currentTimeMillis() - started

    /* ---- The estate list ------------------------------------------------- */

    /* ---- The suggestion answer, one keystroke ----------------------------- */

    if ("suggest".equals(result.get("kind"))) {
        Rows hits = (Rows) result.get("hits")
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>()
        for (Map<String, String> row : hits.rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>()
            item.put("key", P.cell(row, "spacekey"))
            item.put("name", P.cell(row, "spacename"))
            item.put("status", P.cell(row, "spacestatus"))
            item.put("pages", P.cell(row, "pages"))
            items.add(item)
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.TRUE)
        payload.put("hits", items)
        /* "There are more" is stated, never implied by a list that simply stops. */
        payload.put("more", Boolean.valueOf(hits.truncated))
        payload.put("executionMs", Long.valueOf(elapsed))
        return Rest.build(rest, 200,
            JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Rest.JSON)
    }

    /* ---- The picker ------------------------------------------------------- */

    if ("picker".equals(result.get("kind"))) {
        Rows total = (Rows) result.get("total")
        String spaceCount = total.isReadable() && !total.isEmpty()
            ? P.orNa(P.cell(total.first(), "spaces")) : null

        if (format == "json") {
            Map<String, Object> payload = new LinkedHashMap<String, Object>()
            payload.put("ok", Boolean.TRUE)
            payload.put("reportVersion", P.VERSION)
            payload.put("spaces", spaceCount)
            payload.put("usage", "Add find=<text> for suggestions, or space=<KEY> for one space.")
            payload.put("executionMs", Long.valueOf(elapsed))
            return Rest.build(rest, 200,
                JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Rest.JSON)
        }

        StringBuilder out = new StringBuilder(View.head("Space information"))
        out.append("<h1>Space information</h1>\n<p class=\"sub\">")
        if (spaceCount == null) {
            /* The count could not be read. That costs the number and nothing
             * else: the search below works either way, so the page says what
             * happened rather than printing a zero it did not measure. */
            out.append("The number of spaces could not be read, so it is not shown. ")
            out.append("The search below is unaffected.")
        } else {
            out.append(P.html(spaceCount)).append(" spaces on this instance.")
        }
        out.append("</p>\n")

        out.append("<div class=\"card\">\n")
        out.append("<label for=\"q\"><strong>Space</strong></label>\n")
        out.append("<div class=\"pick\">\n")
        out.append("<input id=\"q\" autocomplete=\"off\" autofocus ")
        out.append("placeholder=\"Type a space key or name\">\n")
        out.append("<div id=\"sug\" class=\"sug\" hidden></div>\n")
        out.append("</div>\n")
        out.append("<p class=\"muted\">Suggestions are looked up as you type, from ")
        out.append("at least ").append(Q.MIN_QUERY).append(" characters. The space list is ")
        out.append("NOT delivered with this page: on this instance that would be a group ")
        out.append("over every page of every space before you have typed anything. ")
        out.append("Arrow keys to move, Enter to open, Escape to close.</p>\n")
        out.append("</div>\n")

        out.append("<script>\n")
        out.append("var q=document.getElementById('q'),sg=document.getElementById('sug'),")
        out.append("sel=-1,cur=[],timer=null,seq=0;\n")
        out.append("function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;')")
        out.append(".replace(/</g,'&lt;').replace(/>/g,'&gt;');}\n")
        out.append("function show(h){sg.innerHTML=h;sg.hidden=false;}\n")
        out.append("function render(d){var h='';cur=[];\n")
        out.append("if(d.tooShort){sg.hidden=true;return;}\n")
        out.append("if(d.ok===false){show('<div class=\"more\">'+esc(d.error||")
        out.append("'The search could not be run.')+'</div>');return;}\n")
        out.append("var hits=d.hits||[];\n")
        out.append("for(var i=0;i<hits.length;i++){var e=hits[i];cur.push(e);\n")
        /* A status other than CURRENT is shown rather than filtered out. An
         * archived space is still one an administrator asks about, and a picker
         * that hides what you typed is worse than one that labels it. */
        out.append("var st=(e.status&&e.status.toUpperCase()!=='CURRENT')?")
        out.append("' <span class=\"st\">'+esc(e.status)+'</span>':'';\n")
        out.append("h+='<div class=\"row\" data-i=\"'+i+'\" onmousedown=\"go('+i+')\">'+\n")
        out.append("'<span class=\"k\">'+esc(e.key)+'</span><span class=\"nm\">'+esc(e.name)+")
        out.append("st+'</span><span class=\"ct\">'+esc(e.pages)+' pages</span></div>';}\n")
        out.append("if(d.more)h+='<div class=\"more\">More match than are shown. ")
        out.append("Type further to narrow it.</div>';\n")
        out.append("if(!hits.length)h='<div class=\"more\">No space matches that.</div>';\n")
        out.append("show(h);sel=-1;}\n")
        /* Every answer carries the sequence number of the keystroke that asked
         * for it. A slow reply from an earlier keystroke is dropped instead of
         * overwriting the list the reader is looking at. */
        out.append("function ask(){var t=q.value.trim();if(t.length<").append(Q.MIN_QUERY)
        out.append("){sg.hidden=true;cur=[];return;}\nvar mine=++seq;\n")
        out.append("fetch('?find='+encodeURIComponent(t)+'&format=json',")
        out.append("{headers:{'Accept':'application/json'},credentials:'same-origin'})\n")
        out.append(".then(function(r){return r.json();})\n")
        out.append(".then(function(d){if(mine===seq)render(d);})\n")
        out.append(".catch(function(){if(mine===seq)show('<div class=\"more\">")
        out.append("The search request failed. That is a failed lookup, not an absent space.")
        out.append("</div>');});}\n")
        /* Debounced, so a typed word is one request rather than one per letter. */
        out.append("function later(){clearTimeout(timer);timer=setTimeout(ask,180);}\n")
        out.append("function go(i){if(cur[i])location.search='?space='+")
        out.append("encodeURIComponent(cur[i].key);}\n")
        out.append("function mark(){var r=sg.querySelectorAll('.row');\n")
        out.append("for(var i=0;i<r.length;i++)r[i].className=(i===sel)?'row on':'row';}\n")
        out.append("q.addEventListener('input',later);\n")
        out.append("q.addEventListener('keydown',function(e){\n")
        out.append("if(e.key==='ArrowDown'){sel=Math.min(sel+1,cur.length-1);mark();")
        out.append("e.preventDefault();}\n")
        out.append("else if(e.key==='ArrowUp'){sel=Math.max(sel-1,0);mark();e.preventDefault();}\n")
        /* Enter with nothing highlighted opens the top hit, which the SQL
         * ranking puts at the exact key when one was typed. Enter on an empty
         * box does nothing rather than guessing a space. */
        out.append("else if(e.key==='Enter'){e.preventDefault();")
        out.append("if(sel>-1)go(sel);else if(cur.length)go(0);}\n")
        out.append("else if(e.key==='Escape'){sg.hidden=true;}});\n")
        out.append("q.addEventListener('blur',function(){")
        out.append("setTimeout(function(){sg.hidden=true;},150);});\n")
        out.append("q.addEventListener('focus',function(){if(cur.length)sg.hidden=false;});\n")
        out.append("</script>\n")
        out.append(View.foot(elapsed))
        return Rest.build(rest, 200, out.toString(), Rest.HTML)
    }

    /* ---- One space -------------------------------------------------------- */

    Map<String, String> space = (Map<String, String>) result.get("space")
    Rows counts = (Rows) result.get("counts")
    Rows pages = (Rows) result.get("pages")
    Map<String, String> countRow = counts.isReadable() ? counts.first() : null
    String countProblem = counts.isReadable()
        ? (countRow == null
            ? "The aggregate returned no row, which an aggregate cannot do. Nothing is claimed."
            : null)
        : counts.failure

    if (format == "json") {
        Map<String, Object> spaceNode = new LinkedHashMap<String, Object>()
        spaceNode.put("key", P.cell(space, "spacekey"))
        spaceNode.put("name", P.cell(space, "spacename"))
        spaceNode.put("type", P.spaceType(P.cell(space, "spacetype")))
        spaceNode.put("status", P.cell(space, "spacestatus"))
        spaceNode.put("createdBy", P.user(P.cell(space, "creatordisplay"), P.cell(space, "creatorname"), P.cell(space, "creator")))
        spaceNode.put("created", P.cell(space, "creationdate"))

        Map<String, Object> countNode = new LinkedHashMap<String, Object>()
        if (countProblem != null) {
            countNode.put("readable", Boolean.FALSE)
            countNode.put("error", countProblem)
        } else {
            countNode.put("readable", Boolean.TRUE)
            countNode.put("currentPages", P.cell(countRow, "livepages"))
            countNode.put("trashPages", P.cell(countRow, "trashpages"))
        }

        Map<String, Object> pageNode = new LinkedHashMap<String, Object>()
        if (!pages.isReadable()) {
            pageNode.put("readable", Boolean.FALSE)
            pageNode.put("error", pages.failure)
        } else {
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>()
            for (Map<String, String> row : pages.rows) {
                Map<String, Object> item = new LinkedHashMap<String, Object>()
                item.put("contentId", P.cell(row, "contentid"))
                item.put("title", P.cell(row, "title"))
                item.put("createdBy", P.user(P.cell(row, "creatordisplay"), P.cell(row, "creatorname"), P.cell(row, "creator")))
                item.put("created", P.cell(row, "creationdate"))
                item.put("lastModifiedBy", P.user(P.cell(row, "modifierdisplay"), P.cell(row, "modifiername"), P.cell(row, "lastmodifier")))
                item.put("lastModified", P.cell(row, "lastmoddate"))
                items.add(item)
            }
            pageNode.put("readable", Boolean.TRUE)
            pageNode.put("truncated", Boolean.valueOf(pages.truncated))
            pageNode.put("cap", Integer.valueOf(pages.cap))
            pageNode.put("order", Q.PAGE_ORDER)
            pageNode.put("items", items)
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.TRUE)
        payload.put("reportVersion", P.VERSION)
        payload.put("space", spaceNode)
        payload.put("counts", countNode)
        payload.put("pages", pageNode)
        payload.put("executionMs", Long.valueOf(elapsed))
        return Rest.build(rest, 200, JsonOutput.prettyPrint(JsonOutput.toJson(payload)), Rest.JSON)
    }

    StringBuilder out = new StringBuilder(
        View.head("Space information - " + P.orNa(P.cell(space, "spacekey"))))
    out.append("<h1>").append(P.html(P.orNa(P.cell(space, "spacename")))).append(" (")
    out.append(P.html(P.orNa(P.cell(space, "spacekey")))).append(")</h1>\n")
    out.append("<p class=\"sub\"><a href=\"?\">Back to all spaces</a></p>\n")

    out.append("<div class=\"card\">\n<h2>Space</h2>\n<table>\n")
    out.append("<tr><th>Key</th><td>")
    out.append(P.html(P.orNa(P.cell(space, "spacekey")))).append("</td></tr>\n")
    out.append("<tr><th>Name</th><td>")
    out.append(P.html(P.orNa(P.cell(space, "spacename")))).append("</td></tr>\n")
    out.append("<tr><th>Type</th><td>")
    out.append(P.html(P.spaceType(P.cell(space, "spacetype")))).append("</td></tr>\n")
    out.append("<tr><th>Status</th><td>")
    out.append(P.html(P.orNa(P.cell(space, "spacestatus")))).append("</td></tr>\n")
    out.append("<tr><th>Created by</th><td>")
    out.append(P.html(P.user(P.cell(space, "creatordisplay"), P.cell(space, "creatorname"), P.cell(space, "creator")))).append("</td></tr>\n")
    out.append("<tr><th>Created</th><td>")
    out.append(P.html(P.orNa(P.cell(space, "creationdate")))).append("</td></tr>\n")
    out.append("</table>\n</div>\n")

    out.append("<div class=\"card\">\n<h2>Page counts</h2>\n")
    if (countProblem != null) {
        out.append(View.failure("The page counts", countProblem))
    } else {
        out.append("<table>\n<tr><th>Current pages</th><td class=\"num\">")
        out.append(P.html(P.orNa(P.cell(countRow, "livepages")))).append("</td></tr>\n")
        out.append("<tr><th>Pages in the trash</th><td class=\"num\">")
        out.append(P.html(P.orNa(P.cell(countRow, "trashpages")))).append("</td></tr>\n</table>\n")
    }
    out.append("<p class=\"muted\">Counted: content of type page, status current or deleted, ")
    out.append("no previous version, in this space. Historical versions, drafts, blog posts, ")
    out.append("comments and attachments are not counted. Read from the CONTENT table, which ")
    out.append("is not a public API. Not reconciled against the figure the Confluence ")
    out.append("interface shows.</p>\n</div>\n")

    out.append("<div class=\"card\">\n<h2>Pages</h2>\n")
    if (!pages.isReadable()) {
        out.append(View.failure("The page list",
            pages.failure + " That is a failed read, not a space without pages."))
    } else if (pages.isEmpty()) {
        out.append("<p>No current page is stored for this space. ")
        out.append("The read succeeded and returned nothing.</p>\n")
    } else {
        if (pages.truncated) {
            /* The way out is shown as the address that takes it, not described.
             * "Raise the limit parameter" asks the reader to construct a URL
             * from prose while they are looking at a page that could just carry
             * it. The next step up is offered rather than the maximum, because
             * the count immediately above says how many pages there actually
             * are and jumping straight to 20000 on a space that holds 5100 is a
             * page nobody wanted. */
            String key = P.orNa(P.cell(space, "spacekey"))
            int next = Math.min(Q.MAX_CAP, pages.cap * 2)
            out.append("<div class=\"warn\"><strong>This list is shortened.</strong> ")
            out.append("It carries ").append(pages.cap).append(" pages ordered by ")
            out.append(Q.PAGE_ORDER).append(", which is not the number that exists. ")
            out.append("The pages past the cut are the ones modified longest ago.")
            if (pages.cap >= Q.MAX_CAP) {
                out.append(" This is already the highest limit this endpoint accepts (")
                out.append(Q.MAX_CAP).append("). Open the page tree of the space for the rest.")
            } else {
                out.append(" Raise it: <a href=\"?space=").append(P.urlQuery(key))
                out.append("&amp;limit=").append(next).append("\">")
                out.append("?space=").append(P.html(key)).append("&amp;limit=").append(next)
                out.append("</a>")
                if (next < Q.MAX_CAP) {
                    out.append(", up to <a href=\"?space=").append(P.urlQuery(key))
                    out.append("&amp;limit=").append(Q.MAX_CAP).append("\">limit=")
                    out.append(Q.MAX_CAP).append("</a>")
                }
                out.append(".")
            }
            out.append("</div>\n")
        }
        out.append("<input id=\"pq\" placeholder=\"Filter by title\" oninput=\"f_pb()\">\n")
        out.append("<p class=\"muted\" id=\"pn\"></p>\n")
        out.append("<table>\n<thead><tr><th>Title</th><th>Created by</th><th>Created</th>")
        out.append("<th>Last modified</th><th>Last modified by</th></tr></thead>\n")
        out.append("<tbody id=\"pb\">\n")
        for (Map<String, String> row : pages.rows) {
            String title = P.orNa(P.cell(row, "title"))
            out.append("<tr data-find=\"").append(P.html(title.toLowerCase(Locale.ROOT))).append("\">")
            out.append("<td>").append(P.html(title)).append(" <span class=\"muted\">id ")
            out.append(P.html(P.orNa(P.cell(row, "contentid")))).append("</span></td>")
            out.append("<td>")
            out.append(P.html(P.user(P.cell(row, "creatordisplay"), P.cell(row, "creatorname"), P.cell(row, "creator")))).append("</td>")
            out.append("<td>").append(P.html(P.orNa(P.cell(row, "creationdate")))).append("</td>")
            out.append("<td>").append(P.html(P.orNa(P.cell(row, "lastmoddate")))).append("</td>")
            out.append("<td>")
            out.append(P.html(P.user(P.cell(row, "modifierdisplay"), P.cell(row, "modifiername"), P.cell(row, "lastmodifier")))).append("</td>")
            out.append("</tr>\n")
        }
        out.append("</tbody>\n</table>\n")
        out.append(View.filterScript("pq", "pb", "pn", "pages"))
    }
    out.append("</div>\n")
    out.append(View.foot(elapsed))
    return Rest.build(rest, 200, out.toString(), Rest.HTML)
}
