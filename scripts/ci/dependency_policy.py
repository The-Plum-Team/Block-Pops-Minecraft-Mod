#!/usr/bin/env python3
"""Fail-closed validation for BlockPops dependency-verification exceptions."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import stat
import subprocess
from pathlib import Path
from xml.etree import ElementTree

MAX_METADATA_BYTES = 8 * 1024 * 1024
XML_NAMESPACE = "https://schema.gradle.org/dependency-verification"
TAG = f"{{{XML_NAMESPACE}}}"
SCHEMA_LOCATION = {
    "{http://www.w3.org/2001/XMLSchema-instance}schemaLocation": (
        "https://schema.gradle.org/dependency-verification "
        "https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd"
    )
}
EXPECTED_TRUSTS = frozenset(
    {
        frozenset(
            {
                ("group", "^loom$"),
                ("name", "^mappings$"),
                ("regex", "true"),
                (
                    "reason",
                    "Loom-generated layered mappings; every remote repository rejects this namespace",
                ),
            }
        ),
        frozenset(
            {
                ("group", "^net[.]minecraft$"),
                (
                    "name",
                    "^(minecraft-merged-[0-9a-f]{10}|(?:forge|neoforge)-[0-9A-Za-z.+_-]+-minecraft-merged(?:-deobf)?)$",
                ),
                ("regex", "true"),
                (
                    "reason",
                    "Loom-generated merged Minecraft modules; every remote repository rejects these names",
                ),
            }
        ),
        frozenset(
            {
                ("group", "^net[.]minecraftforge[.][0-9a-f]{64}$"),
                ("name", "^fmlloader$"),
                ("regex", "true"),
                (
                    "reason",
                    "Loom-generated transformed Forge module; every remote repository rejects this namespace",
                ),
            }
        ),
        frozenset(
            {
                (
                    "group",
                    "^net[.]neoforged[.]fancymodloader[.][0-9a-f]{64}$",
                ),
                ("name", "^loader$"),
                ("regex", "true"),
                (
                    "reason",
                    "Loom-generated transformed NeoForge loader; every remote repository rejects this namespace",
                ),
            }
        ),
        frozenset(
            {
                ("group", "^remapped[.].+$"),
                ("regex", "true"),
                (
                    "reason",
                    "Loom-generated remapped modules; every remote repository rejects this namespace",
                ),
            }
        ),
    }
)
GENERATED_COMPONENTS = (
    (re.compile(r"loom"), re.compile(r"mappings")),
    (
        re.compile(r"net[.]minecraft"),
        re.compile(
            r"(?:minecraft-merged-[0-9a-f]{10}|(?:forge|neoforge)-[0-9A-Za-z.+_-]+-minecraft-merged(?:-deobf)?)"
        ),
    ),
    (re.compile(r"net[.]minecraftforge[.][0-9a-f]{64}"), re.compile(r"fmlloader")),
    (
        re.compile(r"net[.]neoforged[.]fancymodloader[.][0-9a-f]{64}"),
        re.compile(r"loader"),
    ),
    (re.compile(r"remapped[.].+"), re.compile(r".+")),
)
EXPECTED_REMOTE_IDENTITIES = frozenset(
    {
        "maven.architectury.dev/",
        "maven.fabricmc.net/",
        "libraries.minecraft.net/",
        "maven.minecraftforge.net/",
        "maven.neoforged.net/releases",
        "repo.spongepowered.org/repository/maven-public",
        "dl.cloudsmith.io/public/geckolib3/geckolib/maven",
        "repo.maven.apache.org/maven2",
    }
)
EXPECTED_LOCAL_REPOSITORIES = frozenset(
    {
        "LoomGlobalMinecraft",
        "LoomLocalMinecraft",
        "LoomLocalRemappedMods",
        "LoomTransformedForgeDependencies",
    }
)
REMOTE_EXCLUSIONS = (
    "excludeGroup('loom')",
    "excludeGroup('net.minecraft')",
    "excludeGroupByRegex('net\\\\.minecraftforge\\\\.[0-9a-f]{64}')",
    "excludeGroupByRegex('net\\\\.neoforged\\\\.fancymodloader\\\\.[0-9a-f]{64}')",
    "excludeGroupByRegex('remapped\\\\..+')",
)
STONECUTTER_COMPONENTS = {
    ("dev.kikugie", "stonecutter", "0.7.11"): {
        "stonecutter-0.7.11.jar": "6c4e06b16eb5a89dbc5375a8626511f203b1dbb3e17d84a0f9a1cf617dcd559a",
        "stonecutter-0.7.11.module": "f80adecb5dd27664fc6fecd42dc6a21275ba9e92a844e175ab41d2de0dc15b3e",
        "stonecutter-0.7.11.pom": "90ee51b5d1a54a6947122241aa7c304e5a1f089df85fd204c184737a80bc88a6",
    },
    ("dev.kikugie.stonecutter", "dev.kikugie.stonecutter.gradle.plugin", "0.7.11"): {
        "dev.kikugie.stonecutter.gradle.plugin-0.7.11.pom": "17c78927965542007002094ef130e5ce90d41b6405dd95f9840edf71c042de0d",
    },
}
STONECUTTER_CHECKSUM_ORIGIN = "Reviewed Kikugie release 0.7.11"
PLUGIN_ONLY_EXCLUSIONS = tuple(
    f"excludeModule('{group}', '{name}')"
    for group, name, _ in STONECUTTER_COMPONENTS
)
EXPECTED_POLICY_SHA256 = "f101246914780e6d5fc4ea06e251ee6a584664c530bae489c02c5781b15ea25e"
EXPECTED_PLUGIN_MANAGEMENT_SHA256 = "fb5e6e4641227587e68ce7180f2edbe3be84b5990e858e5b2c80af9b699521f8"
EXPECTED_SETTINGS_SHA256 = "6113ea0201092da775aa7ba009f5c4fc9e112f3c33d495e257259c726b045f6f"
XML_DECLARATION = b'<?xml version="1.0" encoding="UTF-8"?>\n'


class DependencyPolicyError(ValueError):
    """Raised when dependency verification is broader than the reviewed policy."""


def _read_regular(path: Path) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise DependencyPolicyError(f"cannot open dependency metadata: {error}") from error
    try:
        details = os.fstat(descriptor)
        if not stat.S_ISREG(details.st_mode):
            raise DependencyPolicyError("dependency metadata is not a regular file")
        if details.st_size <= 0 or details.st_size > MAX_METADATA_BYTES:
            raise DependencyPolicyError("dependency metadata size is outside the allowed bounds")
        chunks: list[bytes] = []
        remaining = MAX_METADATA_BYTES + 1
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        payload = b"".join(chunks)
        if not payload or len(payload) > MAX_METADATA_BYTES:
            raise DependencyPolicyError("dependency metadata is empty or oversized")
        return payload
    finally:
        os.close(descriptor)


def _text(path: Path) -> str:
    try:
        return _read_regular(path).decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise DependencyPolicyError(f"{path} is not strict UTF-8") from error


def _generated_component(group: str, name: str) -> bool:
    return any(
        group_pattern.fullmatch(group) and name_pattern.fullmatch(name)
        for group_pattern, name_pattern in GENERATED_COMPONENTS
    )


def validate_repository_policy_text(policy: str) -> None:
    if hashlib.sha256(policy.encode("utf-8")).hexdigest() != EXPECTED_POLICY_SHA256:
        raise DependencyPolicyError("repository policy bytes differ from the reviewed controller")
    before_hosts, separator, after_hosts = policy.partition("switch (repositoryIdentity)")
    if not separator:
        raise DependencyPolicyError("repository policy has no identity allowlist")
    if (
        "repositoryScheme == 'file'" not in before_hosts
        or "repositoryScheme != 'https'" not in before_hosts
        or before_hosts.index("repositoryScheme == 'file'")
        > before_hosts.index("repositoryScheme != 'https'")
    ):
        raise DependencyPolicyError("repository scheme policy is not fail closed")
    for exclusion in REMOTE_EXCLUSIONS + PLUGIN_ONLY_EXCLUSIONS:
        if before_hosts.count(exclusion) != 1:
            raise DependencyPolicyError(f"remote exclusion is not exact: {exclusion}")
    identities = frozenset(re.findall(r"case '([^']+)':", after_hosts))
    if identities != EXPECTED_REMOTE_IDENTITIES:
        raise DependencyPolicyError("remote repository identities are not exact")
    local_match = re.search(
        r"def loomLocalRepositories = \[(.*?)\][.]asImmutable[(][)]", policy, re.DOTALL
    )
    if local_match is None:
        raise DependencyPolicyError("Loom local repository allowlist is missing")
    local_names = frozenset(
        re.findall(r"(?m)^\s*'([^']+)':", local_match.group(1))
    )
    if local_names != EXPECTED_LOCAL_REPOSITORIES:
        raise DependencyPolicyError("Loom local repository names are not exact")
    for required in (
        "repositories.configureEach",
        "!(repository instanceof MavenArtifactRepository)",
        "Unapproved non-Maven dependency repository",
        "Unapproved local Maven repository",
        "Unapproved remote dependency repository",
        "repository.url.userInfo != null",
        "repository.url.port != -1",
        "repository.url.query != null",
        "repository.url.fragment != null",
        "default:",
    ):
        if policy.count(required) != 1:
            raise DependencyPolicyError(f"repository guard is not exact: {required}")
    for forbidden in ("mavenLocal(", "flatDir {", "ivy {"):
        if forbidden in policy:
            raise DependencyPolicyError(f"repository policy contains forbidden surface {forbidden}")


def validate_plugin_management_text(plugin_management: str) -> None:
    if (
        hashlib.sha256(plugin_management.encode("utf-8")).hexdigest()
        != EXPECTED_PLUGIN_MANAGEMENT_SHA256
    ):
        raise DependencyPolicyError("pluginManagement bytes differ from the reviewed controller")
    plugin_urls = frozenset(re.findall(r"url\s*=\s*'(https://[^']+)'", plugin_management))
    expected_plugin_urls = frozenset(
        {
            "https://maven.fabricmc.net/",
            "https://maven.architectury.dev/",
            "https://libraries.minecraft.net/",
            "https://maven.minecraftforge.net/",
            "https://maven.neoforged.net/releases/",
            "https://repo.spongepowered.org/repository/maven-public/",
            "https://maven.kikugie.dev/releases",
        }
    )
    if plugin_urls != expected_plugin_urls:
        raise DependencyPolicyError("plugin repository URLs are not exact")
    if (
        plugin_management.count("maven {") != 7
        or plugin_management.count("content {") != 8
        or plugin_management.count("mavenContent { releasesOnly() }") != 7
    ):
        raise DependencyPolicyError("plugin repositories are not release-only")
    expected_plugin_exclusion_counts = (1, 1, 2, 2, 1)
    if tuple(plugin_management.count(value) for value in REMOTE_EXCLUSIONS) != (
        expected_plugin_exclusion_counts
    ):
        raise DependencyPolicyError("plugin portal generated exclusions are not exact")
    for exclusion in PLUGIN_ONLY_EXCLUSIONS:
        inclusion = exclusion.replace("excludeModule", "includeModule", 1)
        if plugin_management.count(exclusion) != 1 or plugin_management.count(inclusion) != 1:
            raise DependencyPolicyError("Stonecutter plugin origins are not exact")
    for forbidden in ("mavenLocal(", "flatDir {", "ivy {", "google()"):
        if forbidden in plugin_management:
            raise DependencyPolicyError(f"settings declare forbidden repository {forbidden}")


def validate_settings_text(settings: str) -> None:
    if hashlib.sha256(settings.encode("utf-8")).hexdigest() != EXPECTED_SETTINGS_SHA256:
        raise DependencyPolicyError("settings.gradle bytes differ from the reviewed controller")
    plugin_management = settings.split("plugins {", 1)[0]
    validate_plugin_management_text(plugin_management)


def validate_build_script_text(label: str, text: str) -> None:
    for forbidden in ("buildscript {", "mavenLocal(", "flatDir {", "ivy {"):
        if forbidden in text:
            raise DependencyPolicyError(f"{label} declares forbidden repository plane {forbidden}")
    if label != "gradle/repository-policy.gradle" and "software\\\\.bernie.*" in text:
        raise DependencyPolicyError(f"{label} has an overbroad GeckoLib group filter")
    if (
        label != "gradle/repository-policy.gradle"
        and "dl.cloudsmith.io/public/geckolib3/geckolib/maven" in text
        and text.count("software\\\\.bernie(\\\\..*)?") != 1
    ):
        raise DependencyPolicyError(f"{label} has no exact GeckoLib group filter")


def validate_no_tracked_gradle_cache(repository: Path) -> None:
    result = subprocess.run(
        ["git", "-C", str(repository), "ls-files", "--error-unmatch", "--", ".gradle"],
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env={
            **os.environ,
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_PAGER": "cat",
            "LC_ALL": "C",
        },
    )
    if result.returncode == 0:
        raise DependencyPolicyError("Git-tracked content under .gradle is not allowed")
    if result.returncode != 1:
        raise DependencyPolicyError("cannot authenticate the Git-tracked .gradle inventory")


def validate_repository_layout(repository: Path) -> None:
    validate_no_tracked_gradle_cache(repository)
    validate_repository_policy_text(_text(repository / "gradle/repository-policy.gradle"))

    settings = _text(repository / "settings.gradle")
    validate_settings_text(settings)

    build = _text(repository / "build.gradle")
    conventions = _text(repository / "gradle/build-conventions.gradle")
    root_binding = "apply from: rootProject.file('gradle/build-conventions.gradle')"
    if build.count(root_binding) != 1:
        raise DependencyPolicyError("root build conventions binding is not exact")
    binding = "apply from: rootProject.file('gradle/repository-policy.gradle')"
    if conventions.count(binding) != 1:
        raise DependencyPolicyError("project repository policy binding is not exact")

    properties = _text(repository / "gradle.properties").splitlines()
    if properties.count("org.gradle.dependency.verification=strict") != 1 or properties.count(
        "org.gradle.dependency.verification.console=verbose"
    ) != 1:
        raise DependencyPolicyError("Gradle dependency verification mode is not explicit")

    if os.path.lexists(repository / "buildSrc"):
        raise DependencyPolicyError("implicit buildSrc builds are not allowed")
    expected_gradle_files = {
        "build.gradle",
        "common/build.gradle",
        "gradle/build-conventions.gradle",
        "gradle/e2e-harness-conventions.gradle",
        "gradle/repository-policy.gradle",
        "settings.gradle",
    }
    expected_gradle_files.update(
        f"{loader}/build.gradle"
        for loader in ("fabric", "forge", "neoforge")
        if (repository / loader / "build.gradle").exists()
    )
    observed_gradle_files = {
        candidate.relative_to(repository).as_posix()
        for pattern in ("*.gradle", "*.gradle.kts")
        for candidate in repository.rglob(pattern)
        if ".gradle" not in candidate.relative_to(repository).parts
        and "build" not in candidate.relative_to(repository).parts
    }
    if observed_gradle_files != expected_gradle_files:
        raise DependencyPolicyError("Gradle script inventory is not exact")
    for relative in sorted(observed_gradle_files):
        validate_build_script_text(relative, _text(repository / relative))


def validate_metadata(path: Path) -> None:
    payload = _read_regular(path)
    try:
        text = payload.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise DependencyPolicyError("dependency metadata is not strict UTF-8") from error
    if not payload.startswith(XML_DECLARATION):
        raise DependencyPolicyError("dependency metadata has no exact UTF-8 XML declaration")
    if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
        raise DependencyPolicyError("dependency metadata cannot declare XML entities")
    try:
        root = ElementTree.fromstring(payload)
    except ElementTree.ParseError as error:
        raise DependencyPolicyError(f"dependency metadata is invalid XML: {error}") from error
    if root.tag != f"{TAG}verification-metadata" or root.attrib != SCHEMA_LOCATION:
        raise DependencyPolicyError("dependency metadata root is not exact")

    configurations = root.findall(f"{TAG}configuration")
    components = root.findall(f"{TAG}components")
    if len(configurations) != 1 or len(components) != 1 or list(root) != [
        configurations[0],
        components[0],
    ]:
        raise DependencyPolicyError("dependency metadata must contain configuration then components")
    if (
        components[0].attrib
        or (components[0].text or "").strip()
        or not list(components[0])
    ):
        raise DependencyPolicyError("dependency components container is not exact")

    configuration = configurations[0]
    if configuration.attrib:
        raise DependencyPolicyError("dependency verification configuration has attributes")
    expected_children = [
        f"{TAG}verify-metadata",
        f"{TAG}verify-signatures",
        f"{TAG}trusted-artifacts",
    ]
    if [child.tag for child in configuration] != expected_children:
        raise DependencyPolicyError("dependency verification configuration shape is not exact")
    verify_metadata, verify_signatures, trusted_artifacts = list(configuration)
    if (
        verify_metadata.attrib
        or (verify_metadata.text or "").strip() != "true"
        or verify_signatures.attrib
        or (verify_signatures.text or "").strip() != "false"
    ):
        raise DependencyPolicyError("dependency verification flags are not exact")
    if trusted_artifacts.attrib or (trusted_artifacts.text or "").strip():
        raise DependencyPolicyError("trusted-artifacts container is not exact")

    observed: list[frozenset[tuple[str, str]]] = []
    for trust in trusted_artifacts:
        if trust.tag != f"{TAG}trust" or list(trust) or (trust.text or "").strip():
            raise DependencyPolicyError("trusted artifact entry is not an empty trust element")
        attributes = frozenset(trust.attrib.items())
        if len(attributes) != len(trust.attrib):
            raise DependencyPolicyError("trusted artifact entry has duplicate attributes")
        observed.append(attributes)
    if len(observed) != len(EXPECTED_TRUSTS) or frozenset(observed) != EXPECTED_TRUSTS:
        raise DependencyPolicyError("dependency verification trust exceptions are not exact")

    component_identities: set[tuple[str, str, str]] = set()
    stonecutter_identities: set[tuple[str, str, str]] = set()
    for component in components[0]:
        if component.tag != f"{TAG}component" or set(component.attrib) != {
            "group",
            "name",
            "version",
        }:
            raise DependencyPolicyError("dependency component identity is not exact")
        group = component.attrib["group"]
        name = component.attrib["name"]
        version = component.attrib["version"]
        if not group or not name or not version:
            raise DependencyPolicyError("dependency component identity cannot be empty")
        identity = (group, name, version)
        stonecutter_artifacts = None
        if group == "dev.kikugie" or group.startswith("dev.kikugie."):
            stonecutter_artifacts = STONECUTTER_COMPONENTS.get(identity)
            if stonecutter_artifacts is None:
                raise DependencyPolicyError("unreviewed Kikugie component or version")
            stonecutter_identities.add(identity)
        if identity in component_identities:
            raise DependencyPolicyError("dependency component identities must be unique")
        component_identities.add(identity)
        if _generated_component(group, name):
            raise DependencyPolicyError(
                f"generated component {group}:{name} must not carry inert checksums"
            )
        artifacts = list(component)
        if stonecutter_artifacts is not None and {
            artifact.attrib.get("name") for artifact in artifacts
        } != set(stonecutter_artifacts):
            raise DependencyPolicyError("Stonecutter artifact closure is not exact")
        if not artifacts:
            raise DependencyPolicyError(f"dependency component {group}:{name} is empty")
        artifact_names: set[str] = set()
        for artifact in artifacts:
            if artifact.tag != f"{TAG}artifact" or set(artifact.attrib) != {"name"}:
                raise DependencyPolicyError("dependency artifact identity is not exact")
            artifact_name = artifact.attrib["name"]
            if not artifact_name or artifact_name in artifact_names:
                raise DependencyPolicyError("dependency artifact names must be non-empty and unique")
            artifact_names.add(artifact_name)
            checksums = list(artifact)
            if len(checksums) != 1 or checksums[0].tag != f"{TAG}sha256":
                raise DependencyPolicyError(
                    f"dependency artifact {group}:{name}:{artifact_name} needs one SHA-256"
                )
            checksum = checksums[0]
            expected_origin = (
                STONECUTTER_CHECKSUM_ORIGIN if stonecutter_artifacts is not None
                else "Generated by Gradle"
            )
            if set(checksum.attrib) != {"value", "origin"} or re.fullmatch(
                r"[0-9a-f]{64}", checksum.attrib["value"]
            ) is None or checksum.attrib["origin"] != expected_origin:
                raise DependencyPolicyError("dependency SHA-256 declaration is not exact")
            if stonecutter_artifacts is not None and (
                checksum.attrib["value"] != stonecutter_artifacts[artifact_name] or list(checksum)
            ):
                raise DependencyPolicyError("Stonecutter SHA-256 binding is not exact")
            alternate_values: set[str] = set()
            for alternate in checksum:
                if (
                    alternate.tag != f"{TAG}also-trust"
                    or set(alternate.attrib) != {"value"}
                    or list(alternate)
                    or (alternate.text or "").strip()
                    or re.fullmatch(r"[0-9a-f]{64}", alternate.attrib["value"]) is None
                    or alternate.attrib["value"] == checksum.attrib["value"]
                    or alternate.attrib["value"] in alternate_values
                ):
                    raise DependencyPolicyError("alternate SHA-256 declaration is not exact")
                alternate_values.add(alternate.attrib["value"])
    if stonecutter_identities != set(STONECUTTER_COMPONENTS):
        raise DependencyPolicyError("Stonecutter component closure is incomplete")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--metadata",
        type=Path,
        default=Path("gradle/verification-metadata.xml"),
    )
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    try:
        validate_metadata(arguments.metadata)
        validate_repository_layout(arguments.repository_root)
    except DependencyPolicyError as error:
        parser.error(str(error))
    print("dependency verification trust policy is exact")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
