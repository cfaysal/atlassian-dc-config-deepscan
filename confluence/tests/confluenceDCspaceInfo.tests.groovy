/* ===========================================================================
 * Offline harness for confluenceDCspaceInfo.groovy.
 *
 * Two kinds of assertion, and the difference matters.
 *
 * EXECUTED: the classes P, Rest and Rows are prepended verbatim from the real
 * file, so this suite runs the shipped code and never a copy that can drift.
 * The block runs from "class P {" to the line before "class Sql {" - Sql names
 * ComponentLocator and java.sql, so it cannot compile off-instance.
 *
 * READ AS TEXT: everything below Sql - the statements, the endpoint body, the
 * rendering - is asserted against the source text. That is weaker than running
 * it and it is stated rather than hidden: a text assertion proves a string is
 * present, not that the code around it behaves.
 *
 * Run it the way CI does:
 *   START=$(grep -n '^class P {' <file> | cut -d: -f1)
 *   END=$(( $(grep -n '^class Sql {' <file> | cut -d: -f1) - 2 ))
 *   { imports; sed -n "${START},${END}p" <file>; cat <this file>; } > suite.groovy
 * ======================================================================== */

int passed = 0
int failed = 0
List<String> failures = []

def check = { String name, Object actual, Object expected ->
    if (actual == expected) {
        passed++
    } else {
        failed++
        failures << (name + "\n     expected: " + expected + "\n     actual  : " + actual)
    }
}

def ok = { String name, boolean condition ->
    if (condition) {
        passed++
    } else {
        failed++
        failures << name
    }
}

/* The shipped source, read from the working directory or one level up so the
 * suite runs from the repository root and from the confluence directory alike.
 * It fails loudly rather than skipping: a suite that silently tests nothing is
 * worse than one that does not run. */
String source = null
for (String candidate : ["confluence/confluenceDCspaceInfo.groovy", "confluenceDCspaceInfo.groovy",
                         "../confluenceDCspaceInfo.groovy"]) {
    File file = new File(candidate)
    if (file.exists()) {
        source = file.getText("UTF-8")
        break
    }
}
if (source == null) {
    System.err.println("FATAL: confluenceDCspaceInfo.groovy not found. Run from the repository root.")
    System.exit(2)
}

/* The body of a named constant, so a statement can be asserted on its own
 * rather than against the whole file. Without this, "the file contains
 * prevver IS NULL" would pass while the statement that needed it did not. */
def sqlBody = { String name ->
    int at = source.indexOf("static final String " + name + " =")
    if (at < 0) {
        return null
    }
    int end = source.indexOf("\n\n", at)
    return end < 0 ? source.substring(at) : source.substring(at, end)
}

/* =========================================================================
 * 1. EXECUTED - the person label, five layers, each a different fact
 * ====================================================================== */

check("user: display and login differ, both shown",
    P.user("C.Faysal", "cfaysal", "8aaa81a1"), "C.Faysal (cfaysal)")
check("user: display equals login, not printed twice",
    P.user("cfaysal", "cfaysal", "8aaa81a1"), "cfaysal")
check("user: display equals login ignoring case, still collapsed",
    P.user("CFaysal", "cfaysal", "8aaa81a1"), "CFaysal")
check("user: display only",
    P.user("C.Faysal", null, "8aaa81a1"), "C.Faysal")
check("user: login only says the directory has no display name",
    P.user(null, "cfaysal", "8aaa81a1"),
    "cfaysal (no display name in the user directory)")
check("user: key only says the mapping is empty",
    P.user(null, null, "8aaa81a1"),
    "8aaa81a1 (this user key resolves to no entry in user_mapping)")
check("user: nothing at all", P.user(null, null, null), P.NA)
/* Blank is not the same as absent in SQL, but it is here: a column holding
 * spaces names nobody. */
check("user: blanks are treated as absent", P.user("  ", "  ", "  "), P.NA)

/* =========================================================================
 * 2. EXECUTED - escaping. A page title is user text on an admin's screen.
 * ====================================================================== */

check("html: script tag is neutralised",
    P.html("<script>alert(1)</script>"),
    "&lt;script&gt;alert(1)&lt;/script&gt;")
