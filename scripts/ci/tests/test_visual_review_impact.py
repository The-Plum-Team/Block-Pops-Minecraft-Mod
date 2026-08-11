from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any

from scripts.ci.visual_review_impact import (
    ImpactInputError,
    classify_sync_impact,
    read_bounded_json,
)


REPOSITORY = "AkaNebur/BlockPops"
HEAD_REF = "automation/release-sync/opaque-token"
HEAD_SHA = "a" * 40
BASE_REF = "release/1.21.1"
BASE_SHA = "b" * 40
CANDIDATE_SHA = "c" * 40
CANDIDATE_TREE = "d" * 40
ACTOR = "github-actions[bot]"


def pull() -> dict[str, Any]:
    return {
        "number": 8,
        "state": "open",
        "changed_files": 1,
        "merge_commit_sha": CANDIDATE_SHA,
        "user": {"login": ACTOR, "type": "Bot"},
        "labels": [],
        "head": {
            "ref": HEAD_REF,
            "sha": HEAD_SHA,
            "repo": {"full_name": REPOSITORY},
        },
        "base": {
            "ref": BASE_REF,
            "sha": BASE_SHA,
            "repo": {"full_name": REPOSITORY},
        },
    }


def candidate() -> dict[str, Any]:
    return {
        "sha": CANDIDATE_SHA,
        "tree": {"sha": CANDIDATE_TREE},
        "parents": [{"sha": BASE_SHA}, {"sha": HEAD_SHA}],
    }


def changed(
    filename: str, *, status: str = "modified", previous: str | None = None
) -> dict[str, Any]:
    value: dict[str, Any] = {"filename": filename, "status": status}
    if previous is not None:
        value["previous_filename"] = previous
    return value


def classify(
    files: Any,
    *,
    changed_files: int,
    pull_payload: Any | None = None,
    candidate_payload: Any | None = None,
    controller_authenticated: bool = True,
    expected_label: str | None = None,
):
    selected_pull = pull() if pull_payload is None else pull_payload
    if pull_payload is None:
        selected_pull["changed_files"] = changed_files
    return classify_sync_impact(
        pull=selected_pull,
        candidate=candidate() if candidate_payload is None else candidate_payload,
        files=files,
        changed_files=changed_files,
        repository=REPOSITORY,
        expected_head_ref=HEAD_REF,
        expected_head_sha=HEAD_SHA,
        expected_base_ref=BASE_REF,
        expected_base_sha=BASE_SHA,
        expected_candidate_sha=CANDIDATE_SHA,
        expected_candidate_tree=CANDIDATE_TREE,
        expected_actor=ACTOR,
        expected_label=expected_label,
        controller_authenticated=controller_authenticated,
    )


