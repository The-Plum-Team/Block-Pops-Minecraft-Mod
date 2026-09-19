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

## Task 7a — serial build planning without execution

- Added `scripts/release/build_matrix.py --plan`, deriving numeric-version/loader order, exact production/harness tasks, target-local clean tasks, isolated Gradle homes and required Java majors from normalized lanes. The selector follows the designed `-PblockpopsLane=<node>` interface.
- Full scope is the planner default, including during preparation; unresolved lanes fail instead of disappearing. `--scope legacy` and `--artifact-node` explicitly produce partial plans. Plans bind the exact securely read matrix bytes and label source/toolchain evidence unverified.
- Fixed command construction always includes `--no-daemon --no-parallel --max-workers=1 --dependency-verification strict`; arbitrary Gradle flags and conflicting selectors are rejected. Planning starts no process and writes no success/report/cache.
- Root reproduced 29 planner/runtime/portability tests; the current real schema-1 matrix produces a two-lane plan. This is task7's independent planning slice; locks, execution, cancellation and atomic failure/success evidence follow separately. Rollback is this new planner/tests/evidence only.

## Task 4d — normalized Gradle settings context

- Added `MatrixDocument.gradle_context` and its explicit CLI projection, retaining the full authoritative matrix alongside isolated normalized lane inputs. Shared configuration requires an exact selected node; preparation defaults only to the legacy context. Unknown/unresolved selections and conflicting eras/families fail.
- Settings now reads that projection and derives included loader projects from selected lanes via `-PblockpopsLane`. Its existing schema-1 execution guard remains until the build/controller adapter is ready. The reviewed settings digest was updated without changing plugin origins.
- Root reproduced 37 context/matrix/policy tests. Independent review compared two schema-1 API results and16 existing CLI outputs with HEAD: identical;16 lane selections and seven inconsistent-input mutations passed their expected checks.
- Serial isolated Gradle8.14/Java21 probes exercised the actual settings body/parser with only the Foojay plugin request omitted: default includes common/Fabric/Forge; each selected lane includes only common and its loader; unknown selection fails during settings. Evidence is under `build/diagnostics/gradle-context-probe/`. No Loom/game configuration or schema2 execution is claimed.
- Rollback is the settings consumer, context projection/tests and exact settings digest. The next build adapter must consume `context.lanes`, never infer a lane from the full matrix's first row.

## Task 6b — reusable existing build conventions

- Extracted the existing root build body/import verbatim to `gradle/build-conventions.gradle`; root plugin requests remain unchanged and apply that file once. This prepares reuse by the required `stonecutter.gradle` controller without copying build logic or changing its behavior.
- Root independently compared the extracted content with the previous committed bytes. Sixteen dependency-policy tests and its CLI pass; the audited Gradle-file inventory now includes the conventions and rejects missing/duplicate root or repository-policy bindings.
- The rename-independent authored count remains below400 including this evidence. No plugin application, selected-node activation or game build occurred; rollback restores this extraction and its binding checks together.

## Task 5b — separate current and protected-next evaluation

- Candidate/self verification now accepts schema2 only against `current`, checking the declared loader even when inactive. A separate `validate_transition` API requires a distinct ancestor base with a transition, exact schema1 collapse to its `next`, complete matching bytes, and changes confined to that loader's declared build/bootstrap paths and contract.
- The future protected caller must authenticate the base as deployed authority; ancestry supplies no authorization. This API is deliberately absent from the self-check CLI and has not been connected to `pr_gate` or workflows.
- Root reproduced 55 bootstrap/PR-gate/sync tests. Negative cases cover mixed/stale generations, wrong collapse, other loaders, matrix edits, undeclared files and gitlinks hidden by local Git configuration. Independent review found and fixed inactive-current verification before final validation; schema1 reports remain identical.
- No actual contract or executable bytes changed. Task5 remains open for exact future transition records and authenticated controller integration. A loader lacking verifiable current bytes requires a separately reviewed protocol; rollback is this evaluator/tests/evidence unit.

## Task 4e — normalized artifact staging and verification

- Artifact staging and re-verification now use normalized lane identities and archive paths. The existing schema2 artifact format for schema1 matrices is preserved; schema2 matrices still fail explicitly until scoped schema3 provenance is implemented, before staging or Git identity work.
- Added round-trip coverage in an isolated committed fixture repository, including exact matrix/commit/tree/file hashes, cross-lane and duplicate manifest mutations, malformed matrix input and the preparatory/shared execution boundary.
- Root reproduced 36 artifact/matrix tests and separately ran old and new staging/verifier implementations against the same fixture: manifests and verification results are identical. Synthetic archive contents test validation only, not real Minecraft qualification.
- Rollback is this one consumer/tests/evidence unit. Dirty local user work remains preserved; no release staging was attempted against this working checkout.

## Task 7b — checkout exclusion and owned process cleanup

- Added an exclusive checkout lock that never steals stale locks, rejects linked/replaced paths and requires its live ownership token for process execution. Concurrent threads/processes cannot spawn another lane under that checkout lease.
- POSIX subprocesses own a new session/group; cancellation terminates only that group, escalates when needed and waits before releasing the lock. Incomplete cleanup retains the lock. Windows execution fails before spawning until its process-tree implementation is ready; the CLI still requires `--plan`.
- Root reproduced43 planner/runtime/context tests. A real Python parent/child cancellation test proves the owned tree exits while a separate sentinel survives; other tests cover startup failure, stale/replaced locks, cross-process contention, ordering and escalation. No Gradle ran in these tests.
- Rollback is these lock/process primitives and tests. Task7 still needs toolchain/source/output validation, atomic status reports and CLI orchestration; this unit grants no build qualification.

## Task 6c — isolated Stonecutter topology and normalized build inputs

- Settings can construct exactly the selected common/version and loader/version nodes with a detached `stonecutter.gradle` controller; parent descriptors remain empty. Shared conventions now derive version, runtime dependencies, repository family and configured project paths from selected normalized lanes.
- An explicit pre-configuration guard keeps Stonecutter game execution disabled until common/loader/E2E adapters are complete. Selected FML legacy execution also reports the missing common annotation-dependency adapter; the existing default legacy aggregate remains intact. No silent cross-lane input is borrowed.
- Root reproduced21 context/policy tests and the policy CLI. Serialized isolated probes used exact settings/controller bytes and strict metadata: modern topology was exact and hit the expected guard before game/plugin configuration; schema1 and preparing defaults retained legacy descriptors. Probe inputs remained unchanged; results are under `build/diagnostics/stonecutter-context-probe/`.
- This is a selected-node foundation, not working Minecraft compilation or task6 completion. Rollback includes controller/descriptor/convention wiring and its policy inventory/digest updates.

## Task 6 protection follow-up — cover every new Gradle controller

- Added exact protected-parity and CODEOWNERS entries for the extracted conventions, Stonecutter parent descriptor and root controller. New executable build logic must not become an ordinary candidate-controlled input merely because it moved outside the previously protected root script.
- Twenty-one controller/sync tests pass. A real temporary Git history independently changes each new path and confirms the unchanged parity evaluator rejects it; no exception or admission route was added.
- These are local candidate protections, not evidence of deployed authority. Rollback must accompany removal of the corresponding new controller files; publication remains under AUTH-1.

## Real legacy baseline — DNS recovery and matrix-pinned transformer input

- Recovered official Fabric connectivity using freshly observed AliDNS A records172.67.151.177/104.21.33.240 and a temporary Java-process hosts file. Original HTTPS hostnames, certificate checks and strict dependency verification remain intact; no system resolver, repository origin, checksum or user configuration changed. Java21 independently fetched the installer into memory and reproduced its pinned digest. Evidence/limitations: `build/diagnostics/task2/fabric-connectivity-findings.md` and resolver JSON.
- `JAVA_HOME=<Temurin21> JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=<diagnostic hosts> ./gradlew --no-daemon --no-parallel --max-workers=1 --dependency-verification strict -Dorg.gradle.java.installations.paths=<cached JDK17> validateReleaseMatrix check` passed in58s, compiling current Fabric/Forge sources. Java test tasks report NO-SOURCE; this is not a unit-test claim.
- The subsequent archive build failed closed because Architectury3.4.164 independently requests `net.fabricmc:fabric-loader:+` for its transformer and selected unreviewed0.19.5. Official source and the verified local plugin bytecode agree. Added a configuration-specific resolution pin to the existing matrix-owned0.17.3; no checksum additions or plugin upgrade.
- Repeated `buildAllLanes buildAllE2EHarnesses` with the same strict/JDK flags: passed in10s,27 tasks. Both production/harness pairs pass physical archive validators; BlockPops classes are major61 and all bundled classes are Java17-compatible. SHA/size records are in `legacy-archive-boundaries.json`. Sixteen dependency-policy tests also pass.
- These are dirty-checkout legacy diagnostic builds only, without release staging or packaged client execution. No twelve-lane qualification is implied. Rollback of the six-line transformer pin restores the reproduced dynamic-dependency failure; preserve the remaining verification policy.

## Task 1a — inert restricted-transition declarations and decision binding

