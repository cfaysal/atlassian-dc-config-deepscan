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

/* The screen exists and is evidenced; only the parameter that would preselect one
 * scheme on it is not. So the list is linked and the gap is stated, rather than a
 * parameter being invented or the whole link being dropped. */
check("the issue type scheme list is linked", links.issueTypeSchemes(),
    "https://jira.example.com/secure/admin/ManageIssueTypeSchemes.jspa")
ok("the list link carries no invented parameter", !links.issueTypeSchemes().contains("?"))
ok("the gap is stated in words",
    links.issueTypeSchemeUnavailableNote().toLowerCase(Locale.ROOT).contains("opens the list"))

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

/* The same screen scheme is reached once per issue type that maps to it, so a
 * summary built by counting visits would report a project as having six screen
 * schemes when it has two. */
Nd shared = Nd.of("section", "Screens")
shared.add(Nd.of("entry", "Issue type: Bug")
    .add(Nd.of("screenScheme", "Default").ident(7).add(Nd.of("screen", "Edit").ident(101))))
shared.add(Nd.of("entry", "Issue type: Task")
    .add(Nd.of("screenScheme", "Default").ident(7).add(Nd.of("screen", "Edit").ident(101))))
shared.add(Nd.of("entry", "Issue type: Story")
    .add(Nd.of("screenScheme", "Story SS").ident(8).add(Nd.of("screen", "Story").ident(102))))
check("a shared screen scheme is counted once", shared.idsOfKind("screenScheme").size(), 2)
check("a shared screen is counted once", shared.idsOfKind("screen").size(), 2)
check("a kind nobody carries counts zero", shared.idsOfKind("workflow").size(), 0)
/* Without an id there is nothing to deduplicate on, so the label stands in - two
 * different things with the same label are one entry, which is the safe direction:
 * it under-reports rather than inventing variety. */
check("a node without an id falls back to its label",
    Nd.of("x", "root").add(Nd.of("screen", "No id")).add(Nd.of("screen", "No id"))
        .idsOfKind("screen").size(), 1)
check("a leaf has no descendants", Nd.of("field", "Summary").countDescendants(), 0)
check("add ignores null", Nd.of("x", "y").add(null).children.size(), 0)
check("addAll ignores null", Nd.of("x", "y").addAll(null).children.size(), 0)

Nd diagTree = Nd.of("section", "Workflows")
diagTree.add(Nd.of("workflowScheme", "SCRUM WFS").add(broken))
Map<String, List<String>> collected = new LinkedHashMap<String, List<String>>()
diagTree.collectDiagnostics("", collected)
check("one diagnostic collected", collected.size(), 1)
check("and it occurred once", collected.values().iterator().next().size(), 1)
ok("the diagnostic carries its full path",
    collected.values().iterator().next().get(0) == "Workflows > SCRUM WFS > Bug Workflow")

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

/* ---- 12b. a measured absence is not a failed read ------------------------ */

/* A transition with no screen, a project with no category, a condition list that is
 * genuinely empty: all ABSENT, all normal, none of them a failure. Counting them as
 * unreadable made a healthy project announce dozens of failed reads on the summary
 * card while the diagnostics list right beside it correctly said none. */
Report absences = new Report()
Nd withAbsences = absences.section("s", "Workflows")
withAbsences.add(Nd.of("workflowScreen", "Transition screen").absent("No screen"))
withAbsences.add(Nd.of("workflowCondition", "Conditions").absent("none"))
withAbsences.add(Nd.of("projectField", "Category").absent("No category"))
check("absences are not counted as failures", absences.unreadableCount(), 0)
check("absences still count as items", absences.nodeCount(), 4)

Nd oneFailure = absences.section("s2", "Roles")
oneFailure.add(Nd.of("projectRole", "Users").failed("Read failed: RuntimeException"))
check("a real failure is counted", absences.unreadableCount(), 1)
ok("the summary card and the diagnostics list agree",
    absences.unreadableCount() == absences.allDiagnostics().size())

/* ---- 12c. an observation is not a failure -------------------------------- */

Report observed = new Report()
Nd actor = Nd.of("roleActor", "old-admins").note("This actor is marked inactive.")
observed.section("s", "Roles").add(actor)
check("a note is not a diagnostic", observed.allDiagnostics().size(), 0)
check("a note is collected as a note", observed.allNotes().size(), 1)
check("a noted node stays readable", observed.unreadableCount(), 0)
ok("the note carries its path", observed.allNotes()[0].startsWith("Roles > old-admins: "))

String observedHtml = Render.html(observed, [:] as LinkedHashMap, false)
ok("observations get their own card", observedHtml.contains("1 observation"))
ok("observations do not claim a suppressed read", !observedHtml.contains("read was suppressed"))
ok("an observation is styled apart from a failure", observedHtml.contains("class=\"node-note\""))

Report withFailure = new Report()
withFailure.section("s", "Roles").add(Nd.of("projectRole", "Users").failed("Read failed: X"))
String failedHtml = Render.html(withFailure, [:] as LinkedHashMap, false)
ok("a real failure still says so", failedHtml.contains("read was suppressed"))
ok("a failure is not filed as an observation", !failedHtml.contains("1 observation"))

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
ok("a section link is called open in Jira by default", htmlOut.contains(">open in Jira</a>"))

