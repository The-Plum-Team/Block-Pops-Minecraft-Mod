"""The Custom collection the packaged in-world scenario photographs, without the CDN.

In the packaged E2E the game's remote source has no files at all (``RemoteSource.NONE``), so
CI never reaches the CDN. The game reads Custom collections from the verified cache instead,
``blockpops-cache/`` in its game directory, laid out like the CDN. Before the game starts,
the runtime fills the cache of the server and of every client with this collection. It
enables the collection in the test world through 1.5.0's world settings file, which the
server imports when it starts.

The collection reuses bundled resources under the CDN layout of its own id: a figure with its
own model, poses and texture, a figure with a texture only, the box art and the logo.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import struct
from pathlib import Path

COLLECTION_ID = "e2e_fixture"
COLLECTION_NAME = "E2E Custom Collection"
# The in-world step that photographs its wall
STEP = "collection_" + COLLECTION_ID
CACHE_DIRECTORY = "blockpops-cache"
LEGACY_SETTINGS = Path("world") / "data" / "blockpops_world.dat"
MANIFEST = "manifest.json"

RESOURCES = Path("common/src/main/resources")
COLLECTIONS = RESOURCES / "data" / "blockpops" / "collections"
DEFAULT_MODEL = "blockpops:geo/figure/box_figure_default.geo.json"
# The bundled figures it borrows, in wall order, and the collection whose box art and logo it takes
FIGURES = (("dragonballz", "goku"), ("onepiece", "monkey_d_luffy"))
ART = "dragonballz"


def _resource(repository: Path, resource_id: str) -> bytes:
    namespace, path = resource_id.split(":", 1)
    return (repository / RESOURCES / "assets" / namespace / path).read_bytes()


def _record(repository: Path, collection: str) -> dict:
    return json.loads((repository / COLLECTIONS / f"{collection}.json").read_text(encoding="utf-8"))


def figures() -> list[str]:
    """The fixture's figure ids, in the order its wall stands them."""

    return [figure for _, figure in FIGURES]


def build(repository: Path) -> dict[str, bytes]:
    """Every file of the collection by its path in the CDN layout, the manifest included."""

    art = _record(repository, ART)
    box = f"assets/blockpops/textures/block/box/{COLLECTION_ID}.png"
    logo = f"assets/blockpops/textures/block/box/logo/logo_{COLLECTION_ID}.png"
    files = {box: _resource(repository, art["box_texture"]),
             logo: _resource(repository, art["logo"]["texture"])}
    definition = {
        "id": COLLECTION_ID,
        "name": COLLECTION_NAME,
        "author": "The Plum Team",
        "author_url": "",
        "box_texture": "blockpops:" + box.removeprefix("assets/blockpops/"),
        "logo": {"texture": "blockpops:" + logo.removeprefix("assets/blockpops/")},
        "background_color": art["background_color"],
        "figures": [],
    }
    for source, figure_id in FIGURES:
        figure = dict(next(entry for entry in _record(repository, source)["figures"] if entry["id"] == figure_id))
        figure.pop("alternatives", None)
        texture = f"assets/blockpops/textures/block/figure/{COLLECTION_ID}/{figure_id}.png"
        files[texture] = _resource(repository, figure["texture"])
        figure["texture"] = "blockpops:" + texture.removeprefix("assets/blockpops/")
        if figure["model"] != DEFAULT_MODEL:
            files[f"assets/blockpops/geckolib/models/figure/{COLLECTION_ID}/{figure_id}.geo.json"] = (
                _resource(repository, figure["model"]))
            figure["model"] = f"blockpops:geo/figure/{COLLECTION_ID}/{figure_id}.geo.json"
        if "pose_animation" in figure:
            poses = f"figure_poses_{COLLECTION_ID}_{figure_id}"
            files[f"assets/blockpops/geckolib/animations/figure/{poses}.animation.json"] = (
                _resource(repository, figure["pose_animation"]))
            figure["pose_animation"] = f"blockpops:animations/figure/{poses}.animation.json"
        definition["figures"].append(figure)
    files[f"data/blockpops/collections/{COLLECTION_ID}.json"] = (
        json.dumps(definition, indent=2).encode("utf-8") + b"\n")
    manifest = {
        "version": 1,
        "collections": [{"id": COLLECTION_ID, "name": COLLECTION_NAME}],
        "files": [{"path": path, "sha256": hashlib.sha256(data).hexdigest()}
                  for path, data in sorted(files.items())],
    }
    files[MANIFEST] = json.dumps(manifest, indent=2).encode("utf-8") + b"\n"
    return files


def _name(text: str) -> bytes:
    encoded = text.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def legacy_settings() -> bytes:
    """1.5.0's gzipped saved data enabling the collection: {data: {EnabledRemoteCollections: {<id>: 1b}}}."""

    end, byte, compound = b"\x00", b"\x01", b"\x0a"
    enabled = byte + _name(COLLECTION_ID) + b"\x01" + end
    data = compound + _name("EnabledRemoteCollections") + enabled + end
    root = compound + _name("") + compound + _name("data") + data + end
    return gzip.compress(root, mtime=0)


def install(repository: Path, server: Path, clients: list[Path]) -> None:
    """Fills every game directory's verified cache and enables the collection in the server's world."""

    files = build(repository)
    for game_directory in (server, *clients):
        cache = game_directory / CACHE_DIRECTORY
        for path, data in files.items():
            target = cache / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    settings = server / LEGACY_SETTINGS
    settings.parent.mkdir(parents=True, exist_ok=True)
    settings.write_bytes(legacy_settings())
