/* ===========================================================================
 * Offline harness for the Confluence-free parts of confluenceDCspaceConfig.groovy.
 *
 * The class definitions are prepended verbatim from the real file, so this suite
 * always tests the shipped source and never a copy that can drift. The block it
 * tests is everything between "class Pc {" and the END OF THE CONFLUENCE-FREE
 * BLOCK banner: Pc, Http, Dl, Nd, Report, Render, Cx, RemarkRead and
 * ExportOutcome.
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

/* ---- 14. the export marker and the title prefix are this tool's own ------ */

/* Both tools can write into the same space and the remark parser scans every
 * table on the page it is about to replace. The marker is what keeps them
 * apart, and the title prefix is what keeps an administrator from pointing one
 * at the other's page in the first place. */
check("the marker names this export", Cx.MARKER, "cfcon-space-config-export/1")
ok("and is not the Jira tool's", Cx.MARKER != "cfcon-project-config-export/1")
check("the title prefix names this export", Cx.title("DEV"),
    "Confluence space configuration - DEV")
ok("and is not the Jira tool's prefix", !Cx.title("DEV").startsWith("Jira project configuration"))
check("a missing key does not produce a nameless page", Cx.title(null),
    "Confluence space configuration - unknown space")
ok("the title is capped at what Confluence accepts",
    Cx.title("K" * 400).length() == Cx.MAX_TITLE_CHARS)

/* A page written by the Jira tool carries its own marker and its own Path and
 * Remark columns. Ours must refuse it rather than read its remarks across. */
String jiraPage = "<p><em>cfcon-project-config-export/1</em></p><table><tbody>" +
    "<tr><th>Path</th><th>Remark</th></tr>" +
    "<tr><td>Details &gt; Lead</td><td><p>KEEP</p></td></tr></tbody></table>"
RemarkRead crossRead = Cx.parseRemarks(jiraPage)
check("a page from the Jira tool is not adopted", crossRead.outcome, RemarkRead.FAILED)
ok("and nothing is written into it", !crossRead.isWriteAllowed())
check("and not one of its remarks is read", crossRead.remarks.size(), 0)

/* ---- 15. the remark read is fail-closed, every way it can fail ----------- */

String marked = "<p><em>" + Cx.MARKER + "</em></p>"

RemarkRead noPage = new RemarkRead()
ok("no existing page is not a failed read", noPage.isWriteAllowed())
check("and it is NONE, not PARSED", noPage.outcome, RemarkRead.NONE)

ok("an empty body is refused", !Cx.parseRemarks("").isWriteAllowed())
ok("a null body is refused", !Cx.parseRemarks(null).isWriteAllowed())
ok("a body without the marker is refused", !Cx.parseRemarks(
    "<table><tbody><tr><th>Path</th><th>Remark</th></tr></tbody></table>").isWriteAllowed())
ok("a marked page with no remark table is refused", !Cx.parseRemarks(
    marked + "<p>somebody replaced the tables</p>").isWriteAllowed())
ok("a marked page whose table lost the Remark column is refused", !Cx.parseRemarks(
    marked + "<table><tbody><tr><th>Path</th><th>Item</th></tr>" +
    "<tr><td>a</td><td>b</td></tr></tbody></table>").isWriteAllowed())
ok("a row with too few cells is refused", !Cx.parseRemarks(
    marked + "<table><tbody><tr><th>Path</th><th>Item</th><th>Remark</th></tr>" +
    "<tr><td>a</td></tr></tbody></table>").isWriteAllowed())
ok("the same path twice with text in both is refused", !Cx.parseRemarks(
    marked + "<table><tbody><tr><th>Path</th><th>Remark</th></tr>" +
    "<tr><td>a</td><td>keep</td></tr><tr><td>a</td><td>drop</td></tr></tbody></table>").isWriteAllowed())

/* A table inside a Remark cell ends the enclosing table early as far as a
 * regular expression is concerned, so every remark below it would be dropped
 * silently. That is the one outcome this export must never produce. */
ok("a nested table is refused", !Cx.parseRemarks(
    marked + "<table><tbody><tr><th>Path</th><th>Remark</th></tr>" +
    "<tr><td>a</td><td><table><tbody><tr><td>x</td></tr></tbody></table></td></tr>" +
    "</tbody></table>").isWriteAllowed())
ok("and the refusal says what to do about it",
    Cx.parseRemarks(marked + "<table><tbody><tr><th>Path</th><th>Remark</th></tr>" +
        "<tr><td>a</td><td><table><tbody><tr><td>x</td></tr></tbody></table></td></tr>" +
        "</tbody></table>").reason.contains("Take the nested table out of the Remark cell"))

