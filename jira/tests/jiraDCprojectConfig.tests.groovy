/* ===========================================================================
 * Offline harness for the Jira-free parts of jiraDCprojectConfig.groovy.
 * The class definitions are prepended verbatim from the real file by CI, so
 * this suite always tests the shipped source and never a copy that can drift.
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

/* ---- 1. html escaping ---------------------------------------------------- */

check("html null", Pc.html(null), "")
check("html plain", Pc.html("abc"), "abc")
check("html all", Pc.html("<a href=\"x\">&'</a>"),
    "&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;")
check("html ampersand first", Pc.html("&lt;"), "&amp;lt;")
check("html number", Pc.html(42), "42")

/* ---- 2. csv quoting ------------------------------------------------------ */

check("csv null", Pc.csv(null), "\"\"")
check("csv quote", Pc.csv("say \"hi\", ok"), "\"say \"\"hi\"\", ok\"")
check("csv semicolon stays inside quotes", Pc.csv("a;b"), "\"a;b\"")

/* ---- 3. text and orNa ---------------------------------------------------- */

check("text trims", Pc.text("  x  "), "x")
check("text blank is null", Pc.text("   "), null)
check("text null is null", Pc.text(null), null)
check("orNa blank", Pc.orNa("  "), Pc.NA)
check("orNa value", Pc.orNa("v"), "v")

/* ---- 4. url encoding ----------------------------------------------------- */

check("urlPath encodes space as %20", Pc.urlPath("A B"), "A%20B")
check("urlQuery encodes space as plus", Pc.urlQuery("A B"), "A+B")
check("urlPath encodes slash", Pc.urlPath("a/b"), "a%2Fb")
check("urlPath null", Pc.urlPath(null), "")

/* A key containing a slash would otherwise invent a path segment and silently
 * point the link at a different page. */
ok("a key with a slash cannot escape its path segment", !Pc.urlPath("a/b").contains("/"))

/* ---- 5. base url trimming ------------------------------------------------ */

check("trimBase plain", Pc.trimBase("https://jira.example.com"), "https://jira.example.com")
check("trimBase one slash", Pc.trimBase("https://jira.example.com/"), "https://jira.example.com")
check("trimBase many slashes", Pc.trimBase("https://jira.example.com///"), "https://jira.example.com")
check("trimBase null", Pc.trimBase(null), "")

/* ---- 6. assignee type ---------------------------------------------------- */

check("assignee 0", Pc.assigneeType(0L), "Project default")
check("assignee 1", Pc.assigneeType(1L), "Component lead")
check("assignee 2", Pc.assigneeType(2L), "Project lead")
check("assignee 3", Pc.assigneeType(3L), "Unassigned")
check("assignee null", Pc.assigneeType(null), Pc.NA)
/* An unknown number must be reported as itself. Folding it into one of the four
 * known values is how a report starts stating something the instance never said. */
check("assignee unknown is not folded into a known value", Pc.assigneeType(9L), "Unknown (9)")

/* ---- 6b. flags, dates and duck typing ------------------------------------ */

check("flag true", Pc.flag(true), "yes")
check("flag false", Pc.flag(false), "no")
check("dateText null", Pc.dateText(null), Pc.NA)
check("dateText formats", Pc.dateText(new Date(0L)).length(), 10)

class Duckling {
    String greet() { return "quack" }
    String echo(String value) { return "echo:" + value }
    String boom() { throw new IllegalStateException("no") }
}

Duckling duckling = new Duckling()
check("duck calls a present method", Pc.duck(duckling, "greet", null), "quack")
check("duck calls with an argument", Pc.duck(duckling, "echo", "x"), "echo:x")
check("duck on a missing method is null", Pc.duck(duckling, "nope", null), null)
check("duck on a null target is null", Pc.duck(null, "greet", null), null)
/* A method that exists and throws must not take the caller down with it: the
 * whole point of the duck helper is that an optional read cannot cost a report. */
