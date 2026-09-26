# ADR 0001: Adopt mod-base public evidence

## Status

Accepted.

## Context

BlockPops published its packaged-E2E gallery with its own Pages pipeline: `pages.yml` woken by
`workflow_run` and `repository_dispatch`, the `scripts/pages` curator, authenticator, renderer,
refresher and rotator, a three-file static site, and a canonical lossless visual anchor. Quick
Skin ran a separate, larger implementation of the same idea. Maintaining two privileged pipelines
doubled the security review of the most sensitive workflows either repository owns, and let their
guarantees drift apart.

The Plum Team moved the shared pipeline into one public kit,
[The-Plum-Team/mod-base](https://github.com/The-Plum-Team/mod-base), that each mod runs at a single
pinned commit and connects through a small adapter and configuration. BlockPops' governance shapes
the adoption:

- protected paths (`scripts/ci/gate_controller.py` `PROTECTED_PATHS`) change only through the
  SHA-approved controller-upgrade procedure, whose evaluator admits at most 300 paths inside fixed
  roots (`scripts/ci/pr_gate.py`), and `site/` must never become empty;
- candidate code runs only inside the credentialless sandbox, which receives files solely from the
  checkouts and explicit `--overlay` directories and rejects any undeclared top-level path outside
  its generated roots;
- the Packaged E2E job graph is exact (`scripts/ci/e2e_job_graph.py`), so the adoption may change
  steps inside `public-evidence` but may not add a job.

## Decision

Public evidence is delegated to mod-base.

- **Two-part identity.** Only code fixed by the protected BlockPops commit may validate or render
  public evidence: the adapter (`scripts/pages/mod_base_adapter.py`) and configuration
  (`site/mod-base.json`) at `github.sha`, plus the mod-base commit that `github.sha` pins and that
  is reachable from mod-base `main`. The managed caller's `verify-kit` job binds the executing kit
  commit from the run's `referenced_workflows` before any kit job runs; every manifest and
  `_site/build.json` records both identities.
- **One pin.** Every mod-base reference (`pages.yml`, `notify-pages.yml`, `on-demand-e2e.yml`,
  `build-gate.yml`, `visual-review.yml`, `visual-review-drain.yml`) carries the same
  `@<40-hex> # vX.Y.Z` pin. They all lie under protected roots, so a kit bump is a
  `controller-upgrade/mod-base-vX.Y.Z` pull request; its pin is checked before merge by the
  protected identity job's `mod_base_kit.py verify --network`.
- **Caller-owned deployment.** `pages.yml` is the byte-identical managed caller with an empty
  extension region. `deploy` (the only `pages: write`/`id-token: write` job), `verify-kit` and
  `request-rotation` stay in it; kit YAML never holds those permissions.
- **Wake by dispatch.** Producers wake Pages with `workflow_dispatch` holding only `actions: write`.
  Because Packaged E2E must keep its exact job graph, the new `notify-pages.yml` observes completed
  Packaged E2E runs through `workflow_run` as a signal only: no checkout, the run authenticated
  inline, event data only through `env:`. A run on another branch, one whose latest attempt is not
  a completed success (a re-run started before the notifier executed), or one whose advisory
  `public-evidence` job uploaded no `mb-handoff--` in its latest attempt, is not a wake: the
  notifier succeeds without dispatching, just as the retired publisher left the previous site
  unchanged. A malformed run record or an unreadable artifact listing still fails. A later
  attempt's own completion wakes Pages again, and an hourly schedule recovers anything missed.
- **Producer.** The `public-evidence` job keeps its name, conditions, `continue-on-error` and
  read-only permissions; the kit's `prepare-evidence` step replaces the curator and anchor steps.
- **Kit in the sandbox.** The Build gate's controller verifies its own kit (`setup`), stages the
  kit the candidate pins (`mod_base_kit.py stage`, with release checks for a changed pin) and
  overlays it at `out/mod-base-kit`; tests resolve it only through the managed bootstrap and fail
  when it is unavailable.
- **New generations.** Evidence uses `mb-*` artifacts (`mb-handoff--`, `mb-anchor--`,
  `mb-cache--`) and `mod-base.*` schemas with no converters: old `pages-*` and
  `visual-anchor-v1-*` artifacts are never read or deleted and expire on their own. Readers accept
  schema versions N and N-1; every frame's `runtime_evidence` is mandatory.
- **Staged adoption.** The root files outside the controller-upgrade roots (`AGENTS.md`,
  `.gitattributes`, `.gitignore`, `.github/dependabot.yml`, `.github/pull_request_template.md`) are
  listed in `template.deferred` and arrive in an ordinary follow-up pull request; a later controller
  upgrade empties the list. `.github/CODEOWNERS` cannot be deferred and gains `/AGENTS.md` and
  `/docs/ai/` in the adoption itself.

Retired, with the reasons that make each safe:

- the `workflow_run` and `repository_dispatch` (`pages-deploy`, `pages-rotate`) Pages triggers:
  replaced by `operation=manual` and `operation=rotate` dispatches with the same authentication and
  no event payload in the privileged workflow;
- `implementation_sha` resolved from the live default head: replaced by `github.sha` plus a
  mandatory live-head equality check at admission, collection, rendering and deployment, which is
  strictly stronger;
- in-run `rotate-current` and the single lifecycle lock: rotation is always a separate,
  separately locked run that accepts only a `completed/success` owner, so publication bursts can
  neither starve nor overtake it;
- `nest_lone_download.py`: every artifact is downloaded by immutable id;
- the three-file gallery: the kit's gallery covers the loader filter and capture search, with
  releases as tabs;
- the release matrix embedded in compact bundles: bundles embed an expectation that the publisher
  re-derives from the authenticated matrix blob;
- the monthly recovery schedule: the managed caller runs hourly.

## Consequences

- Changing privileged Pages code takes two protected merges: one to mod-base `main`, then a pin bump
  here. A push to mod-base alone changes nothing in BlockPops.
- The adoption's own pull request is gated by the previous `build-gate.yml`, which stages no kit:
  its tests fetch the pin anonymously inside the sandbox, and the owner runs
  `python3 scripts/ci/mod_base_kit.py verify --network` locally on its head. The first later pull
  request proves the `out/mod-base-kit` overlay.
- From mod-base v0.9.2 the staged overlay also carries the kit's composite actions, bound by
  `src/mod_base/template/staged_actions.sha256` inside the digested `src/`. Right after staging,
  the Build gate verifies the staged bytes controller-side, requires that lock-bound `actions/`,
  and checks that every Python step of the candidate-pinned `prepare-evidence` composite unsets
  every credential first (`scripts/ci/mod_base_boundary.py composite`): a kit bump whose composite
  would hand Python a credential fails before it can merge. The candidate-side test runs the same
  check against whichever kit root it resolves and never skips. A kit or a controller bootstrap
  older than v0.9.2 stages no `actions/`, so neither can pass the Build gate: pin back only to
  v0.9.2 or later.
- `adapter.python_path` is `["."]`: the adapter imports the protected `scripts.*` and `e2e.*`
  packages, so the kit's host puts the repository root on the adapter child's `PYTHONPATH`, as every
  BlockPops controller already puts its checkout root on `sys.path`. The root itself is not a
  protected path, and an entry there would be imported in the privileged jobs before any protected
  code (a `sitecustomize` module at start-up, a `json.py` or `PIL/` in place of the real module).
  The Build gate's identity job therefore refuses, controller-side, every candidate root file with
  an importable suffix, every root symbolic link with a module name, every root regular package
  other than `e2e` and `tests`, and a `scripts/__init__` (`mod_base_boundary.py import-root`);
  `tests/test_mod_base_paths.py` requires every first-party module the adapter reaches to be
  protected. A kit host that exposed only the declared packages would make the root check
  unnecessary.
- The adapter decides enrollment from each listed branch's matrix read through the kit's
  `ctx.read_blob`, which returns exactly the committed blob but not its tree-entry mode. The retired
  discovery's refusal of a matrix entry whose mode is not `100644` therefore has no equivalent until
  the kit exposes the mode; a missing matrix still skips a release branch, and every other
  unreadable matrix fails the whole inventory.
- The `master` ruleset (required App contexts, no bypass actors; see the kit's
  [operations guide](https://github.com/The-Plum-Team/mod-base/blob/main/docs/OPERATIONS.md#owner-activation))
  is what makes the pin, the adapter and the configuration trusted roots. It presupposes an
  evaluator that can pass a current pull request. From the schema-2 `preparing` enrollment
  (`466426b`) until #12 (merge `53ed97d`), `pr_gate.py` could not: controller parity read both
  matrices with `load_matrix_bytes`, which refuses schema 2 as "inventory only", so every pull
  request reported both contexts as failures, and a ruleset applied then would have blocked every
  merge. #12 repaired the evaluator: parity and PR identity now read the schema-2 matrix through
  its trusted-gate projection. The remaining owner steps are to post
  `/controller-upgrade approve <head sha>` on the adoption's exact head, confirm that both
  `Trusted PR` contexts turn green on it, and only then apply the ruleset (P1).
- Until the first Packaged E2E after the merge, the site keeps its previous deployment
  (`awaiting-complete-v1-evidence`) and visual review finds no `mb-anchor--`. Dispatch
  `on-demand-e2e.yml` on `master` right after merging. That run gives every later capsule its
  anchor, but not the adoption's own pull-request capsule. A capsule queued before the merge is
  stale once `master` moves, except for the pull request whose delivered commit is the current
  head: it keeps parent zero of its tested merge as its reference. Right after the merge that is
  the adoption itself, and runs of its parent carry only `visual-anchor-v1-*`. Its drain
  reauthentication therefore fails once (a red `prepare`), and the authentication owner-action
  report stops its reselection until the seven-day queue input expires. This is expected; deleting
  the report only repeats the failure.
- The kill switch is `gh workflow disable pages.yml`: the deployed site stays online and producers
  keep working. Reverting the adoption is itself a controller upgrade; tag `pre-mod-base-gallery`
  keeps the retired pipeline auditable, and the old pipeline needs one fresh E2E dispatch.