- Added a strict bounded declaration parser with eleven static exact-file scopes covering matrix, checksums, both version-specific paths, Stonecutter bootstrap and separate contract/next phases for each loader. It binds schema/generation/base/controller/head to independently observed deployment and PR identities; wildcards, directories, extra fields and candidate-supplied authority are not accepted.
- A separate pure binder ties an externally authenticated owner decision to repository/PR, generation, base/controller/head and declaration digest. Ordinary controller-upgrade approval, revoked/mismatched decisions and coercible numeric types fail.
- Root reproduced41 PR-gate tests; independent review found no blocker within these helpers' contract. Neither helper is called by the evaluator, CLI or reauthorization paths. They authenticate no API/deployment and grant no admission; diff/mode/bootstrap/evidence enforcement and deployed authority remain pending.
- Rollback is these inert helpers/tests. Task1 stays unchecked; AUTH-1 still blocks all delivery. Controller-policy changes cannot be smuggled into a Stonecutter payload scope.

## Task 11a — opt-in embedded artifact identity rule

- Added an expected lane identity derived from normalized build context plus caller-authenticated matrix/contract/commit/tree digests. Opt-in production and harness verification requires the exact same embedded `META-INF/blockpops-build.json`; renamed cross-lane pairs, stale inputs, extra keys and coercible numeric/boolean identities fail.
- Production metadata must also carry the lane's actual mod version when this rule is enabled. Fabric uses its version field; FML parses the BlockPops mod record rather than accepting a matching text fragment elsewhere.
- Root and independent review reproduced30 artifact tests, including both metadata formats and preserved legacy round trips. The legacy path remains unchanged when no identity is requested; schema2 matrix staging still fails pending the scoped manifest producer/reader.
- This prepares one provenance rule, not schema3 activation or qualification. Future staging must authenticate the caller digests and require this identity for both archives. Rollback is these opt-in helpers/tests.

## Task 6d — explicit common annotation input

- Normalized Gradle context now names the exact same-era Fabric lane supplying the common Fabric annotations/transformer coordinate. This is an explicit common compile-tool dependency, not a second selected runtime or repository family; no first-row or other-era fallback exists.
- Missing/unconfigured same-era Fabric input fails before Gradle. Reordered matrices preserve the selected Forge/NeoForge runtime, mod version and repository family and do not add another loader project.
- Root reproduced23 context/portability/provenance tests; the agent also ran32 context/schema2 tests. The real Forge projection retains only Forge while explicitly naming `net.fabricmc:fabric-loader:0.17.3` for common annotations. No Gradle/source-adapter activation occurred in this unit.
- Rollback is this projection field/tests; the next convention unit consumes it. Embedded context digests include this explicit common input in addition to the full authoritative matrix digest.

## Task 7c — source/output snapshots and atomic run reports

- Added stable regular-file snapshots, tracked/index/raw source identity and relevant untracked build inputs, including Git-ignored sources that Gradle can still compile. Generated files, `.git` and `.pi` are excluded. Concurrent HEAD/index changes, links, hardlinks and file mutation during reads fail.
- Run reports atomically replace prior success with `running`, bind a fresh run ID and live checkout lease, and finish successfully only for exact planned lane order, zero exits, unchanged source/matrix/contract inputs and unchanged output hashes. They do not claim runtime qualification or clean release provenance; CLI execution remains disabled.
- Root reproduced54 planner/runtime/context tests. Review caught and fixed ignored-source omission and concurrent index drift; dedicated tests prove both invalidate evidence. Other mutations cover missing/changed outputs, stale runners and publication while a process is active.
- Rollback is these snapshot/report primitives and tests. Toolchain validation and orchestration remain task7 follow-ups; Windows process execution is still explicitly unavailable.

## Task 1b — exact restricted-tree boundaries

- Added an opt-in structural verifier binding the declaration to authenticated base/head/synthetic-merge identities, exact ancestry/tree equality, declared changed-file inventory and regular non-executable Git blobs. Extra authority/product paths, hidden submodules, unsafe modes and widened rename scope fail.
- Independent adversarial Git fixtures reproduced replacement-object and graft bypasses. The existing protected Git reader now sets `GIT_NO_REPLACE_OBJECTS=1` and `GIT_GRAFT_FILE` to the null device; neither user refs nor configuration were modified.
- Root reproduced50 PR-gate tests, including nine new real-Git structural/adversarial cases. The helper still supplies no admission and has no evaluator/CLI wiring. Loader-phase semantics, owner-API authentication and exact gate evidence remain separate pending checks.
- Rollback is this structural helper/tests; retain equivalent exact-object hardening if reorganizing the reader. Task1/AUTH-1 remains unresolved for deployment and delivery.

## Task 6e — selected common and loader conventions

- Common annotations and both independent loader-resolution configurations now use the explicit same-era matrix coordinate. Conventions expose the selected common project path and normalized loader record; the resolved legacy FML guard is removed, while Stonecutter game execution remains guarded.
- Root reproduced23 context/policy tests and the strict policy CLI. Serial isolated Forge and Fabric archive rebuilds passed with Java21 launching Gradle and Java17 compilers observed; all four production/harness JARs are byte-identical to the verified legacy baseline. The Fabric rebuild first rejected GeckoLib's alternate loader pin, then passed with the matrix pin on `modCompileOnly`; metadata/checksums remained unchanged.
- Evidence in `build/diagnostics/selected-context/result.json` records498 unchanged inputs, archive hashes and no remaining Gradle process. These are dirty local diagnostics, without release staging or client E2E. Rollback is the convention/guard changes plus exact settings-policy digest.

## Task 7d — explicit toolchain probes and bindings

- Toolchain validation reconstructs the canonical plan before probing explicit JDK homes. Missing/conflicting homes, changed launchers, mismatched Java/compiler versions, daemon JVM criteria and inherited JVM/Gradle option injection fail before Gradle. Commands bind Java21, serial execution and only the selected explicit toolchain paths, with auto-detection/download disabled.
- Root reproduced44 planner/context/runtime tests and real read-only Java/javac probes for17.0.19 and21.0.10. Exact homes and stable executable/release-file hashes are recorded in `build/diagnostics/task2/explicit-toolchain-probes.json`; no Gradle ran in this unit.
- The result explicitly remains `probed`, with Gradle JVM/compiler selection unverified. The future executor must reuse the validated environment and observe actual Gradle selections. Rollback is this API/tests unit; CLI execution and Windows process-tree ownership remain pending.

## Task 11b — recursive production and harness boundaries

- Archive verification now inspects bounded nested content, including renamed/prefixed ZIPs and effective Java multi-release paths. Production rejects harness namespaces/resources/loader IDs; harnesses reject production or third-party classes/resources/metadata. Entry, depth, total-byte and archive-count budgets apply across the whole tree.
- Independent review reproduced two initial bypasses (prefixed ZIPs and multi-release classes); fixes and negative cases are included. Root reproduced38 artifact tests, and the reviewer reverified all four actual legacy JARs successfully. Those archives retain legacy provenance and are not client-E2E evidence.
- Existing schema2 readers remain compatible. Rollback is the recursive boundary rule and tests; schema3 scope/producer work remains separate.

## Task 5c — prepare the exact Fabric common-path transition

- Prepared generation1 `current`/`next` for Fabric only. Current remains the complete prior contract; next changes only its build digest for two `':common'` references becoming `rootProject.common_project_path`. Executable files remain unchanged in this preparation commit.
- Root reproduced14 bootstrap tests and independently checked the immutable current bytes, exact two substitutions and next SHA256 `d947288675e732491aa8a627543b432b02058892b539a01341e08de3dfe0cca4`. Fixture Git verification accepts current and rejects next without collapse. Test setup explicitly extracts current, preventing proposed next from becoming fixture authority.
- Proposed bytes and fixture evidence are under `build/diagnostics/fabric-common-path-transition/`. The following local commit must contain only exact Fabric build bytes plus schema1 next collapse and pass `validate_transition` against this preparation commit. Rollback treats those two phases together; neither is deployed authority or permission to publish.

## Task 5c result and 5d — exact Fabric collapse and Forge preparation

- Fabric preparation `4df3cea` and exact-next `eed2f48` both pass self-verification; `validate_transition` authenticates the latter against the former. The next commit changed only its two declared files. Exact-commit evidence is in `fabric-common-path-transition/exact-commit-verification.json`; no published authority is implied.
- Prepared the equivalent separate Forge transition, retaining Fabric's new pin and every other current binding. Only the Forge build digest changes in next, to `03f91ac3ab04fa24853338fa9ce2ba3e52b22a949d5d10e7e97317a37f76f102`, for exactly two common-project substitutions. Current executable bytes remain untouched in this preparation commit.
- Root independently verified the exact immutable substitutions and hashes;14 bootstrap tests and the isolated current/next fixture pass. Proposed bytes/evidence are under `build/diagnostics/forge-common-path-transition/`. Apply only Forge build plus schema1 collapse in the following commit, then verify it against this preparation; rollback retains the same two-phase boundary.

## Task 7e — observe actual Gradle and compiler selections

