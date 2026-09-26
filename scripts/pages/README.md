# Public packaged-E2E evidence

BlockPops publishes its advisory packaged-E2E gallery with the pinned
[mod-base](https://github.com/The-Plum-Team/mod-base) kit
([ADR 0001](../../docs/architecture/decisions/0001-adopt-mod-base-public-evidence.md)). The kit
owns selection, authentication, compaction, rendering, deployment checks, cache refresh and
exact-ID rotation; its shared rules are in
[`docs/ai/shared/PUBLIC-EVIDENCE.md`](../../docs/ai/shared/PUBLIC-EVIDENCE.md) and its runbook in
the kit's [operations guide](https://github.com/The-Plum-Team/mod-base/blob/main/docs/OPERATIONS.md).
The authoritative release inventory remains each branch's `release/release-matrix.json`; Pages
does not own a second version or loader list.

## What stays in this directory

- `mod_base_adapter.py` is the BlockPops adapter. Its hooks derive each key's expectation from the
  matrix and `e2e/scenario-contract.json`, read the validated packaged aggregate
  (`profiles/<node>--<mc>--<scenario>/result.json` and its PNGs, with every passed assertion
  message as the frame's runtime evidence), return the exact Packaged E2E job graph from
  `scripts/ci/e2e_job_graph.py`, recompute the aggregate scope of a tested run, enroll branches
  from their matrices (read only as inert Git objects), and select the canonical
  `visual_reference.artifact_node` lanes for the lossless anchor. The kit re-verifies every hook
  result; the adapter never uploads, deletes or deploys anything. It imports only protected
  modules, from the repository root that `adapter.python_path` puts on the hook child's path; the
  Build gate refuses any importable entry at that root (`scripts/ci/mod_base_boundary.py`).
- `mod_base_fixtures.py` synthesizes BlockPops-shaped packaged output for
  `mod_base_kit.py run conformance`; no privileged job loads it.
- `requirements.txt` is the hash-locked Pillow wheel for the Python gates, kept in lockstep with
  the kit's lock and `e2e/requirements.txt`.

`site/mod-base.json` configures the kit: project copy, the opaque branch-token keys of the
enrolled branches, the Packaged E2E source policy (display title `Packaged E2E / <commit>`, the
exact attestation job, the exact job graph and the newest-run rule), 1600x900 sources, the
eight-day successor grace of superseded anchors, and the root files still deferred to the
template follow-up.

## Flow

1. The Packaged E2E `public-evidence` job (scheduled or dispatched on the default branch, or a
   release attestation) revalidates the exact aggregate and runs the kit's `prepare-evidence` step,
   which uploads the one-day `mb-handoff--<branch-token>--a<attempt>` and, for a direct canonical
   run, the lossless `mb-anchor--<branch-token>--<commit>--<run>--a<attempt>`.
2. `.github/workflows/notify-pages.yml` authenticates the completed Packaged E2E run and dispatches
   `pages.yml` with `operation=deploy`, holding only `actions: write`. A run on another branch, one
   whose latest attempt is not a completed success (a re-run started before the notifier executed),
   or one that uploaded no `mb-handoff--` in its latest attempt leaves the previous site unchanged
   without a dispatch; a later attempt's own completion wakes Pages again.
3. The managed caller `pages.yml` ("Project site") binds the pinned kit, admits, collects, builds
   the atomic site, deploys it from its own `deploy` job, refreshes the 90-day
   `mb-cache--<branch-token>--<commit>` and requests a separately locked rotation.

A failed validation, deployment, refresh or rotation preserves the previous site and caches.

## Owner activation

After the adoption reaches the protected default branch:

1. In **Settings → Pages**, keep **GitHub Actions** as the publishing source.
   Private-repository Pages also requires a GitHub plan that supports private
   Pages; otherwise the secretless build remains testable but deployment will not
   start.
2. Keep the `github-pages` environment restricted to the protected default branch.
3. Keep default workflow permissions read-only. Within the Pages path, only the caller's `deploy`
   job holds `pages: write` and `id-token: write`, and only `notify-pages.yml`,
   `request-rotation` and the rotation run hold `actions: write`.
4. Permit 90-day Actions artifact retention for the caches and the lossless anchor.

Publish or rotate on demand from the default branch:

```sh
gh workflow run pages.yml --ref master -f operation=manual
gh workflow run pages.yml --ref master -f operation=rotate -f run_id=<pages-run-id> -f sha=<its-head-sha>
```

Pages and AI review are advisory. Required Build and Packaged E2E status checks remain the only
deterministic release gates.
