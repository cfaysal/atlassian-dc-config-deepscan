/* ===========================================================================
 * Offline harness for the Confluence-free parts of confluenceDCspaceConfig.groovy.
 *
 * The class definitions are prepended verbatim from the real file, so this suite
 * always tests the shipped source and never a copy that can drift. The block it
 * tests is everything between "class Pc {" and the END OF THE CONFLUENCE-FREE
 * BLOCK banner: Pc, Http, Dl, Nd, Report and Render.
 *
 * Run it the way CI does:
 *   START=$(grep -n '^class Pc {' <file> | cut -d: -f1)
 *   END=$(( $(grep -n '^ \* END OF THE CONFLUENCE-FREE BLOCK$' <file> | cut -d: -f1) - 2 ))
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

/* ---- 1. the deep links, against their own evidence ----------------------- */

/* Every shape is asserted against the exact string the web-item body in
 * probeB5-out.txt produces. A builder that drifts from its evidence is the
 * failure this class exists to prevent, and it cannot be caught by reading. */
Dl links = new Dl("https://confluence.example.org/")

check("trailing slash is dropped", links.base, "https://confluence.example.org")
check("space summary", links.spaceSummary("DEV"),
    "https://confluence.example.org/spaces/viewspacesummary.action?key=DEV")
check("edit space details", links.editSpaceDetails("DEV"),
    "https://confluence.example.org/spaces/editspace.action?key=DEV")
check("space permissions", links.spacePermissions("DEV"),
    "https://confluence.example.org/spaces/spacepermissions.action?key=DEV")
check("space templates keeps the templates2 segment", links.spaceTemplates("DEV"),
    "https://confluence.example.org/pages/templates2/listpagetemplates.action?key=DEV")
check("space theme", links.spaceTheme("DEV"),
    "https://confluence.example.org/spaces/choosetheme.action?key=DEV")
check("colour scheme uses the lookandfeel action", links.spaceColourScheme("DEV"),
    "https://confluence.example.org/spaces/lookandfeel.action?key=DEV")
check("space categories use the editspacelabels action", links.spaceCategories("DEV"),
    "https://confluence.example.org/spaces/editspacelabels.action?key=DEV")

/* The one shape keyed by the space ID rather than the space key, and the reason
 * every builder is templated from its own link body instead of from a rule. */
check("audit log is keyed by space id", links.spaceAuditLog("655361"),
    "https://confluence.example.org/plugins/servlet/audit/resource/Space,655361")

/* A key that is not URL-safe. A personal space key is derived from the user key
 * and an imported key is not guaranteed to be alphanumeric either. */
check("a key is encoded, never pasted", links.spaceSummary("A B&C"),
    "https://confluence.example.org/spaces/viewspacesummary.action?key=A+B%26C")

/* A builder handed nothing returns null rather than a broken URL, so the node
 * ends up with no link and a navigation path in words. */
check("no key means no link", links.spaceSummary(null), null)
check("a blank key means no link", links.spaceSummary("   "), null)
check("no space id means no audit link", links.spaceAuditLog(null), null)

/* No base URL means no link at all. A relative address would work from one page
 * and silently break everywhere the report is pasted. */
Dl noBase = new Dl(null)
check("no base URL means no link", noBase.spaceSummary("DEV"), null)
check("no base URL means no audit link", noBase.spaceAuditLog("1"), null)

/* ---- 2. NO STATE-CHANGING URL, over every builder there is --------------- */

/* The rule this section enforces: removespace is the Delete Space screen, and
 * the watch and unwatch web-items carry an XSRF token. None of them may be
 * reachable from anything this file emits, in any format. Asserted over the
 * builders AND over a rendered page, because a URL can also arrive as a literal
 * in the renderer rather than from Dl. */
List<String> forbidden = ["xsrftoken", "addspacenotification", "removespacenotification", "removespace"]

List<String> everyUrl = []
for (String key : ["DEV", "A B&C"]) {
    everyUrl << links.spaceSummary(key)
    everyUrl << links.editSpaceDetails(key)
    everyUrl << links.spacePermissions(key)
    everyUrl << links.spaceTemplates(key)
    everyUrl << links.spaceTheme(key)
    everyUrl << links.spaceColourScheme(key)
    everyUrl << links.spaceCategories(key)
}
everyUrl << links.spaceAuditLog("655361")