check("html: ampersand first, so entities are not double-encoded into nonsense",
    P.html("a & b"), "a &amp; b")
check("html: quotes, both kinds", P.html("\"x\" 'y'"), "&quot;x&quot; &#39;y&#39;")
check("html: null renders empty, never the word null", P.html(null), "")
ok("html: no raw angle bracket survives",
    !P.html("<b>").contains("<") && !P.html("<b>").contains(">"))

check("urlQuery: space key with a tilde is encoded", P.urlQuery("~admin"), "%7Eadmin")
check("urlQuery: null is empty, not the word null", P.urlQuery(null), "")

/* =========================================================================
 * 3. EXECUTED - absent versus present
 * ====================================================================== */

check("text: blank is absent", P.text("   "), null)
check("text: null is absent", P.text(null), null)
check("text: value is trimmed", P.text("  ENT0001  "), "ENT0001")
check("orNa: absent renders the marker", P.orNa(null), P.NA)
check("orNa: zero is a value, not an absence", P.orNa("0"), "0")
check("spaceType: personal", P.spaceType("personal"), "Personal")
check("spaceType: anything else is global", P.spaceType("global"), "Global")
check("spaceType: unknown is not guessed", P.spaceType(null), P.NA)
check("cell: missing column is null, not an exception",
    P.cell(new LinkedHashMap<String, String>(), "nope"), null)
check("cell: null row is null", P.cell(null, "spacekey"), null)

/* =========================================================================
 * 4. EXECUTED - Rows: readable, empty and truncated are three states
 *
 * This is the property the whole endpoint exists to protect. A read that
 * failed and a read that found nothing must never answer the same way.
 * ====================================================================== */

Rows empty = new Rows()
ok("rows: an empty successful read IS readable", empty.isReadable())
ok("rows: and it is empty", empty.isEmpty())
check("rows: and it has no first row", empty.first(), null)
check("rows: and its size is zero", empty.size(), 0)

Rows broken = new Rows()
broken.failure = "The statement failed: SQLException - no such column"
ok("rows: a failed read is NOT readable", !broken.isReadable())
ok("rows: a failed read is also empty, which is exactly why isEmpty alone must never be trusted",
    broken.isEmpty())
ok("rows: failed and empty are distinguishable", broken.isReadable() != empty.isReadable())

Rows full = new Rows()
full.rows.add(["spacekey": "ENT0001"] as LinkedHashMap)
full.truncated = true
full.cap = 5000
ok("rows: a truncated read is still readable", full.isReadable())
ok("rows: a truncated read is not empty", !full.isEmpty())
ok("rows: truncation is its own flag, not an error", full.truncated && full.failure == null)
check("rows: first returns the first row", full.first().get("spacekey"), "ENT0001")

/* =========================================================================
 * 5. EXECUTED - the JAX-RS shim answers without naming a namespace
 * ====================================================================== */

check("rest: html content type is explicit about the charset",
    Rest.HTML, "text/html; charset=UTF-8")
check("rest: json content type is explicit about the charset",
    Rest.JSON, "application/json; charset=UTF-8")

/* =========================================================================
 * 6. TEXT - the statements. Each guard asserted on ITS OWN statement body.
 * ====================================================================== */

for (String name : ["COUNT_SQL", "PAGES_SQL", "SUGGEST_SQL", "SPACE_SQL", "SPACE_TOTAL_SQL"]) {
    ok("sql: " + name + " exists", sqlBody(name) != null)
}

for (String name : ["COUNT_SQL", "PAGES_SQL"]) {
    String body = sqlBody(name)
    ok("sql: " + name + " excludes historical versions by prevver",
        body != null && body.contains("c.prevver IS NULL"))
    ok("sql: " + name + " restricts to one space through the join",
        body != null && body.contains("JOIN spaces s ON s.spaceid = c.spaceid")
            && body.contains("s.spacekey = ?"))
    ok("sql: " + name + " binds the content type rather than pasting it",
        body != null && body.contains("c.contenttype = ?"))
}

