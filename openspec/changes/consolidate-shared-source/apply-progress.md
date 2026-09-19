# Apply Progress — consolidate-shared-source

## Current bounded work unit

- Phase/status consumed: `gentle-ai.sdd-status@2`, change `consolidate-shared-source`, apply `ready`, next `apply`, no native blockers.
- Action context: repo-local workspace `C:/Users/nebur/Documents/GitHub/BlockPops`; every edit stayed within the supplied allowed roots and file surfaces.
- Delivery boundary: auto-chain / stacked-to-main, integration branch `master`, 400 authored changed-line cap. This is local unpublished PR-1a foundation work; no commit, branch, push, PR, merge, release, or remote mutation was performed.
- AUTH-1 warning: this local parser work is permitted, but foundation delivery and later restricted-path admission remain unauthorized.

## Completed in this subunit

- Added explicit schema dispatch while preserving complete schema-1 validation and existing execution-consumer behavior.
- Added a schema-neutral immutable inventory model for validated lane identities.
- Added schema-2 inventory-only validation for exactly the twelve selected targets and `preparing` / `shared` migration-state rules.
- Kept schema 2 unsupported by execution consumers until artifact/runtime routing, toolchain, repository, and unresolved-configuration invariants are implemented in later task-3 subunits.
- Added focused tests built from a deepcopy of the live schema-1 matrix rather than a duplicated JSON fixture.
- Task 3 remains unchecked because this is only its first coherent validation-rule group. Tasks 1 and 2 also remain unchecked.

## Files changed

- `scripts/release/matrix.py`
- `tests/test_release_matrix_schema2.py`
- `openspec/changes/consolidate-shared-source/apply-progress.md`

The live `release/release-matrix.json` remained byte-identical: 4,826 bytes, Git blob `14ccfa09c5fd292a79926c3e30309eb55dbb3b6d`, SHA-256 `c554bab87f188be57dccf5a1d14cc81d8dce8ca17663198c4d700f620f3f4fdb`.

## Proportional test-first evidence

| Stage | Command | Observed result |
|---|---|---|
| RED | `python -m unittest tests.test_release_matrix_schema2 -v` | Exit 1; import failed because `normalize_matrix_inventory` did not exist. |
| GREEN | `python -m unittest tests.test_release_matrix_schema2 -v` | Exit 0; 5 tests passed. |
| TRIANGULATE | `python -m unittest tests.test_release_matrix_schema2 tests.test_release_matrix_portability -v` | Exit 0; 16 tests passed, including missing/duplicate/extra/mixed target and migration-state mutations. |
| Regression | `python -m unittest tests.test_release_matrix_portability -v` | Exit 0; 11 existing portability tests passed. |
| Runtime boundary | `python scripts/release/matrix.py --matrix release/release-matrix.json` | Exit 0; current schema-1 matrix validated and emitted its existing two-lane projection. |
| Diff hygiene | `git diff --check` | Exit 0; only the pre-existing `.gitignore` line-ending warning was emitted. |

Testing policy remains conditional-by-test-surface, not strict TDD. No Gradle command was run because this Python-only slice does not change Gradle behavior and the recorded host lacks a compatible JDK.

## Independent work-unit verification

- A separate verifier reproduced all 16 schema/portability tests and the schema-1 CLI success; 10 additional schema/target/migration mutations were rejected.
- Matrix preservation was confirmed against the recorded pre-slice working-tree SHA-256. Git stores LF while this checkout uses CRLF (`core.autocrlf=true`); the normalized blob equals HEAD. Raw HEAD-byte equality is not the working-tree preservation criterion.
- Native review did not run: both consent attempts expired without a lineage or mutation. Native risk assessment was unavailable, so independent technical verification was performed instead; no native approval or receipt is claimed.
- Packaged Minecraft E2E: N/A for this inventory-only Python subunit because execution consumers still reject schema 2 and production/harness behavior is unchanged. Gradle remains unrun; JDK 21/17 were not found in the bounded environment probe.
- Fresh post-apply native status: `nextRecommended: apply`, 17 tasks pending; this subunit does not complete task 3.

## Remaining task-3 work