boolean anyForbidden = false
boolean anyDoVerb = false
for (String url : everyUrl) {
    if (url == null) {
        continue
    }
    String lower = url.toLowerCase(Locale.ROOT)
    for (String word : forbidden) {
        if (lower.contains(word)) {
            anyForbidden = true
        }
    }
    /* A Struts action whose name starts with "do" is by convention the one that
     * performs the change rather than showing the form. */
    if (lower =~ /\/do[a-z]+\.action/ || lower.contains(".action!do")) {
        anyDoVerb = true
    }
}
ok("no builder emits a state-changing address", !anyForbidden)
ok("no builder emits a do-verb action", !anyDoVerb)
check("every builder produced a URL", everyUrl.findAll { it != null }.size(), 15)

/* ---- 3. an empty section and a failed read never look alike -------------- */

/* The one distinction the whole report rests on. A control implementation of the
 * naive renderer is carried here on purpose: a suite that has never been red
 * proves nothing, so the discriminating power is measured rather than assumed. */
/* Both reports carry a base URL. Without one the instance card itself prints
 * "could not be read" for the address, and an assertion on that phrase anywhere
 * in the page would then pass whatever the section did. Measured: with the base
 * URL left out, a mutation that made every node claim to be readable did not
 * turn this suite red. */
Report empty = new Report()
empty.spaceKey = "DEV"
empty.spaceName = "Development"
empty.instanceBaseUrl = "https://confluence.example.org"
empty.section("spaceCategories", "Space categories")
    .absent("No category is set on this space.")

Report broken = new Report()
broken.spaceKey = "DEV"
broken.spaceName = "Development"
broken.instanceBaseUrl = "https://confluence.example.org"
broken.section("spaceCategories", "Space categories")
    .failed("SpaceLabelManager was not available.")

def naive = { Report report ->
    /* What a renderer that only looks at the children would produce. */
    StringBuilder out = new StringBuilder()
    for (Nd node : report.sections) {
        out.append(node.label).append(": ").append(node.children.size()).append(" items\n")
    }
    return out.toString()
}
check("the control collapses the two cases", naive(empty), naive(broken))

Map<String, Object> params = [space: "DEV"] as LinkedHashMap
ok("the shipped HTML keeps them apart",
    Render.html(empty, params, false) != Render.html(broken, params, false))
ok("the shipped JSON keeps them apart", Render.json(empty) != Render.json(broken))
ok("the shipped CSV keeps them apart", Render.csv(empty) != Render.csv(broken))

/* Asserted as the whole badge, class included, rather than as the words. The
 * words alone occur elsewhere on the page and an assertion on them passes even
 * when the badge is gone, which is how a report starts rendering a failed read
 * as an empty one without any test noticing. */
String emptyPage = Render.html(empty, params, false)
String brokenPage = Render.html(broken, params, false)
ok("the empty section carries the absent badge",
    emptyPage.contains("<span class=\"state state-absent\">not configured</span>"))
ok("the broken section carries the unreadable badge",
    brokenPage.contains("<span class=\"state state-unreadable\">could not be read</span>"))
ok("the broken section carries no absent badge",
    !brokenPage.contains("<span class=\"state state-absent\">"))
ok("the empty section carries no unreadable badge",
    !emptyPage.contains("<span class=\"state state-unreadable\">"))
ok("the broken page carries the reason",
    brokenPage.contains("SpaceLabelManager was not available."))
/* The sentence a reader takes as "there is nothing here" must never appear on a
 * section whose read failed. */
ok("the broken section never claims nothing is configured",
    !brokenPage.contains("Nothing configured here."))
/* A measured absence carries the measurement, not a shrug. The reason travels
 * into the section value, which is the line under the heading. */
ok("the empty section says what was measured",
    emptyPage.contains("No category is set on this space."))
ok("and the broken one does not carry that sentence",
    !brokenPage.contains("No category is set on this space."))

/* A measured absence is not a failure and must not be counted as one. */
check("an absent section is not an unreadable one", empty.unreadableCount(), 0)
check("an unreadable section is counted", broken.unreadableCount(), 1)