/* A link that can only land near its item says so in the text somebody clicks. */
Report labelled = new Report()
labelled.section("issueTypeScheme", "Issue type scheme: X")
    .link("https://jira.example.com/secure/admin/ManageIssueTypeSchemes.jspa", null)
    .linkAs("open issue type schemes")
    .add(Nd.of("issueType", "Bug"))
String labelledHtml = Render.html(labelled, [:] as LinkedHashMap, false)
ok("a named link keeps its own text", labelledHtml.contains(">open issue type schemes</a>"))
ok("a named link does not also claim to be exact", !labelledHtml.contains(">open in Jira</a>"))
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

/* Collapsed is the default, and it collapses by marking the list rather than by
 * dropping nodes: the data is in the page either way, which is what lets the
 * in-page Expand all work without another request. */
Report deep = new Report()
Nd deepSection = deep.section("s", "Screens")
deepSection.add(Nd.of("screenScheme", "SS").add(Nd.of("screen", "Edit Screen")))
String expanded = Render.html(deep, [:] as LinkedHashMap, true)
String collapsed = Render.html(deep, [:] as LinkedHashMap, false)
ok("collapsed output still contains the nested node", collapsed.contains("Edit Screen"))
ok("collapsed output hides the nested list", collapsed.contains("class=\"tree hidden\""))
ok("expanded output hides nothing", !expanded.contains("class=\"tree hidden\""))
ok("the collapsed node says how much it hides", collapsed.contains("class=\"node-count muted\""))

/* Sections fold too, and they start folded. What a reader needs while a section is
 * shut has to stay on the header, otherwise folding hides evidence rather than
 * noise. */
ok("a section carries its own chevron", collapsed.contains("class=\"twisty section-twisty\""))
ok("a section body starts hidden", collapsed.contains("class=\"section-body hidden\""))
ok("an expanded page opens its sections", expanded.contains("class=\"section-body\""))
ok("an expanded page hides no section body", !expanded.contains("class=\"section-body hidden\""))
ok("the section header states how many items it holds", collapsed.contains("section-count muted"))
ok("the section header is reachable by keyboard", collapsed.contains("tabindex=\"0\""))

/* Nothing in the page may switch always-expanded ON. A single click that pins
 * depth=full into the URL means the collapsed default is never seen again, on this
 * visit or any later one, and the report then opens as a wall every time. Expanding
 * is done to the page in front of you; the way back out stays reachable. */
ok("no control turns always-expanded on", !collapsed.contains("depth=full"))
ok("the way out of always-expanded is offered while it is on",
    expanded.contains("Leave always-expanded mode"))
ok("the way out actually clears the parameter",
    !Render.html(deep, [project: "X", depth: "full"] as LinkedHashMap, true)
        .contains("?project=X&depth=full\" >"))

/* The marker is matched, not the sentence. "could not be read" is also the label of
 * a summary card at the top of the page, so asserting on the words alone would pass
 * for a page that never marked the section at all. */
/* Both fixtures carry a base URL, because an instance whose address could not be
 * read is itself marked unreadable in the header card, and that marker would drown
 * out the one these two cases are about. */
Report brokenSection = new Report()
brokenSection.instanceBaseUrl = "https://jira.example.com"
brokenSection.section("s", "Workflow scheme").failed("Read failed: RuntimeException")
String brokenCollapsed = Render.html(brokenSection, [:] as LinkedHashMap, false)
ok("a failed read is marked on the header, above the folded body",
    brokenCollapsed.indexOf("class=\"state state-unreadable\"") >= 0 &&
    brokenCollapsed.indexOf("class=\"state state-unreadable\"") < brokenCollapsed.indexOf("section-body"))

Report emptySection = new Report()
emptySection.instanceBaseUrl = "https://jira.example.com"
emptySection.section("s", "Components")
String emptyCollapsed = Render.html(emptySection, [:] as LinkedHashMap, false)
ok("a readable address is rendered as a link", emptyCollapsed.contains(">https://jira.example.com</a>"))

/* An instance whose address could not be read must not print a blank where the
 * address belongs: a report that names no instance describes some Jira, not this
 * one, and a blank would read as though there were nothing to name. */
Report noAddress = new Report()
noAddress.section("s", "Components")
ok("a missing address is marked, not blanked",
    Render.html(noAddress, [:] as LinkedHashMap, false)
        .contains("<strong>Address</strong> <span class=\"state state-unreadable\">"))
ok("an empty section says so on the header, above the folded body",
    emptyCollapsed.indexOf("nothing configured") < emptyCollapsed.indexOf("section-body"))
ok("empty and unreadable sections still differ while both are closed",
    !emptyCollapsed.contains("class=\"state state-unreadable\"") &&
    !brokenCollapsed.contains("nothing configured"))

/* Both views ship in the same page, so the table can never describe a different
 * tree than the one next to it. */
ok("the tree view is rendered", collapsed.contains("class=\"tree view-tree\""))
ok("the table view is rendered too", collapsed.contains("class=\"view-table hidden\""))
ok("the table carries the path column", collapsed.contains("<th>Path</th>"))
ok("the table carries the deepest node", collapsed.contains("SS &gt; Edit Screen"))
ok("both views offer a switch",
    collapsed.contains("setView('tree')") && collapsed.contains("setView('table')"))
ok("the page can expand and collapse itself",
    collapsed.contains("expandAll(true)") && collapsed.contains("expandAll(false)"))

