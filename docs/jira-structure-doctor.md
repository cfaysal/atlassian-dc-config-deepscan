# Structure Doctor: administrator guide

This guide covers the generated [Structure Doctor ScriptRunner file](../dist/structuredoctor/structureIssueDoctor.groovy). It investigates the relationship between Jira parent links and physical Structure rows, looks for duplicate occurrences, and correlates available Automation evidence. It is a diagnostic aid, not a complete repair service.

## Install and open

You need Jira Data Center, ScriptRunner, the Structure app and its API, and membership in `jira-administrators`. The analysis also attempts to read the configured hierarchy and Automation services; missing or incompatible sources appear as coverage gaps.

Install only `dist/structuredoctor/structureIssueDoctor.groovy` in a `structuredoctor` folder under a configured ScriptRunner Script Root. Configure or rescan the custom REST endpoint from that file. Do not install the modular `jira/structuredoctor/` sources or development probes as endpoints. The generated file still needs compilation and acceptance on the target ScriptRunner classpath; passing offline tests does not establish compatibility on your Jira installation.

~~~text
https://<jira-base-url>/rest/scriptrunner/latest/custom/structureIssueDoctor
~~~

All Doctor routes are restricted to `jira-administrators`. Opening the page initially reads the visible Structure list. A full snapshot starts only when you press **Structure analysieren**. The active Core interface is in German.

## Analyse a Structure

1. Choose a Structure. Optionally enter a work-item key to limit what the report **displays**. This does not narrow the complete Structure snapshot used for analysis and safety checks.
2. Set the Automation audit window if needed. The default is 30 days; the UI accepts 1 to 365 days. The requested window is not proof that complete audit history exists.
3. Optionally supply an official Automation rule-export JSON and a Structure Doctor audit-evidence JSON. Each file is limited to 5 MiB. The files are parsed as data, not executed.
4. Press **Structure analysieren**. Read **Datenquellen und Prüfgrenzen** first. Only then interpret hierarchy, duplicates, Automation findings, and causal grades.
5. For duplicate groups, compare physical occurrences and provenance. Select the one occurrence to retain and the eligible permanent rows, then press **Duplikatauswahl prüfen** to check a plan. Nothing is changed by planning.

The browser calls JSON endpoints at `structureIssueDoctorAnalyze` and `structureIssueDoctorPlan`. They reject unknown keys. Direct callers must use `application/json` and the documented payload keys in the source; use the UI for normal administration. `GET structureIssueDoctor?format=json` returns the Structure list, not a full analysis. `GET structureIssueDoctorStatus?operationId=...` reads operation status; it does not make an operation run.

## Read the analysis and plan

**Datenquellen und Prüfgrenzen** lists each source as `COMPLETE`, `INCOMPLETE`, `FAILED`, or `UNAVAILABLE`. Treat the last three as gaps. A report with no visible findings after a filtered or incomplete read is not a clean bill of health.

**Hierarchie-Hinweise** compares the Structure parent/path with the permission-checked Jira parent relation, hierarchy level, row, and provenance. `ORPHAN` describes an unassigned-parent observation; it does not prove Jira requires a parent. An observation is an investigation lead, not an approved repair.

**Duplikate** groups physical occurrences of the same work item. Generator provenance and permanent rows matter: the same key appearing twice does not by itself identify a safe row to remove. No retained occurrence is preselected. The plan validates your selection but the Core cannot execute it.

**Automation-Prüfung** combines rule configuration, available audit executions, and optional JSON evidence. Rule-read and audit-read coverage are separate. An audit association is not proof that a rule wrote a field. Embedded Groovy actions are opaque to this analysis. **Ursachen und Beleglage** grades claims as `CONFIGURATION_CONFLICT`, `POSSIBLE_CAUSE`, `PROBABLE_CAUSE`, or `CONFIRMED_CAUSE` and lists missing evidence. Only the last grade asserts a confirmed causal chain under the implemented checks.

The plan states selected finding groups and prerequisites. It is a review artifact, not an executable change request. The Core **Apply** action is disabled in this version because the live repair writer and target acceptance are incomplete. Do not describe a planned change as applied.

## Separate legacy Parent Link action

The script also registers `POST structureIssueDoctorFix` for a narrow compatibility action. This **can mutate** a Jira Parent Link after validation. It accepts JSON keys `structureId`, `issueKey`, and `confirm`; confirmation must be exactly `SET_PARENT_LINK`. It checks visibility and applicable hierarchy, context, permission, and workflow constraints, uses Jira IssueService, and reads back the result. It emits an issue-updated event without email. It is separate from the Core duplicate plan and is not an implementation of Core Apply. Use it only under an independently approved change procedure and verify the work item afterward.

## Troubleshooting and limits

- **403:** use a member of `jira-administrators` and verify the endpoint group gates.
- **No Structure or partial scan:** read each source status and blocker. An unavailable app API or limited permission is unknown, not an empty Structure.
- **An optional key has no result:** the key only filters displayed findings. A filtered empty display says nothing about the unfiltered snapshot.
- **Automation finding lacks confirmed cause:** check rule and audit coverage, retention, upload schema, and missing evidence. Increasing the requested window cannot recover expired entries.
- **Apply is disabled:** this is the current behavior. Do not use the legacy fix endpoint to execute a Core duplicate plan.
- **ScriptRunner type-check marker:** inspect the target instance's checker exception. The marker on the first character does not identify a first-line source defect.

Analysis data can contain work-item keys, summaries, hierarchy, and Automation details. Restrict access to downloaded/uploaded evidence. The code currently targets the field named `Strategische Ziele KPI`; it does not silently translate that name to `Strategic Goals KPI`. Check the field name on your Jira instance before relying on findings tied to it.
