"""The representative fixture contract stays a faithful, fully bound subset of the real one."""

from __future__ import annotations

import sys
import types
import unittest

import e2e.scenario_contract as scenario_contract
from e2e import orchestrator, packaged_runtime
from e2e.scenario_contract import OpaqueStarsProbe, RequiredGuiTextProbe, load_contract
from tests.contract_fixtures import (
    REAL_CONTRACT_PATH,
    REAL_CONTRACT_SHA256,
    contract_bindings,
    representative_contract,
    representative_contract_path,
)


def _bound_contract_hashes() -> dict[str, str]:
    return {
        "e2e default_contract": scenario_contract.default_contract().sha256,
        "matrix default_contract": sys.modules["scenario_contract"].default_contract().sha256,
        "contract path bytes": load_contract(scenario_contract.DEFAULT_CONTRACT).sha256,
        "packaged runtime": packaged_runtime.SCENARIO_CONTRACT.sha256,
        "orchestrator": orchestrator.CONTRACT.sha256,
    }


class RepresentativeContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.real = load_contract(REAL_CONTRACT_PATH)

    def test_keeps_every_scenario_role_step_and_profile_with_fewer_captures(self) -> None:
        with representative_contract() as contract:
            pass
        self.assertLess(len(contract.capture_ids), len(self.real.capture_ids))
        self.assertEqual(self.real.gui_text_reference_size, contract.gui_text_reference_size)
        self.assertEqual(self.real.scenario_ids, contract.scenario_ids)
        for real in self.real.scenarios:
            subset = contract.scenario(real.scenario)
            self.assertEqual(real.execution_profiles, subset.execution_profiles)
            self.assertEqual(real.orchestration, subset.orchestration)
            self.assertEqual([role.role for role in real.roles], [role.role for role in subset.roles])
            for real_role, role in zip(real.roles, subset.roles):
                self.assertEqual(real_role.step_ids, role.step_ids)
                captures = [step.capture for step in role.steps if step.capture is not None]
                self.assertTrue(captures)
                for capture in captures:
                    self.assertEqual(self.real.capture(real.scenario, role.role, capture.step), capture)
                self.assertEqual(bool(real_role.comparisons), bool(role.comparisons))
                self.assertTrue(set(role.comparisons) <= set(real_role.comparisons))
        kinds = {type(probe) for scenario in contract.scenarios for role in scenario.roles for probe in role.probes}
        self.assertEqual({OpaqueStarsProbe, RequiredGuiTextProbe}, kinds)

    def test_every_pages_binding_moves_together_and_is_restored(self) -> None:
        real = _bound_contract_hashes()
        self.assertEqual({self.real.sha256}, set(real.values()))
        bindings = contract_bindings(REAL_CONTRACT_SHA256, REAL_CONTRACT_PATH)
        # At least both loaders' paths, packaged_runtime's contract and the orchestrator's.
        self.assertGreaterEqual(len(bindings), 4)
        with representative_contract() as contract:
            self.assertEqual({contract.sha256}, set(_bound_contract_hashes().values()))
            self.assertEqual([], contract_bindings(REAL_CONTRACT_SHA256, REAL_CONTRACT_PATH))
            self.assertEqual(
                len(bindings),
                len(contract_bindings(contract.sha256, representative_contract_path())),
            )
            captured = {
                (scenario.scenario, role.role, step.id): step.capture
                for scenario in contract.scenarios
                for role in scenario.roles
                for step in role.steps
                if step.capture is not None
            }
            self.assertTrue(set(packaged_runtime.OPAQUE_STARS_PROBES) <= set(captured))
            self.assertTrue(set(packaged_runtime.REQUIRED_GUI_TEXT_PROBES) <= set(captured))
            for key, capture in captured.items():
                opaque = [probe for probe in capture.probes if isinstance(probe, OpaqueStarsProbe)]
                self.assertEqual(opaque[0] if opaque else None, packaged_runtime.OPAQUE_STARS_PROBES.get(key))
                text = tuple(
                    (probe.label, probe.box, probe.minimum_luma_exclusive, probe.minimum_pixels)
                    for probe in capture.probes
                    if isinstance(probe, RequiredGuiTextProbe)
                )
                self.assertEqual(text or None, packaged_runtime.REQUIRED_GUI_TEXT_PROBES.get(key))
        self.assertEqual(real, _bound_contract_hashes())
        self.assertEqual([], contract_bindings(contract.sha256, representative_contract_path()))
        self.assertEqual(len(bindings), len(contract_bindings(REAL_CONTRACT_SHA256, REAL_CONTRACT_PATH)))

    def test_a_binding_made_inside_the_swap_does_not_leak(self) -> None:
        module = types.ModuleType("scripts.contract_fixture_probe")
        sys.modules[module.__name__] = module
        self.addCleanup(sys.modules.pop, module.__name__)
        with representative_contract():
            module.CONTRACT = scenario_contract.default_contract()
            module.PATH = scenario_contract.DEFAULT_CONTRACT
        self.assertEqual(REAL_CONTRACT_SHA256, module.CONTRACT.sha256)
        self.assertEqual(REAL_CONTRACT_PATH, module.PATH)


if __name__ == "__main__":
    unittest.main()
