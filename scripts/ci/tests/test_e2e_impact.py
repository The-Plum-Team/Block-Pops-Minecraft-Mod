from __future__ import annotations

import unittest

from scripts.ci.e2e_impact import ImpactError, classify, normalize_path


class ImpactTests(unittest.TestCase):
    def test_documentation_is_explained_but_gate_policy_remains_full(self) -> None:
        result = classify(["README.md", "docs/architecture.md"])
        self.assertFalse(result.runtime_required)
        self.assertEqual(result.manifest()["gate_policy"], "always-full")

    def test_agent_guidance_template_metadata_and_pages_wake_are_explained_only(self) -> None:
        paths = [
            "AGENTS.md",
            "docs/ai/PROJECT.md",
            "docs/ai/shared/REPOSITORY.md",
            "docs/ai/shared/PUBLIC-EVIDENCE.md",
            "docs/ai/diagram.svg",
            ".gitattributes",
            ".github/dependabot.yml",
            ".github/workflows/notify-pages.yml",
        ]
        result = classify(paths)
        self.assertFalse(result.runtime_required)
        self.assertEqual((), result.runtime_paths)
        self.assertEqual(result.manifest()["gate_policy"], "always-full")

    def test_mod_base_control_paths_still_require_runtime(self) -> None:
        paths = (
            ".github/workflows/pages.yml",
            ".github/workflows/build-gate.yml",
            "scripts/ci/mod_base_kit.py",
            "scripts/pages/mod_base_adapter.py",
            "site/mod-base.json",
            "docs/aim.txt",
            "AGENTS.md/nested",
        )
        for path in paths:
            with self.subTest(path=path):
                result = classify([path, "docs/ai/shared/REPOSITORY.md"])
                self.assertTrue(result.runtime_required)
                self.assertEqual((path,), result.runtime_paths)

    def test_unknown_and_controller_paths_require_runtime(self) -> None:
        result = classify(
            ["mystery.txt", ".github/workflows/on-demand-e2e.yml"]
        )
        self.assertTrue(result.runtime_required)
        self.assertEqual(
            result.runtime_paths,
            (".github/workflows/on-demand-e2e.yml", "mystery.txt"),
        )

    def test_paths_fail_closed(self) -> None:
        for path in ("", "../README.md", "/README.md", "docs\\x.md", "a//b"):
            with self.subTest(path=path), self.assertRaises(ImpactError):
                normalize_path(path)

    def test_empty_diff_still_requires_the_full_gate(self) -> None:
        result = classify([])
        self.assertTrue(result.runtime_required)
        self.assertEqual(result.manifest()["gate_policy"], "always-full")


if __name__ == "__main__":
    unittest.main()
