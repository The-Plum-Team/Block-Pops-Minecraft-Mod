from __future__ import annotations

import unittest

from scripts.ci.transient_failure import classify_failure


class TransientFailureTests(unittest.TestCase):
    def test_external_capacity_includes_artifact_quota(self) -> None:
        for code in (
            "artifact_quota",
            "actions_quota",
            "provider_rate_limit",
            "runner_capacity",
        ):
            with self.subTest(code=code):
                result = classify_failure(code)
                self.assertEqual(result.category, "external_capacity")
                self.assertTrue(result.retain_queue)
                self.assertTrue(result.retryable)
        detected = classify_failure(
            "unknown", detail="Artifact storage quota has been exceeded"
        )
        self.assertEqual((detected.category, detected.code), ("external_capacity", "artifact_quota"))

    def test_transient_network_is_retryable_and_retained(self) -> None:
        for code in ("connection_reset", "dns_failure", "http_503", "tls_failure"):
            with self.subTest(code=code):
                result = classify_failure(code)
                self.assertEqual(result.category, "transient_network")
                self.assertTrue(result.retain_queue)
                self.assertTrue(result.retryable)

    def test_proven_code_and_security_failures_are_distinct(self) -> None:
        code = classify_failure("test_failure")
        security = classify_failure("digest_mismatch")
        self.assertEqual(code.category, "code_test")
        self.assertEqual(security.category, "security_policy")
        self.assertFalse(code.retain_queue)
        self.assertFalse(security.retain_queue)

    def test_authentication_provider_and_unknown_failures_retain_queue(self) -> None:
        cases = {
            "provider_authentication": "security_policy",
            "credential_missing": "security_policy",
            "provider_error": "unknown",
            "provider_output_invalid": "unknown",
            "made_up_value": "unknown",
        }
        for code, category in cases.items():
            with self.subTest(code=code):
                result = classify_failure(code)
                self.assertEqual(result.category, category)
                self.assertTrue(result.retain_queue)

    def test_manifest_has_no_ai_repair_decision_or_diagnostic_text(self) -> None:
        secret = "do-not-copy-this-provider-diagnostic"
        manifest = classify_failure("unknown", detail=secret).manifest()
        self.assertNotIn("repair", manifest)
        self.assertNotIn("ai", manifest)
        self.assertNotIn("detail", manifest)
        self.assertNotIn(secret, str(manifest))

    def test_oversized_or_malformed_detail_remains_unknown(self) -> None:
        result = classify_failure("not valid", detail="artifact quota " * 500)
        self.assertEqual(result.category, "unknown")
        self.assertEqual(result.code, "unknown")
        self.assertTrue(result.retain_queue)


if __name__ == "__main__":
    unittest.main()
