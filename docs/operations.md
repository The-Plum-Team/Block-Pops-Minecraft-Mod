# Repository configuration and incident recovery

## Local serial build diagnostics

Inspect a selected lane with `python3 scripts/release/build_matrix.py --plan --artifact-node fabric-1.20.1`.
For execution, omit `--plan` and supply `--java-home /absolute/jdk21` plus
`--java17-home /absolute/jdk17` when the selected lane compiles/runs on Java17.
Use `--scope legacy` for preparing-mode legacy lanes or `--scope full` for a
complete configured inventory; the default full scope rejects unresolved targets.
`--clean` adds only the selected project's clean task.

Execution owns one checkout lock, serial Gradle processes and isolated per-lane
homes. Explicit JDK probes, observed compiler selections, source/output hashes
and archive boundaries feed `build/build-matrix-report.json`; per-lane logs and
receipts live under `build/observations/`. A new valid execution invalidates prior
success before probing; failures stop subsequent lanes. Cancellation reaps only
the owned process group. Windows execution remains unsupported until equivalent
process-tree ownership is implemented. Inherited JVM/Gradle option variables and
nonempty `ORG_GRADLE_PROJECT_*` variables are rejected. A successful diagnostic
report does not certify clean release provenance or packaged Minecraft scenarios.

## Owner configuration required

Build, E2E, evidence validation, and PR evaluation are secretless. Narrow
governance/publication writers still require owner configuration:

- Protect `master` with required pull requests, strict up-to-date heads, no
  force pushes/deletions, and no bypass actors. Require the App-sourced contexts
  `Trusted PR / Build and verify` and `Trusted PR / Packaged E2E gate`; do not
  use the similarly named candidate-workflow checks as the protected contexts.
- Create and install a repository-scoped **PR gate** GitHub App with only
  Metadata read and Commit statuses write. Create a protected `pr-gate`
  environment restricted to `master`, set `PR_GATE_APP_CLIENT_ID` there as a
  variable, and store `PR_GATE_APP_PRIVATE_KEY` there as its only secret. The
  protected `handle-pr-gate-result.yml` evaluator is read-only; a fresh writer
  job uses this App only after revalidating the current PR, exact synthetic
  merge parents/tree, ordinary control-plane parity or a bounded SHA-approved
  controller upgrade, newest run attempts, and exact job graphs. Configure both
  required contexts with this App as their expected
  source. Bootstrap in that order: install/configure the App and environment,
  let one current PR produce both contexts, then select those existing contexts
  and that exact App as the expected source in branch protection.
- An organization/enterprise may instead enforce Build and Packaged E2E as
  required-workflow rules from a protected repository/ref. GitHub does not
  expose that rule at the level of this user-owned repository, so it is not the
  documented bootstrap path here.
- Protect every matrix-enrolled release branch with strict bridge contexts
  `Release sync / Build and verify` and
  `Release sync / Packaged E2E gate` plus the same no-bypass rules. Release
  branches accept only generated synchronization PRs; the ordinary
  `Trusted PR / ...` controller and controller-upgrade mode target `master`
  only.
- Keep default `GITHUB_TOKEN` permissions read-only. The current implementation
  requires **Allow GitHub Actions to create and approve pull requests** for its
  narrowly permissioned synchronization jobs. Merely installing an App/token
  does not switch those workflows to that identity. An App migration must first
  wire a protected fresh writer and exact expected-source status policy; do not
  treat an App permission sketch as already implemented.
- Do not replace `scripts/ci/untrusted_runner.py` with environment-variable
  scrubbing alone. Build, tests, fan-in decoders and packaged Minecraft must
  remain under the non-sudo copied-tree boundary, and the account must be dead
  before `upload-artifact` runs. If Ubuntu runner/user-management semantics
  change, freeze the gates and repair this boundary before accepting evidence.