- Added an opt-in protected init script recording the Gradle JVM, main/common/loader/E2E compiler homes, majors, versions, destinations and task outcomes. It checks serial settings and explicit homes before compilation and rechecks selections around each task; cached/up-to-date selection is distinct from work performed. No independent source or release-qualification claim is made.
- Three opt-in integration tests ran ten serial offline/strict Gradle8.14 invocations: actual Java17/21 bytecode61/65, up-to-date reuse, and rejection of wrong JVM/compiler, parallelism, executable override, fractional schema/major and oversized request. Logs/receipts are in `build/diagnostics/task7e/`. Root inspected those receipts and reproduced the controller test module; protected parity and CODEOWNERS include the new executable.
- The script requires a bounded request; it is not yet invoked by the runner. Python must next generate canonical requests and validate their exact hashes, fresh receipts and source/output snapshots before treating an invocation as successful. Rollback removes observer, matching tests and its protection entries together.

## Forge transition result and NeoForge current-byte restoration

- Forge preparation `988f740` and exact-next `aac4d40` pass self-verification and exact transition validation. The executable commit contains only the two declared paths; evidence is in `forge-common-path-transition/exact-commit-verification.json`.
- Restored only the three already-bound NeoForge bootstrap files from historical commit `6b5651e6675347195f50d8024616a4fb7e978de9`: its build script, E2E entrypoint and metadata. Root independently compared every byte with that immutable source and all three existing contract pins. No historical main sources, matrix or contract bytes changed; NeoForge remains inactive/unconfigured.
- The isolated validator fixture explicitly checks the inactive current loader as well; evidence is under `build/diagnostics/neoforge-current-restore/`. This restores the existing current prerequisite for a later exact transition, not a NeoForge game build. Rollback removes only these three restored files.

## Task 1c — bind restricted loader proposals to their exact phase

- Added an opt-in semantic verifier above the exact-tree boundary. Contract-first requires an unchanged complete protected current schema1 contract and the declared loader's schema2 proposal; exact-next requires that loader's protected transition and delegates exact byte/collapse validation. Candidate self-validation cannot select next.
- Root reproduced72 PR-gate/bootstrap tests. Real Git fixtures cover both phases for all three loaders and stale, mixed, inactive-current and unauthorized variants. Bootstrap Git reads now independently suppress replacement objects/grafts, with adversarial regressions. Review found no blocking issue within this helper's contract.
- No evaluator, CLI or admission path calls this helper. Authenticated owner API decisions, exact gate evidence and deployed authority remain pending; fixture records are not published grants or gameplay evidence. Rollback is this semantic helper and tests, preserving exact-object Git reads.

## Task 11c — dual reader with caller-owned schema3 scope

- Added schema3 verification against an explicit caller scope and normalized configured lanes. Headers bind the full authoritative matrix, scenario contract, exact source commit/tree, twelve targets and selected inventory; each row binds its own version/toolchain/context and both embedded archive identities. Schema2 historical verification remains available without scoped arguments; schema2-matrix staging is still blocked pending its producer.
- Root reproduced45 artifact tests. Negative cases cover partial-as-full/legacy claims, wrong lanes, duplicates, stale inputs, coercible numeric fields, linked parents/hardlinks and unlisted staged objects. Review reproduced a late hardlink race; final path, manifest bytes and inventory revalidation now reject it and equivalent late mutations. Git identity reads ignore replacement objects/grafts.
- Fixtures exercise validation only, including a complete twelve-row context; no real schema3 Minecraft archives exist yet. Dirty tracked work still prevents release provenance. Rollback is the scoped context/reader/tests; producer, CLI and independent qualification remain subsequent units.

## Observer integration correction — exact Gradle inventory

- The first combined policy check rejected the new protected observer because its exact Gradle-file inventory had not been extended. Added that single path to the existing audited set; the normal script-content checks still apply to it.
- Twenty-three context/policy tests and the policy CLI pass. No checksum, repository filter or exemption changed. Keep this inventory entry with the observer, including on rollback.

## Task 6f — normalized E2E lane and common binding

- The harness convention now requires the project's normalized lane to belong to the selected Gradle context and match its loader/project/common paths. It no longer chooses by `project.name` or the first loader row in the full matrix. Existing legacy source roots and separate packaging remain byte-equivalent; Stonecutter still awaits preprocessing adapters.
- Serial strict Java21/Java17 rebuilds passed for the default aggregate and isolated Fabric/Forge, with27/15/15 tasks respectively. All four archives retain their baseline hashes;501 actual compile inputs and HEAD remained unchanged. Two negative configuration probes reject a wrong-project lane and a lane outside the selected context. Evidence: `build/diagnostics/e2e-context-binding/result.json`.
- The first diagnostic init used a dependency query at the wrong Gradle lifecycle point and failed; only that ignored probe was corrected before successful reruns. No game-code workaround or checksum change occurred. Rollback is this harness binding; these diagnostic builds still lack clean release/client-E2E qualification.

## Task 4f — bootstrap verification consumes normalized inventory

- Loader bootstrap verification now normalizes secure immutable Git matrix bytes for either schema. It verifies every configured loader, including a NeoForge configuration outside preparing mode's legacy dispatch; unresolved targets do not invent executable bootstraps. No mutable worktree source validation or new execution route is introduced.
- Root reproduced20 bootstrap tests; the agent also passed76 PR-gate/bootstrap tests and compared schema1 report bytes with the prior implementation. Fixtures cover preparing2, preparing4, shared12, malformed pairs, exact-next transitions and isolation from later worktree mutation. The report format and authoritative raw matrix hash remain unchanged.
- Live matrix/contract bytes are unchanged. Synthetic complete inventories test reader behavior only. Rollback is this one consumer and its tests.

## Task 7f — bind and validate fresh build observations

- The runner can now generate one canonical observation request per leased run/lane and validate a fresh exact receipt against private lease-owned expectations. Commands, source/matrix identity, JDK/init/request hashes, compiler homes/versions/outcomes and real class-output snapshots must agree; missing, stale, replayed, no-source or arbitrary skipped receipts fail.
- Root reproduced46 runner/context tests plus the final receipt-shape regression. These receipt fixtures are synthetic protocol tests; actual Gradle observation was verified separately in7e. Raw source snapshots now ignore Git replacements/grafts and retain consumer override sources while excluding only observed Stonecutter generated build directories.
- CLI execution remains pending. The next executor must call these primitives around each serial process and preserve runtime/qualification separation. Rollback is request/receipt binding and tests, retaining equivalent immutable Git identity hardening.

## Task 11d — scoped producer and explicit verification command

- Schema2 matrices can stage schema3 bundles only with explicit lane/legacy/full selection; the same selector is required on re-verification. Both archives must already contain the exact expected build identity. Paths resolve against the chosen repository; schema1 retains its unscoped interface.
- Schema3 source provenance now reuses the runner's complete raw tracked/untracked input snapshot. Review reproduced hidden tracked modifications that Git status checks alone accepted; the byte audit rejects them. A second adversarial case replaced the stage parent during copy: all writes, cleanup and atomic manifest publication now use anchored no-follow directory descriptors, and the unrelated sentinel remains untouched. Unsupported descriptor platforms fail before staging.
- Synthetic round trips cover lane1, preparing legacy2 and shared full12 across all three loaders, with mutation/dirty/link/stale-success rejection. Root's full suites pass:252 Python tests with3 opt-in Gradle smoke skips, and216 CI tests. Two fixture-only stat-cache flakes were corrected by refreshing the temporary test index after restoring bytes; user index/files were untouched.
- These bundles are fixture evidence, not real schema3 game builds. Gradle identity emission and packaged client qualification remain pending. Rollback is producer/CLI/tests/docs together, retaining the already-committed dual reader and historical manifest support.

## Task 6g — detached production/E2E preprocessing adapters

- Detached main roots now consume Stonecutter-generated sources/resources. Common E2E is preprocessed separately and compiled once with the loader harness; generated scenario identities stay outside Stonecutter. FML pack metadata is resolved lazily from generated production resources and missing output fails during resource processing.
- Serial strict/offline Gradle8.14 probes using Stonecutter0.7.11 passed synthetic Fabric/Java17 and NeoForge/Java21 builds, separate archive boundaries and bytecode61/65. A negative missing-generated-pack probe fails as intended;34 probe inputs remained unchanged. Evidence is under `build/diagnostics/stonecutter-source-adapters/`.
- Root reproduced16 dependency-policy tests and the policy CLI. The probe applies the exact main adapter fragment and full harness convention without Loom; it does not prove real game compilation, remapping or overlay routing. The global Stonecutter game guard remains active. Rollback is these two convention adapters together.

## Task 5e — prepare the exact inactive NeoForge transition

- Prepared generation1 for NeoForge's two common-project references, retaining the complete current contract and every other pin. Next build SHA256 is `edf6a066c9df73fc563419d21232c742c927991e7842fda164180991d036a77a`; no executable bytes change in this preparation commit.
- Root independently checked the exact historical/current bytes and proposed two substitutions. Twenty bootstrap tests and isolated current/next fixtures pass, including the declared inactive NeoForge loader and rejection of next without collapse. Evidence is under `build/diagnostics/neoforge-common-path-transition/`.
- Apply only NeoForge build plus exact schema1 next collapse in the following local commit, then validate against this preparation. Matrix schema/activation and publication remain unchanged; rollback preserves the same separate two-phase boundary.

