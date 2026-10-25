"""The World Players figure of a player who is not connected, for the packaged in-world scenario.

Carol played on the test server before and left. Before the server starts, the runtime gives it
her player data file and her entry in the user cache, so the World Players collection lists her
although she never connects. Her box and her figure block stand after the collection walls with
the skin a server stores in what it hands out (``SkinSnapshot``), so drawing them takes the path
of a stored skin: on Minecraft 1.21 and later, BlockPops' skin registration.

The skin is real but needs no download. Its link names textures.minecraft.net, as a skin Mojang
serves does, and the runtime puts the image in the clients' skin cache (``skins/`` in the assets
folder) under the name the game gives that link, so the game reads it from disk as it reads any
skin it downloaded before. Only on 26.3 does the game ask Mojang's discovery service, which it
reads as it starts, whether that host serves skins; that lane needs the service reachable, as the
game does for every skin.
"""

from __future__ import annotations

import base64
import gzip
import hashlib
import json
import uuid
from pathlib import Path

# The in-world step that photographs her box and figure
STEP = "world_players_offline"
COLLECTION_ID = "world_players"
PLAYER_NAME = "Carol"
# The bundled texture she wears as her skin: a 64x64 skin on the default figure model
SKIN = Path("common/src/main/resources/assets/blockpops/textures/figure/adventuretime/finn.png")
TEXTURE_LINK = "http://textures.minecraft.net/texture/"
SKIN_CACHE = "skins"
USER_CACHE = "usercache.json"
# A date the game's user cache keeps her name until
USER_CACHE_EXPIRY = "2099-12-31 23:59:59 +0000"


def player_uuid() -> uuid.UUID:
    """The id an offline-mode server gives her: Java's UUID.nameUUIDFromBytes("OfflinePlayer:Carol")."""

    digest = hashlib.md5(f"OfflinePlayer:{PLAYER_NAME}".encode("utf-8")).digest()
    return uuid.UUID(bytes=digest, version=3)


def skin(repository: Path) -> bytes:
    return (repository / SKIN).read_bytes()


def texture_hash(repository: Path) -> str:
    """The last part of the skin's link, as Mojang names a skin: the SHA-256 of its image."""

    return hashlib.sha256(skin(repository)).hexdigest()


def skin_snapshot(repository: Path) -> str:
    """The textures property a server stores in her box and figure, in Mojang's format, unsigned."""

    payload = {
        "timestamp": 0,
        "profileId": player_uuid().hex,
        "profileName": PLAYER_NAME,
        "textures": {"SKIN": {"url": TEXTURE_LINK + texture_hash(repository)}},
    }
    return base64.b64encode(json.dumps(payload, separators=(",", ":")).encode("utf-8")).decode("ascii")


def cache_name(texture: str) -> str:
    """The name the skin cache gives a texture hash: the SHA-1 of its characters, two bytes each, low byte first."""

    return hashlib.sha1(texture.encode("utf-16-le")).hexdigest()


def blocks(repository: Path) -> tuple[str, ...]:
    """Her box, then her figure block, facing south like the walls, from west to east."""

    data = (f'{{CollectionId:"{COLLECTION_ID}",FigureId:"{player_uuid()}",'
            f'SkinSnapshot:"{skin_snapshot(repository)}"}}')
    return (f"blockpops:box_block[facing=south]{data}", f"blockpops:figure_block[facing=south]{data}")


def player_data_directory(minecraft: str) -> Path:
    """Where the world keeps player files: playerdata up to 1.21.x, players/data from 26.1."""

    era = tuple(int(part) for part in minecraft.split(".") if part.isdigit())
    return Path("world") / ("players/data" if era and era[0] >= 26 else "playerdata")


def player_data() -> bytes:
    """Her player file: an empty compound, gzipped. BlockPops reads only that it is there."""

    return gzip.compress(b"\x0a\x00\x00\x00", mtime=0)


def install_server(server: Path, minecraft: str) -> None:
    """Gives the server her player file and her name, as if she had played on it and left."""

    data = server / player_data_directory(minecraft) / f"{player_uuid()}.dat"
    data.parent.mkdir(parents=True, exist_ok=True)
    data.write_bytes(player_data())
    entry = {"name": PLAYER_NAME, "uuid": str(player_uuid()), "expiresOn": USER_CACHE_EXPIRY}
    (server / USER_CACHE).write_text(json.dumps([entry]) + "\n", encoding="utf-8")


def install_skin(repository: Path, assets: Path) -> None:
    """Puts her skin in the skin cache of the clients' assets folder, where the game looks first."""

    name = cache_name(texture_hash(repository))
    target = assets / SKIN_CACHE / name[:2] / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(skin(repository))