check("duck swallows a throwing method", Pc.duck(duckling, "boom", null), null)

/* ---- 7. query parameters ------------------------------------------------- */

class FakeParams {
    Map<String, String> values = [:]
    Object getFirst(String name) { return values.get(name) }
}

FakeParams params = new FakeParams()
params.values.put("project", "  SCRUM ")
params.values.put("blank", "   ")
params.values.put("flagOn", "YES")
params.values.put("flagOff", "0")
params.values.put("flagJunk", "perhaps")

check("stringParam trims", Pc.stringParam(params, "project", null), "SCRUM")
check("stringParam blank falls back", Pc.stringParam(params, "blank", "fallback"), "fallback")
check("stringParam missing falls back", Pc.stringParam(params, "nope", "fallback"), "fallback")
check("stringParam null container", Pc.stringParam(null, "project", "fallback"), "fallback")
check("booleanParam yes", Pc.booleanParam(params, "flagOn", false), true)
check("booleanParam zero", Pc.booleanParam(params, "flagOff", true), false)
check("booleanParam junk keeps default", Pc.booleanParam(params, "flagJunk", true), true)
check("booleanParam missing keeps default", Pc.booleanParam(params, "nope", false), false)

/* ---- 7b. the response builder -------------------------------------------- */

/* A fake JAX-RS Response class. The real one cannot be named in this file for the
 * same reason it cannot be named in the endpoint: the namespace differs between
 * ScriptRunner lines. A fake is enough to prove what actually matters here, which
 * is that the builder chain is assembled in the right order with the right
 * arguments. */

class FakeBuilder {
    List<String> calls = []
    String entityValue
    FakeBuilder entity(String value) { calls << "entity"; entityValue = value; return this }
    FakeBuilder type(String value) { calls << ("type:" + value); return this }
    FakeBuilder header(String key, String value) { calls << ("header:" + key + "=" + value); return this }
    String build() { calls << "build"; return calls.join("|") }
}

class FakeResponse {
    static FakeBuilder last
    static FakeBuilder ok(String entity) {
        last = new FakeBuilder()
        last.calls << "ok"
        last.entityValue = entity
        return last
    }
    static FakeBuilder status(Integer code) {
        last = new FakeBuilder()
        last.calls << ("status:" + code)
        return last
    }
}

class EmptyResponse { }

check("ok builds ok then type then build",
    Http.ok(FakeResponse, "body", Http.HTML),
    "ok|type:text/html; charset=UTF-8|build")
check("ok passes the entity through", FakeResponse.last.entityValue, "body")

check("a non-200 goes through status then entity",
    Http.build(FakeResponse, 404, "not found", Http.JSON, null),
    "status:404|entity|type:application/json; charset=UTF-8|build")
check("the error entity is passed through", FakeResponse.last.entityValue, "not found")

Map<String, String> csvHeaders = [("Content-Disposition"): "attachment; filename=\"x.csv\""] as LinkedHashMap
check("headers are applied after the content type",
    Http.build(FakeResponse, 200, "a;b", Http.CSV, csvHeaders),
    "ok|type:text/csv; charset=UTF-8|header:Content-Disposition=attachment; filename=\"x.csv\"|build")

check("content types", [Http.HTML, Http.JSON, Http.CSV],
    ["text/html; charset=UTF-8", "application/json; charset=UTF-8", "text/csv; charset=UTF-8"])

/* A response class that answers none of the builder methods must not take the
 * endpoint down. It yields nothing, which the caller sees, rather than an
 * exception thrown out of a report that had already been produced. */
check("an unusable response class yields null, not an exception",
    Http.ok(EmptyResponse, "body", Http.HTML), null)

/* ---- 8. self link -------------------------------------------------------- */

