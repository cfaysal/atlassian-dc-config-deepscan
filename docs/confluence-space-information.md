# Confluence space information: administrator guide

This guide covers [confluenceDCspaceInfo.groovy](../confluence/confluenceDCspaceInfo.groovy). It answers which pages are currently stored in a selected Confluence Data Center space and reports space details and page counts. It does not change Confluence.

## Install and open

You need Confluence Data Center, ScriptRunner, and membership in `confluence-administrators`. Put the Groovy file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from the file and save it. Then open:

~~~text
https://<confluence-base-url>/rest/scriptrunner/latest/custom/spaceInfo
~~~

This GET endpoint is restricted to `confluence-administrators`. Install it as a file rather than pasting a large script inline.

## Find and inspect a space

1. Open the URL and type at least two characters of a space key or name. The page requests matching suggestions; the complete space list is not sent to the browser on initial load.
2. Select a suggestion, or open `?space=ABC` directly. The report identifies the space and shows **Space**, **Page counts**, and **Pages**.
3. Use **Back to all spaces** to search again. Review the page-list cap message before interpreting the visible rows as complete.
4. Use `?space=ABC&format=json` for structured evidence or `?space=ABC&format=csv` for a page inventory.

| Query parameter | Default | Meaning |
| --- | --- | --- |
| `space=<KEY>` | none | Selects one space. Without it HTML shows the search page. |
| `find=<text>` | none | At least two characters; returns JSON suggestions for the search UI. |
| `format=html\|json\|csv` | `html` | Selected-space representation. Without a space, JSON reports the space count; CSV is refused. |
| `limit=<n>` | `5000` | Maximum current page rows to list, capped at `20000`. |

## Read the result

**Space** shows key, name, type, status, creator, and creation time. **Page counts** distinguishes current pages from deleted/trash pages. **Pages** lists current pages, ordered as stated in the report, with title, content ID, creator and creation date, last editor and modification date. Historical versions, drafts, blog posts, and deleted pages are not rows in this current-page list.

The report states when the list reaches its cap. Raise `limit` deliberately, up to `20000`, or use the space page tree for further inspection. A capped list is a partial list, not a complete inventory. The page count and listed rows answer different questions; do not infer that the row count equals all content.

JSON separates the space object, page counts, and page-list state. CSV is semicolon-separated and represents **pages**, not configuration nodes: `spaceKey;title;contentId;createdBy;created;lastModifiedBy;lastModified;listState;listCap;listOrder`. Preserve `listState` and `listCap` when using CSV in an audit. A failed count or list read is reported as failed or unknown, not as zero pages. The endpoint uses read-only SQL with schema checks.

## Troubleshooting and handling

- **403:** sign in as a member of `confluence-administrators` and verify the endpoint group setting.
- **No suggestions:** type at least two characters. A search error is a failed lookup, not proof that the space is absent.
- **CSV refused:** add `space=<KEY>`. CSV is produced for one space only.
- **Fewer rows than page count:** read the list cap and state; increase `limit` if appropriate. The page list covers current pages only.
- **Read failure:** check the displayed stage/error and the database schema/access on this Confluence installation. Do not publish a zero based on a failed read.

This endpoint has no POST write. Its output can contain page titles and user names, so handle downloads as administrator data. For permissions, properties, templates, and other settings, use [Space configuration](confluence-space-configuration.md).
