"""The packaged E2E's Custom collection: a valid CDN-layout collection that never needs the CDN."""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import re
import struct
import tempfile
import unittest
from pathlib import Path

from e2e import custom_fixture as fixture
from e2e import packaged_runtime as runtime
from e2e.scenario_contract import load_contract

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "common/src/main/java/com/theplumteam"
REMOTE_PATHS = MAIN / "remote/RemotePaths.java"
COLLECTIONS = ROOT / runtime.COLLECTIONS_PATH
JAVA_PATTERN = re.compile(r'private static final Pattern (MODEL|TEXTURE|BOX|LOGO|POSES) = Pattern\.compile\(\s*"([^"]+)"\);')


def remote_patterns() -> dict[str, re.Pattern[str]]:
    """RemotePaths' own allowlist, read from the Java source so the two cannot drift apart."""

    found = JAVA_PATTERN.findall(REMOTE_PATHS.read_text(encoding="utf-8"))
    return {name: re.compile(source.replace("\\\\", "\\")) for name, source in found}


def read_nbt(data: bytes):
    """A minimal reader for the compound and byte tags the 1.5.0 settings use."""

    stream = io.BytesIO(data)

    def name() -> str:
        (length,) = struct.unpack(">H", stream.read(2))
        return stream.read(length).decode("utf-8")

    def payload(kind: int):
        if kind == 1:
            return struct.unpack(">b", stream.read(1))[0]
        if kind == 10:
            compound = {}
            while (child := stream.read(1)[0]) != 0:
                key = name()
                compound[key] = payload(child)
            return compound
        raise AssertionError(f"unexpected tag {kind}")

    kind = stream.read(1)[0]
    root_name = name()
    value = payload(kind)
    if stream.read():
        raise AssertionError("data after the root tag")
    return kind, root_name, value


