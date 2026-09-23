# Advisory visual review

BlockPops treats visual review as a second, advisory interpretation layer over
the deterministic Build and Packaged E2E gates. A model never decides whether
a JAR was built correctly, whether Minecraft launched, whether a production
packet propagated state, or whether a contracted assertion passed.

## Evidence flow

1. `on-demand-e2e.yml` runs every matrix lane with the remapped production JAR
   and the physically separate harness. The protected fan-in validates the
   complete report and screenshot inventory.
2. A successful current-head run on the matrix-owned canonical lane publishes
   `visual-anchor-v1-<branch-token>--<commit>-<run-id>-<run-attempt>` for 90
   days. The producer attempt is part of both the immutable name and manifest,
   so a GitHub “re-run all jobs” creates a distinct anchor instead of silently
   reusing evidence from an older attempt. The anchor contains
   the original lossless PNG bytes, addressed by SHA-256, plus exact
   repository/branch/commit/tree, matrix, contract, run, attempt, dimensions,
   pixel hashes, semantic capture identities, and source-artifact provenance.
   Ordinary raw evidence remains available for one day.
3. `visual-review.yml` runs without an AI credential. Protected code
   authenticates the candidate run, attempt, tested commit/tree, complete job
   graph, aggregate artifact, matrix, contract, and exact `master` anchor. An
   open PR and every non-PR source require the current `master` anchor. A PR
   delivered to `master` may retain only parent zero of its exact two-parent
   tested merge as a historical anchor, after the protected controller proves
   the current branch head is that delivered commit with the same tested tree,
   parents, and source head. It pairs every supported candidate lane 1:1 with
   `master` / `fabric-1.20.1` by `capture_id` and writes a data-only queue item.
4. `visual-review-drain.yml` globally serializes oldest-first selection. A
   post-approval runner with no AI credential reauthenticates the PR/branch,
   tested tree, job graph and baseline, then re-emits one exact handoff.
5. A fresh `visual-review` environment job downloads only that bounded handoff.
   It has read-only GitHub scopes solely for a hash-bound stdlib identity
   preflight; it has no checkout, package manager, image decoder, GitHub write
   permission or OIDC identity. It installs one pinned Claude Code binary, the
   Linux x64 build of `@anthropic-ai/claude-code` 2.1.280, only after its
   registry sha512 integrity matches the value pinned in the workflow. Only
   after the current controller, source, tested tree, admitted artifact and
   newest eligible current-or-historical baseline are rebound does the owner's
   `CLAUDE_CODE_OAUTH_TOKEN` reach the client, which passes it solely to that
   CLI's environment. The model gets the Read tool and nothing else, allowed
   for exactly the images of the chunk it is reviewing.
6. A new credentialless, GitHub-read-only publication job reauthenticates every
   mutable identity again, independently validates schema, coverage, routes,
   usage, cost and exact
   client/prompt hashes, then publishes normalized JSON, escaped Markdown, and
   a bounded data-only `provenance.json`. The provenance projection retains the
   exact source/tested tree, contract/matrix and capsule/handoff/review digests,
   per-pair identities, dimensions, image/pixel hashes and integer triage for
   30 days after the images and queue are deleted. CLI results, raw
   transcripts and the token are never artifacts. Exact protected,
   secret-free prompts exist only inside the authenticated one-day handoff and
   are deleted by numeric artifact ID.
7. A separate fresh comment job has no checkout, package installation, Pillow,
   handoff, or images. It downloads only the normalized report by exact numeric
   artifact ID, validates its API digest and exact four-file JSON/Markdown
   inventory, reconstructs and byte-compares escaped Markdown using an
   independent stdlib validator, and rechecks `master`, the exact source run
   attempt immediately before using `issues: write`. It accepts either the
   still-open exact synthetic merge or a closed-and-merged PR only after proving
   that the current base-branch head delivers the same tested tree and parent
   identity, so reviews that finish just after merge remain visible on the PR.

Missing, stale, mixed, duplicate, traversal-bearing, symlinked, oversized,
dimension-incompatible or contract-skewed evidence is rejected before the token
can be used. Images and all model-visible text are untrusted data, never
instructions.

## Deterministic triage and model routing

Canonical RGB PNG bytes are compared first. A byte-identical pair is accepted
deterministically and never sent to Anthropic. Integer pixel metrics rank
changed pairs only; no similarity threshold can declare a semantic pass.

