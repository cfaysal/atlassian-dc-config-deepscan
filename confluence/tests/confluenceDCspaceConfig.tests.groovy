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
Nd capped = Nd.of("spacePermissions", "Permissions")
    .cappedAt(2000, "grants", "permission type, then grant id", "The Permissions screen lists them all.")
check("a capped node is truncated", capped.state, Pc.TRUNCATED)
ok("the cap number is in the node", capped.notes.join(" ").contains("2000"))
ok("the node says the population is larger",
    capped.notes.join(" ").contains("not the number that exists"))

/* "The first N" is only half an announcement. The landing page said "the first
 * 2000" of 5038 spaces and never said the order was by space name, so a reader
 * could not tell whether the tail of an alphabet or an arbitrary slice had been
 * cut, nor go and find it. Both halves are arguments now, so no call site can
 * forget one. */
ok("a cap names the ordering it cut by",
    capped.notes.join(" ").contains("ordered by permission type, then grant id"))
ok("and the route to what it cut",
    capped.notes.join(" ").contains("The Permissions screen lists them all."))

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

/* ---- 9. the landing page is an estate sweep, not a picker ---------------- */

/* The page it replaced listed the first 2000 of 5038 space names. There is no
 * question an administrator asks that a truncated alphabet of names answers, so
 * the landing page now carries one row per space for every space, with the
 * stored facts they triage on, and every key still links into the per-space
 * report. */

check("the whole population", Render.estateCountLine(12, 12), "12 spaces")
check("a filtered population", Render.estateCountLine(3, 12), "3 of 12 spaces match")
check("no match names the population", Render.estateCountLine(0, 12), "no match out of 12 spaces")

/* The old count line ended in ", showing the first 40". Nothing is windowed any
 * more: every delivered row is on the page and visible, because an administrator
 * who cannot see all of them cannot analyse all of them. */
ok("no row window is announced, because there is none",
    !Render.estateCountLine(5038, 5038).contains("showing the first"))

def sweepRow = { String key, String name, String type, String status,
                 String grants, String anon, String admins, String cats,
                 String css, String stores, String storeCount ->
    return ([key: key, name: name, type: type, status: status, grants: grants,
             anon: anon, admins: admins, categories: cats, stylesheet: css,
             appconfig: stores, appconfigCount: storeCount] as LinkedHashMap)
}

def sweepOf = { List<Map<String, String>> rows, Map<String, String> columnFails ->
    return ([rows: rows, columnFailures: columnFails, failure: null,
             truncated: Boolean.FALSE, cap: Integer.valueOf(20000),
             order: "space key"] as LinkedHashMap)
}

Report shell = new Report()
shell.instanceBaseUrl = "https://confluence.example.org"
List<Map<String, String>> estateRows = []
estateRows << sweepRow("DEV", "Development", "global", "CURRENT", "44", "12", "3", "0", "0", "plugin settings, Bandana", "2")
estateRows << sweepRow("ENG", "Engineering", "global", "CURRENT", "0", "0", "0", "2", "1", "plugin settings", "1")
estateRows << sweepRow("HR", "People", "global", "CURRENT", "9", "0", "0", "0", "0", "", "0")
estateRows << sweepRow("~cfaysal", "Faysal", "personal", "CURRENT", "6", "0", "1", "0", "0", "", "0")
estateRows << sweepRow("OLD", "Retired", "global", "ARCHIVED", "5", "0", "1", "0", "0", "", "0")

String estatePage = Render.estate(shell, sweepOf(estateRows, [:] as LinkedHashMap), "")

ok("the sweep links a space by key", estatePage.contains("?space=DEV"))
ok("a personal space key is encoded", estatePage.contains("?space=%7Ecfaysal"))
ok("every delivered row is on the page", estatePage.contains("?space=OLD"))
check("and no row was dropped", estatePage.split("[?]space=", -1).length - 1, estateRows.size())
ok("a personal space says so", estatePage.contains("Personal space"))
ok("an archived space says so", estatePage.contains("Archived"))
ok("the sweep does not claim a cap it did not hit", !estatePage.contains("This sweep is not complete"))

/* The row of one space, so an assertion can be about THAT space rather than
 * about the page containing a substring somewhere. A page-wide contains() is how
 * a test passes for the wrong reason: "anon" is also in the class name col-anon
 * on every single row. */
def rowOf = { String page, String key ->
    int anchor = page.indexOf('?space=' + key + '"')
    if (anchor < 0) { return null }
    int open = page.lastIndexOf('<tr ', anchor)
    int close = page.indexOf('</tr>', anchor)
    return open < 0 || close < 0 ? null : page.substring(open, close)
}
def flagsOf = { String page, String key ->
    String row = rowOf(page, key)
    if (row == null) { return "no such row" }
    int at = row.indexOf('data-flags="')
    return at < 0 ? "no flags" : row.substring(at + 12, row.indexOf('"', at + 12))
}

/* A row that is absent must FAIL an assertion, never throw one. A harness that
 * throws stops the run and hides every assertion after it: the mutation that
 * made the sweep drop rows was detected by a NullPointerException rather than by
 * the assertion written for it, which is detection by accident. */
def rowText = { String page, String key -> rowOf(page, key) ?: "" }

ok("the row helper finds a row", rowOf(estatePage, "DEV") != null)
ok("and does not find one that is not there", rowOf(estatePage, "NOSUCH") == null)
ok("and a missing row fails an assertion rather than throwing one",
    !rowText(estatePage, "NOSUCH").contains("anything"))

/* Zero explicit grants is a configuration state - the defaults apply - and is
 * rendered as words rather than as a bare 0 in a column of numbers, which reads
 * as "nothing found" to anyone scanning. It is also NOT unreadable: the
 * statement ran and returned a zero. */
ok("no explicit grants is written out as a state, on the space that has none",
    rowText(estatePage, "ENG").contains("none, defaults apply"))
ok("and that row carries no unreadable cell",
    !rowText(estatePage, "ENG").contains("state-unreadable"))
ok("a space that HAS grants shows the number instead",
    rowText(estatePage, "DEV").contains(">44<")
        && !rowText(estatePage, "DEV").contains("none, defaults apply"))

/* Both traps of this sweep have a visible, per-row consequence. */
check("the space granting anonymous access carries that flag and no other case",
    flagsOf(estatePage, "DEV"), "anon app")
check("a space with grants but no administrator is flagged ownerless",
    flagsOf(estatePage, "HR"), "noadmin orphan")
check("a space with no grants at all is a different case, and is not ownerless",
    flagsOf(estatePage, "ENG"), "nogrants noadmin app categories css")
check("an archived space carries the archived flag", flagsOf(estatePage, "OLD"), "archived")
/* The key is URL-encoded in the link, so the row is looked up by the encoded
 * form. A personal space key is derived from a user key and is not URL-safe. */
check("and a personal space the personal one", flagsOf(estatePage, "%7Ecfaysal"), "personal")
ok("a space with an administrator is not flagged as missing one",
    !flagsOf(estatePage, "DEV").contains("noadmin"))

/* The two no-administrator numbers are reported separately, because a space with
 * no grants at all is a space on the defaults while a space that has grants and
 * no SETSPACEPERMISSIONS row is a space somebody configured and left ownerless.
 * Of the five rows above: ENG and HR have no administrator, and HR has grants. */
ok("the summary counts spaces with no administrator",
    estatePage.contains("with no space administrator"))
ok("and counts the ownerless ones separately",
    estatePage.contains("of those have grants but no administrator"))

/* ---- 9b. an unreadable column is never a measured zero ------------------- */

/* The failure this whole report exists to prevent. A side statement that failed
 * must not leave a column of zeroes behind: "no space has a category" and "the
 * categories were not read" are different sentences and only one is true. */
Map<String, String> brokenColumnFail = [categories: "The table label does not carry the column namespace."] as LinkedHashMap
String brokenColumn = Render.estate(shell, sweepOf(estateRows, brokenColumnFail), "")
ok("an unreadable column says so above the table",
    brokenColumn.contains("Categories column could not be read"))
ok("and names the reason", brokenColumn.contains("does not carry the column namespace"))
/* EVERY cell in that column, not "the page mentions unreadable somewhere", and
 * counted inside the table body so the CSS rule of the same name cannot make the
 * assertion pass on its own. */
def bodyOf = { String page ->
    int open = page.indexOf('<tbody id="estateBody">')
    return open < 0 ? "" : page.substring(open, page.indexOf("</tbody>", open))
}
check("every row cell in the column is marked unreadable, and exactly those",
    bodyOf(brokenColumn).split("state-unreadable", -1).length - 1, estateRows.size())
check("and a healthy column leaves no unreadable cell at all",
    bodyOf(estatePage).split("state-unreadable", -1).length - 1, 0)
ok("the cell of a space with two categories no longer claims two",
    !rowText(brokenColumn, "ENG").contains(">2<"))
ok("and it does not claim zero either",
    !rowText(brokenColumn, "HR").contains("col-categories num\" data-sort=\"0\">0<"))
ok("its summary tile is unreadable rather than a count",
    brokenColumn.contains("with space categories"))
ok("a column that WAS read is untouched by another column failing",
    rowText(brokenColumn, "ENG").contains("none, defaults apply"))
ok("and the other columns still carry their numbers",
    rowText(brokenColumn, "DEV").contains(">44<"))

/* ---- 9c. a failed sweep is not an empty instance ------------------------- */

Map<String, Object> deadSweep = [rows: [], columnFailures: [:] as LinkedHashMap,
    failure: "The SAL read-only executor factory resolved but no component was returned.",
    truncated: Boolean.FALSE, cap: Integer.valueOf(20000), order: "space key"] as LinkedHashMap
String deadPage = Render.estate(shell, deadSweep, "")
ok("a failed sweep says so", deadPage.contains("The estate could not be read"))
ok("and names the reason", deadPage.contains("no component was returned"))
ok("and refuses to read as an instance with no spaces",
    deadPage.contains("No row below is missing: none was read at all."))
