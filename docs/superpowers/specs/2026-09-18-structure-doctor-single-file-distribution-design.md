# Structure Doctor single-file distribution design

**Work item:** OP-1371  
**Date:** 2026-09-18  
**Status:** Approved approach, implementation pending

## Problem

The maintained Structure Doctor consists of one ScriptRunner REST controller and 33 focused
Core source files. That layout is appropriate for development and testing, but ScriptRunner's
Script Editor has no bulk directory import. A Jira administrator with UI access only would
otherwise have to create and paste 34 files individually.

The deployable form must therefore be one Groovy file without turning the maintained source
back into a 4,861-line monolith.

## Decision

Keep the modular sources as the only hand-edited implementation and generate one deterministic
distribution file:

```text
dist/structuredoctor/structureIssueDoctor.groovy
```

The generated file is a build artifact. It is never edited directly. Its large line count is
an explicit distribution-only exception to the 250-line source-file limit.

## ScriptRunner installation contract

The administrator performs one paste in ScriptRunner's Script Editor:

1. Create or select the `structuredoctor` folder in a configured Script Root.
2. Create `structureIssueDoctor.groovy` inside that folder.
3. Paste the complete generated file and save it.
4. Configure or rescan the REST endpoint using
   `structuredoctor/structureIssueDoctor.groovy`.

The bundle declares `package structuredoctor`, so its relative file path must match that
package. No other Doctor source file is installed.

## Generator

A repository tool builds the distribution from exactly these inputs:

- `jira/structureIssueDoctor.groovy`
- every `jira/structuredoctor/*.groovy` file

The generator:

1. reads the Core files in stable lexical order;
2. collects and de-duplicates normal and static imports;
3. emits one `package structuredoctor` declaration;
4. removes per-file package and import declarations;
5. emits the complete Core definitions followed by the REST controller body;
6. preserves source markers naming every contributing file;
7. writes normalized LF output with a final newline; and
8. embeds a manifest containing the relative input paths and a SHA-256 fingerprint of their
   normalized contents.

The build fails rather than silently omitting an unreadable source, an unexpected package, or
an empty controller/Core set. The tool accepts no arbitrary source directory from browser or
runtime input.

## Runtime behavior

The single-file artifact must preserve the modular implementation without changing behavior:

- exactly six ScriptRunner endpoint declarations;
- every endpoint restricted to `jira-administrators`;
- explicit missing-user rejection remains present;
- Jira hierarchy and Automation remain read-only;
- the capability-gated Core writer remains disabled by default;
- the legacy Parent Link confirmation remains `SET_PARENT_LINK`; and
- no new credentials, outbound calls, scopes, or permissions are introduced.

The generator is packaging only. It does not enable a live provider, writer, deployment, or
mutation probe.

## Verification

Automated checks must prove:

- two consecutive builds are byte-identical;
- rebuilding the committed artifact produces no diff;
- the embedded manifest covers the controller and all Core inputs exactly once;
- the artifact contains no residual per-file `package` declarations or missing imports;
- Groovy conversion-phase parsing succeeds;
- endpoint count, administrator groups, authentication checks, confirmation constant, and
  disabled-writer contract match the maintained sources;
- the existing modular test suites remain green; and
- credential, outbound-network, ASCII, control-byte, and diff hygiene gates remain green.

CI regenerates the artifact and fails on drift. This prevents a source change from being
merged without the corresponding single-file distribution update.

## Error handling

Generation errors are terminal and name the affected relative source path without printing
file contents. A stale or partial artifact is never accepted as a successful build. The
previous committed artifact remains unchanged when generation fails before final assembly.

## Non-goals

- Converting the Doctor into a Jira Data Center plugin or JAR.
- Installing or deploying to a Jira instance.
- Enabling the Core writer.
- Replacing the modular source with the generated artifact.
- Adding a self-modifying installer or filesystem-writing ScriptRunner bootstrap script.

## Acceptance criteria

1. The repository produces exactly one deployable Doctor Groovy file.
2. A Jira administrator needs to paste only that file into ScriptRunner's Script Editor.
3. No `jira/structuredoctor/*.groovy` file is required on the target instance.
4. The generated artifact is deterministic, traceable to all maintained inputs, and guarded
   against drift in CI.
5. Existing security boundaries and runtime behavior remain unchanged.
6. No installation, deployment, or live mutation occurs during implementation.