- Validate schema-2 lane routing, per-lane versions, build layout, Java/toolchain, repository family, task/output paths, and artifact/runtime pairing.
- Add explicit unresolved-configuration reporting and the remaining mutation coverage before any schema-2 document can be accepted by execution consumers.
- Preserve schema-1 projections and arbitrary historical matrix behavior while adding those rules.
- [ ] 3. **PR 1 — add schema-1/schema-2 normalized matrix parsing without changing the live matrix.** Extend `scripts/release/matrix.py` and focused portability fixtures in `tests/test_release_matrix_portability.py` to dispatch schemas exactly, preserve schema-1 bytes/behavior, validate the twelve `targets`, `migration` state, lane routing/toolchain/repository fields, and report unresolved configurations explicitly. **Edit scope:** `scripts/release/matrix.py`, `tests/test_release_matrix_portability.py`, and test fixtures under `tests/`; do not edit `release/release-matrix.json`. **Depends on:** none for the local test-first parser subunit; task 1 before eventual delivery because `scripts/release/` is a future governed surface. **Verification/acceptance:** run the focused portability test and `python scripts/release/matrix.py --matrix release/release-matrix.json`; mutations reject missing, duplicate, extra, mixed-era, and unknown-schema data while current schema-1 output remains unchanged. **Rollback:** revert only parser/tests/fixtures in this slice. **Estimate:** 260–380 lines.

## Deviations and rollback

- No design deviation: the intermediate API is deliberately inventory-only and reports `execution_supported=False` for schema 2.
- Rollback boundary: remove `tests/test_release_matrix_schema2.py`, revert only the schema-dispatch/inventory additions in `scripts/release/matrix.py`, and remove this bounded progress entry; no live matrix or consumer migration is coupled to it.

## Codex Mac continuation — task 3, configured lane contracts

- Resumed existing commit `6f86bfd` on `codex/claude-visual-e2e`; Pi is out of scope.
- Reproduced the inherited 16 focused tests and schema-1 CLI successfully on macOS, Python 3.11.15.
- Added strict schema-2 lane versions, legacy/Stonecutter routing, exact task/archive identities, per-era artifact/runtime Java, Gradle JVM 21, repository families, source-route references, and complete artifact/runtime pairing. Shared mode requires every target configured; preparation retains both legacy rows.
- Reused the existing configuration validator with explicit schema context; schema-1 single-era/FML guards and execution output remain unchanged. Schema 2 remains rejected by execution consumers.
- RED: 34 mutation failures before implementation. GREEN: `python3 -m unittest tests.test_release_matrix_schema2 tests.test_release_matrix_schema2_configuration tests.test_release_matrix_portability -q` — 19 tests passed. Schema-1 CLI output compares byte-for-byte with the pre-change Mac baseline; `git diff --check` passes.
- Baseline observation, separate from migration qualification: full `tests` discovery ran 141 tests with 1 failure/16 errors from CRLF visual prompts; CI discovery ran 175 tests with 4 failures/3 errors in dependency metadata, bootstrap and the macOS temporary-directory boundary. No unrelated fixes were applied.
- Registered Temurin 21.0.10 and cached Temurin 17.0.19 are available; Gradle/builds/E2E were not run in this Python unit.
- Independent review found no schema-1 regression; 27 artifact/report/scenario tests also pass. It identified remaining schema-2 installer/dependency-family and metadata-era checks, to be handled as the next bounded unit.
- Remaining task 3: those runtime-context checks, explicit main/E2E source-tree and overlay validation, normalized configured/unresolved projection, and further negative coverage. No build or qualification success is implied.
- Live matrix and user `.gitignore`/`.pi` bytes are preserved. No push, PR, merge, publication, protection change or sync-workflow retirement.
- Rollback: revert only this unit's matrix validator, tests and progress entry; the live schema-1 matrix has no dependency on it.

## Task 3 — bind schema-2 runtime inputs to their lane

- Installer IDs, loader versions and exact reviewed URLs now agree with the loader/Minecraft era. Runtime dependency group/artifact identities and repository origins cannot borrow another loader context; Fabric API and GeckoLib retain their Minecraft identity.
- Loader metadata must identify the artifact Minecraft version; FML ranges allow a higher numeric upper bound or an exact version, rejecting empty/reversed ranges including equivalent trailing-zero versions.
- A separate verifier authored the context mutation tests and reviewed the helper. The new tests were first run after implementation and passed; no RED run is claimed for this unit.
- `python3 -m unittest tests.test_release_matrix_schema2 tests.test_release_matrix_schema2_configuration tests.test_release_matrix_schema2_context tests.test_release_matrix_portability -q`: 25 tests passed. Schema-1 CLI remains byte-identical to baseline.
- Schema-1 semantics, the live matrix, execution-consumer rejection of schema 2, and publication restrictions remain unchanged. Remaining task 3: main/E2E source routes/overlays and unresolved configuration projection.
- Rollback is this helper/call, its new context test module, and this evidence entry; no runtime/build execution or remote mutation occurred.