/* A section without children has nothing to tabulate, and an empty table would
 * read as a measured emptiness. */
Report noChildren = new Report()
noChildren.section("s", "Components")
ok("a childless section renders no table",
    !Render.html(noChildren, [:] as LinkedHashMap, false).contains("class=\"view-table hidden\""))

/* ---- 14b. long values are clamped, never cut ----------------------------- */

check("a null value renders as nothing", Render.valueHtml(null), "")
check("a short value is rendered plainly", Render.valueHtml("Story"), "Story")
check("a short value is still escaped", Render.valueHtml("<b>"), "&lt;b&gt;")
ok("a value at the limit is not clamped",
    !Render.valueHtml("x" * Render.VALUE_CLAMP).contains("<details"))

/* The real case this exists for: a scheme description carrying a provenance record
 * with an id list and no space in it. It has no break opportunity, so rendered raw
 * it walks out of the card. */
String provenance = "Managed by Scalpel SchemeMerger type=issue-type-scheme avgSimilarity=1.00 " +
    "participantsBefore=" + (11000..11400).collect { String.valueOf(it) }.join(",")
String clamped = Render.valueHtml(provenance)
ok("a long value is clamped into a details element", clamped.contains("<details class=\"long\""))
ok("the clamp names the full length", clamped.contains(String.valueOf(provenance.length()) + " characters"))
ok("the full text is still in the page", clamped.contains(Pc.html(provenance)))
ok("the clamped preview is only the head",
    clamped.contains(Pc.html(provenance.substring(0, Render.VALUE_CLAMP))))
ok("nothing is silently dropped",
    clamped.contains("show all"))

/* Escaping still applies to the clamped half and to the full body, otherwise a
 * scheme description could close the details element it sits in. */
String hostileLong = "<img src=x onerror=alert(1)>" + ("y" * (Render.VALUE_CLAMP + 50))
String hostileClamped = Render.valueHtml(hostileLong)
ok("a long value cannot inject markup", !hostileClamped.contains("<img src=x"))
ok("the escaped form survives", hostileClamped.contains("&lt;img src=x"))

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
ok("picker lists every project",
    pickerHtml.contains("?project=SCRUM") && pickerHtml.contains("?project=OPS"))
ok("picker escapes a project name", pickerHtml.contains("Operations &amp; &lt;Support&gt;"))
ok("picker states the count", pickerHtml.contains("2 projects"))
ok("picker offers a search field", pickerHtml.contains("id=\"projectQuery\""))
ok("picker ships its own filter", pickerHtml.contains("function filterProjects"))
ok("a project row carries a lowercased search key",
    pickerHtml.contains("data-find=\"ops operations &amp; &lt;support&gt;\""))
ok("the base URL identifies the instance", pickerHtml.contains("<strong>Address</strong>"))

/* The count line is the guard against a filtered list reading as the whole one, so
 * every branch of it is pinned. */
check("all projects, short list", Render.countLine(3, 3), "3 projects")
check("all projects, long list", Render.countLine(600, 600),
    "600 projects, showing the first " + Render.PROJECT_ROWS)
check("a filtered subset names the population", Render.countLine(2, 600),
    "2 of 600 projects match")
check("a capped subset says it is capped", Render.countLine(120, 600),
    "120 of 600 projects match, showing the first " + Render.PROJECT_ROWS)
check("no match says so against the population", Render.countLine(0, 600),
    "no match out of 600 projects")

/* A long list is capped in the markup, not just in the browser, so the first paint
 * is short even before any script runs. */
List<Map<String, String>> manyProjects = new ArrayList<Map<String, String>>()
for (int i = 0; i < Render.PROJECT_ROWS + 25; i++) {
    manyProjects.add([key: "P" + i, name: "Project " + i] as LinkedHashMap)
}
String bigPicker = Render.picker(new Report(), manyProjects, "")
int visibleRows = bigPicker.split("class=\"export-hit\"", -1).length - 1
check("only the cap is visible on first paint", visibleRows, Render.PROJECT_ROWS)
ok("every project is still in the page, just hidden",
    bigPicker.contains("?project=P" + (Render.PROJECT_ROWS + 24)))
ok("the count line names the full population",
    bigPicker.contains(String.valueOf(manyProjects.size()) + " projects"))

Report failedShell = new Report()
failedShell.globalDiagnostics.add("The project list could not be read: RuntimeException. " +
    "This is a failed read, not an empty instance.")
String failedPicker = Render.picker(failedShell, [], "")
ok("a failed project list is not shown as an empty instance",
    failedPicker.contains("could not be read") && failedPicker.contains("not an empty instance"))
ok("an empty instance and a failed read produce different pickers",
    Render.picker(new Report(), [], "") != failedPicker)

/* ---- 18. the Confluence export ------------------------------------------- */

check("title carries the project key", Cx.title("SCRUM"), "Jira project configuration - SCRUM")
check("title without a key still says what it is", Cx.title(null),
    "Jira project configuration - unknown project")
ok("title stays inside the Confluence limit",
    Cx.title("K" * 400).length() <= Cx.MAX_TITLE_CHARS)

check("storage escaping", Cx.esc("<b>&\"x\"</b>"), "&lt;b&gt;&amp;&quot;x&quot;&lt;/b&gt;")

