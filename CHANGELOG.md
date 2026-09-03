# Changelog

All notable changes to this project are documented here. Each endpoint carries its own
version, declared once in its helper class and printed by every output channel, so the
sections below are grouped by endpoint rather than by a single repository version.

## confluenceDCspaceInfo 0.1

### Added

- **A Confluence endpoint that answers how one space looks, rather than how it is
  configured.** Its sibling `confluenceDCspaceConfig.groovy` states as a rule that it counts
  no content and runs no search, so that it stays cheap enough to be harmless on a production
  instance. This endpoint is the deliberate counterpart: the space header, the page count and
  the trash count as two separate figures, and the page list with title, creator, creation
  date, last modification and who made it. It is a separate file so that the sibling's rule
  stays intact.

- **The page filter is measured, not assumed.** `CONTENT` holds one row per page VERSION.
  Measured on Confluence 10.2.15 over `contenttype = 'PAGE'`: 1005070 rows carry
  `content_status = 'current'` with a null `prevver` and a space id, 25 carry a non-null
  `prevver` with a NULL space id, 12 are trash and 40 are drafts. A live page is therefore
  current, without a previous version, and in a space. Two independent guards enforce that,
  and both stay: neither behaviour is documented by Atlassian, and dropping one does not fail
  loudly - it returns a plausible wrong number.

- **The landing page delivers no space list.** Opening the endpoint reads one number and
  renders a search box. Suggestions are answered per keystroke, ranked in SQL so the cap cuts
  by the order the reader sees. An earlier draft shipped every space to the browser to drive
  the search: 293 ms and 269822 bytes on an instance with 5038 spaces, spent before the reader
  had typed a character and almost all of it discarded. The suggestion statement is measured at
  80 ms for a broad prefix, counts included.

- **People are named rather than keyed.** `CONTENT.CREATOR`, `CONTENT.LASTMODIFIER` and
  `SPACES.CREATOR` store a user key. `USER_MAPPING` resolves it to a user name and `CWD_USER`
  to a display name, so the report shows `Display Name (username)` and falls back one layer at
  a time. Each fallback is a different fact: a user removed from the directory keeps their
  `USER_MAPPING` row, so the user name outlives the display name.

  The display name is read as a scalar aggregate, never as a join. `CWD_USER` keys a person by
  user name AND directory, so an instance running LDAP beside the internal directory can hold
  one user name twice and a join would emit the same page twice. A page list whose row count
  changes with the user configuration is worse than one without display names.

- **A CSV of the page list, which refuses to exist when the read failed.** `format=csv` with
  a space returns one row per page. A page list that could not be read produces NO document
  and a 500 with the reason: a spreadsheet has nowhere to put a banner, so an empty file and a
  failed read are the same thing once they are open, and the reader would conclude the space
  holds no pages. The cut state, the cap and the ordering travel on EVERY row for the same
  reason - a cap announced once is a cap nobody sees. A field opening with `=`, `+`, `-` or
  `@` is disarmed with an apostrophe, because Excel and LibreOffice execute such a field on
  open and a page title is written by anyone who can create a page.

- **A shortened page list offers the way out as an address.** The banner names the cap, the
  ordering it cut by and which pages fell off the end, and carries a link to the raised limit
  rather than prose telling the reader to build a URL. At the maximum it says so instead of
  offering a link that would change nothing.

### Known limitations

- This endpoint shares no code with its sibling and is deliberately not part of the
  `shared-renderer-drift` comparison. It therefore has no five-state node model; the
  distinction that matters, between a read that failed, one that found nothing and one that
  was cut short, is carried by its `Rows` type and rendered differently in each case.
- Its statements have not run on Oracle. None of them uses the `COALESCE` construct that broke
  the sibling there, which is not the same as being proven.
- The page count has not been reconciled against the figure the Confluence interface reports.
- A zero page count is measured rather than defaulted, because `COUNT(CASE ... THEN 1 END)`
  returns zero over zero rows. The rendered page does not yet say so, so a reader cannot tell
  that zero apart from a zero arrived at by other means.

## confluenceDCspaceConfig 0.2 and jiraDCprojectConfig 0.2

### Changed

