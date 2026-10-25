#!/usr/bin/env python3
"""Publish the jars one successful Build run tested to Modrinth and CurseForge.

``verify`` authenticates the Build run through the GitHub API, requires the commit it tested to
have exactly the tree being released, downloads its bundle by immutable artifact id and digest,
checks every production jar of the release matrix against the digest its lane recorded, and
writes a manifest. ``publish`` uploads the manifest's jars to one marketplace, checking each
jar's digest again just before it is sent. Only ``verify`` holds a GitHub token and only
``publish`` holds a marketplace token.

    publish_marketplaces.py verify --version 2.0.3 --run-id N --output DIR
    publish_marketplaces.py publish --manifest DIR/manifest.json --marketplace modrinth [--lanes NODE ...]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path, PurePosixPath
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.lib.secure_json import SecureJsonError, loads as loads_secure_json, read as read_secure_json  # noqa: E402
from scripts.release import artifact_transport  # noqa: E402
from scripts.release.matrix import MatrixError, load_matrix_inventory, matrix_sha256  # noqa: E402

MATRIX = REPO / "release/release-matrix.json"
CHANGELOGS = REPO / "docs/changelogs"
BUILD_WORKFLOW = ".github/workflows/mod-base-build.yml"
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
RUN_ID = re.compile(r"^[1-9][0-9]{0,19}$")
SHA = re.compile(r"^[0-9a-f]{40}$")
NODE = re.compile(r"^[a-z]+-[0-9][0-9.]*$")
MAX_API_BYTES = 4 * 1024 * 1024
MAX_LANE_JSON_BYTES = 256 * 1024
MAX_CHANGELOG_BYTES = 16 * 1024
MAX_JAR_BYTES = 64 * 1024 * 1024

MODRINTH_API = "https://api.modrinth.com/v2"
MODRINTH_PROJECT = "u1JjJR7q"
CURSEFORGE_API = "https://minecraft.curseforge.com/api"
CURSEFORGE_PROJECT = 1394429
LOADER_NAMES = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}


class PublishError(RuntimeError):
    pass


# ---- verify ---------------------------------------------------------------------------------


def git_tree(revision: str) -> str:
    if revision != "HEAD" and SHA.fullmatch(revision) is None:
        raise PublishError("a revision must be HEAD or a full commit id")
    result = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{revision}^{{tree}}"], cwd=REPO,
                            capture_output=True, text=True, check=False)
    tree = result.stdout.strip()
    if result.returncode != 0 or SHA.fullmatch(tree) is None:
        raise PublishError(f"commit {revision} is not in this checkout")
    return tree


def github_json(path: str, *, token: str, api_url: str) -> Any:
    request = urllib.request.Request(f"{api_url}{path}", headers={
        "Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "blockpops-publish-marketplaces"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read(MAX_API_BYTES + 1)
    except (urllib.error.URLError, OSError) as exc:
        raise PublishError(f"GitHub API {path} failed: {exc}") from exc
    try:
        return loads_secure_json(raw, label=f"GitHub API {path}", max_bytes=MAX_API_BYTES)
    except SecureJsonError as exc:
        raise PublishError(str(exc)) from exc


def build_artifact(repository: str, run_id: str, *, token: str, api_url: str) -> dict[str, Any]:
    """The bundle artifact of a successful Build run, as the GitHub API describes it."""

    run = github_json(f"/repos/{repository}/actions/runs/{run_id}", token=token, api_url=api_url)
    if not isinstance(run, dict) or str(run.get("id")) != run_id:
        raise PublishError(f"run {run_id} was not found")
    if (run.get("repository") or {}).get("full_name") != repository:
        raise PublishError(f"run {run_id} belongs to another repository")
    if run.get("path") != BUILD_WORKFLOW:
        raise PublishError(f"run {run_id} is not a run of {BUILD_WORKFLOW}")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise PublishError(f"run {run_id} did not succeed")
    attempt = run.get("run_attempt")
    if type(attempt) is not int or attempt < 1:
        raise PublishError(f"run {run_id} has no valid attempt")
    name = f"mb-ci-build--{run_id}--a{attempt}"
    listing = github_json(f"/repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100&name={name}",
                          token=token, api_url=api_url)
    found = [a for a in (listing or {}).get("artifacts", []) if isinstance(a, dict) and a.get("name") == name]
    if len(found) != 1:
        raise PublishError(f"run {run_id} has {len(found)} artifacts named {name}")
    artifact = found[0]
    if artifact.get("expired") is not False:
        raise PublishError(f"{name} has expired")
    if str((artifact.get("workflow_run") or {}).get("id")) != run_id:
        raise PublishError(f"{name} belongs to another run")
    tested = (artifact.get("workflow_run") or {}).get("head_sha")
    if not isinstance(tested, str) or SHA.fullmatch(tested) is None:
        raise PublishError(f"{name} names no tested commit")
    if type(artifact.get("id")) is not int or not isinstance(artifact.get("digest"), str):
        raise PublishError(f"{name} has no id or digest")
    return {"id": artifact["id"], "digest": artifact["digest"], "name": name, "attempt": attempt,
            "tested_commit": tested}


def regular_file(path: Path, label: str, max_bytes: int) -> bytes:
    """The bytes of one regular file that is not a link, read with a size bound."""

    try:
        info = path.lstat()
    except OSError as exc:
        raise PublishError(f"{label} is missing: {exc}") from exc
    if not stat.S_ISREG(info.st_mode) or info.st_size <= 0 or info.st_size > max_bytes:
        raise PublishError(f"{label} must be a regular file of 1 to {max_bytes} bytes")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    descriptor = os.open(path, flags)
    try:
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            data = handle.read(max_bytes + 1)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    if len(data) != info.st_size:
        raise PublishError(f"{label} changed while it was read")
    return data


def lane_record(bundle: Path, node: str) -> dict[str, Any]:
    found = sorted(bundle.glob(f"targets/*/lanes/{node}/artifacts.json"))
    if len(found) != 1:
        raise PublishError(f"the bundle has {len(found)} artifacts.json for {node}")
    try:
        data, _ = read_secure_json(found[0], label=f"{node} artifacts.json", max_bytes=MAX_LANE_JSON_BYTES)
    except SecureJsonError as exc:
        raise PublishError(str(exc)) from exc
    if not isinstance(data, dict):
        raise PublishError(f"{node} artifacts.json is not an object")
    return data


def verify_bundle(bundle: Path, *, version: str, tree: str, matrix_path: Path = MATRIX) -> list[dict[str, Any]]:
    """Every production jar of the matrix, checked against the record its lane wrote."""

    try:
        configured = load_matrix_inventory(matrix_path).require_complete()
    except MatrixError as exc:
        raise PublishError(str(exc)) from exc
    matrix_digest = matrix_sha256(matrix_path)
    lanes = []
    for configuration in configured:
        node = configuration.identity.artifact_node
        artifact = {"loader": configuration.identity.loader, "minecraft": configuration.identity.minecraft,
                    "java": configuration.artifact["java"]}
        if configuration.mod_version != version:
            raise PublishError(f"{node} is at {configuration.mod_version}, not {version}")
        filename = PurePosixPath(configuration.production_jar).name
        record = lane_record(bundle, node)
        if record.get("git_tree") != tree:
            raise PublishError(f"{node} was built from tree {record.get('git_tree')}, not {tree}")
        if (record.get("matrix") or {}).get("sha256") != matrix_digest:
            raise PublishError(f"{node} was built from another release matrix")
        entries = [e for e in record.get("artifacts", []) if isinstance(e, dict) and e.get("artifact_node") == node]
        if len(entries) != 1:
            raise PublishError(f"{node} artifacts.json lists {len(entries)} entries for it")
        entry = entries[0]
        for key in ("loader", "minecraft", "mod_version"):
            if entry.get(key) != (version if key == "mod_version" else artifact[key]):
                raise PublishError(f"{node} artifacts.json disagrees with the matrix on {key}")
        production = entry.get("production") or {}
        if production.get("filename") != filename or production.get("path") != f"files/{filename}":
            raise PublishError(f"{node} artifacts.json names another jar")
        digest = production.get("sha256")
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise PublishError(f"{node} artifacts.json has no jar digest")
        data = regular_file(bundle / "files" / filename, f"{node} jar", MAX_JAR_BYTES)
        if len(data) != production.get("bytes") or hashlib.sha256(data).hexdigest() != digest:
            raise PublishError(f"{node} jar differs from the one its lane recorded")
        lanes.append({"node": node, "loader": artifact["loader"], "minecraft": artifact["minecraft"],
                      "java": artifact["java"], "file": f"files/{filename}", "sha256": digest,
                      "title": f"Block Pops {version} [{LOADER_NAMES[artifact['loader']]}] "
                               f"[MC {artifact['minecraft']}]"})
    return sorted(lanes, key=lambda lane: lane["node"])


def changelog(version: str, directory: Path = CHANGELOGS) -> str:
    text = regular_file(directory / f"{version}.md", f"changelog {version}", MAX_CHANGELOG_BYTES).decode("utf-8")
    if not text.strip():
        raise PublishError(f"changelog {version} is empty")
    return text


def verify(args: argparse.Namespace) -> None:
    if VERSION.fullmatch(args.version) is None or RUN_ID.fullmatch(args.run_id) is None:
        raise PublishError("the version or run id is malformed")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GH_TOKEN", "")
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    if not token:
        raise PublishError("GH_TOKEN is required")
    notes = changelog(args.version)
    tree = git_tree("HEAD")
    artifact = build_artifact(repository, args.run_id, token=token, api_url=api_url)
    if git_tree(artifact["tested_commit"]) != tree:
        raise PublishError(f"run {args.run_id} tested {artifact['tested_commit']}, whose tree is not this commit's")
    bundle = args.output / "bundle"
    try:
        artifact_transport.download(repository=repository, artifact_id=artifact["id"], digest=artifact["digest"],
                                    output=bundle, token=token, api_url=api_url)
    except (artifact_transport.ArtifactDownloadError, OSError, ValueError) as exc:
        raise PublishError(f"{artifact['name']}: {exc}") from exc
    lanes = verify_bundle(bundle, version=args.version, tree=tree)
    manifest = {"version": args.version, "run_id": int(args.run_id), "run_attempt": artifact["attempt"],
                "tested_commit": artifact["tested_commit"], "tree": tree, "changelog": notes, "lanes": lanes}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True), encoding="utf-8")
    print(f"{len(lanes)} jars of {args.version} verified from {artifact['name']} (tested {artifact['tested_commit']})")


# ---- publish --------------------------------------------------------------------------------


def request(method: str, url: str, headers: dict[str, str], body: bytes | None = None,
            attempts: int = 3) -> tuple[int, bytes]:
    for attempt in range(attempts):
        req = urllib.request.Request(url, data=body, method=method,
                                     headers={"User-Agent": "The-Plum-Team/blockpops-publish", **headers})
        try:
            with urllib.request.urlopen(req, timeout=120) as response:
                return response.status, response.read(MAX_API_BYTES)
        except urllib.error.HTTPError as error:
            payload = error.read(2000)
            if error.code >= 500 and attempt + 1 < attempts:
                time.sleep(5 * (attempt + 1))
                continue
            return error.code, payload
        except (urllib.error.URLError, OSError):
            if attempt + 1 < attempts:
                time.sleep(5 * (attempt + 1))
                continue
            raise
    raise PublishError("unreachable")


def multipart(fields: dict[str, str], file_field: str, filename: str, data: bytes) -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n"
                     f"Content-Type: application/json\r\n\r\n{value}\r\n".encode())
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; "
                 f"filename=\"{filename}\"\r\nContent-Type: application/java-archive\r\n\r\n".encode())
    parts.append(data)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def response_json(status: int, body: bytes, label: str) -> Any:
    if status != 200:
        raise PublishError(f"{label}: HTTP {status} {body[:300]!r}")
    try:
        return json.loads(body)
    except ValueError as exc:
        raise PublishError(f"{label}: the answer is not JSON") from exc


def jar_bytes(manifest_dir: Path, lane: dict[str, Any]) -> bytes:
    data = regular_file(manifest_dir / "bundle" / lane["file"], f"{lane['node']} jar", MAX_JAR_BYTES)
    if hashlib.sha256(data).hexdigest() != lane["sha256"]:
        raise PublishError(f"{lane['node']} jar changed since it was verified")
    return data


def publish_modrinth(manifest: dict[str, Any], lanes: list[dict[str, Any]], manifest_dir: Path,
                     token: str, dry_run: bool) -> None:
    version = manifest["version"]
    listed = response_json(*request("GET", f"{MODRINTH_API}/project/{MODRINTH_PROJECT}/version", {}),
                           "Modrinth listing")
    published = {(v["loaders"][0], v["game_versions"][0]) for v in listed
                 if v.get("version_number") == version and v.get("loaders") and v.get("game_versions")}
    for lane in lanes:
        if (lane["loader"], lane["minecraft"]) in published:
            print(f"modrinth {lane['node']}: already published, skipped")
            continue
        data = {"name": lane["title"], "version_number": version, "changelog": manifest["changelog"],
                "dependencies": [], "game_versions": [lane["minecraft"]], "version_type": "release",
                "loaders": [lane["loader"]], "featured": False, "status": "listed",
                "project_id": MODRINTH_PROJECT, "file_parts": ["file"], "primary_file": "file"}
        if dry_run:
            print(f"modrinth {lane['node']}: would publish {lane['file']} as {lane['title']}")
            continue
        jar = jar_bytes(manifest_dir, lane)
        payload, content_type = multipart({"data": json.dumps(data)}, "file", PurePosixPath(lane["file"]).name, jar)
        made = response_json(*request("POST", f"{MODRINTH_API}/version",
                                      {"Authorization": token, "Content-Type": content_type}, payload, attempts=1),
                             f"modrinth {lane['node']}")
        if made["files"][0]["hashes"]["sha512"] != hashlib.sha512(jar).hexdigest():
            raise PublishError(f"modrinth {lane['node']}: the uploaded bytes differ from the verified jar")
        print(f"modrinth {lane['node']}: published {made['id']}")


def curseforge_ids(versions: list[tuple[str, str, int]], lane: dict[str, Any]) -> list[int]:
    """CurseForge's ids for the lane's Minecraft version, loader, both sides and Java."""

    def one(kind: str, name: str) -> int:
        matches = [i for slug, n, i in versions if n == name and
                   (slug.startswith("minecraft-") if kind == "minecraft" else slug == kind)]
        if len(matches) != 1:
            raise PublishError(f"CurseForge has {len(matches)} {kind} versions named {name!r}")
        return matches[0]

    ids = [one("minecraft", lane["minecraft"]), one("modloader", LOADER_NAMES[lane["loader"]]),
           one("environment", "Client"), one("environment", "Server")]
    if lane["java"]:
        ids.append(one("java", f"Java {lane['java']}"))
    return ids


def publish_curseforge(manifest: dict[str, Any], lanes: list[dict[str, Any]], manifest_dir: Path,
                       token: str, dry_run: bool) -> None:
    headers = {"X-Api-Token": token}
    types = {t["id"]: t["slug"] for t in response_json(
        *request("GET", f"{CURSEFORGE_API}/game/version-types", headers), "CurseForge version types")}
    versions = [(types.get(v["gameVersionTypeID"], ""), v["name"], v["id"]) for v in response_json(
        *request("GET", f"{CURSEFORGE_API}/game/versions", headers), "CurseForge versions")]
    for lane in lanes:
        metadata = {"changelog": manifest["changelog"], "changelogType": "markdown", "displayName": lane["title"],
                    "gameVersions": curseforge_ids(versions, lane), "releaseType": "release"}
        if dry_run:
            print(f"curseforge {lane['node']}: would publish {lane['file']} with {metadata['gameVersions']}")
            continue
        jar = jar_bytes(manifest_dir, lane)
        payload, content_type = multipart({"metadata": json.dumps(metadata)}, "file",
                                          PurePosixPath(lane["file"]).name, jar)
        made = response_json(*request("POST", f"{CURSEFORGE_API}/projects/{CURSEFORGE_PROJECT}/upload-file",
                                      {**headers, "Content-Type": content_type}, payload, attempts=1),
                             f"curseforge {lane['node']}")
        print(f"curseforge {lane['node']}: published file {made['id']}")


def publish(args: argparse.Namespace) -> None:
    try:
        manifest, _ = read_secure_json(args.manifest, label="publish manifest", max_bytes=MAX_API_BYTES)
    except SecureJsonError as exc:
        raise PublishError(str(exc)) from exc
    lanes = manifest["lanes"]
    if args.lanes:
        unknown = sorted(set(args.lanes) - {lane["node"] for lane in lanes})
        if unknown or any(NODE.fullmatch(node) is None for node in args.lanes):
            raise PublishError(f"unknown lanes: {' '.join(unknown) or 'malformed'}")
        lanes = [lane for lane in lanes if lane["node"] in args.lanes]
    variable = "MODRINTH_TOKEN" if args.marketplace == "modrinth" else "CURSEFORGE_TOKEN"
    token = os.environ.get(variable, "").strip()
    if not token and not args.dry_run:
        raise PublishError(f"{variable} is required")
    print(f"{args.marketplace}: {len(lanes)} jars of {manifest['version']}")
    upload = publish_modrinth if args.marketplace == "modrinth" else publish_curseforge
    upload(manifest, lanes, args.manifest.parent, token, args.dry_run)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("verify")
    check.add_argument("--version", required=True)
    check.add_argument("--run-id", required=True)
    check.add_argument("--output", type=Path, required=True)
    send = commands.add_parser("publish")
    send.add_argument("--manifest", type=Path, required=True)
    send.add_argument("--marketplace", choices=("modrinth", "curseforge"), required=True)
    send.add_argument("--lanes", nargs="*")
    send.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        (verify if args.command == "verify" else publish)(args)
        return 0
    except PublishError as exc:
        print(f"publish error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