class CustomFixtureTests(unittest.TestCase):
    def setUp(self) -> None:
        self.files = fixture.build(ROOT)
        self.manifest = json.loads(self.files[fixture.MANIFEST])
        self.definition = json.loads(self.files[f"data/blockpops/collections/{fixture.COLLECTION_ID}.json"])

    def test_every_file_is_one_the_game_would_download_for_the_collection(self) -> None:
        patterns = remote_patterns()
        self.assertEqual({"MODEL", "TEXTURE", "BOX", "LOGO", "POSES"}, set(patterns))
        assets = [path for path in self.files if path.startswith("assets/")]
        for path in assets:
            with self.subTest(path=path):
                match = next((m for m in (p.fullmatch(path) for p in patterns.values()) if m), None)
                self.assertIsNotNone(match)
                owner = match.group(1)
                self.assertTrue(owner == fixture.COLLECTION_ID or owner.startswith(fixture.COLLECTION_ID + "_"))
        self.assertRegex(fixture.COLLECTION_ID, r"[a-z0-9_]{1,32}")
        self.assertNotIn(fixture.COLLECTION_ID, [path.stem for path in COLLECTIONS.glob("*.json")])

    def test_the_manifest_lists_every_file_with_its_digest_and_nothing_else(self) -> None:
        self.assertEqual({"version", "collections", "files"}, set(self.manifest))
        self.assertEqual([{"id": fixture.COLLECTION_ID, "name": fixture.COLLECTION_NAME}], self.manifest["collections"])
        listed = {entry["path"]: entry["sha256"] for entry in self.manifest["files"]}
        self.assertEqual(set(self.files) - {fixture.MANIFEST}, set(listed))
        for path, digest in listed.items():
            self.assertEqual(hashlib.sha256(self.files[path]).hexdigest(), digest, path)

    def test_the_definition_names_only_its_own_files_or_the_shared_defaults(self) -> None:
        def file(resource_id: str) -> str:
            namespace, path = resource_id.split(":", 1)
            if path.startswith("geo/"):
                return f"assets/{namespace}/geckolib/models/{path.removeprefix('geo/')}"
            if path.startswith("animations/"):
                return f"assets/{namespace}/geckolib/animations/{path.removeprefix('animations/')}"
            return f"assets/{namespace}/{path}"

        shared = {fixture.DEFAULT_MODEL, "blockpops:animations/figure/box_figure_default.animation.json"}
        self.assertEqual(fixture.COLLECTION_ID, self.definition["id"])
        self.assertIn(file(self.definition["box_texture"]), self.files)
        self.assertIn(file(self.definition["logo"]["texture"]), self.files)
        self.assertEqual(fixture.figures(), [figure["id"] for figure in self.definition["figures"]])
        for figure in self.definition["figures"]:
            self.assertNotIn("alternatives", figure)
            for key in ("model", "texture", "animation", "pose_animation"):
                if key in figure and figure[key] not in shared:
                    self.assertIn(file(figure[key]), self.files, f"{figure['id']} {key}")
        own_models = [figure for figure in self.definition["figures"] if figure["model"] != fixture.DEFAULT_MODEL]
        self.assertTrue(own_models and any("pose_animation" in figure for figure in own_models))
        self.assertTrue(any(figure["model"] == fixture.DEFAULT_MODEL for figure in self.definition["figures"]))

    def test_the_textures_are_pngs(self) -> None:
        for path, data in self.files.items():
            if path.endswith(".png"):
                self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8], path)

    def test_the_legacy_settings_enable_the_collection_as_1_5_0_saved_them(self) -> None:
        data = fixture.legacy_settings()
        self.assertEqual(data, fixture.legacy_settings())
        self.assertEqual(
            (10, "", {"data": {"EnabledRemoteCollections": {fixture.COLLECTION_ID: 1}}}),
            read_nbt(gzip.decompress(data)),
        )

    def test_install_fills_every_cache_and_the_world(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            server, clients = root / "server", [root / "client_a", root / "client_b"]
            fixture.install(ROOT, server, clients)
            for game_directory in (server, *clients):
                cache = game_directory / fixture.CACHE_DIRECTORY
                self.assertEqual(
                    {path: data for path, data in self.files.items()},
                    {path.relative_to(cache).as_posix(): path.read_bytes()
                     for path in cache.rglob("*") if path.is_file()},
                )
            self.assertEqual(fixture.legacy_settings(), (server / fixture.LEGACY_SETTINGS).read_bytes())

    def test_the_custom_wall_stands_after_the_packaged_walls(self) -> None:
        packaged = runtime.showcase_collections(COLLECTIONS)
        with tempfile.TemporaryDirectory() as temporary:
            server = Path(temporary)
            runtime.write_server_files(server, 25565, ROOT / "e2e" / "server-template", "1.20.1",
                                       ((fixture.COLLECTION_ID, fixture.figures()),))
            written = (server / "world" / "datapacks" / "blockpops_e2e_time" / "data" / "blockpops_e2e"
                       / "function" / "showcase.mcfunction").read_text()
        expected = runtime.showcase_function(packaged, ((fixture.COLLECTION_ID, fixture.figures()),))
        self.assertEqual(expected, written)
        # One empty wall's place apart from the last packaged wall
        origin = runtime.SHOWCASE_WALL_ORIGIN_X + (len(packaged) + 1) * runtime.SHOWCASE_WALL_SPACING
        self.assertIn(f'setblock {origin} {runtime.SHOWCASE_GROUND_Y} {runtime.SHOWCASE_WALL_Z} '
                      f'blockpops:box_block[facing=south]{{CollectionId:"{fixture.COLLECTION_ID}",'
                      f'FigureId:"{fixture.figures()[0]}"}}', written)

    def test_only_the_scenario_that_photographs_it_gets_it(self) -> None:
        contract = load_contract(ROOT / "e2e" / "scenario-contract.json")
        steps = {scenario: [step for role in contract.scenario(scenario).roles for step in role.step_ids]
                 for scenario in ("ui-regression", "in-world")}
        self.assertNotIn(fixture.STEP, steps["ui-regression"])
        walls = [step for step in steps["in-world"] if step.startswith("collection_")]
        self.assertEqual(fixture.STEP, walls[-1])
        source = (ROOT / "e2e" / "packaged_runtime.py").read_text(encoding="utf-8")
        self.assertIn("custom_fixture.STEP in SCENARIO_CONTRACT.role(scenario, role).step_ids", source)

    def test_the_game_reaches_the_cdn_only_outside_the_packaged_e2e(self) -> None:
        source = (MAIN / "remote/RemoteSource.java").read_text(encoding="utf-8")
        self.assertIn("return E2EDeterminism.ENABLED ? NONE : new CdnSource();", source)
        builders = [path.relative_to(MAIN).as_posix() for path in MAIN.rglob("*.java")
                    if "new CdnSource(" in path.read_text(encoding="utf-8")]
        self.assertEqual(["remote/RemoteSource.java"], builders)


if __name__ == "__main__":
    unittest.main()