/* A FAILED read reports its reason and holds no remark, so a caller that ignored
 * the gate would still write an empty column rather than a wrong one. */
RemarkRead refused = Cx.parseRemarks(marked + "<p>no table</p>")
ok("a failed read carries its reason", refused.reason != null && !refused.reason.isEmpty())
check("and no remarks at all", refused.remarks.size(), 0)
check("and reports itself as failed", refused.asMap().get("outcome"), RemarkRead.FAILED)

/* The seed this export writes into an empty Remark cell is its own markup, not
 * an administrator's note, so it must read back as no remark - including after
 * an editor round trip that stamps a macro-id on it and wraps it in a
 * paragraph. */
RemarkRead seeded = Cx.parseRemarks(marked + "<table><tbody>" +
    "<tr><th>Path</th><th>Remark</th></tr>" +
    "<tr><td>a</td><td>" + Cx.REMARK_SEED + "</td></tr>" +
    "<tr><td>b</td><td><p>" + Cx.REMARK_SEED.replace("<ac:structured-macro ",
        "<ac:structured-macro ac:macro-id=\"a1b2\" ") + "</p></td></tr>" +
    "<tr><td>c</td><td></td></tr>" +
    "<tr><td>d</td><td>&#160;</td></tr></tbody></table>")
check("the seed read back is PARSED", seeded.outcome, RemarkRead.PARSED)
check("and carries no remark of its own", seeded.remarks.size(), 0)
ok("a seeded page is writable", seeded.isWriteAllowed())

/* ---- 16. two exports in a row with a remark typed between them ----------- */

/* The offline half of the path the Jira sibling records as never exercised.
 * What can be proven without an instance is that this export's own output is
 * readable by its own parser, that a remark typed into it survives the next
 * render verbatim, and that it survives the one after that. What cannot be
 * proven here is that Confluence stores and returns the markup unchanged - that
 * needs the instance. */
def exportPayload = { Report source, boolean values ->
    Map<String, Object> model = source.toMap()
    Map<String, Object> chosen = new LinkedHashMap<String, Object>()
    chosen.put("values", Boolean.valueOf(values))
    model.put("options", chosen)
    return model
}

Report tripReport = new Report()
tripReport.spaceKey = "DEV"
tripReport.spaceName = "Development"
tripReport.instanceBaseUrl = "https://confluence.example.org"
Nd tripDetails = tripReport.section("spaceDetails", "Details")
tripDetails.note("Schema-coupled. This section reads the Confluence database directly, "
    + "because the Java API cannot answer it.")
tripDetails.add(Nd.of("spaceField", "Status").val("Current"))
tripDetails.add(Nd.of("spaceField", "Lead").val("cfaysal"))
Nd tripPerms = tripReport.section("spacePermissions", "Permissions")
tripPerms.add(Nd.of("spacePermissionType", "VIEWSPACE")
    .add(Nd.of("spacePermissionGrant", "confluence-users")))

ExportOutcome firstRun = Cx.render(exportPayload(tripReport, false), null)
String firstStorage = firstRun.storage

ok("the first run writes the marker onto the page", firstStorage.contains(Cx.MARKER))
ok("one table per section, each inside the bundled expand macro so it opens closed",
    firstStorage.count("<ac:structured-macro ac:name=\"expand\">") >= 2
    && firstStorage.count("<table><tbody>") == 2)
ok("and the sections keep the headings the report used, with their own counts",
    firstStorage.contains("<ac:parameter ac:name=\"title\">Details (3 items)</ac:parameter>")
    && firstStorage.contains("<ac:parameter ac:name=\"title\">Permissions (3 items)</ac:parameter>"))
ok("a schema-coupled section says so on the page, not only in the report",
    firstStorage.contains("Schema-coupled."))
check("nothing was carried over on a first run", firstRun.remarksCarried, 0)

/* The writer's output has to be readable by the reader, or the second run
 * refuses to write and the export bricks itself. */
RemarkRead reread = Cx.parseRemarks(firstStorage)
check("the export reads its own page back", reread.outcome, RemarkRead.PARSED)
check("and finds no remark on it yet", reread.remarks.size(), 0)

/* The administrator types into one cell. */
String typed = "<p>KEEP - agreed with the space owner</p>"
int rowAt = firstStorage.indexOf("<td>Details &gt; Status</td>")
ok("the row that gets the remark is on the page", rowAt >= 0)
int seedAt = firstStorage.indexOf(Cx.REMARK_SEED, rowAt)
String edited = firstStorage.substring(0, seedAt) + typed +
    firstStorage.substring(seedAt + Cx.REMARK_SEED.length())

