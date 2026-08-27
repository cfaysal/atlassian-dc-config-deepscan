#!/usr/bin/env python3
"""Assert that the table renderer has not drifted between the shipped endpoints.

Both endpoints must stay single, self-contained files: each one is dropped into a
ScriptRunner script root on its own. That rules out the usual answer, a shared
module, so the renderer is duplicated on purpose. What was missing is the guard
that keeps the duplication honest.

Compared is executable code only. Comments are deliberately excluded: they may and
should differ per product - the Jira file names request types as its example, the
Confluence file does not - and a check that forbade that would produce worse
comments, not better code.

Differences are allowed only when they appear in ALLOWED below, each with a reason.
Anything else fails the run and prints the diff.
"""

import io
import os
import re
import sys
import difflib

ENDPOINTS = [
    ("jira", "jira/jiraDCprojectConfig.groovy"),
    ("confluence", "confluence/confluenceDCspaceConfig.groovy"),
]

# The functions that together render the table view. A function added to the
# renderer belongs in this list, or it is outside the guard without anyone saying so.
SHARED = [
    "shouldSplit",
    "emitTables",
    "recordOpen",
    "tableHeading",
    "subTable",
    "levelHeader",
    "collectRows",
    "humanKind",
    "camelWords",
]

# Intentional differences, each visible and reasoned. A pair is (jira line,
# other line). Keep this list short: every entry is a place the two products
# genuinely diverge, and a long list means the guard has stopped guarding.
ALLOWED = [
    (
        'out.append("<th class=\\"col-link\\">In Jira</th>")',
        'out.append("<th class=\\"col-link\\">In Confluence</th>")',
        "the link column names the product it links into",
    ),
]


def strip_comments(lines):
    """Executable lines only, whitespace normalised."""
    out = []
    in_block = False
    for raw in lines:
        line = raw.strip()
        if in_block:
            if "*/" in line:
                in_block = False
            continue
        if line.startswith("/*"):
            if "*/" not in line:
                in_block = True
            continue
        if line.startswith("//") or line.startswith("*"):
            continue
        line = re.sub(r"\s+", " ", line)
        if line:
            out.append(line)
    return out


def extract(path, name):
    """The body of one method, found by signature and closed by brace balance."""
    src = io.open(path, encoding="utf-8").read().split("\n")
    start = None
    for index, line in enumerate(src):
        if re.search(r"(static|private static).*\b%s\(" % re.escape(name), line):
            start = index
            break
    if start is None:
        return None
    depth = 0
    body = []
    for line in src[start:]:
        body.append(line)
        depth += line.count("{") - line.count("}")
        if depth == 0 and len(body) > 1:
            break
    return strip_comments(body)


def allowed_pair(removed, added):
    for left, right, _ in ALLOWED:
        if removed == left and added == right:
            return True
    return False


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    missing = [p for _, p in ENDPOINTS if not os.path.exists(os.path.join(root, p))]
    if missing:
        # Not an error: the second endpoint may simply not exist yet. Silence here
        # would be wrong though - a guard that quietly checks nothing is worse than
        # no guard, because it reads as a passing check.
        print("::notice title=Nothing to compare::missing %s" % ", ".join(missing))
        return 0

    base_name, base_path = ENDPOINTS[0]
    failures = 0
    for other_name, other_path in ENDPOINTS[1:]:
        for func in SHARED:
            left = extract(os.path.join(root, base_path), func)
            right = extract(os.path.join(root, other_path), func)
            if left is None or right is None:
                where = base_name if left is None else other_name
                print("::error::%s() is missing from the %s endpoint. The renderer "
                      "is shared by copy, so a function present in one and absent "
                      "from the other is drift, not a difference." % (func, where))
                failures += 1
                continue

            diff = [
                line for line in difflib.unified_diff(left, right, lineterm="", n=0)
                if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
            ]
            if not diff:
                continue

            removed = [line[1:] for line in diff if line.startswith("-")]
            added = [line[1:] for line in diff if line.startswith("+")]
            unexplained = []
            if len(removed) == len(added):
                for was, now in zip(removed, added):
                    if not allowed_pair(was, now):
                        unexplained.append((was, now))
            else:
                unexplained = [(was, "") for was in removed] + [("", now) for now in added]

            if unexplained:
                failures += 1
                print("::error file=%s::%s() differs from the %s endpoint beyond the "
                      "declared exceptions. A change to the renderer has to reach "
                      "both files." % (other_path, func, base_name))
                for was, now in unexplained:
                    if was:
                        print("    %s only: %s" % (base_name, was))
                    if now:
                        print("    %s only: %s" % (other_name, now))

    if failures:
        print("\n%d renderer function(s) drifted. Either apply the change to both "
              "files, or declare the difference in tools/shared-renderer-drift.py "
              "with a reason." % failures)
        return 1

    print("Renderer identical across %d endpoint(s), %d function(s) compared, "
          "%d declared exception(s)." % (len(ENDPOINTS), len(SHARED), len(ALLOWED)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