## Task 3 — explicit production/harness source routing

- Schema 2 requires each configured module's canonical `main` and `e2e` roots. Artifact `source_routes` references exactly common plus its own loader; unconfigured targets make no filesystem claim.
- Overlays declare configured lane scope, source set, exact added/replaced relative paths, incompatibility, historical commit/path, and acceptance check. Overlapping scope, opposite-loader routes, traversal, backslashes, undeclared files and version snapshots fail closed.
- With `repository` supplied, validation checks all routed directories/files, declared replacements, common/loader collisions, symlinks and special files. It also checks the whole legacy directory so undeclared sibling E2E files cannot hide under an admitted main overlay.
- Independent review and source mutations pass, including real checkout sources, temporary replacements/additions, lane-scoped collision checks, internal symlinks and FIFO files. Source existence is not inferred when repository validation is omitted.
- `python3 -m unittest tests.test_release_matrix_schema2_sources tests.test_release_matrix_schema2 tests.test_release_matrix_schema2_configuration tests.test_release_matrix_schema2_context tests.test_release_matrix_portability tests.test_artifact_and_report_validation tests.test_scenario_contract -q`: 57 tests passed. Schema-1 CLI output remains byte-identical; diff hygiene passes.
- Remaining task 3: expose configured lane lookup and explicit unresolved target reporting, with full/shared fixture coverage. No execution consumers or live matrix were activated; no publication action occurred.
- Rollback: this schema-2 route validation and its fixtures/tests/progress entry only. The original schema-1 source validator remains intact.

## Task 3 — normalized lookup and unresolved-target reports

- Added immutable lane configuration snapshots with safe copied artifact/runtime views, per-lane archive-version expansion and exact configured/unknown/unresolved lookup. Schema-1 views retain the existing legacy inputs.
- `load_matrix_inventory()` and `matrix.py --kind inventory` inspect either schema without enabling schema-2 execution. Reports include target/configured counts, missing artifact/runtime reasons, migration mode, source-check status and execution-support status. A preparatory schema-2 fixture reports 12 targets, 2 configured, 10 unresolved; malformed/orphan configurations remain errors.
- `require_complete()` demands every target configuration. A synthetic twelve-lane shared fixture passes parser coverage while remaining non-executable and unqualified. These fixture dependency values are test data, not proposed lane pins.
- Independent review covered the new lookup/report API and tests. Added explicit malformed-type and secure-JSON mutations; source checks and deeply copied nested configuration views remain test-covered.
- Validation: `python3 -m unittest tests.test_release_matrix_schema2 tests.test_release_matrix_schema2_configuration tests.test_release_matrix_schema2_context tests.test_release_matrix_schema2_sources tests.test_release_matrix_schema2_reports tests.test_release_matrix_portability tests.test_artifact_and_report_validation tests.test_scenario_contract -q` — 63 tests passed. The schema-1 CLI still compares byte-for-byte with the pre-change Mac baseline.
- Task 3 is complete for local parser/configuration scope. Task 4 is next: adapt consumers individually, preserving schema-1 behavior and keeping the live matrix at schema 1 until the later enrollment dependencies are satisfied. Tasks 1/2 and all build/E2E/qualification/transition gates remain open.
- Work units so far: `544156f` lane configuration (207 authored lines), `f81836d` runtime context (202), `91b5237` source routes (274); this fourth unit adds only parser projections, tests and this evidence. Every unit stays below the 400-line cap.
- Preservation hashes confirm unchanged live matrix, `.gitignore` and `.pi` files. Existing planning files remain local. No Gradle or packaged-E2E success, global-suite pass, delivery authority or remote action is claimed.
- Rollback: revert this projection/API/CLI addition and its tests/evidence only; execution consumers continue rejecting schema 2.
- Final independent audit found no material task-3 blocker. Compared with `6f86bfd`, both current and arbitrary-named historical schema-1 matrices produced 14 identical API results, 28 identical CLI outputs and 24 identical mutation outcomes; the live matrix SHA-256 remains `c554bab87f188be57dccf5a1d14cc81d8dce8ca17663198c4d700f620f3f4fdb`.

## Task 4a — protected E2E job graph consumes normalized lanes

