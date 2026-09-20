# Exploration — consolidate shared source

## Scope and closure method

This is exploration only. No source, workflow, branch, remote, dependency, build, test, or publication action was performed in this phase. `openspec/config.yaml` confirms OpenSpec storage, auto execution, auto-chain with the selected `stacked-to-main` strategy, 400 changed-line review budget, and unconfirmed TDD policy. The working branch is `codex/claude-visual-e2e` at `a200152bab52e2a2020d02498127b3ce4ac9797d`; pre-existing `.gitignore` and `.pi` work was untouched.

A command-capable verifier completed the requested read-only `git show`, `git log`, and `git ls-tree` inspection of every named historical ref. It changed no refs or files. This artifact incorporates that direct evidence.

## Remote/default evidence

The earlier live-probe 404 was resolved by switching the active `gh` account to `AkaNebur`; do not repeat the account question. The account switch was the only authorized environment mutation. Subsequent repository queries were read-only.

| Evidence | Finding | Limit |
|---|---|---|
| Repository/default | `gh repo view AkaNebur/BlockPops` reports private `true`, viewer permission `ADMIN`, and default branch `master`; `git ls-remote` resolves `master` to `35c72713ccdb94205df4bc5cbafcd5264dd64377`. | Observed live state, subject to fresh delivery-time recheck. |
| Branch governance | The branch endpoint reports `protected: false` and embedded required-status enforcement off. | Dedicated protection and ruleset endpoints return HTTP 403 upgrade-required, so complete absence of protection/rulesets is not proven. |
| Exact live tree | Recursive tree query for `35c7271` reports `truncated: false` and contains no `.github/*`, `scripts/ci/pr_gate.py`, `CONTRIBUTING.md`, or `docs/operations.md`; exact-SHA content queries return 404 for those paths. | Proves those paths absent at that SHA, not that no host-side governance exists. |
| Actions metadata | Workflow listing names `Build gate` and `Packaged E2E`. | Metadata is not proof that workflow YAML is deployed on the exact default-branch tree. |
| Environments | Repository environments query reports `total_count: 0`. | Does not resolve ruleset/protection endpoints that remain API-limited. |
| Candidate foundation | Feature commit `a200152` contains proposed strict controller and policy assets. | Candidate content is not deployed live authority and cannot approve its own initial deployment. |

**Main versus master:** `master` is both the configured product branch and the currently observed live default. `stacked-to-main` remains a strategy label, not a request to rename it.

## Lane inventory

Support here means concrete implementation/configuration evidence, **not** publication or release verification. Local tags are absent; publication and live CI/release execution remain unverified by the current evidence.

| Group | Version / loader lanes | Direct evidence (ref + commit + path) | Configuration and confidence |
|---|---|---|---|
| Core implemented lanes | 1.20.1 Fabric; 1.20.1 Forge | `origin/master` / `35c72713ccdb94205df4bc5cbafcd5264dd64377` / `gradle.properties`, `fabric/src/main/resources/fabric.mod.json`, `forge/src/main/resources/META-INF/mods.toml` | `minecraft_version=1.20.1`, `enabled_platforms=fabric,forge`, Fabric Loader 0.17.3, Forge 1.20.1-47.4.9; Fabric metadata `~1.20.1`, Forge metadata `[1.20.1,)`. **Implemented support; publication unverified.** |
| Core implemented lanes | 1.21.1 Fabric; 1.21.1 NeoForge | `origin/1.21.1-neoforge-fabric` / `4f6e40390a3fa552c267e5e39f3b9e2c3684ba51` / `gradle.properties`, Fabric and NeoForge metadata paths | `minecraft_version=1.21.1`, `enabled_platforms=fabric,neoforge`, Fabric Loader 0.17.0, NeoForge 21.1.77; Fabric metadata `~1.21.1`, NeoForge metadata `[1.21.1,1.22)`. `git log` includes `f420028 Port fixes from master to 1.21.1 NeoForge/Fabric`. **Implemented support; publication unverified.** |
| Core E2E architecture | 1.21.1 Fabric; 1.21.1 NeoForge | `origin/feat/1.21.1-multiversion-e2e` / `6b5651e6675347195f50d8024616a4fb7e978de9` / `release/release-matrix.json`, `gradle/e2e-harness-conventions.gradle`, `common/src/e2e`, `fabric/src/e2e`, `neoforge/src/e2e` | Release-role matrix names `1.21.1-neoforge-fabric`, canonical `master`; `fabric-1.21.1` and `neoforge-1.21.1` are Java 21 with Loader/NeoForge 0.17.0/21.1.77, `remapJar` plus separate `remapE2EHarnessJar`, and both PR/scheduled anchors true. This confirms the intended 1.21.1 E2E architecture, not publication. |
| Core E2E architecture | 1.20.1 Fabric; 1.20.1 Forge | `origin/feat/multiversion-e2e-architecture` / `3fbf917a63ec0a1b9ae4743da085367dfe23dc1a` / `release/release-matrix.json`, `common/src/e2e`, `fabric/src/e2e`, `forge/src/e2e` | Matrix configures 1.20.1 Fabric/Forge Java 17 and the E2E roots exist. **Implementation evidence; publication unverified.** |
| Additional config/source lanes | 1.21.4 Fabric; 1.21.4 NeoForge | `origin/feature/multi-version-stonecraft` / `50eb7fd16de97d5e0177cace988a41225436a828` / `gradle.properties`, `settings.gradle.kts`, `fabric/src/v1_21_4`, `neoforge/src/v1_21_4` | Version is declared; Forge is selected for 1.20 and NeoForge for 1.21. Fabric Loader 0.17.0; NeoForge 21.4.156. **Concrete config/source evidence; build, E2E, release, and support completeness unverified.** |
| Additional config/source lanes | 1.21.5 Fabric; 1.21.5 NeoForge | same ref / `gradle.properties`, `settings.gradle.kts`, `fabric/src/v1_21_5`, `neoforge/src/v1_21_5` | Fabric Loader 0.16.10; NeoForge 21.5.82. **Concrete config/source evidence; completeness unverified.** |
| Additional config/source lanes | 1.21.6 Fabric; 1.21.6 NeoForge | same ref / `gradle.properties`, `settings.gradle.kts`, `fabric/src/v1_21_6`, `neoforge/src/v1_21_6` | Fabric Loader 0.16.10; NeoForge 21.6.20-beta. **Concrete config/source evidence; completeness unverified; beta loader is a material risk.** |
| Additional config/source lanes | 1.21.7 Fabric; 1.21.7 NeoForge | same ref / `gradle.properties`, `settings.gradle.kts`, `fabric/src/v1_21_7`, `neoforge/src/v1_21_7` | Fabric Loader 0.16.14; NeoForge 21.7.25-beta. **Concrete config/source evidence; completeness unverified; beta loader is a material risk.** |