Map<String, Object> base = [project: "SCRUM", format: null, depth: null] as LinkedHashMap
check("link drops nulls", Pc.link(base, null), "?project=SCRUM")
check("link applies override", Pc.link(base, [format: "json"]), "?project=SCRUM&format=json")
check("link can clear a value", Pc.link([project: "SCRUM", depth: "top"] as LinkedHashMap, [depth: null]),
    "?project=SCRUM")
check("link encodes", Pc.link([project: "A B"] as LinkedHashMap, null), "?project=A+B")

/* ---- 9. deep links: the evidenced shapes --------------------------------- */

Dl links = new Dl("https://jira.example.com/")

check("screen", links.screen(101), "https://jira.example.com/secure/admin/ConfigureFieldScreen.jspa?id=101")
check("screen scheme", links.screenScheme(7),
    "https://jira.example.com/secure/admin/ConfigureFieldScreenScheme.jspa?id=7")
check("issue type screen scheme", links.issueTypeScreenScheme(12),
    "https://jira.example.com/secure/admin/ConfigureIssueTypeScreenScheme.jspa?id=12")
check("field configuration", links.fieldConfiguration(3),
    "https://jira.example.com/secure/admin/ConfigureFieldLayout.jspa?id=3")
check("field configuration scheme", links.fieldConfigurationScheme(4),
    "https://jira.example.com/secure/admin/ConfigureFieldLayoutScheme.jspa?id=4")
check("custom field", links.customField(10004),
    "https://jira.example.com/secure/admin/ConfigureCustomField.jspa?customFieldId=10004")
check("custom field context", links.customFieldContext(10004, 10100),
    "https://jira.example.com/secure/admin/ConfigureCustomField.jspa?customFieldId=10004&fieldConfigSchemeId=10100")
check("permission scheme", links.permissionScheme(0),
    "https://jira.example.com/secure/admin/EditPermissions.jspa?schemeId=0")
check("notification scheme", links.notificationScheme(10000),
    "https://jira.example.com/secure/admin/EditNotifications!default.jspa?schemeId=10000")
check("issue security scheme", links.issueSecurityScheme(10001),
    "https://jira.example.com/secure/admin/EditIssueSecurities!default.jspa?schemeId=10001")
check("issue security level", links.issueSecurityLevel(10001, 10200),
    "https://jira.example.com/secure/admin/EditIssueSecurities!default.jspa?schemeId=10001&levelId=10200")
check("workflow scheme", links.workflowScheme(10500),
    "https://jira.example.com/secure/admin/workflows/EditWorkflowScheme.jspa?schemeId=10500")
check("workflow live", links.workflow("Bug Workflow", false),
    "https://jira.example.com/secure/admin/workflows/ViewWorkflowSteps.jspa?workflowName=Bug+Workflow&workflowMode=live")
check("workflow draft", links.workflow("Bug Workflow", true),
    "https://jira.example.com/secure/admin/workflows/ViewWorkflowSteps.jspa?workflowName=Bug+Workflow&workflowMode=draft")

check("project summary", links.projectSummary("SCRUM"),
    "https://jira.example.com/plugins/servlet/project-config/SCRUM/summary")
check("project roles", links.projectRoles("SCRUM"),
    "https://jira.example.com/plugins/servlet/project-config/SCRUM/roles")
check("project versions", links.projectVersions("SCRUM"),
    "https://jira.example.com/plugins/servlet/project-config/SCRUM/administer-versions")
check("project components", links.projectComponents("SCRUM"),
    "https://jira.example.com/plugins/servlet/project-config/SCRUM/administer-components")
check("project issue type", links.projectIssueType("SCRUM", 10001),
    "https://jira.example.com/plugins/servlet/project-config/SCRUM/issuetypes/10001")

/* A trailing slash on the base URL must not produce a double slash. */
ok("no double slash after the host", !links.projectSummary("SCRUM").replace("https://", "").contains("//"))