- **Every glyph above ASCII is written as a `\uXXXX` escape, and a CI gate keeps it that
  way.** Four raw characters sat in output strings: the `\u2014` placeholder in `NA` in both
  endpoints, and the `\u2191` / `\u2193` sort arrows in the estate stylesheet. ScriptRunner
  compiles a script with the DEFAULT charset of the server JVM, which is a property of the
  customer's server and not of this repository. Measured on Groovy 3.0.21 with the identical
  source file: the raw literal reads as the intended character under `-Dfile.encoding=UTF-8`,
  as `U+FFFD` under `US-ASCII` and as mojibake under `ISO-8859-1`, while `\uXXXX` reads as the
  intended character under all three, in every string form the file uses including the triple
  quoted ones. The escape sits on the GROOVY side, so exactly one glyph still reaches the
  stylesheet: the one-escaping-layer rule that the comment next to the arrows records is
  unchanged, and a CSS escape there would still be the octal trap it warns about.
- **New hygiene gate: no raw character above `U+007F` in a tracked Groovy file.** Same class
  as the control-byte gate next to it and in the same place. It is written in Python rather
  than as a `grep -P` pattern, because the control-byte gate is a `grep -P` with a `|| true`:
  BSD grep has no `-P`, the error is swallowed, and the step reports green on a maintainer's
  Mac without having read a byte - observed while writing this, `grep: invalid option -- P`
  once per file, step result PASS. A gate that passes without checking is worse than none.
  The existing control-byte gate is left as it is; changing it is not part of this work and
  it does hold on the runner, which is GNU grep.

## userMacroDeepScan 4.1.0

### Changed

- **The export POST is `application/json` sent by `fetch`, not a form submission.** 4.0.1
  rendered the XSRF token into the form and was still refused on the instance: the page showed
  its own fail-loud line, so the token had not resolved there, and the button came back
  `XSRF check failed` all the same. The approach was closed either way, which is the finding
  that produced this version.

  `XsrfResourceFilter` in `atlassian-rest-common` checks every non-GET request whose media
  type is in `XSRFABLE_TYPES`. That list holds `application/x-www-form-urlencoded`,
  `multipart/form-data` and `text/plain` - exactly the three enctypes an HTML form can
  produce, and no more. A POST without JavaScript therefore cannot get past that filter at
  all, with or without a token. `application/json` is not in the list, and
  `X-Atlassian-Token: no-check` is a header only a script can set, so either half alone would
  carry the request.

  This is not a new idea in this repository. `confluence/confluenceDCspaceConfig.groovy` and
  `jira/jiraDCprojectConfig.groovy` have posted exactly this way against the same instance for
  months. The pattern was copied rather than reinvented.

- **The `<form>` is gone.** The checkboxes and remark fields are unchanged; they now sit in a
  plain section, the options keep their own box of hidden inputs, and the script reads both
  out of the DOM at click time. The CSS counter that drives the running tally moved its scope
  from `form` to that section. The button is `type="button"`: there is nothing to submit.

- **The answer is read as Markdown, not JSON.** `response.text()`, a `Blob`, and a temporary
  `<a download>`. The file name is unchanged.

- **A refusal is put on the page with its status and its body**, next to the button. A silent
  failure is the failure class that cost both earlier attempts: the page looked correct until
  the button was pressed.

- **A POST body that arrives and cannot be read as a JSON object is refused with `400`.**
  Ignoring it would fall through to the GET defaults, which render HTML - and the export
  script would save that page under a `.md` name, a wrong file that looks like a right one.
  An absent or blank body is still a GET in every respect and says nothing.

### Added

- `Uma.postedBody(body, diagnostics)` and `Uma.postedMarks(body)`: the JSON body as a map, and
  the marks out of it in the shape `applyMarks` already expects. Both are pure and both sit
  inside the offline-testable block. `postedMarks` keeps the rule its form-shaped predecessor
  established - the result is keyed by macro NAME, so a macro that moved between rendering the
  page and posting it gets its own mark rather than its neighbour's - and it accepts the tick
  as a JSON boolean or as the strings the form used, because losing a page of typed remarks to
  a type mismatch is by far the worse failure.
- `Uma.EXPORT_SCRIPT`: the whole script block as one constant, appended verbatim.

### Security

- **The no-script promise is replaced, not dropped.** Up to 4.0.1 this page carried no script
  at all, which mattered because it renders macro content. The narrower promise that takes its
  place is this one:

  > The script block is a constant and it **never** interpolates macro data. No macro name,
  > description, template or remark is ever written into it by the server. The marks are read
  > out of the DOM at export time, never embedded.

  This is enforced structurally rather than by care: the block is a plain triple-quoted string
  constant, so there is no interpolation to get wrong. The offline suite renders the same
  report with a macro whose name, key, title, description, template and remark all carry a
  quote, angle brackets and a literal `</script>`, with the same string arriving as a
  diagnostic line, as the name filter of the option fields and as the completeness href, and
  asserts that the block comes out identical to the one on a page of plain rows.