/* ---- 4. a cap announces itself IN the node ------------------------------- */

/* The probe that preceded this file returned exactly 25 permission rows, which
 * was its own cap, and said nothing. The output was indistinguishable from a
 * space with 25 grants. */
Nd capped = Nd.of("spacePermissions", "Permissions").cappedAt(2000, "grants")
check("a capped node is truncated", capped.state, Pc.TRUNCATED)
ok("the cap number is in the node", capped.notes.join(" ").contains("2000"))
ok("the node says the population is larger",
    capped.notes.join(" ").contains("The full population is larger and was not read."))

Report cappedReport = new Report()
cappedReport.spaceKey = "DEV"
cappedReport.sections.add(capped)
capped.add(Nd.of("spacePermissionType", "VIEWSPACE").val("1 grant"))
ok("the cap reaches the page", Render.html(cappedReport, params, false).contains("2000"))
ok("the cap reaches the JSON", Render.json(cappedReport).contains("2000"))
ok("a truncated section is not counted as a failed read", cappedReport.unreadableCount() == 0)

/* ---- 5. withheld is neither absent nor unreadable ------------------------ */

Nd withheld = Nd.of("spacePropertyDetail", "Value").redacted("Withheld.")
check("a redacted node carries its own state", withheld.state, Pc.REDACTED)
check("and its own label", Render.stateLabel(Pc.REDACTED), "withheld")
ok("which is not the absent label", Render.stateLabel(Pc.REDACTED) != Render.stateLabel(Pc.ABSENT))
ok("and not the unreadable label",
    Render.stateLabel(Pc.REDACTED) != Render.stateLabel(Pc.UNREADABLE))
ok("the page has a colour of its own for it", Render.html(empty, params, false).contains(".state-redacted"))

Report redactedReport = new Report()
redactedReport.spaceKey = "DEV"
redactedReport.section("spaceProperties", "Space properties").add(withheld)
check("a withheld value is not a failed read", redactedReport.unreadableCount(), 0)
ok("the CSV carries the state verbatim", Render.csv(redactedReport).contains("\"redacted\""))

/* ---- 6. the permission subject, all four cases --------------------------- */

/* Measured in the bytecode of SpacePermission$AccessSubject on 10.2.14:
 * ALL_AUTHENTICATED_USERS carries "authenticated-users" and ANONYMOUS_USERS
 * carries null. Three null columns is therefore the anonymous grant, a real
 * grant, and never an unreadable row. */
check("a group grant", Pc.subject("confluence-users", null, null, null), "Group: confluence-users")
check("a user grant resolves the key",
    Pc.subject(null, "8aaa81a1", "cfaysal", null), "User: cfaysal (8aaa81a1)")
check("an unresolvable user key is still named, never blank",
    Pc.subject(null, "8aaa81a1", null, null),
    "User: 8aaa81a1 - this user key resolves to no entry in user_mapping")
check("three null columns is the anonymous subject",
    Pc.subject(null, null, null, null), "Anonymous access")
check("the measured literal is all logged-in users",
    Pc.subject(null, null, null, "authenticated-users"), "All logged-in users")
check("an unmeasured subject is printed, not guessed",
    Pc.subject(null, null, null, "something-else"), "Subject: something-else")

check("a name with no key", Pc.userLabel("cfaysal", null), "cfaysal")
check("neither is never blank", Pc.userLabel(null, null), Pc.NA)
check("a blank name is not a name", Pc.userLabel("   ", "key1"),
    "key1 - this user key resolves to no entry in user_mapping")

/* ---- 7. the value deny-list ---------------------------------------------- */

ok("secret", Pc.sensitive("my.secret.thing"))
ok("token", Pc.sensitive("oauth_TOKEN"))
ok("password", Pc.sensitive("Password"))
ok("apikey", Pc.sensitive("service.apikey"))
ok("credential", Pc.sensitive("stored-credentials"))
ok("an ordinary key is not withheld", !Pc.sensitive("promotedTemplates"))
ok("null is not withheld", !Pc.sensitive(null))

/* ---- 8. the stored space values ------------------------------------------ */

/* Read verbatim out of Space.hbm.xml, whose named queries compare spaceType
 * against 'global' and 'personal', and measured on the instance, where
 * spacestatus holds CURRENT or ARCHIVED. An unknown value is reported as itself
 * rather than folded into one of the two. */