/* A space key is an identifier and is checked, not cleaned. A personal space key
 * keeps its tilde: stripping it turns "~cfaysal" into a key that exists nowhere,
 * and Confluence answers that with zero hits and no error. */
check("a normal space key passes", Cx.spaceKeyProblem("DOCS"), "")
check("a personal space key passes", Cx.spaceKeyProblem("~cfaysal"), "")
ok("an empty key is refused with a reason", Cx.spaceKeyProblem("  ").length() > 0)
ok("a lone tilde is refused with a reason", Cx.spaceKeyProblem("~").length() > 0)
ok("a quote in a key is refused", Cx.spaceKeyProblem("DO\"CS").length() > 0)

/* cqlTerm sanitises a search term. It must never be used on a space key, which is
 * why the two have separate functions and separate tests. */
ok("cqlTerm removes what could end a literal", !Cx.cqlTerm("a\"b*c?d~e").contains("\""))
ok("cqlTerm removes wildcards", !Cx.cqlTerm("a*b?c").contains("*") && !Cx.cqlTerm("a*b?c").contains("?"))

check("no parent requested", Cx.moveDecision("", "10"), Cx.MOVE_NOT_REQUESTED)
check("parent already correct", Cx.moveDecision("10", "10"), Cx.MOVE_ALREADY_THERE)
check("parent must move", Cx.moveDecision("11", "10"), Cx.MOVE_REQUESTED)

ok("a request may not carry both a parent id and a parent title",
    Cx.parentProblem("10", "Some page", "Report") == Cx.PARENT_BOTH)
check("a request with only an id is fine", Cx.parentProblem("10", "", "Report"), "")
check("a request with neither is fine", Cx.parentProblem("", "", "Report"), "")

/* ---- 19. flattening and the carry-over key -------------------------------- */

Map<String, Object> flatRoot = [
    label: "Screens", state: "read",
    children: [[label: "Screen Scheme", state: "read",
                children: [[label: "Edit Screen", state: "read", value: "2 tabs"]]]]
] as LinkedHashMap
List<Map<String, Object>> flatRows = new ArrayList<Map<String, Object>>()
Cx.flatten(flatRoot, "", flatRows)
check("every node becomes a row", flatRows.size(), 3)
check("the path is the chain of labels", flatRows[2].get("path"), "Screens > Screen Scheme > Edit Screen")
check("the root path is the root label", flatRows[0].get("path"), "Screens")

/* ---- 20. render, then read the remarks back ------------------------------- */

Map<String, Object> exportModel = [
    reportVersion: Pc.VERSION,
    generatedAt: "2026-08-26 12:00:00 CEST",
    instance: [title: "Example Jira", jiraVersion: "10.3.19"] as LinkedHashMap,
    project: [key: "SCRUM", name: "Scrum Project"] as LinkedHashMap,
    totals: [nodes: 3, unreadable: 1, unlinked: 0] as LinkedHashMap,
    diagnostics: ["Screens > Screen Scheme: Read failed: RuntimeException"],
    sections: [[
        label: "Permission scheme: Default", state: "read",
        deepLink: "https://jira.example.com/secure/admin/EditPermissions.jspa?schemeId=0",
        children: [
            [label: "Browse Projects", state: "read", value: "2 grants"],
            [label: "Administer Projects", state: "unreadable"]
        ]
    ]]
] as LinkedHashMap

ExportOutcome fresh = Cx.render(exportModel, new RemarkRead())
ok("the page carries the export marker", fresh.storage.contains(Cx.MARKER))
ok("the page carries the remark column header", fresh.storage.contains("<th>" + Cx.COL_REMARK + "</th>"))
ok("the unreadable node is named as unreadable", fresh.storage.contains("could not be read"))
ok("a fresh page seeds every remark cell", fresh.storage.contains(Cx.REMARK_SEED))
ok("the deep link is rendered as a link", fresh.storage.contains("EditPermissions.jspa?schemeId=0"))
ok("the suppressed read is listed", fresh.storage.contains("Suppressed reads"))
check("nothing was carried over into a fresh page", fresh.remarksCarried, 0)

/* The round trip is the property the whole export rests on: what an administrator
 * typed must come back out of the page it was typed into, unchanged. */
RemarkRead readBack = Cx.parseRemarks(fresh.storage)
check("a page this export wrote can be read", readBack.outcome, RemarkRead.PARSED)
check("a seeded cell is not a remark", readBack.remarks.size(), 0)

/* Exactly one cell is edited, the last one, which belongs to the child row
 * "Administer Projects". Editing every cell would make the orphan check below
 * meaningless, because the section root survives every shrink. */
String seedCell = "<td>" + Cx.REMARK_SEED + "</td>"
int lastSeed = fresh.storage.lastIndexOf(seedCell)
ok("the fresh page has a seeded cell to edit", lastSeed >= 0)
String edited = fresh.storage.substring(0, lastSeed) + "<td>keep this one</td>" +
    fresh.storage.substring(lastSeed + seedCell.length())
RemarkRead afterEdit = Cx.parseRemarks(edited)
check("the edited remark is read back", afterEdit.remarks.size(), 1)
ok("the remark keeps its text", afterEdit.remarks.values().iterator().next().contains("keep this one"))

ExportOutcome second = Cx.render(exportModel, afterEdit)
check("the remark is carried into the new page", second.remarksCarried, 1)
ok("the carried remark is verbatim", second.storage.contains("keep this one"))
check("nothing was orphaned", second.orphanKeys.size(), 0)