- Macro content still reaches the page through `esc()` only, in HTML text nodes and
  attributes. Nothing about that changed.
- The assertions that claimed "no `<script` anywhere on the page" were replaced by the sharper
  form rather than deleted: exactly one block, it is the constant, and no row datum is in it.

### Kept, and not called

Golden Rule 1: nothing was deleted. Each of these is annotated in place with why it is there
and why nothing calls it.

- `parseForm` and `marksIn` read the `application/x-www-form-urlencoded` shape. That is the
  shape the XSRF filter refuses, which is exactly why they are worth keeping as the reference
  for it.
- `xsrfField` and `salComponent` are the 4.0.1 token path. They are correct code for an
  approach that cannot work through this filter; deleting them would invite the next reader to
  try it again. Their offline assertions stay with them - a broken kept function is worse than
  a deleted one.
- `ENDPOINT_PATH` was the form action. The export now posts to `window.location.pathname`, but
  the constant is the documented name both registrations use.

### Not verified

- **Nothing here was measured against a running instance.** The offline suite proves the
  payload shape, the marks round trip, the refusal path and the constancy of the script block.
  It cannot prove that `XsrfResourceFilter` accepts this request, because the filter is not
  part of the offline block. What supports the choice is a measurement made elsewhere: the two
  sister endpoints in this repository post exactly this way against the same instance and have
  not been refused.

### Also in this release, without a version change

- The registration comment in `confluence/confluenceDCspaceConfig.groovy` and
  `jira/jiraDCprojectConfig.groovy` said "CSRF - UNVERIFIED. The Custom REST Endpoint
  documentation does not say whether these endpoints sit behind the XSRF filter". For
  Confluence that is now measured - the refusal of this endpoint's form POST on that instance
  is the measurement - and the comment says so. The Jira comment records what was measured on
  Confluence and states plainly that the same thing was **not** measured on Jira: the filter
  comes from `atlassian-rest-common`, which both products ship, but probable is not measured.
  Comment only in both files; no code changed and neither version moved.

## userMacroDeepScan 4.0.1

### Fixed

- **The export POST was refused with "XSRF check failed", which made the feature the 4.0.0
  rework was built for unusable on the instance.** The report opened, the marks could be set,
  and pressing Save as .md produced the error. The wording belongs to
  `XsrfCheckFailedException`, thrown by `XsrfResourceFilter` in `atlassian-rest-common`:
  `XSRFABLE_TYPES` contains `APPLICATION_FORM_URLENCODED`, which is exactly what a browser
  sends for `<form method="post">`, and `XsrfResourceFilterFactory` checks every non-GET
  method by default while checking `GET` only where a resource asks for it. That is the
  asymmetry that was observed. The filter runs before the endpoint closure, so the closure
  never saw the request and nothing in it could have reported the problem.

  The form now carries the token as a hidden field, rendered by the server. The field name is
  asked of `XsrfTokenValidator.getXsrfParameterName()` rather than written as the literal
  `atl_token`, which is what the Atlassian documentation asks for. The value is the persistent
  token from `XsrfTokenAccessor.getXsrfToken(request, response, create)` with `create` false
  and no response, so the token is READ from the session or the cookie and none is minted:
  the endpoint stays write-free.

  **The page still carries no script.** That is a security property of a page that renders
  macro content, not a preference, and none of the alternatives were open. The header
  exemption `X-Atlassian-Token: no-check` is a request header, and an HTML form cannot set
  one; Atlassian scopes that route to the command line and to other systems itself.
  `@XsrfProtectionExcluded` needs a JAX-RS method and a ScriptRunner closure is not one. The
  `atlassian.rest.xsrf.legacy.enabled` dark feature would turn the CSRF protection of every
  REST resource on the instance into opt-in, which is not a change to make for one report.

- **A token that cannot be resolved leaves a line on the page.** Missing component, no request
  on the thread, an empty field name, an empty token, or a throwing component: each renders
  the form without the field and adds a sentence to the existing diagnostics block above it,
  naming which of the three SAL objects failed and what the consequence is. Silence would have
  looked exactly like success until the button was pressed, which is how 4.0.0 shipped.

### Changed

- The SAL objects are resolved by class name and held as `Object`. `HttpContext.getRequest()`
  returns the servlet request, which is `javax.servlet` on Confluence 8 and `jakarta.servlet`
  from 9 on, so naming that type would have pinned the file to one platform line - the defect
  the CI gate against `javax` and `jakarta` imports exists to prevent. No import was added.
