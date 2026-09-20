# Design — Consolidate twelve lanes without weakening the delivery boundary

## Decision and readiness

Keep the existing canonical `common`, `fabric`, and `forge` source roots; add `neoforge`, detached Stonecutter version nodes, and only demonstrated compatibility overlays. Evolve the matrix through dual-schema readers and an explicitly partial migration state. Build one selected lane per Gradle subprocess, with separate production/harness artifacts and matrix-derived qualification. Preserve historical release matrices and synchronization until the replacement is qualified and retirement is authorized.

**Design complete; local implementation is authorized, but delivery is not.** Unpublished code, tests, and configuration may proceed on the existing feature branch under the user's migration and conventional work-unit commit authorization. AUTH-1 remains unresolved for initial foundation admission and every later published restricted transition. Dependency/plugin qualification and compatible local Java also remain unresolved. No implementation, tests, builds, branch changes, commits, pushes, or PRs were performed in this planning correction.

Scope is explicitly BlockPops (not `packages/coding-agent`). The twelve selected targets, auto/OpenSpec/auto-chain, 400 changed-line budget, and `stacked-to-main` are already decided. Integration means `master`, not a branch rename. Local work-unit commits do not authorize pushes, PR creation, merges, publication, protection changes, or release. Strict TDD consent remains unconfirmed: use proportional test-first checks on runnable tooling and packaged acceptance for Minecraft behavior.

## Evidence and existing seams

Inputs read directly: `openspec/config.yaml`, this change's exploration/proposal and all three specifications; `CONTRIBUTING.md`; `docs/operations.md`, `docs/release-architecture.md`, `docs/e2e.md`; current settings/build/loader conventions, matrix validator, artifact staging, E2E selection, repository/bootstrap policies, trusted-gate code, and targeted production/harness sources.

| Existing seam | Consequence for this design |
|---|---|
| `settings.gradle` and `scripts/release/matrix.py` accept schema 1 only | Install compatible readers before changing the live matrix. Unknown schemas remain errors. |
| `build.gradle` requires one Minecraft/Java era and one FML family | Do not remove the guards and configure every lane together; isolate the selected context. |
| `common/build.gradle` chooses GeckoLib through the active FML family | Common compilation must use selected-lane dependencies, not a global first row. |
| Harness convention uses `project.name` and first matching loader row | Version nodes must receive an explicit lane record and exact common-project path. |
| Artifact manifest schema 2 owns one global mod version and all active rows | Add a new manifest schema and scoped verification before independent lane staging. |
| E2E `--row-json` must exactly equal a protected matrix projection | Preserve exact equality; do not accept caller-supplied runtime overrides. |
| Dependency policy hashes settings/plugin-management bytes and repository policy | Stonecutter needs deliberately reviewed validator, repository, and checksum changes, not a plugin-only edit. |
| Loader bootstrap validation authenticates base-contract bytes | Use the documented two-generation current/next transition, never mechanical digest refresh. |

Quick-Skin-Mod was read only at `settings.gradle.kts`, `stonecutter.gradle.kts`, and `scripts/release/build_matrix.py`. Reuse its detached-source and serial-process concepts, not its module graph, 34-lane scope, no-remap modernization, or domain implementation. Stonecutter 0.9.8 is the initial compatibility candidate from that reference, not a proven compatible dependency. Keep Gradle 8.14 and current BlockPops plugin versions initially; a necessary incompatibility must produce specific evidence and a bounded dependency decision, not an unrequested upgrade sweep.

## 1. AUTH-1 governs published admission, not authorized local development

The live repository does not contain the proposed controller foundation at exact default-branch SHA `35c72713ccdb94205df4bc5cbafcd5264dd64377`. Therefore the feature tree's controller-upgrade design cannot authorize its own initial deployment. Respect these distinct boundaries:

| Surface/action | Current route and limit |
|---|---|
| Local migration code, tests, fixtures, configuration, and documentation on the existing feature branch | Authorized to develop and commit as unpublished work. Preserve candidate trust protections and label results as local readiness only. |
| Initial controller foundation | May be implemented and tested locally. Its exact reviewed commit requires future explicit human delivery authority plus a fresh independent governance recheck; it cannot use candidate policy to admit itself. |
| Restricted matrix, verification metadata, version-specific shims, Stonecutter admission, and NeoForge bootstrap | May be prepared locally under the migration authorization. Published admission waits for the foundation to be deployed and observed active, followed by a fresh authenticated exact-head decision under that deployed generation. |
| Push, PR creation, merge, default-branch update, publication, release, or protection/ruleset change | Not authorized by this design, local commits, `ADMIN` permission, or AUTH-1 planning. No automatic direct push/PR and no reusable bypass exist. |