String pages = sqlBody("PAGES_SQL")
ok("sql: PAGES_SQL orders newest first with a total tiebreak, so a second run cuts identically",
    pages != null && pages.contains("ORDER BY c.lastmoddate DESC, c.contentid DESC"))
ok("sql: PAGES_SQL resolves both user keys through user_mapping",
    pages != null && pages.count("LEFT JOIN user_mapping") == 2)

String suggest = sqlBody("SUGGEST_SQL")
ok("sql: SUGGEST_SQL binds every LIKE pattern",
    suggest != null && !suggest.contains("LIKE '") && suggest.contains("LIKE ?"))
ok("sql: SUGGEST_SQL ranks in SQL so the cap cuts by the order shown",
    suggest != null && suggest.contains("ORDER BY CASE WHEN LOWER(s.spacekey) = ?"))
ok("sql: SUGGEST_SQL does not filter by status, so an archived space stays findable",
    suggest != null && !suggest.contains("spacestatus = ?"))

/* The display name must never multiply rows. An aggregate cannot; a join can,
 * on an instance holding one user name in two directories. */
ok("sql: the display name is an aggregate, not a join",
    source.contains("(SELECT MAX(dc.display_name) FROM cwd_user dc")
        && source.contains("(SELECT MAX(dm.display_name) FROM cwd_user dm"))
ok("sql: no JOIN against cwd_user anywhere",
    !source.replaceAll("(?s)/\\*.*?\\*/", "").contains("JOIN cwd_user"))

/* Oracle stores text as NVARCHAR2 and a bare literal is CHAR. A literal inside
 * COALESCE or NULLIF against a text column is ORA-12704. Numeric is safe. */
