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
4. It opens the real Settings link and asserts packaged server settings are
   present while the development-only Develop tab is absent.

The `in-world` flow shows the mod as a player meets it in the world:

1. After dismissing the first-join prompt, the player opens the claw machine and
   presses the real Use Regular Token control; the server's `DropBoxPacket`
   delivers a box with a drawn One Piece figure, captured in hand.
2. The held box is placed through the real block-placement packet and captured
   from the front, the side and above.
3. A claw machine is put in hand through the creative-inventory packet and
   captured in first and third person.
4. The test data pack builds one wall of real box blocks per collection (every
   figure, rows of nine; see `showcase_function` in `e2e/packaged_runtime.py`)
   and the harness photographs each wall, asserting every box's figure.

Only the packaged E2E sets `blockpops.e2e.enabled` on the client and server. With
it the star background and the GeckoLib idle animations hold one frame, the
claw-machine draw is seeded, and toasts are cleared before each capture, so an
unchanged view is byte-identical from run to run.

Each step asserts production class/container identity and records a semantic
capture. Screenshots are exactly 1600×900 at GUI scale 2. Generic image checks,
BlockPops-specific text/background probes, and localized change comparisons are
deterministic.

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

## Visual comparison

Protected `master` / `fabric-1.20.1` is the canonical baseline. Its current-head
anchor retains the original lossless PNG bytes for 90 days with the producer
run and attempt in both its immutable artifact name and manifest, plus exact
commit/tree, matrix/contract, semantic frame, dimensions, file hash
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
