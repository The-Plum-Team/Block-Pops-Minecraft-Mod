# Repository configuration and incident recovery

## Owner configuration required

The deterministic implementation is secretless, but repository governance
requires owner configuration:

- Protect `master` with strict required checks `Build and verify` and
  `Packaged E2E gate`, required pull requests, no force pushes/deletions, and no
  bypass actors.
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
- Add the model credential named by the tracked provider adapter (initially
  `CLAUDE_CODE_OAUTH_TOKEN`) only if advisory AI review is desired. It must be
  exposed only to the fresh review job, never build, curation, cleanup, Pages,
  or synchronization.

At the time this system was implemented the repository was private, all three
remote branches reported unprotected, and the ruleset/protection APIs returned
HTTP 403 with an upgrade requirement. GitHub Pro (or making the repository
public) is therefore required before deterministic checks can be institutionally
authoritative against direct pushes. Do not represent a green workflow as
branch governance until protection is visible through the API.

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