class VisualReviewImpactTests(unittest.TestCase):
    def test_exact_authenticated_nonvisual_sync_can_skip_visual_review(self) -> None:
        files = [
            changed("scripts/ci/tests/test_visual_review_impact.py", status="added"),
            changed("docs/ai/visual-review.md"),
        ]
        decision = classify(files, changed_files=len(files))
        self.assertEqual(decision.visual_review, "skip")
        self.assertEqual(decision.reason, "authenticated-nonvisual-sync")
        self.assertEqual(decision.manifest()["gate_policy"], "always-full")

    def test_closed_merged_sync_does_not_depend_on_the_deleted_head_branch(self) -> None:
        delivered = pull()
        delivered.update(
            {
                "state": "closed",
                "merged": True,
                "merged_at": "2026-08-11T12:00:00Z",
            }
        )
        decision = classify(
            [changed("docs/ai/visual-review.md")],
            changed_files=1,
            pull_payload=delivered,
        )
        self.assertEqual("skip", decision.visual_review)

        wrong_parent = candidate()
        wrong_parent["parents"][0] = {"sha": "e" * 40}
        decision = classify(
            [changed("docs/ai/visual-review.md")],
            changed_files=1,
            pull_payload=delivered,
            candidate_payload=wrong_parent,
        )
        self.assertEqual("review", decision.visual_review)

    def test_controller_authentication_is_mandatory(self) -> None:
        decision = classify(
            [changed("docs/ai/visual-review.md")],
            changed_files=1,
            controller_authenticated=False,
        )
        self.assertEqual(decision.visual_review, "review")

    def test_fork_or_ref_sha_skew_requires_review(self) -> None:
        mutations = []
        fork = pull()
        fork["head"] = {**fork["head"], "repo": {"full_name": "attacker/fork"}}
        mutations.append(fork)
        wrong_sha = pull()
        wrong_sha["head"] = {**wrong_sha["head"], "sha": "c" * 40}
        mutations.append(wrong_sha)
        wrong_ref = pull()
        wrong_ref["head"] = {**wrong_ref["head"], "ref": "feature/not-sync"}
        mutations.append(wrong_ref)
        for payload in mutations:
            with self.subTest(payload=payload):
                self.assertEqual(
                    classify(
                        [changed("docs/ai/visual-review.md")],
                        changed_files=1,
                        pull_payload=payload,
                    ).visual_review,
                    "review",
                )

    def test_actor_and_candidate_topology_are_exact(self) -> None:
        wrong_actor = pull()
        wrong_actor["user"] = {"login": "attacker", "type": "User"}
        wrong_tree = candidate()
        wrong_tree["tree"] = {"sha": "e" * 40}
        reversed_parents = candidate()
        reversed_parents["parents"] = list(reversed(reversed_parents["parents"]))
        self.assertEqual(
            classify(
                [changed("docs/ai/visual-review.md")],
                changed_files=1,
                pull_payload=wrong_actor,
            ).visual_review,
            "review",
        )
        for payload in (wrong_tree, reversed_parents):
            with self.subTest(payload=payload):
                self.assertEqual(
                    classify(
                        [changed("docs/ai/visual-review.md")],
                        changed_files=1,
                        candidate_payload=payload,
                    ).visual_review,
                    "review",
                )

    def test_optional_automation_label_is_exact_when_configured(self) -> None:
        files = [changed("docs/ai/visual-review.md")]
        self.assertEqual(
            classify(files, changed_files=1, expected_label=None).visual_review,
            "skip",
        )
        self.assertEqual(
            classify(
                files,
                changed_files=1,
                expected_label="automation: release-sync",
            ).visual_review,
            "review",
        )
        labeled = pull()
        labeled["labels"] = [{"name": "automation: release-sync"}]
        self.assertEqual(
            classify(
                files,
                changed_files=1,
                pull_payload=labeled,
                expected_label="automation: release-sync",
            ).visual_review,
            "skip",
        )

    def test_visual_runtime_prompt_and_queue_paths_require_review(self) -> None:
        paths = (
            "common/src/main/java/com/akanebur/blockpops/BlockPops.java",
            "e2e/visual_review_prompt.md",
            "scripts/visual/handoff.py",
            "scripts/ci/visual_review_queue.py",
            "scripts/ci/visual_review_impact.py",
            ".github/workflows/visual-review.yml",
            ".github/claude/package-lock.json",
            "README.md",
        )
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(
                    classify([changed(path)], changed_files=1).visual_review,
                    "review",
                )

    def test_inventory_is_complete_bounded_canonical_and_rename_safe(self) -> None:
        cases = (
            (None, 1),
            ([], 0),
            ([changed("docs/ai/one.md")], 2),
            ([changed("docs/../common/hidden.java")], 1),
            ([changed("docs/ai/one.md", status="unknown")], 1),
            ([changed("docs/ai/one.md"), changed("docs/ai/one.md")], 2),
            ([changed("docs/ai/new.md", status="renamed")], 1),
            (
                [
                    changed(
                        "docs/ai/new.md",
                        status="renamed",
                        previous="common/src/main/java/removed.java",
                    )
                ],
                1,
            ),
        )
        for files, count in cases:
            with self.subTest(files=files, count=count):
                self.assertEqual(
                    classify(files, changed_files=count).visual_review,
                    "review",
                )

    def test_safe_rename_requires_both_exact_paths(self) -> None:
        decision = classify(
            [
                changed(
                    "docs/ai/new.md",
                    status="renamed",
                    previous="docs/ai/old.md",
                )
            ],
            changed_files=1,
        )
        self.assertEqual(decision.visual_review, "skip")

    def test_cli_json_reader_rejects_duplicate_keys_and_nonfinite_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            duplicate = Path(raw) / "duplicate.json"
            duplicate.write_text('{"head":1,"head":2}\n', encoding="utf-8")
            with self.assertRaises(ImpactInputError):
                read_bounded_json(duplicate)
            nonfinite = Path(raw) / "nonfinite.json"
            nonfinite.write_text('{"value":NaN}\n', encoding="utf-8")
            with self.assertRaises(ImpactInputError):
                read_bounded_json(nonfinite)


if __name__ == "__main__":
    unittest.main()