## NeoForge transition result and task 11e — embedded identity generation

- NeoForge preparation `37b7cb3` and exact-next `c89e18d` pass self and protected-next validation, including the inactive declared loader. The next commit contains only its build script and contract collapse; exact evidence is in `neoforge-common-path-transition/exact-commit-verification.json`.
- Added a deterministic lane-identity generator with fixed matrix-derived output paths and anchored atomic publication. Clean raw source bytes emit the reader's exact identity; dirty/hidden/untracked inputs produce an explicit diagnostic fingerprint that release verification rejects. No Gradle task invokes it yet.
- Root reproduced10 generation/identity tests. Review reproduced a source mutation during atomic publication; a final raw snapshot now rejects that race and removes only the newly generated owned identity. Other cases cover clean legacy/detached paths, reproducibility, linked paths and mid-read drift.
- This is a local producer primitive, not a qualified game build. Gradle resource wiring remains next; rollback is the generator/tests. Current legacy archive contents and the live matrix are unchanged.

## Task 1d — authenticate exact owner decisions

- Added an opt-in API reader for exact restricted-transition owner commands. It binds repository/PR/base/head/controller generation/declaration and unchanged comment bytes, rechecks authenticated PR identity, and requires fresh direct-comment and paginated inventory reads. Revoked, stale, malformed, edited, deleted-during-read and wrong-owner records cannot fall back to an older approval.
- Root reproduced65 PR-gate tests, including pagination and changes during reads. Independent review caught an edited-away revocation and timestamp tie; any newer owner edit now blocks until a fresh exact decision. GitHub cannot reconstruct a comment deleted before the first observation; the helper documents that limit.
- This helper grants no admission and changes no evaluator, CLI or workflow. The caller must retain the bound digest and repeat authentication after gate-evidence selection. Deployed authority and publication remain pending. Rollback is the API reader/binding/tests together.

## Task 6h — real Loom detached adapter diagnostics

- Gradle matrix validation now checks the complete normalized inventory. Detached nodes receive distinct module groups: real Loom resolution previously confused common and loader projects sharing the same group/name/version. The legacy identity remains unchanged.
- A copied501-input fixture projected the unchanged schema1 1.20.1 contexts into detached paths. Serial strict Java21/Java17 Fabric and Forge production/harness builds pass after the group correction; focused configuration assertions prove unique identities and common transformation selection. Root reproduced17 policy tests. Logs and exact comparisons are in `build/diagnostics/stonecutter-loom-1.20.1/`.
- Both harness JARs match the baseline byte-for-byte. Production differences are limited to Architectury-generated platform class paths and the detached refmap name; all four archives pass boundaries. The first Forge copy omitted its real `gradle.properties`; the corrected copy includes it and passes without a product workaround.
- This is real Loom compilation/remapping in a projected diagnostic topology, not schema2 lane, NeoForge, overlay or client-E2E qualification. Live matrix and Stonecutter game guard remain unchanged. Rollback is module identity plus inventory command/policy/test.

## Task 7g — serial executor and explicit CLI

- Connected lock, stale-success invalidation, explicit JDK probes, serial owned processes, fresh observations, archive boundaries and final raw source/output checks. The CLI now executes with explicit homes or emits a pure `--plan`; reports label build scope and never assert release qualification. Missing same-era annotation inputs fail during planning.
- Root reproduced54 runner/context tests. Simulated process failures, startup errors, cancellation, invalid observations, missing/leaking/changed archives and source drift stop later lanes. Inherited nonempty Gradle project variables fail before probes. Review reproduced a replaceable log-parent escape; descriptor-anchored exclusive creation now prevents the external write and process start.
- These executor tests simulate Gradle and use real archive validators; actual runner invocation remains next. The earlier real observer and Loom probes remain separate evidence. Windows fails before process creation until owned process-tree cleanup exists. Rollback is executor/CLI/tests and its operations command documentation.

## Task 12a — explicit scoped packaged-runtime input binding

- Schema2 staged listing/execution now requires an external lane/legacy/full selector and verifies the matching schema3 bundle. Runtime coverage records exact selected nodes, scenarios, partial status and artifact scope; schema1 output shapes remain unchanged. Scoped inputs are verified again before evidence promotion.
- Independent review reproduced two mismatches: a cached scenario contract and an already-loaded runtime matrix could disagree with the verified bundle. The orchestrator now normalizes one secure matrix read and binds its raw hash plus the loaded contract hash to every staged verification. CLI paths retain links for rejection instead of resolving them away.
- Root reproduced11 runtime-selection tests. Real staged validators reject wrong/full scope, source drift, linked parents and both loaded-input mismatches; game execution is simulated and no gameplay pass is claimed. Late failure leaves prior evidence intact. CI fan-in schema3 consumption remains a separate unit. Rollback is this orchestrator adapter and tests.

## Task 1e — compose restricted-transition evidence without admission

- An opt-in helper combines authenticated identity, exact immutable-tree/loader-phase checks, fresh owner decisions and two newest-exact Build/E2E observations. It binds immutable artifact IDs/digests/sizes and validated job identities, rechecks owner decisions after each observation, then rechecks checkout/tree identity.
- Expected graphs come from the unchanged schema1 base matrix. Matrix transitions and schema2 protected bases explicitly remain unsupported; candidate contents cannot supply their graph. Root reproduced74 PR-gate tests, including real temporary Git trees and simulated authenticated API changes, incomplete/failed gates, replacement artifacts and revoked decisions.
- The result explicitly has `admission: false`. Existing evaluator, CLI, writer reauthorization and workflows remain disconnected; no deployed or publication authority is claimed. Rollback is helper/evidence capture/tests, leaving prior ordinary/controller behavior intact.

## Task 4g — normalized matrix CLI projections

- Matrix CLI projections now use normalized lane selection: preparing defaults to legacy dispatch, shared requires complete coverage, and explicit node/full/legacy selectors remain validated. Raw and inventory output always retain the whole authority. The schema1-only programmatic reader remains fail-closed for consumers not yet adapted.
- Root reproduced25 matrix/report/portability tests, including byte-for-byte schema1 output for all six projections, partial preparation, explicit modern selection and unresolved/full rejection. Independent review reproduced29 matrix/graph tests. The dependency-policy CLI also passes.
- This emits dispatch data without qualifying or building a lane. Live schema1 matrix bytes remain identical; workflow scope arguments, fan-in and trusted-controller readers still need separate adapters before preparing-mode enrollment. Rollback is CLI selection/tests.

## Task 12b — exact scheduled versus PR runtime selection

- Added an explicit `--projection` for row-json validation, preserving PR anchors as the default. Scheduled rows previously failed because the orchestrator always compared them with PR rows. Each request now matches only its selected authoritative projection; runtime/non-anchor projections and numeric/boolean type aliases are rejected.
- Root reproduced19 runtime-selection/recipe tests across schema1, preparing legacy/modern and shared inventories, including crossed projection failures. The action still needs to forward its already-owned projection in a later workflow unit; no scheduled game execution is claimed. Rollback is this selector/comparison/tests.

## Task 12c — scoped per-lane fan-in verification

- The per-lane API and expected-lane projection now consume normalized matrices with explicit external schema2 scopes. Already-verified schema3 bundles must match the loaded raw matrix/contract, complete selected inventory, per-lane metadata and embedded identities. Payload coverage distinguishes one executed lane from its wider artifact bundle; final input re-reads reject drift.
- Root and independent reviewer reproduced24 legacy/scoped fan-in tests. Existing screenshot/report/profile checks run on synthetic payload fixtures for legacy2, modern lane1 and a full12 bundle; scope, typed-row, identity, scenario and late-input mutations fail. These fixtures do not qualify Minecraft gameplay.
- Aggregate creation/validation and the CLI still reject schema2. Their next adapter must pass caller-owned scope to staged verification before invoking this API. Rollback is scoped per-lane helpers/payload validation/tests, preserving legacy aggregate shapes.

## Task 12e — forward the action's anchor projection

- The packaged-runtime action now forwards its existing authoritative projection input to the orchestrator through the already-permitted `BLOCKPOPS_PROJECTION` variable. Runtime selection and fresh validation therefore use the same PR/scheduled projection; credential/account boundaries and upload conditions are unchanged.
- Root reproduced33 workflow/security-boundary tests. This completes the action wiring for12b, without claiming a GitHub or game run. Schema2 artifact scope wiring remains pending. Rollback is action argument/environment plus its contract test.

## Source-route correction — reject undeclared Stonecutter node overrides

