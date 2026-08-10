# Packaged E2E and visual evidence

## Contracted behavior

`e2e/scenario-contract.json` is the only scenario/capture authority. The
`ui-regression` flow runs in every matrix lane:

1. A fresh player joins the real dedicated server and receives the production
   first-join favorite-color prompt.
2. The harness selects purple and presses the real Done control. The test waits
   for server persistence and the synchronized `world_players` figure.
3. The client uses Minecraft's real block-interaction path on a datapack-created
   claw machine and validates the production collection screen.
4. It opens the real Settings link and asserts packaged server settings are
   present while the development-only Develop tab is absent.

Each step asserts production class/container identity and records a semantic
capture. Screenshots are exactly 1600×900 at GUI scale 2. Generic image checks,
BlockPops-specific text/background probes, and localized change comparisons are
deterministic.

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

Protected `master` / `fabric-1.20.1` is the canonical baseline. Current-head
baseline evidence must have the same contract SHA and exactly one frame for
every candidate `capture_id`. Pairing rejects missing, duplicate, stale, mixed,
or incompatible-aspect evidence before any model credential is available.

Images are decoded and rewritten as metadata-free RGB PNGs. Same-aspect size
drift is normalized with LANCZOS; aspect-ratio drift fails. The single-use
capsule contains only its exact manifest and content-addressed paired images.

The provider receives a read-only, path-scoped image/manifest surface and emits
JSON to stdout. Protected code rejects missing, duplicate, extra, incoherent,
or unbounded verdicts and publishes only normalized output. Raw provider output
is never an artifact.