RemarkRead afterTyping = Cx.parseRemarks(edited)
check("the typed remark is read back", afterTyping.outcome, RemarkRead.PARSED)
check("exactly one of them", afterTyping.remarks.size(), 1)
check("keyed by the path of its row", afterTyping.remarks.get("Details > Status"), typed)

ExportOutcome secondRun = Cx.render(exportPayload(tripReport, false), afterTyping)
check("the second run carries it over", secondRun.remarksCarried, 1)
check("and counts what it read", secondRun.remarksRead, 1)
ok("verbatim, not regenerated", secondRun.storage.contains(typed))
ok("and it is not orphaned", secondRun.orphanKeys.isEmpty())

RemarkRead thirdRead = Cx.parseRemarks(secondRun.storage)
check("and it survives the run after that too", thirdRead.remarks.get("Details > Status"), typed)

/* ---- 17. a remark whose item disappeared is kept, not dropped ------------ */

Report shrunk = new Report()
shrunk.spaceKey = "DEV"
shrunk.spaceName = "Development"
Nd shrunkDetails = shrunk.section("spaceDetails", "Details")
shrunkDetails.add(Nd.of("spaceField", "Lead").val("cfaysal"))

ExportOutcome orphaned = Cx.render(exportPayload(shrunk, false), afterTyping)
check("the remark is not carried into a row", orphaned.remarksCarried, 0)
check("it is named as an orphan instead", orphaned.orphanKeys.size(), 1)
check("under its own path", orphaned.orphanKeys.get(0), "Details > Status")
ok("in a table of its own", orphaned.storage.contains("Remarks without a matching item"))
ok("with the administrator's words intact", orphaned.storage.contains(typed))

/* The orphan table is read back on the next run, which is the whole point of
 * keeping it: a remark parked there is not lost, it is waiting. */
RemarkRead orphanRead = Cx.parseRemarks(orphaned.storage)
check("the orphan table is read back", orphanRead.remarks.get("Details > Status"), typed)

/* ---- 18. the shared row budget ------------------------------------------- */

/* Spending the budget in section order let the first big section starve every
 * section behind it, and a starved section did not appear at all - not even by
 * name. Every section is offered an equal part and gives back what it does not
 * need. */
check("a budget nobody exhausts is handed out in full",
    Cx.shareBudget([10, 20, 30], 100), [10, 20, 30])
check("what a small section does not need goes to the big one",
    Cx.shareBudget([2, 100], 20), [2, 18])
check("and the total is never exceeded",
    Cx.shareBudget([100, 100, 100], 30).sum(), 30)
ok("no section is starved to nothing while another is served",
    Cx.shareBudget([1, 1000, 1000], 100).every { it > 0 })
check("an empty page needs no budget", Cx.shareBudget([], 100), [])

Report bigReport = new Report()
bigReport.spaceKey = "DEV"
bigReport.spaceName = "Development"
Nd smallSection = bigReport.section("spaceDetails", "Details")
for (int i = 0; i < 2000; i++) {
    smallSection.add(Nd.of("spaceField", "Field " + String.valueOf(i)).val("x"))
}
Nd hugeSection = bigReport.section("spacePermissions", "Permissions")
for (int i = 0; i < 4000; i++) {
    hugeSection.add(Nd.of("spacePermissionGrant", "Grant " + String.valueOf(i)).val("x"))
}

ExportOutcome cut = Cx.render(exportPayload(bigReport, false), null)
ok("a page that had to be cut says so at the top",
    cut.storage.contains("This page is not complete."))
ok("and names the sections that were cut", cut.storage.contains("Permissions"))
ok("every section keeps its heading even so", cut.storage.contains("Details ("))
ok("the cut section says what was cut on its own heading",
    cut.storage.contains("the rest is cut"))
ok("and again above its own table", cut.storage.contains("This section is not complete."))
ok("the section that fits is not announced as cut",
    !cut.storage.contains("Details (2001 of"))
ok("the cut is reported to the caller, not only on the page",
    cut.warnings.any { it.startsWith("Cut to 5000 of 6002 rows") })

/* ---- 19. property VALUES never reach the page unless they were asked for -- */

/* The sharpest edge in this tool. The payload travels through the browser, so
 * these reports are deliberately built the way a TAMPERED payload would look:
 * a value is present in the tree even though the report that produced it was
 * run without values=true. The gate has to withhold it anyway. */