**Current-state note:** authenticated read-only evidence reports private repository `AkaNebur/BlockPops`, viewer permission `ADMIN`, default branch `master`, and live `master` at `35c72713ccdb94205df4bc5cbafcd5264dd64377`. The branch endpoint reports `protected: false` with embedded required-status enforcement off, but dedicated protection and ruleset queries return HTTP 403 upgrade-required, so complete absence of governance rules is not proven. The exact untruncated tree at that SHA lacks `.github/*`, `scripts/ci/pr_gate.py`, `CONTRIBUTING.md`, and `docs/operations.md`; exact-SHA content queries corroborate those absences. Actions metadata names Build gate and Packaged E2E, but it does not prove workflow YAML is deployed at that SHA. Feature commit `a200152` is candidate foundation, not live authority.

**Delivery gate AUTH-1:** follow the [draft authorization procedure proposal](authorization-plan.md). First prepare and review a minimal foundation locally. Later, a human must explicitly authorize the exact foundation commit and delivery action after fresh governance inspection; admission must not depend on the candidate evaluator. Only after independent observation proves that foundation active may future controller-governed restricted transitions use authenticated base/controller generation, exact-head decisions, and required evidence. The draft remains **DRAFT / NOT DELIVERY AUTHORIZATION**.

Task 1 remains unchecked because foundation deployment and proof are pending, but it does not block authorized unpublished implementation. No repeat confirmation is needed for the twelve lanes, local migration work, conventional local work-unit commits, or chain choice. PR/publication, each delivery action, and eventual sync retirement remain separate.

## 2. Matrix contract and staged compatibility

### Schema choice

Keep `release/release-matrix.json` as the only live inventory. Extend `scripts/release/matrix.py` with exact schema dispatch: unchanged schema-1 validation for historical branch matrices, and schema-2 validation for canonical shared-source migration. Do not loosen schema 1 to accept mixed eras. The internal normalized model provides lane lookups and projections; legacy consumers receive explicit compatible views rather than pretending schema 2 is schema 1 on disk.

Schema 2 retains current project/branch/visual/installers/artifacts/runtimes conventions to avoid a large replacement. Add these bounded concepts:

| Field/model | Contract |
|---|---|
| `targets` | Exactly twelve unique `loader-minecraft` identities; this is migration-target membership, not a built/support claim. No thirteenth combination, missing lane, or duplicated pair. |
| `migration.mode` | `preparing` or `shared`; `preparing` is explicitly incomplete, never full qualification. |
| `migration.legacy_nodes` | Existing Fabric/Forge 1.20.1 execution selection during preparation, referencing target identities rather than copying configurations. Empty in shared mode. |
| `artifacts` / `runtimes` | Paired executable configurations; initially the current two rows. Every row belongs to `targets`; each configured node has exactly one row in each array. Add other rows only with complete reviewed inputs. |
| `lane_count` | Number of executable artifact/runtime rows, retaining its existing meaning. Target count is derived from `targets` and must be twelve. Reports always show both counts while preparing. |
| Artifact additions | Lane `mod_version`, `build_layout` (`legacy` or `stonecutter`), Gradle JVM, repository family, source-route references. Version expansion uses the lane value, not `project.mod_version`. |
| Source routing | Canonical main and E2E roots by module, with explicit lane-scoped overlays and replacement paths. No source existence claim for unconfigured targets. Configured rows require all routed files. |
| Qualification inputs | Minecraft/loader/installers, dependency coordinates, artifact/runtime Java, remap policy, metadata, production/harness tasks and paths remain exact reviewed values. No inferred defaults from branch names. |

The existing project mod version remains only a schema-1/legacy compatibility field until consumers stop using it; schema 2 checks it against the legacy rows during preparation. It is not the release version authority for new lanes. In shared mode, consumers must use per-lane versions exclusively. Artifact and runtime Java remain separately modeled/validated even where both equal 17 or 21.

