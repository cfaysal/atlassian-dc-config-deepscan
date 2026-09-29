# Confluence user macros: administrator guide

This guide covers [userMacroDeepScan.groovy](../confluence/userMacroDeepScan.groovy). It inventories Confluence Data Center user macros, their definitions, template signals, and an administrator's migration decision. The scan and exports do not save changes to Confluence.

## Install and open

You need Confluence Data Center, ScriptRunner, and membership in `confluence-administrators`. Place the Groovy file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from that file and save it. Open:

~~~text
https://<confluence-base-url>/rest/scriptrunner/latest/custom/userMacros
~~~

The GET and POST routes are group-gated. Install from a file so the large script is not constrained by the inline serialized setting.

## Inventory and decide

1. Open the HTML report. It lists macros visible to the UserMacroLibrary, with **Macro**, **Function / description**, **Content**, and **Still needed?** columns.
2. Read the macro definition and the analysis signals. A header comment is the author's description, while executable Velocity lines are the source for behavior. The analyser excludes comments from live-code signals.
3. For each macro, mark **Still needed?** and enter an administrator remark. The count below the table shows how many were assessed. An unassessed macro follows the decision rule shown beside the export button; review the rule before using results for migration planning.
4. Press **Check completeness** to compare library-visible names against stored configuration. This can reveal user macros hidden by a plugin macro of the same name.
5. Press **Save as .md** to download the inventory with current marks and remarks. Save it before leaving the page: marks are held only in that browser page and are not persisted in Confluence.

| Query parameter | Default | Meaning |
| --- | --- | --- |
| `format=html\|md\|json\|csv` | `html` | HTML review sheet or downloadable/structured export. `markdown` is also accepted. |
| `template=true\|false` | `true` | Include full Velocity template text. |
| `analyze=true\|false` | `true` | Analyse template signals. `false` leaves dependencies unanalysed. |
| `shadowCheck=true\|false` | `false` | Compare stored configuration for hidden or shadowed user macros. |
| `name=<text>` | none | Restrict to a named macro. |

Example: `?format=json&shadowCheck=true`. The HTML **Check completeness** control enables the shadow check. It may read stored values beyond the library-visible inventory, so run it deliberately.

## Interpret the output

The report distinguishes macro configuration, template text, metadata, and detected signals. **Context objects**, **parameter references**, **method calls**, HTML/CSS/JavaScript flags, resource hosts, embedded macro candidates, permission logic, and content metadata are investigation leads. A signal alone does not prove that the macro is used, safe to migrate, or equivalent to a Cloud feature. Treat commented-out code and unverified header claims separately from executable code.

A default scan covers the library-visible macros. With `shadowCheck=false`, absence of hidden macros is unknown. Read completeness warnings before making a global claim. A read error is not an empty library. If `template=false`, missing template text means “not requested,” not “no template.” If `analyze=false`, dependency findings are not measured.

Markdown contains the decision rule, source and completeness notes, macro metadata, parameters, template analysis and full templates when requested, marks/remarks, and a result template for post-processing. JSON carries the same inventory and read-state data. CSV is comma-separated with one row per macro; it includes metadata and signal columns plus `stillNeeded`, `decision`, and `remark`. CSV does not replace review of the full template. All formats can contain internal names, URLs, code, and credentials embedded by macro authors.

## Troubleshooting and handling

- **403:** sign in as a member of `confluence-administrators` and verify both endpoint group gates.
- **A macro seems missing:** inspect read status, then run **Check completeness**. A plugin with the same name can hide a stored user macro.
- **Analysis seems empty:** verify `template` and `analyze` settings and inspect whether the template is recorded.
- **Marks disappeared:** they are not saved server-side. Repeat the assessment and download Markdown before leaving.
- **Export fails:** read the inline status near **Save as .md**. The button sends a JSON POST to the same endpoint; it does not create a Confluence page.

The endpoint sends no-store response headers, but downloaded files still need access controls. Review and redact the export before sharing it or submitting it to any external analysis service.