- Added an opt-in `MatrixDocument` API preserving the original schema and immutable lane snapshots. Default protected scope remains the two legacy lanes during preparation; shared mode uses every target. Lane selection is explicit and unknown/unresolved/full-incomplete selections fail.
- Adapted only `scripts/ci/e2e_job_graph.py` in this consumer unit. It keeps exact attempts, job identities, conclusions and event-dependent public evidence behavior. Other execution readers remain schema-1-only until adapted.
- Independent review reproduced 26 focused tests and compared 18 schema-1 graphs with HEAD: identical names/conclusions. Shared graphs reject missing lanes and legacy-only evidence.
- Expanded CI discovery caught candidate-owned test-fixture imports; moved synthetic schema-2 controller fixtures into the protected `scripts/ci/tests/matrix_fixtures.py` surface. `python3 -m unittest scripts.ci.tests.test_e2e_job_graph scripts.ci.tests.test_sync_merge tests.test_release_matrix_schema2_reports tests.test_release_matrix_portability -q`: 31 tests passed, including protected import closure.
- Task 4 remains partial: Gradle, artifact staging/verifier and packaged-runtime consumers follow separately. No live-matrix activation or publication occurred.
- Rollback: this document/projection API, one graph consumer and its protected tests together.

## Task 2 — Mac environment and checkout-byte recovery

- JDK 21.0.10 launches the checksum-pinned Gradle 8.14 wrapper successfully with `--no-daemon --no-parallel --version`; cached JDK 17.0.19 also launches successfully. Wrapper entrypoint was invoked directly through Java before restoring `gradlew` LF/0755 bytes/mode to Git's exact baseline.
- Read-only remote observation: default `master` remains `35c72713ccdb94205df4bc5cbafcd5264dd64377`, active account AkaNebur, private repository, no environments. Branch reports protected=false; protection/rulesets return HTTP 403, so absence of controls is not established. No account or remote changes.
- Restored only clean gate/build files whose CRLF-to-LF bytes exactly matched both index and HEAD, revalidating hashes immediately before writing and skipping concurrent changes. This repairs transport-only byte mismatches without Git content changes or relaxed hash validation. `.gitignore`, `.pi` and the live matrix were excluded.
- Full `python3 -m unittest discover -s tests -q`: 161 tests passed after repair. Bootstrap tests now pass 10/10. CI discovery exposed the protected-fixture dependency corrected above and the pre-existing macOS /tmp symlink test assumption; the latter remains separate from migration logic.
- Detailed local environment/governance/repair logs are in ignored `build/diagnostics/task2/`; diagnostic `validateReleaseMatrix check` is running serially with Java 21 and strict checksums. No build or packaged-runtime qualification is claimed.

## Task 5a — parse bounded current/next bootstrap contracts

- Added exact schema-2 bootstrap transition parsing: generation 1, one declared loader, complete schema-1 current/next records, and no changes to other loaders. Unknown generations, ambiguous types, duplicate keys, nested transitions, missing loaders and widened scope fail closed.
- Schema-1 verification remains active. Schema-2 execution explicitly requires the separate base-owned evaluator; parsing grants no authority and cannot admit its own executable changes.
- `python3 -m unittest scripts.ci.tests.test_loader_bootstrap -v`: 10 tests passed after checkout-byte repair; the same tests passed in an exact Git-byte temporary snapshot beforehand. CLI verification of the existing exact HEAD's schema-1 contract passed.
- Only validator/tests changed (162 authored lines before this evidence); no actual bootstrap contract, protected executable byte, digest, remote or publication was changed. Root review confirmed the fail-closed boundary.
- Task 5 remains partial: implement and test base-owned current/next evaluation separately before preparing an exact transition record. Rollback is this parser/test addition only.

## Task 2 — portable sandbox fixture and green Python baseline

- Changed only the sandbox boundary test to use a resolved temporary directory, preserving the production runner's exact Linux boundary policy. Sibling/nested paths still fail; explicit root and parent symlink cases now fail in the portable fixture too.
- The previous macOS `/tmp` alias failure was reproduced before the fixture change. `python3 -m unittest scripts.ci.tests.test_untrusted_runner -q`: 12 tests passed.
- Full post-repair CI suite: `python3 -m unittest discover -s scripts/ci/tests -p 'test_*.py' -q`: 182 tests passed. The product/release Python suite passed 161 tests after byte repair. No protection, checksum, bootstrap digest or production sandbox behavior was relaxed.
- Gradle diagnostic still awaits Fabric Maven connectivity; this separate network limitation does not invalidate the reproduced Java21/Gradle8.14 launch. Rollback of this unit changes only the host-independent test fixture and this evidence.

