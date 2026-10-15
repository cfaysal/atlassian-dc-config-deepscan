# Confluence space configuration: administrator guide

This guide covers [confluenceDCspaceConfig.groovy](../confluence/confluenceDCspaceConfig.groovy). It shows a Confluence Data Center space inventory, then a detailed configuration tree for one space. GET scans are read-only; an explicit page export is the only write.

## Install and open

You need Confluence Data Center, ScriptRunner, and membership in `confluence-administrators`. Put the file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from the file and save it. Use a file because this script is too large for the serialized inline setting.

~~~text
https://<confluence-base-url>/rest/scriptrunner/latest/custom/spaceConfig
~~~

Both the GET and POST routes are restricted to `confluence-administrators`.

## Run a scan

1. Open the URL without `space` for the instance space inventory. Search by key or name and sort the delivered rows in the browser. These controls work on rows already loaded. The checkboxes select spaces for the bulk export described below.
2. Select a space key for its full report, or use `?space=ABC`.
3. Use **Tree** or **Table**, node toggles, **Expand all**, and **Collapse all** to inspect the result. **Pick another space** returns to the inventory.
4. Use **JSON** or **CSV** on a selected space for machine-readable evidence.

| Query parameter | Default | Meaning |
| --- | --- | --- |
| `space=<KEY>` | none | Selects one space. Without it the HTML estate inventory appears, even when `format` is supplied. |
| `format=html\|json\|csv` | `html` | Representation for one space. |
| `depth=collapsed\|full` | `collapsed` | Initial node expansion. `full` can make a large response. |
| `values=true\|false` | `false` | Include property values. Secret-like keys remain redacted. |

Example: `?space=ABC&format=json&values=false`. The **Values** control changes the same setting in HTML. Use `values=true` only when report recipients are authorized to see configuration values.

## Read the result

The estate inventory lists stored space facts for triage. It uses read-only, set-based database queries and shows read status. It is not a page-count or content search. Select a space for these six sections:

| Section | What to inspect |
| --- | --- |
| **Details** | Space identity, type, status, and administrative metadata. |
| **Permissions** | Who has configured rights in the space; verify effective access separately where needed. |
| **Properties** | Stored keys and, if requested, values. Redaction still applies. |
| **Look and Feel** | Space theme and presentation settings. |
| **Templates** | Space templates and related configuration. |
| **Categories** | Assigned categories. |

The tree records configuration, not whether a page is used. A diagnostic means the section or node was not fully read; do not turn an error into “nothing configured.” A deep link leads to the closest established administration view, with a link note where needed.

Per-space JSON carries report metadata, space, sections, totals, diagnostics, and notes. Per-space CSV is semicolon-separated, one row per configuration node: `path;kind;label;value;id;state;deepLink;linkNote;diagnostics`. Choose a space before requesting these formats. Keep redaction and diagnostic context with exported data.

## Optional Confluence page export

From a selected space report, press **Export to a page**. Search for and select the destination space, optionally choose a parent page, enter the title, and press **Generate the page**. This POST writes the detail page on the same Confluence instance. It preserves existing **Remark** cells when regenerating its own marker-protected page. It refuses to overwrite an unrelated detail page or proceed when existing remarks cannot be read safely. Check the returned page and destination after generation.

The selected parent also becomes the fixed overview page. To create one during export, type its new title in the parent field without selecting an existing result, then press **Generate the page**. Parent creation, detail persistence and overview persistence share one SAL transaction, so the new parent can receive its table during that first export. Choose the same parent for subsequent exports to accumulate rows in one table. An existing export page name updates its row and section links; a new name adds a row. The page ID also prevents a duplicate after a rename. Section counts match the detail page, including any truncation. Existing parent text and other tables outside the managed overview remain intact. Without a parent, the export writes only the detail page.

The result links to both pages and states whether the overview was updated. A parsing or verification problem can be reported separately when the transaction completes. A persistence error or failed commit instead produces a transaction error, with no claim that the detail page was written. Check the destination pages before retrying after that error. Matching by name avoids a duplicate when exporting again. The overview requires permission to update the selected parent. Keep its generated table and invisible identity anchors intact; add your own notes outside it. A row whose link was saved without a space key is read as a link into the overview page's own space. A read error names the row by its position and anchor. Older detail pages appear in the overview when exported again with that parent.

### Rebuilding the overview

