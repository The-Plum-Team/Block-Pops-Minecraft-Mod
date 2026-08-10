# Multi-version release architecture

## Trust zones

1. Branch-local matrices and protected controller code define expected work.
2. Untrusted candidate code builds and launches Minecraft without repository
   write tokens, model credentials, environments, or deployment credentials.
3. Protected secretless controllers authenticate exact run, attempt, job graph,
   commit, tree, matrix, artifact, report, screenshot, and capture identities.
4. Only a bounded canonical image capsule crosses into a fresh model-credential
   runner. Only normalized advisory JSON leaves it.
5. Dedicated writer jobs publish statuses, merge an exact head, delete an exact
   single-use artifact ID, or deploy an already-built Pages artifact. They do
   not execute candidate code.

## Branch lifecycle

The canonical matrix has `branch.role=integration` and synchronization disabled.
An enrolled release matrix has `branch.role=release`, its exact API branch name,
the canonical source, and synchronization enabled. Missing matrices are not
release claims. A valid JSON matrix that self-identifies as an enrolled release
but fails the complete schema aborts discovery.

The synchronizer binds each plan to the exact source commit/tree and target
commit/tree/matrix blob. Its merge candidate has exactly those two parents.
The target matrix remains byte-identical. A protected controller-path inventory
must match the canonical source. Unknown conflicts stop publication.

Because a pull request created with `GITHUB_TOKEN` is not a dependable trigger
for every downstream workflow configuration, the publisher explicitly
dispatches Build and Packaged E2E at the candidate ref. The trusted result
handler ignores its wake-up payload beyond using it as a locator and re-queries
GitHub for the newest exact runs and attempts.

## Artifact chain

Gradle builds reproducible remapped production and physically separate remapped
harness JARs. `scripts/release/verify_release.py` stages immutable copies and an
exact manifest. Each packaged lane revalidates that manifest, installs genuine
loader clients and a dedicated server, installs only hash-verified matrix
dependencies, and places the harness on clients only.

The branch matrix owns the mod version and all full runtime dependency
coordinates. `e2e/loader-bootstrap-contract.json` is a protected security
allowlist, not a second release matrix: it contains one reviewed build-script
digest and exact E2E entrypoint/resource inventory per supported loader.
`scripts/ci/loader_bootstrap.py` derives the active loaders from the matrix,
reads the exact tested Git commit, rejects extra/missing/executable files, and
requires one final binding to the physically separate harness convention. The
trusted release handler validates a candidate against the contract from the
canonical protected commit.

Reports and screenshots are accepted only after exact scenario, role, ordered
step, assertion, capture basename, digest, size, image decode, dimensions, pixel
probe, and full inventory validation. An extra file is evidence corruption, not
harmless diagnostics.

## Gate and advisory policy

Build and Packaged E2E are deterministic required gates. The visual model is
asked about semantic UI defects—missing widgets, blur, clipping, transparency,
text/state/layout errors, or material render differences. Whole-frame pixel
inequality alone is not a defect and never makes the model authoritative.

Release-sync PRs use bridge contexts `Release sync / Build and verify` and
`Release sync / Packaged E2E gate`. This prevents an incidental approval-held
PR run with the same check name from colliding with authenticated dispatched
runs. The handler posts a bridge status only for the exact tested head.

Ordinary PR workflow YAML is part of the candidate tree, so its own green check
cannot authenticate that YAML. A protected default-branch `workflow_run`
evaluator therefore treats completion events only as locators, re-reads the
current same-repository PR and exact synthetic merge, requires the matrix and
dependency-verification metadata to match the current base, verifies protected
controller/bootstrap parity, selects the newest exact Build and Packaged E2E
attempts, and authenticates their complete job graphs. A separate status-only
GitHub App then emits `Trusted PR / Build and verify` and
`Trusted PR / Packaged E2E gate` on the current source head. The App writer has
no checkout or candidate execution surface. Missing App credentials produce no
trusted context and fail closed.

Release synchronization has its own protected default-branch handler that
authenticates exact topology, controller parity, loader bootstrap, run attempt,
and job graph before merging only the tested automation head.