- Inspection of the pinned Stonecutter0.7.11 API confirmed that node-local `versions/<node>/src` is an override input, while generated sources live under `build/`. Schema2 source validation now rejects nonempty node overrides and linked version roots/nodes, preventing undeclared sources from bypassing canonical/legacy-overlay routing.
- Root reproduced16 source/report tests, including main/E2E overrides, linked inputs and accepted generated build output/empty override directories. No source was moved and the Stonecutter game guard remains active. Rollback is this source-inventory check and tests.

## Tasks 7h/7i — real executor rejection and bounded optional test compilers

- In a clean isolated copy of `b7da037`, the real runner completed Fabric's strict build with explicit Java21/17 and no DNS/JVM injection, then correctly stopped before Forge because the receipt had an extra Loom `compileTestJava` task. Its exact `NO-SOURCE` outcome exposed a mismatch between the observer's complete compiler inventory and Python's three-task expectation. Source stayed unchanged and the owned lock/processes were released; evidence is under `build/diagnostics/task7h/`.
- Python now requires all three main/E2E compilers and admits only the selected common/loader test compilers at canonical test output paths, with the same explicit toolchain. Only these optional tasks can report exact `NO-SOURCE`, and residual classes, linked paths, arbitrary extra compilers or skipped outcomes still fail. Root reproduced50 runner tests; independent review found no weakened required-task acceptance.
- The failed run is not a successful runner or client-E2E qualification. Its Fabric harness matches the baseline; production differs in recorded LF conversion, javac debug names and Architectury-generated names across checkouts, so byte equivalence is not claimed. A fresh clean-copy runner retry remains next. Rollback is the optional receipt binding/validation/tests.

## Task 12d — scoped aggregate evidence reader

- Aggregate validation accepts schema2 only with external scope, an already-verified schema3 bundle and its raw digest. Exact aggregate coverage binds selected/target nodes, migration state, projection and scenarios in receipt/summary/resolved records; artifact hashes and source commit/tree stay bound. Source API authentication still requires the existing complete external identity group.
- Root reproduced32 fan-in tests. Synthetic legacy2, scheduled Neo lane1 and shared12 aggregates pass existing profile/pixel validation; omitted lanes, partial-as-full, stale source/input/digest and pixel tampering fail. Eight red-to-green cases also corrected boolean/float schema aliases in lane and aggregate summary/runtime-store records. Independent review found no blocker.
- Producer and CLI remain schema1-only until their next units. Structural receipt validation alone is not source-run authentication or game qualification. Rollback is the dual aggregate reader/tests, retaining exact typed schema checks.

## Task 11f — wire one identity into both loader archives

- Schema2 loader builds now generate one shared identity before source generation, preprocessing and compilation, then include it in production and harness resources. The generator compares the complete captured Gradle context with freshly validated inputs. Preparing legacy aggregate builds remain usable but carry an explicit diagnostic identity; only an isolated clean lane can match release verification. Schema1 output is unchanged.
- Root reproduced18 identity/context tests. Four strict/offline Gradle8.14 fixtures passed on JVM21 with Java17/21: schema1, isolated legacy, detached Neo and preparing aggregate. Both archives share the expected identity; schema1 fixture bytes are unchanged, and diagnostic identities fail release checks. These fixtures exercise the exact convention wiring without Loom/Minecraft. Inputs remained unchanged; evidence is under `build/diagnostics/build-identity-wiring/complete-context-*`.
- Rollback is generator/context validation and both convention adapters together. Real schema2 game compilation remains pending.

## Task 7j — real serial legacy build and staged round trip

- A fresh clean local copy of `e0bcd802285afcd2097a751aceed4de1ed19be1c` completed the real runner for Fabric/Forge1.20.1 in43.96 seconds, with Gradle JVM21 and observed Java17 compilers. All four archive boundaries passed. The unscoped verifier staged and reverified the historical schema2 manifest, whose digest is `7503f77776b3b8a1248458716889e0028f7c70f3b950315d1cd48337a1b5ad6f`; staged hashes match the runner report.
- All680 source inputs, commit/tree and report stayed unchanged. The lease was released and both owned daemon processes exited. No DNS override or prewarming was needed. Evidence is under `build/diagnostics/task7j/`, including `result.json` and `staging-result.json`.
- This is successful local compilation/staging of the existing two lanes, not packaged-client interaction, twelve-lane qualification or publication. User work and the live matrix remain untouched.

## Task 12f — scoped aggregate producer

- Aggregate creation now uses the same normalized external selection and coverage helpers as both readers. It binds every lane payload to the already-verified bundle, source commit/tree and raw inputs, then revalidates matrix/contract and the download inventory before publication. Legacy receipt behavior remains supported.
- Root reproduced38 fan-in tests, including synthetic legacy2, scheduled Neo lane1 and shared12 producer/reader round trips. Missing/unknown lanes, crossed coverage, stale source identity and input drift fail without accepting an aggregate. These protocol fixtures are not game qualification.
- Independent review reproduced a preexisting publication-parent swap in the shared atomic-directory helper. CLI activation is deferred until the next separate filesystem-hardening unit closes that race. Rollback is scoped producer/helpers/tests; external authentication still belongs to its caller.

## Task 12h — matrix-owned default dispatch selection

- Added a small shared CLI for workflow callers: normalized schema1 emits `unscoped`, preparing emits `legacy`, and complete shared emits `full`. It reuses the matrix's selection API and accepts no bundle-derived or custom scope. Source inspection defaults on; disabling it still validates the complete configuration.
- Root reproduced5 focused tests for malformed/incomplete/type-aliased inputs, explicit paths outside the checkout and misleading manifest data. The live matrix emits `unscoped`. This helper grants no authentication or qualification and changes no workflow yet. Rollback is helper/tests together.

## Runner correction — retain the Gradle check lifecycle

- Workflow review found that serial production/harness tasks omitted the `check` selector used by the existing gate. Each isolated command now also requests `check`, retaining common/loader verification tasks and their failure propagation.
- The mocked executor regression first reproduced an incorrect success when only `check` would fail. Root then reproduced50 runner tests with the correction: that failure stops before the next lane and replaces prior success with a failed report. A real failing Java-test probe remains pending; no new game qualification is claimed. Rollback must retain equivalent lifecycle checks before using the runner in CI.

## Task 12i — scoped Build workflow dispatch

- The Build workflow installs Java17 before its matrix-owned Gradle JVM and passes the compiler path as a quoted positional argument through the existing credentialless boundary. Schema1 retains direct aggregate build/check; schema2 uses the serial runner with the normalized default scope. Staging and the fresh sealed validator independently derive the same selection from the matrix, never the artifact manifest.
- Root reproduced37 workflow/boundary tests, including executed shell fragments with inert subprocesses for all three scope tokens, whitespace/metacharacter paths, malformed selection and failed build. The pinned action's `path` output was verified against [its exact action definition](https://github.com/actions/setup-java/blob/b6effb05e454b25005698d916606bdc6ffcbf961/action.yml). Review's missing-check finding was fixed in the preceding runner unit.
- No GitHub execution or deployment occurred; trusted controller admission and upload conditions are unchanged. The E2E workflow/action still need corresponding adapters. Rollback is this workflow and shell tests together.

## Task 6i — consume declared main/E2E overlays

- Stonecutter nodes can now preprocess one matrix-declared overlay per source set through its public prepare API, exclude exact replaced canonical files and merge declared additions. Production and E2E remain separate. Canonical sources and identity barriers are preserved; the global modern-game guard remains active.
- Root reproduced31 source/identity/policy tests. Six real strict/offline Gradle probes cover initial Java compilation, incremental replacement/removal without clean, deselected overlays, and rejected undeclared/linked/node-override inputs. Separate fixture JARs and all input snapshots pass; evidence is under `build/diagnostics/stonecutter-overlays/`. These synthetic Java probes use the exact adapters but no Loom/game runtime.
- Rollback is both overlay convention hooks together. Reviewed real lane pins, game compatibility ports and node activation remain next.

## Fan-in correction — bind aggregate publication to owned directories

- Independent reproduction replaced the publication parent after validation and caused the old helper to publish an unvalidated external directory. Writes and cleanup now use no-follow directory descriptors, parent/stage identity is checked around publication, and exclusive rename preserves a concurrently created destination. Unsupported hosts fail before creating the output parent.
- Root reproduced12 atomic/producer tests, then all7 final atomic adversaries including a parent swap immediately after rename. Five original adversaries were red before the fix. The reviewed macOS exclusive-rename backend ran locally; the Linux backend has not been executed here. Existing scope/reader tests also pass independently.
- Rollback must retain equivalent directory binding and exclusive publication; returning to pathname-only writes/rename would reopen the reproduced defect. This change grants no remote evidence authority.

## Task 12j — scoped E2E input-bundle build

- The E2E workflow's build job now uses the same Java17/Gradle-JVM separation and normalized dispatch contract as Build. Schema2 runs serially, and staged verification under both candidate and fresh validator receives the exact matrix-owned scope. Existing schema1 aggregate/check behavior remains available.
- Root reproduced41 workflow/boundary tests, reusing executed shell checks across both workflows for all scope tokens and failed-build/invalid-selection paths. Runtime action and fan-in call sites still await their separate adapters; no GitHub/client execution or publication is claimed. Rollback is this build-job adapter and shared shell test extension.