A twelve-slot target projection exists from the first schema-2 matrix. Unconfigured slots return `unresolved` with missing-input reasons, not invented dependency coordinates or task success. Full build/qualification planning fails until all twelve configurations and routes are present. A deliberately selected configured lane can be built with a report marked partial; selection never removes targets.

Keep historical provenance and qualification in a migration ledger/report keyed by matrix target, not in a second release inventory. Historical source evidence, executable configuration, build/E2E qualification, support declaration, and publication are separate fields. A local `qualified=true` Boolean is not trusted evidence; derive qualification from verified reports. Unknown publication remains unknown even after local builds pass.

### Consumer order

1. Add v1/v2 parsing, projection, and mutation tests while leaving the live v1 matrix byte-identical. Fixtures are test data, never an alternate production authority.
2. Adapt Gradle context selection, staging, runtime selection, controller matrix parsing, and fan-in to the new normalized API. Keep v1 projections/output behavior unchanged and test arbitrary-named historical release matrices.
3. Prepare the live-matrix schema-2 change locally with all twelve target identities but only the existing two executable rows and `preparing` mode; deliver it only through the resolved AUTH-1 route. Existing trusted legacy gates remain two-lane gates, explicitly not migration completion evidence.
4. Add complete configurations and source ports in bounded slices. Preparatory new-lane runs are visibly partial. Required legacy checks still run; incomplete migration reports cannot replace them.
5. Change the two legacy artifact rows to Stonecutter only after equivalence checks. Once all twelve routes/configurations exist, switch to `shared`; production/harness/CI/E2E projections must then cover every target. No default fallback to legacy on failure.

The preparatory subset is temporary compatibility, not the final specification's coverage. Tests require the full target projection throughout and require all twelve runnable rows before shared-mode activation. A configured-but-failing lane remains in all plans. Neither a failure nor missing evidence can silently reduce the target set.

## 3. Source and Gradle layout

```text
release/release-matrix.json             # sole inventory and routing authority
settings.gradle                        # strict projection + selected graph
build.gradle                           # schema-dispatched legacy/shared conventions
stonecutter.gradle                     # detached controller; new protected root
common/src/main/{java,resources}/       # canonical gameplay and assets, retained in place
common/src/e2e/{java,resources}/        # canonical harness, retained in place
{fabric,forge,neoforge}/src/main/...    # loader entrypoints/platform code/metadata
{fabric,forge,neoforge}/src/e2e/...     # client-only bootstrap and harness metadata
<module>/src/legacy<era>/{main,e2e}/... # optional narrow, matrix-declared overlays
<module>/versions/<minecraft>/build/   # lane-local generated inputs and outputs
scripts/release/build_matrix.py         # small Python serial planner/executor
```

Node identities are `:common:<minecraft>` and `:<loader>:<minecraft>`. Production tasks are `:<loader>:<minecraft>:remapJar` and harness tasks `:<loader>:<minecraft>:remapE2EHarnessJar` for the initial remapped lanes; any no-remap exception requires lane evidence and explicit matrix values. Output paths include loader/version and resolve under that node's build directory. Archive naming retains `BlockPops - <Loader> - <Minecraft>-<lane version>.jar` and distinct `BlockPops E2E - ...-0.0.0.jar` identities.

The complete logical Stonecutter graph comes from configured matrix rows; a lane subprocess materializes only its version's common node and selected loader node. `-PblockpopsLane=<artifact_node>` selects one exact lane. Unknown, unconfigured, conflicting, or missing selection for shared compilation fails before resolving game dependencies. An inventory-only controller may inspect all descriptors but must not apply Loom/Architectury simultaneously to Forge and NeoForge nodes. Full task existence is verified by serial per-lane configuration probes, not one mixed-family Gradle build.

Use detached preprocessing (`active null` concept) so builds never rewrite tracked sources. Keep Groovy build scripts; adapt only the small Stonecutter integration to its pinned API. Compatibility of the Groovy controller/central script and generated output locations is an implementation probe, required before matrix activation. No speculative Kotlin conversion of the entire project is planned.

Production preprocessing and harness preprocessing are separate tasks and destinations. Do not assume Stonecutter automatically preprocesses `src/e2e`: explicitly register that source tree or a dedicated detached preprocessing task through the pinned API, then make E2E compilation depend on it. `generateE2EContractJava` remains a distinct generated harness input. No `main` source set may consume harness output.