/* A remark whose item has disappeared is kept, not dropped. */
Map<String, Object> shrunk = Cx.copyMap(exportModel)
shrunk.put("sections", [[label: "Permission scheme: Default", state: "read"]])
ExportOutcome afterShrink = Cx.render(shrunk, afterEdit)
check("the vanished item's remark is orphaned, not lost", afterShrink.orphanKeys.size(), 1)
ok("the orphaned remark is still on the page", afterShrink.storage.contains("keep this one"))
ok("the orphan table explains itself",
    afterShrink.storage.contains("Remarks without a matching item"))

/* ---- 20b. a repeated path must not share one remark ---------------------- */

/* Two grants of the same type under one permission produce the identical label
 * chain. The path is the carry-over key, so left alone that key stamped one
 * administrator's remark onto every row sharing it, counted it once per row, and on
 * the next run the parser found the same path twice with text in both and refused to
 * write anything ever again. Fail-closed, so nothing was lost, but the export
 * bricked itself two runs after the first remark. */
Map<String, Object> twins = [
    reportVersion: Pc.VERSION,
    project: [key: "SCRUM", name: "Scrum"] as LinkedHashMap,
    instance: [:] as LinkedHashMap,
    totals: [:] as LinkedHashMap,
    sections: [[
        label: "Permissions: Default", state: "read",
        children: [[
            label: "Browse Projects", state: "read",
            children: [
                [label: "Project Role", state: "read", value: "Users"],
                [label: "Project Role", state: "read", value: "Administrators"]
            ]
        ]]
    ]]
] as LinkedHashMap

ExportOutcome twinPage = Cx.render(twins, new RemarkRead())
RemarkRead twinRead = Cx.parseRemarks(twinPage.storage)
check("a page with repeated labels is readable", twinRead.outcome, RemarkRead.PARSED)

List<Map<String, Object>> twinRows = new ArrayList<Map<String, Object>>()
for (Map<String, Object> section : Cx.rowsOf(twins, "sections")) {
    Cx.flatten(section, "", twinRows)
}
int beforeUnique = (twinRows.collect { Cx.str(it, "path", "") } as Set).size()
Cx.makePathsUnique(twinRows)
List<String> uniquePaths = twinRows.collect { Cx.str(it, "path", "") }
ok("the duplicate existed in the first place", beforeUnique < twinRows.size())
check("every path is unique afterwards", (uniquePaths as Set).size(), twinRows.size())
ok("the repeat is marked as a repeat", uniquePaths.any { it.endsWith(" #2") })
ok("the first occurrence keeps its plain path", uniquePaths.any { it.endsWith("> Project Role") })

/* The whole round trip: annotate one of the twins, carry it over, and the other twin
 * must NOT inherit it. */
String twinSeed = "<td>" + Cx.REMARK_SEED + "</td>"
int firstTwin = twinPage.storage.lastIndexOf(twinSeed)
String twinEdited = twinPage.storage.substring(0, firstTwin) + "<td>only this one</td>" +
    twinPage.storage.substring(firstTwin + twinSeed.length())
RemarkRead twinEditedRead = Cx.parseRemarks(twinEdited)
check("exactly one remark is read back", twinEditedRead.remarks.size(), 1)
ExportOutcome twinSecond = Cx.render(twins, twinEditedRead)
check("it is carried over exactly once", twinSecond.remarksCarried, 1)
check("and nothing is orphaned by the rename", twinSecond.orphanKeys.size(), 0)
check("the third run is still writable", Cx.parseRemarks(twinSecond.storage).outcome, RemarkRead.PARSED)

/* ---- 20c. one node, one path, in every channel --------------------------- */

Report crossChannel = new Report()
crossChannel.section("issueTypeScheme", "Issue types: X").add(Nd.of("issueType", "Bug"))
String crossHtml = Render.html(crossChannel, [:] as LinkedHashMap, false)
String crossCsv = Render.csv(crossChannel)
ok("the html table prefixes the section label", crossHtml.contains("Issue types: X &gt; Bug"))
ok("the csv prefixes the same label", crossCsv.contains("Issue types: X > Bug"))

/* ---- 21. the write gate --------------------------------------------------- */

/* Every one of these is a page this export did not write, or a page it can no
 * longer read. None of them may be overwritten. */
List<List<Object>> refusals = [
    ["an empty body", Cx.parseRemarks("")],
    ["a null body", Cx.parseRemarks(null)],
    ["a foreign page", Cx.parseRemarks("<p>Somebody else's page</p>")],
    ["a marked page whose table is gone", Cx.parseRemarks("<p>" + Cx.MARKER + "</p>")]
]
for (List<Object> entry : refusals) {
    RemarkRead refused = (RemarkRead) entry[1]
    check("refused: " + entry[0], refused.outcome, RemarkRead.FAILED)
    ok("no write allowed for " + entry[0], !refused.isWriteAllowed())
    ok("the refusal says why for " + entry[0], Pc.text(refused.reason) != null)
    check("a refused read carries no remarks for " + entry[0], refused.remarks.size(), 0)
}

ok("a fresh read allows writing", new RemarkRead().isWriteAllowed())
ok("a parsed read allows writing", readBack.isWriteAllowed())