## Task 12g — explicit scoped fan-in CLI

- Create, aggregate validation and lane validation now accept one external scope or artifact node and forward it unchanged to staged-bundle verification and evidence APIs. Local artifact repository paths are separate from the remote owner/repository identity. Scoped validation verifies the bundle before reading aggregate evidence; legacy structural validation keeps its original interface and rejects ignored bundle options.
- Root reproduced52 fan-in tests, including seven CLI cases for scoped round trips, wrong/missing selection, rejected bundles, typed manifest drift and repository-relative inputs under a fresh validator. Independent review found no blocker. Synthetic evidence remains distinct from authenticated game qualification. Rollback is CLI adapter/tests, retaining strict typed re-read validation.

## Task 12k — match packaged runtime and sealed-lane scopes

- The packaged-runtime action derives its artifact selection from the validated matrix and passes it to both staged verification and the orchestrator. The fresh credentialless lane validator independently derives that selection from the sealed checkout; projection/row inputs retain their existing explicit bindings.
- Root reproduced35 action/workflow/boundary tests. Executed shell fragments confirm schema1 omission and exact legacy/full forwarding, unchanged quoted row/scenario values, and no display/game launch on invalid scope or rejected bundle. Upload conditions and environment allowlists remain unchanged. This is local action validation, not a Minecraft or GitHub run. Rollback is action and corresponding tests.

## Task 12l — scoped authoritative workflow fan-in

- The aggregate job now derives selection from the candidate matrix and forwards it to creation. Its fresh sealed validator derives selection independently, verifies scoped artifacts against the explicit local checkout and retains exact source-run fields plus the existing raw manifest/receipt digest comparison. Schema1 keeps its structural aggregate interface.
- Root reproduced32 fan-in-shell/security/job-graph tests. Executed snippets cover all default scopes, quoted checkout paths, exact source identity and rejection of unknown scope or crossed manifest digest. Upload authorization/conditions are unchanged. The separate public-evidence consumer still needs its bundle adapter; no CI/game/publication run is claimed. Rollback is this aggregate-job adapter and shell tests.

## Task 12m — verify scoped bundles before public aggregate consumption

- The public-evidence job downloads the production/harness bundle from the same exact source run/attempt as its aggregate. Scoped validation supplies the explicit checkout/stage/manifest and derives scope from the matrix; schema1 keeps the existing unscoped aggregate interface. Source artifact digest checks remain required.
- Root reproduced33 workflow/graph tests, including executed public-consumer shell checks for legacy/full/unscoped and rejected unknown scope. Downstream Pages/visual readers still need their own normalized adapters, so this does not claim complete public schema2 support. No upload, deployment or publication ran. Rollback is this bundle/validation step and test extension.

## Task 7k — real preparing build, schema3 staging and failed-check stop

- A clean isolated copy of `aee782221c7090deb655dcf65c2c54d27f5ae30c` added only a preparing-schema2 matrix in fixture commit `1b6f3063c8d64f3634eef79d9565635b46ee0316`, preserving the two real lanes' pins and declaring all twelve targets. The strict serial runner passed in61.97 seconds with `check`; all four archives contain exact clean identities. Stage/reverify passed with explicit `legacy` scope and partial2/12 coverage. Manifest SHA256: `341631451a2a22cc7a720661887ac935411684a96fd69c7a159d7eede4a69673`.
- A separate clean fixture commit `37f580651f360393761a5ed8a3a1ba7cbae62619` added one deliberately invalid common Java test. The runner failed at `:common:compileTestJava`, never started Forge and produced no success manifest. Both runs released their leases/processes; all685 positive source inputs, report, manifest and JARs stayed unchanged afterward.
- Evidence is under `build/diagnostics/task7k/` and `task7k-negative/`. This proves real local legacy compilation/provenance under preparing schema2, including failure propagation. It does not prove client interaction, modern game lanes or release qualification; the main checkout's matrix remains schema1.

## Task 8a — preserve explicit schema1 compatibility fixtures

- Historical schema1 tests and synthetic schema2 factories now use a frozen pre-enrollment matrix instead of inheriting future live-matrix changes. A separate live smoke still validates the real checkout's normalized configuration, source tree and default selection. Runtime recipe identities and legacy artifact/report contracts remain unchanged.
- Root reproduced68 directly affected tests; the independent focused run covered177. The snapshot preserves the pre-migration matrix content, while receipts always hash the fixture's actual bytes rather than confusing CRLF checkout bytes with the LF Git blob. Trusted controller/bootstrap and source-backed visual fixtures remain for separate preparation units. No live matrix or production reader changed. Rollback is fixture/helpers and their test consumers together.

## Task 8b — keep protected-policy fixtures independent of enrollment

- PR-policy and loader-bootstrap temporary repositories now seed only their matrix from the frozen schema1 fixture. Every executable controller, verification metadata file, loader build and bootstrap contract still comes from the current checkout; mutations operate on those temporary repositories.
- Root reproduced94 policy/bootstrap tests, including the existing restricted-evaluation schema2 rejection. No production admission rule was relaxed and no protected hash was frozen to stale bytes. Rollback is these two test-fixture adapters.

## Task 6j — choose Loom platform before applying the plugin

- Schema2 contexts now set common's Fabric platform and each loader's matrix-owned repository family before Loom is applied. Conflicting `loom.platform` or legacy `loom.forge` properties fail instead of silently changing the loader family. Schema1's existing property behavior is preserved; loader bootstrap bytes and the modern-game guard are unchanged.
- Root reproduced17 dependency-policy tests. Fourteen strict/offline configuration probes exercised the real pinned Loom API: six reached explicit platform assertions and eight rejected conflicting command-line/system/environment/parent selectors. Positive probes deliberately exit1 with `PLATFORM_PROBE_VERIFIED_BEFORE_GAME` after those assertions; they are not successful game builds. Exact binding/evidence is under `build/diagnostics/loom-platform-context/`.
- Read-only official-artifact review also found incompatible historical1.21.1 pins. Declared compatible minima are Fabric Loader0.17.2/API0.116.6+1.21.1 and NeoForge21.1.150 with Architectury13.0.8/GeckoLib4.8; hashes and embedded requirements are recorded in `build/diagnostics/lane-1.21.1-inputs/evidence.md`. They remain unadmitted inputs until the transitive closure and lane configuration are reviewed. Rollback is pre-apply platform selection, retaining equivalent conflict rejection when schema2 is used.

## Task 12n — serialize the two CI Gradle builders

