# Structure Doctor Single-File Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate one deterministic ScriptRunner-ready Groovy file from the maintained Structure Doctor controller and all 33 modular Core sources.

**Architecture:** Keep the 34 maintained Groovy sources authoritative. A dependency-free Node ESM generator validates and normalizes them, hoists imports, emits one package declaration, embeds a source manifest and fingerprint, and atomically writes the distribution artifact. A local Node test suite proves determinism, source coverage, security invariants, and drift without invoking GitHub Actions.

**Tech Stack:** Node.js built-ins (`fs`, `path`, `crypto`, `node:test`), Groovy source text, existing local Groovy test harnesses.

---

### Task 1: Define the bundle contract as failing tests

**Files:**
- Create: `tools/tests/structure-doctor-bundle.tests.mjs`
- Test: `jira/structureIssueDoctor.groovy`
- Test: `jira/structuredoctor/*.groovy`

- [ ] **Step 1: Write source-inventory and deterministic-build tests**

  Use `node:test`, `assert/strict`, `child_process.spawnSync`, and a temporary directory. Assert that the inventory is one controller plus exactly 33 lexically sorted modules, two independent generations are byte-identical, output uses LF with one final newline, and `--check` detects a modified artifact without overwriting it.

- [ ] **Step 2: Write content and security invariant tests**

  Parse the generated text and assert exactly one `package structuredoctor`, unique import lines, 34 unique `// SOURCE:` markers, an exact manifest path list, a 64-character lowercase SHA-256 fingerprint, six endpoint declarations, six `groups: ["jira-administrators"]` declarations, the missing-user rejection, disabled writer default, `SET_PARENT_LINK`, and no embedded probe script.

- [ ] **Step 3: Run the tests and verify the expected failure**

  Run: `node --test tools/tests/structure-doctor-bundle.tests.mjs`

  Expected: FAIL because `tools/build-structure-doctor-bundle.mjs` and the committed distribution artifact do not exist.

- [ ] **Step 4: Commit the failing contract locally**

  Run: `git add tools/tests/structure-doctor-bundle.tests.mjs docs/superpowers/plans/2026-09-19-structure-doctor-single-file-distribution.md`

  Run: `git commit -m "OP-1371 test single-file Doctor distribution [skip ci]"`

### Task 2: Implement the deterministic generator

**Files:**
- Create: `tools/build-structure-doctor-bundle.mjs`
- Test: `tools/tests/structure-doctor-bundle.tests.mjs`

- [ ] **Step 1: Implement fixed source discovery**

  Resolve repository paths from `import.meta.url`; accept only optional `--output <path>` and `--check`; require `jira/structureIssueDoctor.groovy` and exactly 33 regular `.groovy` files directly below `jira/structuredoctor`; sort modules by filename using ordinal comparison.

- [ ] **Step 2: Implement normalization and validation**

  Normalize CRLF and CR to LF, require non-empty sources, require every module to contain exactly `package structuredoctor`, reject any controller package declaration, extract only single-line `import` and `import static` declarations, and reject residual package/import declarations after stripping.

- [ ] **Step 3: Implement traceable rendering**

  Emit a generated-file warning, manifest lines containing every repository-relative input path exactly once, one combined SHA-256 over each normalized `relativePath + NUL + content + NUL` pair, one `package structuredoctor`, sorted unique imports, then every stripped module body in lexical order followed by the stripped controller body. Precede every body with `// SOURCE: <relative path>`.

- [ ] **Step 4: Implement safe output and check mode**

  Build the complete byte buffer before touching the destination. In write mode, create the parent directory, write a sibling temporary file with exclusive creation, and rename it over the destination. In `--check` mode, compare bytes and fail without writing when the artifact is absent or stale.

- [ ] **Step 5: Run the focused tests**

  Run: `node --test tools/tests/structure-doctor-bundle.tests.mjs`

  Expected: PASS.

- [ ] **Step 6: Commit the generator locally**

  Run: `git add tools/build-structure-doctor-bundle.mjs tools/tests/structure-doctor-bundle.tests.mjs`

  Run: `git commit -m "OP-1371 build deterministic Doctor bundle [skip ci]"`

### Task 3: Generate and verify the deployable Groovy

**Files:**
- Create: `dist/structuredoctor/structureIssueDoctor.groovy`
- Modify: `README.md`

- [ ] **Step 1: Generate the artifact**

  Run: `node tools/build-structure-doctor-bundle.mjs`

  Expected: `dist/structuredoctor/structureIssueDoctor.groovy` is written from all 34 inputs.

- [ ] **Step 2: Prove the committed artifact is current**

  Run: `node tools/build-structure-doctor-bundle.mjs --check`

  Expected: exit 0 and a current-artifact receipt.

- [ ] **Step 3: Run bundle and existing Doctor tests locally**

  Run: `node --test tools/tests/structure-doctor-bundle.tests.mjs`

  Run the repository's existing local Structure Doctor Groovy test commands from `jira/tests/README.md`, plus both probe test scripts. GitHub Actions are not run or dispatched.

- [ ] **Step 4: Document one-file installation**

  Add a README section pointing administrators to `dist/structuredoctor/structureIssueDoctor.groovy`, state that it is generated and must not be edited, require `structuredoctor/structureIssueDoctor.groovy` below a ScriptRunner Script Root, and state that no modular source or probe file is installed.

- [ ] **Step 5: Verify scope and commit locally**

  Run: `git diff --check`

  Run: `git status --short`

  Expected: only the generator, generator tests, generated artifact, plan, and README are changed or newly tracked.

  Run: `git add dist/structuredoctor/structureIssueDoctor.groovy README.md`

  Run: `git commit -m "OP-1371 distribute Doctor as one Groovy [skip ci]"`

### Task 4: Final acceptance

**Files:**
- Verify: all files changed by Tasks 1-3

- [ ] **Step 1: Re-run all local verification from a clean worktree**

  Run the generator check, focused Node tests, existing modular Groovy tests, security scans, `git diff --check`, and `git status --short`.

- [ ] **Step 2: Inspect the actual target artifact**

  Read the generated artifact's byte size, line count, embedded input count, fingerprint, endpoint count, administrator-group count, and final newline from `dist/structuredoctor/structureIssueDoctor.groovy`.

- [ ] **Step 3: Record C1-C4**

  Record requested behavior, test evidence, unchanged security/privacy boundaries, and final scope integrity in OP-1371 through the Jira service-account broker. Do not transition or push until the implementation has passed and the Director's requested integration step is clear.