- The token call is written against `InvokerHelper` directly rather than through the file's
  `duckAll` helper. `duckAll` folds a thrown error into the same `null` it returns for an
  absent value, and telling those two apart is the whole job of this path.

### Not verified

- **Nothing here was measured against a running instance.** The offline suite proves the field
  is built, named from the validator, escaped, and that every failure path leaves a diagnostic.
  It cannot prove that `XsrfResourceFilter` accepts the token, because the filter is not part
  of the offline block. That remains open until the endpoint is exercised on ScriptRunner.

## userMacroDeepScan 4.0.0 - unreleased

### Removed, and the reason it is a major version

- **The heuristic pre-sort is gone.** `suggestedClass` and `suggestedReason` no longer appear
  in JSON, CSV, HTML or Markdown, the HTML chip that carried the verdict in green and amber
  is gone with them, and the rule in the embedded brief that warned readers about the
  pre-sort is gone because there is nothing left to warn about. Measured against the
  evidenced assessment it was meant to feed: the pre-sort said `FORGE_REQUIRED` 33 times
  where that assessment reached 12. Labelling a verdict "heuristic" does not stop it being
  read as a verdict, and a wrong one is worse than none. This breaks the output contract,
  which is what the major version is for.

### Added

- **The signals stayed.** The same five measurements the pre-sort weighed are reported as
  plain lines under `signals`, in all four formats: permission logic, JavaScript, resource
  loads with their host, the number of method calls, the context objects. Plus the linked
  hosts, still told apart from the loaded ones. No line says what it is worth. The four HTML
  chips that used to be red because they drove the verdict are neutral now; red is left for
  a state of the report itself, such as a template that could not be analysed.
- **The administrator marks the macros that are still needed.** Per macro, the HTML report
  offers a tick and a free text remark. An unmarked macro counts as obsolete and is not
  researched. That rule removes work, so it is stated rather than applied quietly - but where
  it is stated follows from what the reader is holding. The Markdown, JSON and CSV exports
  carry the full caveat above the inventory, with the count, the consequence and the name of
  every macro it applies to: in a file that has left the instance, the unassessed set is a
  decision already taken. The interactive page carries the rule as a single sentence in the
  bar at the bottom, beside the running count, the note that the marks are not saved in
  Confluence and are lost when the page is left, and the export button. Those are the three
  things that act on it.
- **The interactive page opens with no alert block and no name list.** Zero marks is the state
  every report starts in, so a block that fires on it is read once and skipped from the second
  report on, and it spends on a default state the attention the real findings need. On the
  reference instance it filled the top of the page with 60 of 60 macros by name before a
  single table row was visible, while the table says per row whether a macro is marked. The
  renderer that produced it is kept and unchanged; only its place in the view was wrong.
- The decision and the remark appear per macro in HTML, Markdown, JSON and CSV, and in the
  Markdown result table as a column of its own. The remark is user text, so it goes through
  the same gate as macro content in each format: `esc()`, `mdCell()`, `csvCell()` with the
  leading `=`, `+`, `-`, `@` neutralisation.
- **The export is a POST.** The Save as .md button became a form against the same endpoint,
  carrying `template`, `analyze`, `shadowCheck` and `name` as hidden fields. A page of typed
  remarks does not fit in a query string, and a renderer in the browser would be a second
  copy of output this repository has a drift gate against, so the server renders. The
  endpoint remains READ-ONLY: the POST is a rendering call, it has no write path in either
  method, and every response of both carries the no-store headers and nosniff. The JAX-RS
  namespace is still resolved reflectively; no `jakarta` or `javax` import was added.

### Fixed

- **F4, major.** With `?name=X&shadowCheck=true` the completeness check reported every other
  stored user macro as hidden by a plugin macro and put `visibleMacroCount` at 1. The set of
  visible names was built from the list the name filter had already reduced, while the check
  asks what the LIBRARY shows. It is built from the unfiltered pass now. Reachable with one
  click, because the completeness button carries the name filter with it.
- Same defect, second half: the name filter compared with `equalsIgnoreCase` while the shadow
  comparison was case-sensitive, so a macro stored under one casing and returned under
  another read as hidden. Both sides fold now.
- **F6, major.** The offline suite compiles only the block above the
  `END OF THE OFFLINE-TESTABLE BLOCK` banner, so the endpoint closure ran in no test - and
  F4 sat in that closure. The name-set building, the name filter and the whole mark
  evaluation are static `Uma` helpers now, inside the cut block and under test. The suite
  grew from 208 to 309 assertions, including one that combines the name filter with
  `shadowCheck` and proves the filtered-out macros do not land in `hiddenMacroNames`.