Report valueReport = new Report()
valueReport.spaceKey = "DEV"
valueReport.spaceName = "Development"
Nd propertySection = valueReport.section("spaceProperties", "Space properties")
propertySection.add(Nd.of("spaceProperty", "com.acme.integration.token")
    .add(Nd.of("spacePropertyDetail", "Namespace").val("com.acme"))
    .add(Nd.of("spacePropertyDetail", "Value").val("TOPSECRETVALUE42")))
propertySection.add(Nd.of("spaceProperty", "promotedTemplates")
    .add(Nd.of("spacePropertyDetail", "Value").val("PLAINVALUE7")))

String withoutValues = Cx.render(exportPayload(valueReport, false), null).storage
ok("a run without values=true publishes no value at all",
    !withoutValues.contains("TOPSECRETVALUE42") && !withoutValues.contains("PLAINVALUE7"))
/* Escaped on the way into storage format, like every other cell. */
ok("and says why the column is empty",
    withoutValues.contains(Cx.esc(Cx.VALUES_NOT_REQUESTED)))
ok("the keys themselves are still on the page",
    withoutValues.contains("com.acme.integration.token"))

String withValues = Cx.render(exportPayload(valueReport, true), null).storage
ok("a deny-listed key is withheld even when values were asked for",
    !withValues.contains("TOPSECRETVALUE42"))
ok("and the page says it was withheld rather than absent",
    withValues.contains(Cx.esc(Cx.VALUES_DENIED)))
ok("an ordinary value is published when values=true was set",
    withValues.contains("PLAINVALUE7"))
ok("the page states which of the two runs this was",
    withValues.contains("This run was given values=true"))
ok("and the other one states the opposite",
    withoutValues.contains("This run was not given values=true"))

/* A payload with no options block at all is a payload that never declared the
 * flag, and the answer to that is no, not yes. */
Map<String, Object> noOptions = valueReport.toMap()
ok("a payload without an options block publishes nothing",
    !Cx.render(noOptions, null).storage.contains("TOPSECRETVALUE42"))

/* The gate itself, per case, so a change to the caller cannot quietly move it. */
check("no flag, no value", Cx.valueText("spacePropertyDetail", "Value", "anything", "v", false),
    Cx.VALUES_NOT_REQUESTED)
check("flag plus a deny-listed key, no value",
    Cx.valueText("spacePropertyDetail", "Value", "my.secret.key", "v", true), Cx.VALUES_DENIED)
check("flag plus an ordinary key, the value",
    Cx.valueText("spacePropertyDetail", "Value", "promotedTemplates", "v", true), "v")
check("a node that is not a property value is untouched by the gate",
    Cx.valueText("spaceField", "Value", "com.acme.token", "Current", false), "Current")
check("and neither is another detail of the same property",
    Cx.valueText("spacePropertyDetail", "Namespace", "com.acme.token", "com.acme", false), "com.acme")

/* ---- 20. the parent instruction and the position verdict ----------------- */

check("an id and a title at once is refused", Cx.parentProblem("123", "Reports", "Report"),
    Cx.PARENT_BOTH)
check("an id alone is fine", Cx.parentProblem("123", "", "Report"), "")
check("a title alone is fine", Cx.parentProblem("", "Reports", "Report"), "")
check("neither is fine", Cx.parentProblem("", "", "Report"), "")
ok("a page cannot be its own parent",
    Cx.parentProblem("", "Report", "Report").contains("cannot be its own parent"))
ok("and case does not get around that",
    Cx.parentProblem("", "REPORT", "Report").contains("cannot be its own parent"))

check("no parent named, no move", Cx.moveDecision(null, "5"), Cx.MOVE_NOT_REQUESTED)
check("already there, no move", Cx.moveDecision("5", "5"), Cx.MOVE_ALREADY_THERE)
check("elsewhere, move", Cx.moveDecision("5", "9"), Cx.MOVE_REQUESTED)
check("an unknown position resolves to move, not to skip",
    Cx.moveDecision("5", null), Cx.MOVE_REQUESTED)

check("no chain at all measures nothing",
    Cx.innermostAncestor(null).get("measured"), Boolean.FALSE)
check("an empty chain is a measured top-level page",
    Cx.innermostAncestor([]).get("measured"), Boolean.TRUE)
check("and names no parent", Cx.innermostAncestor([]).get("parentId"), null)
check("the direct parent is the last entry of the chain",
    Cx.innermostAncestor(["1", "2", "3"]).get("parentId"), "3")