Changed pairs are ordered with key captures first. Triage chunks contain at
most five pairs and verification chunks at most four, so the model opens at
most ten images per call. The current matrix produces 44 pairs (two loaders by
22 semantic captures: five `ui-regression` screens and seventeen `in-world`
views), within a 48-pair budget whose full escalation is bounded to 22 logical
calls (ceil(48 / 5) triage plus ceil(48 / 4) verification). A byte-identical pair
never reaches the model, and the E2E-only determinism (frozen star scroll and
GeckoLib clocks, seeded claw-machine draws, cleared toasts, no clouds) keeps
unchanged captures byte-identical. A capsule with more than 48 pairs, or whose
worst-case prompt exceeds its byte budget, fails before any model call and
requires an explicit cost-envelope review.

- `claude-opus-5-5` triages each changed pair as clean, anomalous, or
  uncertain.
- Only anomalous or uncertain pairs reach a second, independent
  `claude-opus-5-5` call for a semantic verdict. The report keeps the two
  routes' historical names, `sonnet` and `fable`.
- Opus 5.5 needs Claude Code 2.1.280 or newer; older releases reject it.
- Claude Code constrains the result with JSON Schema (`--json-schema`), and
  protected code validates it again. A failed result, missing pair, extra
  pair, duplicate, unknown enum, incoherent verdict or unbounded text cannot
  become a report.
- Harmless antialiasing, particles, lighting, font rasterization, animation or
  world-background variation is not a regression by itself. Blur, clipping,
  missing/unexpected widgets, bad layout, incorrect text/state, unintended
  transparency and material rendering corruption are review targets.

Each call runs `claude --print` in the read-only capsule with `--safe-mode`,
`--no-session-persistence`, `--tools Read`, one `--allowedTools Read(./images/…)`
entry per chunk image, and `--permission-mode dontAsk`. The prompt arrives on
standard input, and the CLI environment carries only the token, `PATH`, `HOME`
and fixed locale/updater settings.

The client spaces calls by at least 15 seconds, allows at most one retry for a
rate-limited, overloaded, timed-out or malformed call, and stops after 90
minutes. Authentication and configuration failures are not retried. No call is
retried indefinitely. Queue processing allows at most two drain attempts for an
exact source run/attempt/tested SHA.

Telemetry contains only model IDs, exact protected-code/prompt digests, route
counts, bounded token counts, Claude Code session IDs, duration, retries, and
Claude Code's own API-equivalent cost estimate. A subscription is not billed per
call; the estimate only shows what the same review would cost on the API. Usage
counts against the subscription's limits, and a rate or usage limit becomes a
retained queue item with a cooldown.

## Durable queue and retention

Semantic queue identity is
`source_run_id + source_run_attempt + tested_sha`; the immutable input name also
ends in the queue producer's exact `run_attempt`, and the manifest records both
producer run ID and attempt. An Actions artifact name is only an index until its
numeric ID, digest, size, exact owner workflow/run/attempt and embedded manifest
have all been authenticated. Re-runs of one producer coalesce to its newest
authenticated attempt; artifacts from different producer runs for one semantic
identity remain an ambiguity and fail closed.

- data-only queue input: 7 days, at most 96 MiB each, eight queued items and
  256 MiB of relevant live queue state;
- single-use code/prompt handoff and sanitized result: 1 day, deleted by exact
  authenticated ID after processing (404 is idempotent success);
- attempt and retry-cooldown markers: 7 days;
- normalized advisory (`review.json`, escaped Markdown, and data-only
  `provenance.json`) or owner-action report: 30 days;
- original lossless current canonical anchor: 90 days; each authenticated
  superseded anchor remains available for an eight-day grace period after its
  first authenticated successor (the seven-day queue window plus one day);
- ordinary raw runtime evidence: 1 day; compact Pages derivatives: 90 days.

Screenshots sent through Claude Code follow the data-retention terms of the
owner's Claude subscription, not an API workspace policy. If private
screenshots must not leave under those terms, leave the token unset: changed
pairs then produce an owner-action report and no model call.

A globally fixed concurrency group serializes selection, provider use,
publication and cleanup. Duplicate dispatches are harmless. Retryable network,
rate-limit or provider-capacity failures create an authenticated cooldown
marker and retain the queue. A malformed provider envelope/schema receives at
most one bounded in-run retry; if it remains invalid, the queue is retained and
an owner-action report is published. Configuration, authentication and other
nonretryable ambiguous failures do not spend a second provider call. Two failed
drain attempts produce an exhaustion report and retain the input for manual
recovery; no third attempt is scheduled.