For overlays, resolve canonical sources first, then replace only explicitly declared relative paths or add declared compatibility adapters. Reject undeclared shadowing, duplicate classes/resources, traversal, symlinks, and files belonging to the opposite loader. Each overlay records its exact affected lanes, incompatibility, historical source, and acceptance check. Favor small preprocessor conditionals for API syntax; extract narrow adapters for larger differences. Do not create `src/v*` snapshots or copy entire screens by version.

## 4. Reconcile historical behavior, not historical build systems

Use the pinned commits recorded in exploration as inputs; do not checkout, rewrite, delete, or merge historical branches wholesale. During apply, use read-only `git show <sha>:<path>` and path-scoped diffs before each port. This phase consumed the exploration's verified historical-ref evidence; it did not independently execute Git commands.

| Baseline | Reconciliation use |
|---|---|
| Current feature `a200152...` and live `master` `35c72713...` | Preserve the candidate security/E2E implementation and existing 1.20.1 gameplay. The feature-tree foundation is not installed on live `master`; reconcile and review it before any separately authorized foundation delivery. |
| `4f6e4039...` / `origin/1.21.1-neoforge-fabric` | Product/platform API differences for Fabric/NeoForge 1.21.1. |
| `6b5651e6...` / `origin/feat/1.21.1-multiversion-e2e` | NeoForge bootstrap and Java-21 packaged-runtime adaptation; compare against current stronger controller/harness policy. |
| `3fbf917a...` / `origin/feat/multiversion-e2e-architecture` | 1.20.1 harness lineage where needed, not a reason to overwrite newer safety code. |
| `50eb7fd1...` / `origin/feature/multi-version-stonecraft` | Targeted `src/v1_21_4` through `src/v1_21_7` API/configuration deltas. Do not import version snapshots or declare them already qualified. |

First reconcile registration/platform entrypoints and metadata; then identifiers/network payload paths; then blocks/items/data serialization; then renderer/UI API differences; then harness adapters. `ModNetworking` currently uses constructor-based `ResourceLocation` and Architectury receiver registration; `PlatformHelper` already supplies loader-specific `@ExpectPlatform` boundaries. Reuse these seams rather than inventing a new platform framework.

For every reconciled unit record source SHA/path, retained common behavior, deliberate differences, and affected-lane checks in apply evidence. Preserve registry IDs, packet semantics, saved player/world data, configuration formats, and asset identities. Historical feature additions are not implicitly in scope. The real favorite-color packet/server persistence, synchronized figure, claw-machine interaction, and packaged settings UI must remain covered. The harness must not bypass a changed production API by mutating server state or constructing a fake screen.

## 5. Toolchains, repositories, and serialization

Select Gradle JVM 21 before any build: the existing wrapper is 8.14 and current ambient Java produced class major 69. Use Java 17 compilation/runtime for 1.20.1 and Java 21 for the five 1.21 targets, subject to actual lane validation. Validate selected `JAVA_HOME`, reported Gradle JVM, available compile toolchains, and runtime launcher major separately. Do not fix an ambient-JDK mismatch by changing source language levels or upgrading Gradle blindly.

Repository-family selection is per lane: Forge uses reviewed Maven Central origins for `cpw.mods`/`org.lwjgl`; NeoForge uses the reviewed NeoForge/Minecraft origins; Fabric uses its own reviewed non-FML context. Never borrow the repository choice from the first runtime row. Isolate Gradle user homes per lane under `build/gradle-home/<artifact_node>` and keep lane build directories separate; common-node generated output for the same Minecraft version is rebuilt under the selected context rather than reused across families without matching inputs.

Keep strict dependency verification, all remote generated-namespace exclusions, and exact bindings of the four Loom file repositories to the selected cache paths. The five generated-name checksum exceptions remain bounded. Adding Stonecutter requires a narrowly filtered Kikugie plugin origin and reviewed checksum closure; prepare and test that closure locally, then use AUTH-1 for published admission. Never admit observed mapping ZIP hashes or disable checksum verification. Keep tracked `.gradle`/`buildSrc` prohibited.

