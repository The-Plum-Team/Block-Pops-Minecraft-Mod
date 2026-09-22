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
   preflight, plus `id-token: write`; it has no checkout, package installation,
   image decoder, GitHub write permission, static Anthropic key, Claude Code
   subscription token, or tools. Only after the current controller, source,
   tested tree, admitted artifact and newest eligible current-or-historical
   baseline are rebound does it
   exchange a GitHub OIDC JWT for a short-lived Anthropic token.
6. A new credentialless, GitHub-read-only publication job reauthenticates every
   mutable identity again, independently validates schema, coverage, routes,
   usage, cost and exact
   client/prompt hashes, then publishes normalized JSON, escaped Markdown, and
   a bounded data-only `provenance.json`. The provenance projection retains the
   exact source/tested tree, contract/matrix and capsule/handoff/review digests,
   per-pair identities, dimensions, image/pixel hashes and integer triage for
   30 days after the images and queue are deleted. Provider envelopes, raw
   transcripts, JWTs and bearer tokens are never artifacts. Exact protected,
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
dimension-incompatible or contract-skewed evidence is rejected before WIF can
be used. Images and all model-visible text are untrusted data, never
instructions.

## Deterministic triage and model routing

Canonical RGB PNG bytes are compared first. A byte-identical pair is accepted
deterministically and never sent to Anthropic. Integer pixel metrics rank
changed pairs only; no similarity threshold can declare a semantic pass.

Changed pairs are ordered with key captures first. Requests are kept below a
28 MiB application limit, beneath the Claude Messages 32 MiB limit. Sonnet
chunks contain at most five pairs because each 1600x900 pair contributes two
lossless images; Fable chunks contain at most four. The current matrix produces
ten pairs (two loaders by five semantic captures), so even full escalation is
bounded to five logical calls. A capsule with more than ten pairs, or whose
byte-driven worst-case partition exceeds five calls, fails before any provider
call and requires an explicit cost-envelope review.

- `claude-sonnet-5` classifies each changed pair as clean, anomalous, or
  uncertain. Its request explicitly uses the supported `high` effort with
  thinking disabled; lowering effort requires a remote calibration sweep so a
  cost optimization cannot silently increase false negatives.
- Only anomalous or uncertain pairs reach `claude-fable-5` for an independent
  semantic verdict. Fable uses its required adaptive thinking mode at `high`
  effort.
- Structured output is constrained by JSON Schema and then validated again by
  protected code. A refusal, truncation, missing pair, extra pair, duplicate,
  unknown enum, incoherent verdict or unbounded text cannot become a report.
- Harmless antialiasing, particles, lighting, font rasterization, animation or
  world-background variation is not a regression by itself. Blur, clipping,
  missing/unexpected widgets, bad layout, incorrect text/state, unintended
  transparency and material rendering corruption are review targets.

The client spaces calls by at least 15 seconds, allows at most one retry for a
retryable transport/HTTP failure or malformed provider envelope/schema, honors
bounded `Retry-After`, refreshes short-lived identity material during long runs,
and stops after 35 minutes. Authentication, billing, configuration, policy and
explicit refusal/truncation failures are not retried as provider requests. No
provider request is retried indefinitely. A structured `stop_details` refusal is
terminal even if Messages pairs it with `stop_reason: end_turn`. Queue processing
allows at most two drain attempts for an exact source run/attempt/tested SHA.

Cost telemetry contains only model IDs, exact protected-code/prompt digests,
route counts, bounded token counts, request IDs, duration, retries, and a
conservative standard-price upper bound. Every request pins
`service_tier: standard_only` and `inference_geo: global`; the response must
confirm standard/global routing before its usage can become telemetry. Fable's
`output_tokens` already includes billed adaptive-thinking tokens, while
`output_tokens_details.thinking_tokens` is independently bounded and never
added a second time. If a retryable provider sub-schema is invalid but its
authoritative token totals are valid, both the rejected attempt and its single
retry are included in telemetry. As of 2026-08-11 the standard prices used by
validation are $3/$15 per million Sonnet input/output tokens and $10/$50 per
million Fable
input/output tokens. Anthropic's temporary promotional pricing is intentionally
not used for the upper bound. A normal clean review is expected to remain
roughly $0.09-$0.15; full escalation is expected to remain roughly $0.45-$1.00.
Workspace spend/rate limits remain the owner's hard external ceiling.

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

