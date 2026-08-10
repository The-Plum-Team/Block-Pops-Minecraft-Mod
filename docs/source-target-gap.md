# Quick Skin to BlockPops gap matrix

Research was pinned to the read-only Quick Skin Git objects at
`4ae9d4f585886a436fc9bc65930e13541611086d`; the later portability correction
was verified at `4a1d407bee640fe5c0decacea22502924b759cad`. The reference checkout was not
switched or modified.

| Concern | Reference evidence | BlockPops decision and target |
|---|---|---|
| Release authority | `release/release-matrix.json`, `scripts/release/matrix.py` | Reuse the single-authority concept. Adapt schema to exact opaque `branch` identity and BlockPops' two branch-local Architectury layouts in `release/release-matrix.json` and `scripts/release/matrix.py`. |
| Version routing | `settings.gradle.kts`, Stonecutter nodes and overlays | Stonecutter is not applicable today: each BlockPops release branch is already a single-version Groovy multi-project. `settings.gradle`, `build.gradle`, and `source_routing` derive the active Fabric plus Forge/NeoForge modules without creating version subprojects. |
| Branch discovery | `scripts/release/version_branches.py` loader-first name regex | Adapt, do not copy. `scripts/release/version_branches.py` enumerates refs and enrolls only a strict matrix whose exact identity matches the API ref; copied feature matrices are inert. |
| Synchronization | `.github/workflows/sync-version-branches.yml`, `scripts/ci/version_port_merge.py` | Reuse exact source/target/tree binding and matrix retention. BlockPops uses opaque target digests and fails closed on unknown target-specific conflicts; it does not expose production conflicts to the visual-review model. |
| Exact-head gates | `.github/workflows/build-gate.yml`, `.github/workflows/on-demand-e2e.yml`, `.github/workflows/handle-version-port-result.yml` | Reuse explicit dispatch and post-merge attestation. Correct the historical-success behavior: strict newest `(created_at, run_id)` and current `run_attempt` wins. Release bridge contexts are distinct from incidental PR checks. |
| Production artifacts | `scripts/release/artifact_manifest.py`, Gradle reproducibility configuration | Adapt metadata and Groovy tasks to `:fabric:remapJar`, `:forge:remapJar`, and `:neoforge:remapJar`. `scripts/release/artifact_manifest.py` binds exact remapped production and separate harness bytes. |
| Separate harness | `gradle/e2e-harness-conventions.gradle.kts` and loader E2E source sets | Adapt to `gradle/e2e-harness-conventions.gradle` and branch-local loader entrypoints. Production and harness ZIP inventories are mutually exclusive. |
| Runtime bootstrap | `e2e/packaged_runtime.py`, `e2e/runtime_store.py`, `e2e/loader-bootstrap-contract.json` | Reuse content-addressed installation/store protections. Installer URLs/hashes and runtime Maven coordinates live in the release matrix instead of a duplicated loader inventory; artifact hashes come from strict Gradle verification metadata. |
| Scenario semantics | Quick Skin skin/cape client scenarios in `e2e/scenario-contract.json` | Replace completely with BlockPops' organic first-join favorite-color round trip, real claw-machine block interaction, collection screen, and Settings modal. Development-only screens are explicitly excluded. |
| Visual baseline | `e2e/visual_evidence.py`, `e2e/visual_review.py` | The reference lane is applicable: protected `master` / `fabric-1.20.1`. Candidate loaders/versions pair 1:1 by semantic `capture_id`; dimension/aspect/provenance errors fail before AI. |
| AI boundary | `.github/workflows/visual-review.yml`, `.github/claude/` | Reuse secretless authentication/curation, bounded single-use capsule, fresh credential runner, read-only provider surface, protected normalization, and exact artifact-ID cleanup. AI remains advisory. |
| Public evidence | `.github/workflows/pages.yml`, Pages build/rotation scripts | Reuse current-head provenance, compact derivatives, atomic Pages deployment, one rolling cache per matrix-enrolled branch, and post-success exact-ID rotation. Opaque branch digests replace name-shaped artifact keys. |
| Security tests | `scripts/ci/tests/test_workflow_security.py`, matrix/runtime/visual mutation suites | Adapt expectations to each fixture matrix. `tests/test_release_matrix_portability.py` proves an arbitrary-name Minecraft 1.21.1 Fabric+NeoForge/Java 21 branch without default-branch lane assumptions. |

Quick Skin's skin/cape domain model, loader-first branch naming, Kotlin Gradle
paths, Stonecutter graph, and optional AI merge-conflict repair are not copied.