ok("and prints no summary it did not measure", !deadPage.contains("spaces, every one of them below"))
/* And no table either. An empty table under the banner still carries a count
 * line, and "no match out of 0 spaces" is a measurement of a population that was
 * never read. */
ok("and no table at all", !deadPage.contains("<tbody id=\"estateBody\">"))
ok("so it cannot report a population it never read",
    !deadPage.contains("out of 0 spaces"))

/* A value that is not a number is not a zero either, even when its column read
 * fine. */
Map<String, String> odd = ([key: "ODD", name: "Odd", type: "global", status: "CURRENT",
    grants: "not a number", anon: "0", admins: "1", categories: "0", stylesheet: "0",
    appconfig: "", appconfigCount: "0"] as LinkedHashMap)
String oddPage = Render.estate(shell, sweepOf([odd], [:] as LinkedHashMap), "")
ok("an unparseable count is unreadable, not zero",
    rowText(oddPage, "ODD").contains("state-unreadable"))
ok("and is not reported as a space running on the defaults",
    !rowText(oddPage, "ODD").contains("none, defaults apply"))

/* ---- 9d. a cut announces its ordering and the route past it -------------- */

Map<String, Object> cutSweep = sweepOf(estateRows, [:] as LinkedHashMap)
cutSweep.put("truncated", Boolean.TRUE)
String cutPage = Render.estate(shell, cutSweep, "")
ok("a cut sweep says so", cutPage.contains("This sweep is not complete"))
ok("and names what it holds", cutPage.contains("the first 5 spaces"))
ok("and the ORDER it cut by, which the old banner never did",
    cutPage.contains("ordered by space key"))
ok("and how to reach what was cut",
    cutPage.contains("putting its key in the space parameter"))
ok("and calls the number its own rather than the population",
    cutPage.contains("this report's own cap and not the number that exists"))

/* ---- 9e. no round trip, and no state-changing address -------------------- */

/* Filtering and sorting are client-side over the delivered rows on purpose: the
 * page has to keep working after it has been saved or mailed on. */
ok("the sweep issues no request of its own",
    !estatePage.contains("XMLHttpRequest") && !estatePage.contains("fetch("))
String estateLower = estatePage.toLowerCase(Locale.ROOT)
boolean estateForbidden = false
for (String word : forbidden) {
    if (estateLower.contains(word)) {
        estateForbidden = true
    }
}
ok("no state-changing address reaches the sweep", !estateForbidden)
ok("no do-verb action reaches the sweep", !(estateLower =~ /\/do[a-z]+\.action/))

/* A control character in the page is the bug that made this file read as binary
 * once already. Built from the code point so this suite never contains one. */
boolean estateControl = false
for (int cp = 0; cp < 32; cp++) {
    if (cp == 9 || cp == 10 || cp == 13) {
        continue
    }
    if (estatePage.indexOf(String.valueOf((char) cp)) >= 0) {
        estateControl = true
    }
}
ok("no control character reaches the sweep", !estateControl)

/* ---- 9f. the columns are declared once ----------------------------------- */

/* The header, the body and the sort all read one list, so a column cannot exist
 * in one of the three and be missing from the other two. */
ok("every declared column has a heading in the table", Render.SWEEP_COLUMNS.every {
    estatePage.contains('<th class="col-' + it.get(0))
})
ok("and an explanation under it", Render.SWEEP_COLUMNS.every {
    estatePage.contains(it.get(3))
})
check("the heading lookup answers for a real column", Render.columnHeading("anon"), "Anonymous")
check("and returns the id rather than throwing for one it does not know",
    Render.columnHeading("nosuchcolumn"), "nosuchcolumn")

/* ---- 9g. a count that is not a number is not a zero ---------------------- */

check("a stored count reads as its number", Render.asCount("12"), 12)
check("a measured none reads as zero", Render.asCount("0"), 0)
check("nothing at all is not a zero", Render.asCount(null), -1)
check("and neither is a value that is not a number", Render.asCount("many"), -1)
check("whitespace around a number is not a failure", Render.asCount(" 7 "), 7)

/* ---- 9h. the store namespace rule, wildcards and all --------------------- */

/* The per-space form of a property-store namespace is <plugin namespace>:<KEY>
 * and the bare form is the key itself, both measured on the instance. The SQL
 * shape of this rule would be LIKE '%:' || spacekey, where an underscore in a
 * space key is a WILDCARD - so it is done here, on strings, with no wildcard in
 * sight. */
check("the suffixed form yields the space key",
    Pc.storeSpaceKey("com.atlassian.confluence.blueprints.plugin-module-state:ENG"), "ENG")
check("the bare form is the space key itself", Pc.storeSpaceKey("DEV"), "DEV")
check("the last colon wins", Pc.storeSpaceKey("a:b:DEV"), "DEV")
check("a trailing colon names no space", Pc.storeSpaceKey("com.example:"), null)
check("nothing names no space", Pc.storeSpaceKey(null), null)
check("and neither does blank", Pc.storeSpaceKey("   "), null)
/* The wildcard case, which is the whole reason this is not SQL. A key holding an
 * underscore must match only itself. */
check("an underscore is a character here, not a wildcard",
    Pc.storeSpaceKey("com.example:A_C"), "A_C")
ok("so it does not answer for a key that a LIKE pattern would have matched",
    Pc.storeSpaceKey("com.example:ABC") != "A_C")

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
/* The counts are the RECORDS in the section, not the nodes of the tree. Details
 * holds two fields; Permissions holds one grant under one permission type, and
 * the section and the type are columns on that grant rather than rows of their
 * own. A container that carried a row here was the defect. */
ok("and the sections keep the headings the report used, with their own record counts",
    firstStorage.contains("<ac:parameter ac:name=\"title\">Details (2 items)</ac:parameter>")
    && firstStorage.contains("<ac:parameter ac:name=\"title\">Permissions (1 item)</ac:parameter>"))
/* The heading is now the ONLY place the section is named. A first column reading
 * "Details" on every row of the Details table is what the table view does not do
 * and what this export did until now. */
ok("and the name on the heading is in no cell of the table under it",
    !firstStorage.contains("<td>Details</td>") && !firstStorage.contains("<td>Permissions</td>"))
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
/* One level per column BELOW the member, so the row is found by its own level
 * cells: the section's name is on the heading above the table and in no cell of
 * it. The key it is carried over by is still every level joined, which is the
 * string the oldest layout put in the Path cell - the heading supplies the
 * leading segment the row no longer carries. */
int rowAt = firstStorage.indexOf("<tr><td>Status</td><td>Current</td>")
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
    !cut.storage.contains("Details (2000 of"))
/* 6000, not 6002: the two section nodes are containers and a container is a
 * column on the rows below it rather than a row of its own. */
ok("the cut is reported to the caller, not only on the page",
    cut.warnings.any { it.startsWith("Cut to 5000 of 6000 rows") })

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

/* ---- 24. the deprecation source contract --------------------------------- */

/* Three deprecated calls were removed on OP-1005 and none of them may come back.
 * A comment saying so binds only the reader who happens to open that file, so it
 * is asserted here instead, against the SHIPPED source: the property reader and
 * the endpoint bodies sit on the far side of the Confluence-free banner and can
 * never be loaded by an offline suite, which leaves the text of the file as the
 * only thing this suite can hold them to.
 *
 * The ScriptRunner editor is where the warnings surfaced. It reports them to
 * whoever has the file open and to nobody else, which is not enforcement.
 */
File endpointSource = new File("confluence/confluenceDCspaceConfig.groovy")
if (!endpointSource.isFile()) {
    endpointSource = new File("../confluenceDCspaceConfig.groovy")
}
ok("the deprecation contract can read the endpoint source", endpointSource.isFile())
String endpointText = endpointSource.isFile() ? endpointSource.getText("UTF-8") : ""
ok("and the source it read is not empty", endpointText.length() > 0)

/* The type names are allowed to survive in prose - the comments explain what was
 * replaced and why, and a rule that forbids its own explanation gets a worse
 * comment rather than better code. Comment lines are therefore cut before the
 * type names are checked, exactly as the CI gate for a bare call( does it. */
String endpointCode = endpointText.readLines()
    .findAll { String line -> !(line.trim() ==~ /^(\*|\/\/|\/\*).*/) }
    .join("\n")
ok("cutting the comments left the code behind", endpointCode.contains("class Scan {"))

/* 1. SpaceManager.getSpace, deprecated since 7.3.0. */
ok("no deprecated SpaceManager lookup remains", !endpointText.contains("spaceManager.getSpace("))
ok("the SpaceManager type is not named in code at all", !endpointCode.contains("SpaceManager"))

/* 2. BandanaManager, deprecated since 9.3 and marked for removal in 11.0. */
ok("no BandanaManager import remains",
    !endpointText.contains("import com.atlassian.bandana.BandanaManager"))
ok("the BandanaManager type is not named in code at all", !endpointCode.contains("BandanaManager"))
ok("no Bandana context is constructed", !endpointCode.contains("ConfluenceBandanaContext"))

/* 3. BandanaManager.getValue, deprecated since 9.4. Map.Entry.getValue is all
 * over this file and is a different method, so the receiver is what is matched. */
