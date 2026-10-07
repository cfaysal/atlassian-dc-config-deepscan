# Overview typecheck fix implementation plan

**Goal:** Fix OP-1475's new overview helper typechecking errors in both standalone endpoints and publish the verified files to remote main.

**Architecture:** Preserve the existing Map-based storage model and transports. Give homogeneous maps generic value types and narrow mixed-map values to typed locals before invoking String, collection and Number methods. Keep the embedded helpers identical. Typecheck their actual shipped source against dynamically compiled, source-derived platform-free dependencies.

**Tech stack:** Groovy 3.0.21, Java 17, Python test runner and GitHub Actions.

The Director approved this follow-up after the regression report. OP-1475 exists with problem, goal and acceptance criteria; its follow-up comment records the static gate and remote artifact acceptance. One writer uses the clean existing checkout on `fix/op-1475-overview-typecheck`, based on `origin/main`. `pre-op-1475-overview-typecheck` preserves the baseline.

## 1. Failing static regression gate

- Add `tools/config-overview-typecheck.groovy`. Extract the same actual Pc-through-export helper block as the functional suites, load it dynamically, and compile ConfigOverview/OverviewExport with TypeChecked through INSTRUCTION_SELECTION. Missing extraction markers and compilation errors must exit nonzero.
- Run the gate separately for `confluence/confluenceDCspaceConfig.groovy` and `jira/jiraDCprojectConfig.groovy`; record the existing errors before source changes.

## 2. Minimal matching endpoint fixes

- Type homogeneous identity, section and column maps as `Map<String, String>`.
- Narrow mixed-map marker names and offsets, model row/column lists and page versions at their boundaries. Reuse the already validated Number value when comparing versions.
- Apply the identical helper block to both endpoints, retaining validation, markup and transport behavior.
- Run the new gate and both existing source-derived functional suites.

## 3. Enforced verification and publication

- Invoke the gate for both endpoint matrix legs in `.github/workflows/ci.yml` and from `tools/run-config-tests.py`.
- Update CONTRIBUTING.md and ARCHITECTURE.md to describe the narrower offline static gate and the remaining full instance-classpath requirement.
- Verify a deliberately injected original Map/String regression is refused by the new gate. Run parser, functional, renderer drift and publication/source checks.
- Obtain bounded independent review and simplifier feedback on the current diff. Preserve unrelated source and guards.
- Commit with the OP-1475 prefix, publish through a PR, merge the passing revision and read both endpoint blob identities from remote main. Public repository CI is enabled.
- Record target evidence and remaining live-instance limitations on OP-1475. Preserve test evidence locally and remove reconstructible temporary runtime files after integration.

The documented endpoint installation is manual placement into a chosen ScriptRunner script root and saving its custom endpoint. This request follows the remote-main status question; no concrete Jira/Confluence instance was identified, so remote source rollout is the measured target. Do not claim live installation or live permission behavior.

## Verified before publication

Groovy 3.0.21 reproduced 31 static diagnostics per endpoint before the fix. After the fix, both actual Overview helper blocks pass TypeChecked, all 1,063 functional checks and both parse checks pass, and the injected Object-to-String regression and missing helper markers are refused. The shared renderer drift check passes. Independent review and simplifier review found no blocking issues. Remote delivery receipts and remaining instance checks are recorded on OP-1475.