`build_matrix.py` owns execution, not a Gradle task that launches Gradle recursively. Validate the whole inventory, select scope, sort by numeric Minecraft version then loader, acquire one checkout build lock, and synchronously execute each subprocess with `--no-daemon --no-parallel --max-workers=1`. Reject parallel overrides. Await process exit (including cancellation cleanup) before starting another lane. A second runner must fail or wait without spawning Gradle. Lock cleanup must not steal an active lock or kill unrelated user processes.

Each invocation receives the exact lane selector, target-local clean task when requested, matrix-owned production/harness tasks, and lane validation. Fail fast on nonzero exit, unavailable JDK, changing matrix/source identity, missing artifacts, or changed earlier outputs. Atomically replace a running/failed/success report; invalidate previous success before startup. Record scope, source commit/tree or explicitly dirty diagnostic identity, matrix digest, resolved toolchains, commands, exit status, and output hashes. Dirty diagnostic reports are never release evidence.

In shared mode, `buildAllLanes` / `buildAllE2EHarnesses` invoked without the Python runner fail with the documented serial command rather than scheduling all projects. Legacy schema-1 aggregates retain their behavior. `check` remains a meaningful validator; do not claim it supplies Java unit tests that do not exist.

CI's two workflows currently each build a bundle. Keep their independently authenticated bundle ownership rather than redesigning cross-workflow artifact reuse. Add a common repository-level non-cancelling concurrency group to their Gradle build jobs so Build and E2E builders cannot overlap; each job then uses the serial Python runner. Runtime-only E2E jobs may retain bounded parallelism because they do not invoke Gradle. Local direct lane invocations outside the runner are unsupported for aggregate qualification.

## 6. Artifact, E2E, and release contracts

### Data flow

```text
protected source identity + matrix bytes
  -> strict normalized inventory / scope / lane context
  -> serial isolated Gradle -> production JAR + harness JAR per lane
  -> verify_release -> immutable scoped manifest and exact staged files
  -> real packaged client + dedicated server + client-only matching harness
  -> protected PNG/report fan-in + newest exact-head trusted contexts
  -> per-lane qualification -> release plan (no publishing authority)
```

Introduce artifact manifest schema 3 after dual readers are deployed. Preserve schema 2 for historical v1 matrices. Schema 3 includes source commit/tree, authoritative matrix path/hash, scenario-contract hash, explicit scope (`legacy`, `lane`, `full`), selected node inventory, and per-lane mod version/toolchain/build-context identity plus production and harness hashes/sizes/paths. Full means precisely twelve; lane means one exact requested node; legacy is valid only while preparing. Verify scope against the trusted caller's expectation, not the manifest's self-assertion. Hash the authoritative matrix, never a lossy legacy projection.

Staging continues using safe bounded ZIP inspection, exclusive production/harness directories, no symlink/special entries, and exact file inventory. Add embedded lane/build-input identity to both JARs so a renamed same-loader harness cannot pass as another lane. The shared identity includes matrix/contract digest and source tree; pair it with observed archive hashes in the manifest. Reject cross-lane or stale production/harness pairs, harness namespaces/resources/entrypoints in production (including nested JARs), production classes in harnesses, and unlisted staged files. Release selectors use manifest `production` records only, never `*.jar` globs.

Preserve the clean tracked-tree provenance requirement in `artifact_manifest.git_commit()`. The pre-existing `.gitignore` and `.pi` work must not be stashed, reset, or committed merely to satisfy it. Authoritative staging belongs in an exact committed CI checkout or separately authorized clean checkout; dirty local compilation is useful diagnostic evidence only.

`e2e.orchestrator`, runtime recipes, CI projections, protected job-graph validation, and fan-in must resolve the same normalized lane identity and expected scope. Every shared-mode PR/scheduled lane runs all applicable protected contract scenarios; no path-impact pruning is introduced. Maintain exact scenario/role/step assertions, capture identities, 1600×900 scale-2 frames, bounded HTTPS/pinned downloads, client-only harness placement, and independent PNG recomputation. Version-specific datapack resources and `VanillaShim` adaptation remain AUTH-1 surfaces.

No controller rewrite: retain protected-default source authentication, disposable non-sudo execution, empty credential environment, account termination before uploads, fresh validator account, exact source/merge/attempt/job graph/artifact authentication, and statuses-only App writer. Preserve `Trusted PR / Build and verify` and `Trusted PR / Packaged E2E gate`; candidate YAML/checks never self-authorize. Update expected schema/graph readers before producers change, with negative tests for stale/mixed/partial evidence. Exact loader entrypoint changes require contract-first current+next, then exact-next bytes/collapse; never permanent dual acceptance.

