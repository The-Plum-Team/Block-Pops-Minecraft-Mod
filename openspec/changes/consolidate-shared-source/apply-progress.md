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