When overview rows are missing but the detail pages still sit under the overview page, select the space, pick the overview page from the search and press **Rebuild overview rows**. The rebuild reads the direct child pages of that page and takes each row's name and section counts from the detail page itself; nothing is scanned again and no detail page is written. All missing rows are added with one save of the overview, and each added row is read back and confirmed. The rebuild never replaces or rewrites a row: a page that already has a row is reported as present, and a page whose title is already held by a row for another page ID is skipped with that page ID. A child page without the export marker, a Jira export page, a page that is not current, a page whose header names the space as "unknown" and a page whose sections do not parse exactly are skipped, each with its reason. A child page that could not be read is reported as unread and the result as not complete; its row is unknown, not absent, so run the rebuild again. A typed title that was not picked cannot be rebuilt: a rebuild never creates a parent.

### Export log

Every successful export of a space, single or bulk and by any administrator, is recorded in the export log of this instance: when, by whom, to which space and page, and under which parent. The space inventory shows the entry as a mark on the space, and the report page shows it above the export form as **Last export**. The log is read without any request to Confluence.

The log is stored in the application's own settings store (SAL plugin settings, global scope), one small entry per space under the key `cfcon.cfgexport.<product>.<KEY>`; a key that would be longer than 100 characters is stored under its SHA-256. Every node of a Data Center cluster reads the same store from the database. Only the latest export of a space is kept. The entry is written after the export transaction has committed, so a failed export leaves no entry.

The log records exports; it does not follow Confluence. A page or an overview row deleted later is still in the log, so check the overview from the export form when the current state matters. A log entry that cannot be written is reported with the export as **EXPORT LOG NOT UPDATED**; the page itself is written all the same. The export log has not yet been measured on an instance: if the settings store cannot be reached from the endpoint, every page says so and the overview check still works.

### Export several spaces at once

The space inventory exports a selection of spaces in one run, so an instance with hundreds of spaces does not need one export per report page.

1. Tick the spaces in the inventory. **Select all shown** ticks every space the search and the **Show** filter currently show, **Clear selection** clears every tick, shown or not. A space whose key could not be read has no checkbox, because the export names a space by its key.
2. Press **Choose the target space**, then pick the space and the overview page with the same stages as the single export.
3. Every space in the export log of this instance carries a blue mark such as **exported 2026-10-15 to DOC / Config overview**; hover it for the time, the administrator, the target and the page. Once an overview page is picked, every space that has a row in its overview table also gets a green **in overview** mark. **Select shown, not yet exported** ticks only the shown spaces that carry neither mark.
4. Press **Export selected (N)** and confirm. The spaces are exported one after the other, never in parallel, because every export updates the same overview table. A progress bar shows how far the run is.

Each space is written exactly as the single export on its report page would write it: the report is read as JSON (`?space=KEY&format=json`) and posted to the same export route, under the title the single export suggests, `Confluence space configuration - KEY`. A page with that title is updated and keeps its remarks; otherwise it is created. Property values are exported only when the inventory itself was opened with `values=true`, which is the same rule as for a single report. A parent typed by title is found or created by the first write, and every later write in the run names it by its ID, so one run cannot create the parent twice.

A failed space does not stop the run. After the run, every space that failed or was written with a warning is listed with its reason, and **Retry failed** runs only the failed ones again. Retrying is safe: an existing page is updated, not duplicated. **Stop after the current space** ends the run after the write in progress; the rest is not touched. Keep the tab open until the run has finished: the run happens in the browser, and the page warns before it is closed during a run.

## Limits and troubleshooting

- **403:** use a member of `confluence-administrators` and check the group gate on both routes.
- **Unexpected HTML after JSON/CSV request:** include `space=<KEY>`; no space means the inventory.
- **Missing values:** `values=false` is the default. Secret-like keys stay redacted with `values=true`.
- **Incomplete inventory or section:** read diagnostics and check database/API access and schema compatibility. A failed read is unknown, not zero.
- **Export refused:** check permissions, destination, parent, and the marker on an existing page. Do not remove the marker to force an overwrite.
- **No space marked as exported:** the blue marks come from the export log; if the log cannot be read, the page says so at the top instead of showing every space as not exported. The green marks need a picked overview page; a typed title that was not picked marks nothing, and an overview table that cannot be read says so in the note above the table.

This endpoint does not count pages or run CQL. Use [Space information](confluence-space-information.md) for a page inventory. Treat reports and exported pages as administrator data, especially with `values=true`.