The Anthropic workspace is a separate retention boundary. Fable 5 is a Covered
Model and requires Anthropic's 30-day provider-side retention; it is not
eligible for zero data retention. The owner must explicitly accept and enable
that policy on the dedicated visual-review workspace before activating Fable.
If private screenshots require ZDR, leave the AI environment unconfigured and
change the reviewed model/routing contract in a separate security and cost
review; do not silently fall back. Sonnet/structured Messages are ZDR-eligible
when the organization's agreement and selected model permit it.

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
invalidates the older queue before OIDC is minted, preventing duplicate paid
review of superseded attempt evidence. The selector emits an authenticated
terminal `stale_source` state so exact-ID cleanup can remove that queue item
without creating an attempt marker, handoff, OIDC token, or provider request. A
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

Create a dedicated Anthropic workspace and a developer service account, then a
GitHub Actions workload-identity federation rule with:

- issuer `https://token.actions.githubusercontent.com` using discovery JWKS;
- audience `https://api.anthropic.com`;
- exact subject `repo:The-Plum-Team/BlockPops:environment:visual-review`;
- claims restricted to repository `The-Plum-Team/BlockPops`, owner `The-Plum-Team`, ref
  `refs/heads/master`, and workflow ref
  `The-Plum-Team/BlockPops/.github/workflows/visual-review-drain.yml@refs/heads/master`;
- target service account and dedicated workspace;
- OAuth scope `workspace:inference`, not `workspace:developer` or `org:admin`;
- token lifetime approximately 600 seconds;
- conservative workspace spend and rate limits;
- `global` in the workspace's allowed inference geographies, because every
  request explicitly pins `inference_geo: global` and fails closed otherwise;
- explicit 30-day retention on this dedicated workspace for Fable 5.

Create the GitHub environment `visual-review`, restrict it to protected
`master`, and set these environment variables (they are resource identifiers,
not provider credentials):

- `ANTHROPIC_FEDERATION_RULE_ID`
- `ANTHROPIC_ORGANIZATION_ID`
- `ANTHROPIC_SERVICE_ACCOUNT_ID`
- `ANTHROPIC_WORKSPACE_ID`

Do not add `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`,
`ANTHROPIC_OAUTH_ACCESS_TOKEN`, or `CLAUDE_CODE_OAUTH_TOKEN`; the client fails
closed if one is present. Claude Pro/Max/Team subscription usage and Claude Code
OAuth are not used by this system. With all byte-identical pairs, review remains
fully secretless. Changed pairs without valid WIF configuration retain the
queue and publish a 30-day owner-action report without weakening deterministic
gates or making a provider call.

Official implementation references:

- [Anthropic model IDs and versioning](https://platform.claude.com/docs/en/about-claude/models/model-ids-and-versions)
- [Claude Fable 5 capabilities, pricing, refusal behavior, and retention](https://platform.claude.com/docs/en/about-claude/models/introducing-claude-fable-5-and-claude-mythos-5)
- [Claude API and model-specific data retention](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention)
- [Claude Sonnet 5 behavior and pricing](https://platform.claude.com/docs/en/about-claude/models/whats-new-sonnet-5)
- [Claude vision limits](https://platform.claude.com/docs/en/build-with-claude/vision)
- [Structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)
- [Effort and thinking compatibility](https://platform.claude.com/docs/en/build-with-claude/effort)
- [Messages response, usage, and refusal fields](https://platform.claude.com/docs/en/api/messages)
- [Service tiers](https://platform.claude.com/docs/en/api/service-tiers)
- [Inference geography and data residency](https://platform.claude.com/docs/en/manage-claude/data-residency)
- [Claude API errors and retries](https://platform.claude.com/docs/en/api/errors)
- [Claude rate limits](https://platform.claude.com/docs/en/api/rate-limits)
- [GitHub Actions WIF for Claude](https://platform.claude.com/docs/en/manage-claude/wif-providers/github-actions)
- [WIF scopes](https://platform.claude.com/docs/en/manage-claude/wif-reference)
- [GitHub Actions OIDC security](https://docs.github.com/en/actions/concepts/security/openid-connect)
- [GitHub Actions concurrency](https://docs.github.com/en/actions/concepts/workflows-and-actions/concurrency)

## Recovery and extension

An owner-action report deliberately blocks automatic reselection while retaining
the queue. Correct the WIF/workspace/provider problem, delete only the exact
authenticated report ID, and dispatch `visual-review-queue-wake`. For a
rate-limit marker, wait for its authenticated cooldown; do not delete it to
force an early paid retry. For stale candidate or baseline evidence, let the
protected cleanup delete the queue and rerun Packaged E2E at the current head.

When adding a Minecraft lane or capture, update only the branch matrix or
scenario contract as appropriate, preserve semantic `capture_id` stability,
add deterministic probe calibration canaries, and deliberately re-evaluate the
ten-pair/five-call budget. Do not raise image, queue, retry or cost
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
