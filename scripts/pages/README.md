# Public packaged-E2E evidence

This directory implements BlockPops' advisory public evidence surface. The
authoritative release inventory remains each branch's
`release/release-matrix.json`; Pages does not own a second version or loader list.

The Packaged E2E workflow curates a one-day
`pages-e2e-<branch-token>-<run-attempt>` handoff.
The token is the first 24 hexadecimal characters of SHA-256 over the exact UTF-8
branch name, so opaque names and slashes never become artifact paths. The handoff
contains a strict manifest, passing packaged results, and only contracted PNGs.
The protected default-branch Pages controller authenticates the exact source
workflow/run/attempt, repository, published branch head, commit tree, matrix
blob, hashes, schemas, lane coverage, capture IDs, decoded pixels, and
comparisons before it creates WebP derivatives. Build/E2E dispatches execute
from the protected default branch, so a run's GitHub `head_branch/head_sha` is
its controller identity—not the release bytes it tested. Every raw and compact
manifest therefore keeps these identities separately:

- `provenance.branch/commit/tree` is the exact published branch head;
- `provenance.handoff.controller_branch/controller_sha` owns the public handoff;
- `provenance.packaged.branch/commit/tree` is the exact packaged source; and
- `provenance.packaged.controller_branch/controller_sha` owns that source run.

For each enrolled published head, selection searches Packaged E2E runs on the
default controller branch and requires the exact display title
`Packaged E2E / <published-commit>`. Only the newest matching run and attempt is
eligible; pending, cancelled, or failed newer work blocks older evidence. The
historical attempt endpoint, exact job graph, selected artifact ID/digest/owner,
both controller identities, current published tree, and post-merge attestation
job are then reauthenticated after download. A rolling-cache fallback must carry
the same newest handoff provenance; cache age alone never makes it eligible.
Bearer-authenticated API clients ignore ambient proxy settings, use the platform
TLS trust store, and reject redirects. Artifact archive downloads allow only
HTTPS redirects and remove `Authorization` whenever the origin changes.
Direct canonical runs, including the schedule, follow the same title/controller
rules and are the only producers of the durable canonical lossless anchor; for
those runs, published and controller heads must be identical.

One complete gallery is deployed atomically. Selection, promotion, deployment,
and exact-ID rotation share one fixed, non-cancelling concurrency lock, so a new
gallery cannot read an artifact while an older successful generation deletes it.
Its compact source caches retain no PNG, logs, runtime directory, or AI output.
After deployment and cache publication succeed, the same workflow rotates while
still holding that lock. The final job is gated on every upstream success and
binds the exact protected workflow ref, repository, default-branch ref, run ID,
run attempt, and head SHA before accepting its narrowly scoped
`in_progress`/no-conclusion owner. The `pages-rotate` dispatch remains an
idempotent recovery path, but it accepts only an exact historical owner that is
already `completed/success`. Both paths recheck current heads and compact
manifests, and delete superseded caches, consumed raw handoffs, and transient
deploy artifacts only by immutable artifact ID. Raw handoff deletion uses its
controller branch/SHA rather than confusing those fields with the published
branch. The current lossless anchor is always retained. An authenticated
superseded anchor remains for eight days after its first authenticated
successor—the seven-day queue window plus a one-day expiry/cleanup margin. Later
rotations delete it by exact ID, while the platform's 90-day retention ceiling
bounds storage during sustained churn. A failed validation, deployment, cache
publication, or rotation preserves the prior site/cache generation.

Repository-owner activation after these files reach the protected default branch:

1. In **Settings → Pages**, select **GitHub Actions** as the publishing source.
   Private-repository Pages also requires a GitHub plan that supports private
   Pages; otherwise the secretless build remains testable but deployment will not
   start.
2. Restrict the `github-pages` environment to the protected default branch. Do
   not permit release or pull-request branches to deploy it.
3. Keep default workflow permissions read-only. The workflow grants `pages:write`
   and `id-token:write` only to deployment, and `actions:write` only to the
   exact same-run rotation and authenticated historical recovery controller.
4. Permit 90-day Actions artifact retention so one compact rolling cache can be
   kept per enrolled branch. Raw handoffs and promotion artifacts use one day.

For a protected manual refresh, send a repository dispatch rather than running a
workflow from a selectable ref:

```sh
gh api --method POST repos/The-Plum-Team/Block-Pops-Minecraft-Mod/dispatches \
  -f event_type=pages-deploy
```

This intentionally keeps every credential-bearing controller on the default
branch implementation.

Pages and AI review are advisory. Required Build and Packaged E2E status checks
remain the only deterministic release gates.