`origin/feature/multi-version-stonecraft` also declares 1.20.1 and 1.21.1, but those are already covered by stronger core-ref evidence. Its latest inspected log entry is `50eb7fd Update - 1.5.0 - Check Desc`. Its tree output was truncated at 50 KB, so E2E absence must not be inferred. Preserve every historical branch; no feature ref is dismissed merely because of its name.

## Product scope decision — all twelve lanes

The inventory is closed for known locally available implementation evidence: **four core lanes** (1.20.1 Fabric/Forge and 1.21.1 Fabric/NeoForge) and **eight additional concrete config/source lanes** (1.21.4–1.21.7, Fabric/NeoForge). Local evidence proves the latter eight have source/configuration, not their build, packaged-E2E, release, or continuing-support completeness.

**Owner-confirmed decision after inventory:** Preserve all twelve combinations in the shared-source migration target: 1.20.1 Fabric/Forge, plus 1.21.1, 1.21.4, 1.21.5, 1.21.6, and 1.21.7 each on Fabric/NeoForge. The four-core-lane-only alternative was not selected. This resolves the product scope gate for proposal/spec/design/tasks; do not repeat this question.

**Evidence boundary:** Target inclusion is not successful-build, packaged-E2E, or published-release proof. The eight additional lanes require qualification within this change, rather than being silently deferred or dropped. Historical-ref evidence and remote uncertainty above remain unchanged. A lane that cannot qualify must remain visibly unresolved, not be reported as supported or removed without a new owner decision.

## Migration seams

1. `release/release-matrix.json`, `settings.gradle`, and `build.gradle` are the current matrix/build seams. The current Groovy build expects one Minecraft/Java era and forbids concurrent Forge and NeoForge; a shared multi-era graph must replace those assumptions.
2. Quick-Skin-Mod is read-only reference: its `settings.gradle.kts` and `stonecutter.gradle.kts` show matrix-derived Stonecutter nodes, canonical source with narrow overlays, and `scripts/release/build_matrix.py` serializes one `--no-daemon --no-parallel` Gradle process per target. Adapt concepts only, not its broad 34-lane machinery or domain implementation.
3. `gradle/e2e-harness-conventions.gradle`, `scripts/release/artifact_manifest.py`, `e2e/orchestrator.py`, `e2e/runtime_store.py`, and `e2e/scenario-contract.json` already support separate production and packaged-E2E JARs. The migrated matrix must derive production, harness, CI, and packaged E2E rows for each selected lane.
4. Preserve `.github/workflows/sync-release-branches.yml` until a replacement has validated equivalent protection and every selected lane’s build/E2E path.

## Toolchain and checks

- The current matrix uses Gradle JVM 21 with Minecraft 1.20.1 Java 17 outputs. Verified 1.21.1 E2E matrix evidence uses Java 21. The migration must maintain per-era toolchain separation.
- `gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.14. Existing baseline was not rerun: Python discovery reported 136 tests with 2 failures and 38 errors; Gradle stopped before configuration with `Unsupported class file major version 69`. Later remediation should select the matrix-compatible Gradle JVM, not change source speculatively.
- TDD policy is unconfirmed. Use proportional matrix/schema/projection tests, serialized build-plan checks, and packaged-E2E evidence; do not claim strict TDD consent from test presence alone.

## Next recommendation and risks

**Next local work:** retain all twelve targets and proceed without another scope or account question. Task 2 may record JDK/toolchain evidence without tracked edits; alternatively task 3 may begin with schema-2 portability tests and fixtures while leaving the live matrix unchanged. AUTH-1 remains pending for foundation delivery and later published restricted admission, not for this authorized unpublished work.

Risks: protection/ruleset coverage remains partly unobservable because dedicated endpoints return HTTP 403; publication remains unverified; the current build’s single-era guards conflict with the target graph; late NeoForge lanes use beta loaders; and removing `sync-release-branches.yml` early would remove current release protections.
