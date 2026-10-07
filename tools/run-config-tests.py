#!/usr/bin/env python3
"""Run the same source-derived Jira/Confluence configuration suites as CI."""
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parent.parent
imports = "\n".join([
    "import groovy.json.JsonOutput",
    "import org.codehaus.groovy.runtime.InvokerHelper",
    "import java.time.ZonedDateTime",
    "import java.time.format.DateTimeFormatter",
    "import java.util.regex.Pattern",
    "import java.util.regex.Matcher",
    "import java.lang.reflect.Method",
]) + "\n"

with tempfile.TemporaryDirectory(prefix="deepscan-config-tests-") as temp:
    subprocess.run(["groovy", "-DrepoRoot=" + str(repo), "tools/tests/config-overview-typecheck.tests.groovy"], cwd=repo, check=True)
    for product, scope in [("jira", "project"), ("confluence", "space")]:
        endpoint = f"{product}/{product}DC{scope}Config.groovy"
        source = (repo / endpoint).read_text(encoding="utf-8")
        start = source.index("class Pc {")
        banner = source.index(f" * END OF THE {product.upper()}-FREE BLOCK")
        end = source.rfind("/*", start, banner)
        suite = Path(temp) / f"{product}-suite.groovy"
        suite.write_text(imports + source[start:end] +
                         (repo / product / "tests" / f"{product}DC{scope}Config.tests.groovy").read_text(encoding="utf-8"),
                         encoding="utf-8")
        print(f"Testing {endpoint}", flush=True)
        subprocess.run(["groovy", "tools/config-overview-typecheck.groovy", endpoint], cwd=repo, check=True)
        subprocess.run(["groovy", "-DrepoRoot=" + str(repo), str(suite)], cwd=repo, check=True)
        subprocess.run(["groovy", "tools/parsecheck.groovy", endpoint], cwd=repo, check=True)
