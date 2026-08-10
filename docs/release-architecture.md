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