Visual review stays advisory. Preserve the canonical `master`/`fabric-1.20.1` anchor, lossless retention, semantic pairing, provider isolation, security/cost bounds, and model routing. This migration does not add a model provider or let visual noise skip a deterministic lane.

Independent release planning uses a small `scripts/release/plan_release.py --artifact-node <node>` command (new interface), reading lane version and production identity from the matrix/manifest. It requires that lane's exact matching build, boundary, and packaged-E2E evidence; missing/newer-failed/stale evidence fails closed. Other lanes need not publish or share a mod version. A qualified lane may be planned while another target remains unresolved, but the migration remains incomplete and publication remains separately gated. A version or matrix change requires fresh matching evidence; do not reuse an old qualification Boolean. A lane-scoped plan cannot satisfy the full migration gate.

## 7. File changes and bounded rollout

| Area | Intended change |
|---|---|
| `scripts/release/matrix.py`, portability fixtures/tests | Dual schemas, target/configuration distinction, exact scoped projections and source routing. |
| `release/release-matrix.json` | Incremental schema-2 enrollment, lane inputs/version/task paths, and final shared activation; local preparation is allowed, published admission remains under AUTH-1. |
| `settings.gradle`, `build.gradle`, `stonecutter.gradle`, small `gradle/` conventions | Detached selected-node graph, explicit contexts, per-lane outputs/toolchains. |
| Loader build scripts and `gradle/e2e-harness-conventions.gradle` | Explicit lane binding, selected common dependency, separate preprocessing and archives. |
| Canonical source roots and declared `src/legacy*` overlays | Targeted historical reconciliation, not wholesale moves. |
| `scripts/release/build_matrix.py`, manifest/verifier, new release planner | Serialized builds, scoped provenance, production-only independent selection. |
| E2E selectors/bootstrap/datapack, CI policies/workflows/actions/tests | All-lane qualification, compatible trusted schema/graph transitions, unchanged trust zones. |
| README, contributor/operations/release/E2E docs | Exact commands, policy route once authorized, qualification and rollback instructions. |

One slicing pass yields the following dependency order. Each row is a repeatable work-unit boundary, not a promise that an entire architectural layer fits one PR. Tests/docs count toward additions plus deletions; aim below 350, hard cap 400. Tasks must name concrete files and estimate each unit before apply.

| Order | Work-unit boundary (repeat where stated) | Budget / exit check |
|---|---|---|
| 0 | AUTH-1 delivery resolution and fresh baseline/toolchain/governance facts | Does not block unpublished local implementation; no foundation or restricted-path delivery until resolved. |
| 1a | One dual-schema validation rule group with mutation tests | ≤400; v1 behavior unchanged. |
| 1b | One projection/consumer adapter with tests | ≤400 each; no live matrix change. |
| 2a | Bootstrap transition schema/current+next validator | ≤400 per cohesive loader/validator unit; unchanged executable bytes. |
| 2b | Reviewed settings/repository/Stonecutter foundation, split policy reader from activation | ≤400 per unit; checksum additions separately reviewed, no broad refresh. |
| 2c | Serial planner, then process/report/lock execution as separate units | ≤400 each including tests; no nested/concurrent Gradle. |
| 3a | Schema-2 target declaration preserving two operational rows | ≤400; twelve targets visible, legacy checks preserved. |
| 3b | One lane's complete matrix/runtime inputs and portability assertions | ≤400 each; incomplete lanes remain unresolved. |
| 4 | One compatibility seam or small class/resource group and its lane checks | ≤400 each; canonical paths retained; no whole-version copy. |
| 5a | One loader's exact-next build/bootstrap transition and collapse | ≤400 each; both old/new contract checks pass. |
| 5b | One artifact/provenance rule or E2E consumer adaptation | ≤400 each; cross-lane and leak mutations fail. |
| 6 | One protected workflow/graph expectation transition with security tests | ≤400 each; old evaluator compatibility established first. |
| 7 | Independent release selection/qualification report and tests | ≤400 per contract unit; no publication. |
| 8 | Full shared-mode activation and exact twelve-lane qualification evidence | ≤400 code/docs; all target paths exist before activation. |
| 9 | Separately authorized sync retirement, only after equivalence and all-lane evidence | ≤400 per cohesive unit; historical refs retained. |

