#!/usr/bin/env python3
"""Check repository language and capability documentation contracts."""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


class ProjectContractTests(unittest.TestCase):
    def test_project_text_is_english(self):
        names = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=ROOT,
        ).decode().split("\0")
        hits = []
        for name in names:
            path = ROOT / name
            if not name or not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for number, line in enumerate(text.splitlines(), 1):
                if CJK.search(line):
                    hits.append(f"{name}:{number}")
        self.assertFalse(hits, f"{len(hits)} CJK lines remain; first locations: " + ", ".join(hits[:20]))

    def test_capabilities_documents_every_registered_tool(self):
        tools = ROOT / "tools/src/main/java/com/mineagent/tools"
        registration = (tools / "ToolRegistration.java").read_text()
        classes = re.findall(r"ToolRegistry.register\(new (\w+)\(\)\)", registration)
        names = {}
        for path in tools.rglob("*Tool.java"):
            match = re.search(r'String name\(\)\s*\{\s*return "([^"]+)"', path.read_text())
            if match:
                names[path.stem] = match.group(1)
        document = ROOT / "CAPABILITIES.md"
        self.assertTrue(document.is_file(), "CAPABILITIES.md must exist")
        text = document.read_text()
        for name in classes:
            with self.subTest(tool=name):
                self.assertIn(f"`{names[name]}`", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
