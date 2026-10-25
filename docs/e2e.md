# Packaged E2E and visual evidence

## Contracted behavior

`e2e/scenario-contract.json` is the only scenario/capture authority. Two
scenarios run in every matrix lane. The `ui-regression` flow:

1. A fresh player joins the real dedicated server and receives the production
   first-join favorite-color prompt.
2. The harness selects purple and presses the real Done control. The test waits
   for server persistence and the synchronized `world_players` figure.
3. The client uses Minecraft's real block-interaction path on a datapack-created
   claw machine and validates the production collection screen.
4. It presses the real Plushes tab, which selects the first plush collection,
   and waits until the server has stored that selection on the claw machine.
5. It opens the real Settings link and asserts packaged server settings are
   present while the development-only Develop tab is absent.

The `in-world` flow shows the mod as a player meets it in the world:

1. After dismissing the first-join prompt, the player opens the claw machine and
   presses the real Use Regular Token control; the server's `DropBoxPacket`
   delivers a box with a drawn One Piece figure. The screen plays the draw out
   and must show that same figure before the harness closes it; the box is then
   captured in hand.
2. The held box is placed through the real block-placement packet and captured
   from the front, the side and above.
3. A claw machine is put in hand through the creative-inventory packet and
   captured in first and third person.
4. The test data pack builds one wall of real box blocks per collection (every
   figure, rows of nine; see `showcase_function` in `e2e/packaged_runtime.py`)
   and the harness photographs each wall the contract names, asserting every
   box's figure. A new collection therefore lands in two pull requests: an
   ordinary one packages it (its wall is built but not yet photographed), then
   a controller upgrade adds its `collection_<id>` step to
   `e2e/scenario-contract.json`.
5. The last wall is a Custom collection, `e2e_fixture`, with no CDN
   (`e2e/custom_fixture.py`). The game never reaches the CDN in the packaged
   E2E: `RemoteSource.forGame()` is `RemoteSource.NONE` there. Before the game
   starts, the runtime fills the verified cache (`blockpops-cache/`) of the
   server and of the client with the collection, in the CDN layout and with its
   manifest. It enables the collection in the world through 1.5.0's
   `data/blockpops_world.dat`, which the server imports on its first start.
   - The collection borrows bundled resources under its own id: Goku with his
     own model, poses and texture, Luffy with a texture on the default model,
     the Dragon Ball Z box art and logo.
   - Its wall stands one empty wall's place after the bundled ones, so the
     short wall's neighbour stays out of its picture.
   - The harness waits until the server has synced the collection and every
     file it names has loaded, then photographs its wall.
   - It only runs in a scenario whose contract names the
     `collection_e2e_fixture` step.

   The pure core under it (`com.theplumteam.remote`) also has an opt-in
   repository test, `tests/test_remote_core.py`. It needs a JDK and a Gson
   JAR, and feeds the core hostile JSON, paths, digests, sizes, PNGs and
   manifests.
6. In the next wall's place stand a World Players box and figure block of
   Carol, a player who played on the server and is not connected
   (`e2e/world_players_fixture.py`). They carry the skin a server stores in
   what it hands out, so drawing them takes the stored-skin path, through
   BlockPops' skin registration from Minecraft 1.21 on.
   - Before the server starts, the runtime gives it her player file and her
     name in `usercache.json`, so the World Players collection lists her
     without her joining.
   - Her skin link names `textures.minecraft.net`, as Mojang's do. The runtime
     puts the image in the clients' skin cache, `assets/skins/`, under the name
     the game gives that link, so the game reads it from disk and downloads
     nothing. Only 26.3 first asks Mojang's discovery service, which the game
     reads as it starts, whether that host serves skins: that lane needs the
     service reachable, as the game does for every skin.
   - The harness asserts that the collection lists her, that both blocks hold
     the same stored skin, and that the box's own model draws exactly the
     cached texture that skin names. Then it photographs both.
   - It only runs in a scenario whose contract names the
     `world_players_offline` step.