## userMacroDeepScan 3.6.0 - unreleased

### Added

- The documented template header is checked against the configuration instead of being
  printed as fact. A field still holding the placeholder text of Atlassian's own template
  is named, and so is a `Macro has a body` value that is neither yes nor no or that
  contradicts `UserMacroConfig`. Seen on a real macro: a header answering that question
  with a body-processing value.

## userMacroDeepScan 3.5.0 - unreleased

First version in this repository. The endpoint inventories Confluence Data Center user
macros and analyses each Velocity template for what it actually depends on, as input for a
Cloud migration assessment.

### Added

- User macro inventory at `/rest/scriptrunner/latest/custom/userMacros`, read-only and
  restricted to `confluence-administrators`. Four output formats: an HTML report, a
  Markdown handover for an analysis agent, JSON and CSV.
- Per template, a dependency analysis computed on the CODE half only. Velocity strips
  comments before rendering, and Atlassian's own recommended macro header is a comment
  block, so scanning the raw template reported documentation links as outbound calls and
  commented-out examples as live HTML. Comment findings are reported separately.
- A distinction between a host a resource is loaded from, which is a runtime dependency,
  and a host that is merely linked, which is not.
- Optional completeness check, `shadowCheck=true`. `UserMacroLibrary` does not return user
  macros hidden by an identically named plugin macro, so the default answer is the
  library-visible set and says so. The check compares against the stored configuration and
  names what the library does not show.

### Known limits

- The completeness check reads the stored configuration through `BandanaManager`, which is
  deprecated since Confluence 9.3 and marked for removal in 11.0. It is reached
  reflectively, so its removal costs the check and not the endpoint. Measured on a live
  instance: the documented replacement, `PluginSettings.get(String)`, returns null for the
  same key, so it is not a replacement for this value. Verified with javap, `PluginSettings`
  has get, put and remove and no way to enumerate keys.
- The completeness check has never been measured against an instance that actually has a
  hidden user macro. Every run so far found an empty difference.

## jiraDCprojectConfig 0.1 - unreleased

First working version.

### Added

- Project picker and a bookmarkable per-project report at
  `/rest/scriptrunner/latest/custom/projectConfig`.
- Full recursive scan of a project: issue type scheme, issue type screen scheme down to
  screen, tab and field, field configuration scheme down to per-field behaviour, custom
  field contexts with options and defaults, workflow scheme with every layer and each
  workflow's statuses and transitions, permission, notification and issue security schemes
  down to the resolved grant, project roles with actors, versions and components.
- Custom fields are split into the ones a context names this project on, which are expanded
  in full, and the ones that reach it only through a global context, which are counted in one
  line and not listed. On an instance with hundreds of custom fields the second group is
  nearly all of them, its contents are identical on every project, and it buried the handful
  that answer the question.
- The row budget of the exported page is shared between sections instead of spent in section
  order, and a cut section keeps its heading and says so.
- Priority scheme of the project with its priorities in picker order and the default for new
  issues, and the project properties apps have written, which no administration screen shows.
- The details a project screen does not put in one place: project email, avatar, creation and
  last change with who made it, and whether the project lead still holds a licensed
  application role.
- Per configuration item, what was missing before: custom field default values per context,
  the default issue security level of the project, an unpublished workflow scheme draft with
  who left it and when, workflow status and transition properties, tab and field positions on
  a screen, renderer names instead of plugin keys, custom events told apart from Jira's own,
  and the members of a group a scheme grants to.
- Jira Service Management on a service project: the customer portal, every request type
  with the issue type it raises, its portal groups and the fields of its customer form, the
  queues with the filter that defines each one, and the SLA time metrics. The section is
  left out entirely on a project that is not a service project, and on an instance without
  the app installed it says so rather than being empty. Every Service Management type is
  reached by name, so this file still loads where the app was never installed.
- HTML, JSON and CSV output. The HTML page carries a tree view and a flat table view of the
  same data and switches between them without another request.
- Confluence page export over a Jira application link, staged behind its button, with a
  Remark column that belongs to the administrator and is carried over verbatim. If that
  read fails, nothing is written. Each section gets its own table inside the bundled Expand
  macro, so the page opens closed; the remark read is unaffected, because it scans every
  table on the page whatever is wrapped around it.
- Deep link per node, every link shape backed by primary evidence read out of a running
  instance. The one shape that could not be evidenced carries no link and says so.
- Offline test suite compiled together with the Jira-free classes cut out of the endpoint
  itself, plus CI: parse check, suite, credential scan, and a check that application links
  are used only inside the declared transport section.
