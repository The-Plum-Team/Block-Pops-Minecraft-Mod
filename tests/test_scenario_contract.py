from __future__ import annotations

import copy
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

from e2e.scenario_contract import (
    ScenarioContractError,
    capture_id,
    load_contract,
)


REPOSITORY = Path(__file__).resolve().parents[1]
CONTRACT_PATH = REPOSITORY / "e2e" / "scenario-contract.json"


def _contract_data() -> dict[str, object]:
    return json.loads(CONTRACT_PATH.read_bytes())


class ContractFixture(unittest.TestCase):
    def load_value(self, value: object):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "contract.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            return load_contract(path)


class ScenarioContractTests(ContractFixture):
    def test_canonical_contract_owns_profiles_steps_and_semantic_capture_identity(self) -> None:
        raw = CONTRACT_PATH.read_bytes()
        contract = load_contract(CONTRACT_PATH)

        self.assertEqual(hashlib.sha256(raw).hexdigest(), contract.sha256)
        self.assertEqual(("ui-regression",), contract.scenarios_for_profile("runtime-default"))
        self.assertEqual(
            set(contract.scenarios_for_profile("pr")),
            set(contract.scenarios_for_profile("release")),
        )
        self.assertEqual(("client_a",), contract.expected_roles("ui-regression"))
        self.assertEqual(("client_a",), contract.expected_roles("in-world"))
        self.assertEqual(
            tuple(
                capture_id(scenario, "client_a", step)
                for scenario in ("ui-regression", "in-world")
                for step in contract.expected_capture_steps(scenario, "client_a")
            ),
            contract.capture_ids,
        )
        self.assertTrue(
            all(
                step.assertion_required
                for step in contract.role("ui-regression", "client_a").steps
            )
        )

    def test_supported_visual_probe_schemas_are_indexed_by_step(self) -> None:
        value = _contract_data()
        steps = value["scenarios"][0]["roles"][0]["steps"]
        steps[0]["capture"]["probes"] = [
            {
                "kind": "opaque-stars-background",
                "region": [0.0, 0.0, 0.1, 0.1],
                "maximum_mean_luma": 80.0,
                "bright_luma": 180,
                "maximum_bright_fraction": 0.05,
            },
            {
                "kind": "required-gui-text",
                "label": "Favorite color heading",
                "box": [100, 100, 500, 180],
                "minimum_luma_exclusive": 150,
                "minimum_pixels": 8,
            },
        ]

        contract = self.load_value(value)
        probes = contract.probes_for(
            "ui-regression", "client_a", "favorite_color_prompt"
        )

        self.assertEqual(
            ["opaque-stars-background", "required-gui-text"],
            [probe.kind for probe in probes],
        )
        self.assertTrue(all(probe.step == "favorite_color_prompt" for probe in probes))

    def test_contract_structure_mutations_fail_closed(self) -> None:
        def assertion_optional(value: dict[str, object]) -> None:
            value["scenarios"][0]["roles"][0]["steps"][0]["assertion_required"] = False

        def duplicate_step(value: dict[str, object]) -> None:
            steps = value["scenarios"][0]["roles"][0]["steps"]
            steps.append(copy.deepcopy(steps[0]))

        def unsafe_step(value: dict[str, object]) -> None:
            value["scenarios"][0]["roles"][0]["steps"][0]["id"] = "../capture"

        def comparison_unknown(value: dict[str, object]) -> None:
            value["scenarios"][0]["roles"][0]["comparisons"][0]["second_step"] = "missing"

        def comparison_without_capture(value: dict[str, object]) -> None:
            del value["scenarios"][0]["roles"][0]["steps"][0]["capture"]

        def profile_uncovered(value: dict[str, object]) -> None:
            for scenario in value["scenarios"]:
                scenario["execution_profiles"].remove("release")

        def unknown_field(value: dict[str, object]) -> None:
            value["security_bypass"] = True

        mutations = {
            "assertion optional": assertion_optional,
            "duplicate step": duplicate_step,
            "unsafe step": unsafe_step,
            "unknown comparison step": comparison_unknown,
            "comparison without capture": comparison_without_capture,
            "uncovered execution profile": profile_uncovered,
            "unknown root field": unknown_field,
        }
        for label, mutation in mutations.items():
            value = _contract_data()
            mutation(value)
            with self.subTest(label=label), self.assertRaises(ScenarioContractError):
                self.load_value(value)

    def test_visual_probe_mutations_fail_closed(self) -> None:
        invalid_probes = {
            "unknown kind": {"kind": "run-shell"},
            "empty normalized rectangle": {
                "kind": "opaque-stars-background",
                "region": [0.5, 0.5, 0.5, 0.8],
                "maximum_mean_luma": 80,
                "bright_luma": 180,
                "maximum_bright_fraction": 0.05,
            },
            "text box outside reference image": {
                "kind": "required-gui-text",
                "label": "heading",
                "box": [0, 0, 1601, 20],
                "minimum_luma_exclusive": 150,
                "minimum_pixels": 1,
            },
            "text pixel minimum exceeds box": {
                "kind": "required-gui-text",
                "label": "heading",
                "box": [0, 0, 2, 2],
                "minimum_luma_exclusive": 150,
                "minimum_pixels": 5,
            },
        }
        for label, probe in invalid_probes.items():
            value = _contract_data()
            value["scenarios"][0]["roles"][0]["steps"][0]["capture"]["probes"] = [probe]
            with self.subTest(label=label), self.assertRaises(ScenarioContractError):
                self.load_value(value)

    def test_duplicate_keys_nonfinite_numbers_and_oversized_contracts_are_rejected(self) -> None:
        raw_values = (
            b'{"schema_version":1,"schema_version":1}',
            b'{"schema_version":NaN}',
        )
        for raw in raw_values:
            with tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "contract.json"
                path.write_bytes(raw)
                with self.subTest(raw=raw), self.assertRaises(ScenarioContractError):
                    load_contract(path)

        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "contract.json"
            path.write_bytes(b" " * (1024 * 1024 + 1))
            with self.assertRaisesRegex(ScenarioContractError, "size must be"):
                load_contract(path)

    @unittest.skipUnless(hasattr(os, "symlink"), "symlinks are unavailable")
    def test_contract_path_cannot_be_a_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "real.json"
            target.write_bytes(CONTRACT_PATH.read_bytes())
            link = root / "contract.json"
            link.symlink_to(target)

            with self.assertRaisesRegex(ScenarioContractError, "must not be a symlink"):
                load_contract(link)


if __name__ == "__main__":
    unittest.main()
