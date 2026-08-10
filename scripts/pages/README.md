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
workflow/run/attempt, repository, branch head, commit tree, matrix blob, hashes,
schemas, lane coverage, capture IDs, decoded pixels, and comparisons before it
creates WebP derivatives.

One complete gallery is deployed atomically. Its compact source caches retain no
PNG, logs, runtime directory, or AI output. After deployment, a fresh controller
authenticates the owning Pages run as `completed/success`, rechecks current heads
and compact manifests, and deletes superseded caches, consumed raw handoffs, and
transient deploy artifacts only by immutable artifact ID. A failed validation,
deployment, or rotation preserves the prior site/cache generation.

Repository-owner activation after these files reach the protected default branch:

1. In **Settings → Pages**, select **GitHub Actions** as the publishing source.
   Private-repository Pages also requires a GitHub plan that supports private
   Pages; otherwise the secretless build remains testable but deployment will not
   start.
2. Restrict the `github-pages` environment to the protected default branch. Do
   not permit release or pull-request branches to deploy it.
3. Keep default workflow permissions read-only. The workflow grants `pages:write`
   and `id-token:write` only to deployment, and `actions:write` only to the
   post-success rotation dispatch/controller.
4. Permit 90-day Actions artifact retention so one compact rolling cache can be
   kept per enrolled branch. Raw handoffs and promotion artifacts use one day.

For a protected manual refresh, send a repository dispatch rather than running a
workflow from a selectable ref:

```sh
gh api --method POST repos/AkaNebur/BlockPops/dispatches \
  -f event_type=pages-deploy
```

This intentionally keeps every credential-bearing controller on the default
branch implementation.

Pages and AI review are advisory. Required Build and Packaged E2E status checks
remain the only deterministic release gates.