- Set Pages source to GitHub Actions. Create the `github-pages` environment and
  limit deployment to protected `master`. Pages runs the pinned
  [mod-base](https://github.com/The-Plum-Team/mod-base) kit: its managed caller
  `.github/workflows/pages.yml` keeps the only `pages: write`/`id-token: write`
  job, and `site/mod-base.json`, `scripts/pages/mod_base_adapter.py` and every
  kit pin are trusted roots of those privileged jobs. The `master` protection
  above is therefore a precondition of the mod-base adoption and of every kit
  bump; the kit's
  [operations guide](https://github.com/The-Plum-Team/mod-base/blob/main/docs/OPERATIONS.md#p1-ruleset-block-pops-master)
  holds the exact ruleset payload. Require the contexts only after a current PR
  has passed both. From the schema-2 `preparing` enrollment (`466426b`) until
  the trusted-gate projection landed (#12, merge `53ed97d`), controller parity
  refused every PR. Until gate runs were matched by their PR head and controller
  reference (#15, merge `7ed7975`), no real gate run was evidence at all. Either
  way the ruleset would have blocked every merge. The remaining order is:
  configure the PR gate App in the `pr-gate` environment, post
  `/controller-upgrade approve <head sha>` on the mod-base adoption's exact
  head, confirm that both `Trusted PR` contexts turn green on it, and only then
  apply the ruleset
  ([ADR 0001](architecture/decisions/0001-adopt-mod-base-public-evidence.md)).
- Create a protected `visual-review` environment if advisory AI review is
  desired and restrict it to protected `master`. Store the owner's
  `claude setup-token` token as that environment's `CLAUDE_CODE_OAUTH_TOKEN`
  secret, as listed in [the visual-review runbook](visual-review.md), never as
  a repository secret. Do not add an Anthropic API key. The model job has no
  checkout, package manager, image decoder, or GitHub write scope; the model
  gets only the Read tool for the images it reviews, and the job's read-only
  GitHub token exists only for the final stdlib identity preflight.
- Keep repository Actions artifact retention at **90 days or greater**; the
  canonical lossless anchor `mb-anchor--<branch-token>--<commit>--<run>--a<attempt>`
  and the `mb-cache--<branch-token>--<commit>` Pages caches cannot meet their
  windows under a lower repository cap. Ordinary `mb-handoff--<branch-token>--a<attempt>`
  handoffs live one day.

At the 2026-08-11 API snapshot the repository was private and both long-lived
matrix lines (`master` and `1.21.1-neoforge-fabric`) reported unprotected; the
feature-branch inventory is intentionally irrelevant. The ruleset/protection APIs returned
HTTP 403 with an upgrade requirement. GitHub Pro (or making the repository
public) is therefore required before deterministic checks can be institutionally
authoritative against direct pushes. Do not represent a green workflow as
branch governance until protection is visible through the API.

The PR-gate App is intentionally distinct from the synchronization identity.
Its installation token may write commit statuses only: it cannot read candidate
contents, dispatch workflows, modify branches, create PRs, comment, or merge.
`CODEOWNERS` is defense in depth and becomes enforceable only when branch
protection requires an eligible independent code-owner review; it does not
authenticate a check by itself. A sole repository owner cannot satisfy an
independent-review policy on their own PR.

Keep default Actions permissions read-only. The current repository setting also
disallows Actions-created PRs; enable **Allow GitHub Actions to create and
approve pull requests** before expecting the current automated release PR
creation path to work.

## Initial control-plane bootstrap limitation

The protected `pull_request_target` gates, `workflow_run` evaluators, and manual
`workflow_dispatch` entrypoints must already exist on GitHub's default branch
before GitHub will execute them as the trusted controller. Consequently this
implementation cannot produce protected PRT evidence for its own pre-merge head,
and the controller-upgrade procedure below cannot authorize the mechanism that
implements that procedure. A stacked branch does not change this boundary.

The first installation therefore requires the repository's current authorized
governance process to land one exact, completely reviewed commit. It is not
permission to reuse an older success, treat candidate-controlled checks as App
contexts, disable a gate, or add a temporary `pull_request` control path. If that
existing authority is unavailable, initial installation remains a blocker.
Immediately after installation, dispatch Build and Packaged E2E against the exact
current default-branch HEAD, then exercise both protected PRT gates and the App
bridge on a fresh same-repository PR before selecting the stable contexts in
branch protection. Any repair uses the now-installed controller-upgrade process.
The one exception is repairing the evaluator while it refuses every PR, as it
did from the schema-2 `preparing` enrollment (`466426b`) until its trusted-gate
projection landed, and, because it never matched a real gate run, until gate
runs were matched by their PR head and controller reference. No controller
upgrade can be admitted then, so that repair
lands as one exact, completely reviewed commit through the same governance
process and limits as the first installation, followed by the same fresh-PR
exercise of both protected gates before any stable context is relied on.

PR #7 owns the canonical foundation on which this implementation was prepared;
PR #8 is an independent release-line port. Its existing release-base PR shape is
a one-time foundation under bootstrap authority, not precedent for steady-state
release contributions. Resolve artifact storage and obtain newest exact-head
Build and Packaged E2E success for every head before the current authorized
bootstrap process lands either foundation. Land or incorporate the reviewed
control plane only on top of the exact PR #7 state, while PR #8 may land
independently. After both lines and the controller are present, perform the
deliberate 1.21.1 exact-candidate reconciliation below and then resume normal
two-parent synchronization. That reconciliation is not an ordinary release PR.
A changed head invalidates the preceding evidence and must be rerun.

## Controller-upgrade procedure

Ordinary PRs cannot change their own protected evaluator. After this mechanism
already exists on the protected default branch, use this exact procedure for a
deliberate controller, workflow, E2E-oracle, visual-review, or shared security-test
upgrade:

1. Start from the exact current `master` and push a same-repository branch named
   `controller-upgrade/<purpose>`. Target only `master` and add the exact
   `controller-upgrade` label.
2. Keep the diff inside the controller roots enforced by
   `scripts/ci/pr_gate.py`: protected workflows/actions and CODEOWNERS, shared
   build/controller/E2E/evidence/visual/site/test code, Markdown documentation,
   and only the matrix-selected loaders' `build.gradle` or `src/e2e` trees.
   While the `master` matrix is schema 2 in `preparing` mode, ordinary and
   controller-upgrade evaluation take the PR's branch identity, the selected
   loaders and the parity-protected loader paths from its trusted-gate
   projection: the branch policy plus the two legacy lanes `fabric-1.20.1` and
   `forge-1.20.1`. The expected job graphs come from the full document's
   default `legacy` scope, the same two lanes. Both therefore equal the
   schema-1 gate's. An already protected `shared` integration matrix projects
   every target, requires the full E2E graph and protects all active loaders;
   incomplete inventories, release-role schema 2 and malformed matrices fail
   closed. This controller upgrade does not authorize a matrix transition.
   The loader-bootstrap contract still
   binds every configured loader, NeoForge included, so an ordinary change to
   NeoForge's `build.gradle` or `src/e2e` must match that contract, which the
   schema-1 gate did not require.
   Product `src/main` code, unknown paths, submodules, symlinks,
   executable-mode additions, forbidden `.gradle`/`buildSrc` trees, the release
   matrix, dependency-verification metadata, and version shims are rejected.
3. Let the exact synthetic merge run Build and Packaged E2E. The protected old
   evaluator still requires its own exact job graphs and one immutable exact-run
   artifact from each successful newest attempt; candidate YAML cannot redefine
   that expected graph.
4. After reviewing the complete current-head diff, the repository owner
   `AkaNebur` posts one plain, single-line issue comment (no Markdown fence):
   `/controller-upgrade approve <40-character-lowercase-head-sha>`. A new commit
   always needs a new SHA-bound approval. The latest owner command for that exact
   head wins; `/controller-upgrade revoke <sha>`, editing the approval, or
   deleting it invalidates publication and wakes the protected handler.
5. Merge only while both ordinary stable contexts are current successes on that
   exact source head. The `pr-gate` environment writer first re-reads the open PR,
   current default/base/head, exact synthetic merge parents/tree, and approval
   digest with its read-only `GITHUB_TOKEN`. Only then does it mint the separate
   statuses-only App token. It checks out protected `master` code only and never
   candidate code; an authorization change is published as failure on the same
   immutable head.

`issue_comment` events are locators, not authority: the protected default-branch
handler ignores payload claims and re-queries GitHub. It never executes
candidate code; the underlying Build/E2E runs use their separately protected
`pull_request_target` controllers and disposable account. Repeated
create/edit/delete deliveries and Build/E2E wakes are serialized by PR and are
harmless. This procedure cannot bootstrap
itself after required contexts are enforced: install it before selecting those
contexts during the initial repository bootstrap, or use the repository's
existing authorized governance process without weakening an established gate.

### Restricted shim admission

The deployed controller also accepts `restricted-transition/<purpose>` branches
targeting the exact current `master`, without the `controller-upgrade` label.
The `vanilla-shim` scope admits only a modification of the existing
`common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java`. Extra paths and deletion
fail closed. This route retains
the base-owned matrix, job graphs and both mandatory exact-run gate artifacts.
Its foundation must deploy through the controller-upgrade procedure first;
a candidate controller cannot authorize its own transition.

Put one strict JSON declaration between these exact PR-body markers:

```text
<!-- blockpops-restricted-transition:start -->
{"schema_version":1,"controller_generation":1,"controller_sha":"<master-sha>","base_sha":"<master-sha>","head_sha":"<candidate-sha>","scope":"vanilla-shim","paths":["common/src/e2e/java/com/theplumteam/e2e/VanillaShim.java"]}
<!-- blockpops-restricted-transition:end -->
```

The parser exposes `RestrictedTransition.digest`, the SHA-256 of its canonical
declaration. After reviewing the exact diff, `AkaNebur` posts the single line
`/restricted-transition approve 1 <head-sha> <declaration-digest>`.
The latest owner decision wins; `revoke`, an edited/deleted approval, a new head,
base or declaration invalidates publication. The protected evaluator and writer
independently reread that decision before publishing the two fixed App contexts.
Merge only with both newest exact gates and App contexts successful. A new base
requires a new declaration and fresh approval, even if the candidate is unchanged.

### Shared matrix admission

The independently deployed controller also admits the `matrix` scope on
`restricted-transition/*`, using the same generation-1 declaration and owner
command, with `paths` exactly `["release/release-matrix.json"]`.
`shared_activation_matrix` derives the entire expected candidate from the exact
protected base. The base must be a complete `preparing` integration inventory.
Only `migration.mode=shared`, empty `legacy_nodes`, and the former legacy lanes'
Stonecutter layout/task/output paths may change. Versions, dependencies, installers,
source routing, reference and every other field remain identical. Activation is
one-shot; arbitrary matrix edits and every other restricted scope fail closed.
The required E2E graph is the full protected target set, never the legacy subset.
Build independently validates the complete scoped report and staged archives.
No matrix payload may include controller, shim or product changes.

### Loader-bootstrap transitions

Loader-bootstrap bytes have an additional two-generation constraint. The old
protected evaluator always checks a controller candidate's loader files against
the contract in its base commit. Therefore loader bytes and the contract that
first authorizes them must not change in the same PR. At that protected boundary
the order is contract policy first, loader bytes second. However, Build and
Packaged E2E also self-validate the candidate's own contract, so a naive PR that
simply replaces today's digest with a future digest will correctly fail. A real
content transition must use two controller upgrades:

1. PR A upgrades the contract schema/validator to a bounded reviewed
   `current`+`next` form, retains the exact current loader digest, and adds the
   one reviewed future digest. Loader files remain byte-identical. The old
   evaluator accepts the unchanged files under its base contract; candidate
   Build/E2E accept the same files through `current`.
2. After PR A merges, PR B changes only the applicable loader bootstrap bytes to
   exact `next` and collapses the contract back to the one new current digest.
   The now-protected transitional validator accepts `next`, while candidate
   Build/E2E accept the collapsed new contract. Both PRs need independent
   exact-head owner approval and both deterministic gates. Let each final shared
   state synchronize and attest on every enrolled release branch; never bypass
   the candidate bootstrap probe or leave a permanent dual-digest window.

The current schema is single-digest, so such a future migration must implement
and test the bounded transitional schema in PR A; the runbook is not permission
to pre-authorize unmatched bytes with the current parser.

### mod-base kit bumps

Every mod-base reference (`pages.yml`, `notify-pages.yml`, `on-demand-e2e.yml`,
`build-gate.yml`, `visual-review.yml`, `visual-review-drain.yml`) carries one
`@<40-hex> # vX.Y.Z` pin under protected roots, so a kit bump is always a
controller upgrade:

```sh
git switch --create controller-upgrade/mod-base-vX.Y.Z origin/master
python3 scripts/ci/mod_base_kit.py bump --to vX.Y.Z
python3 scripts/ci/mod_base_kit.py verify --network
python3 scripts/ci/mod_base_kit.py run template check --repo .
```

`bump` refuses a tag that does not peel to a commit reachable from mod-base
`main`, then rewrites every pin and resynchronizes the managed files
(`pages.yml`'s managed region, `scripts/ci/mod_base_kit.py` and
`docs/ai/shared/*.md`); never edit those by hand. Open the pull request with the
`controller-upgrade` label and approve its exact head as above. The protected
Build gate's identity job runs the same `verify --network` against the
candidate's pin, and its build job stages the new kit for the candidate sandbox
only after those release checks pass.

The adoption itself is gated by the previous `build-gate.yml`, which stages no
kit: its tests fetch the pin anonymously inside the sandbox, and the owner runs
`python3 scripts/ci/mod_base_kit.py verify --network` locally on its exact head
and records the output in the pull request before approving it. The root files a
controller upgrade cannot change (`AGENTS.md`, `.gitattributes`, `.gitignore`,
`.github/dependabot.yml`, `.github/pull_request_template.md`) stayed in
`site/mod-base.json` `template.deferred` until the ordinary follow-up pull
request (#11) added them; a later controller upgrade emptied the list, so
`template check` now requires all of them strictly.

Until that follow-up adds the managed `.gitattributes` (`text eol=lf` for every
managed path), a clone with `core.autocrlf=true` checks the managed files out
with CRLF line endings, and `template check` reports them ("has CRLF line
endings"). Run `git config core.autocrlf input` in that clone and check the
managed files out again, or run
`python3 scripts/ci/mod_base_kit.py run template sync --write --repo .`, which
rewrites them with LF. Once `.gitattributes` has landed, delete and check out
those paths once more.

### mod-base Build adapter

From mod-base v1.1.1 (v1.1.0 failed its canary and is never pinned) the kit can run Build and Packaged E2E itself, in its own
sandbox, through a protected Build adapter: `scripts/ci/mod-base-build.json`
names the dispatcher `scripts/ci/mod_base_build_dispatch.py`, the native glue
`scripts/ci/mod_base_build_adapter.py`, the policy suite
`scripts/ci/mod_base_build_policy.py` and every file the protected hooks import,
each with its SHA-256. `site/mod-base-build-activation.json` says how far the kit
runs: `disabled` runs nothing; `shadow` runs the kit's managed callers beside the
native gates, which stay authoritative, and publishes their results as
`Trusted PR / Build and verify (shadow)` and `Trusted PR / Packaged E2E gate
(shadow)`, which no ruleset requires. Only the `publish` job of the managed
`mod-base-gate-status.yml` writes them, as commit statuses, with the dedicated
gate status App `plum-mod-base-gate` (its one permission is "Commit statuses:
Read and write"); its client ID and private key live only in the environment
`mod-base-gate`, whose deployment branch is `master` alone. The App that writes
the required `Trusted PR / ...` contexts is not used for them.

The kit admits a change of the manifest only at an unchanged pin, so adoption is
three controller upgrades, each merged before the next starts: the kit bump
alone (`bump --to vX.Y.Z`, no Build config and no manifest); then, at that pin,
the Build config, the adapter and the manifest in `disabled`; then `shadow`.
Every pull request that adds, changes or removes the manifest runs, before the
owner approves its head:

```bash
git worktree add --detach ../base origin/master
python3 scripts/ci/mod_base_kit.py run template activation --repo .
python3 scripts/ci/mod_base_kit.py run template sync --repo . --write
python3 scripts/ci/mod_base_kit.py run template check --repo .
python3 scripts/ci/mod_base_kit.py run template transition --repo . --base ../base
```

`template transition` exits 2 when the pin changed too, or when the mode skips
a step. No workflow runs it; record its output in the pull request.

The adapter adds no policy: one kit target per Minecraft version runs
`build_matrix.py --artifact-node` and the lane-scoped `verify_release.py` for
each of its lanes; one kit lane runs the native packaged scenarios of one
artifact node with its `pr-anchors` row; the protected hooks call the native
verifiers on the sealed files. Any change to a file the config lists changes
its hash: run `python3 scripts/ci/mod_base_build_config.py --write` in an LF
checkout and commit the config with the change, or the kit refuses it. `tests/test_mod_base_build_adapter.py` pins the plan to the
native matrix and the hashes to the files.

## Bootstrap a release branch

Matrix discovery cannot safely infer a legacy release from its name. Bootstrap
`1.21.1-neoforge-fabric` once with a deliberate branch-specific matrix and the
shared controller/runtime implementation. Its matrix must identify that exact
branch, Fabric+NeoForge 1.21.1, and Java 21. Only then enable synchronization and
governance for it. PRs #7 and #8 are independent ports rather than an ancestry
chain. After both exact-head gates pass and both foundations land through the
current authorized bootstrap process, the first canonical-to-release
synchronization is expected to stop on real product-code conflicts.

For that one conflict reconciliation, do not open an ordinary PR into the
release branch. Under review, construct one exact merge commit with the exact
current release HEAD as first parent and exact current `master` HEAD as second
parent. Resolve only the intended product conflicts, preserve the target matrix
byte-for-byte and the 1.21.1 NeoForge/version-specific implementation, and keep
all protected controller paths in canonical parity. Derive the canonical
24-lowercase-hex branch token with the protected
`scripts/ci/gate_controller.py branch-token` policy for the exact release branch,
then push the candidate to `automation/release-sync/<token>`; if that rolling ref
already exists, replace only its expected exact value with force-with-lease.

Wake the protected synchronizer after the push. It re-reads current source and
target heads, accepts the candidate only if its ordered parents, tree, retained
matrix, loader-bootstrap policy, and controller parity are still exact, then
reuses the head, opens or updates the generated release-sync PR, dispatches Build
and Packaged E2E, and lets the protected handler merge and attest only the tested
head. If either long-lived head moved, discard and reconstruct the candidate.
Never merge it directly, invent ordinary-release PRT contexts, or broaden the
conflict allowlist to force this bootstrap.

## Recovery runbook

- **A gate failed:** inspect the newest exact-head run. Fix the root cause and
  dispatch a newer run. Never bless an older success or post a manual success
  context.
- **A gate is stale/pending:** reconcile the PR. The controller must keep it
  unmerged until the newest run attempt settles; a canceled notification can be
  recovered by the scheduled/manual reconciler.
- **The target head moved:** discard/update the automation candidate from the
  new exact base. Do not force the old tree through.
- **A protected conflict appeared:** leave synchronization blocked and use the
  exact two-parent candidate procedure above. The protected synchronizer, not an
  ordinary release PR or a direct push, must open, gate, merge, and attest the
  resulting release-sync PR. Do not add a broad conflict exception.
- **A protected controller/test must change:** use the separately authenticated
  controller-upgrade procedure above. Ordinary candidate workflow
  success cannot authorize changes to its own evaluator, tests, prompts, or
  policy. Never disable required contexts as an ad-hoc upgrade path.
- **A loader bootstrap changed:** update the matrix-driven build implementation
  first, then deliberately review the affected digest in
  `e2e/loader-bootstrap-contract.json`. Never snapshot or bless a digest from an
  untrusted release candidate. Missing, extra, executable, or differently
  bound harness files must remain a hard failure.
- **Dependency verification rejected a Loom-generated module:** confirm its
  coordinate matches one of the five exact generated-name exceptions and that
  every remote repository still rejects that namespace. Do not append the
  observed archive hash: Loom's locally generated ZIP is host-dependent. Any
  other coordinate remains a hard checksum failure and must be investigated.
- **Post-merge attestation failed:** freeze further synchronization, compare the
  final parent order/tree/matrix to the tested candidate, and revert or repair
  through a gated PR.
- **Visual queue stopped:** inspect the normalized 30-day owner-action report.
  Authentication, configuration, unknown provider output, and exhausted
  attempts intentionally retain the data-only queue without automatic paid
  retries. Fix the external condition, delete only the exact authenticated
  blocking report ID, and dispatch `visual-review-queue-wake`.
- **Claude returned 429/capacity/network failure:** keep the queue and its
  authenticated cooldown marker. The drain honors the greater of 30 minutes
  and bounded `Retry-After`; never delete the marker to force an early retry.
- **Visual artifact cleanup failed:** re-authenticate artifact name, owner run,
  attempt, digest, size, and exact numeric ID, then delete that ID only. A 404
  is idempotent success. Never rotate by a name wildcard. The failed run does
  not self-dispatch without a durable marker/report or successful cleanup; use
  the scheduled recovery after the underlying Actions API condition clears.
- **Pages promotion failed:** the prior site and `mb-cache--` caches remain;
  no partial generation is deployed. Read the `Publish / Admit publication`
  reason in the run summary (the kit's
  [admission reasons](https://github.com/The-Plum-Team/mod-base/blob/main/docs/OPERATIONS.md#publication-admission)).
  After fixing the cause, publish from the default branch with
  `gh workflow run pages.yml --ref master -f operation=manual`; if no current
  evidence exists, first dispatch `gh workflow run on-demand-e2e.yml --ref master`.
  Rotation is always a separate, separately locked run that accepts only a
  `completed/success` Pages owner and deletes superseded `mb-*` artifacts by
  exact ID; retry it with
  `gh workflow run pages.yml --ref master -f operation=rotate -f run_id=<pages-run-id> -f sha=<its-head-sha>`.
  `gh workflow disable pages.yml` stops publication while keeping the deployed
  site online.
- **mod-base kit unavailable:** the Build gate fails closed at one of three
  points. An unreleased, impostor or inconsistent candidate pin fails the
  `Resolve authoritative build matrix` job at "Verify the candidate's mod-base
  pin (controller-side)" with `mod_base_kit: error: pin <sha> is not reachable
  from The-Plum-Team/mod-base main …`, `… is not a commit of …`,
  `tag vX.Y.Z does not exist …` or `tag vX.Y.Z peels to …, not the pin …`, and
  the build job never starts. A controller whose own kit tree differs from its
  pin fails "Verify the controller-pinned mod-base kit" with exit status 78
  (controller skew). The same `setup` check guards the advisory visual-review
  curator and the drain's `prepare`, `admit` and `publish` jobs, whose
  reauthentication reads the kit's anchor grammar; a skew there fails only that
  advisory run. A released pin that cannot be fetched, copied or matched to
  its staged-file lock fails "Stage the controller-verified kit for the
  candidate sandbox" with `mod-base kit unavailable for candidate pin <sha>`.
  Never edit the staged kit, add an unpinned override, or skip the tests that
  need it: pin a released kit through a controller upgrade, and rerun a fetch
  failure once GitHub recovers. A local cache entry that fails verification can
  be deleted
  (`rm -rf ~/Library/Caches/mod-base/<sha>` on macOS,
  `${XDG_CACHE_HOME:-~/.cache}/mod-base/<sha>` elsewhere) and is fetched again.
- **A mod-base boundary check fails:** "Refuse importable entries at the
  candidate root (controller-side)" prints each root entry Python could import
  ahead of protected code (`mod_base_boundary: repository root candidate:
  json.py: a root file with an importable suffix`); move that code under a
  protected package instead of the root. "Check the staged kit's evidence
  composite (controller-side)" names each `prepare-evidence` step that runs
  Python before unsetting a credential; never pin a kit release that fails it.
  It exits 2 when the staged kit does not verify or carries no `actions/`
  bound by `staged_actions.sha256`: a kit older than mod-base v0.9.2 has none,
  so pin back only to v0.9.2 or later. A candidate test that fails with
  `ModuleNotFoundError: No module named 'PIL'` inside an adapter hook or the
  conformance run means a kit older than v1.0.2, which hides the sandbox's
  user-site Pillow from its isolated children; pin v1.0.2 or later.
- **Actions artifact quota is exhausted:** Build/E2E may finish compilation and
  staging but cannot cross the immutable-artifact boundary, so the gate must
  remain failed and packaged scenarios must not be represented as tested. In
  repository/account billing, remove only obsolete artifacts whose IDs and
  owners are understood or raise the Actions storage budget, then wait for
  GitHub's quota recalculation and rerun the exact failed head. The retired
  `pages-*` and `visual-anchor-v1-*` artifacts are never read again and expire
  on their own. Never disable uploads, evidence fan-in, the lossless anchor, or
  retention checks to get a green status.
- **The Claude Code token may be exposed:** delete the `CLAUDE_CODE_OAUTH_TOKEN`
  secret of the `visual-review` environment, revoke the token from the owner's
  Claude account, delete the single-use handoff by authenticated ID and audit
  the environment job, then store a fresh `claude setup-token` token before
  waking the queue. Deterministic gates remain valid because they never receive
  the token or model access.