/* Two remarks for the same path are ambiguous. Guessing which one to keep would
 * silently discard an administrator's text, so nothing is written at all. */
/* The duplicate has to repeat a path that already carries a real remark. A second
 * row against a path whose cell is still seeded is not ambiguous at all, because a
 * seed is not a remark - which is itself worth pinning down, so it is checked
 * right after. */
String duplicatePath = afterEdit.remarks.keySet().iterator().next()
String duplicated = edited.replace("</tbody></table>",
    "<tr><td>" + Cx.esc(duplicatePath) + "</td><td>x</td><td></td><td></td><td></td>" +
    "<td>second remark</td></tr></tbody></table>")
RemarkRead ambiguous = Cx.parseRemarks(duplicated)
check("a duplicated path is refused", ambiguous.outcome, RemarkRead.FAILED)
ok("no write allowed on ambiguity", !ambiguous.isWriteAllowed())

/* A second row against a still-seeded path is NOT ambiguous: the seed carries no
 * text of anybody's, so there is nothing that could be lost by keeping the other. */
String seededTwice = edited.replace("</tbody></table>",
    "<tr><td>Permission scheme: Default</td><td>x</td><td></td><td></td><td></td>" +
    "<td>only remark for this path</td></tr></tbody></table>")
RemarkRead notAmbiguous = Cx.parseRemarks(seededTwice)
check("a seed does not collide with a real remark", notAmbiguous.outcome, RemarkRead.PARSED)
check("both real remarks are read", notAmbiguous.remarks.size(), 2)

/* ---- 22. the row cap is stated, never silent ------------------------------ */

List<Map<String, Object>> manyChildren = new ArrayList<Map<String, Object>>()
for (int i = 0; i < Cx.MAX_ROWS + 10; i++) {
    manyChildren.add([label: "Field " + i, state: "read"] as LinkedHashMap)
}
Map<String, Object> hugeModel = Cx.copyMap(exportModel)
hugeModel.put("sections", [[label: "Fields", state: "read", children: manyChildren]])
ExportOutcome huge = Cx.render(hugeModel, new RemarkRead())
ok("a cut table says it is cut", huge.storage.contains("This table is not complete"))
ok("the cut is reported to the caller as well", !huge.warnings.isEmpty())
ok("the last row beyond the cap is absent", !huge.storage.contains("Field " + (Cx.MAX_ROWS + 9)))
ok("an uncut table makes no such claim", !fresh.storage.contains("This table is not complete"))

/* ---- 23. Every table is collapsed, and collapsing changes nothing else ----- */

/* The page is long by design, so each section table sits inside Confluence's own
 * Expand macro and renders closed. What has to be proven is not that the markup is
 * present - it is that wrapping the tables changed nothing about reading them back,
 * because the remark carry-over is the one thing on this page that is not
 * reproducible from the report. */

int countOf(String haystack, String needle) {
    int total = 0
    int at = haystack.indexOf(needle)
    while (at >= 0) {
        total++
        at = haystack.indexOf(needle, at + needle.length())
    }
    return total
}

String EXPAND_OPEN = "<ac:structured-macro ac:name=\"expand\">"
String EXPAND_CLOSE = "</ac:rich-text-body></ac:structured-macro>"

Map<String, Object> twoSections = Cx.copyMap(exportModel)
twoSections.remove("diagnostics")
twoSections.put("sections", [
    [label: "Details", state: "read", children: [
        [label: "Lead", state: "read", value: "amelia"]
    ]] as LinkedHashMap,
    [label: "Workflows", state: "read", children: [
        [label: "Default workflow scheme", state: "read", value: "jira"]
    ]] as LinkedHashMap
])

ExportOutcome closedPage = Cx.render(twoSections, new RemarkRead())
check("one expand macro per section", countOf(closedPage.storage, EXPAND_OPEN), 2)
check("every expand macro is closed", countOf(closedPage.storage, EXPAND_CLOSE), 2)
check("one table per section", countOf(closedPage.storage, "<table>"), 2)
ok("the first expand is titled with its section and its size",
    closedPage.storage.contains("<ac:parameter ac:name=\"title\">Details (2 items)</ac:parameter>"))
ok("so is the second",
    closedPage.storage.contains("<ac:parameter ac:name=\"title\">Workflows (2 items)</ac:parameter>"))

/* A table outside a macro body would render expanded, which is the thing this
 * change exists to prevent. Cutting every body out of the page has to leave no
 * table behind. */
String withoutBodies = closedPage.storage.replaceAll("(?s)<ac:rich-text-body>.*?</ac:rich-text-body>", "")
ok("no table is left outside a collapsed body", !withoutBodies.contains("<table>"))

RemarkRead collapsedRead = Cx.parseRemarks(closedPage.storage)
check("a collapsed page can still be read", collapsedRead.outcome, RemarkRead.PARSED)
check("a collapsed page with no remarks yields none", collapsedRead.remarks.size(), 0)

/* One remark in each of the two tables. Before the split there was a single table,
 * so a remark that survives here proves the read crosses a section boundary and a
 * macro wrapper rather than stopping at the first table it finds. */
String collapsedSeedCell = "<td>" + Cx.REMARK_SEED + "</td>"
int firstSeed = closedPage.storage.indexOf(collapsedSeedCell)
ok("the collapsed page has a seeded cell in the first table", firstSeed >= 0)
String editedTwice = closedPage.storage.substring(0, firstSeed) + "<td>from section one</td>" +
    closedPage.storage.substring(firstSeed + collapsedSeedCell.length())