The drain dispatches an immediate continuation only after durable state proves
that re-selection will make progress: a credential-attempt marker exists, an
exhaustion report exists, or terminal exact-ID cleanup succeeded. A failed
marker/report upload or failed deletion stops the chain; the twice-hourly
schedule retries it. This fail-closed rule prevents an artifact-quota or cleanup
outage from turning one retained item into an unbounded dispatch loop.

Every selector and both pre-provider reauthentication layers also require the
mutable GitHub run to remain on the queued `source_run_attempt`. A later re-run
invalidates the older queue before the token is used, preventing duplicate
review of superseded attempt evidence. The selector emits an authenticated
terminal `stale_source` state so exact-ID cleanup can remove that queue item
without creating an attempt marker, handoff, or model call. A
packaged workflow controller may be an older commit only when GitHub proves it
is an exact ancestor of both the queue producer and the current protected
`master` controller. Immediately before an advisory issue comment is created or
updated, the isolated writer also compares the mutable source-run identity with
the authenticated historical attempt and refuses a superseded result.

Only an exact same-repository `github-actions[bot]` synchronization candidate
with authenticated two-parent topology/controller parity and a complete diff
containing solely protected CI-test Python or documentation Markdown may skip
AI review. Uncertainty means review. Workflows, matrices, Gradle, product code,
runtime, scenarios, probes, prompts and classifiers always remain visually
impacting. Packaged E2E is never skipped by this classifier.

## Owner configuration

Like Quick Skin, the review runs on the owner's Claude subscription through
Claude Code:

1. On a machine logged in to that subscription, run `claude setup-token` and
   copy the `sk-ant-oat01-…` token it prints.
2. Store it as the secret `CLAUDE_CODE_OAUTH_TOKEN` of the GitHub environment
   `visual-review`, not as a repository secret, so only jobs admitted to that
   environment can read it:
   `gh secret set CLAUDE_CODE_OAUTH_TOKEN --env visual-review --repo The-Plum-Team/Block-Pops-Minecraft-Mod`
3. Restrict the `visual-review` environment's deployment branches to protected
   `master`.

Do not add `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` or
`ANTHROPIC_OAUTH_ACCESS_TOKEN`; Claude Code would prefer them over the
subscription token, so the client fails closed if one is present. With all
byte-identical pairs, review needs no token. Changed pairs without a valid
token retain the queue and publish a 30-day owner-action report without
weakening deterministic gates or making a model call.

The token expires about a year after it is created; renew it the same way.

References:

- [Claude Code headless mode and `--print` output](https://code.claude.com/docs/en/headless)
- [Claude Code CLI flags](https://code.claude.com/docs/en/cli-reference)
- [Claude Code authentication](https://code.claude.com/docs/en/iam)
- [Anthropic model IDs and versioning](https://platform.claude.com/docs/en/about-claude/models/model-ids-and-versions)
- [GitHub Actions environment secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GitHub Actions concurrency](https://docs.github.com/en/actions/concepts/workflows-and-actions/concurrency)

## Recovery and extension

An owner-action report deliberately blocks automatic reselection while retaining
the queue. Correct the token or subscription problem, delete only the exact
authenticated report ID, and dispatch `visual-review-queue-wake`. For a
rate-limit marker, wait for its authenticated cooldown; do not delete it to
force an early paid retry. For stale candidate or baseline evidence, let the
protected cleanup delete the queue and rerun Packaged E2E at the current head.

When adding a Minecraft lane or capture, update only the branch matrix or
scenario contract as appropriate, preserve semantic `capture_id` stability,
add deterministic probe calibration canaries, and deliberately re-evaluate the
48-pair/22-call budget. Do not raise image, queue, retry or cost
limits as a mechanical response to a failure.

BlockPops does not enable AI-generated functional repair. Its two release lines
have loader/version-specific production code and branch-local dependency
metadata, while deterministic repair validation would require a separately
governed patch writer and remote gate cycle. Build/E2E failures therefore
receive deterministic classification and ordinary reviewed fixes. Known
network, runner, Maven/Mojang/GitHub or storage failures do not invoke Claude;
an owner or CI recovery controller reruns the failed exact SHA remotely after
diagnosis. The release gate does not silently auto-retry every such failure, so
this is never authorization to bless an older success.
