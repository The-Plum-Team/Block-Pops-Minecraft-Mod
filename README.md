# BlockPops

BlockPops is an Architectury Minecraft mod for collectible figures, animated
blocks, and interactive claw-machine gameplay.

`master` owns the canonical source and its complete Minecraft, loader, Java,
dependency, artifact and packaged-runtime inventory in
[`release/release-matrix.json`](release/release-matrix.json). Historical release
branches retain their own branch-local matrices. Derive supported targets from
the relevant matrix rather than maintaining another version list.

The integration matrix's `migration.mode` controls mandatory gate coverage:
`preparing` retains the legacy projection; `shared` verifies every configured
target. The matrix validator and build plan show the selected inventory.

## Local verification

Set `BUILD_SCOPE` to `legacy` for a `preparing` matrix or `full` for a `shared`
matrix. Set `JDK25_HOME`, `JDK21_HOME` and `JDK17_HOME` to the installed JDK homes
required by its build plan. Use a POSIX host for scoped resource generation and
artifact staging.

```bash
python3 scripts/release/matrix.py
python3 -m unittest discover -s tests -v
python3 -m unittest discover -s scripts/ci/tests -t . -v
# or both suites across every core, as the Build gate runs them:
python3 scripts/ci/parallel_unittest.py -t . scripts/ci/tests tests
python3 scripts/ci/dependency_policy.py --metadata gradle/verification-metadata.xml
python3 scripts/ci/mod_base_kit.py verify --network
python3 scripts/ci/mod_base_kit.py run template check --repo .
python3 -B scripts/release/build_matrix.py --plan --scope "$BUILD_SCOPE"
python3 -B scripts/release/build_matrix.py --scope "$BUILD_SCOPE" \
  --java-home "$JDK25_HOME" --java17-home "$JDK17_HOME" \
  --java21-home "$JDK21_HOME" --clean --discard-gradle-homes
python3 scripts/release/verify_release.py \
  --matrix release/release-matrix.json --scope "$BUILD_SCOPE" \
  --manifest build/release/artifacts.json \
  --stage build/release
python3 scripts/release/verify_release.py --scope "$BUILD_SCOPE" \
  --manifest build/release/artifacts.json --stage build/release --verify-staged
```

The serial build creates real remapped production JARs and physically
separate client-only E2E harness JARs. Packaged E2E installs those exact bytes
into genuine loader clients and dedicated servers; it never substitutes a Loom
development launch.

See [CONTRIBUTING.md](CONTRIBUTING.md),
[the release architecture](docs/release-architecture.md), and
[the E2E contract](docs/e2e.md) before changing build, runtime, UI, networking,
or release-controller code. The advisory Claude routing, durable queue, cost
limits, Claude Code token, and recovery procedures are documented in
[the visual-review architecture](docs/visual-review.md).

The advisory public gallery of packaged screenshots is published to GitHub
Pages by the pinned [mod-base](https://github.com/The-Plum-Team/mod-base) kit;
see [the public-evidence notes](scripts/pages/README.md).

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