## Task 4b — normalized packaged-runtime selection

- `e2e/orchestrator.py` now selects validated normalized lanes, preserving exact authoritative `--row-json` equality. Preparatory default dispatch stays legacy; a modern lane requires explicit `--artifact-node`; full scope requires complete configuration. Schema-2 version/loader filters cannot silently turn a full plan into a partial one.
- Schema-2 listings expose scope, migration mode and target/configured counts; row-json selections are labeled lane scope. Existing schema-1 listing output remains unchanged.
- 37 runtime-selection, matrix-portability and artifact-boundary tests passed. Independent review reproduced them and compared ten schema-1 `--list` outputs against HEAD: byte-identical.
- Before schema-2 packaged execution is enabled, tasks 11/12 must add explicit scope/counts to persistent runtime evidence and compatible artifact/fan-in readers. Current artifact validation still rejects schema 2, so this listing/selection unit does not activate it.
- Rollback is this one consumer and its focused selection tests; no real Minecraft, active-matrix change or publication occurred.

## Task 2 verification gate status

- Required local observation is complete: Java21 wrapper launch, available Java17/21, working-tree preservation and fresh read-only default/governance observations are recorded above. Task 2 can close as an environment-observation gate; it is not build qualification.
- The additional strict Gradle configuration/check diagnostic was cancelled at 600 seconds, exit143, after repeated Fabric Maven connection waits and no task progress. Only its own wrapper/daemon were terminated and verified absent. Official IPv4/IPv6 DNS/TLS-preserving probes confirm the host connectivity limitation; no mirror, origin policy or verification weakening was applied.
- Next build evidence is the isolated Groovy/Stonecutter compatibility probe, then the real selected-lane probes. Full build/E2E task15 remains open.

## Task 4c — normalized client runtime recipes

- Adapted `e2e/packaged_runtime.py`, the actual matrix-to-cache recipe boundary found during inspection; `runtime_store.py` remains a generic cache and needs no separate matrix inventory. Recipes now use the canonical validated runtime row and installer digest from the same matrix.
- Exact JSON equality rejects cross-lane rows, coercible numeric/boolean types, additional fields and installer overrides before launcher or network work. Schema-1 cache identity and host normalization are unchanged.
- Root reproduced `python3 -m unittest tests.test_runtime_recipe tests.test_runtime_selection tests.test_release_matrix_portability -q`: 22 tests passed. Six recipe tests include four independently captured pre-change schema-1 cache digests, current/historical rows, modern configured lanes and malformed/unresolved inputs.
- This corrects task 4's anticipated file boundary to its observed owner, without changing the generic cache or activating schema 2 execution. Rollback is this consumer, its tests and this evidence; no installation, live matrix change or publication occurred.

## Task 6a — probed Stonecutter version and strict plugin closure

- Selected Stonecutter 0.7.11 after inspecting official published source/module metadata: the initial 0.9.8 candidate requires Gradle 9, as do the inspected 0.8+ releases. Keeping the existing Gradle 8.14/Loom baseline avoids an unrelated toolchain migration. The sibling Quick-Skin-Mod checkout currently pins 0.9.7 and does not provide the proposed runner; no sibling code was copied.
- Ignored isolated Groovy probes ran serially with JDK21/Gradle8.14 and strict verification for Fabric1.20.1 and NeoForge1.21.1. Both compiled separate main/E2E source sets through detached Stonecutter preprocessing, produced separate archives, checked class majors61/65 and behavior constants, and preserved all24 fixture inputs. Commands/results are in `build/diagnostics/stonecutter-probe/`; these synthetic probes do not qualify Loom, game dependencies or real lanes.
- Added only the exact Kikugie plugin marker/implementation modules and four reviewed SHA-256 bindings. Other plugin origins and every remote project repository cannot supply these modules. The validator rejects other Kikugie components/versions, missing/extra artifacts, alternate digests and altered origin filters.
- Root reproduced 15 dependency-policy tests; CLI policy validation passes. Existing XML components are unchanged. This unit does not apply the plugin or alter the project graph, wrapper, Loom, live matrix or sources.
- Rollback is this plugin-origin/checksum/policy unit together. Task6 remains partial until selected-node integration and real configuration probes; publication still requires AUTH-1.