/* A missing id yields no link at all rather than a link that goes nowhere. */
check("screen without id", links.screen(null), null)
check("screen with blank id", links.screen("   "), null)
check("context without context id", links.customFieldContext(10004, null), null)
check("level without level id", links.issueSecurityLevel(10001, null), null)
check("project page without key", links.projectSummary(null), null)
check("issue type without id", links.projectIssueType("SCRUM", null), null)

/* The one shape that could not be evidenced is recorded as a gap, in words, and
 * not silently replaced by a guessed parameter. */
ok("the unevidenced issue type scheme link is recorded as a gap",
    links.issueTypeSchemeUnavailableNote() != null &&
    links.issueTypeSchemeUnavailableNote().toLowerCase(Locale.ROOT).contains("not linked"))

/* ---- 10. node behaviour -------------------------------------------------- */

Nd node = Nd.of("screen", "Bug Edit Screen").val("3 tabs").ident(101)
check("node kind", node.kind, "screen")
check("node label", node.label, "Bug Edit Screen")
check("node value", node.value, "3 tabs")
check("node id is a string", node.id, "101")
check("fresh node is readable", node.isReadable(), true)

node.link(links.screen(101), "Administration > Issues > Screens")
check("link attached", node.deepLink, "https://jira.example.com/secure/admin/ConfigureFieldScreen.jspa?id=101")
check("note cleared when a link exists", node.linkNote, null)

Nd unlinked = Nd.of("issueTypeScheme", "Default Issue Type Scheme")
unlinked.link(null, "Administration > Issues > Issue type schemes")
check("no link", unlinked.deepLink, null)
check("note kept when the link is missing", unlinked.linkNote, "Administration > Issues > Issue type schemes")

/* A link and its absence note can never both be set, which is what keeps an
 * unexplained missing link from reaching the report. */
ok("link and note are mutually exclusive",
    (node.deepLink == null) != (node.linkNote == null) &&
    (unlinked.deepLink == null) != (unlinked.linkNote == null))

Nd broken = Nd.of("workflow", "Bug Workflow").failed("Read failed: RuntimeException")
check("failed sets state", broken.state, Pc.UNREADABLE)
check("failed is not readable", broken.isReadable(), false)
check("failed keeps the reason on the node", broken.diagnostics.size(), 1)

Nd missing = Nd.of("issueSecurityScheme", "Issue security scheme").absent("No scheme associated")
check("absent sets state", missing.state, Pc.ABSENT)
check("absent states the reason as the value", missing.value, "No scheme associated")

/* ---- 11. tree arithmetic ------------------------------------------------- */

Nd root = Nd.of("section", "Screens")
Nd level1 = Nd.of("screenScheme", "Bug Screen Scheme")
Nd level2 = Nd.of("screen", "Bug Edit Screen")
level2.add(Nd.of("tab", "Field Tab").add(Nd.of("field", "Summary")))
level1.add(level2)
root.add(level1)
check("descendants counted through every level", root.countDescendants(), 4)
check("a leaf has no descendants", Nd.of("field", "Summary").countDescendants(), 0)
check("add ignores null", Nd.of("x", "y").add(null).children.size(), 0)
check("addAll ignores null", Nd.of("x", "y").addAll(null).children.size(), 0)

Nd diagTree = Nd.of("section", "Workflows")
diagTree.add(Nd.of("workflowScheme", "SCRUM WFS").add(broken))
List<String> collected = diagTree.collectDiagnostics("")
check("one diagnostic collected", collected.size(), 1)
ok("the diagnostic carries its full path",
    collected[0].startsWith("Workflows > SCRUM WFS > Bug Workflow: "))

/* ---- 12. report totals --------------------------------------------------- */

Report report = new Report()
report.projectKey = "SCRUM"
report.projectName = "Scrum Project"
report.instanceBaseUrl = "https://jira.example.com"

Nd details = report.section("projectDetails", "Project details")
details.link(links.projectSummary("SCRUM"), null)
details.add(Nd.of("projectField", "Key").val("SCRUM"))
details.add(Nd.of("projectField", "Lead").failed("Read failed: NullPointerException"))

