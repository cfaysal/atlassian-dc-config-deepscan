# Jira project configuration: administrator guide

This guide covers [jiraDCprojectConfig.groovy](../jira/jiraDCprojectConfig.groovy). It inventories the configuration of one Jira Data Center project for handover, audit, or migration planning. The scan does not change Jira configuration or count issues.

## Install and open

You need Jira Data Center, ScriptRunner, and membership in `jira-administrators`. Put the Groovy file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from that file and save it. Install it as a file because the script exceeds ScriptRunner's serialized inline setting.

~~~text
https://<jira-base-url>/rest/scriptrunner/latest/custom/projectConfig
~~~

Opening the URL without `project` shows the project picker. Search by key or name, select a project, and bookmark the resulting URL. The checkboxes in the picker select projects for the bulk export described below. Both the GET report and POST export routes are restricted to `jira-administrators`.

## Run a scan

1. Choose a project in the picker, or open `?project=ABC`.
2. Read the HTML report. **Tree** shows the hierarchy and **Table** shows rows. **Expand all**, **Collapse all**, and the node toggles change the browser view.
3. Follow a deep link to inspect its Jira administration page. A missing link can be intentional when a node has no distinct edit page.
4. Use **JSON** or **CSV** to download the selected project's data. **Pick another project** returns to the picker.

| Query parameter | Default | Meaning |
| --- | --- | --- |
| `project=<KEY>` | none | Selects one project. Without it the HTML picker appears even if `format` is supplied. |
| `format=html\|json\|csv` | `html` | Representation for a selected project. |
| `depth=collapsed\|full` | `collapsed` | Initial node expansion. `full` can make the page much larger. |
| `includeInactive=true\|false` | `true` | Include released and archived versions. |

Example: `?project=ABC&format=csv`. This endpoint has no `numbers` parameter. `depth=top` is not a supported value.

## Read the result

**Details** identifies the project. **Issue type scheme** lists types and the default. **Issue type screen scheme** follows types and operations down to screens, tabs, and fields. **Field configuration scheme** shows required, hidden, and renderer settings. **Custom fields** shows project-relevant contexts, options, and defaults; the shared global context is summarized. **Priority scheme** shows available and default priorities.

**Workflow scheme** follows schemes to workflows, statuses, transitions, conditions, validators, post functions, and screens. **Permission scheme**, **notification scheme**, and **issue security scheme** show grants, recipients, and security levels. **Project roles**, **versions**, **components**, and **project properties** expose further settings. **Jira Service Management** appears for a service project and covers portal, request types, queues, and SLAs when available.

A configured value is evidence of configuration, not proof that a particular work item used it. `includeInactive=false` removes some version evidence. Read diagnostics at the affected node: a failed or unsupported read is unknown, not an empty setting. Large membership lists can be capped; check the stated cap before treating a list as complete. Deep links lead to administration views but do not themselves authorize a change.

JSON carries report metadata, project, sections, totals, diagnostics, and notes. CSV is semicolon-separated with one row per configuration node: `path;kind;label;value;id;state;deepLink;linkNote;diagnostics`. These rows are configuration nodes, not work items. Keep diagnostics alongside the exported report.

## Optional Confluence page export

In the HTML report, press **Export to Confluence**. Select a configured Confluence application link, destination space, optional parent page, and title. Review these selections and press **Generate Confluence Page**. This POST writes the detail page on the selected Confluence instance. The page must carry this export's marker before it can be regenerated; unrelated detail pages are protected. Existing **Remark** cells are carried forward. If existing remarks cannot be read safely, the write is refused. Open the returned page to verify the destination and content.

The selected parent also becomes the fixed overview page. Choose the same parent for subsequent exports. An existing export page name updates its row, section links and counts; a new name adds a row. The page ID also prevents a duplicate after a rename. Counts match the detail page, including any truncation. Existing parent text and tables outside the generated overview remain intact. Without a parent, only the detail page is written.

Both writes use the selected authenticated application link. The calling user needs permission to update the parent as well as the detail page. The result links to both pages and reports overview failures or unconfirmed saves separately from detail success. Resolve the problem and re-export; matching by name avoids a duplicate. Keep the managed table and its invisible identity anchors intact; put your own notes outside it. A row whose link was saved without a space key is read as a link into the overview page's own space. A read error names the row by its position and anchor. Older detail pages appear when exported again with that parent.

To restore missing overview rows without exporting again, pick the target, the space and the overview page from the search, then press **Rebuild overview rows**. Over the same application link, the rebuild lists the direct child pages of the overview page (`GET /rest/api/content/{id}/child/page`, paged by its next link), reads each body on its own and takes the row from the detail page itself. All missing rows are added with one save, and each is confirmed after a read back. Existing rows are never replaced: a page that already has a row is reported as present, and a same-title row for another page ID makes the page skipped. Pages without the Jira export marker, Confluence export pages, a project named "unknown" on the page and pages whose sections do not parse are skipped with their reason. A child page that could not be read, or a child list cut at its cap of 20 listing requests (500 pages at the requested 25 per request), makes the result not complete; rows for those pages are unknown, so run the rebuild again.

### Export several projects at once

The project picker exports a selection of projects in one run, so an instance with hundreds of projects does not need one export per report page.

1. Tick the projects in the picker. **Select all shown** ticks every project the search currently shows, **Clear selection** clears every tick, shown or not.
2. Press **Choose the Confluence target**, then pick the application link, the space and the overview page with the same stages as the single export.
3. Once an overview page is picked, every project that already has a row in its overview table is marked **exported**. **Select shown, not yet exported** then ticks only the projects still missing. The marks come from that one overview table: a project exported without this parent, or under a title of its own, is not marked.
4. Press **Export selected (N)** and confirm. The projects are exported one after the other, never in parallel, because every export updates the same overview table. A progress bar shows how far the run is.

Each project is written exactly as the single export on its report page would write it: the report is read as JSON (`?project=KEY&format=json`) and posted to the same export route, under the title the single export suggests, `Jira project configuration - KEY`. A page with that title is updated and keeps its remarks; otherwise it is created. A parent typed by title is found or created by the first write, and every later write in the run names it by its ID, so one run cannot create the parent twice.

A failed project does not stop the run. After the run, every project that failed or was written with a warning is listed with its reason, and **Retry failed** runs only the failed ones again. Retrying is safe: an existing page is updated, not duplicated. **Stop after the current project** ends the run after the write in progress; the rest is not touched. Keep the tab open until the run has finished: the run happens in the browser, and the page warns before it is closed during a run.

## Limits and troubleshooting

- **403:** sign in as a member of `jira-administrators` and verify the group gate on both routes.
- **No project report:** supply a valid project key. `format=json` without `project` still opens the HTML picker.
- **Partial node or absent link:** read its diagnostic or link note. A failed read is not proof that the setting is absent.
- **Large page:** keep the default collapsed depth and expand only relevant branches.
- **Export refused:** check application link, target permissions, selected space and parent, and the marker on an existing page. Do not bypass a remark-read failure.
- **No project marked as exported:** marks need a picked overview page. A typed title that was not picked marks nothing, and an overview table that cannot be read says so in the note above the list instead of marking nothing silently.

The GET scan stays on Jira. The explicit page export contacts Confluence. Treat downloaded reports and exported pages as administrator data because they can contain people, configuration values, and links.
