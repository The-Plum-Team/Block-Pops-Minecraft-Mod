# Contributing

## Source and release ownership

Work from the branch for the Minecraft line you intend to change. `common`,
`fabric`, and the active FML loader module remain the canonical production
source layout for that branch. The authoritative branch-local matrix owns:

- exact branch role and synchronization identity;
- Minecraft, loaders, Java toolchain, and source routing;
- Gradle production and E2E harness tasks and output paths;
- the published mod version and full dependency coordinates;
- loader installers and runtime dependencies;
- PR and scheduled packaged-E2E lanes; and
- the canonical visual reference identity.

Never infer any of these from a branch name. Release branch names are opaque.
A feature branch carrying a copied matrix is deliberately not enrolled because
the matrix's exact branch identity does not match the feature ref.

## Required checks

Before opening a pull request, run the matrix validator, Python tests, aggregate
production/harness build, and artifact staging commands in the README. Changes
to the scenario contract must regenerate both harnesses and pass contract
mutation tests.

Loader build scripts and `src/e2e` entrypoints are also bound by
`e2e/loader-bootstrap-contract.json`. Do not update its hashes as a mechanical
response to a failure: review the executable change and preserve the single
final `gradle/e2e-harness-conventions.gradle` binding.

Two deterministic gates are authoritative. On ordinary PRs, branch protection
must require their protected App-authenticated bridge contexts, not a check run
created from candidate-controlled workflow YAML:

- `Trusted PR / Build and verify`
- `Trusted PR / Packaged E2E gate`

`Build and verify` and `Packaged E2E gate` remain the underlying deterministic
jobs. Release-sync PRs use the separately authenticated `Release sync / ...`
contexts described below.

AI visual review and the public evidence gallery are advisory. Do not weaken a
deterministic assertion or skip a lane to accommodate visual-review noise.

## Packaged scenario changes

Every contracted step needs a real assertion. Every captured checkpoint gets
the semantic identity `<scenario>.<role>.<step>`. Applicability is universal
unless the scenario schema explicitly models and protects a narrower scope.

Harness code may drive a real production control or Minecraft interaction, but
must not construct a fake product screen or mutate server state as a substitute
for the production packet path. Development-only UI such as the Develop tab or
figure-position editor is not valid packaged coverage.

## Release branch synchronization

Shared changes originate on the canonical integration branch. Automation
discovers enrolled releases from strict branch-local matrices, creates one
two-parent merge candidate per exact target head, retains the target matrix
byte-for-byte, and explicitly dispatches Build and Packaged E2E. Unknown
conflicts fail closed and require a deliberate branch-specific port.

Automation merges only the exact head authenticated by the newest exact-head
run of both gates. A newer pending or failed run supersedes an older success.
Post-merge attestations must reproduce the final parents, tree, and retained
matrix.
