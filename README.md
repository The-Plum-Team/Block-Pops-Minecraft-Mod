# BlockPops

BlockPops is an Architectury Minecraft mod for collectible figures, animated
blocks, and interactive claw-machine gameplay.

The repository has two release lines. Each branch owns its exact Minecraft,
loader, Java, dependency, artifact, and packaged-runtime inventory through
`release/release-matrix.json`:

- `master`: Minecraft 1.20.1, Fabric and Forge, Java 17.
- `1.21.1-neoforge-fabric`: Minecraft 1.21.1, Fabric and NeoForge, Java 21.

Do not add version or loader lists to workflows or helper scripts. Add or
change a lane in that release branch's matrix, then make every consumer derive
from it.

## Local verification

```bash
python3 scripts/release/matrix.py
python3 -m unittest discover -s tests -v
./gradlew --no-daemon --no-parallel clean buildAllLanes buildAllE2EHarnesses
python3 scripts/release/verify_release.py \
  --matrix release/release-matrix.json \
  --manifest build/release/artifacts.json \
  --stage build/release
```

The normal Gradle build creates real remapped production JARs and physically
separate client-only E2E harness JARs. Packaged E2E installs those exact bytes
into genuine loader clients and dedicated servers; it never substitutes a Loom
development launch.

See [CONTRIBUTING.md](CONTRIBUTING.md),
[the release architecture](docs/release-architecture.md), and
[the E2E contract](docs/e2e.md) before changing build, runtime, UI, networking,
or release-controller code.