Chain: `master <- 1a <- 1b... <- 2... <- 3... <- 4... <- 5... <- 6... <- 7 <- 8 <- 9`. This is the chosen stacked-to-main strategy, not a tracker/feature-branch chain. Delivery must respect exact-current-master policy: only an eligible current slice is submitted against current `master`; after integration, rebase/re-evaluate the next slice. Controller-upgrade slices cannot target another feature branch. No chain branches/PRs are created here.

Source reconciliation may involve thousands of lines across screens/renderers/harness code; no exact diff was generated, so no measured line-count claim is made. Retaining canonical roots avoids needless mass moves but does not make porting free. Split by behavior/API seam, not formatting or removal of comments/tests. Large existing files can receive small targeted hunks. If a single cohesive import, move, checksum closure, or contract transition still exceeds 400 after this slicing pass, stop that unit and report its actual count with a `size:exception` recommendation; no exception is preauthorized. Never hide a huge source move or semantic rewrite behind rename statistics.

## 8. Command-level verification plan (not executed)

Run from the BlockPops root. Commands below are planned evidence, not passing results. `python` denotes the selected interpreter (`python3` on CI); Windows uses `gradlew.bat`. First record `git status --short` without altering user files and preserve command, environment, exit code, scope, and exact tested identity for every check.

### Existing checks before and during migration

```powershell
# Set only the process environment to an installed JDK 21; do not guess a path.
$env:JAVA_HOME = $env:JAVA_HOME_21_X64
& "$env:JAVA_HOME/bin/java.exe" -version
.\gradlew.bat --no-daemon --no-parallel --version
python scripts/release/matrix.py --matrix release/release-matrix.json
python -m unittest discover -s tests -v
python -m unittest discover -s scripts/ci/tests -p "test_*.py" -v
python -m unittest tests.test_release_matrix_portability tests.test_artifact_and_report_validation tests.test_scenario_contract -v
python -m unittest scripts.ci.tests.test_dependency_policy scripts.ci.tests.test_loader_bootstrap scripts.ci.tests.test_pr_gate scripts.ci.tests.test_gate_controller scripts.ci.tests.test_e2e_job_graph scripts.ci.tests.test_e2e_fanin scripts.ci.tests.test_untrusted_runner scripts.ci.tests.test_workflow_security -v
python scripts/ci/dependency_policy.py --metadata gradle/verification-metadata.xml
python scripts/ci/loader_bootstrap.py --repository . --head-sha <exact-committed-sha>
.\gradlew.bat --no-daemon --no-parallel validateReleaseMatrix check
# Legacy-only build while the live execution layout is still legacy:
.\gradlew.bat --no-daemon --no-parallel clean buildAllLanes buildAllE2EHarnesses
```

If `JAVA_HOME_21_X64` is absent or not Java 21, stop before invoking Gradle and locate an installed compatible JDK; installation is not assumed. Windows symlink privilege/byte-stability failures must not be fixed by weakening security tests. Reproduce on an appropriate clean Ubuntu environment and keep host baseline failures separate.

### New interfaces to implement and test before using

```text
python scripts/release/build_matrix.py --plan
python scripts/release/build_matrix.py --artifact-node fabric-1.20.1 --plan
python scripts/release/build_matrix.py --artifact-node fabric-1.20.1 --clean
python scripts/release/build_matrix.py --clean
python -m unittest discover -s tests -p "test_build_matrix.py" -v
python -m unittest discover -s tests -p "test_release_selection.py" -v
python scripts/release/verify_release.py --artifact-node <node> --stage build/lane-release --manifest build/lane-release/artifacts.json
python scripts/release/plan_release.py --artifact-node <node> --manifest build/lane-release/artifacts.json --qualification <verified-lane-report>
```

The `--artifact-node` verifier flag and the planner/test files above are new contracts, not commands that exist today. Full runner planning reports all twelve targets and errors on unconfigured entries; selected planning reports partial scope. Test mocked process ordering, locking, startup failure, cancellation, wrong JDK, parallel override, stale success, changed matrix, and deleted/changed earlier outputs before real builds. Add equality checks between full build/runtime/CI inventories and targets, with duplicate/missing/extra/mixed-family mutations.

### Existing staging and packaged acceptance after compatible builds