ok("no getValue call on a bandana receiver remains",
    !((endpointCode =~ /(?i)bandana[A-Za-z]*\s*\.\s*getValue\s*\(/).find()))

ok("deprecation warnings are not suppressed", !endpointText.contains("SuppressWarnings"))

/* The values parameter is a CHOICE this report makes, not a measurement of the
 * space. Both branches that decline to print a value are therefore withheld, not
 * absent: the deny-list one and the plain values=false one. Seen live on ENG,
 * where the only property carried the state "not configured" while the text
 * beside it read "Not read. Pass values=true" - a policy dressed as a finding. */
ok("declining to read values is withheld, not absent",
    endpointCode.contains("value.redacted(\"Not read. Pass values=true to read property values.\")"))
ok("and no property value is ever called absent", !endpointCode.contains("value.absent("))

/* The replacements, asserted positively. A ban with no counterpart passes just as
 * well when the feature was deleted as when it was fixed. */
check("the space lookup uses the SpaceService locator, in both endpoints",
    endpointText.count("spaceService.getKeySpaceLocator("), 2)
ok("the persistence SpaceService is imported, not the API one",
    endpointText.contains("import com.atlassian.confluence.content.service.SpaceService"))
/* This used to assert that the API SpaceService kept a distinct alias. It has no
 * import left to keep a name for: the type is a Spring proxy the ScriptRunner
 * chaining classloader cannot load, and it went out on OP-1005 together with the
 * picker that resolved it. Section 25 holds the ban.
 *
 * What belongs HERE is the other half of that sentence, and it is the half that
 * could be broken by a careless ban: the persistence layer must not have gone out
 * with it. The count is asserted rather than the presence, so an API import
 * creeping back under any alias fails this line too. */
check("exactly one SpaceService import survives, and it is the persistence one",
    endpointText.readLines().count { String line ->
        line.startsWith("import ") && line.contains("SpaceService") }, 1)
ok("the plugin settings factory is imported",
    endpointText.contains(
        "import com.atlassian.confluence.api.service.settings.ExtendedPluginSettingsFactory"))
check("and it is asked for this space exactly once",
    endpointCode.count("settingsFactory.createSettingsForKey("), 1)

/* The store attribution survived the swap. It moved from the API to SQL, and the
 * point of these four is that it moved rather than went away: the API answers one
 * merged view and can no longer say which table a key sits in. */
ok("the Bandana store is still read, now from its own table",
    endpointText.contains("FROM bandana b WHERE b.bandanacontext = ?"))
ok("the Bandana attribution survived", endpointCode.contains("\"Bandana\""))
ok("the plugin settings attribution survived", endpointCode.contains("\"plugin settings\""))
ok("the content properties attribution survived", endpointCode.contains("\"content properties\""))
ok("and a key no table located is labelled rather than left blank",
    endpointCode.contains("STORE_UNLOCATED"))

/* ---- 25. the space picker reads the database, not a Spring proxy --------- */

/* The export picker resolved com.atlassian.confluence.api.service.content.SpaceService
 * and failed every single time it was opened, with
 *
 *   IllegalArgumentException: org.springframework.aop.SpringProxy referenced from
 *   a method is not visible from class loader ChainingClassLoader
 *
 * reproduced on two independent Confluence 10.2.14 instances. The API service
 * layer is a Spring proxy that classloader cannot use, which this project had
 * already measured for SpacePropertyService and for
 * EffectiveSpacePermissionsCalculator. The picker was lifted from the App
 * Footprint sibling on the strength of running THERE and was never held to that
 * measurement, which is the mistake this section exists to stop repeating.
 *
 * The picker and the statement that replaced it sit on the far side of the
 * Confluence-free banner and can never be loaded by an offline suite, so the text
 * of the shipped source is all this suite can hold them to. That is a weaker claim
 * than running the code, and it is stated rather than dressed up: it stops the
 * import coming back by habit, which is exactly how it arrived. It proves nothing
 * about the running instance.
 */

/* 1. the ban. Comments still explain what was removed and why, so the package is
 * matched as an IMPORT rather than as a string anywhere in the file. */
ok("no import from the proxied API service package survives",
    !(endpointText =~ /(?m)^import\s+com\.atlassian\.confluence\.api\.service\.content/).find())
ok("the alias it was imported under is named nowhere in code",
    !endpointCode.contains("ApiSpaceService"))
ok("nor is the finder that service handed back", !endpointCode.contains("SpaceFinder"))
check("and the four API model types it needed are gone with it",
    ["ApiSpace", "ApiSpaceStatus", "PageResponse", "SimplePageRequest"]
        .count { String type -> endpointCode.contains(type) }, 0)

/* api.service.SETTINGS is a different package and the one measured exception on
 * this instance. A ban that swept it up would be a regression, so its survival is
 * asserted rather than assumed. */
ok("the plugin settings factory, which IS acquirable here, is untouched",
    endpointText.contains(
        "import com.atlassian.confluence.api.service.settings.ExtendedPluginSettingsFactory"))

/* 2. the statement that replaced it. Read out of the source as declared, so the
 * assertions below are about the shipped text and not about a copy. */
java.util.regex.Matcher pickerMatch =
    (endpointText =~ /(?s)static final String PICKER_SQL =(.*?)\n\n/)
boolean pickerFound = pickerMatch.find()
ok("the picker statement is declared as its own constant", pickerFound)
String pickerSql = pickerFound
    ? pickerMatch.group(1).replaceAll(/["+]/, " ").replaceAll(/\s+/, " ").trim() : ""

ok("it is a SELECT", pickerSql.startsWith("SELECT "))
ok("over the spaces table", pickerSql.contains("FROM spaces s"))
ok("returning the key and the name", pickerSql.contains("SELECT s.spacekey, s.spacename"))
ok("restricted to one status", pickerSql.contains("WHERE s.spacestatus = ?"))
check("through exactly one placeholder", pickerSql.count("?"), 1)
/* The length is on both negatives on purpose. An empty pickerSql - which is what
 * a file WITHOUT the statement yields - contains neither the status value nor an
 * interpolation, so both would pass on a source that has no picker at all. An
 * empty result is not evidence of absence. */
ok("and the status VALUE is nowhere in the statement text",
    pickerSql.length() > 0 && !pickerSql.contains("CURRENT"))
ok("because it travels as a bound argument",
    endpointCode.contains("static final String PICKER_STATUS = \"CURRENT\""))
ok("the ordering is in the statement, so the cap cuts by the order it shows",
    pickerSql.contains("ORDER BY LOWER("))
ok("by name first", pickerSql.indexOf("s.spacename") < pickerSql.lastIndexOf("s.spacekey"))
ok("nothing is interpolated into it: the constant is two string literals and a plus",
    pickerSql.length() > 0 && !(pickerSql =~ /\$\{/).find())

/* 2b. Oracle. Confluence stores text as NVARCHAR2 there, a SQL string literal is
 * CHAR, and COALESCE, NULLIF, DECODE and || all require ONE character set across
 * their arguments. Mixing them raises ORA-12704 and nothing on PostgreSQL ever
 * says so. The picker shipped with exactly that mistake and only a customer
 * instance caught it, so the ban is on EVERY statement in the file, not on the
 * one that made it. Numeric defaults such as COALESCE(g.grants, 0) carry no
 * character set and stay allowed. */
List<String> charsetOffenders = new ArrayList<String>()
java.util.regex.Matcher sqlDecl =
    (endpointCode =~ /(?s)static final String ([A-Z_]+_SQL)\s*=(.*?)\n\n/)
int sqlConstants = 0
while (sqlDecl.find()) {
    sqlConstants++
    String body = sqlDecl.group(2).replaceAll(/\s+/, " ")
    if ((body =~ /(?i)(COALESCE|NULLIF|DECODE)\s*\([^)]*'[^']*'/).find() ||
        (body =~ /\|\|\s*'/).find() || (body =~ /'[^']*'\s*\|\|/).find()) {
        charsetOffenders.add(sqlDecl.group(1))
    }
}
ok("the statement constants were actually found, so the ban is not vacuous", sqlConstants >= 10)
check("no statement mixes a string literal into COALESCE, NULLIF, DECODE or ||",
    charsetOffenders.join(", "), "")

/* 3. bound, capped and shape-checked at the call site. The whole call is asserted
 * as one string: a cap passed but a shape check skipped, or a shape check done on
 * the wrong table, would each pass a looser assertion. */
ok("the statement runs through the shared reader, bound and capped",
    endpointCode.contains(
        "Db.query(connection, PICKER_SQL, [PICKER_STATUS],"))
ok("with the cap this file declares", endpointCode.contains("PICKER_READ, PICKER_CAP)"))
ok("and the columns are verified against the catalogue before it runs",
    endpointCode.contains("Db.shapeProblem(Db.shape(connection, \"spaces\", PICKER_COLUMNS))"))
ok("the status column is on the shape check, because the statement restricts on it",
    endpointCode.contains(
        "PICKER_COLUMNS = [\"spacekey\", \"spacename\", \"spacestatus\"]"))
ok("the picker takes a connection, not a service",
    endpointCode.contains("static Map<String, Object> spaceRows(Connection connection)"))

/* 4. the cap announces itself. The picker this replaced cut 5038 spaces down to
 * 2000 in silence, which is the objection that started this. */
ok("the cap is set far above the largest instance measured",
    endpointCode.contains("static final int PICKER_CAP = 20000"))
ok("the cap and its ordering travel with the rows",
    endpointCode.contains("spacePayload.put(\"cap\", listed.get(\"cap\"))")
        && endpointCode.contains("spacePayload.put(\"order\", listed.get(\"order\"))"))
ok("the ordering it would announce is the one the statement used",
    endpointCode.contains("PICKER_ORDER = \"space name, then space key\""))
ok("and the browser says so when the cut happened",
    endpointText.contains("This list is NOT complete"))

/* 5. a failed read is never an empty list, which is the invariant this whole file
 * exists for. The picker can fail six ways and find nothing legitimately in one,
 * and the seven must not read alike. The count is exact on purpose: a seventh
 * occurrence would mean the empty answer had been given the failure sentence. */
int spacesAt = endpointCode.indexOf("if (requestedAction == \"spaces\")")
int pagesAt = endpointCode.indexOf("if (requestedAction == \"pages\")")
ok("the spaces stage can be isolated out of the source", spacesAt >= 0 && pagesAt > spacesAt)
String spacesStage = spacesAt < 0 ? "" : endpointCode.substring(spacesAt, pagesAt)

ok("the spaces stage resolves no Confluence component at all",
    !spacesStage.contains("ComponentLocator"))
ok("it takes the read-only executor the report path takes",
    spacesStage.contains("Db.factory()"))
ok("and reads through a read-only connection",
    spacesStage.contains("Db.withConnection(executorFactory)"))

String failedRead = "That is a failed read, not an instance without spaces."
check("every failure the stage can hit says it failed", spacesStage.count(failedRead), 3)
check("and so does every failure the reader can hit", endpointCode.count(failedRead), 6)
ok("the one legitimate empty answer says something else entirely",
    spacesStage.contains("The space inventory answered but named no current space"))
ok("a missing connection is a failed read, not an empty instance",
    endpointCode.contains("No database connection was available, so the space list could not"))
ok("a moved column is a failed read naming the column",
    endpointCode.contains("result.put(\"error\", \"The space list could not be read. \" + problem"))

/* ---- 26. the exported page is a record model, and the old layout still reads */

/* Until this change the exported page repeated the whole breadcrumb as text in a
 * Path cell on EVERY row, and container rows sat in the same table as data rows.
 * Nothing about that table could be sorted, filtered or pivoted. The table view
 * of the report was rebuilt for exactly that reason; the exported page was not.
 *
 * The dangerous half is the carry-over, and it is why this section is long. Every
 * remark an administrator has already typed sits on a page written by the OLD
 * build and is keyed on its Path column. A reader that stopped understanding that
 * column would orphan every one of them in the very run that modernises the page,
 * and the file's own rule is that a remark which cannot be read is a remark that
 * must not be touched. */

/* ---- 26a. the identity rule, on its own ---------------------------------- */

check("the identity is the levels joined, exactly as the Path column had it",
    Cx.pathOf(["Details", "Status"]), "Details > Status")
check("a trailing level that does not apply to the record adds nothing",
    Cx.pathOf(["Details", "Status", ""]), "Details > Status")
check("and a row of nothing but those is no identity at all", Cx.pathOf(["", ""]), "")
/* The writer holds raw labels and the reader recovers them with plainText, which
 * collapses whitespace. Normalising on both sides is what stops a label carrying
 * a double space from being orphaned on every single run. */
check("whitespace is normalised the way the page round trip normalises it",
    Cx.pathOf(["A  B", " C "]), "A B > C")
check("nothing is not the string null", Cx.pathOf([null, "C"]), " > C")

/* The trap that decides how a table is read. The OLD data table ALSO ends in
 * Value, State, In Confluence, Remark, so its tail alone looks exactly like the
 * new layout - and its Path and Item cells would then be taken for levels. That
 * is why the reader tests for a Path column FIRST and only then for levels. */
check("the old data table looks like two level columns by its tail alone",
    Cx.levelColumnCount([Cx.COL_PATH, Cx.COL_ITEM, Cx.COL_VALUE, Cx.COL_STATE,
        Cx.COL_LINK, Cx.COL_REMARK]), 2)
check("a real level table names its levels and is counted the same way",
    Cx.levelColumnCount(["Space details", "Field", Cx.COL_VALUE, Cx.COL_STATE,
        Cx.COL_LINK, Cx.COL_REMARK]), 2)
check("a table with no column in front of Value is not a level table",
    Cx.levelColumnCount([Cx.COL_VALUE, Cx.COL_STATE, Cx.COL_LINK, Cx.COL_REMARK]), -1)
/* This export never writes a level header called "Value" - RESERVED_HEADERS sees
 * to that - but a page an administrator edited by hand can carry one, and the
 * block has to end at the REAL Value column rather than at the first cell that
 * happens to read like it. */
check("a level that reads Value does not displace the Value column",
    Cx.levelColumnCount(["Space property", Cx.COL_VALUE, Cx.COL_VALUE, Cx.COL_STATE,
        Cx.COL_LINK, Cx.COL_REMARK]), 2)
check("and the orphan table, which carries identities and no tree, is not one either",
    Cx.levelColumnCount([Cx.COL_PATH, Cx.COL_REMARK]), -1)

/* ---- 26b. what the new build writes -------------------------------------- */

Report modelReport = new Report()
modelReport.spaceKey = "DEV"
modelReport.spaceName = "Development"
modelReport.instanceBaseUrl = "https://confluence.example.org"
Nd modelDetails = modelReport.section("spaceDetails", "Details")
modelDetails.add(Nd.of("spaceField", "Status").val("Current"))
modelDetails.add(Nd.of("spaceField", "Lead").val("cfaysal"))
Nd modelPerms = modelReport.section("spacePermissions", "Permissions")
Nd modelView = Nd.of("spacePermissionType", "VIEWSPACE")
modelView.add(Nd.of("spacePermissionGrant", "confluence-users").val("yes"))
modelView.add(Nd.of("spacePermissionGrant", "developers").val("yes"))
modelPerms.add(modelView)

String modelStorage = Cx.render(exportPayload(modelReport, false), null).storage

/* One column per level, then Value, State, In Confluence, Remark. The header of
 * each level comes from the KIND of the nodes on it, the same rule the table view
 * applies to the same data, so a section that gains a level gains a header
 * without anyone maintaining a list. */
ok("one column per level BELOW the member, named after the levels they hold",
    modelStorage.contains("<tr><th>Field</th><th>Value</th>" +
        "<th>State</th><th>In Confluence</th><th>Remark</th></tr>"))
ok("a deeper section gets a deeper header rather than a longer cell",
    modelStorage.contains("<tr><th>Permission type</th>" +
        "<th>Grant</th><th>Value</th><th>State</th><th>In Confluence</th><th>Remark</th></tr>"))
/* The member is on the heading, so its own kind is not a column header either.
 * "Space details" as a column whose every cell reads "Details" was the last
 * residue of the tree thrown into a table. */
ok("and the member's own level is not a column at all",
    !modelStorage.contains("<th>Space details</th>")
    && !modelStorage.contains("<th>Space permissions</th>"))
ok("the Path column is gone from the data tables",
    !modelStorage.contains("<th>Path</th>"))
ok("and so is the Item column it stood next to",
    !modelStorage.contains("<th>Item</th>"))
ok("no cell carries a breadcrumb any more",
    !modelStorage.contains("&gt; Status") && !modelStorage.contains("&gt; VIEWSPACE"))

/* The repetition moved from inside one cell to down a column, and that is the
 * whole point: a sort, a filter and a pivot consume the second and not the
 * first. */
ok("a record under the member starts at the level below it",
    modelStorage.contains("<tr><td>Status</td><td>Current</td>")
    && modelStorage.contains("<tr><td>Lead</td><td>cfaysal</td>"))
ok("an ancestor that is NOT the member is still written out on every row",
    modelStorage.contains("<tr><td>VIEWSPACE</td><td>confluence-users</td>")
    && modelStorage.contains("<tr><td>VIEWSPACE</td><td>developers</td>"))
check("a readable container contributes no row of its own",
    modelStorage.count("<tr><td>VIEWSPACE</td>"), 2)

/* ---- 26c. a level that does not apply, and a level that could not be read -- */

/* These two must never look alike. An empty level cell means the level does not
 * apply to that record; a record that could not be read says so in the State
 * column. Confusing the two is the single thing this whole report exists not to
 * do. */
Report depthReport = new Report()
depthReport.spaceKey = "DEV"
depthReport.spaceName = "Development"
Nd depthSection = depthReport.section("spaceDetails", "Details")
depthSection.add(Nd.of("spaceField", "Status").val("Current"))
Nd depthGroup = Nd.of("spaceField", "Theme")
depthGroup.add(Nd.of("spaceFieldDetail", "Name").val("Documentation"))
depthSection.add(depthGroup)
depthSection.add(Nd.of("spaceField", "Categories").failed("the label read did not answer"))

String depthStorage = Cx.render(exportPayload(depthReport, false), null).storage

ok("a record that does not reach the deepest level leaves that cell empty",
    depthStorage.contains("<tr><td>Status</td><td></td><td>Current</td><td></td>"))
ok("a record that could not be read is not an empty one - the State column says which",
    depthStorage.contains("<tr><td>Categories</td><td></td><td></td>" +
        "<td>could not be read</td>"))
ok("and the record that does reach the deepest level fills it",
    depthStorage.contains("<tr><td>Theme</td><td>Name</td><td>Documentation</td>"))

/* The trailing empty cell must not reach the identity, or a shallow row would key
 * on a string the writer never wrote and lose its remark on the next run. */
String depthTyped = "<p>KEEP - the shallow row</p>"
int depthSeed = depthStorage.indexOf(Cx.REMARK_SEED,
    depthStorage.indexOf("<tr><td>Status</td><td></td><td>Current</td>"))
RemarkRead depthRead = Cx.parseRemarks(
    depthStorage.substring(0, depthSeed) + depthTyped +
    depthStorage.substring(depthSeed + Cx.REMARK_SEED.length()))
check("a trailing empty level cell is not part of the identity",
    depthRead.remarks.get("Details > Status"), depthTyped)
check("exactly one remark, so no empty cell invented a second one",
    depthRead.remarks.size(), 1)

/* An unreadable node keeps its row even when it has children. A failure that
 * vanishes because it happened to sit on a branch is the absence that was never
 * measured. */
Report deadReport = new Report()
deadReport.spaceKey = "DEV"
deadReport.spaceName = "Development"
Nd deadSection = deadReport.section("spacePermissions", "Permissions")
Nd deadType = Nd.of("spacePermissionType", "VIEWSPACE").failed("the grant read did not answer")
deadType.add(Nd.of("spacePermissionGrant", "confluence-users").val("yes"))
deadSection.add(deadType)
String deadStorage = Cx.render(exportPayload(deadReport, false), null).storage
ok("an unreadable container keeps a row of its own, children or not",
    deadStorage.contains("<tr><td>VIEWSPACE</td><td></td><td></td>" +
        "<td>could not be read</td>"))
ok("and what hangs off it is still on the page",
    deadStorage.contains("<tr><td>VIEWSPACE</td><td>confluence-users</td>"))

/* ---- 26d. a level header may not collide with a column of the table ------- */

/* The reader finds the level block by locating Value and Remark. A level header
 * that read "Value" would cut the block in the wrong place, so it becomes
 * "Aspect" - which is what a level whose name says nothing usable is called
 * anyway. spacePropertyValue under spaceProperty produces exactly that clash. */
Report clashReport = new Report()
clashReport.spaceKey = "DEV"
clashReport.spaceName = "Development"
Nd clashSection = clashReport.section("spaceProperty", "Property")
clashSection.add(Nd.of("spacePropertyValue", "promotedTemplates").val("x"))
String clashStorage = Cx.render(exportPayload(clashReport, false), null).storage
ok("a level header that would collide with a column of this table becomes Aspect",
    clashStorage.contains("<tr><th>Aspect</th><th>Value</th>"))
check("and the page is still readable by its own parser",
    Cx.parseRemarks(clashStorage).outcome, RemarkRead.PARSED)

/* ---- 26e. a repeated identity, and where its ordinal has to travel -------- */

Report twinReport = new Report()
twinReport.spaceKey = "DEV"
twinReport.spaceName = "Development"
Nd twinSection = twinReport.section("spacePermissions", "Permissions")
Nd twinType = Nd.of("spacePermissionType", "VIEWSPACE")
twinType.add(Nd.of("spacePermissionGrant", "confluence-users").val("yes"))
twinType.add(Nd.of("spacePermissionGrant", "confluence-users").val("yes"))
twinSection.add(twinType)
String twinStorage = Cx.render(exportPayload(twinReport, false), null).storage

ok("a repeated identity carries its ordinal on the LAST level cell",
    twinStorage.contains("<td>confluence-users #2</td>"))
String twinTyped = "<p>KEEP - the second grant</p>"
int twinSeed = twinStorage.indexOf(Cx.REMARK_SEED,
    twinStorage.indexOf("<td>confluence-users #2</td>"))
RemarkRead twinRead = Cx.parseRemarks(
    twinStorage.substring(0, twinSeed) + twinTyped +
    twinStorage.substring(twinSeed + Cx.REMARK_SEED.length()))
check("so the reader joins the cells back into the key the writer keyed on",
    twinRead.remarks.get("Permissions > VIEWSPACE > confluence-users #2"), twinTyped)
check("and the second run puts it back on that row",
    Cx.render(exportPayload(twinReport, false), twinRead).remarksCarried, 1)

/* ---- 26f. THE ONE THAT MATTERS: a page written by the OLD build ----------- */

/* This is the fixture, in the layout the shipped build writes today: a Path cell
 * holding the whole breadcrumb, an Item column, container rows and data rows in
 * one table, and four administrator remarks spread over both tables. It is what
 * is on the instances right now. */
String oldSeed = Cx.REMARK_SEED
String oldLayoutPage =
    "<p>Complete configuration of space <strong>Development</strong> (DEV).</p>" +
    "<table><tbody>" +
    "<tr><th>Path</th><th>Item</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Details</td><td>Details</td><td></td><td></td><td></td><td>" + oldSeed + "</td></tr>" +
    "<tr><td>Details &gt; Status</td><td>Status</td><td>Current</td><td></td><td></td>" +
    "<td><p>KEEP the status</p></td></tr>" +
    "<tr><td>Details &gt; Lead</td><td>Lead</td><td>cfaysal</td><td></td><td></td>" +
    "<td><p>KEEP the lead</p></td></tr>" +
    "</tbody></table>" +
    "<table><tbody>" +
    "<tr><th>Path</th><th>Item</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Permissions</td><td>Permissions</td><td></td><td></td><td></td><td>" + oldSeed + "</td></tr>" +
    "<tr><td>Permissions &gt; VIEWSPACE</td><td>VIEWSPACE</td><td>2 grants</td><td></td><td></td>" +
    "<td><p>KEEP the permission type</p></td></tr>" +
    "<tr><td>Permissions &gt; VIEWSPACE &gt; confluence-users</td><td>confluence-users</td>" +
    "<td>yes</td><td></td><td></td><td><p>KEEP the grant</p></td></tr>" +
    "<tr><td>Permissions &gt; VIEWSPACE &gt; developers</td><td>developers</td>" +
    "<td>yes</td><td></td><td></td><td>" + oldSeed + "</td></tr>" +
    "</tbody></table>" +
    "<p><em>" + Cx.MARKER + "</em></p>"

RemarkRead fromOld = Cx.parseRemarks(oldLayoutPage)
check("a page in the old Path layout is still read by this build",
    fromOld.outcome, RemarkRead.PARSED)
ok("and it is allowed to be written over", fromOld.isWriteAllowed())
check("every remark on it is found - none, not one, is missed", fromOld.remarks.size(), 4)
check("keyed exactly as the old build keyed them, on a leaf",
    fromOld.remarks.get("Details > Status"), "<p>KEEP the status</p>")
check("on a second leaf", fromOld.remarks.get("Details > Lead"), "<p>KEEP the lead</p>")
check("on a container", fromOld.remarks.get("Permissions > VIEWSPACE"),
    "<p>KEEP the permission type</p>")
check("and three levels down", fromOld.remarks.get("Permissions > VIEWSPACE > confluence-users"),
    "<p>KEEP the grant</p>")
check("the export's own seed is not mistaken for an administrator's note",
    fromOld.remarks.containsKey("Permissions > VIEWSPACE > developers"), false)

/* The same report the new build renders, handed the remarks read off the old
 * page. Every one of the four has to come out the other side. */
ExportOutcome migrated = Cx.render(exportPayload(modelReport, false), fromOld)

check("what the new build read off the old page, it counted", migrated.remarksRead, 4)
check("the three that still have a record land on their record", migrated.remarksCarried, 3)
ok("the leaf remark is on the leaf row, verbatim",
    migrated.storage.contains("<tr><td>Status</td><td>Current</td>" +
        "<td></td><td></td><td><p>KEEP the status</p></td></tr>"))
ok("the second leaf remark too",
    migrated.storage.contains("<tr><td>Lead</td><td>cfaysal</td>" +
        "<td></td><td></td><td><p>KEEP the lead</p></td></tr>"))
ok("and the one three levels down landed on the right grant, not on its sibling",
    migrated.storage.contains("<tr><td>VIEWSPACE</td><td>confluence-users</td>" +
        "<td>yes</td><td></td><td></td><td><p>KEEP the grant</p></td></tr>")
    && migrated.storage.contains("<tr><td>VIEWSPACE</td><td>developers</td>" +
        "<td>yes</td><td></td><td></td><td>" + Cx.REMARK_SEED + "</td></tr>"))

/* The fourth was written on a container, and a container is a column now. It is
 * not dropped and it is not silently reattached to something else: it is parked
 * in the orphan table with the administrator's words untouched, and the page says
 * why it is there. */
check("the remark on a container is kept as an orphan, not discarded",
    migrated.orphanKeys, ["Permissions > VIEWSPACE"])
ok("with the administrator's words intact",
    migrated.storage.contains("<p>KEEP the permission type</p>"))
ok("and the page says why a container remark can end up there",
    migrated.storage.contains("it was a container"))
check("NOT ONE of the four remarks is lost",
    migrated.remarksCarried + migrated.orphanKeys.size(), 4)

/* And the page that comes out of the migration reads back to exactly the same set
 * of identities the old page did. That is the property the whole change rests on:
 * the identity is the same string whichever layout it was read from. */
RemarkRead fromNew = Cx.parseRemarks(migrated.storage)
check("the level layout reads back", fromNew.outcome, RemarkRead.PARSED)
check("and yields the SAME identities the old layout yielded",
    new TreeSet<String>(fromNew.remarks.keySet()), new TreeSet<String>(fromOld.remarks.keySet()))
check("with the same text under each of them",
    new TreeSet<String>(fromNew.remarks.values()), new TreeSet<String>(fromOld.remarks.values()))
check("and the run after that still carries three onto records",
    Cx.render(exportPayload(modelReport, false), fromNew).remarksCarried, 3)

/* The orphan table keeps a Path column on purpose: it carries identities and has
 * no tree to spread over columns, and that column is what the reader keys it on.
 * It is also the reason COL_PATH is still written anywhere at all. */
ok("the orphan table still carries the Path column it is read by",
    migrated.storage.contains("<tr><th>Path</th><th>Remark</th></tr>"))

/* Fail-closed is unchanged, and the level layout must not have opened a way past
 * it: a page whose tables the reader cannot make sense of is not written over. */
String levelMarked = "<p><em>" + Cx.MARKER + "</em></p>"
ok("a level table that lost its Remark column is refused", !Cx.parseRemarks(levelMarked +
    "<table><tbody><tr><th>Space details</th><th>Field</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th></tr><tr><td>Details</td><td>Status</td><td>Current</td>" +
    "<td></td><td></td></tr></tbody></table>").isWriteAllowed())
ok("a level row with too few cells is refused", !Cx.parseRemarks(levelMarked +
    "<table><tbody><tr><th>Space details</th><th>Field</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr><tr><td>Details</td></tr>" +
    "</tbody></table>").isWriteAllowed())
ok("the same identity twice with text in both is refused", !Cx.parseRemarks(levelMarked +
    "<table><tbody><tr><th>Space details</th><th>Field</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Details</td><td>Status</td><td>a</td><td></td><td></td><td><p>one</p></td></tr>" +
    "<tr><td>Details</td><td>Status</td><td>b</td><td></td><td></td><td><p>two</p></td></tr>" +
    "</tbody></table>").isWriteAllowed())
ok("and a marked page whose only table is neither layout is refused", !Cx.parseRemarks(levelMarked +
    "<table><tbody><tr><th>One</th><th>Two</th></tr><tr><td>a</td><td>b</td></tr>" +
    "</tbody></table>").isWriteAllowed())

/* ---- 26g. THREE LAYOUTS, THREE FIXTURES, not one remark misplaced -------- */

/* Putting the member on its own heading made a THIRD layout, and the reader is
 * the one path in this tool that can destroy text a human typed. It is therefore
 * exercised against all three at once, with remarks on every one of them, and the
 * assertion is not "the read succeeded" but "every remark came out on the item it
 * was written on".
 *
 *   1. the Path breadcrumb column, written by every build up to this morning;
 *   2. the member as the leading level COLUMN, which is what is on both instances
 *      right now - content id 75923464, version 2, written 09:02 today;
 *   3. this build: the member on the heading and in no column at all.
 *
 * All three key on the SAME identity string. That is the only reason a remark
 * survives a change of layout, and it is what these three fixtures measure. */

Report threeReport = new Report()
threeReport.spaceKey = "DEV"
threeReport.spaceName = "Development"
threeReport.instanceBaseUrl = "https://confluence.example.org"
Nd threeDetails = threeReport.section("spaceDetails", "Details")
threeDetails.link("https://confluence.example.org/spaces/editspace.action?key=DEV", null)
threeDetails.add(Nd.of("spaceField", "Status").val("Current"))
threeDetails.add(Nd.of("spaceField", "Lead").val("cfaysal"))
Nd threePerms = threeReport.section("spacePermissions", "Permissions")
Nd threeView = Nd.of("spacePermissionType", "VIEWSPACE")
threeView.add(Nd.of("spacePermissionGrant", "confluence-users").val("yes"))
threeView.add(Nd.of("spacePermissionGrant", "developers").val("yes"))
threePerms.add(threeView)

Map<String, String> typedRemarks = new LinkedHashMap<String, String>()
typedRemarks.put("Details > Status", "<p>KEEP - agreed with the space owner</p>")
typedRemarks.put("Details > Lead", "<p>KEEP - the lead is on leave</p>")
typedRemarks.put("Permissions > VIEWSPACE > confluence-users", "<p>KEEP - the first grant</p>")
typedRemarks.put("Permissions > VIEWSPACE > developers", "<p>KEEP - the second grant</p>")

/* An administrator typing into the cell of one row: the first seed after the row
 * starts is that row's, because the Remark cell is the last one on it. */
/* A fixture that stops matching must FAIL, not hand back the page unchanged. An
 * unmodified page is indistinguishable from a successfully typed one, and every
 * assertion downstream would then be testing a remark nobody wrote - the same
 * "a failed operation must not look like a successful one" rule this suite exists
 * to enforce, applied to the suite itself. */
def typeInto = { String storage, String rowStart, String remark ->
    int at = storage.indexOf(rowStart)
    if (at < 0) {
        throw new IllegalStateException("fixture row not found: " + rowStart)
    }
    int seed = storage.indexOf(Cx.REMARK_SEED, at)
    if (seed < 0) {
        throw new IllegalStateException("no remark seed after fixture row: " + rowStart)
    }
    return storage.substring(0, seed) + remark + storage.substring(seed + Cx.REMARK_SEED.length())
}

/* 1. Path, Item, container rows and data rows in one table. */
String layoutOne =
    "<table><tbody>" +
    "<tr><th>Path</th><th>Item</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Details</td><td>Details</td><td></td><td></td><td></td><td>" +
    Cx.REMARK_SEED + "</td></tr>" +
    "<tr><td>Details &gt; Status</td><td>Status</td><td>Current</td><td></td><td></td><td>" +
    typedRemarks.get("Details > Status") + "</td></tr>" +
    "<tr><td>Details &gt; Lead</td><td>Lead</td><td>cfaysal</td><td></td><td></td><td>" +
    typedRemarks.get("Details > Lead") + "</td></tr>" +
    "</tbody></table>" +
    "<table><tbody>" +
    "<tr><th>Path</th><th>Item</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Permissions &gt; VIEWSPACE &gt; confluence-users</td><td>confluence-users</td>" +
    "<td>yes</td><td></td><td></td><td>" +
    typedRemarks.get("Permissions > VIEWSPACE > confluence-users") + "</td></tr>" +
    "<tr><td>Permissions &gt; VIEWSPACE &gt; developers</td><td>developers</td>" +
    "<td>yes</td><td></td><td></td><td>" +
    typedRemarks.get("Permissions > VIEWSPACE > developers") + "</td></tr>" +
    "</tbody></table>" +
    "<p><em>" + Cx.MARKER + "</em></p>"

/* 2. The member as the leading COLUMN, headers exactly as the page on the
 * instance carries them. The headings are on this fixture too, and they must
 * change nothing: without the token the leading level is in the cells. */
String layoutTwo =
    Cx.expandOpen("Details (2 items)") +
    "<table><tbody>" +
    "<tr><th>Space details</th><th>Field</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Details</td><td>Status</td><td>Current</td><td></td><td></td><td>" +
    typedRemarks.get("Details > Status") + "</td></tr>" +
    "<tr><td>Details</td><td>Lead</td><td>cfaysal</td><td></td><td></td><td>" +
    typedRemarks.get("Details > Lead") + "</td></tr>" +
    "</tbody></table>" + Cx.expandClose() +
    Cx.expandOpen("Permissions (2 items)") +
    "<table><tbody>" +
    "<tr><th>Space permissions</th><th>Permission type</th><th>Grant</th><th>Value</th>" +
    "<th>State</th><th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Permissions</td><td>VIEWSPACE</td><td>confluence-users</td><td>yes</td>" +
    "<td></td><td></td><td>" +
    typedRemarks.get("Permissions > VIEWSPACE > confluence-users") + "</td></tr>" +
    "<tr><td>Permissions</td><td>VIEWSPACE</td><td>developers</td><td>yes</td>" +
    "<td></td><td></td><td>" +
    typedRemarks.get("Permissions > VIEWSPACE > developers") + "</td></tr>" +
    "</tbody></table>" + Cx.expandClose() +
    "<p><em>" + Cx.MARKER + "</em></p>"

/* 3. What this build writes, with the same four remarks typed into it. */
String layoutThree = Cx.render(exportPayload(threeReport, false), null).storage
ok("this build states which layout it wrote", layoutThree.contains(Cx.LAYOUT_MARKER))
ok("and the page still carries the marker a page is adopted by, so no page is stranded",
    layoutThree.contains(Cx.MARKER))
layoutThree = typeInto(layoutThree, "<tr><td>Status</td><td>Current</td>",
    typedRemarks.get("Details > Status"))
layoutThree = typeInto(layoutThree, "<tr><td>Lead</td><td>cfaysal</td>",
    typedRemarks.get("Details > Lead"))
layoutThree = typeInto(layoutThree, "<tr><td>VIEWSPACE</td><td>confluence-users</td>",
    typedRemarks.get("Permissions > VIEWSPACE > confluence-users"))
layoutThree = typeInto(layoutThree, "<tr><td>VIEWSPACE</td><td>developers</td>",
    typedRemarks.get("Permissions > VIEWSPACE > developers"))

Map<String, String> everyLayout = new LinkedHashMap<String, String>()
everyLayout.put("the Path breadcrumb layout", layoutOne)
everyLayout.put("the member-as-column layout", layoutTwo)
everyLayout.put("the member-on-the-heading layout", layoutThree)

for (Map.Entry<String, String> fixture : everyLayout.entrySet()) {
    String where = fixture.getKey()
    RemarkRead read = Cx.parseRemarks(fixture.getValue())
    check(where + " is read", read.outcome, RemarkRead.PARSED)
    ok(where + " may be written over", read.isWriteAllowed())
    check(where + " gives up every remark on it", read.remarks.size(), 4)
    check(where + " keys them on the identity all three share, text and all",
        read.remarks, typedRemarks)

    /* And the run that rewrites the page puts each of them back on the row it
     * belonged to - not on its sibling, which is the failure that would look like
     * a success on every count that only adds remarks up. */
    ExportOutcome again = Cx.render(exportPayload(threeReport, false), read)
    check(where + ": every remark is carried onto a record", again.remarksCarried, 4)
    check(where + ": and not one of them is orphaned", again.orphanKeys.size(), 0)
    ok(where + ": on the status row",
        again.storage.contains("<tr><td>Status</td><td>Current</td><td></td><td></td><td>" +
            typedRemarks.get("Details > Status") + "</td></tr>"))
    ok(where + ": on the lead row",
        again.storage.contains("<tr><td>Lead</td><td>cfaysal</td><td></td><td></td><td>" +
            typedRemarks.get("Details > Lead") + "</td></tr>"))
    ok(where + ": on the first grant and not on its sibling",
        again.storage.contains("<tr><td>VIEWSPACE</td><td>confluence-users</td><td>yes</td>" +
            "<td></td><td></td><td>" +
            typedRemarks.get("Permissions > VIEWSPACE > confluence-users") + "</td></tr>"))
    ok(where + ": and on the sibling, its own",
        again.storage.contains("<tr><td>VIEWSPACE</td><td>developers</td><td>yes</td>" +
            "<td></td><td></td><td>" +
            typedRemarks.get("Permissions > VIEWSPACE > developers") + "</td></tr>"))
}

/* The two level layouts are the same SHAPE, so the token in the footer is the only
 * thing that SAYS which one a page is in.
 *
 * THIS ASSERTION CHANGED, and the old one is quoted here because it is the defect:
 * it read "without the token the same page is read as the older layout", with a
 * second one confirming that every identity came out short by the segment the
 * heading carries. Both passed. They were true, and they documented a read that
 * lost an administrator's remark off every row while reporting success - a token
 * is a guess dressed as a fact, and this file refuses to guess everywhere else.
 *
 * The reader now cross-checks the token against the tables, which carry the answer
 * structurally: the older layout repeats the member's own label down the first
 * level column of every table and this one never does. Here the two disagree, so
 * the layout is not known, and an unknown layout is refused rather than resolved
 * in favour of whichever side happens to be cheaper to believe. */
RemarkRead misread = Cx.parseRemarks(layoutThree.replace(Cx.LAYOUT_MARKER, Cx.MARKER))
check("a heading-layout page that lost its token is refused, not read as the older layout",
    misread.outcome, RemarkRead.FAILED)
ok("and the refusal says the layout could not be determined",
    String.valueOf(misread.reason).contains("layout of the existing page could not be determined"))
ok("naming the token it looked for and what the tables said instead",
    String.valueOf(misread.reason).contains(Cx.LAYOUT_MARKER) && String.valueOf(misread.reason).contains("repeat the heading"))
ok("no identity is built off a page whose layout is unknown", misread.remarks.isEmpty())
ok("and nothing is written over it", !misread.isWriteAllowed())

/* The mirror, which matters just as much: a page in the OLDER layout that somehow
 * carries the token. Believed, it would prepend the member to every identity and
 * detach every remark the same way round. */
RemarkRead gainedToken = Cx.parseRemarks(layoutTwo.replace(
    "<p><em>" + Cx.MARKER + "</em></p>", "<p><em>" + Cx.LAYOUT_MARKER + "</em></p>"))
check("and a page in the older layout carrying the token is refused too",
    gainedToken.outcome, RemarkRead.FAILED)
ok("with the same reason, from the other side",
    String.valueOf(gainedToken.reason).contains("layout of the existing page could not be determined"))

/* ---- 26h. the heading is where the member lives now ---------------------- */

String headed = Cx.render(exportPayload(threeReport, false), null).storage

ok("every member has a heading of its own, carrying its label and its item count",
    headed.contains("<ac:parameter ac:name=\"title\">Details (2 items)</ac:parameter>")
    && headed.contains("<ac:parameter ac:name=\"title\">Permissions (2 items)</ac:parameter>"))
/* The table view puts the link on the section header, next to the count. Here it
 * is a property of the member and not of any one row, so it goes to the same
 * place rather than into a cell. */
ok("and the link of a member that has one is on that heading, not on a row",
    headed.contains("<p><a href=\"https://confluence.example.org/spaces/editspace.action?key=DEV\">" +
        "open in Confluence</a></p>"))

/* Writer and reader of a heading are one pair, so a label is asserted through
 * both of them. A label carrying a parenthesis of its own is the case a
 * first-match rule gets wrong, and it is a real one: "Look and feel (global)". */
check("the label comes back out of its own heading",
    Cx.memberLabelOf(Cx.memberHeading("Details", "11 items")), "Details")
check("including one that carries a parenthesis of its own",
    Cx.memberLabelOf(Cx.memberHeading("Look and feel (global)", "4 items")),
    "Look and feel (global)")
check("and one whose heading says what was cut away",
    Cx.memberLabelOf(Cx.memberHeading("Permissions", "5000 of 8000 items, the rest is cut")),
    "Permissions")
check("a string that is not a heading yields no label", Cx.memberLabelOf("Details"), null)
check("and nothing is not a label either", Cx.memberLabelOf(null), null)

/* A member with NO label. The heading is the leading segment of every identity in
 * the table under it, and `flatten` builds that same segment with an empty-string
 * fallback. If the writer ever fell back to a word of its own, the two sides would
 * key the same row differently and every remark under that member would detach on
 * the next run. So an unlabelled member must produce a heading the reader REFUSES,
 * which makes the read fail closed instead of mis-keying. */
check("an unlabelled member yields a heading no label can be read from",
    Cx.memberLabelOf(Cx.memberHeading("", "2 items")), null)
ok("and the writer uses the same empty fallback the identity is built with",
    endpointCode.contains("String heading = str(member, \"label\", \"\")"))

/* The heading goes through esc() on the way onto the page and plainText() on the
 * way back. A label carrying a character XHTML has to escape must survive both,
 * or every remark in that member's table is orphaned on the next run. */
Report ampReport = new Report()
ampReport.spaceKey = "DEV"
ampReport.spaceName = "Development"
Nd ampSection = ampReport.section("spaceDetails", "Look & feel (2026)")
ampSection.add(Nd.of("spaceField", "Status").val("Current"))
String ampStorage = typeInto(Cx.render(exportPayload(ampReport, false), null).storage,
    "<tr><td>Status</td><td>Current</td>", "<p>KEEP - the escaped one</p>")
RemarkRead ampRead = Cx.parseRemarks(ampStorage)
check("a member label that has to be escaped survives the round trip",
    ampRead.remarks.get("Look & feel (2026) > Status"), "<p>KEEP - the escaped one</p>")
check("and the run after that puts it back on the same row",
    Cx.render(exportPayload(ampReport, false), ampRead).remarksCarried, 1)

/* The remark seed is a status macro and carries an ac:parameter called "title" of
 * its own, inside a table cell. A locator that searched for the parameter alone
 * would take the last seed of one table for the heading of the next - and on the
 * real exported page all but seven of the forty-five title parameters are seeds. */
check("a seed inside a table is not mistaken for the heading of the next one",
    Cx.memberLabelOf(Cx.headingAbove(Cx.expandHeadings(headed), 0,
        headed.lastIndexOf("<table><tbody>"))), "Permissions")

/* ---- 26i. no column repeats the heading it sits under -------------------- */

/* The defect the Director found by putting the two views side by side: the first
 * column of the Details table read "Details" on all eleven rows. Stated exactly -
 * not "a constant column", because a section holding one permission type
 * legitimately has one, but "a level column that repeats the member's own name". */
def columnsRepeatingTheHeading = { String storage ->
    List<String> found = new ArrayList<String>()
    Map<Integer, String> headings = Cx.expandHeadings(storage)
    java.util.regex.Matcher bodies = Cx.TBODY.matcher(storage)
    int previousEnd = 0
    while (bodies.find()) {
        int floor = previousEnd
        previousEnd = bodies.end()
        String member = Cx.memberLabelOf(Cx.headingAbove(headings, floor, bodies.start()))
        if (member == null) {
            continue
        }
        List<String> rows = new ArrayList<String>()
        java.util.regex.Matcher rowMatcher = Cx.ROW.matcher(bodies.group(1))
        while (rowMatcher.find()) {
            rows.add(rowMatcher.group(1))
        }
        if (rows.size() < 2) {
            continue
        }
        List<String> header = Cx.cellsOf(rows.get(0))
        int levels = Cx.levelColumnCount(header)
        for (int c = 0; c < levels; c++) {
            boolean everyRow = true
            for (int r = 1; r < rows.size(); r++) {
                List<String> cells = Cx.cellsOf(rows.get(r))
                String here = c < cells.size() ? Cx.plainText(cells.get(c)) : ""
                if (here != member) {
                    everyRow = false
                }
            }
            if (everyRow) {
                found.add(Cx.plainText(header.get(c)) + " = " + member)
            }
        }
    }
    return found
}

check("no column of the exported page repeats the heading it sits under",
    columnsRepeatingTheHeading(headed), [])
check("and the check bites: the layout on the instances right now has exactly two",
    columnsRepeatingTheHeading(layoutTwo),
    ["Space details = Details", "Space permissions = Permissions"])

/* ---- 26j. a member that could not be read keeps its own row -------------- */

/* The exception that is not negotiable, at the one place this restructure could
 * have swallowed it: the member itself. Its name is on the heading now, so its own
 * row has no level cell left to sit in - and it still has to BE a row, with its
 * state on it, or a failed read disappears because of where it happened to sit. */
Report mutedReport = new Report()
mutedReport.spaceKey = "DEV"
mutedReport.spaceName = "Development"
Nd mutedSection = mutedReport.section("spaceCategories", "Space categories")
mutedSection.failed("the label read did not answer")
String mutedStorage = Cx.render(exportPayload(mutedReport, false), null).storage

ok("the member that could not be read is a row of its own, no level cell filled",
    mutedStorage.contains("<tr><td></td><td></td><td>could not be read</td>"))
/* Empty or not, the level column stays. It is what the reader counts off to find
 * where the identity ends, and a table without one is refused by this export's own
 * parser - which would brick the export on a section holding a single value. */
ok("and the table keeps one level column, which is what its own parser counts off",
    mutedStorage.contains("<tr><th>Aspect</th><th>Value</th><th>State</th>" +
        "<th>In Confluence</th><th>Remark</th></tr>"))

String mutedTyped = "<p>KEEP - chased with the instance admin</p>"
mutedStorage = typeInto(mutedStorage, "<tr><td></td><td></td><td>could not be read</td>", mutedTyped)
RemarkRead mutedRead = Cx.parseRemarks(mutedStorage)
check("a remark on it is keyed on the member alone, which only the heading carries",
    mutedRead.remarks.get("Space categories"), mutedTyped)
check("so the next run puts it back on that row",
    Cx.render(exportPayload(mutedReport, false), mutedRead).remarksCarried, 1)

/* Fail-closed at the new seam. A page in this layout whose table sits under no
 * heading has identities that cannot be rebuilt, and a key that comes out short
 * orphans every remark under it. It is refused rather than read short. */
String headless = "<p><em>" + Cx.LAYOUT_MARKER + "</em></p>" +
    "<table><tbody><tr><th>Field</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr>" +
    "<tr><td>Status</td><td>Current</td><td></td><td></td><td><p>one</p></td></tr>" +
    "</tbody></table>"
ok("a table in this layout with no heading above it is refused, not read short",
    !Cx.parseRemarks(headless).isWriteAllowed())

/* A report with no section at all still writes one table, and that table has a
 * header and no row. It sits under no heading, and it has to stay readable, or an
 * empty report would brick its own export on the next run. */
Report barren = new Report()
barren.spaceKey = "DEV"
barren.spaceName = "Development"
check("a page with no section at all is still readable by its own parser",
    Cx.parseRemarks(Cx.render(exportPayload(barren, false), null).storage).outcome,
    RemarkRead.PARSED)

/* ---- 26k. what says nothing about the layout is not a disagreement ------- */

/* The cross-check above is only safe if it stays quiet where it has no evidence.
 * Two shapes this export writes itself would otherwise be read as a contradiction
 * and brick the export on a page that is perfectly well formed. */

/* A table with a header and no row. Nothing repeats and nothing fails to repeat. */
String emptyLevelTable = "<p><em>" + Cx.LAYOUT_MARKER + "</em></p>" +
    Cx.expandOpen("Details (0 items)") +
    "<table><tbody><tr><th>Field</th><th>Value</th><th>State</th>" +
    "<th>In Confluence</th><th>Remark</th></tr></tbody></table>" + Cx.expandClose()
check("a table with a header and no row is not read as a layout disagreement",
    Cx.parseRemarks(emptyLevelTable).outcome, RemarkRead.PARSED)

/* And the member-only row: one level cell, empty, because the member's name is on
 * the heading. It does NOT repeat the heading, which is the right answer - the
 * older layout wrote the member's name into that very cell. mutedStorage above is
 * exactly this page, and it still reads. */
check("nor is a member-only row whose single level cell is empty",
    mutedRead.outcome, RemarkRead.PARSED)
check("and the remark on it is still keyed on the member alone",
    mutedRead.remarks.size(), 1)

/* ---- 26l. an administrator's own expand macro is not a heading ----------- */

/* The heading above a table is the leading segment of every identity under it, and
 * EXPAND_TITLE matches an expand-with-title ANYWHERE in the storage, table cells
 * included. The remark seed is defended by matching on the macro NAME; an expand
 * macro an administrator pastes into a Remark cell is an expand with a title like
 * any other, and its offset sits inside a table. */
String adminExpand = "<ac:structured-macro ac:name=\"expand\" ac:schema-version=\"1\" " +
    "ac:macro-id=\"aaaabbbb-cccc-dddd-eeee-ffff00001111\">" +
    "<ac:parameter ac:name=\"title\">Why this is set (agreed with the owner)</ac:parameter>" +
    "<ac:rich-text-body><p>KEEP - the space owner asked for it</p></ac:rich-text-body>" +
    "</ac:structured-macro>"

String pastedPage = typeInto(Cx.render(exportPayload(threeReport, false), null).storage,
    "<tr><td>Status</td><td>Current</td>", adminExpand)
pastedPage = typeInto(pastedPage, "<tr><td>VIEWSPACE</td><td>confluence-users</td>",
    typedRemarks.get("Permissions > VIEWSPACE > confluence-users"))
pastedPage = typeInto(pastedPage, "<tr><td>VIEWSPACE</td><td>developers</td>",
    typedRemarks.get("Permissions > VIEWSPACE > developers"))

RemarkRead pastedRead = Cx.parseRemarks(pastedPage)
check("a page carrying an administrator's own expand macro in a Remark cell is read",
    pastedRead.outcome, RemarkRead.PARSED)
check("their macro is a remark like any other, on the item they typed it on",
    pastedRead.remarks.get("Details > Status"), adminExpand)
check("and every remark still lands on its own item, none keyed off their macro title",
    new TreeSet<String>(pastedRead.remarks.keySet()),
    new TreeSet<String>(["Details > Status",
        "Permissions > VIEWSPACE > confluence-users",
        "Permissions > VIEWSPACE > developers"]))
check("so the next run carries all three back onto their own rows",
    Cx.render(exportPayload(threeReport, false), pastedRead).remarksCarried, 3)

/* THE ONE THAT BITES. The same page with the second table's own expand deleted,
 * which is one click in the editor. Its genuine heading is gone, so the last
 * expand-with-title starting before that table is the one the administrator typed
 * into a cell of the table ABOVE it. Before this change the reader took it: both
 * grants came back keyed on "Why this is set", the read reported success, and both
 * remarks would have been orphaned on the next run with nothing said.
 *
 * A heading is now looked for only between the END of the previous table body and
 * the start of this one. Every genuine member heading lies there, so the bound is
 * exact - and the wrong lead becomes a null, which this reader already fails
 * closed on. */
String beheaded = pastedPage.replace(Cx.expandOpen("Permissions (2 items)"), "")
ok("the fixture really did lose that heading", beheaded.length() < pastedPage.length())
RemarkRead beheadedRead = Cx.parseRemarks(beheaded)
check("a table whose own heading is gone is refused, not keyed off the table above it",
    beheadedRead.outcome, RemarkRead.FAILED)
ok("and the reason names the real cause, the missing section heading",
    String.valueOf(beheadedRead.reason).contains("sits under no"))
boolean fromTheMacro = false
for (String key : beheadedRead.remarks.keySet()) {
    if (key.startsWith("Why this is set")) {
        fromTheMacro = true
    }
}
ok("not one identity is built out of the administrator's own macro title", !fromTheMacro)
ok("and nothing is written over that page", !beheadedRead.isWriteAllowed())

/* ---- 26m. the drift gate fails closed when it cannot find an endpoint ---- */

/* tools/shared-renderer-drift.py is the only runtime-independent gate holding the
 * two duplicated renderers in step, and it used to answer a missing endpoint with
 * a GitHub Actions notice and a zero. A notice does not fail a run, so renaming or
 * moving an endpoint disarmed the gate outright while CI went on reporting green.
 *
 * This suite cannot execute Python, so the branch is held to the tool's own source
 * the way section 24 holds the endpoint to text it cannot load. The run proving it
 * exits non-zero belongs in the work item, not here. */
File driftSource = new File("tools/shared-renderer-drift.py")
if (!driftSource.isFile()) {
    driftSource = new File("../../tools/shared-renderer-drift.py")
}
ok("the drift gate's own source can be read", driftSource.isFile())
String driftText = driftSource.isFile() ? driftSource.getText("UTF-8") : ""
int missingAt = driftText.indexOf("if missing:")
int compareAt = driftText.indexOf("base_name, base_rel = ENDPOINTS[0]")
ok("its missing-endpoint branch is where this assertion expects it",
    missingAt > 0 && compareAt > missingAt)
String missingBranch = missingAt < 0 || compareAt < missingAt
    ? "" : driftText.substring(missingAt, compareAt)
ok("a missing endpoint is not answered with a notice", !missingBranch.contains("::notice"))
ok("it is an error", missingBranch.contains("::error"))
ok("and the run ends non-zero rather than green",
    missingBranch.contains("return 1") && !missingBranch.contains("return 0"))
ok("the message names the path it expected to find",
    missingBranch.contains("os.path.join(root, relative)"))
ok("and the self-retiring declaration ledger is untouched",
    driftText.contains("no longer differs between the endpoints")
        && driftText.contains("Move it into"))

/* ---- 26n. an unreconciled page is a 409, not a 500 ----------------------- */

/* Measured on the instance: an export refused with "Nothing was written (500): The
 * page could not be written: ExternalChangesException: Unable to save changes to
 * unreconciled page ContentId{id=74973191}". Synchrony holds a revision the
 * database does not have, so Confluence refuses the save - the same principle as
 * this export's own remark rule, one layer down. It is temporary, recoverable and
 * not a fault, so it earns the 409 every other fail-closed path here gets, and a
 * message an administrator can act on instead of a class name.
 *
 * The type is Confluence-internal and is deliberately NOT imported: the endpoint
 * matches it by class NAME down the cause chain. The fixture below is therefore a
 * class of this suite's own carrying that name, which is exactly what the code
 * under test looks at. */
Map<String, Object> stale = Cx.writeRefusal(new ExternalChangesException(
    "Unable to save changes to unreconciled page ContentId{id=74973191}"))
check("an unreconciled page is refused with 409, not reported as a fault",
    stale.get("status"), Integer.valueOf(409))
ok("in plain words that say what the state is",
    stale.get("message").toString().contains("unreconciled collaborative-editing changes"))
ok("what clears it", stale.get("message").toString().contains("Open the page once in the editor"))
ok("and that nothing was written", stale.get("message").toString().contains("Nothing was written"))
ok("with no class name left in it", !stale.get("message").toString().contains("Exception"))

check("the cause chain is walked, not only the exception that arrived",
    Cx.writeRefusal(new IllegalStateException("save failed",
        new RuntimeException("wrapped",
            new ExternalChangesException("unreconciled")))).get("status"),
    Integer.valueOf(409))
ok("and a cause that points at itself is not a hang",
    !Cx.unreconciled(new SelfCausedThrowable("round and round")))

/* Every other write failure maps exactly as it did before: a 500 carrying the
 * class and the message, because an unknown failure IS a fault and hiding its type
 * helps nobody. */
Map<String, Object> otherFailure = Cx.writeRefusal(
    new IllegalStateException("the database went away"))
check("an unrelated write failure is still a 500", otherFailure.get("status"),
    Integer.valueOf(500))
check("with the wording it has today", otherFailure.get("message"),
    "The page could not be written: IllegalStateException: the database went away")

/* And the write path goes through this mapping rather than keeping a 500 of its
 * own, which is the half of the change no offline suite can reach. */
ok("the write path maps its failure through it",
    endpointCode.contains("Map<String, Object> refusal = Cx.writeRefusal(error)"))
ok("and no longer carries a hard-coded 500 for a throw out of the save",
    !endpointCode.contains(
        "refuse(500, \"write\", \"The page could not be written: \" + Cx.errorDetail(error))"))

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"

/* The stand-in for the Confluence-internal type the write path recognises. The
 * endpoint does not import it - it cannot, it is not on the ScriptRunner side of
 * the boundary - and matches the class NAME instead, so a class of this suite's
 * own carrying that name is a faithful fixture rather than a shortcut. */
class ExternalChangesException extends RuntimeException {

    ExternalChangesException(String message) {
        super(message)
    }
}

/* A cause chain that never ends. Walking one is a hang, not a diagnosis, so the
 * walk has to stop - and a self-referencing cause is the shortest such chain
 * there is. */
class SelfCausedThrowable extends RuntimeException {

    SelfCausedThrowable(String message) {
        super(message)
    }

    @Override
    Throwable getCause() {
        return this
    }
}

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
