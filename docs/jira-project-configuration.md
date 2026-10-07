# Jira project configuration: administrator guide

This guide covers [jiraDCprojectConfig.groovy](../jira/jiraDCprojectConfig.groovy). It inventories the configuration of one Jira Data Center project for handover, audit, or migration planning. The scan does not change Jira configuration or count issues.

## Install and open

You need Jira Data Center, ScriptRunner, and membership in `jira-administrators`. Put the Groovy file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from that file and save it. Install it as a file because the script exceeds ScriptRunner's serialized inline setting.

~~~text
https://<jira-base-url>/rest/scriptrunner/latest/custom/projectConfig
~~~

Opening the URL without `project` shows the project picker. Search by key or name, select a project, and bookmark the resulting URL. Both the GET report and POST export routes are restricted to `jira-administrators`.

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

Both writes use the selected authenticated application link. The calling user needs permission to update the parent as well as the detail page. The result links to both pages and reports overview failures or unconfirmed saves separately from detail success. Resolve the problem and re-export; matching by name avoids a duplicate. Keep the managed table and its invisible identity anchors intact; put your own notes outside it. Older detail pages appear when exported again with that parent.

## Limits and troubleshooting

- **403:** sign in as a member of `jira-administrators` and verify the group gate on both routes.
- **No project report:** supply a valid project key. `format=json` without `project` still opens the HTML picker.
- **Partial node or absent link:** read its diagnostic or link note. A failed read is not proof that the setting is absent.
- **Large page:** keep the default collapsed depth and expand only relevant branches.
- **Export refused:** check application link, target permissions, selected space and parent, and the marker on an existing page. Do not bypass a remark-read failure.

The GET scan stays on Jira. The explicit page export contacts Confluence. Treat downloaded reports and exported pages as administrator data because they can contain people, configuration values, and links.