- Build and E2E build jobs now share one repository-scoped concurrency group with running-job cancellation disabled and a multi-pending queue. Runtime-only jobs retain their existing scheduling. This adds the cross-workflow serialization required by the design, alongside each runner's local checkout lease.
- Root reproduced39 workflow/graph tests. The `jobs.<job_id>.concurrency` and `queue: max` behavior was checked against [GitHub's current documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency). Existing workflow-level obsolete-run cancellation remains separate; no scheduler/CI execution is claimed locally. Rollback must retain equivalent cross-workflow Gradle serialization.

## Task 12o — consume externally scoped visual evidence

- The visual evidence reader normalizes both schemas and requires external execution selection plus verified artifact-bundle scope for schema2. A lane may execute within a wider bundle; an aggregate must match that bundle and its external anchor projection. Exact attested nodes, scenarios, images and coverage remain mandatory. Matrix authentication now hashes the same secure read used for normalization.
- Root reproduced58 existing/scoped visual tests, then all8 scoped tests after six boolean/float output-schema mutations exposed and fixed an inherited numeric-alias acceptance. The final independent run passed59 tests. These synthetic pixels do not qualify gameplay, and this reader's coverage checks do not replace caller-owned bundle/fan-in authentication.
- Curator/capsule callers remain fail-closed until their adapters. Rollback is the scoped evidence API/tests, retaining exact integer schema validation and single-read matrix binding.

## Task 8c — finish isolated legacy fixtures without weakening policy

- Source-backed Pages/visual fixtures now materialize the frozen schema1 matrix and its declared directories, keeping source validation enabled. Sync tests use the same explicit baseline. Protected CI helpers own the pure fixture reader so their tests no longer import candidate-owned `tests.*` code; the existing import policy remains unchanged.
- Root reproduced33 sync/baseline/Pages/anchor tests; the independent focused run passed89. The remaining direct live-matrix reader is the deliberate checkout smoke test. Empty temporary source fixtures are compatibility inputs, not compiled-source evidence. No production admission rule or live matrix changed. Rollback is these fixture helpers and their consumers together.

## Task 6k — validate schema2 sources before configuring nodes

- Settings now performs source validation for schema2 before creating the selected graph and compares its exact normalized context with the initial configuration read. Missing/linked/undeclared routes and input drift fail before Stonecutter or Loom. Schema1 retains its historical path; the modern-game guard remains active.
- Root reproduced18 dependency-policy tests and checked every exact evidence binding. The independent combined policy/source run passed25 tests. Twelve strict/offline real-Gradle probes reproduce the prior undeclared-node defect, preserve both legacy paths, retain the modern guard and reject eight invalid source/drift cases before plugin/project configuration. Evidence is under `build/diagnostics/settings-source-validation/`; all unchanged-input cases and owned-process cleanup passed.
- These settings probes do not compile Minecraft. Rollback must retain source validation and context binding before activating schema2 nodes; the exact settings policy hash changes with this implementation.

## Task 12p — preserve the exact canonical visual anchor across schemas

- Anchor identity/validation now selects the canonical lane through the normalized matrix API and hashes the same secure matrix read. Branch eligibility, one-lane membership and complete scenario frames remain unchanged; a canonical anchor never claims matrix-wide qualification. Schema2 creation still fails at the unadapted raw-evidence producer boundary.
- The independent focused run passed45 anchor/workflow tests. Preparing/shared synthetic reader fixtures reject changed references, crossed lanes, missing frames, invalid schema types, malformed/linked matrices and stale raw hashes. No Minecraft or remote producer ran. Rollback is this reader adapter and focused tests together.

## Pre-enrollment integrated verification

- At `88bc6d5`, root ran both discovery suites:294 Python tests passed with3 opt-in Gradle skips, and290 CI tests passed. Logs are under `build/diagnostics/preparing-preenrollment-{python,ci}.log`. Real Gradle evidence remains recorded in the earlier bounded probes/builds; these skips are not new Gradle passes.
- User `.gitignore` and `.pi/gentle-ai/sdd-preflight.json` retain their handoff hashes. The live schema1 matrix still has raw SHA256 `c554bab87f188be57dccf5a1d14cc81d8dce8ca17663198c4d700f620f3f4fdb`. All units since `2334a47` remain below400 authored lines. No remote mutation occurred.

## Task 8d — enroll the live matrix in local preparing mode

- The live matrix now declares schema2/preparing with all twelve exact targets and only the existing Fabric/Forge1.20.1 configurations. Existing pins, installers, runtime rows, metadata and task/output paths are identical; routes retain canonical main and add existing E2E roots. Default execution remains `legacy`; full selection reports ten unresolved targets and fails.
- Root and independent review compared the candidate's original fields/projections against schema1. The raw enrolled matrix SHA256 is `e60a3849aa7cb20aee1b2d68d7dd4ea24b5ecc4b3f1bfa1e61c4443909d0b80e`. Root reproduced23 focused tests, then301 Python tests (3 opt-in Gradle skips) and290 CI tests against the enrolled matrix. Inventory/context and logs are under `build/diagnostics/preparing-enrolled-*`; the live smoke derives unresolved membership dynamically so subsequent reviewed lanes can be configured.
- Task7k already proved real clean-copy legacy builds/staging with this exact candidate matrix; it remains partial2/12 compilation evidence. Enrollment grants no modern readiness, client qualification or publication authority. Unadapted authority/public readers remain fail-closed. User changes remain preserved, and no remote mutation occurred. Rollback is this matrix enrollment and its live smoke expectation together to schema1.

## Task 9a — admit the first exact Fabric1.21.1 dependency boundary

- The first strict isolated Fabric1.21.1 attempt stopped during common configuration on four missing checksums: Architectury13.0.8 JAR/module and Fabric Loader0.17.2 JAR/POM. This unit adds only those four exact official/historical pins. Existing artifacts, trust configuration and repository policy are byte-for-byte unchanged at the parsed record level; no checksum was generated or broadly trusted.
- Root independently compared cache bytes with the successful official download receipts and reproduced18 dependency-policy tests. Candidate metadata SHA256 is `aff08f28099a3ce9092df8a5c58f896f828cc3e1b2f400f006115532bf74091c`; exact URLs/digests and review are under `build/diagnostics/fabric-1.21.1-probe/metadata-admission-review.json`.
- The diagnostic copy is based on `7fba4db` with only its matrix and modern guard changed; that bypass exists only in the fixture. The first attempt failed before compilation, kept all source inputs unchanged and closed its Gradle process. It proves strict rejection, not modern readiness. The main guard remains active; later dependency boundaries and ports remain pending. Rollback is these four checksum records only.

## Task 9b — configure the guarded Fabric1.21.1 lane

- Added only Fabric1.21.1's explicit artifact/runtime pair: Java21, detached Stonecutter task/output paths, Fabric repository family and existing canonical main/E2E routes. Its historical mod version remains1.1.2, independent of legacy1.1.3. Reviewed compatible pins are Loader0.17.2, API0.116.6+1.21.1, Architectury13.0.8 and GeckoLib4.8; installer1.1.0 retains its existing digest.
- Root reproduced66 matrix/context/runner tests. Independent comparison proved the new JSON matches the reviewed diagnostic fixture and all prior records remain unchanged. Twelve targets remain visible, three are configured and nine unresolved; default execution is still legacy2 and full selection still fails. Explicit selection derives only the Fabric1.21.1 context and never mixes FML families.
- The modern-game guard remains active. Dependency closure, canonical compatibility ports, metadata generation and real build/client qualification remain incomplete for this lane. Configured status represents reviewed inputs only. Rollback is this pair, lane count and focused selection test; no other target or source tree changes.

## Task 12q — reconstruct raw Pages evidence under an external scope

- The raw reader now accepts schema2 only with caller-owned scope/node/projection and exact aggregate coverage. It binds provenance to the same secure matrix read and reconstructs every selected lane/frame from protected packaged-result and pixel checks. Missing/crossed lanes, omitted scenarios, forged pixels and partial coverage presented as full fail; it does not authenticate a run or verify JARs itself.
- Root reproduced39 raw/Pages/anchor tests, including real synthetic-image reconstruction for legacy2, one modern lane and shared12. Exact integer schema/Java checks reject numeric aliases. Existing producer/compact/CLI callers still fail without their own scope adapters; no publication occurred. Rollback is the opt-in raw reader and focused tests, retaining same-read matrix binding and strict types.
- Review identified a separate inherited pathname-based atomic-directory weakness in Pages producers, analogous to the fixed fan-in defect. This reader does not write output. Producer activation must first reproduce and close that boundary in a separate unit; no current source of producer authority is expanded here.

## Task 9c — verify the Fabric1.21.1 mapping artifacts

- After the first four admissions, the second strict fixture attempt stopped on only `net.fabricmc:intermediary:1.21.1` v2 JAR/POM in common's detached mapping configuration. This unit adds their two exact SHA256 records, with every existing record and verification setting preserved.
- Four official HTTPS downloads (artifacts plus SHA256 sidecars) agree with cached resolution bytes and historical pins. Root independently rehashed both downloaded files/sidecars and validated the exact metadata delta;18 policy tests passed independently. Metadata SHA256 is `a6101b0c5a1098e8702d0581172b21f449ce198ad9b74265639bc27813fc2d5b`. Receipts/review/run bindings are `build/diagnostics/fabric-1.21.1-probe/attempt02-*`.
- Attempt02 failed before tasks/compilation, preserved all fixture inputs and released Gradle. The main modern guard remains active; remaining closure and source ports still block readiness. Rollback is the two intermediary records only.

## Shared filesystem prerequisite — reuse descriptor-bound publication

- Extracted the already-tested fan-in directory writer/publication mechanism into `scripts/lib/atomic_directory.py`, preserving no-follow traversal, exclusive creation/rename, parent/stage identity checks and owned cleanup. Fan-in wrappers retain their public failure type. This prepares reuse for the inherited Pages producer weakness without enabling that producer yet.
- Root reproduced all52 fan-in tests, including seven directory-substitution/exclusive-publication adversaries; independent review found no behavioral regression. macOS backend execution remains the local evidence; Linux execution is still unverified here. Rollback is the extraction and fan-in wrappers/tests together, retaining equivalent directory binding.

## Task 10a — preserve namespace/path identifiers across Minecraft APIs

- Added one canonical `ResourceLocations.of` adapter: unprocessed legacy Java uses the public constructor; Stonecutter's1.21+ branch uses `fromNamespaceAndPath`. Replaced37 equivalent calls in18 common/Fabric files. Historical comparisons against `6b5651e6` and `4f6e4039` identified the API change; current identifiers, textures, packet IDs and behavior remain exact, including FigureModel's current fallback rather than the different historical path.
- A real strict/offline Stonecutter0.7.11 probe generated both branches. javac17/21 compiled them against the actual mapped1.20.1/1.21.1 game JARs, emitted class61/65 and invoked the constructor/factory respectively. Both crossed-API controls failed; raw legacy source compiled too. Root independently reversed all37 substitutions back to HEAD and checked helper/API-JAR digests. Evidence and an opt-in reproducible test are under `build/diagnostics/resource-locations/` and `tests/test_resource_locations.py`.
- Source bytes stayed unchanged during the probe and owned processes closed. This verifies the compatibility seam, not full consumer compilation or game/client behavior; broader lane qualification remains pending. Rollback is this helper and its37 call sites together.

## Pages correction — bind writes, publication and cleanup to owned directories

- Five adversarial probes reproduced the old Pages helper accepting substituted parents/stages, replacing a concurrent destination or proceeding without required primitives. Pages/anchor writers now reuse descriptor-bound publication and write through their owned stage descriptor. A scoped context preserves the existing callback interface and restores outer state on nested success/failure; escaped paths are rejected.
- Root reproduced45 Pages/raw/anchor/atomic tests, then all7 final atomic tests after adding durable escape/nested-failure cases. Independent review reproduced18 focused tests and three additional probes without a blocker. Baseline failures and the passing combined run are `build/diagnostics/pages-atomic-{before,after}.log`. Replaced external trees and sentinel files remain untouched.
- This closes an inherited local producer boundary without enabling schema2 producers or granting remote authority. Rollback must preserve equivalent no-follow writes, exclusive publication and descriptor-owned cleanup.

## Task 13a — read durable lane build evidence without granting freshness

- Added a bounded POSIX report reader requiring an externally authenticated raw report digest and an already verified schema3 bundle. It compares the exact current plan, clean source/matrix/contract, lane version, complete execution/compiler records and output hashes. It relocates only recorded checkout paths and never opens historical JDK or checkout files. Probe markers remain explicitly unverified; live process observation stays the producer's responsibility.
- Root reproduced7 focused tests after review tightened marker/probe/home validation; the independent combined runner/reader run passed57. Mutations reject incomplete/failed/crossed evidence, wrong digests, numeric aliases, unsafe report links and changes during validation. Root also reverified the retained real task7k stage and successfully bound both legacy reports using its recorded report digest; replay is `build/diagnostics/build-evidence-real-replay.json`.
- This API does not infer newest-run status, authenticate its own inputs or qualify/publish a lane. Report transport and authenticated freshness remain required before successful release planning can be exposed. Rollback is this opt-in reader/tests only.

## Task 12r — preserve explicit scope while curating raw Pages evidence

- Raw curation now has an opt-in schema2 API using the same external scope/node/projection contract as its reader. It selects exactly those profiles, preserves the complete target inventory and partial status, hashes the same normalized matrix read and revalidates the constructed output before descriptor-bound publication. Schema1 behavior remains supported.
- Root reproduced32 producer/legacy/atomic tests; independent review reproduced19 producer/raw/atomic tests. Synthetic legacy2, modern lane1 and shared12 round trips pass; missing/extra profiles, omitted/crossed external scope, matrix drift and altered output coverage fail without a published directory. No CLI or workflow adapter is activated in this unit.
- Upstream aggregate/bundle authentication remains the caller's prerequisite; pixel curation does not create run authority or game qualification. Rollback is this producer adapter/tests together.

## Task 10a — real legacy consumer build after the identifier port

- A clean isolated copy of `b15fdf848bedb719fdccf2c94b21dc461690b04c` (tree `ce7a3b2a41ce8b2655876061bbcca00838c60c97`) passed the serial legacy runner in53.09 seconds: both production/harness archives and `check`, with observed JVM21/Java17 compilers. Schema3 stage/reverify passed at explicit legacy2/12 scope while the matrix contains three configured lanes. Actual helper and common/Fabric consumer bytecode uses the intended legacy constructor/adapter calls.
- Manifest SHA256 is `8ae40ca3af6c30292935f000fbe5f9a0a89d95c837affa82c17e497af76cc6cd`; report SHA256 is `488d5006eb1c6b0caedd722de2f7c688df7ff02541f2ab95873dd0d84d493906`. All699 clean source inputs stayed unchanged, all four archive identities/hashes passed, and owned daemons/lease closed. Earlier task7k evidence remains intact. Full records are `build/diagnostics/task10a-legacy/result.json`.
- This supersedes the port's earlier uncompiled-consumer limitation for the two legacy lanes only. Modern game compilation, packaged-client scenarios and publication remain unproven/unperformed.

## Task 9d — admit the stable Fabric1.21.1 transitive boundary

- Attempt03 failed strict verification before compilation on103 missing artifacts. This unit admits only71 whose official HTTPS bytes, SHA256 sidecars and historical pins agree; every prior metadata record remains unchanged. Root independently checked the exact delta and retained receipts;18 dependency-policy tests passed. Metadata SHA256 is `dca23e7332bc79cb43b107c681b7c2ade2d3f6d43e546887109ce9932162b2c2`.
- The other32 JARs remain deferred. Their official hashes differ from historical pins solely because three signature-entry ZIP timestamps changed: root independently reproduced all32 historical hashes by changing only those timestamps in memory. No reconstructed bytes were admitted. The19 available historical cache files remain untouched; a later unit must pin only reviewed current official bytes and use an isolated cache.
- Evidence is `build/diagnostics/fabric-1.21.1-probe/attempt03-admission-a-review.json` and adjacent official/payload receipts. This is dependency admission only, not modern compilation or qualification. Rollback removes only these71 records; repository/trust policy and the modern guard remain unchanged.

## Task 13b — transport the verified Build report separately

- Scoped Build jobs now seal the runner report alongside staged archives, bind its raw digest after candidate termination, and validate it from the restored protected controller before a separate fixed-file upload. The CLI reverifies the bundle, externally expected commit/tree and every selected lane report; schema1 remains unscoped. Existing sandbox environment allowances, permissions and job graph are unchanged.
- Root reproduced25 focused report/workflow tests; the independent combined run passed59 and a second agent reviewed the boundary. Executed shell fixtures reject absent reports and failed validators without an upload marker, preserve quoting and always seal before rejecting an invalid export selector. These local fixtures do not claim remote workflow execution.
- Transport is only byte/source binding: future consumers must authenticate workflow/controller/run/attempt/artifact identity and freshness independently. No release selection or publication is enabled. Rollback is this Build transport, CLI and focused tests together.

## Task 12s — compact and copy Pages evidence under explicit scope

- Compact generation, validation and cache copying now accept the same external schema2 scope/node/projection, retain exact matrix bytes and aggregate coverage, and validate against that external matrix before descriptor-bound publication. Default unadapted schema2 callers remain rejected. Exact integer checks cover schema, dimensions and sizes.
- Independent adversarial review reproduced three inherited invalid accepts: a PNG replaced after raw validation, a different valid manifest substituted during copy, and an unreferenced derivative file. The producer now rechecks source bytes, copy requires canonical manifest equality and the reader requires exactly the referenced derivative inventory. All three regressions failed before their corrections.
- Root reproduced35 compact/atomic/legacy tests; the independent combined run passed54. Synthetic image round trips cover legacy2, one modern lane and shared12 with missing/crossed/altered scope rejected. These APIs do not authenticate their own upstream artifacts or qualify game execution; CLI/workflow adapters remain pending. Rollback is this adapter/tests together while retaining equivalent substitution rejection.

## Task 9e — pin the reviewed current Fabric API JAR bytes

- Added the32 deferred JAR hashes from official HTTPS downloads and matching published sidecars. Only current official bytes are admitted, with no historical alternate or extra trust rule. Root checked every receipt against the exact XML delta; deleting only these32 entries reconstructs the prior metadata. Its new SHA256 is `23c753fae3dcf260288765b79c161e9973c588946da5a1d4c4469ad536164eb1`;18 dependency-policy tests passed independently.
- Timestamp-only historical equivalence is recorded in task9d and grants no alternate checksum acceptance. The next diagnostic uses a new exact-commit fixture and private copied dependency cache, excluding only the19 documented historical JAR files from that copy. Original cache bytes, prior attempts and the main modern guard remain intact; ordinary official resolution must pass strict verification.
- Review is `build/diagnostics/fabric-1.21.1-probe/attempt03-admission-b-review.json`. No modern compilation or qualification is claimed. Rollback removes only these32 checksum entries.

## Task 12t — derive an exact job graph from caller-owned scope

- The job-graph API/CLI now accepts opt-in schema2 legacy/full or an explicit lane. Lane selection requires workflow_dispatch; PR and scheduled graphs cannot be narrowed to one lane. Invalid, crossed or unresolved selections fail before returning even the fixed Build graph. Existing callers and default projections retain their behavior; observed jobs never choose the scope.
- Root reproduced104 scoped/existing graph and protected-controller tests. Independent review found no bypass: matrix validation requires both anchors for every runtime, and missing/extra/crossed jobs still fail the exact inventory. Tests remove each scenario/control job and reject a partial graph presented as full.
- This prepares later authenticated lane dispatch without activating a workflow input. Build job names alone do not prove lane coverage; verified bundle/report evidence remains required. Rollback is the explicit graph adapter and tests together.

## Integrated verification after the scoped readers and report transport

- A separate clean checkout of `98f1a0ab0c98b528aa403f82c2e8a552997b77b1`, tree `96989622e116dafc4fd33561f707c9acca32d447`, passes340 Python tests with4 opt-in Gradle skips and303 CI tests. It remains clean after both suites. Exact commands, log digests and results are `build/diagnostics/integrated-98f1a0a/result.json`; no Gradle was launched by this verification.
- Root rechecked unchanged original `.gitignore`/`.pi` hashes and the last nine work-unit commits' rename-independent authored sizes, all below400. This integrated result covers the committed readers/transport/metadata, not the subsequent in-progress S2C/Pages CLI units or modern/client qualification. No remote mutation occurred.