Nd schemes = report.section("issueTypeScheme", "Issue type scheme")
schemes.link(null, "Administration > Issues > Issue type schemes")
schemes.add(Nd.of("issueType", "Bug").ident(1))

check("sections counted", report.sections.size(), 2)
check("nodes counted across sections", report.nodeCount(), 5)
check("one unreadable node", report.unreadableCount(), 1)
check("one node without a link", report.unlinkedCount(), 1)
check("diagnostics reach the report", report.allDiagnostics().size(), 1)

Map<String, Object> asMap = report.toMap()
check("map carries the version", asMap.get("reportVersion"), Pc.VERSION)
ok("map carries totals", ((Map) asMap.get("totals")).get("nodes") == 5)
ok("map omits empty child lists",
    !((Map) ((List) asMap.get("sections"))[1].get("children")[0]).containsKey("children"))

/* ---- 13. csv rendering --------------------------------------------------- */

String csvOut = Render.csv(report)
List<String> csvLines = csvOut.trim().split("\n") as List<String>
check("csv has a header plus one row per node", csvLines.size(), 1 + report.nodeCount())
ok("csv header names the path column", csvLines[0].startsWith("path;kind;label;"))
ok("csv keeps the unreadable state", csvOut.contains("\"unreadable\""))
ok("csv carries the link note", csvOut.contains("Administration > Issues > Issue type schemes"))

Report quoted = new Report()
quoted.section("s", "He said \"hi\"; then left").add(Nd.of("f", "a;b"))
String quotedCsv = Render.csv(quoted)
ok("csv doubles embedded quotes", quotedCsv.contains("\"He said \"\"hi\"\"; then left\""))

/* ---- 14. html rendering -------------------------------------------------- */

String htmlOut = Render.html(report, [project: "SCRUM"] as LinkedHashMap, false)
ok("html names the project", htmlOut.contains("Scrum Project"))
ok("html prints the version", htmlOut.contains("v" + Pc.VERSION))
ok("html renders the deep link", htmlOut.contains("project-config/SCRUM/summary"))
ok("html marks the unreadable node", htmlOut.contains("could not be read"))
ok("html shows the missing-link note as a tooltip",
    htmlOut.contains("Administration &gt; Issues &gt; Issue type schemes"))
ok("html contains no external resource",
    !htmlOut.contains("http://") &&
    !htmlOut.contains("src=\"//") &&
    !htmlOut.toLowerCase(Locale.ROOT).contains("<link rel=\"stylesheet\""))

Report hostile = new Report()
hostile.projectKey = "X"
hostile.projectName = "<script>alert(1)</script>"
hostile.section("projectDetails", "Project details").add(Nd.of("f", "<img onerror=x>"))
String hostileHtml = Render.html(hostile, [:] as LinkedHashMap, false)
ok("html escapes the project name", !hostileHtml.contains("<script>alert(1)</script>"))
ok("html escapes a node label", !hostileHtml.contains("<img onerror=x>"))
ok("html keeps the escaped text", hostileHtml.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))

/* depth=top collapses everything below the first level, and it does so by
 * marking the list rather than by dropping the nodes: the data is still there. */
Report deep = new Report()
Nd deepSection = deep.section("s", "Screens")
deepSection.add(Nd.of("screenScheme", "SS").add(Nd.of("screen", "Edit Screen")))
String expanded = Render.html(deep, [:] as LinkedHashMap, false)
String collapsed = Render.html(deep, [:] as LinkedHashMap, true)
ok("collapsed output still contains the nested node", collapsed.contains("Edit Screen"))
ok("collapsed output hides the nested list", collapsed.contains("class=\"tree hidden\""))
ok("expanded output hides nothing", !expanded.contains("class=\"tree hidden\""))

/* ---- 15. the discrimination this report exists for ----------------------- */

