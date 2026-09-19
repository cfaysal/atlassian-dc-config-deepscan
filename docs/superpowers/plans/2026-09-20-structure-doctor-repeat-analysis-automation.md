# OP-1371: repeat analysis and live Automation reads

Approved scope: repair repeated Structure analysis and connect the read-only Data Center Automation API. Deliver the generated single-file endpoint. Do not change Jira hierarchy, Automation configuration, permissions, or enable Core Apply.

1. Reproduce repeated full-document replacement with the production browser script. Add a failing regression for A -> B -> Plan -> A and error retry; isolate script declarations, then repeat in a browser.
2. Bind installed Automation services through the plugin-owned Spring context when not exported as OSGi services. Use documented read methods only. Normalize rule metadata and bounded, paginated audit reads. Missing methods, partial pages, unsupported rule actions and retention limits must remain explicit, never healthy empty results.
3. Cover the read adapter with synthetic service fixtures: rules, pagination, permission denial, exceptions, missing details and retention. Preserve the JSON fallback.
4. Rebuild the single-file artifact. Run the local Groovy suites, bundle checks, browser regression, and independent simplifier review. No GitHub Actions.
5. Record verified results and target-verification gaps in OP-1371 through the broker. Commit/push only with `[skip ci]`; independently check the remote artifact and absence of an Actions run.

The yellow first-character ScriptRunner warning is a file-level static-check failure, not a comment/import diagnosis. Its internal cause is UNKNOWN without the corresponding checker exception. Local parsing or mocked tests do not prove the proprietary target type checker succeeds.
