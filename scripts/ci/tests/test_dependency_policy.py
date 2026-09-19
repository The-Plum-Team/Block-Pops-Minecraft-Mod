from __future__ import annotations

import copy
import subprocess
import tempfile
import unittest
from unittest import mock
from pathlib import Path
from xml.etree import ElementTree

from scripts.ci.dependency_policy import (
    DependencyPolicyError,
    REMOTE_EXCLUSIONS,
    PLUGIN_ONLY_EXCLUSIONS,
    XML_DECLARATION,
    _generated_component,
    validate_build_script_text,
    validate_metadata,
    validate_no_tracked_gradle_cache,
    validate_plugin_management_text,
    validate_repository_layout,
    validate_repository_policy_text,
    validate_settings_text,
)
from scripts.ci.gate_controller import FORBIDDEN_PATHS, PROTECTED_PATHS


REPO = Path(__file__).resolve().parents[3]
METADATA = REPO / "gradle" / "verification-metadata.xml"
POLICY = REPO / "gradle" / "repository-policy.gradle"
NAMESPACE = "https://schema.gradle.org/dependency-verification"
TAG = f"{{{NAMESPACE}}}"


class DependencyVerificationPolicyTests(unittest.TestCase):
    def test_current_metadata_has_only_reviewed_generated_exceptions(self) -> None:
        validate_metadata(METADATA)
        validate_repository_layout(REPO)
        root = ElementTree.parse(METADATA).getroot()
        trusted = root.findall(f"{TAG}configuration/{TAG}trusted-artifacts/{TAG}trust")
        generated = [
            (component.attrib["group"], component.attrib["name"])
            for component in root.findall(f"{TAG}components/{TAG}component")
            if _generated_component(
                component.attrib["group"], component.attrib["name"]
            )
        ]
        self.assertEqual(5, len(trusted))
        self.assertEqual([], generated)

    def test_generated_component_patterns_accept_only_exact_loom_outputs(self) -> None:
        accepted = (
            ("loom", "mappings"),
            ("net.minecraft", "minecraft-merged-deadbeef00"),
            ("net.minecraft", "forge-1.20.1-47.4.9-minecraft-merged"),
            ("net.minecraft", "neoforge-21.1.77-minecraft-merged-deobf"),
            ("net.minecraftforge." + "a" * 64, "fmlloader"),
            ("net.neoforged.fancymodloader." + "a" * 64, "loader"),
            ("remapped.software.bernie.geckolib", "geckolib-fabric"),
        )
        rejected = (
            ("loom.evil", "mappings"),
            ("loom", "other"),
            ("net.minecraft", "minecraft"),
            ("net.minecraft", "minecraft-merged-deadbeef0g"),
            ("net.minecraftforge." + "a" * 63, "fmlloader"),
            ("net.minecraftforge." + "a" * 64, "forge"),
            ("net.neoforged.fancymodloader", "loader"),
            ("net.neoforged.fancymodloader." + "a" * 63, "loader"),
            ("net.neoforged.fancymodloader." + "g" * 64, "loader"),
            ("net.neoforged.fancymodloader." + "a" * 64, "earlydisplay"),
            ("unremapped.software.bernie", "geckolib"),
        )
        for group, name in accepted:
            self.assertTrue(_generated_component(group, name), (group, name))
        for group, name in rejected:
            self.assertFalse(_generated_component(group, name), (group, name))

    def test_trust_broadening_and_flag_mutations_fail_closed(self) -> None:
        tree = ElementTree.parse(METADATA)
        root = tree.getroot()
        configuration = root.find(f"{TAG}configuration")
        self.assertIsNotNone(configuration)
        assert configuration is not None
        trusted = configuration.find(f"{TAG}trusted-artifacts")
        self.assertIsNotNone(trusted)
        assert trusted is not None

        mutations = []
        neoforge_trust = next(
            trust
            for trust in trusted
            if trust.attrib.get("name") == "^loader$"
        )
        exact_neoforge_group = neoforge_trust.attrib["group"]
        neoforge_trust.set("group", "^net[.]neoforged[.]fancymodloader[.].*$")
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))
        neoforge_trust.set("group", exact_neoforge_group)

        trust = list(trusted)[0]
        trust.set("group", ".*")
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))
        trust.set("group", "^loom$")
        ElementTree.SubElement(
            trusted,
            f"{TAG}trust",
            {"group": ".*", "regex": "true", "reason": "broad"},
        )
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))
        trusted.remove(list(trusted)[-1])
        configuration.find(f"{TAG}verify-metadata").text = "false"  # type: ignore[union-attr]
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))

        configuration.find(f"{TAG}verify-metadata").text = "true"  # type: ignore[union-attr]
        components = root.find(f"{TAG}components")
        self.assertIsNotNone(components)
        assert components is not None
        ElementTree.SubElement(
            components,
            f"{TAG}component",
            {"group": "loom", "name": "mappings", "version": "generated"},
        )
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))

        with tempfile.TemporaryDirectory() as raw:
            candidate = Path(raw) / "verification-metadata.xml"
            for payload in mutations:
                with self.subTest(payload=payload[:120]):
                    candidate.write_bytes(payload)
                    with self.assertRaises(DependencyPolicyError):
                        validate_metadata(candidate)

    def test_metadata_rejects_entities_symlinks_and_oversize_files(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            entity = root / "entity.xml"
            entity.write_bytes(
                b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY y "z">]><x>&y;</x>'
            )
            with self.assertRaises(DependencyPolicyError):
                validate_metadata(entity)

            link = root / "link.xml"
            link.symlink_to(METADATA)
            with self.assertRaises(DependencyPolicyError):
                validate_metadata(link)

            oversized = root / "oversized.xml"
            oversized.write_bytes(b"x" * (8 * 1024 * 1024 + 1))
            with self.assertRaises(DependencyPolicyError):
                validate_metadata(oversized)

            utf16 = root / "utf16.xml"
            utf16.write_bytes(METADATA.read_text("utf-8").encode("utf-16"))
            with self.assertRaises(DependencyPolicyError):
                validate_metadata(utf16)

            utf16_entity = root / "utf16-entity.xml"
            utf16_entity.write_bytes(
                METADATA.read_text("utf-8")
                .replace(
                    '<?xml version="1.0" encoding="UTF-8"?>',
                    '<?xml version="1.0" encoding="UTF-16"?>\n<!DOCTYPE verification-metadata [<!ENTITY x "y">]>',
                    1,
                )
                .encode("utf-16")
            )
            with self.assertRaises(DependencyPolicyError):
                validate_metadata(utf16_entity)

    def test_remote_components_require_one_exact_sha256_per_artifact(self) -> None:
        tree = ElementTree.parse(METADATA)
        root = tree.getroot()
        checksum = root.find(f"{TAG}components/{TAG}component/{TAG}artifact/{TAG}sha256")
        self.assertIsNotNone(checksum)
        assert checksum is not None
        original_tag = checksum.tag
        original_value = checksum.attrib["value"]

        mutations = []
        checksum.tag = f"{TAG}sha1"
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))
        checksum.tag = original_tag
        checksum.attrib["value"] = original_value[:40]
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))
        checksum.attrib["value"] = original_value
        ElementTree.SubElement(checksum, f"{TAG}md5", {"value": "0" * 32})
        mutations.append(ElementTree.tostring(root, encoding="utf-8", xml_declaration=True))

        with tempfile.TemporaryDirectory() as raw:
            candidate = Path(raw) / "verification-metadata.xml"
            for payload in mutations:
                candidate.write_bytes(payload)
                with self.assertRaises(DependencyPolicyError):
                    validate_metadata(candidate)

    def test_lwjgl_native_metadata_covers_the_linux_ci_runtime(self) -> None:
        root = ElementTree.parse(METADATA).getroot()
        components = root.find(f"{TAG}components")
        self.assertIsNotNone(components)
        assert components is not None
        checked = 0
        for component in components:
            if component.attrib.get("group") != "org.lwjgl":
                continue
            artifacts = {artifact.attrib["name"] for artifact in component}
            for artifact in artifacts:
                suffix = "-natives-macos.jar"
                if not artifact.endswith(suffix):
                    continue
                linux_artifact = artifact[: -len(suffix)] + "-natives-linux.jar"
                self.assertIn(linux_artifact, artifacts, component.attrib)
                checked += 1
        self.assertGreater(checked, 0)

    def test_remote_repository_policy_excludes_every_trusted_namespace(self) -> None:
        policy = POLICY.read_text("utf-8")
        before_hosts, separator, after_hosts = policy.partition("switch (repositoryIdentity)")
        self.assertTrue(separator)
        self.assertLess(before_hosts.index("repositoryScheme == 'file'"), before_hosts.index("repositoryScheme != 'https'"))
        for exclusion in (
            "excludeGroup('loom')",
            "excludeGroup('net.minecraft')",
            "excludeGroupByRegex('net\\\\.minecraftforge\\\\.[0-9a-f]{64}')",
            "excludeGroupByRegex('net\\\\.neoforged\\\\.fancymodloader\\\\.[0-9a-f]{64}')",
            "excludeGroupByRegex('remapped\\\\..+')",
        ):
            self.assertEqual(before_hosts.count(exclusion), 1, exclusion)
        for identity in (
            "maven.architectury.dev/",
            "maven.fabricmc.net/",
            "libraries.minecraft.net/",
            "maven.minecraftforge.net/",
            "maven.neoforged.net/releases",
            "repo.spongepowered.org/repository/maven-public",
            "dl.cloudsmith.io/public/geckolib3/geckolib/maven",
            "repo.maven.apache.org/maven2",
        ):
            self.assertEqual(after_hosts.count(f"case '{identity}':"), 1, identity)
        self.assertIn("default:", after_hosts)
        self.assertIn("Unapproved remote dependency repository", after_hosts)
        self.assertIn("repositories.configureEach", policy)
        self.assertIn("!(repository instanceof MavenArtifactRepository)", policy)
        self.assertIn("Unapproved non-Maven dependency repository", policy)
        for local_name in (
            "LoomGlobalMinecraft",
            "LoomLocalMinecraft",
            "LoomLocalRemappedMods",
            "LoomTransformedForgeDependencies",
        ):
            self.assertEqual(policy.count(f"'{local_name}'"), 1)
        for exact_path in (
            "caches/fabric-loom",
            ".gradle/loom-cache",
            "minecraftMaven",
            "remapped_mods",
            "forge/transformed-dependencies-v1",
        ):
            self.assertIn(exact_path, policy)
        for exact_owner in (
            "includeGroupByRegex('org\\\\.lwjgl(\\\\..*)?')",
            "includeGroupByRegex('cpw\\\\.mods(\\\\..*)?')",
            "excludeGroupByRegex('org\\\\.lwjgl(\\\\..*)?')",
            "excludeGroupByRegex('cpw\\\\.mods(\\\\..*)?')",
        ):
            self.assertEqual(policy.count(exact_owner), 1, exact_owner)
        self.assertEqual(
            policy.count(
                "def useNeoForgeRepositoryOrigins = "
                "rootProject.forge_family_loader == 'neoforge'"
            ),
            1,
        )
        self.assertEqual(policy.count("if (useNeoForgeRepositoryOrigins)"), 3)
        self.assertNotIn("includeGroupByRegex('org\\\\.lwjgl.*')", policy)
        self.assertNotIn("includeGroupByRegex('cpw\\\\.mods.*')", policy)
        self.assertIn("actualLocalPath != expectedLocalPath", policy)
        self.assertIn("Unapproved local Maven repository", policy)
        self.assertNotIn("mavenLocal()", policy)

    def test_repository_policy_mutations_fail_closed(self) -> None:
        policy = POLICY.read_text("utf-8")
        mutations = [policy.replace(exclusion, "", 1) for exclusion in REMOTE_EXCLUSIONS]
        mutations.extend(
            (
                policy.replace(
                    "includeGroupByRegex('cpw\\\\.mods(\\\\..*)?')",
                    "includeGroupByRegex('cpw\\\\.mods.*')",
                    1,
                ),
                policy.replace(
                    "excludeGroupByRegex('cpw\\\\.mods(\\\\..*)?')",
                    "",
                    1,
                ),
                policy.replace(
                    "includeGroupByRegex('org\\\\.lwjgl(\\\\..*)?')",
                    "includeGroupByRegex('org\\\\.lwjgl.*')",
                    1,
                ),
                policy.replace(
                    "excludeGroupByRegex('org\\\\.lwjgl(\\\\..*)?')",
                    "",
                    1,
                ),
                policy.replace(
                    "rootProject.forge_family_loader == 'neoforge'",
                    "true",
                    1,
                ),
                policy.replace(
                    "case 'repo.maven.apache.org/maven2':",
                    "case 'evil.example/repository':\n        case 'repo.maven.apache.org/maven2':",
                    1,
                ),
                policy.replace("'LoomGlobalMinecraft':", "'MavenLocal':", 1),
                policy.replace("'caches/fabric-loom'", "'caches/attacker'", 1),
                policy + "\nrepositories { mavenLocal() }\n",
                policy.replace("repository.url.query != null", "false", 1),
                policy.replace(
                    "repositories.withType(MavenArtifactRepository).configureEach { repository ->",
                    "repositories.withType(MavenArtifactRepository).configureEach { repository ->\n    if (true) { return }",
                    1,
                ),
            )
        )
        for candidate in mutations:
            with self.subTest(candidate=candidate[-100:]):
                self.assertNotEqual(candidate, policy)
                with self.assertRaises(DependencyPolicyError):
                    validate_repository_policy_text(candidate)

    def test_plugin_management_filter_broadening_fails_closed(self) -> None:
        settings = (REPO / "settings.gradle").read_text("utf-8")
        plugin_management = settings.split("plugins {", 1)[0]
        validate_settings_text(settings)
        validate_plugin_management_text(plugin_management)
        broadened = plugin_management.replace(
            "includeGroupByRegex('net\\\\.fabricmc(\\\\..*)?')",
            "includeGroupByRegex('.*')",
            1,
        )
        with self.assertRaises(DependencyPolicyError):
            validate_plugin_management_text(broadened)
        alternate_plane = settings + (
            "\ndependencyResolutionManagement { repositories { "
            "maven { url = 'https://evil.example/maven/' } } }\n"
        )
        with self.assertRaises(DependencyPolicyError):
            validate_settings_text(alternate_plane)

    def test_buildscript_and_implicit_build_planes_fail_closed(self) -> None:
        build = (REPO / "build.gradle").read_text("utf-8")
        validate_build_script_text("build.gradle", build)
        injected = "buildscript { repositories { mavenLocal() } }\n" + build
        with self.assertRaises(DependencyPolicyError):
            validate_build_script_text("build.gradle", injected)

    def test_settings_source_validation_cannot_be_disabled_or_detached_from_context(self) -> None:
        settings = (REPO / "settings.gradle").read_text("utf-8")
        for before, after in (
            ("matrixCommand.findAll { it != '--no-source-check' }", "matrixCommand"),
            ("if (!Arrays.equals(matrixBytes, checkedBytes))", "if (false)"),
            ("if (releaseMatrix.schema_version == 2)", "if (false)"),
        ):
            with self.subTest(before=before):
                candidate = settings.replace(before, after, 1)
                self.assertNotEqual(candidate, settings)
                with self.assertRaises(DependencyPolicyError):
                    validate_settings_text(candidate)

    def test_git_tracked_gradle_cache_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repository = Path(raw)
            subprocess.run(
                ["git", "init", "--quiet", str(repository)],
                check=True,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            validate_no_tracked_gradle_cache(repository)
            payload = repository / ".gradle/loom-cache/minecraftMaven/evil.jar"
            payload.parent.mkdir(parents=True)
            payload.write_bytes(b"not a generated Loom artifact")
            subprocess.run(
                ["git", "-C", str(repository), "add", "--force", "--", ".gradle"],
                check=True,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            with self.assertRaises(DependencyPolicyError):
                validate_no_tracked_gradle_cache(repository)

    def test_plugin_repositories_are_include_filtered_and_portal_rejects_generated_groups(self) -> None:
        settings = (REPO / "settings.gradle").read_text("utf-8")
        plugin_management = settings.split("plugins {", 1)[0]
        self.assertEqual(plugin_management.count("mavenContent { releasesOnly() }"), 7)
        self.assertEqual(plugin_management.count("content {"), 8)
        expected_exclusions = {
            "excludeGroup('loom')": 1,
            "excludeGroup('net.minecraft')": 1,
            "excludeGroupByRegex('net\\\\.minecraftforge\\\\.[0-9a-f]{64}')": 2,
            "excludeGroupByRegex('net\\\\.neoforged\\\\.fancymodloader\\\\.[0-9a-f]{64}')": 2,
            "excludeGroupByRegex('remapped\\\\..+')": 1,
        }
        for exclusion, count in expected_exclusions.items():
            self.assertEqual(plugin_management.count(exclusion), count, exclusion)
        for forbidden in ("mavenLocal(", "flatDir {", "ivy {", "google()"):
            self.assertNotIn(forbidden, plugin_management)

    def test_stonecutter_origin_is_limited_to_two_plugin_modules(self) -> None:
        settings = (REPO / "settings.gradle").read_text("utf-8")
        policy = POLICY.read_text("utf-8")
        self.assertIn("id 'dev.kikugie.stonecutter' version '0.7.11' apply false", settings)
        for exclusion in PLUGIN_ONLY_EXCLUSIONS:
            inclusion = exclusion.replace("excludeModule", "includeModule", 1)
            self.assertEqual(settings.count(inclusion), 1)
            self.assertEqual(settings.count(exclusion), 1)
            self.assertEqual(policy.count(exclusion), 1)
            with self.assertRaises(DependencyPolicyError):
                validate_settings_text(settings.replace(exclusion, "", 1))
            with self.assertRaises(DependencyPolicyError):
                validate_repository_policy_text(policy.replace(exclusion, "", 1))
            with self.assertRaises(DependencyPolicyError):
                validate_settings_text(settings.replace(inclusion, "includeGroup('dev.kikugie')", 1))
        with self.assertRaises(DependencyPolicyError):
            validate_settings_text(settings.replace("maven.kikugie.dev/releases", "maven.kikugie.dev/snapshots"))

    def test_stonecutter_closure_rejects_unreviewed_bytes_and_scope(self) -> None:
        original = ElementTree.parse(METADATA).getroot()
        with tempfile.TemporaryDirectory() as raw:
            candidate = Path(raw) / "verification-metadata.xml"
            for mutation in (
                "unchanged", "missing component", "extra component", "version", "group",
                "missing artifact", "extra artifact", "digest", "alternate digest", "origin",
            ):
                root = copy.deepcopy(original)
                components = root.find(f"{TAG}components")
                component = next(item for item in components if item.attrib["group"] == "dev.kikugie")
                checksum = component[0][0]
                if mutation == "missing component":
                    components.remove(component)
                elif mutation == "extra component":
                    extra = copy.deepcopy(component)
                    extra.set("name", "unreviewed-plugin")
                    components.append(extra)
                elif mutation == "version":
                    component.set("version", "0.9.8")
                elif mutation == "group":
                    component.set("group", "dev.kikugie.extra")
                elif mutation == "missing artifact":
                    component.remove(component[0])
                elif mutation == "extra artifact":
                    extra = copy.deepcopy(component[0])
                    extra.set("name", "stonecutter-0.7.11-sources.jar")
                    component.append(extra)
                elif mutation == "digest":
                    checksum.set("value", "0" * 64)
                elif mutation == "alternate digest":
                    ElementTree.SubElement(checksum, f"{TAG}also-trust", {"value": "0" * 64})
                elif mutation == "origin":
                    checksum.set("origin", "Generated by Gradle")
                candidate.write_bytes(XML_DECLARATION + ElementTree.tostring(root, encoding="utf-8"))
                with self.subTest(mutation=mutation):
                    if mutation == "unchanged":
                        validate_metadata(candidate)
                    else:
                        with self.assertRaisesRegex(DependencyPolicyError, "Stonecutter|Kikugie|SHA-256"):
                            validate_metadata(candidate)

    def test_policy_is_protected_applied_and_validated_before_gradle(self) -> None:
        self.assertIn(".gradle", FORBIDDEN_PATHS)
        self.assertIn("buildSrc", FORBIDDEN_PATHS)
        self.assertIn("gradle/repository-policy.gradle", PROTECTED_PATHS)
        codeowners = (REPO / ".github" / "CODEOWNERS").read_text("utf-8")
        self.assertIn("/gradle/repository-policy.gradle @AkaNebur", codeowners)
        self.assertIn("/gradle/verification-metadata.xml @AkaNebur", codeowners)
        self.assertIn("/.gradle/ @AkaNebur", codeowners)
        self.assertIn("/buildSrc/ @AkaNebur", codeowners)
        build_script = (REPO / "build.gradle").read_text("utf-8")
        self.assertEqual(build_script.count("apply from: rootProject.file('gradle/build-conventions.gradle')"), 1)
        conventions = (REPO / "gradle/build-conventions.gradle").read_text("utf-8")
        self.assertEqual(
            conventions.count("apply from: rootProject.file('gradle/repository-policy.gradle')"),
            1,
        )
        properties = (REPO / "gradle.properties").read_text("utf-8").splitlines()
        self.assertEqual(properties.count("org.gradle.dependency.verification=strict"), 1)
        self.assertEqual(
            properties.count("org.gradle.dependency.verification.console=verbose"), 1
        )
        loader_build_files = tuple(
            REPO / loader / "build.gradle"
            for loader in ("fabric", "forge", "neoforge")
            if (REPO / loader / "build.gradle").is_file()
        )
        for build_file in (
            REPO / "gradle/build-conventions.gradle",
            REPO / "common" / "build.gradle",
            *loader_build_files,
        ):
            with self.subTest(build_file=build_file):
                text = build_file.read_text("utf-8")
                self.assertNotIn('software\\\\.bernie.*', text)
                self.assertIn('software\\\\.bernie(\\\\..*)?', text)
        for workflow_name in (
            "build-gate.yml",
            "on-demand-e2e.yml",
            "verify-gate-attestation.yml",
        ):
            workflow = (REPO / ".github" / "workflows" / workflow_name).read_text("utf-8")
            with self.subTest(workflow=workflow_name):
                self.assertIn("scripts/ci/dependency_policy.py", workflow)
                if "./gradlew" in workflow:
                    self.assertLess(
                        workflow.index("scripts/ci/dependency_policy.py"),
                        workflow.index("./gradlew"),
                    )
                self.assertNotRegex(
                    workflow,
                    r"--dependency-verification(?:=|\s+)(?:off|lenient)",
                )

    def test_missing_or_duplicate_convention_bindings_fail_closed(self) -> None:
        for relative, target in (("build.gradle", "build-conventions"),
                                 ("stonecutter.gradle", "build-conventions"),
                                 ("gradle/build-conventions.gradle", "repository-policy")):
            binding = f"apply from: rootProject.file('gradle/{target}.gradle')"
            for replacement in ("", binding + "\n" + binding):
                def read(path):
                    text = path.read_text("utf-8")
                    return text.replace(binding, replacement, 1) if path == REPO / relative else text
                with self.subTest(relative=relative, replacement=replacement), mock.patch(
                    "scripts.ci.dependency_policy._text", side_effect=read
                ), self.assertRaisesRegex(DependencyPolicyError, "binding is not exact"):
                    validate_repository_layout(REPO)

    def test_gradle_matrix_validation_cannot_skip_or_project_inventory(self) -> None:
        conventions = REPO / "gradle/build-conventions.gradle"
        for replacement in ("", ", '--kind', 'artifacts'", ", '--kind', 'gradle-context'"):
            def read_candidate(path):
                text = path.read_text("utf-8")
                return text.replace(", '--kind', 'inventory'", replacement) if path == conventions else text
            with self.subTest(replacement=replacement), mock.patch(
                "scripts.ci.dependency_policy._text", side_effect=read_candidate,
            ), self.assertRaisesRegex(DependencyPolicyError, "complete normalized inventory"):
                validate_repository_layout(REPO)


if __name__ == "__main__":
    unittest.main()