Only the packaged E2E sets `blockpops.e2e.enabled` on the client and server. With
it the star background and the GeckoLib idle animations hold one frame, the
claw-machine draw is seeded, and toasts are cleared before each capture, so an
unchanged view is byte-identical from run to run.

Each step asserts production class/container identity and records a semantic
capture. Screenshots are exactly 1600×900 at GUI scale 2, which is also the scale
the menus choose for themselves at that size ([`docs/ui.md`](ui.md)): an 800×450
canvas. Generic image checks, BlockPops-specific text/background probes, and
localized change comparisons are deterministic.

The protected fan-in fully decodes every PNG and independently recomputes pixel
hashes, blank-frame metrics, and each contracted regional comparison. A client
report cannot make the deterministic gate pass by declaring fabricated image
metrics. Runtime downloads are HTTPS-only, SHA-256 pinned, streamed into fresh
exclusive files, and capped per blob before admission to the immutable store.

## Local commands

List matrix-derived work without launching Minecraft:

```bash
python3 -m e2e.orchestrator --list
```

After building and staging artifacts, run one packaged lane on a machine with a
usable display/OpenGL stack:

```bash
python3 -m pip install --only-binary=:all: --require-hashes -r e2e/requirements.txt
xvfb-run -a python3 -m e2e.orchestrator \
  --packaged \
  --artifact-node fabric-1.20.1 \
  --scenarios ui-regression
```

CI on Ubuntu with Xvfb and Mesa is authoritative when a local macOS/Windows
display or the required JDK is unavailable.
The protected runtime action installs Mesa EGL and passes `SDL_VIDEO_FORCE_EGL=1`
explicitly into the credentialless account. Minecraft 26.3's SDL3 backend can then
create its required sRGB OpenGL window under Xvfb; older GLFW clients ignore the
hint. This selects a real Mesa context without overriding reported capabilities.

## Visual comparison

Protected `master` / `fabric-1.20.1` is the canonical baseline. Its current-head
anchor, `mb-anchor--<branch-token>--<commit>--<run-id>--a<run-attempt>` from the
pinned mod-base kit, retains the original lossless PNG bytes for 90 days with
the producer run and attempt in both its immutable artifact name and manifest,
plus exact commit/tree, matrix/contract, semantic frame, dimensions, file hash
and pixel hash provenance. Current-head baseline evidence must have the same
contract SHA and exactly one frame for every candidate `capture_id`. Pairing
rejects missing, duplicate, stale, mixed, or incompatible evidence before any
model credential is available.

Images are fully decoded and rewritten as metadata-free RGB PNGs. Every frame
must already have the contract's exact 1600x900 reference dimensions; any size
or aspect skew fails before curation. The single-use capsule contains only its
exact manifest and content-addressed paired images.

Exact canonical PNG equality is the only automatic visual acceptance. Integer
pixel metrics prioritize changed pairs but never make a semantic decision.
Changed pairs are grouped under a strict request/call budget: Claude Opus 5.5
triages first and only anomalous or uncertain pairs reach an independent Opus 5.5
verification.

The model receives only the Read tool, allowed for exactly the images it is
reviewing, and no repository checkout or GitHub permission. Protected code
rejects missing, duplicate, extra, incoherent, or unbounded structured verdicts
and publishes only normalized output. Raw CLI output is never an artifact. The
fixed `visual-review` environment runs a hash-pinned Claude Code CLI with the
owner's subscription token, like Quick Skin. This result remains advisory
regardless of severity. See [the visual-review architecture](visual-review.md)
for queue, cost, retention, token and recovery details.

## Public gallery

After a successful scheduled or dispatched run on `master`, the `public-evidence`
job revalidates the exact aggregate and hands it to the pinned mod-base kit as
`mb-handoff--<branch-token>--a<run-attempt>`; `notify-pages.yml` then wakes the
`Project site` workflow, which publishes the gallery under `/e2e/`. Every
published frame keeps its passed assertion message, source and derivative pixel
metrics, lane, production JAR digest and run provenance. The gallery is
advisory; see [the public-evidence notes](../scripts/pages/README.md) and
[ADR 0001](architecture/decisions/0001-adopt-mod-base-public-evidence.md).
