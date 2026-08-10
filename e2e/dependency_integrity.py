#!/usr/bin/env python3
"""Resolve packaged-runtime dependency hashes from Gradle's strict verification authority."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path


SHA256 = re.compile(r"^[0-9a-f]{64}$")
MAX_METADATA_BYTES = 16 * 1024 * 1024


class DependencyIntegrityError(ValueError):
    pass


def verified_sha256(
    metadata_path: Path,
    *,
    group: str,
    name: str,
    version: str,
    artifact: str,
) -> str:
    """Return the one exact SHA-256 trusted by Gradle for a Maven artifact."""

    for value, label in (
        (group, "group"),
        (name, "name"),
        (version, "version"),
        (artifact, "artifact"),
    ):
        if not isinstance(value, str) or not value or value != value.strip():
            raise DependencyIntegrityError(f"{label} must be a non-empty trimmed string")
    if Path(artifact).name != artifact or "/" in artifact or "\\" in artifact:
        raise DependencyIntegrityError(f"artifact name is unsafe: {artifact!r}")
    try:
        size = metadata_path.stat().st_size
        if size <= 0 or size > MAX_METADATA_BYTES:
            raise DependencyIntegrityError("Gradle verification metadata exceeds its size limit")
        root = ET.parse(metadata_path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise DependencyIntegrityError(
            f"cannot read Gradle verification metadata {metadata_path}: {exc}"
        ) from exc

    namespace = "https://schema.gradle.org/dependency-verification"
    component_tag = f"{{{namespace}}}component"
    artifact_tag = f"{{{namespace}}}artifact"
    sha_tag = f"{{{namespace}}}sha256"
    components = [
        component
        for component in root.iter(component_tag)
        if component.attrib
        == {"group": group, "name": name, "version": version}
    ]
    if len(components) != 1:
        raise DependencyIntegrityError(
            f"verification metadata has {len(components)} components for "
            f"{group}:{name}:{version}"
        )
    artifacts = [
        candidate
        for candidate in components[0].findall(artifact_tag)
        if candidate.attrib == {"name": artifact}
    ]
    if len(artifacts) != 1:
        raise DependencyIntegrityError(
            f"verification metadata has {len(artifacts)} records for "
            f"{group}:{name}:{version}:{artifact}"
        )
    hashes = [
        child.attrib.get("value")
        for child in artifacts[0].findall(sha_tag)
        if set(child.attrib) <= {"value", "origin", "reason"}
    ]
    if len(hashes) != 1 or not isinstance(hashes[0], str) or not SHA256.fullmatch(hashes[0]):
        raise DependencyIntegrityError(
            f"verification metadata must contain one lowercase SHA-256 for "
            f"{group}:{name}:{version}:{artifact}"
        )
    return hashes[0]
