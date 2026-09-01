# Changelog

All notable changes to this project are documented here. Each endpoint carries its own
version, declared once in its helper class and printed by every output channel, so the
sections below are grouped by endpoint rather than by a single repository version.

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
  researched. That rule removes work, so it is stated rather than applied quietly: every
  export carries a line of its own, above the inventory, naming how many macros went unmarked
  and which, and the HTML page keeps a running count and says plainly that the marks are not
  saved in Confluence and are lost when the page is left.
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
