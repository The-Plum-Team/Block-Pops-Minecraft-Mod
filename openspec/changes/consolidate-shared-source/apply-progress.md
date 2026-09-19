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

## Task 12u — expose explicit Pages evidence scope at the CLI

- All five evidence commands now accept mutually exclusive legacy/full or one artifact node, plus an external anchor projection. They pass the exact selection to the validated APIs; schema2 never infers it from a manifest, while schema1 retains its arguments and byte-identical summary. Scoped summaries include complete aggregate coverage and partial status.
- Root reproduced14 CLI/compact tests and the final expanded schema1 rejection case; the independent Pages run passed53. Real synthetic-pixel CLI round trips cover schema1, legacy2, one modern scheduled lane and shared12. Missing/crossed/unresolved selectors, changed projections, invalid profiles and conflicting arguments fail without publication. No workflow or source-authentication adapter is activated here.
- Independent closure review also confirms tasks4 and11 are complete as local implementation: every named normalized consumer is adapted (the generic runtime store's actual matrix owner is packaged_runtime), and schema3 producer/dual-reader/boundary/identity rules have synthetic twelve-lane and real clean legacy integration evidence. Task9's remaining configurations, task15's twelve-lane game qualification and task1's AUTH-1 delivery remain open. Rollback is this CLI/tests only; the local completion audit grants no delivery authority.

## Task 10b — preserve encoded server-to-client payloads across APIs

- Added `PacketNetworking.sendToPlayer` and replaced six equivalent calls in five packet classes. Legacy delegates unchanged;1.21+ wraps the existing encoded buffer with RegistryFriendlyByteBuf and the target ServerPlayer's registry access. All twelve current codecs remain exact against both historical refs `6b5651e6`/`4f6e4039`; unlike their allocation with RegistryAccess.EMPTY, this adapter uses the actual player's registry without altering payload bytes. Receiver registration and client initialization remain unchanged.
- Root independently reversed all six substitutions and reproduced the real opt-in test after the agent's successful run: strict/offline Stonecutter0.7.11 generates both branches, javac17/21 compiles actual mapped Minecraft/Architectury APIs, class versions61/65 and expected constructor/delegation bytecode match, and crossed APIs fail. Both real buffer probes produce identical32812 bytes (SHA256 `9f39b05623f0ebe5ee187e9723cf60923ff8836b289c8e2f24b33cc7a9af41a4`) while preserving indices, reference count and registry object, including Unicode/nullable flags/positions/enums/UTF limits.
- Root rehashed every API input and obtained identical observations; remapped API inputs retain exact official-source/tool/mapping receipts. Evidence is `build/diagnostics/s2c-networking/result.json` and `root-probe/`. Source inputs stayed unchanged and owned Gradle processes closed. This validates the adapter, not actual network sends, full consumer builds or packaged gameplay; modern payload registration and client-to-server adaptation remain separate units. Rollback is the helper, six calls and opt-in test together.

## Task 9f — verify the resolved Architectury and Fabric Loader source archives

- A clean-copy retry at exact `9806fcc` with normal DNS stopped at the240-second diagnostic bound, before tasks, on a stalled Fabric Maven TCP connection. Its private cache and sole fixture guard bypass were recorded; original sources/caches stayed unchanged and owned processes closed. This was a network timeout, not a checksum failure.
- A subsequent process-only address override used a separately verified official-host HTTPS endpoint with unchanged Host/SNI/TLS and strict checksums. Retry05 completed in10.2 seconds, verified all32 current Fabric API JARs (including19 normally resolved private replacements), and stopped before tasks on two missing sources.jar checksums. This unit admits exactly those Architectury13.0.8/Fabric Loader0.17.2 source archives, matching official sidecars, historical pins and resolved bytes;18 policy tests pass. Metadata SHA256 is `07190c8ce66b3f8b81f72c290d3b7edda4972052c16280768e33bd7e5b1a2a0a`.
- Root checked the two additions, cache hashes and byte-identical prior XML reconstruction. Receipts are `build/diagnostics/fabric-1.21.1-probe/attempt04-findings.md` and `attempt05-{official,admission}-review.json`. No global DNS, trust, repository policy or main guard changed; no modern compilation is claimed. Rollback removes only these two checksum records.

## Task 12v — forward authenticated public fan-in scope to raw curation

- The public E2E curator now derives default scope afresh from its matrix and uses the same event/attestation projection as the preceding authenticated fan-in validation. Quoted command arrays forward scope/projection only for schema2; unknown or failed selection stops before curation. Attestation selection is read through an environment value rather than inserted into new shell code.
- Root and independent runs each pass41 workflow/security/graph tests. Executed shell fixtures check schema1/preparing/shared across dispatch, schedule and attestation, exact identities, literal shell metacharacters, removed credentials and fan-in-before-curation ordering. Failed authentication/helper/curator cases prevent successful continuation. Existing upstream bundle/source authentication, permissions and uploads remain unchanged.
- This is an unpublished workflow adapter, not a remote run or permission to publish. Canonical anchor creation and downstream Pages/visual consumers retain their own separate adapter requirements. Rollback is this single workflow step and its focused tests together.

## Task 12w — create the fixed canonical anchor from scoped raw evidence

- Anchor creation API/CLI now requires caller-owned raw scope/node/projection for schema2 and validates the entire raw input before extracting only master/fabric-1.20.1. A valid raw lane without that canonical member fails. Anchor schema, complete canonical captures, source-run/artifact identity and direct exact-head restriction remain unchanged; the output never inherits aggregate qualification.
- Root reproduced16 anchor tests; independent API/CLI review found no blocker. Real synthetic PNG round trips cover raw legacy2, canonical lane1 and shared12 through both API and CLI. Omitted/crossed/unresolved selectors, absent canonical membership, attested provenance, post-validation PNG replacement and conflicting CLI selectors fail. Legacy calls retain their existing interface.
- This consumer requires prior raw-artifact authentication and does not perform it itself. Its workflow activation remains a separate reviewed step; no remote evidence or game qualification is claimed. Rollback is the opt-in producer/CLI and focused tests together.

## Task 12x — pass explicit raw scope when producing the canonical anchor

- The existing direct-run anchor step derives matrix scope only after canonical eligibility and exact raw-artifact ID/digest checks, then supplies the matching event projection through a quoted create command. Schema1 retains its previous arguments. Unknown scope and helper/create failures prevent the artifact/eligible-success markers; the no-attestation gate and upload ownership remain unchanged.
- Root and independent runs each pass44 workflow/security/graph tests. Executed shell cases cover both digest formats, all supported scopes, dispatch/schedule, malformed/ineligible identities and failing boundaries. Explicit failure exits preserve raw ID/digest rejection across the tested shell behavior; credentials are scrubbed immediately before invoking the producer too.
- This completes the local public raw/anchor workflow adapters only. Downstream Pages assembly and visual/release authentication still need their own schema2 transitions; no workflow was dispatched or published. Rollback is this step and its focused tests together.

## Task 10c — bind client payloads to one live connection before encoding

- Seven C2S call sites now supply their existing encoder lazily through the common environment gateway. A separate client helper captures one connection, preserves the existing IllegalStateException/message when absent before allocating a buffer, and delegates the same collectPackets/c2s/send pipeline. Modern wrapping uses that connection's registry access, never Minecraft.level. Current IDs, twelve codecs, handlers and registration stay unchanged; normal backend ownership is preserved.
- Root reversed all seven substitutions and reproduced the real C2S probe: both generated branches compile against actual APIs with Java17/21, crossed APIs fail, a null real connection never invokes the encoder, and a classloader denying client classes still loads/resolves the common gateway. Root/independent observations and source hashes are identical. Independent review compared the actual Architectury bytecode. Evidence is `build/diagnostics/c2s-networking/result.json` and `root-probe/`.
- The older S2C fixture now includes the real client helper required by the gateway; its missing-source failure was reproduced before this minimal correction, and the complete S2C probe passes again. Both owned probe processes closed and all11 edited source/test inputs stayed invariant during verification. These API/lifecycle checks do not send a game packet, start a dedicated server or qualify gameplay; packaged networking and modern registration remain pending. Rollback is this gateway/helper, seven calls and both probe fixtures together.

## Task 9g — verify the resolved GeckoLib source archive

- Retry06 changed only the previously admitted metadata in the same isolated fixture/cache and stopped in9.31 seconds before tasks on GeckoLib Fabric1.21.1/4.8 sources.jar. Its official Cloudsmith HTTPS bytes, sidecar, historical pin and private resolution all match SHA256 `30c2567f6dda2a6f0c76c60bcf414a815cbaf3990a51fd5428146cabcc95de7c`. This unit adds exactly that one record;18 policy tests pass.
- Root checked the private artifact and exact three-line delta, whose removal reconstructs the prior XML. New metadata SHA256 is `6444c00918a145cf09918e6496ac2c95646af86b7b974f9616471eb2d114b36a`. Run/official/admission receipts are `build/diagnostics/fabric-1.21.1-probe/attempt06-*`; sources and19 original caches remained unchanged and owned processes closed.
- This remains strict dependency resolution, not compilation or readiness. The private DNS diagnostic and fixture-only guard bypass remain recorded, with global configuration/trust untouched. Rollback removes only this source-archive record.

## Task 12y — accept the existing workflow's matrix-digest spelling

- The four Pages evidence readers/copy commands now accept both `--matrix-sha256` (already used by Pages) and the existing `--matrix_sha256`, with one destination and unchanged digest validation. This repairs the CLI boundary without changing workflow selection or authentication.
- Root reproduced the seven real CLI tests alongside eight release-run tests; the independent CLI suite also passes. A modern scheduled-lane round trip preserves bytes and coverage through both spellings; all four commands reject a wrong external digest before producing output. Rollback is the alias and focused CLI tests; no workflow or remote action changed.

## Task 13c — select the newest exact producer from live API observations

- Added an opt-in release reader for Build and Packaged E2E run identities. It requires externally authenticated source/controller inputs, checks current branch heads and the active workflow, selects the newest exact title before requiring success, and rereads the current run, exact attempt, newest inventory and branch heads. A newer failed/pending/wrong-controller run cannot expose an older success; malformed/duplicate matching rows fail closed.
- Eight focused tests pass, including changed attempts, concurrent dispatch, source/controller advances, typed identities and event restrictions. Independent review reproduced a canonical-branch alternating-head defect; the correction requires source/controller commit equality before API access and one canonical head read per pass. The original counterexample now fails before any API call, while final tree/head advancement still fails.
- This reader does not authenticate controller deployment, artifacts, job graphs or gameplay and is not wired to a release command. A caller must validate that evidence and repeat live selection before producing a plan; a stored JSON identity never grants freshness or publication. Rollback is the reader/tests only.

## Task 9h — admit the resolved Minecraft library coordinates

- Retry07 stopped before Java tasks in10.88 seconds on99 missing library/metadata artifacts. All99 freshly downloaded official HTTPS artifacts match their historical SHA256 and private resolved bytes; published sidecars provide28 SHA256 and71 SHA1 checks (stronger sidecars were unavailable for those71). All39 JARs also match the freshly verified Mojang1.21.1 manifest. Root independently rehashed every source/cache/sidecar and historical Git pin; original inputs/cache and owned-process cleanup pass.
- This first admission adds only76 artifacts belonging to38 coordinates declared by Minecraft, in302 XML lines. Every existing record/byte remains unchanged;18 policy tests pass. Metadata SHA256 is `563deb1e6eb9d860770a8a6ab1b827ccb93bd0223a53f2de46c387adc7672905`. Receipts are `build/diagnostics/fabric-1.21.1-probe/attempt07-{official,root,admission-a}-review.json`.
- The remaining23 parent/BOM POMs are explained by observed dependency edges and await their separate unit. These exact SHA256 admissions neither broaden repository/trust policy nor qualify a modern build. Rollback removes only this unit's76 records.

## Task 13d — bind exact producer jobs and artifact locators

- A new opt-in API combines current-run selection with an externally bound schema2 matrix/scope, complete exact-attempt jobs, and two immutable artifact locators per producer: Build bundle/report or E2E input/aggregate. It checks direct artifact IDs against repeated inventories, then rereads run identity and original matrix bytes. Job expectations use a private snapshot of the originally hashed matrix, never a second mutable graph input.
- Root reproduced29 reader/selector/graph tests and30 workflow/report tests. Independent review found and reproduced two defects: legitimate canonical Build push was rejected by the graph API, and a job name/ID swap survived the legacy graph summary. Build alone now supports push in API/CLI without narrowing it to a lane; the new reader additionally binds each job's ID/name/outcome/owner. Both counterexamples are covered; reordered equivalent inventories remain valid. Existing graph receipts are unchanged.
- Tests cover missing/extra/failed jobs, wrong attempt/owner, expired/crossed/changed artifacts, typed IDs, missing selection, changed matrix/run and legacy/lane/full coverage. These are API metadata checks, not downloads, content qualification or controller-deployment proof. A future planner must verify all downloaded content and repeat these live checks. Rollback is this reader/tests and the bounded Build-push graph adapter together; no workflow or publication is activated.

## Task 9i — admit the resolved parent and BOM metadata

- The second retry07 admission adds only the23 remaining POMs, whose33 observed parent/import edges explain their presence outside Minecraft's direct coordinates. Their official bytes, available sidecars, historical SHA256 pins and private resolved copies were independently rehashed in task9h; every coordinate has a recorded provenance edge and none is unresolved.
- Root verified the exact115-line insertion and unchanged prior1005 artifact records/bytes;18 policy tests pass. Metadata SHA256 is `dae287cb7a4262adfa2920aea9fa42b57ce8bbc2b4497f053e140ca8a1311b1e`. Receipts are `build/diagnostics/fabric-1.21.1-probe/attempt07-{pom-provenance,admission-b-review,root-admission-b-review}.json`. No further Gradle retry has run yet. Rollback removes these23 POM records without changing repositories, trust or other pins.

## Task 10d — register modern S2C payload types only on physical servers

- Modern ModNetworking.init now supplies its five existing S2C IDs to a helper that registers payload types only in Env.SERVER. Both historical ModNetworking paths at `6b5651e6`/`4f6e4039` use that environment distinction; current initClient and all handlers/codecs remain unchanged. The client receiver API already registers those types, so adding them on physical clients would duplicate registration. Legacy generation contains neither new call nor backend registration.
- Root independently rehashed all API/backend inputs and reproduced the strict/offline Stonecutter+javac17/21 probe with identical observations. Real verified Fabric/Architectury backend JVMs, using Knot's constructor without init/launch, observe five server types, zero premature client types, five types after receiver registration and rejection of an explicit duplicate. Source/symbol inputs remain unchanged and owned Gradle processes close. Receipts are `build/diagnostics/s2c-registration/result.json`, `root-review.json` and `root-probe/`.
- ModNetworking compilation uses105 clean legacy class declarations only as symbols; backend execution excludes them. The real client receiver API uses passive callbacks, not actual initClient/game handlers. Architectury Fabric13's official runtime hash is verified separately from Gradle metadata admission. These checks do not launch Minecraft, a dedicated server or packaged E2E. Rollback is the helper, guarded call and opt-in probe together; bootstrap contract bytes are unchanged.

## Task 12z — inspect immutable Pages branch matrices under explicit scope

- Added opt-in `inspect_pages_branch` for schema1/unscoped or schema2/legacy/full. It pins commit/tree before reading one bounded regular Git blob, checks its Git hash, normalizes those exact bytes once and returns their SHA256 with selected/configured/target inventories and version lists. A final ref read detects advancement. Existing sync/discovery/CLI interfaces remain unchanged and schema1-only; no run event or projection is inferred here.
- Root reproduced26 real Git/portability/sync tests. Review identified ambient GIT_DIR redirection despite `git -C`; the new helper now removes inherited GIT_* variables and fixes replacement/graft/config isolation. Regressions reproduce foreign-repository confusion before the fix and preserve explicit linked-worktree identity afterward. Other cases reject crossed/incomplete scopes, corrupt/oversized/unsafe-mode blobs and changed refs while ignoring worktree bytes.
- This snapshot does not authenticate a remote ref, current producer or qualification. Rollback is this opt-in helper and focused tests. A separate acceptance audit also confirms tasks5/6 are complete as local foundations: exact current/next transitions for all three loaders and isolated pinned Stonecutter/Loom/source probes satisfy their stated scope. Root's exact-commit bootstrap CLI and38 bootstrap/policy tests pass; tasks1/9/14/15 remain open and the modern-game guard stays active.

## Integrated verification after networking and current-run metadata readers

- A separate clean checkout of `3a29d5c91176a83d5f225f1e8c9858c32a27c62f`, tree `d572c419f1d51905431580e596942c95914673a0`, passes379 Python tests with7 explicit real-Gradle opt-ins skipped and308 CI tests. Both suites ran without Gradle and left the checkout clean; commands/results/log hashes are `build/diagnostics/integrated-3a29d5c/result.json`. The new network probes passed separately against real APIs/backends; this suite does not launch Minecraft.
- Root again verified original `.gitignore`/`.pi` bytes and the last seven work-unit sizes, all below400 authored lines. This milestone covers committed S2C/C2S/registration, branch inspection and producer metadata; subsequent dependency additions and Pages authentication work require their own checks. No remote mutation occurred.

## Task 9j — admit the resolved Architectury Fabric backend

- Retry08 reached Fabric1.21.1 configuration and stopped in 14.08 seconds before Java tasks on Architectury Fabric13.0.8 JAR/module checksums. Fresh official HTTPS artifacts and SHA256 sidecars match historical pins, prior runtime research and private resolved bytes; root independently rehashed both. Inputs and the 19 original cached variants remain unchanged, and owned processes closed.
- This unit adds exactly those two records in eight XML lines, preserving all prior bytes. All 18 policy tests pass. Metadata SHA256 is `df94ced4e3b81ba80f02c3d60c07aada4077547ad28d0a0727cf972c79128e48`; receipts are `build/diagnostics/fabric-1.21.1-probe/attempt08-{official,admission}-review.json`. No sources or unobserved dependencies are admitted. Rollback removes this component; modern compilation and gameplay remain unqualified.

## Task 10e — reverify real legacy consumers after all networking seams

- The serial runner passes both legacy production/harness/check builds in 56.508 seconds from clean commit `f44f219ad54172c94f4201fe89a3210c06a3e90f`, tree `39cd8309af0b866a300829bd2be9ee13c77b58fc`, with Gradle JVM21 and observed Java17 compilers. All 717 source inputs remain identical. Schema3 staging/reverification preserves explicit partial coverage of 2/12 targets; helpers appear only in production, and the modern registration call is absent from legacy bytecode.
- Root independently verified both staged pairs, durable lane reports and the unchanged clean source snapshot. Manifest SHA256 is `d87490ac73c77d82a62767040cc49f552d676f4b722c817720e78c2aff88f7ee`; report SHA256 is `da7bb66e378ef04e6b3d7dda761b597df914264a1a93badfe57657e27ef7e4d0`. Evidence is `build/diagnostics/task10-networking-legacy/{result,root-review}.json`.
- BlockPops classes use major61; Architectury's injected PlatformMethods uses compatible major52, recorded separately. Prior evidence remains intact, checkout lease and owned processes are closed, and no DNS override was needed. This proves legacy build/provenance after the networking changes, not packaged client/server gameplay, modern compilation or publication.

## Task 12aa — authenticate scoped public evidence against external source inputs

- Pages source authentication now accepts explicit legacy/full scope plus caller-owned branch/commit/tree/matrix SHA256 for schema2; omitted or partial binding fails. It derives projection from authenticated direct/attested run events and compares complete declared coverage against that selection. Scheduled packaged evidence cannot be relabelled as an attested PR projection. Schema1 retains its output and rejects the new scoped options.
- Matrix and contract snapshots supply the graph/coverage readers; original matrix, contract and manifest bytes are rechecked after API authentication. Exact run/artifact ownership remains required, numeric identity aliases fail, and private snapshot arguments cannot be injected through the public wrapper. Root and independent suites each pass 17 scoped/legacy/graph tests, including API/CLI round trips, missing/crossed evidence and input drift.
- This API assumes authenticated extraction and newest-handoff selection by its caller; it does not choose freshness, validate pixels or grant publication. Workflow forwarding and Pages assembly remain separate units. Rollback is this source-authentication adapter and its tests; no workflow or graph contract changed.

## Task 9k — admit the resolved Fabric backend and API source archives

- Retry09 stopped during Fabric configuration in 11.42 seconds on 51 exact sources.jar artifacts, before any task execution. All 51 official HTTPS downloads and SHA256 sidecars match historical pins and private resolved copies; root independently rehashed each source. Snapshots and the 19 original cached variants remain unchanged, and owned processes closed.
- This admission adds those 51 source records to existing binary components in 153 XML lines, preserving every prior byte and record. All 18 policy tests pass. Metadata SHA256 is `fd56fccf2fe489a3d139358fd398daaaf41faf5bd1852974dbd8836b53f20946`; evidence is `build/diagnostics/fabric-1.21.1-probe/attempt09-{official,admission}-review.json`. No new coordinates, repository policy or trust exception are introduced. Rollback removes only these source records; modern compilation remains pending.

## Task 10f — declare modern codecs for the three entity blocks

- Added guarded MapCodec/simpleCodec declarations and codec overrides to BoxBlock, ClawMachineBlock and FigureBlock. Each fragment matches both historical references `6b5651e6`/`4f6e4039`; removing the additions restores prior source bytes exactly. Actual mapped API inspection confirms the modern abstract method.
- Real Stonecutter 0.7.11 offline/strict preprocessing passes for all three complete classes on 1.20.1 and 1.21.1 in four tasks and seven seconds. Legacy Java tokens remain unchanged; modern output adds exactly the historical fragments. Root independently rehashed all six generated files, API receipts and metadata bound to commit `94137f0`. Evidence is `build/diagnostics/block-codecs/result.json`.
- This unit does not compile complete modern blocks or execute codecs: interaction, destroy, clone and data ports remain pending. Rollback removes these 39 guarded lines. The main modern-build guard and publication restrictions remain active.

## Task 9l — admit the resolved Mixin compiler dependency

- Retry10 reached matrix validation, build identity and common Stonecutter tasks in 19.67 seconds, then stopped before javac while verifying compileClasspath. Loader 0.17.2 declares the exact Sponge Mixin 0.16.3+mixin.0.8.7 coordinate and JAR digest. Root independently checked its official HTTPS JAR/POM, SHA256 sidecars, historical pins, private resolved copies and Loader declaration.
- Only these two artifacts are admitted in eight XML lines; prior bytes remain intact. Both independent runs pass all 18 dependency-policy tests. Metadata SHA256 is `ad96ac39d22da238aa68c10a545138690acc80d938714db30ef14bcaae2d21eb`; receipts are `build/diagnostics/fabric-1.21.1-probe/attempt10-{official,admission}-review.json`, with Loader provenance in `attempt10-loader-provenance.json`.
- Probe sources and the 19 original cached variants remain unchanged, and owned processes closed. Rollback removes only this component. No repository/trust change or modern Java compilation is claimed.

## Task 12ab — discover Pages branches with matrix-owned dispatch scope

- Added opt-in `discover_pages_repository` and `--pages --canonical-branch` CLI dispatch. Each enrolled immutable matrix chooses schema1/unscoped, preparing/legacy or shared/full independently. Returned commit/tree/blob/raw digest and complete scope inventories carry no event projection or qualification. The existing sync/default discovery interface remains unchanged.
- Root and the independent implementation run each pass 34 real Git, portability and sync tests. Cases cover mixed schemas, malformed enrollment, missing canonical branches, remote-ref additions/deletions/movement, inherited Git redirection, symbolic aliases and a 1,000-branch bound. Discovery rechecks the complete ref inventory after inspecting pinned commits; it does not authenticate remote advertisements itself.
- Workflow transport, authenticated event projection and scoped Pages assembly remain separate work. Rollback removes only this opt-in discovery adapter and its tests; no workflow or publication is activated.

## Task 13e — compose independent Build and E2E content for one lane

- Added a local content reader that verifies both complete scoped bundles, every Build-report lane and the complete E2E aggregate against external raw digests and source/run/projection inputs. Scopes may differ if both contain the requested lane. Full typed lane identities must match; archive hashes may differ between independent builds. The selected production is always the exact E2E-tested record, with its harness, scenarios and aggregate binding retained separately from Build evidence.
- Eight focused cases pass across root runs, including real synthetic ZIPs, staging, PNGs and fan-in; seven core cases also pass independently. Only process/toolchain boundaries are simulated. Root additionally passes nine durable-report and fourteen scoped fan-in tests. Negatives reject crossed inputs, failed reports, a changed unselected Build lane, resealed corrupt pixels and late mutations. Independent review reproduced an accepted Build-JAR mutation during E2E validation; final checks of every production/harness record now reject it. Relative stage paths are anchored before validation.
- This API does not authenticate transport or producer freshness and exposes no qualification/publication flag. A future planner must authenticate downloads, repeat live producer checks and verify selected bytes on use. Rollback removes this reader and its focused tests; no CLI, workflow or release action is enabled.

## Integrated verification after Pages discovery and lane-content composition

- A separate clean checkout of `5d13d8a13f49357c4c7f7db6d4c7aa1a2de435d7`, tree `e170ccb412eda148a95b361d0d6ad71e1fa38fef`, passes 404 Python tests (seven real-Gradle opt-ins skipped) and 308 CI tests. Both suites leave it clean. Commands, durations and log hashes are in `build/diagnostics/integrated-5d13d8a/result.json`; Gradle/API backend probes remain separately scoped evidence.
- Root again confirmed original `.gitignore` and `.pi` hashes. This checkpoint includes codecs, dependency closure, scoped Pages authentication/discovery and independent Build/E2E content composition; it does not include later Gecko imports or prove modern compilation, packaged gameplay or delivery.

## Task 10g — adapt GeckoLib animation imports without changing block-entity behavior

- Thirteen imports across BoxBlockEntity, ClawMachineBlockEntity and FigureBlockEntity now select modern GeckoLib packages behind version guards. Both historical references `6b5651e6`/`4f6e4039` agree; root rehashed the admitted 4.7.4/4.8 JARs and relevant class records. The 22 added lines leave all prior class-body bytes intact.
- Real strict/offline Stonecutter preprocessing passes for all three complete classes on both versions in 10.47 seconds. Root rehashed all six generated outputs and receipts: legacy Java tokens are unchanged, and modern changes contain only the reviewed imports. Attempt12 from `cfcb402` established the same 27 import errors with current networking/codecs; attempt13 adds only these imports and removes that entire error set.
- The modern diagnostic still fails: javac now emits 100 diagnostics across 22 files, reaching its usual reporting limit, so this is not an exhaustive remaining-error count. Data components/registry-aware persistence, block interaction and renderer/UI adaptations remain. No checksum rejection appears. Evidence is `build/diagnostics/fabric-1.21.1-probe/geckolib-import-review/unit-result.json` and `attempt13-compile-frontier.json`; owned processes close and both frozen baselines plus original caches remain intact. Rollback removes these guards/imports; no lane or gameplay qualification is claimed.

## Task 10h — preserve block interaction while adapting the modern dispatch signature

- The three block interaction bodies are extracted unchanged. Legacy use delegates with its original arguments; modern useItemOn preserves the supplied hand and stack reference. The adapter preserves all five legacy result meanings and maps PASS to SKIP_DEFAULT_BLOCK_INTERACTION. Native client/server dispatch inspection confirms that this route receives empty stacks and both hands; it avoids an additional default block interaction while retaining the item-use path and advancement semantics.
- Both historical references move Claw/Figure to useWithoutItem and add a Box fallback, which changes offhand dispatch and extraction behavior. This unit deliberately preserves canonical interaction instead. Real strict/offline Stonecutter generates all complete sources; javac17/21 compiles the result helper, and native Minecraft enums verify conversion plus rejection against the wrong API. Root independently reproduces the probe in 5.487 seconds with identical result SHA256 `dde9f0f1a2bda81f367a50109a2b501603f16cccf84e10026bb9d30e585320fb` and rehashes inputs. Evidence is `build/diagnostics/block-interactions/{closure,root-review}.json`.
- The probe compiles only the helper, not complete modern blocks, and runs no game interaction. Persistence, destroy and clone ports remain separate. Owned Gradle processes close. Rollback is these wrappers/helper/probe together; the modern-build guard and delivery restrictions remain active.

## Task 13f — extract independently bound producer archives with bounded resources

- Added a local extractor for Build/E2E bundles, reports and aggregates, with explicit externally authenticated ZIP digest/size inputs. It bounds central-directory records before ZipFile allocation, counts implicit directories in the physical inventory budget, rejects unsafe/normalized paths and links, and verifies every extracted file plus the exact descriptor-bound inventory before exclusive output creation. Standard bounded ZIPs are supported; multipart/ZIP64 inputs fail closed. Existing Pages download behavior is unchanged.
- Root passes 27 extractor/Pages/atomic-output tests. Independent review reproduced three accepted substitutions (NUL-truncated names, a late payload mutation and an extra output file) plus excessive implicit-directory creation; fixes reject all four. Regressions also preserve a concurrently created output, reject forged directory counts before parsing, enforce byte/expansion limits and allow only bounded empty aggregate logs. Input/output mutations invalidate extraction and owned staging is removed.
- This verifies local transport bytes, not API ownership, source content, newest producers or release qualification. The caller must obtain immutable artifact bindings and validate all extracted content separately. The ZIP byte-binding model follows [GitHub's artifact format and size documentation](https://github.com/actions/upload-artifact); no GitHub download or remote mutation was performed by these tests. Rollback removes this extractor and its tests.

## Task 12ac — bind authenticated Pages selection to exact compact pixels

- Schema2 source authentication now offers opt-in `bind_compact` / `--bind-compact`, emitting a separate pages-compact-selection record. It preserves full authenticated provenance, raw source-artifact identity, scope/projection and the exact compact-manifest SHA256. Raw inputs rederive each WebP from its authenticated PNG and compare exact encoder bytes; cache copies retain the original manifest bytes. Final reads recheck both inventories, manifests, matrix and contract.
- Root and implementation runs each pass 35 focused API/CLI, real-pixel, source and graph tests. Independent review reproduced an invalid acceptance of unrelated red WebPs with rewritten hashes/dimensions; rederivation now rejects both 1x1 and original-size replacements. Raw conversion and binding require the same encoder environment; differences fail closed, without claiming compatibility across Pillow/libwebp environments. Default/schema1 outputs remain unchanged.
- The companion record cannot authenticate itself or select freshness. Workflow transport, companion-artifact ownership and build_site/refresh integration remain pending; no inventory, workflow, publication or qualification authority changes. Rollback removes this opt-in adapter and its focused tests.

## Task 12ad — anchor static gallery writes before scoped assembly

- The site renderer now writes every static asset, image and JSON record through the shared descriptor-bound atomic-directory helper, with exclusive final publication. Default/schema1 selection and `_inventory` remain unchanged. Four reproduced failures involving replaced parents/stages, concurrent destinations and symlink parents now fail while preserving foreign files.
- Root independently passes all 17 focused site/atomic/round-trip tests in 3.116 seconds; implementation tests and independent code review agree. This local output foundation does not enable schema2 assembly, workflow deployment or publication. Rollback is the renderer write adapter and its five integration tests.

## Task 13g — download exact producer ZIP bindings through bounded HTTPS transport

- Added a release download adapter using externally authenticated immutable artifact ID, profile, ZIP size and digest. Streaming stops at the API-bound size; the existing evidence extractor verifies exact bytes and inventory. Pages retains its public download signature and extractor. Both paths use the existing HTTPS and credential-safe redirect policy.
- Independent review reproduced a temporary-file replacement that overwrote an external file; root also reproduced an accepted parent replacement. The shared downloader now retains its original descriptor, anchors creation/cleanup to the parent descriptor and rejects changed identities. Root passes 20 focused tests; independent download/extractor checks pass 17. HTTP is simulated while ZIP extraction and filesystem substitutions are real. No live download, producer freshness or qualification is claimed; rollback is this adapter, descriptor fix and tests.

## Task 10i — reverify clean legacy builds after the block adapters

- Clean commit `40f36c9c43c98ed5222cd320c6e1a57e8002e5f5`, tree `af0fc1b07b86b1c1765b75224b17baded5b0120c`, passes serial Fabric/Forge production, harness and check builds, followed by schema3 legacy staging/reverification. Root independently verifies both staged pairs, durable lane reports and all 724 unchanged source inputs in 6.737 seconds. Manifest SHA256 is `931910f27b2adf625ab0c5ac6f9005bd080dc72f0ffc7aa445983eb98e557c83`; report SHA256 is `803226dbddfd585b8cd1a4e599d9b17db025ec2e040955d03a5f76115865aed4`.
- Evidence in `build/diagnostics/task10-blocks-legacy/{result,root-review}.json` records BlockPops major61, production-only helpers, legacy use/interact signatures and absent modern API references. Prior caches/evidence remain intact and no Gradle processes remain. The external diagnostic wrapper failed after the successful runner while parsing a daemon-log name, so its process exit was not retained; lane exit codes and durable report are successful, and recovery only staged/reverified without recompilation. Coverage remains partial 2/12, with no gameplay, modern qualification or publication claim.

## Task 13h — join current producer metadata, ZIP transport and lane content

- Added `acquire_lane_evidence` in the future planner module. It checks a clean exact source/matrix context, authenticates both current producers, downloads their four immutable artifacts and composes independent Build/E2E scopes. Internal manifest/report/aggregate digests come only from authenticated extraction receipts. E2E run/attempt/event determine SourceIdentity and projection; only its tested production is selected. Unique build children preserve diagnostics without replacing caller directories.
- Forty focused tests pass in 28.688 seconds, including eight acquisition cases using real synthetic ZIPs, manifests, reports and pixels with simulated API/HTTP/toolchain boundaries. Rechecks reject changed producer state, crossed attempts/scopes, invalid transport and late payload/inventory mutations. Independent review reproduced a late Build-report change after its individual hash check; all four descriptor/stamp leases now remain open through the final source check and close together. The independent regression also passes.
- The API requires independently authenticated deployed-controller and source inputs; it does not establish deployment authority, emit a release plan or expose qualification/publication flags. A caller must still verify selected bytes when using them. CLI/controller authentication and final plan construction remain separate work. Rollback is this acquisition function and its tests.

## Task 10j — preserve block-entity item data across NBT and components

- Eight accesses in BoxBlock, FigureBlock, GeoBlockItem and BoxBlockItem now use BlockEntityItemData. Legacy preserves nullable NBT reads, aliasing and exact replacement. Modern reads copies and writes an independent component containing the required string id; historical references `6b5651e6`/`4f6e4039` agree on box_block/figure_block type IDs. Gameplay keys, defaults and surrounding flows remain unchanged. Mutating widgets, renderers and registry-aware block-entity persistence are separate seams.
- Root rehashed 84 API/library inputs and independently passes strict/offline Stonecutter plus javac17/21 and real ItemStack/codec checks in 9.072 seconds. The helper has matching class bytes across both runs, with majors61/65; all five complete source files preprocess, but only helper/probe compile. Native checks cover missing/empty data, replacement, ownership/legacy aliases, unrelated fields, codec round trips and invalid/missing id plus wrong-API negatives. Root's replay includes the final two import-order adjustments.
- Evidence is `build/diagnostics/item-block-data/{closure,root-review}.json` and `root-probe/`. The initial attempt is retained: actual runtime checks passed, but its wrong-API assertion expected a different javac message and was corrected. Owned Gradle processes close. Vanilla bootstrap creates no world and proves neither complete modern compilation nor gameplay. Rollback is the helper, eight substitutions and opt-in probe together.

## Task 12ae — render matrix-scoped Pages selections without inferring authority

- `build_site.build` now accepts opt-in external `branch_inputs`. It recomputes each complete discovery row and default scope from exact raw matrix bytes/Git blob, validates typed selection/provenance/compact-manifest bindings, and retains aggregate_scope in gallery releases. Matrix snapshots isolate validation; final input and payload reads reject drift. Caller-authenticated companion transport remains mandatory. Default/schema1 behavior, `_inventory`, CLI and workflows remain unchanged.
- Root passes all 25 focused site/atomic/round-trip tests in 26.059 seconds; implementation and independent review agree on the new bindings. Real compact pixels cover mixed schema1/preparing/shared, raw/cache selection and scheduled evidence; inconsistent attested/scheduled or crossed source identities fail. This adapter neither authenticates transport/freshness itself nor qualifies lanes. Review also found a pre-existing output-stage image mutation accepted by both renderer paths; a separate bounded fix remains required before workflow wiring. Rollback is this opt-in adapter and its tests.

## Task 10k — adapt Claw block-entity persistence to registry-aware methods

- ClawMachineBlockEntity now selects modern saveAdditional/loadAdditional/getUpdateTag signatures with HolderLookup.Provider and forwards that provider unchanged. Legacy active Java tokens remain identical. CollectionId save/load conditions and update behavior are preserved; custom update tags load only their published custom fields, without resetting unrelated modern components through loadWithComponents.
- Root independently rehashes 90 inputs and reproduces strict/offline preprocessing plus compilation of the complete class against real Minecraft/Gecko/Architectury APIs on javac17/21 in 8.151 seconds. Both complete class hashes match the implementation run, with majors61/65. The sole clean-baseline ModBlockEntities class supplies declarations only; neither it nor Claw is executed. Native vanilla dispatch confirms loadCustomOnly calls loadAdditional. Evidence is `build/diagnostics/block-entity-persistence/{closure,root-review}.json` and `root-probe/`.
- Provider callback signatures also agree with historical references and the [official NeoForge 1.21.1 branch interface](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/src/main/java/net/neoforged/neoforge/common/extensions/IBlockEntityExtension.java), inspected on September20. This is not a pinned NeoForge binary/runtime check, full mod compilation or gameplay. Owned Gradle processes close. Box/Figure persistence and renderer calls remain separate. Rollback is these guards and the opt-in probe.

## Task 12af — seal the exact rendered site bytes before output creation

- Both renderer paths now retain the exact static, image, JSON and empty .nojekyll bytes supplied to descriptor-bound writes. After all input rechecks, the renderer verifies the complete output file/directory inventory, byte hashes, sizes, types and link counts, then repeats identity/time stamps to detect changes during hashing. This closes the inherited stage-mutation failure recorded in task12ae; foreign/special/extra outputs fail without publishing a site.
- Root passes all 29 focused tests in 32.861 seconds and independently replays the original WebP-corruption regression in 0.745 seconds. Adversaries cover every output kind, same-inode/same-size changes, extras, links/FIFOs, later-file hashing and final scoped-input callbacks. Existing parent/stage replacement protections and default/scoped behavior remain intact. Rollback is the output seal and four tests plus fixture return-value adjustments; no workflow, remote publication or qualification is enabled.

## Integrated checkpoint — clean local commit 8868ec5

- A separate checkout of `8868ec5be3ce40b9b6663b0a863ff2f30c20422e`, tree `b292fedb2a55dd7bfa4ef8cc697db7ecc94fe9b9`, passes 454 Python tests (9 explicit real-Gradle opt-ins skipped) in 367.546 seconds and all 308 CI tests in 99.381 seconds. The checkout stays clean; no Gradle runs in this checkpoint. Later Claw persistence and rendered-output sealing retain their separate focused evidence.
- The first attempt is preserved: the diagnostic launcher incorrectly injected GIT_NO_REPLACE_OBJECTS into a test that deliberately sets up replacement refs. Removing inherited GIT_* controls from the launcher makes that focused test and the unchanged full checkout pass; no product or test change was required. Receipts and both attempts remain in `build/diagnostics/integrated-8868ec5/`, with successful logs SHA256 `de759a547d40f9448c56eaa857a1ab41b4e6db72cea284933c80ad95a8a818dd` / `fa1b405b04fdf83494fc955b5214a33c3d8e3492544506f8e2d6a4aa67407d48`. This is local regression evidence, not gameplay, lane qualification or publication authority.

## Task 13k — observe the exact canonical implementation checkout

- `authenticate_canonical_checkout` derives its repository from the implementation's actual protected path. It binds a clean raw source snapshot, schema2 integration matrix and current loader-bootstrap contract to the exact GitHub API repository/default commit/tree, repeats local and remote observations and rejects inherited GIT_* controls before use. No caller-supplied checkout, expected SHA or receipt selects the controller.
- Root independently passes seven real Git/bootstrap tests in 15.527 seconds; the existing eight acquisition tests also pass and independent review finds no blocker. Tests cover hidden/dirty bytes, API identity, redirection, current-versus-proposed bootstrap and late drift. This observation does not grant AUTH-1, deployed generation, governance completeness, qualification or publication; the forthcoming plan caller must reauthenticate within all transport leases. No real GitHub requests occur in these tests. Rollback is this observer and its tests.

## Task 10l — preserve Box/Figure persistence, drops and clone data

- Both block entities now use registry-aware modern persistence/update callbacks and saveToItem while retaining the exact legacy active Java tokens. Modern saveToItem receives its provider from the four Level/LevelReader drop/clone callers, preserves Box IsOpen=false and writes the exact block-entity component type ID. Modern playerWillDestroy retains the drop order and returns the superclass result unchanged; detached entities do not lose fields through a new level-null guard.
- Root independently repeats strict/offline Stonecutter and compiles all four complete classes plus both real compatibility helpers against the actual Minecraft/Gecko/Architectury APIs on javac17/21 in 15.973 seconds. Class hashes match the implementation run (majors61/65), input hashes remain unchanged, and bytecode confirms provider forwarding, clone signatures and drop-before-super ordering. Ten baseline classes supply declarations only; stale copies of the six compiled classes/helpers are excluded and none of those baseline symbols execute.
- Evidence is `build/diagnostics/box-figure-persistence/{closure,root-review}.json` and `root-probe/`, result SHA256 `20a74082d4f1811bddc4b27c5c60d3fb6730b8d06bcd5f0827f96c8e0b22595b`. This is complete-class API/legacy-preservation evidence, not gameplay, a full modern mod build or NeoForge runtime validation. Owned Gradle processes close; renderer consumers remain a separate unit. Rollback is these four guarded sources and the opt-in probe.

## Task 12ag — bind collected Pages compacts to a separate run-attempt companion

- The protected collect shell derives scope from the exact external branch matrix and projection from source authentication, preserving schema1 arguments. Scoped raw/cache compaction is followed by authenticated bind-compact; typed source identity and raw matrix bytes are rechecked before a separate atomic companion is created. The companion name binds branch token, commit, Pages run ID and attempt; its matrix and optional selection remain outside the strict compact inventory. Tokens are supplied only to authentication subprocesses, and upload-ready is emitted only after success.
- Root repeats all 43 shell/workflow/security/graph checks in 4.432 seconds; the underlying API/binding integration suite also passes and independent review finds no blocker. Shell tests cover raw/cache and both projections, identity/matrix drift, helper failures, invalid scope/attempt and existing output preservation, including Bash3.2 empty-array behavior. Discovery, assembly CLI, refresh and rotate remain unchanged and pending. The consumer must independently authenticate the companion's immutable artifact ID/digest and exact workflow/run/attempt ownership; the JSON cannot authenticate itself. No remote workflow or publication was run. Rollback is this collect step, companion upload and matching shell tests.

## Task 10m — adapt item renderers without losing cached state or saved fields

- Three modern item renderers read block-entity components and use the timer API from historical `6b5651e6` at the same renderer paths. Their entities expose the canonical complete field-reading body through loadForItemRendering; modern loadAdditional still calls its superclass before that body. Unlike the historical partial Box reader, hitbox/logo fields remain included. Box preserves its three cases: present block data loads, an unmodified item resets with an empty tag, and unrelated explicit item data leaves cached state intact. Figure/Claw remain no-ops when block data is absent. All six legacy classes retain identical active Java tokens.
- The strict/offline probe passes in 18.701 seconds: all six complete classes plus the real item-data helper compile on javac17/21 (majors61/65), complete read bodies match, and bytecode binds the timer boolean and data-loading calls. Native ItemStack checks distinguish nonempty default components from explicit patches and cover unrelated names and present/empty block data. Independent source/test review finds no blocker.
- Evidence is `build/diagnostics/item-renderer-data/{preparation,closure}.json` and `probe/`, result SHA256 `6b54127cbfd0f2b96b1d3d6e1fdf349a90b2c81baf99cab97fb86e4395442f07`. All 197 inputs remain unchanged; 102 clean-build classes supply declarations only and are excluded from runtime classpaths. No renderer, world or gameplay executes, and this does not prove full modern compilation. Owned Gradle processes close. Rollback is these six guarded sources and the opt-in probe.

## Task 10 diagnostic — refreshed Fabric1.21.1 frontier at c8ae2b7

- One isolated strict serial attempt from `c8ae2b7e00664021d39913f47ec56c12be705cd4`, tree `4e9cd8da1ea00d23b5f0d5d6d80aadc6301138a7`, reaches common javac with observed JVM/compiler21 and release21. Its fixture changes only the diagnostic guard; a separate init script raises the diagnostic cap to1000. All 136 errors across21 files are captured without truncation or missing-checksum rejection; the three block entities and BoxBlock/FigureBlock have no diagnostics. The earlier attempt13 stopped at100, so totals are not a regression comparison. The later item-renderer unit is outside this checkpoint.
- The log ends BUILD FAILED in44s. The diagnostic controller exited early because its daemon-filename parser was wrong; the same wrapper/daemon were monitored to closure at76.05s without restart, timeout or signals, but the parent exit code is unavailable and remains null. The ignored helper now uses an exact PID filename pattern with six regression cases. Prior attempt13 files/cache and main guard/matrix/metadata remain unchanged. Evidence is `build/diagnostics/fabric-1.21.1-probe/attempt14-{run,compile-frontier,observed-compiler-input-hashes,controller-regression}.json` and `attempt14-strict.log`. Production/harness/check completion and gameplay remain unproven; no qualification or publication is inferred.

## Task 13l — expose the canonical-only local release-plan command

- The real CLI authenticates its own clean deployed default checkout, acquires independent scoped Build/E2E evidence, and selects only the E2E-tested production archive with the lane's own identity/version. It writes a new direct build child only after live producer/canonical/source rechecks inside all four transport leases, exact plan-byte checks and final transport seals. All targets and remaining unselected lanes stay explicit. Public acquisition behavior is preserved; no source path, expected SHA, generation or receipt can override authority.
- Root repeats all 24 CLI/authentication/acquisition tests in 112.598 seconds and executes the script help; independent review also closes two reproduced failures: default drift during final producer reads and tracked-source drift during temporary-plan writing. Negative tests leave no final plan. Real Git/bootstrap/ZIP/content/pixels are exercised with simulated API/HTTP and fixture toolchains; no live producer or publication request occurs.
- This completes task13's local implementation and no-publication command contract. Actual use still requires independently deployed canonical bytes and authentic current qualified evidence; this plan does not grant AUTH-1, prove governance completeness, qualify other lanes or complete migration. The command and its prerequisites are documented in `docs/release-architecture.md`. Rollback is the CLI/private finalizer, tests and command documentation together.

## Task 10n — retain Claw destruction order with the modern return type

- ClawMachineBlock selects the modern BlockState return type and returns its superclass result directly, matching both historical `6b5651e6` and `4f6e4039`. The creative/survival drop branches and legacy active Java tokens remain unchanged. No clone or removal behavior changes.
- Root repeats strict/offline preprocessing and compilation of the complete Claw block/entity plus real interaction helper on javac17/21 in 6.308 seconds. Complete class hashes match the implementation run, all 96 inputs remain unchanged, and bytecode preserves drop-before-super ordering. The negative fixture restores void and reproduces the two attempt14 errors. Six clean-baseline classes supply declarations only; no world/gameplay executes. Evidence is `build/diagnostics/claw-block-destroy/{closure,root-review}.json`, result SHA256 `59c547aa29556f369e79a115022e68e6e8596cb080a451afbd28f483c58b17b1`. Rollback is the eight guarded source lines and the opt-in test.

## Task 10o — forward the modern tooltip context without changing tooltip content

- GeoBlockItem now forwards Item.TooltipContext to the modern superclass signature, as in both historical references; its complete tooltip body and legacy Nullable Level path remain unchanged. The existing renderer probe additionally compiles the real GeoBlockItem and excludes its old declaration, retaining full legacy-token comparison and native ItemStack checks.
- The expanded strict/offline javac17/21 probe passes in 11.842 seconds with all 197 inputs unchanged; independent review finds no blocker. Evidence is `build/diagnostics/item-renderer-tooltip/{preparation,closure}.json` and `probe/`, result SHA256 `1fa6703a370b999917cfa8a90c12833a9407f3dca45e52e9065c17cabb5c1e05`. Actual tooltip rendering and gameplay are not executed. Rollback is the five guarded signature lines and bounded probe extension.

## Task 10p — preserve fresh signed profile access across Authlib APIs

- AuthlibProfiles bridges fillProfileProperties/getValue to fetchProfile/profile/value for DropBoxPacket, UnlockCollectionPacket and GetBoxCommand. Both historical references use the modern UUID/secure=true/null-result mapping. The six substitutions retain profile construction/name, null-UUID guards, catch/log policy, skin-property bytes and surrounding discovery/fallback behavior; reversing the imports/substitutions restores complete original consumer bytes. The secure=false client-skin registration path is deliberately separate.
- Root reproduces strict/offline Stonecutter, javac17/21 and native Authlib4.0.43/6.0.54 checks in 5.905 seconds, with all 84 inputs unchanged and majors61/65. Proxy services exercise the actual interface without HTTP; the three extracted consumer profile bodies retain their null/try/catch/log branches while player/figure access is supplied as test parameters. Return identity, UUID/name/secure flag, nulls, propagated/caught exceptions and raw signed/unsigned property values pass; each helper rejects the other era's API.
- Evidence is `build/diagnostics/authlib-profiles/{closure,root-review}.json`, result SHA256 `99760ca5b6e2889c76efa796441bc01daa60505fb1af8c221f7ffc2cafe46833`. All four full sources preprocess; only the helper and extracted-body probe compile, since three item-NBT writes remain a separate seam. No HTTP, game or qualification occurs. Rollback is the helper, six substitutions and opt-in test.

## Integrated checkpoint — clean local commit f8d39b2

- A separate no-remote checkout of `f8d39b27f2de00febe11e3d97a55fb625cca8bf9`, tree `da50db2ae20f80b601b227727a611e6ff9425847`, passes 483 Python tests (13 real-Gradle opt-ins skipped) in 409.542 seconds and all 308 CI tests in 100.662 seconds. It remains clean before/after. This covers the release CLI, collect companion workflow and renderer/tooltip units; the later Authlib unit retains separate native evidence.
- Receipts/logs remain in `build/diagnostics/integrated-f8d39b2/`, with log SHA256 `38a37779420be9e7c4c8de3d365212438634b1420231a9add03c3c89f3ccea04` / `50315ccd5f48b42437fcd27a837eee7cc0c8914fccdfc7fe6c4671c15607e291`. The launcher removes inherited Git controls and Gradle opt-ins; no Gradle or remote action runs in this checkpoint. Passing local regression does not establish gameplay qualification, deployment or publication authority.

## Task 10q — adapt player-skin reads while retaining registration and cache behavior

- FigureModel/FigureBlockModel select modern PlayerSkin texture access for their six player/insecure-profile reads; SkinModelDetector captures one PlayerSkin and compares its texture/model directly. Legacy active tokens remain identical in all three classes. Priorities, UUIDs, caches, pixel detection and four legacy registerSkins calls stay intact; historical rewrites that removed fallback behavior were not adopted.
- Root reproduces strict/offline preprocessing and real javac17/21 checks in 5.585 seconds; 102 inputs and complete class hashes match the implementation run, with independent review finding no blocker. The detector and four dependencies compile completely on both eras, and both complete models compile on17. On21 the models still fail at exactly four registerSkins calls and emit no classes; this expected boundary is explicit, not a successful modern-model compilation. Ten baseline symbols are declarations only, with no rendering/network execution. Evidence is `build/diagnostics/player-skin-reads/{closure,root-review}.json`, result SHA256 `c051ced5688187e35a5d5e97964f789eb67cad703efec1ff41fea44a8836e40c`. Rollback is these three guarded sources and the opt-in probe; asynchronous registration remains a separate required unit.

## Task 12ah — acquire authenticated Pages companions through the actual renderer CLI

- An opt-in build_site route binds its clean implementation commit/tree and invocation to the live default, active Pages workflow, exact in-progress run/attempt and successful collect coverage. Immutable companion IDs, direct/list API digests/sizes, branch heads and matrix Git blobs are independently checked. Bounded extraction supplies external branch_inputs; all companion descriptors, byte/stamp/inventory and path bindings stay live through final API/source checks and atomic rendering. A conservative aggregate quota applies before downloads. Default CLI/workflows remain unchanged; discovery and refresh/rotate wiring are still pending.
- Root repeats all 40 companion/site/output/transport tests in 60.358 seconds; independent review finds no blocker. Real Git/ZIP/pixels with simulated API cover preparing/full/mixed schema, wrong ownership/attempt/tree, unsafe payloads, late source/default changes and earlier-companion byte/directory substitution during later hashing. That last case was reproduced failing before global seals were added. The companion JSON confers no authority by itself. No remote action, gameplay qualification or publication runs. Rollback is this opt-in CLI, two-file archive profile and tests; this unit totals394 authored lines including this note.

## Task 12ai — wire protected Pages assembly to authenticated companions

- The build job derives its route from the protected implementation matrix: schema1 retains its CLI arguments, while schema2 passes the exact protected implementation/default and Pages run/attempt to the authenticated reader. Explicit origin/ref/SHA guards fail before rendering; only the scoped renderer receives the token. Discovery inventory lives in RUNNER_TEMP so the implementation stays clean, with the promotion artifact basename preserved.
- Root repeats all 48 focused shell/workflow/security/graph tests in 5.366 seconds and independent review finds no blocker. A Bash3 guard regression was reproduced and corrected with explicit failure exits. Discovery, refresh and rotate remain unchanged and require their own adapters before activation. No remote action or publication occurs. Rollback is this build step, promotion path and shell tests.

## Task 10r — preserve gifted box data through the component bridge

- DropBoxPacket, UnlockCollectionPacket and GetBoxCommand now write block-entity item data with the historical blockpops:box_block ID. Reversing the three imports/calls restores each complete consumer byte for byte, including all Authlib calls. Legacy addTagElement executes the same getOrCreateTag/put operation as before; modern copies retain the mandatory component ID.
- Root repeats strict/offline Stonecutter, complete compilation of the three consumers plus five current helpers on javac17/21, and native ItemStack/codec checks in 10.927 seconds. All 194 inputs remain unchanged and complete class hashes match the implementation run. The 102 baseline declarations exclude every compiled helper/consumer and never execute. Evidence is build/diagnostics/gifting-item-data/{closure,root-review}.json and root-probe/. No HTTP, full modern mod build, gifting gameplay or qualification is claimed. Rollback is these three substitutions/imports and the opt-in probe.
