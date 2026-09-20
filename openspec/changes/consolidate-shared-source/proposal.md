# Proposal — Consolidate twelve lanes onto shared sources

## Decision and intent

Consolidate BlockPops development onto the repository-configured canonical branch, `master`, using a central release matrix, Stonecutter-managed shared sources, and narrow compatibility overlays. Preserve all twelve owner-selected Minecraft/loader combinations and independent releases per lane. Reduce duplicated fixes and branch synchronization work without weakening production, packaged-E2E, or release protections.

This is a planning proposal, not delivery or publishing authorization. The owner selected all twelve lanes after inventory and authorized unpublished local migration implementation plus conventional work-unit commits on the existing feature branch; those decisions are resolved. Local readiness does not authorize push, PR creation, merge, publication, or release. See [exploration.md](exploration.md) for historical-ref evidence and its limits, and [../../config.yaml](../../config.yaml) for session/project policy.

## Product scope and evidence

| Minecraft | Target loaders | Starting evidence |
|---|---|---|
| 1.20.1 | Fabric, Forge | Core implementation and separate packaged-E2E architecture |
| 1.21.1 | Fabric, NeoForge | Core implementation and separate packaged-E2E architecture |
| 1.21.4 | Fabric, NeoForge | Configuration/source; qualification required |
| 1.21.5 | Fabric, NeoForge | Configuration/source; qualification required |
| 1.21.6 | Fabric, NeoForge | Configuration/source; qualification required; historical beta NeoForge |
| 1.21.7 | Fabric, NeoForge | Configuration/source; qualification required; historical beta NeoForge |

All twelve are migration targets, not claims of passing builds, supported runtime completeness, or released artifacts. The eight later lanes must be qualified as part of this change. Failed or unavailable qualification remains explicit and blocks completion for that lane; it does not justify silently dropping it. Publication is unverified for every lane.

Maintainers should make a shared gameplay fix once, keep necessary loader/version adaptations reviewable, and select a lane for release without forcing unrelated lanes to publish. Players should retain existing gameplay, data compatibility, and loader-appropriate artifacts; this change introduces no intended gameplay feature or data-format migration.

## Current-state gap

The checked-in matrix currently describes only 1.20.1 Fabric/Forge. Other implementation and configuration live on historical refs. Current build/policy assumptions select one Minecraft/Java era and one FML loader family per tree. Branch-based synchronization protects shared changes today but does not provide the selected twelve-lane shared-source architecture.

The migration must preserve those protections while replacing branch-local assumptions. Existing source/configuration is evidence to reconcile, not a ready-made twelve-lane implementation to copy wholesale.

## In scope

| Layer | Proposed outcome |
|---|---|
| Central matrix | One authoritative definition of the twelve lane identities, source routing, dependency/toolchain choices, production and harness tasks/paths, runtime/CI coverage, and lane-specific release identity/versioning. Derived consumers must reject missing, duplicate, or inconsistent lanes. |
| Shared sources | Stonecutter-managed version nodes reuse canonical production and harness sources. Keep loader/version overlays narrow, explicit, and limited to demonstrated incompatibilities. |
| Build isolation | Isolate Forge and NeoForge dependency/repository contexts per applicable lane rather than globally enabling both in one legacy context. Preserve strict checksum and repository-origin protections. |
| Serialized execution | Execute Gradle lane builds serially with no concurrent lane Gradle processes or parallel Gradle execution. Preserve separate Gradle JVM, artifact, and runtime toolchain requirements by era. |
| Artifact boundary | Produce a production JAR and a physically separate packaged-E2E harness JAR for every lane. Prevent E2E code/resources from entering production artifacts or publication selections; retain explicit artifact provenance. |
| Matrix-derived CI/E2E | Derive build, harness, runtime, and CI rows from the same matrix. Exercise real packaged production artifacts with their matching harnesses and deterministic scenario assertions. Keep advisory visual review separate from authoritative gates. |
| Independent releases | Allow a qualified lane to be selected and versioned/released independently, without requiring unrelated lane publication. Missing evidence for a selected release lane fails closed; a release selection is not authority to omit other migration targets. |
| Controlled transition | Keep historical refs and the existing release-sync workflow while the replacement is qualified. Update relevant documentation and tooling contracts before any authorized cutover. |