int collapsedLastSeed = editedTwice.lastIndexOf(collapsedSeedCell)
ok("and one in the second table", collapsedLastSeed > firstSeed)
editedTwice = editedTwice.substring(0, collapsedLastSeed) + "<td>from section two</td>" +
    editedTwice.substring(collapsedLastSeed + collapsedSeedCell.length())

RemarkRead bothRead = Cx.parseRemarks(editedTwice)
check("a remark in each collapsed table is read", bothRead.remarks.size(), 2)
ExportOutcome carried = Cx.render(twoSections, bothRead)
check("both remarks are carried over", carried.remarksCarried, 2)
ok("the first section keeps its remark", carried.storage.contains("from section one"))
ok("the second section keeps its remark", carried.storage.contains("from section two"))
check("and nothing was orphaned on the way", carried.orphanKeys.size(), 0)

/* A section name is a scheme name, and a scheme name can carry markup. Inside a
 * macro parameter an unescaped angle bracket produces a page Confluence refuses to
 * save, which is the same failure as in a table cell and needs the same escaping. */
Map<String, Object> markupTitle = Cx.copyMap(exportModel)
markupTitle.remove("diagnostics")
markupTitle.put("sections", [[label: "Fields <b> & \"quoted\"", state: "read"] as LinkedHashMap])
ExportOutcome escapedTitle = Cx.render(markupTitle, new RemarkRead())
ok("a section name with markup cannot break out of the macro parameter",
    escapedTitle.storage.contains("<ac:parameter ac:name=\"title\">" +
        "Fields &lt;b&gt; &amp; &quot;quoted&quot; (1 item)</ac:parameter>"))

/* An empty report used to be harmless because the header row was written anyway.
 * With one table per section, no section means no table at all - and a page with no
 * table cannot be read back, which fails closed and refuses every later write. The
 * export would have bricked itself on a report that found nothing. */
Map<String, Object> noSections = Cx.copyMap(exportModel)
noSections.remove("diagnostics")
noSections.put("sections", [])
ExportOutcome emptySectionsPage = Cx.render(noSections, new RemarkRead())
ok("a report with no section still writes a readable table",
    emptySectionsPage.storage.contains("<th>" + Cx.COL_PATH + "</th>"))
check("and the page it wrote can be read back",
    Cx.parseRemarks(emptySectionsPage.storage).outcome, RemarkRead.PARSED)

/* The row cap is a page-wide budget, not a per-table one. A section that starts
 * past the cap gets no macro of its own, because an empty expand titled with a
 * section name reads as a section that has nothing in it. */
List<Map<String, Object>> capFilling = new ArrayList<Map<String, Object>>()
for (int i = 0; i < Cx.MAX_ROWS; i++) {
    capFilling.add([label: "Field " + i, state: "read"] as LinkedHashMap)
}
Map<String, Object> spillModel = Cx.copyMap(exportModel)
spillModel.remove("diagnostics")
spillModel.put("sections", [
    [label: "Fields", state: "read", children: capFilling] as LinkedHashMap,
    [label: "Workflows", state: "read", children: [
        [label: "Only workflow", state: "read"] as LinkedHashMap
    ]] as LinkedHashMap
])
ExportOutcome spill = Cx.render(spillModel, new RemarkRead())
ok("the cut is announced above the tables", spill.storage.contains("This table is not complete"))
ok("nothing past the cap is written", !spill.storage.contains("Only workflow"))
check("the section past the cap gets no macro of its own",
    countOf(spill.storage, EXPAND_OPEN), 1)

/* The two prose blocks below the tables are long as well, and they collapse the
 * same way. exportModel carries exactly one suppressed read. */
ok("the suppressed reads are collapsed too",
    fresh.storage.contains("<ac:parameter ac:name=\"title\">Suppressed reads (1 failed read)</ac:parameter>"))
ok("the orphan table is collapsed too",
    afterShrink.storage.contains("<ac:parameter ac:name=\"title\">Remarks without a matching item (1 remark)</ac:parameter>"))

/* ---- 24. A table inside a remark cell is refused, not silently obeyed ----- */

/* The read works on the page markup, and markup nesting is the one thing a regular
 * expression cannot see. A table pasted into a Remark cell used to end the enclosing
 * table early: every remark below it was dropped and re-seeded on the next write, so
 * an administrator lost text with nothing reporting a failure. The property under
 * test is that this now refuses to write rather than writing the wrong thing. */

ok("a page this export wrote carries no nesting", !Cx.hasNestedTableBody(closedPage.storage))
ok("an empty page carries none either", !Cx.hasNestedTableBody(""))
ok("two tables side by side are not nested",
    !Cx.hasNestedTableBody("<table><tbody><tr><td>a</td></tr></tbody></table>" +
        "<table><tbody><tr><td>b</td></tr></tbody></table>"))
ok("a table inside a table is nested",
    Cx.hasNestedTableBody("<tbody><tr><td><table><tbody><tr><td>x</td></tr></tbody></table></td></tr></tbody>"))

/* The real shape: a page this export wrote, with a table pasted into one remark
 * cell. Built out of the real output rather than by hand, so the test cannot pass
 * against a page shape the export never produces. */