check("a run that named no parent claims nothing",
    Cx.parentOutcome(null, true, "9", null).get("applied"), null)
check("a read-back that did not answer is unknown, not a failure",
    Cx.parentOutcome("5", false, null, null).get("applied"), Cx.PARENT_APPLIED_UNKNOWN)
check("a read-back that names the requested parent is true",
    Cx.parentOutcome("5", true, "5", null).get("applied"), Cx.PARENT_APPLIED_TRUE)
check("a read-back that names something else is false",
    Cx.parentOutcome("5", true, "9", null).get("applied"), Cx.PARENT_APPLIED_FALSE)
ok("and it says where the page actually sits",
    Cx.parentOutcome("5", true, "9", null).get("reason").toString().contains("under page 9"))
ok("a top-level page is not described as under something",
    Cx.parentOutcome("5", true, null, null).get("reason").toString().contains("at the top level"))

/* ---- 21. the title search terms ------------------------------------------ */

check("words in the order they were typed", Cx.titleTokens("Space configuration DEV"),
    ["Space", "configuration", "DEV"])
check("punctuation separates and never survives into a term",
    Cx.titleTokens("a-b_c*d\"e"), ["a", "b", "c", "d", "e"])
check("nothing but punctuation yields nothing", Cx.titleTokens("***"), [])
check("no query yields nothing", Cx.titleTokens(null), [])
ok("the clause count is bounded",
    Cx.titleTokens((1..40).collect { "w" + it }.join(" ")).size() == Cx.MAX_TITLE_TOKENS)

/* ---- 22. the export writes XHTML, and no address that changes anything ---- */

/* Storage format is XHTML: an unescaped angle bracket out of a template name is
 * not cosmetic, it produces a page Confluence refuses to save. */
Report escReport = new Report()
escReport.spaceKey = "DEV"
escReport.spaceName = "Dev & <Ops>"
escReport.instanceBaseUrl = "https://confluence.example.org"
Nd escSection = escReport.section("spaceTemplates", "Templates")
escSection.add(Nd.of("spaceTemplate", "<script>alert('x')</script>").val("\"quoted\""))
String escaped = Cx.render(exportPayload(escReport, false), null).storage
ok("a tag from the data never reaches the page as markup",
    !escaped.contains("<script>"))
ok("but its text does", escaped.contains("&lt;script&gt;"))
ok("an ampersand is escaped once, not twice", !escaped.contains("&amp;amp;"))
check("the escaper covers all five", Cx.esc("&<>\"'"), "&amp;&lt;&gt;&quot;&#39;")
check("and null is not the string null", Cx.esc(null), "")

boolean exportForbidden = false
String escLower = escaped.toLowerCase(Locale.ROOT)
for (String word : forbidden) {
    if (escLower.contains(word)) {
        exportForbidden = true
    }
}
ok("no state-changing address reaches the exported page", !exportForbidden)
ok("and no do-verb action either", !(escLower =~ /\/do[a-z]+\.action/))

/* An empty report still has to produce a page its own parser accepts, or the
 * next run refuses to write and the export bricks itself. */
ExportOutcome emptyExport = Cx.render(exportPayload(new Report(), false), null)
check("an empty report still writes a readable page",
    Cx.parseRemarks(emptyExport.storage).outcome, RemarkRead.PARSED)

/* ---- 23. the export card, and what it hands the browser ------------------ */

Map<String, Object> valuesOn = [space: "DEV", values: "true"] as LinkedHashMap
Map<String, Object> valuesOff = [space: "DEV"] as LinkedHashMap
String cardOn = Render.html(valueReport, valuesOn, false)
String cardOff = Render.html(valueReport, valuesOff, false)

ok("the report offers the export", cardOn.contains("Export to a Confluence page"))
ok("behind a button, so rendering it reads nothing", cardOn.contains("onclick=\"openExport()\""))
ok("the values flag travels with the payload", cardOn.contains("&quot;values&quot;:true"))
ok("and its absence travels just as explicitly", cardOff.contains("&quot;values&quot;:false"))
ok("the page title is proposed, not demanded",
    cardOn.contains("value=\"Confluence space configuration - DEV\""))
ok("the card says the remark column is the administrator's",
    cardOn.contains("read back from the"))
ok("and that nothing leaves this instance",
    cardOn.contains("nothing is sent anywhere outside this instance"))
ok("the export posts to this same endpoint", cardOn.contains("window.location.pathname"))
ok("no Jira label survived into the export card", !cardOn.contains("in Jira"))

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