Quick-Skin-Mod is a read-only architectural reference for these concepts. Do not copy its lane count, unrelated domain code, or advanced orchestration machinery. The design must choose the smallest BlockPops-specific structure that satisfies these outcomes.

## Non-goals and preservation rules

- No new Minecraft versions or loader combinations beyond the twelve selected lanes.
- No gameplay redesign, unrelated feature work, mass dependency refresh, or wholesale rewrite of E2E/security controllers.
- No branch deletion, historical-ref rewriting, commits, pushes, PR creation, publication, or default-branch change in this phase.
- No rename from `master` to `main`. The selected delivery strategy label `stacked-to-main` describes the chain shape, not a literal branch name.
- Do not remove `.github/workflows/sync-release-branches.yml` until the replacement has validated equivalent protections and every target lane's build/E2E path.
- Preserve existing user `.gitignore` and `.pi` changes. Leave sibling Quick-Skin-Mod untouched.

## Affected areas

| Area | Expected later change |
|---|---|
| `release/release-matrix.json`, matrix validators and projections | Expand the model to twelve lane definitions and independent release ownership without duplicate inventories. |
| `settings.gradle`, `build.gradle`, loader build scripts, Gradle conventions | Introduce Stonecutter integration, lane/toolchain isolation, and serialized aggregate execution. Exact layout belongs in design. |
| `common`, `fabric`, `forge`, NeoForge sources and version overlays | Reconcile historical implementations into shared code plus bounded compatibility differences. |
| `gradle/e2e-harness-conventions.gradle`, `scripts/release/`, `e2e/` | Preserve separate artifact packaging, provenance, scenario/bootstrap contracts, and matrix-derived runtime selection. |
| `.github/workflows/`, `tests/`, contributor/operations documentation | Adapt CI and qualification coverage while retaining trusted gates and documenting migration/release controls. |

These are anticipated implementation surfaces. The user's existing authorization permits bounded unpublished local implementation and tests; this proposal does not authorize delivery or widen any slice beyond its task-defined scope.

## Safety and delivery gates

Authenticated read-only evidence now reports private repository `AkaNebur/BlockPops`, viewer permission `ADMIN`, default branch `master`, and exact live head `35c72713ccdb94205df4bc5cbafcd5264dd64377`. The earlier “Repository not found” / HTTP 404 was resolved by switching the active `gh` account. The branch endpoint reports `protected: false` and embedded status enforcement off, but branch-protection and ruleset endpoints return HTTP 403 upgrade-required; complete absence of governance rules is therefore not proven. The exact untruncated tree at that SHA contains none of `.github/*`, `scripts/ci/pr_gate.py`, `CONTRIBUTING.md`, or `docs/operations.md`. Workflow-list metadata names Build gate and Packaged E2E, but does not prove deployed default-branch YAML.

The strict controller and policy at feature commit `a200152` are proposed candidate foundation, not live authority. They cannot approve their own initial deployment. AUTH-1 therefore gates initial foundation delivery and later published restricted-path admission—not authorized unpublished local development. Before foundation delivery, require fresh governance observation and explicit human authority for the exact reviewed foundation commit and action; after deployment, independently verify it active before any controller-governed transition. Preserve candidate trust protections, checksum verification, repository isolation, loader-bootstrap contract review, and exact-head semantics. Do not infer an automatic direct-push/PR route, disable protection, or create a reusable bypass.

Plan layered, reviewable slices of at most 400 changed lines, including relevant tests and documentation. Auto-chain is selected with `stacked-to-main` recorded in project delivery planning; integration remains `master`. Split further where needed rather than manufacturing a size exception. No chain branches or PRs are created by this proposal.

Suggested dependency order, not an assertion that each layer fits a single slice:

1. Matrix/schema contracts and qualification inventory.
2. Serialized build planning and isolated Stonecutter nodes.
3. Shared-source reconciliation and narrow overlays, grouped into small lane/compatibility slices.
4. Production/harness separation and matrix-derived CI/E2E integration.
5. Independent release selection, full qualification, and separately authorized transition.

Local implementation may proceed task by task now that proposal, specifications, design, and tasks exist; task 1 remains pending for delivery/foundation proof rather than local source editing. TDD policy is unconfirmed: use proportional automated checks on runnable surfaces and executable packaged-E2E acceptance for runtime behavior, without asserting strict-TDD consent.

## Risks and mitigations

| Risk | Mitigation / gate |
|---|---|
| Eight later lanes may have incomplete ports or incompatible/beta dependencies | Qualify each explicitly; keep failures visible and require owner review for any target-scope change. |
| Shared sources conceal era/loader behavior differences | Preserve gameplay and scenario invariants; use narrow overlays with lane-specific acceptance evidence. |
| Forge/NeoForge or Java-era contamination | Isolate dependency contexts and toolchains; validate deterministic serial build plans. |
| Harness leakage or mismatched artifacts | Inspect production and harness archives and provenance per lane; exclude harnesses from release artifacts. |
| Twelve-lane runtime cost and serialized build latency | Use matrix-derived, explicit execution plans; optimize reuse only without skipping required qualification or relaxing serialization. |
| Security/release protections lost during transition | Retain sync workflow and trusted gates until equivalent replacement validation and authorized cutover. |
| Existing baseline failures obscure regressions | Preserve baseline evidence, separate environmental blockers from new failures, and never report an unexecuted check as passing. |

## Rollback

Before cutover, keep the current synchronization workflow and historical refs available; qualify the replacement without publishing from it. If a slice fails validation, stop advancement and restore affected files through a reviewed revert under the authorized delivery route, without resetting refs or altering user work. Preserve lane-specific failure evidence.

If transition fails, stop new release dispatch and return to the last validated release/build path only after verifying its protections and artifacts still match the intended source. Do not overwrite published artifacts or rewrite release history. Sync retirement is a separate gated step after all twelve lane paths and equivalent protections validate, not cleanup performed in this phase.

## Success criteria

- [ ] The central matrix enumerates exactly the twelve selected combinations; build, artifact, release-selection, CI, and E2E consumers derive lane identity from it without divergent lists.
- [ ] Canonical sources and narrow overlays implement the selected lanes without routine per-branch duplication of shared fixes.
- [ ] Every lane passes matrix validation, serialized production/harness builds, artifact-boundary checks, and applicable deterministic packaged-E2E scenarios on compatible toolchains; unresolved lanes prevent declaring the migration complete.
- [ ] Each lane has distinct production and harness JARs with matching provenance; production archives and release selections exclude harness content.
- [ ] Release planning demonstrates independent selection/versioning without publishing unrelated lanes; publishing itself remains separately authorized.
- [ ] Forge/NeoForge isolation, Java-era requirements, dependency verification, and trusted deterministic gates remain effective.
- [ ] Historical refs and user changes are preserved; the old synchronization workflow remains until the replacement is fully validated and its retirement authorized.
- [ ] Fresh remote/governance facts and explicit delivery permissions are verified before foundation delivery or cutover, without treating API-limited governance evidence as a local-implementation blocker.

## Checks and next step

Proposal inputs were read directly from OpenSpec and current repository standards/matrix. No separate `research.md` exists in this change directory; no additional broad research was performed. No tests or Gradle commands were rerun, and no source/workflow files were changed.

Inherited baseline only: Python discovered 136 tests with 2 failures and 38 errors; Gradle failed before task configuration with `Unsupported class file major version 69`. These results are not evidence against or verification of a not-yet-implemented migration.

**Next local task:** task 2 may record the JDK/toolchain environment with no tracked edits, or task 3 may start with a schema-2 test-only subunit in `tests/test_release_matrix_portability.py` plus fixtures, leaving the live matrix unchanged. Proposed verification: run the focused portability test and `python scripts/release/matrix.py --matrix release/release-matrix.json`. Do not deliver or publish either result under this planning document.
