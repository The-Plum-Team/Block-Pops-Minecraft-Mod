# Repository configuration and incident recovery

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
  merge parents/tree, unchanged control plane, newest run attempts, and exact
  job graphs. Configure both required contexts with this App as their expected
  source. Bootstrap in that order: install/configure the App and environment,
  let one current PR produce both contexts, then select those existing contexts
  and that exact App as the expected source in branch protection.
- An organization/enterprise may instead enforce Build and Packaged E2E as
  required-workflow rules from a protected repository/ref. GitHub does not
  expose that rule at the level of this user-owned repository, so it is not the
  documented bootstrap path here.
- Protect every matrix-enrolled release branch with strict bridge contexts
  `Release sync / Build and verify` and
  `Release sync / Packaged E2E gate` plus the same no-bypass rules.
- Keep default `GITHUB_TOKEN` permissions read-only. Enable **Allow GitHub
  Actions to create and approve pull requests** for the narrowly permissioned
  synchronization jobs, or install a narrow App/token instead.
- A synchronization App needs Metadata read; Contents read/write; Pull requests
  read/write; Actions read/write; and Commit statuses read/write. Repository
  administration is separate and is needed only to reconcile rulesets.
- Set Pages source to GitHub Actions. Create the `github-pages` environment and
  limit deployment to protected `master`.
- Create a protected `visual-review` environment if advisory AI review is
  desired. Store only `OPENAI_API_KEY` as its secret and set
  `OPENAI_VISUAL_MODEL` as its environment variable. Restrict the environment
  to protected `master`; optional required reviewers may add a human approval.
  The credential is exposed only to the fresh review job, never Build, E2E,
  curation, normalization, cleanup, Pages, or synchronization. The fixed
  client uses the Responses API with storage disabled and no tools.

At the time this system was implemented the repository was private, all three
remote branches reported unprotected, and the ruleset/protection APIs returned
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
disallows Actions-created PRs; either enable **Allow GitHub Actions to create
and approve pull requests** or install the narrow synchronization App before
expecting automated release PR creation.

## Bootstrap a release branch

Matrix discovery cannot safely infer a legacy release from its name. Bootstrap
`1.21.1-neoforge-fabric` once with a deliberate branch-specific matrix and the
shared controller/runtime implementation. Its matrix must identify that exact
branch, Fabric+NeoForge 1.21.1, and Java 21. Only then enable synchronization and
governance for it.

## Recovery runbook

- **A gate failed:** inspect the newest exact-head run. Fix the root cause and
  dispatch a newer run. Never bless an older success or post a manual success
  context.
- **A gate is stale/pending:** reconcile the PR. The controller must keep it
  unmerged until the newest run attempt settles; a canceled notification can be
  recovered by the scheduled/manual reconciler.
- **The target head moved:** discard/update the automation candidate from the
  new exact base. Do not force the old tree through.
- **A protected conflict appeared:** leave synchronization blocked and open a
  reviewed branch-specific port. Do not add a broad conflict exception.
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
- **Visual capsule cleanup failed:** re-authenticate artifact name, owner run,
  digest, and exact numeric ID, then delete that ID only. Never rotate by a name
  wildcard.
- **Pages promotion failed:** retain the prior rolling cache/site. Rotation is
  allowed only after a successful atomic deployment of all current heads.
- **AI credential exposure is suspected:** revoke it immediately, delete the
  single-use handoff by authenticated ID, audit the fresh review job, and rerun
  curation with a new credential. Deterministic gates remain valid because they
  never receive the model secret.