```bash
python scripts/release/verify_release.py --matrix release/release-matrix.json --manifest build/release/artifacts.json --stage build/release
python scripts/release/verify_release.py --matrix release/release-matrix.json --manifest build/release/artifacts.json --stage build/release --verify-staged
python scripts/release/matrix.py --kind artifacts
python scripts/release/matrix.py --kind pr-anchors
python scripts/release/matrix.py --kind scheduled-anchors
python -m e2e.orchestrator --list
python -m pip install --only-binary=:all: --require-hashes -r e2e/requirements.txt
xvfb-run -a python -m e2e.orchestrator --packaged
```

The final command selects all matrix runtimes and default protected scenarios; compare emitted/result node sets with all twelve targets. For diagnosis use the existing `--artifact-node <node>` selector, but never count that as full qualification. CI must independently validate exact job graphs, all lane reports, real assertions/captures, JAR boundaries, and trusted newest exact-head contexts. Add mutations for renamed wrong-lane harnesses, embedded provenance mismatch, harness resources/nested contents in production, unexpected files, stale matrix/contract/tree, partial scope presented as full, and selected-lane release without matching qualification.

For each runtime lane verify first-join color prompt, real Done/network path, server persistence and synchronized figure, real claw-machine interaction, collection UI, and packaged Settings with no Develop tab. Preserve deterministic probe canaries for each supported layout variant. Add per-lane smoke assertions for loading existing data/registries and correct loader metadata; failures do not authorize data-format changes.

Remote-dependent read-only rechecks before delivery/cutover: `git ls-remote --symref origin HEAD`, `git ls-remote --heads --tags origin`, `gh api repos/AkaNebur/BlockPops`, `gh api repos/AkaNebur/BlockPops/releases --paginate`, and authenticated branch-protection, ruleset, environment, workflow-content, and required-context inspection for current `master`. The earlier 404 was resolved by switching the active `gh` account; future 403/visibility limits remain unknown evidence, not proof that protections or releases are absent. Inspect the exact prospective slice with `git diff --numstat <base>...<head>` and a rename-independent review of source moves before applying the 400-line gate.

Inherited baseline only: 136 Python tests discovered, 2 failures and 38 errors; Gradle stopped before configuration with `Unsupported class file major version 69`. No new passing evidence is claimed. Separate baseline remediation from migration regressions; do not expand scope into unrelated host or visual fixes.

## 9. Cutover, rollback, and acceptance

Before cutover, keep schema-1 readers, legacy build dispatch, historical refs, and `sync-release-branches.yml`. Shared controller changes must remain portable to enrolled schema-1 release branches; replay their matrices in tests and obtain the documented synchronization/attestation evidence. A conflict remains fail-closed. Keeping the workflow file alone does not prove synchronization still works.

Require all twelve exact lane configurations, serial production/harness outputs, matching provenance, deterministic packaged scenarios, equivalent trusted protections, fresh remote facts, and AUTH-1 delivery authority before declaring migration complete. Independently release-plannable does not mean globally complete. Owner-selected scope cannot be reduced to clear a failure.

Retirement is a distinct task after those checks and separate retirement authorization. The user's condition “retire only after validation” is not authority to remove it early. Do not delete historical refs or rewrite tags. Publication/PR creation is outside this design's authorization.

If a preparatory slice fails, stop advancement and use a reviewed revert of that slice and its dependent activation, retaining verified evidence and legacy dispatch. Do not reset user work or rewrite branches. After shared activation, freeze new release dispatch first; revert configuration/controller generations through the authorized route and rebuild/restage the intended source before restoring a release path. Old evidence with a different matrix/tree is not reusable. Never overwrite published artifacts or automatically re-enable a previously retired sync controller without revalidating its authority.

Acceptance maps directly to the three specifications: exact target/derived-consumer and isolation invariants (lane-matrix); canonical source, physical artifact separation, provenance, and twelve-lane runtime evidence (shared-source-qualification); qualified independent planning, AUTH-1, preserved trusted gates/history, and gated retirement/remote facts (release-transition).

**Next local work:** task 2 may gather environment evidence without edits, or task 3 may begin with a schema-2 test-only subunit while leaving the live matrix unchanged. AUTH-1 blocks published admission/delivery, not these authorized unpublished steps. This design does not claim runtime success or delivery authority.