check("global", Pc.spaceType("global"), "Global space")
check("personal", Pc.spaceType("personal"), "Personal space")
check("an unknown type is not guessed", Pc.spaceType("team"), "Unknown (team)")
check("no type", Pc.spaceType(null), Pc.NA)
check("current", Pc.spaceStatus("CURRENT"), "Current")
check("archived", Pc.spaceStatus("ARCHIVED"), "Archived")
check("an unknown status is not guessed", Pc.spaceStatus("DRAFT"), "Unknown (DRAFT)")

/* A timestamp is printed as the driver returned it, minus the fractional
 * seconds. A parse that fails silently and yields a plausible wrong date is
 * worse than a machine-shaped one that is right. */
check("fractional seconds are dropped", Pc.stamp("2026-04-24 07:20:52.804"), "2026-04-24 07:20:52")
check("a stamp without them survives", Pc.stamp("2026-04-24 07:20:52"), "2026-04-24 07:20:52")
check("no stamp", Pc.stamp(null), Pc.NA)

/* ---- 9. the picker ------------------------------------------------------- */

check("the whole population", Render.countLine(12, 12), "12 spaces")
check("a filtered population", Render.countLine(3, 12), "3 of 12 spaces match")
check("no match names the population", Render.countLine(0, 12), "no match out of 12 spaces")
check("the row cap is announced", Render.countLine(100, 100), "100 spaces, showing the first 40")

Report shell = new Report()
shell.instanceBaseUrl = "https://confluence.example.org"
List<Map<String, String>> rows = []
rows << ([key: "DEV", name: "Development", type: "global", status: "CURRENT"] as LinkedHashMap)
rows << ([key: "~cfaysal", name: "Faysal", type: "personal", status: "CURRENT"] as LinkedHashMap)
rows << ([key: "OLD", name: "Retired", type: "global", status: "ARCHIVED"] as LinkedHashMap)
String pickerPage = Render.picker(shell, rows, "", 3)

ok("the picker links a space by key", pickerPage.contains("?space=DEV"))
ok("a personal space key is encoded", pickerPage.contains("?space=%7Ecfaysal"))
ok("a personal space says so", pickerPage.contains("personal</span>"))
ok("an archived space says so", pickerPage.contains("archived</span>"))
ok("the picker does not claim a cap it did not hit", !pickerPage.contains("This list is not complete"))

/* A read cap on the picker hides the space somebody is looking for, so it is
 * announced next to the list rather than in a log. */
String cappedPicker = Render.picker(shell, rows, "", 5038)
ok("a capped picker says so", cappedPicker.contains("This list is not complete"))
ok("and names the whole population", cappedPicker.contains("5038"))
ok("and names what it holds", cappedPicker.contains("the first 3"))

/* A picker that could not read the list says that, rather than showing nothing
 * and letting the reader conclude the instance has no spaces. */
Report brokenShell = new Report()
brokenShell.globalDiagnostics.add("The space list could not be read: no executor.")
String brokenPicker = Render.picker(brokenShell, [], "", 0)
ok("a failed list is not an empty instance", brokenPicker.contains("could not be read"))
ok("and is marked as a suppressed read", brokenPicker.contains("Some reads were suppressed"))

/* ---- 10. no state-changing URL reaches a rendered page ------------------- */

/* The builders are one route into the page. A literal in the renderer is the
 * other, and it is the one a test over Dl alone would miss. */
Report full = new Report()
full.spaceKey = "DEV"
full.spaceName = "Development"
full.instanceBaseUrl = "https://confluence.example.org"
Nd details = full.section("spaceDetails", "Details")
details.link(links.spaceSummary("DEV"), null)
details.add(Nd.of("spaceField", "Status").val("Current").link(null, Dl.archiveUnavailableNote()))
Nd perms = full.section("spacePermissions", "Permissions")
perms.link(links.spacePermissions("DEV"), null)
perms.add(Nd.of("spacePermissionType", "VIEWSPACE")
    .add(Nd.of("spacePermissionGrant", Pc.subject(null, null, null, null))
        .add(Nd.of("spacePermissionGrantDetail", "Granted by").val("cfaysal"))))

