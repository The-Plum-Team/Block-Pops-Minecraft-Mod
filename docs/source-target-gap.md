# Quick Skin to BlockPops gap matrix

Initial gate research was pinned to the read-only Quick Skin Git objects at
`4ae9d4f585886a436fc9bc65930e13541611086d`; the portability correction was
verified at `4a1d407bee640fe5c0decacea22502924b759cad`. The current durable Claude queue,
lossless-anchor, and workload-identity architecture was re-verified from an
immutable read-only snapshot at `4c34229017cb27ad982b0320d66473419e6d2013`.
The reference checkout was never switched or modified.

The "Public evidence" row records the original port. BlockPops has since delegated public evidence
to the shared mod-base kit; see
[ADR 0001](architecture/decisions/0001-adopt-mod-base-public-evidence.md).

| Concern | Reference evidence | BlockPops decision and target |
|---|---|---|
| Release authority | `release/release-matrix.json`, `scripts/release/matrix.py` | Reuse the single-authority concept. Adapt schema to exact opaque `branch` identity and BlockPops' two branch-local Architectury layouts in `release/release-matrix.json` and `scripts/release/matrix.py`. |
| Version routing | `settings.gradle.kts`, Stonecutter nodes and overlays | Stonecutter is not applicable today: each BlockPops release branch is already a single-version Groovy multi-project. `settings.gradle`, `build.gradle`, and `source_routing` derive the active Fabric plus Forge/NeoForge modules without creating version subprojects. |
| Branch discovery | `scripts/release/version_branches.py` loader-first name regex | Adapt, do not copy. `scripts/release/version_branches.py` enumerates refs and enrolls only a strict matrix whose exact identity matches the API ref; copied feature matrices are inert. |
| Synchronization | `.github/workflows/sync-version-branches.yml`, `scripts/ci/version_port_merge.py` | Reuse exact source/target/tree binding and matrix retention. BlockPops uses opaque target digests and fails closed on unknown target-specific conflicts; it does not expose production conflicts to the visual-review model. |
| Exact-head gates | `.github/workflows/build-gate.yml`, `.github/workflows/on-demand-e2e.yml`, `.github/workflows/handle-version-port-result.yml` | Reuse explicit dispatch and post-merge attestation. Correct the historical-success behavior: strict newest `(created_at, run_id)` and current `run_attempt` wins. Release bridge contexts are distinct from incidental PR checks. |
| Production artifacts | `scripts/release/artifact_manifest.py`, Gradle reproducibility configuration | Adapt metadata and Groovy tasks to `:fabric:remapJar`, `:forge:remapJar`, and `:neoforge:remapJar`. `scripts/release/artifact_manifest.py` binds exact remapped production and separate harness bytes. |
| Separate harness | `gradle/e2e-harness-conventions.gradle.kts` and loader E2E source sets | Adapt to `gradle/e2e-harness-conventions.gradle` and branch-local loader entrypoints. Production and harness ZIP inventories are mutually exclusive. |
| Runtime/bootstrap integrity | `e2e/packaged_runtime.py`, `e2e/runtime_store.py`, `e2e/loader-bootstrap-contract.json`, `scripts/ci/e2e_job_graph.py` | Reuse content-addressed installation/store protections and adapt the bootstrap allowlist without Quick Skin's hard-coded branch inventory. Installer URLs, full runtime Maven coordinates, mod version, and active loaders live only in the branch matrix. The protected contract pins one generic build script plus the exact E2E entrypoint/resource tree per loader; Build, E2E, the protected sync handler, and post-merge attestation validate it at the exact commit. |
| Scenario semantics | Quick Skin skin/cape client scenarios in `e2e/scenario-contract.json` | Replace completely with BlockPops' organic first-join favorite-color round trip, real claw-machine block interaction, collection screen, and Settings modal. Development-only screens are explicitly excluded. |
| Visual baseline | `e2e/visual_evidence.py`, `e2e/visual_review.py` | The reference lane is applicable: protected `master` / `fabric-1.20.1`. Candidate loaders/versions pair 1:1 by semantic `capture_id`; dimension/aspect/provenance errors fail before AI. |
| AI boundary | `.github/workflows/visual-review.yml`, `.github/workflows/visual-review-drain.yml`, protected direct-provider client | Reuse the secretless curator/credential split but adapt it to BlockPops' exact PR merge identity and direct Anthropic Messages API. A globally serialized FIFO drain combines a data-only queue item with fresh protected code and prompts, exchanges GitHub OIDC for short-lived `workspace:inference`, exposes no tools or GitHub write permission, and publishes only independently normalized schema-bound output. The credential runner's read token is scoped to a final stdlib identity preflight and removed before provider use. Exact-identical pairs cost nothing; Sonnet triages changed pairs and Fable sees only ambiguous/anomalous pairs. AI remains advisory. |
| Public evidence | `.github/workflows/pages.yml`, Pages build/rotation scripts, `scripts/pages/visual_anchor.py` | Reuse current-head provenance, compact derivatives, atomic Pages deployment, one rolling cache per matrix-enrolled branch, and post-success exact-ID rotation. Add a separate 90-day current canonical anchor containing original content-addressed lossless PNG bytes for authenticated AI pairing; compact WebP is never an AI baseline. Opaque branch digests replace name-shaped artifact keys. |
| Security tests | `scripts/ci/tests/test_workflow_security.py`, matrix/runtime/visual mutation suites | Adapt expectations to each fixture matrix. `tests/test_release_matrix_portability.py` proves an arbitrary-name Minecraft 1.21.1 Fabric+NeoForge/Java 21 branch without default-branch lane assumptions. |

Quick Skin's skin/cape domain model, loader-first branch naming, Kotlin Gradle
paths, Stonecutter graph, Claude Code subscription flow, and optional
AI-generated functional repairs are not copied. BlockPops keeps repair disabled
until it has a separately governed, credential-free patch-validation and writer
boundary appropriate to its branch-local loader metadata.
