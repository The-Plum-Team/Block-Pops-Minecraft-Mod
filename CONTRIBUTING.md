# Contributing

## Source and release ownership

Ordinary product and documentation PRs target the exact current `master`.
Controller upgrades also target only `master` through the separately authorized
procedure below. Enrolled release branches are not ordinary PR targets: they
accept only the generated `Release sync / ...` bridge, including for a reviewed
conflict reconciliation. `common`, `fabric`, and the matrix-selected FML loader
remain the production source layout for the tree being tested. The authoritative
branch-local matrix owns:

- exact branch role and synchronization identity;
- Minecraft, loaders, Gradle JVM, artifact/runtime Java toolchains, and source routing;
- Gradle production and E2E harness tasks and output paths;
- the published mod version and full dependency coordinates;
- loader installers and runtime dependencies;
- PR and scheduled packaged-E2E lanes; and
- the canonical visual reference identity.

Never infer any of these from a branch name. Release branch names are opaque.
A feature branch carrying a copied matrix is deliberately not enrolled because
the matrix's exact branch identity does not match the feature ref.
One branch may activate Forge or NeoForge, never both. That matrix-derived
choice also binds `cpw.mods` and `org.lwjgl` to one reviewed repository origin:
Maven Central for the Forge line, and the NeoForge/Minecraft repositories for
the NeoForge line; strict checksums remain mandatory in both cases.

## Required checks

Before opening a pull request, run the matrix validator, Python tests, aggregate
production/harness build, and artifact staging commands in the README. Changes
to the scenario contract must regenerate both harnesses and pass contract
mutation tests.

Tests that need the pinned mod-base kit resolve it only through
`scripts/ci/mod_base_kit.py`: from the Build gate's staged `out/mod-base-kit`
overlay, from `MOD_BASE_KIT_PATH`/`MOD_BASE_KIT_SHA`, or from a verified user
cache outside the repository that the first run fetches anonymously. An
unavailable kit fails the suite; it never skips. To iterate against a local kit
clone, set `MOD_BASE_KIT_PATH=../mod-base MOD_BASE_ALLOW_UNPINNED=1` (refused
whenever `CI` is set).

Loader build scripts and `src/e2e` entrypoints are also bound by
`e2e/loader-bootstrap-contract.json`. Do not update its hashes as a mechanical
response to a failure: review the executable change and preserve the single
final `gradle/e2e-harness-conventions.gradle` binding.

Remote Gradle artifacts remain checksum-verified. Loom also creates mappings,
merged Minecraft modules, and remapped dependencies locally; their ZIP bytes
are not reproducible across hosts. The five exact generated-name exceptions in
`gradle/verification-metadata.xml` are safe only together with
`gradle/repository-policy.gradle`, which makes those namespaces unreachable
from every HTTPS repository and binds Loom's four local repositories to their
canonical cache paths. Git-tracked `.gradle` content and implicit `buildSrc`
builds are forbidden. Never broaden either side or add observed generated
hashes one run at a time.

Two deterministic gates are authoritative. On ordinary PRs into `master`, branch
protection must require their protected App-authenticated bridge contexts, not a
check run created from candidate-controlled workflow YAML:

- `Trusted PR / Build and verify`
- `Trusted PR / Packaged E2E gate`

`Build and verify` and `Packaged E2E gate` remain the underlying deterministic
jobs. Generated release-sync PRs use the separately authenticated
`Release sync / ...` contexts described below. Do not expect the ordinary
`Trusted PR / ...` contexts on a PR whose base is a release branch.

Protected controller changes use the deliberately narrower procedure in
[`docs/operations.md`](docs/operations.md#controller-upgrade-procedure), not an
ordinary feature PR. Branch from the exact current `master` as
`controller-upgrade/<purpose>`, target `master`, add the exact
`controller-upgrade` label, and keep the change within the protected
controller/docs/test or matrix-selected loader-bootstrap roots. After reviewing
the complete exact-head diff, only `AkaNebur`, an administrator of the owning organization, may post
`/controller-upgrade approve <head-sha>`. Every new head requires a new approval;
the latest exact-head `approve` or `revoke` command wins. Neither this route nor
an ordinary PR may change the release matrix, verification metadata, or
version-specific shims. Both newest exact Build and Packaged E2E attempts and
their protected App contexts remain mandatory.

AI visual review and the public evidence gallery are advisory. Do not weaken a
deterministic assertion or skip a lane to accommodate visual-review noise.
The gallery is published by the pinned
[mod-base](https://github.com/The-Plum-Team/mod-base) kit through
`scripts/pages/mod_base_adapter.py` and `site/mod-base.json`
([ADR 0001](docs/architecture/decisions/0001-adopt-mod-base-public-evidence.md)).
Its managed files (`pages.yml`'s managed region, `scripts/ci/mod_base_kit.py`
and `docs/ai/shared/*.md`) come only from the kit: never edit them by hand, and
check them with `python3 scripts/ci/mod_base_kit.py run template check --repo .`.
A kit bump is a controller upgrade made with
`python3 scripts/ci/mod_base_kit.py bump --to vX.Y.Z` on a
`controller-upgrade/mod-base-vX.Y.Z` branch; see
[`docs/operations.md`](docs/operations.md#mod-base-kit-bumps).
The protected review path uses the owner's Claude Code subscription token from
the `visual-review` environment, like Quick Skin, and never an API key. Changes to captures,
prompts, routing, retry limits, image bounds, or model-visible expectations
must also update the security and cost tests described in
[`docs/visual-review.md`](docs/visual-review.md).

## Packaged scenario changes

Every contracted step needs a real assertion. Every captured checkpoint gets
the semantic identity `<scenario>.<role>.<step>`. Applicability is universal
unless the scenario schema explicitly models and protects a narrower scope.

Harness code may drive a real production control or Minecraft interaction, but
must not construct a fake product screen or mutate server state as a substitute
for the production packet path. Development-only UI such as the Develop tab or
figure-position editor is not valid packaged coverage.

Each deterministic visual probe needs calibration canaries for every supported
layout variant. The canaries must accept legitimate font/layout differences and
still reject empty, washed-out, blurred, missing-widget, or wrong-state frames.
Do not tune a threshold from one loader screenshot and assume it is portable.

## Release branch synchronization

Shared changes originate on `master`. Automation discovers enrolled releases
from strict branch-local matrices, creates or reuses one two-parent merge
candidate per exact target head, retains the target matrix byte-for-byte, and
explicitly dispatches Build and Packaged E2E. The release branch accepts only
the PR opened for that authenticated `automation/release-sync/<token>` head.

Unknown conflicts fail closed. Resolve one only by reviewing and constructing an
exact merge commit whose ordered parents are the current release head first and
the current `master` head second. Preserve the release matrix byte-for-byte and
the target-owned loader/version implementation while resolving the intended
shared product changes; protected controller paths must remain a valid canonical
projection. Push that exact commit to the canonical
`automation/release-sync/<token>` ref, where `<token>` is the protected
`branch-token` value for the exact release branch, using force-with-lease if the
rolling ref already exists. Then wake the protected synchronizer. It must
reauthenticate and reuse the exact candidate, open or update the generated PR,
dispatch both gates, merge only that tested head, and attest the final release
HEAD. Never open an ordinary release PR or merge the hand-built candidate
directly.

Automation merges only the exact head authenticated by the newest exact-head
run of both gates. A newer pending or failed run supersedes an older success.
Post-merge attestations must reproduce the final parents, tree, and retained
matrix.