String page = Render.html(full, params, false)
String pageLower = page.toLowerCase(Locale.ROOT)
boolean pageForbidden = false
for (String word : forbidden) {
    if (pageLower.contains(word)) {
        pageForbidden = true
    }
}
ok("no state-changing address reaches the HTML", !pageForbidden)
ok("and none reaches the JSON",
    !Render.json(full).toLowerCase(Locale.ROOT).contains("removespace"))
ok("and none reaches the CSV",
    !Render.csv(full).toLowerCase(Locale.ROOT).contains("removespace"))
ok("no do-verb action reaches the HTML", !(pageLower =~ /\/do[a-z]+\.action/))

/* The archive node ships without a link and says where to go instead, because
 * no archive action and no archive web-item exists on 10.2.14. */
ok("the archive node carries no link but a path in words",
    page.contains("Space tools &gt; Overview. Archiving a space has no addressable screen"))
check("and is counted as unlinked", full.unlinkedCount(), 1)

/* ---- 11. the report identity travels with the report --------------------- */

ok("the page names the space", page.contains("Development"))
ok("and its key", page.contains("(DEV)"))
ok("and the report version", page.contains("v" + Pc.VERSION))
ok("the footer names it too", page.contains("Space configuration report v" + Pc.VERSION))
ok("the JSON names it", Render.json(full).contains("\"reportVersion\": \"" + Pc.VERSION + "\""))
ok("the labels say Confluence, not Jira", page.contains("open in Confluence"))
ok("and so does the table column", page.contains("In Confluence"))
ok("no Jira label survived the port", !page.contains("in Jira"))

/* ---- 12. the response builder, with a fake JAX-RS Response --------------- */

/* Proves the chain is built in the right order with the right arguments without
 * either JAX-RS namespace on the classpath. */
check("200 goes through ok(entity)",
    FakeResponse.trace(Http.ok(FakeResponse.class, "body", Http.HTML)),
    "ok(body)|type(text/html; charset=UTF-8)|build")
check("anything else goes through status then entity",
    FakeResponse.trace(Http.build(FakeResponse.class, 404, "gone", Http.JSON, null)),
    "status(404)|entity(gone)|type(application/json; charset=UTF-8)|build")
check("headers are appended in order",
    FakeResponse.trace(Http.build(FakeResponse.class, 200, "csv", Http.CSV,
        [("Content-Disposition"): "attachment; filename=\"x.csv\""] as LinkedHashMap)),
    "ok(csv)|type(text/csv; charset=UTF-8)|header(Content-Disposition=attachment; filename=\"x.csv\")|build")

/* ---- 13. no control character reaches the page --------------------------- */

/* A glyph written as a CSS escape inside a Groovy string is read twice: Groovy
 * takes a backslash followed by digits as an OCTAL escape. Built from the code
 * point so this file never has to contain one. */
boolean anyControl = false
for (int cp = 0; cp < 32; cp++) {
    if (cp == 9 || cp == 10 || cp == 13) {
        continue
    }
    if (page.indexOf(String.valueOf((char) cp)) >= 0) {
        anyControl = true
    }
}
ok("no control character reaches the page", !anyControl)

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"

/* The fake stands in for jakarta.ws.rs.core.Response and javax.ws.rs.core.Response
 * at once, which is the whole point: neither is on this classpath and the
 * endpoint never names either. */
class FakeResponse {

    static List<String> calls = new ArrayList<String>()

    static FakeResponse ok(String entity) {
        calls.clear()
        calls.add("ok(" + entity + ")")
        return new FakeResponse()
    }

    static FakeResponse status(Integer code) {
        calls.clear()
        calls.add("status(" + code + ")")
        return new FakeResponse()
    }

    FakeResponse entity(String entity) {
        calls.add("entity(" + entity + ")")
        return this
    }

    FakeResponse type(String contentType) {
        calls.add("type(" + contentType + ")")
        return this
    }

    FakeResponse header(String name, String value) {
        calls.add("header(" + name + "=" + value + ")")
        return this
    }

    String build() {
        calls.add("build")
        return calls.join("|")
    }

    static String trace(Object built) {
        return String.valueOf(built)
    }
}
