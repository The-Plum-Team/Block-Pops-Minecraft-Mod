"""Repository guidance follows the pinned mod-base template, including its staged adoption.

``AGENTS.md`` and the other root files a controller upgrade cannot change arrive in an ordinary
follow-up pull request, so they may be absent while ``site/mod-base.json`` lists them in
``template.deferred``. Every present file, and every file that is not deferred, is checked strictly:
by the kit's own ``template check`` through the managed bootstrap, and by the rules below.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path

from scripts.ci import mod_base_kit
from tests import mod_base_path


REPO = Path(__file__).resolve().parents[1]
CONFIG = REPO / "site" / "mod-base.json"
SHARED_IMPORTS = ("docs/ai/shared/REPOSITORY.md", "docs/ai/shared/PUBLIC-EVIDENCE.md")
FORBIDDEN = ("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md")
OWNER = "@AkaNebur"
#: The mod-base CODEOWNERS fragment: CODEOWNERS can never be deferred.
FRAGMENT_PATTERNS = ("/.github/", "/site/", "/scripts/pages/", "/scripts/ci/", "/AGENTS.md", "/docs/ai/")
#: Local Markdown written for the adoption; their relative links must resolve in this checkout.
LINKED_DOCUMENTS = (
    "docs/ai/PROJECT.md",
    "docs/architecture/decisions/README.md",
    "docs/architecture/decisions/0001-adopt-mod-base-public-evidence.md",
    "docs/operations.md",
    "docs/e2e.md",
    "docs/visual-review.md",
    "docs/release-architecture.md",
    "scripts/pages/README.md",
    "README.md",
    "CONTRIBUTING.md",
)
LINK = re.compile(r"\]\(([^)\s]+)\)")


def template() -> dict:
    return json.loads(CONFIG.read_text("utf-8"))["template"]


def owner_rules(text: str) -> dict[str, list[str]]:
    rules: dict[str, list[str]] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        tokens = line.split()
        if not tokens or tokens[0].startswith("#"):
            continue
        owners = []
        for token in tokens[1:]:
            if token.startswith("#"):
                break
            owners.append(token)
        if not owners:
            raise AssertionError(f"CODEOWNERS line {number} removes ownership: {line!r}")
        rules.setdefault(tokens[0], []).extend(owners)
    return rules


class RepositoryGuidanceTests(unittest.TestCase):
    def test_no_claude_instruction_file_shadows_agents_md(self) -> None:
        for path in FORBIDDEN:
            with self.subTest(path=path):
                self.assertFalse(os.path.lexists(REPO / path), f"{path} must not exist")

    def test_agents_md_is_the_import_manifest_once_it_exists(self) -> None:
        settings = template()
        local = settings["agents_local"]
        self.assertTrue(local)
        # The local imports exist before AGENTS.md does, so the follow-up PR only adds the manifest.
        for path in (*SHARED_IMPORTS, *local):
            with self.subTest(imported=path):
                self.assertTrue((REPO / path).is_file())
                self.assertFalse((REPO / path).is_symlink())
        agents = REPO / "AGENTS.md"
        if "AGENTS.md" in settings["deferred"] and not os.path.lexists(agents):
            return
        self.assertTrue(agents.is_file())
        self.assertEqual(
            "".join(f"@{path}\n" for path in (*SHARED_IMPORTS, *local)),
            agents.read_text("utf-8"),
        )

    def test_codeowners_gives_every_template_fragment_pattern_the_owner(self) -> None:
        rules = owner_rules((REPO / ".github" / "CODEOWNERS").read_text("utf-8"))
        for pattern in FRAGMENT_PATTERNS:
            with self.subTest(pattern=pattern):
                self.assertEqual([OWNER], rules.get(pattern))

    def test_an_ownerless_codeowners_rule_is_rejected(self) -> None:
        with self.assertRaisesRegex(AssertionError, "line 2 removes ownership"):
            owner_rules("/AGENTS.md @AkaNebur\n/docs/ai/   # owner-less\n")

    def test_the_pinned_kit_template_check_is_clean(self) -> None:
        # Resolve through the helper first so an unavailable kit fails with its own message.
        mod_base_path.kit_root()
        environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
        result = subprocess.run(
            [sys.executable, "-P", str(REPO / "scripts" / "ci" / "mod_base_kit.py"), "run", "--repo", str(REPO),
             "--", "template", "check", "--repo", str(REPO)],
            cwd=REPO,
            env=environment,
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_bootstrap_is_the_managed_kit_file(self) -> None:
        managed = mod_base_path.kit_root() / "template" / "managed" / "scripts" / "ci" / "mod_base_kit.py"
        self.assertEqual(managed.read_bytes(), (REPO / "scripts" / "ci" / "mod_base_kit.py").read_bytes())
        self.assertEqual(Path(mod_base_kit.__file__).resolve(), (REPO / "scripts" / "ci" / "mod_base_kit.py").resolve())

    def test_local_markdown_links_resolve(self) -> None:
        for document in LINKED_DOCUMENTS:
            path = REPO / document
            text = path.read_text("utf-8")
            for target in LINK.findall(text):
                if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
                    continue
                relative = target.split("#", 1)[0]
                with self.subTest(document=document, target=target):
                    resolved = (path.parent / relative).resolve()
                    self.assertTrue(resolved.is_relative_to(REPO.resolve()), target)
                    self.assertTrue(resolved.exists(), f"{document} links to missing {target}")


if __name__ == "__main__":
    unittest.main()
