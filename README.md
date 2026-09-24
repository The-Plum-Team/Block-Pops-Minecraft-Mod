# BlockPops

BlockPops is an Architectury Minecraft mod for collectible figures, animated
blocks, and interactive claw-machine gameplay.

The repository has two release lines. Each branch owns its exact Minecraft,
loader, Java, dependency, artifact, and packaged-runtime inventory through
`release/release-matrix.json`:

- `master`: Minecraft 1.20.1 to 26.3 across 20 lanes - Forge and Fabric on
  1.20.1, NeoForge and Fabric from 1.21.1 - on Java 17, 21 and 25 by era.
- `1.21.1-neoforge-fabric`: Minecraft 1.21.1, Fabric and NeoForge, Java 21.

Do not add version or loader lists to workflows or helper scripts. Add or
change a lane in that release branch's matrix, then make every consumer derive
from it.

## Local verification

```bash
python3 scripts/release/matrix.py
python3 -m unittest discover -s tests -v
python3 -m unittest discover -s scripts/ci/tests -t . -v
# or both suites across every core, as the Build gate runs them:
python3 scripts/ci/parallel_unittest.py -t . scripts/ci/tests tests
python3 scripts/ci/dependency_policy.py --metadata gradle/verification-metadata.xml
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
or release-controller code. The advisory Claude routing, durable queue, cost
limits, Claude Code token, and recovery procedures are documented in
[the visual-review architecture](docs/visual-review.md).

## License

**All rights reserved** — the source is public, but this is not open source.

You are welcome to read the code, build it for your own use, and fork it to
send pull requests back here. You may not reupload, mirror or redistribute the
mod, publish modified versions of it, or reuse the code in another project.

The only official downloads are [Modrinth](https://modrinth.com/mod/block-pops),
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/block-pops) and this
repository. Anything else is a reupload.

The characters, names and logos in the bundled figure collections belong to
their respective owners and are not covered by this license.

Need permission for something not covered here? Ask — reasonable requests are
usually granted. Full terms in [LICENSE](LICENSE).