/* An empty section and an unreadable section must not render alike. A renderer
 * that only asks "are there children" cannot tell them apart, and that is exactly
 * the failure mode this project refuses. The control below is that naive
 * renderer, written out here so the claim is demonstrated rather than asserted. */

def naiveRender = { Nd candidate ->
    return candidate.children.isEmpty() ? "nothing" : "something"
}

Nd genuinelyEmpty = Nd.of("componentSection", "Components")
Nd couldNotRead = Nd.of("componentSection", "Components").failed("Read failed: RuntimeException")

ok("the naive control cannot tell an empty section from an unreadable one",
    naiveRender(genuinelyEmpty) == naiveRender(couldNotRead))

Report emptyReport = new Report()
emptyReport.sections.add(genuinelyEmpty)
Report brokenReport = new Report()
brokenReport.sections.add(couldNotRead)

String emptyHtml = Render.html(emptyReport, [:] as LinkedHashMap, false)
String brokenHtml = Render.html(brokenReport, [:] as LinkedHashMap, false)

ok("this renderer does tell them apart", emptyHtml != brokenHtml)
ok("the empty one says nothing is configured", emptyHtml.contains("Nothing configured here."))
ok("the unreadable one does not claim nothing is configured",
    !brokenHtml.contains("Nothing configured here."))
ok("the unreadable one says the read failed", brokenHtml.contains("could not be read"))
check("only the unreadable one counts as unreadable", brokenReport.unreadableCount(), 1)
check("the empty one counts as readable", emptyReport.unreadableCount(), 0)

/* Same discipline in the machine-readable channels, because a consumer that only
 * reads the JSON must be able to make the same distinction. */
ok("json keeps the states apart",
    Render.json(emptyReport).contains("\"state\": \"read\"") &&
    Render.json(brokenReport).contains("\"state\": \"unreadable\""))
ok("csv keeps the states apart",
    !Render.csv(emptyReport).contains("\"unreadable\"") &&
    Render.csv(brokenReport).contains("\"unreadable\""))

/* ---- 16. state labels ---------------------------------------------------- */

check("label read", Render.stateLabel(Pc.READ), "read")
check("label unreadable", Render.stateLabel(Pc.UNREADABLE), "could not be read")
check("label absent", Render.stateLabel(Pc.ABSENT), "not configured")
check("label truncated", Render.stateLabel(Pc.TRUNCATED), "shortened")
/* Four distinct states must produce four distinct sentences, otherwise the
 * distinction exists in the model and dies in the rendering. */
ok("the four states read differently",
    ([Pc.READ, Pc.UNREADABLE, Pc.ABSENT, Pc.TRUNCATED].collect { Render.stateLabel(it) } as Set).size() == 4)

/* ---- 17. the picker ------------------------------------------------------ */

Report shell = new Report()
shell.instanceTitle = "Example Jira"
List<Map<String, String>> projectRows = [
    [key: "SCRUM", name: "Scrum Project"] as LinkedHashMap,
    [key: "OPS", name: "Operations & <Support>"] as LinkedHashMap
]
String pickerHtml = Render.picker(shell, projectRows, "")
ok("picker lists every project", pickerHtml.contains("value=\"SCRUM\"") && pickerHtml.contains("value=\"OPS\""))
ok("picker escapes a project name", pickerHtml.contains("Operations &amp; &lt;Support&gt;"))
ok("picker states the count", pickerHtml.contains("2 projects"))
ok("picker submits with GET", pickerHtml.contains("method=\"get\""))

Report failedShell = new Report()
failedShell.globalDiagnostics.add("The project list could not be read: RuntimeException. " +
    "This is a failed read, not an empty instance.")
String failedPicker = Render.picker(failedShell, [], "")
ok("a failed project list is not shown as an empty instance",
    failedPicker.contains("could not be read") && failedPicker.contains("not an empty instance"))
ok("an empty instance and a failed read produce different pickers",
    Render.picker(new Report(), [], "") != failedPicker)

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"
