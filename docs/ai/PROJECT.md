# Project and release contract

This file is part of the repository-wide instruction set imported by `AGENTS.md`. Together, the
documents listed there are the source of truth for coding agents working anywhere below the
repository root. The two shared documents imported first (`docs/ai/shared/REPOSITORY.md` and
`docs/ai/shared/PUBLIC-EVIDENCE.md`) are managed by mod-base and hold the rules every mod shares;
this file and the other local imports hold only what is specific to BlockPops.

`AGENTS.md` itself is a root file that a controller upgrade cannot add, so it lands in an ordinary
follow-up pull request. Until then `site/mod-base.json` lists it in `template.deferred`; read this
file and the two shared documents directly.

## Documentation map

- `AGENTS.md` is the import-only manifest for the complete coding-agent instruction set. Its first
  two lines are the managed shared documents; the rest are listed in `site/mod-base.json`
  `template.agents_local`.
- `CONTRIBUTING.md` is the human-facing path from a fresh fork to a reviewed pull request.
- `README.md` is for users and builders.
- [`docs/release-architecture.md`](../release-architecture.md) owns the trust zones, branch
  lifecycle and release planning; [`docs/operations.md`](../operations.md) owns owner
  configuration, the controller-upgrade procedure and recovery;
  [`docs/e2e.md`](../e2e.md) owns the packaged scenarios; and
  [`docs/visual-review.md`](../visual-review.md) owns the advisory AI review.
- `docs/architecture/decisions/` records evidence-backed architectural decisions;
  [ADR 0001](../architecture/decisions/0001-adopt-mod-base-public-evidence.md) records the
  adoption of mod-base public evidence.
- `site/mod-base.json` and `scripts/pages/mod_base_adapter.py` connect this repository to the
  mod-base public-evidence kit; `scripts/ci/mod_base_kit.py` finds the pinned kit.

## What BlockPops is

BlockPops is an Architectury Minecraft mod for collectible figures, animated blocks and
interactive claw-machine gameplay. Each branch's `release/release-matrix.json` is the single
authoritative inventory of its Minecraft versions, loaders, Java toolchains, artifacts, packaged
lanes, source routing and canonical visual reference. Never duplicate that inventory in a workflow,
script or document; derive every consumer from the matrix. Release branch names are opaque.

## Trust model

- **Protected controller, untrusted candidate.** Build and Packaged E2E run from the protected
  default branch on `pull_request_target`. They check out the candidate only as data and run all of
  its code (tests, Gradle, Minecraft) inside `scripts/ci/untrusted_runner.py`'s disposable,
  credentialless account, which is killed and locked before any artifact credential exists.
- **Governance.** Branch protection on `master` requires the App-sourced contexts
  `Trusted PR / Build and verify` and `Trusted PR / Packaged E2E gate`. Protected paths
  (`scripts/ci/gate_controller.py` `PROTECTED_PATHS`) change only through the SHA-approved
  controller-upgrade procedure in [`docs/operations.md`](../operations.md). The evaluator
  constants in `scripts/ci/gate_controller.py`, `scripts/ci/pr_gate.py` and
  `scripts/ci/e2e_job_graph.py` are never edited to admit a pull request.
- **Exact job graph.** `scripts/ci/e2e_job_graph.py` fixes the Packaged E2E job set that the gate,
  release attestation, visual review and public evidence authenticate. Change steps inside a job,
  never the set of jobs, unless the graph itself is deliberately upgraded.
- **Public evidence is advisory.** GitHub Pages and AI visual review never replace the
  deterministic gates.

## Public evidence through mod-base

The Pages pipeline is the pinned mod-base kit; the shared rules are in
`docs/ai/shared/PUBLIC-EVIDENCE.md`. BlockPops specifics:

- **One pin, six workflows.** `pages.yml` (the managed caller), `notify-pages.yml`,
  `on-demand-e2e.yml`, `build-gate.yml`, `visual-review.yml` and `visual-review-drain.yml`
  reference the kit at one `@<40-hex> # vX.Y.Z` pin. All of them, the configuration and the adapter lie under protected
  roots, so every kit bump is a `controller-upgrade/mod-base-vX.Y.Z` pull request made with
  `python3 scripts/ci/mod_base_kit.py bump --to vX.Y.Z`.
- **Producer.** The Packaged E2E `public-evidence` job keeps its name, conditions and read-only
  permissions; after revalidating the exact aggregate it runs the kit's `prepare-evidence` step,
  which uploads `mb-handoff--<branch-token>--a<attempt>` and, for a direct canonical run, the
  lossless `mb-anchor--<branch-token>--<commit>--<run>--a<attempt>`.
- **Wake.** `notify-pages.yml` is the only `workflow_run` consumer of Packaged E2E for Pages. It
  has no checkout, authenticates the completed run inline and dispatches `pages.yml` holding only
  `actions: write`; a run on another branch, one whose latest attempt is not a completed success,
  or one that handed off no `mb-handoff--` in its latest attempt succeeds without a dispatch. `pages.yml` itself never uses `workflow_run`, `repository_dispatch` or
  `pull_request_target`.
- **Targets.** Keys are opaque 24-hex branch tokens (`scripts/ci/gate_controller.py branch-token`)
  of the matrix-enrolled branches; the adapter decides enrollment from each branch's matrix, read
  as an inert Git object. Today only `master` enrolls.
- **Kit in the sandbox.** The Build gate's controller verifies its own kit (`setup`), stages the
  kit the candidate pins with `mod_base_kit.py stage`, and copies it into the sandbox at
  `out/mod-base-kit`. Candidate tests reach the kit only through `scripts/ci/mod_base_kit.py`
  (via `tests/mod_base_path.py`); an unavailable kit fails them, never skips them. The identity job
  runs the protected bootstrap's `verify --network` against the candidate's pin and refuses every
  root entry Python could import ahead of protected code, because the adapter's `python_path` is
  the repository root (`scripts/ci/mod_base_boundary.py import-root`); after staging, the build job
  verifies the staged kit, requires its lock-bound `actions/` (mod-base v0.9.2 or later), and
  checks the candidate-pinned `prepare-evidence` composite there (`mod_base_boundary.py
  composite`). Keep Python code out of the repository root and `scripts/__init__.py` absent.
- **Staged adoption.** The root files `AGENTS.md`, `.gitattributes`, `.gitignore`,
  `.github/dependabot.yml` and `.github/pull_request_template.md` are outside the controller-upgrade
  roots. They stay in `template.deferred` until the ordinary follow-up pull request adds them and a
  later controller upgrade empties the list.

## Task routing

| Change scope | Start from | Expected destination |
|---|---|---|
| Product behavior, assets or documentation | `master` | The owning shared source; enrolled release branches receive it only through release synchronization |
| One Minecraft version or loader | `master`, matrix first | Its matrix-selected loader module or source routing |
| Protected workflows, controller, E2E oracle, Pages or tests | `master` | A `controller-upgrade/<purpose>` pull request |
| mod-base kit version | `master` | `controller-upgrade/mod-base-vX.Y.Z` via `mod_base_kit.py bump` |
| Generated output or staged artifacts | Nowhere | Fix the tracked input instead |

## Verification

Run the smallest relevant check while iterating, then the aggregate gate:

```bash
git diff --check
python3 scripts/release/matrix.py
python3 -m compileall -q e2e scripts tests
python3 scripts/ci/parallel_unittest.py -v -t . scripts/ci/tests tests
python3 scripts/ci/dependency_policy.py --metadata gradle/verification-metadata.xml
python3 scripts/ci/mod_base_kit.py verify --network
python3 scripts/ci/mod_base_kit.py run template check --repo .
```

The README lists the Gradle build and release staging commands. Packaged Minecraft scenarios are
described in [`docs/e2e.md`](../e2e.md).