for (String name : ["COUNT_SQL", "PAGES_SQL", "SUGGEST_SQL", "SPACE_SQL", "SPACE_TOTAL_SQL"]) {
    String body = sqlBody(name)
    ok("sql: " + name + " puts no string literal inside COALESCE or NULLIF",
        body == null || !(body =~ /(?i)(COALESCE|NULLIF|DECODE)\s*\([^)]*'/))
    ok("sql: " + name + " uses no non-portable row limiter",
        body == null || !(body =~ /(?i)\b(LIMIT|FETCH FIRST|TOP|NULLS)\b/))
    ok("sql: " + name + " carries no literal status or type word",
        body == null || !(body.contains("'current'") || body.contains("'deleted'")
            || body.contains("'PAGE'")))
}

for (String name : ["PAGE_TYPE", "CURRENT", "DELETED"]) {
    ok("sql: the constant " + name + " exists, so the value is bound and named once",
        source.contains("static final String " + name + " = \""))
}

/* =========================================================================
 * 7. TEXT - the shipping rules
 * ====================================================================== */

ok("ship: no internal ticket reference reaches a customer instance",
    !(source =~ /OP-\d+/))
ok("ship: no internal jargon reaches a customer instance",
    !source.toLowerCase().contains("preview"))
ok("ship: source is ASCII only, so no encoding turns a comment into mojibake",
    !(source =~ /[^\x00-\x7F]/))
ok("ship: no control byte", !(source =~ /[\x00-\x08\x0B\x0C\x0E-\x1F]/))

ok("platform: no jakarta import", !source.contains("import jakarta"))
ok("platform: no javax import", !source.contains("import javax"))
ok("platform: the response class is resolved at runtime",
    source.contains("Class.forName(\"jakarta.ws.rs.core.Response\")")
        && source.contains("Class.forName(\"javax.ws.rs.core.Response\")"))

ok("gate: registered once, for administrators only",
    source.count("groups: [\"confluence-administrators\"]") == 1)
ok("gate: GET only, no POST handler", !source.contains("httpMethod: \"POST\""))
ok("gate: the endpoint is registered once", source.count("\nspaceInfo(") == 1)

ok("read-only: no statement mutates",
    !(source =~ /(?i)"\s*(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE)\s/))
ok("read-only: the executor is the read-only one",
    source.contains("\"createReadOnly\""))

/* No state-changing address is ever emitted, in any format. */
for (String verb : ["removespace", "removepage", "purgefromtrash", "emptytrash",
                    "restorefromtrash", "doremove", "xsrftoken", "atl_token"]) {
    ok("safety: the page emits no " + verb + " address", !source.toLowerCase().contains(verb))
}

/* =========================================================================
 * 8. TEXT - shape checks, so a schema change is named rather than empty
 * ====================================================================== */

for (String table : ["spaces", "content", "user_mapping", "cwd_user"]) {
    ok("shape: " + table + " is checked against the catalogue before it is read",
        source.contains("Sql.shape(connection, \"" + table + "\""))
}

/* =========================================================================
 * 9. TEXT - the cap announces itself, and offers the way out as an address
 * ====================================================================== */

ok("cap: the page list cap is named once as a constant",
    source.contains("static final int DEFAULT_CAP = 5000"))
ok("cap: the ordering the cut follows is named once and reused",
    source.contains("static final String PAGE_ORDER ="))
ok("cap: a shortened list says so",
    source.contains("This list is shortened."))
ok("cap: and says the number shown is not the number that exists",
    source.contains("which is not the number that exists"))
ok("cap: and names which pages fell off the end",
    source.contains("modified longest ago"))
ok("cap: the way out is a link, not an instruction to build a URL",
    source.contains("Raise it: <a href=") && source.contains("&amp;limit="))
ok("cap: at the maximum it says so instead of offering a link that changes nothing",
    source.contains("This is already the highest limit this endpoint accepts"))
ok("cap: the suggestion list states that more matched rather than just stopping",
    source.contains("More match than are shown"))

/* =========================================================================
 * 10. TEXT - a failed read is never rendered as an empty result
 * ====================================================================== */

ok("state: a failed count says it is a failed read",
    source.contains("That is a failed read, not a space without pages."))
ok("state: a failed executor acquisition says it is not an empty instance",
    source.contains("not an instance without spaces."))
ok("state: a failed search says it is not an absent space",
    source.contains("That is a failed lookup, not an absent space."))
ok("state: an empty page list says the read succeeded",
    source.contains("The read succeeded and returned nothing."))
/* The count is COUNT(CASE ... THEN 1 END), which returns 0 over zero rows.
 * A space with no page therefore yields a MEASURED zero rather than a NULL
 * that some COALESCE would have to turn into one. That is the guarantee this
 * asserts. KNOWN GAP: the rendered page does not yet SAY that the zero was
 * measured, so a reader cannot tell it apart from a zero by other means. */
ok("state: a zero page count is measured, not defaulted",
    sqlBody("COUNT_SQL") != null
        && sqlBody("COUNT_SQL").contains("COUNT(CASE WHEN c.content_status = ? THEN 1 END)"))
ok("state: an aggregate that returns no row is a failure, not a zero",
    source.contains("which an aggregate cannot do"))
ok("state: a missing space is a 404 only when the statement matched nothing",
    source.contains("The statement ran and matched nothing."))
ok("state: an unreadable space row leaves existence unknown",
    source.contains("Whether this space exists is UNKNOWN."))

/* =========================================================================
 * 11. TEXT - the browser side
 * ====================================================================== */

ok("browser: the suggestion request is debounced",
    source.contains("setTimeout(ask,180)"))
ok("browser: a stale reply cannot overwrite a newer one",
    source.contains("var mine=++seq") && source.contains("if(mine===seq)render(d)"))
ok("browser: exactly one fetch, on the picker page",
    source.count("fetch('?find=") == 1)
ok("browser: no other request mechanism",
    !source.contains("XMLHttpRequest"))
ok("browser: the embedded JSON cannot close its own script element",
    source.contains("\\\\u003c") || !source.contains("JsonOutput.toJson(picker)"))
ok("browser: a short query costs no statement",
    source.contains("static final int MIN_QUERY = 2"))

/* =========================================================================
 * Report
 * ====================================================================== */

println ""
println "confluenceDCspaceInfo offline suite"
println "  passed: " + passed
println "  failed: " + failed
if (failed > 0) {
    println ""
    for (String failure : failures) {
        println "  FAIL " + failure
    }
    println ""
    System.exit(1)
}
println "  OK"
