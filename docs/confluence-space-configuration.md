# Confluence space configuration: administrator guide

This guide covers [confluenceDCspaceConfig.groovy](../confluence/confluenceDCspaceConfig.groovy). It shows a Confluence Data Center space inventory, then a detailed configuration tree for one space. GET scans are read-only; an explicit page export is the only write.

## Install and open

You need Confluence Data Center, ScriptRunner, and membership in `confluence-administrators`. Put the file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from the file and save it. Use a file because this script is too large for the serialized inline setting.

~~~text
https://<confluence-base-url>/rest/scriptrunner/latest/custom/spaceConfig
~~~

Both the GET and POST routes are restricted to `confluence-administrators`.

## Run a scan

1. Open the URL without `space` for the instance space inventory. Search by key or name and sort the delivered rows in the browser. These controls work on rows already loaded.
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

From a selected space report, press **Export to a page**. Search for and select the destination space, optionally choose a parent page, enter the title, and press **Generate the page**. This POST writes one page on the same Confluence instance. It preserves existing **Remark** cells when regenerating its own marker-protected page. It refuses to overwrite an unrelated page or proceed when existing remarks cannot be read safely. Check the returned page and destination after generation.

## Limits and troubleshooting

- **403:** use a member of `confluence-administrators` and check the group gate on both routes.
- **Unexpected HTML after JSON/CSV request:** include `space=<KEY>`; no space means the inventory.
- **Missing values:** `values=false` is the default. Secret-like keys stay redacted with `values=true`.
- **Incomplete inventory or section:** read diagnostics and check database/API access and schema compatibility. A failed read is unknown, not zero.
- **Export refused:** check permissions, destination, parent, and the marker on an existing page. Do not remove the marker to force an overwrite.

This endpoint does not count pages or run CQL. Use [Space information](confluence-space-information.md) for a page inventory. Treat reports and exported pages as administrator data, especially with `values=true`.
