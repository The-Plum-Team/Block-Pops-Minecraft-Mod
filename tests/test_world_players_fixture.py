"""The packaged E2E's World Players player who is not connected, with a skin the game never downloads."""

from __future__ import annotations

import base64
import gzip
import hashlib
import json
import struct
import tempfile
import unittest
import uuid
from pathlib import Path
from urllib.parse import urlsplit

from e2e import custom_fixture
from e2e import packaged_runtime as runtime
from e2e import world_players_fixture as fixture
from e2e.scenario_contract import load_contract

ROOT = Path(__file__).resolve().parents[1]
COLLECTIONS = ROOT / runtime.COLLECTIONS_PATH
SCENARIO = ROOT / "common/src/e2e/java/com/theplumteam/e2e/scenario/InWorldScenario.java"
OWN_SKIN = ROOT / "common/src/main/java/com/theplumteam/client/worldcontent/OwnSkin.java"


class WorldPlayersFixtureTests(unittest.TestCase):
    def test_her_id_is_the_one_an_offline_server_gives_her(self) -> None:
        # Java's UUID.nameUUIDFromBytes: the MD5 of the bytes, stamped as a version 3 IETF UUID
        digest = bytearray(hashlib.md5(b"OfflinePlayer:Carol").digest())
        digest[6] = digest[6] & 0x0F | 0x30
        digest[8] = digest[8] & 0x3F | 0x80
        self.assertEqual(uuid.UUID(bytes=bytes(digest)), fixture.player_uuid())
        self.assertEqual("0af3f783-cbb9-32f0-953c-0d7e29e82d58", str(fixture.player_uuid()))

    def test_her_skin_is_a_64_by_64_png(self) -> None:
        data = fixture.skin(ROOT)
        self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
        self.assertEqual((64, 64), struct.unpack(">II", data[16:24]))

    def test_the_stored_skin_is_an_unsigned_textures_property_naming_a_mojang_skin_link(self) -> None:
        payload = json.loads(base64.b64decode(fixture.skin_snapshot(ROOT), validate=True))
        self.assertEqual({"timestamp", "profileId", "profileName", "textures"}, set(payload))
        self.assertEqual((fixture.player_uuid().hex, fixture.PLAYER_NAME),
                         (payload["profileId"], payload["profileName"]))
        self.assertEqual({"SKIN"}, set(payload["textures"]))
        link = urlsplit(payload["textures"]["SKIN"]["url"])
        # The only host every version's game accepts a skin from
        self.assertEqual(("http", "textures.minecraft.net"), (link.scheme, link.hostname))
        self.assertEqual("/texture/" + hashlib.sha256(fixture.skin(ROOT)).hexdigest(), link.path)

    def test_the_skin_cache_names_it_as_the_game_does(self) -> None:
        # Guava's hashUnencodedChars, as the game and OwnSkin take a texture hash
        self.assertEqual("595a80e8da6b91bc59fe2c7833621d19864dfdac",
                         fixture.cache_name("c83562d78c8011a1e61d9562d8e0d3710cdb8ca341f43883b757f93cd8dd955a"))
        self.assertIn('MessageDigest.getInstance("SHA-1").digest(hash.getBytes(StandardCharsets.UTF_16LE))',
                      OWN_SKIN.read_text(encoding="utf-8"))
        self.assertIn('MessageDigest.getInstance("SHA-1").digest(hash.getBytes(StandardCharsets.UTF_16LE))',
                      SCENARIO.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as temporary:
            assets = Path(temporary)
            fixture.install_skin(ROOT, assets)
            name = fixture.cache_name(fixture.texture_hash(ROOT))
            self.assertEqual([assets / "skins" / name[:2] / name], [path for path in assets.rglob("*") if path.is_file()])
            self.assertEqual(fixture.skin(ROOT), (assets / "skins" / name[:2] / name).read_bytes())

    def test_the_server_knows_her_as_a_player_who_left(self) -> None:
        for minecraft, folder in (("1.20.1", "world/playerdata"), ("1.21.11", "world/playerdata"),
                                  ("26.1", "world/players/data"), ("26.3", "world/players/data")):
            with self.subTest(minecraft=minecraft), tempfile.TemporaryDirectory() as temporary:
                server = Path(temporary)
                fixture.install_server(server, minecraft)
                data = server / folder / f"{fixture.player_uuid()}.dat"
                self.assertEqual(b"\x0a\x00\x00\x00", gzip.decompress(data.read_bytes()))
                self.assertEqual(
                    [{"name": "Carol", "uuid": str(fixture.player_uuid()), "expiresOn": "2099-12-31 23:59:59 +0000"}],
                    json.loads((server / "usercache.json").read_text(encoding="utf-8")),
                )
                self.assertEqual({data, server / "usercache.json"},
                                 {path for path in server.rglob("*") if path.is_file()})

    def test_her_blocks_stand_in_the_place_after_the_custom_walls(self) -> None:
        packaged = runtime.showcase_collections(COLLECTIONS)
        custom = ((custom_fixture.COLLECTION_ID, custom_fixture.figures()),)
        with tempfile.TemporaryDirectory() as temporary:
            server = Path(temporary)
            runtime.write_server_files(server, 25565, ROOT / "e2e" / "server-template", "1.20.1", custom, True)
            lines = (server / "world" / "datapacks" / "blockpops_e2e_time" / "data" / "blockpops_e2e"
                     / "function" / "showcase.mcfunction").read_text().splitlines()
            self.assertTrue((server / "world" / "playerdata" / f"{fixture.player_uuid()}.dat").is_file())
        self.assertEqual(runtime.showcase_function(packaged, custom).splitlines()[2:], lines[2:-2])
        origin = runtime.SHOWCASE_WALL_ORIGIN_X + (len(packaged) + 2) * runtime.SHOWCASE_WALL_SPACING
        self.assertEqual(origin, runtime.SHOWCASE_WALL_ORIGIN_X
                         + runtime.world_players_index(packaged, custom) * runtime.SHOWCASE_WALL_SPACING)
        self.assertEqual(f"forceload add {runtime.SHOWCASE_WALL_ORIGIN_X} {runtime.SHOWCASE_WALL_Z} "
                         f"{origin + runtime.SHOWCASE_COLUMNS} {runtime.SHOWCASE_WALL_Z}", lines[1])
        data = (f'{{CollectionId:"world_players",FigureId:"{fixture.player_uuid()}",'
                f'SkinSnapshot:"{fixture.skin_snapshot(ROOT)}"}}')
        place = f"{runtime.SHOWCASE_GROUND_Y} {runtime.SHOWCASE_WALL_Z}"
        self.assertEqual([f"setblock {origin} {place} blockpops:box_block[facing=south]{data}",
                          f"setblock {origin + 1} {place} blockpops:figure_block[facing=south]{data}"],
                         lines[-2:])
        # The harness walks to the same place and asks for the same player
        source = SCENARIO.read_text(encoding="utf-8")
        self.assertIn("worldPlayersOffline(minecraft, collections.size() + 1 + custom.size())", source)
        self.assertIn('"OfflinePlayer:Carol".getBytes(StandardCharsets.UTF_8)', source)
        self.assertIn(f'WORLD_PLAYERS_STEP = "{fixture.STEP}"', source)

    def test_only_the_scenario_that_photographs_her_gets_her(self) -> None:
        contract = load_contract(ROOT / "e2e" / "scenario-contract.json")
        steps = {scenario: [step for role in contract.scenario(scenario).roles for step in role.step_ids]
                 for scenario in ("ui-regression", "in-world")}
        self.assertNotIn(fixture.STEP, steps["ui-regression"])
        self.assertEqual(fixture.STEP, steps["in-world"][-1])
        self.assertIn(fixture.STEP, contract.expected_capture_steps("in-world", "client_a"))
        source = (ROOT / "e2e" / "packaged_runtime.py").read_text(encoding="utf-8")
        self.assertIn("world_players_fixture.STEP in SCENARIO_CONTRACT.role(scenario, role).step_ids", source)
        self.assertIn('world_players_fixture.install_skin(repo, install_dir / "assets")', source)


if __name__ == "__main__":
    unittest.main()