String nestedSeed = "<td>" + Cx.REMARK_SEED + "</td>"
int nestAt = closedPage.storage.indexOf(nestedSeed)
ok("there is a seeded cell to paste a table into", nestAt >= 0)
String withNestedTable = closedPage.storage.substring(0, nestAt) +
    "<td><table><tbody><tr><td>pasted</td></tr></tbody></table></td>" +
    closedPage.storage.substring(nestAt + nestedSeed.length())

RemarkRead nestedRead = Cx.parseRemarks(withNestedTable)
check("a page with a nested table is refused", nestedRead.outcome, RemarkRead.FAILED)
ok("and refusing means nothing is written", !nestedRead.isWriteAllowed())
ok("the refusal says what to do about it", nestedRead.reason.contains("nested"))

/* Without the guard this page parsed as a success and lost the remarks below the
 * pasted table. Proving the old behaviour is what makes the guard worth having:
 * the second remark is on the page and the naive read cannot see it. */
int lastNestSeed = withNestedTable.lastIndexOf(nestedSeed)
String nestedWithRemarkBelow = withNestedTable.substring(0, lastNestSeed) +
    "<td>this one is below the pasted table</td>" +
    withNestedTable.substring(lastNestSeed + nestedSeed.length())
ok("the lost remark really is on the page",
    nestedWithRemarkBelow.contains("this one is below the pasted table"))
check("and the read still refuses rather than dropping it",
    Cx.parseRemarks(nestedWithRemarkBelow).outcome, RemarkRead.FAILED)

/* ---- 25. One sentence about four hundred items is one line, not four hundred - */

/* A note that is true of one item is a finding. The same sentence repeated for every
 * custom field on the instance is a property of the instance, and printing it once
 * per item buries every real finding underneath it. This is what a real run produced:
 * 858 observations, almost all of them the same sentence. */

Report floodReport = new Report()
floodReport.projectKey = "SCRUM"
floodReport.projectName = "Scrum Project"
floodReport.instanceBaseUrl = "https://jira.example.com"
Nd floodSection = floodReport.section("customFields", "Custom fields")
for (int i = 0; i < 400; i++) {
    Nd floodField = Nd.of("customField", "Field " + i)
    floodField.add(Nd.of("contextScope", "Applies to projects").note("This one repeats."))
    floodSection.add(floodField)
}
floodSection.add(Nd.of("customField", "Special").note("This one is on its own."))

Map<String, List<String>> floodGrouped = floodReport.notesByText()
check("four hundred repeats and one singleton are two kinds", floodGrouped.size(), 2)
check("the repeated kind knows how often it occurred", floodGrouped.get("This one repeats.").size(), 400)
ok("and where the first of them was",
    floodGrouped.get("This one repeats.").get(0) == "Custom fields > Field 0 > Applies to projects")

List<String> floodFlat = floodReport.allNotes()
check("the flat form carries one entry per kind, not per item", floodFlat.size(), 2)
ok("a repeated entry says how many it stands for", floodFlat[0].contains("[400 items, first: "))
ok("a single entry is written out with its path",
    floodFlat[1] == "Custom fields > Special: This one is on its own.")

String floodHtml = Render.html(floodReport, [:] as LinkedHashMap, false)
/* Counted inside the card only. The note is deliberately also written at each of the
 * four hundred nodes it belongs to, and counting the whole page would measure that
 * instead of the thing under test. */
int cardStart = floodHtml.indexOf("<div class=\"diag diag-info\">")
ok("the report carries an observations card", cardStart >= 0)
String floodCard = floodHtml.substring(cardStart, floodHtml.indexOf("</ul>", cardStart))
check("the card carries the repeated kind once", countOf(floodCard, "This one repeats."), 1)
ok("while the tree still marks it at every item it belongs to",
    countOf(floodHtml, "This one repeats.") > 400)
ok("the card names the total and the distinct count",
    floodCard.contains("401 observations, 2 of them distinct"))
ok("the repeated kind carries its multiplier", floodCard.contains("<strong>400&#215;</strong>"))
ok("and a few example paths", floodCard.contains("Custom fields &gt; Field 0 &gt; Applies to projects"))
ok("with the rest counted rather than listed", floodCard.contains("and 397 more"))

/* The singleton must survive the grouping. Burying the one real finding under the
 * repeated one is the same defect in the other direction. */
ok("the finding that occurred once is still on the card",
    floodCard.contains("This one is on its own."))

/* A global diagnostic belongs to the run and has no node, so it must not invent a
 * path for itself. */
Report globalReport = new Report()
globalReport.projectKey = "SCRUM"
globalReport.projectName = "Scrum Project"
globalReport.instanceBaseUrl = "https://jira.example.com"
globalReport.globalDiagnostics.add("The instance version could not be read.")
globalReport.section("projectDetails", "Details").add(Nd.of("projectField", "Key").val("SCRUM"))
Map<String, List<String>> globalGrouped = globalReport.diagnosticsByText()
check("the global diagnostic is collected", globalGrouped.size(), 1)
ok("and carries no path", globalGrouped.values().iterator().next().get(0).isEmpty())
String globalHtml = Render.html(globalReport, [:] as LinkedHashMap, false)
ok("the card shows it", globalHtml.contains("The instance version could not be read."))
ok("without an empty location line", !globalHtml.contains("<div class=\"muted\"></div>"))

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"
