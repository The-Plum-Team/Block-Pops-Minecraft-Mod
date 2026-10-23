# Project and release contract

This file is part of the repository-wide instruction set imported by `AGENTS.md`. Together, the
documents listed there are the source of truth for coding agents working anywhere below the
repository root. The two shared documents imported first (`docs/ai/shared/REPOSITORY.md` and
`docs/ai/shared/PUBLIC-EVIDENCE.md`) are managed by mod-base and hold the rules every mod shares;
this file and the other local imports hold only what is specific to BlockPops.

`AGENTS.md` itself is a root file that a controller upgrade cannot add, so it landed in an ordinary
follow-up pull request (#11). `site/mod-base.json` no longer defers it or any other template file.

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
  adoption of mod-base public evidence,
  [ADR 0002](../architecture/decisions/0002-load-custom-collections-at-runtime.md) runtime Custom
  collections,
  [ADR 0003](../architecture/decisions/0003-accept-operator-uploaded-world-collections.md)
  operator-uploaded World collections and
  [ADR 0004](../architecture/decisions/0004-extend-world-collections-for-the-redesigned-creator.md)
  their extensions for the redesigned creator.
- `site/mod-base.json` and `scripts/pages/mod_base_adapter.py` connect this repository to the
  mod-base public-evidence kit; `scripts/ci/mod_base_kit.py` finds the pinned kit.

## What BlockPops is

BlockPops is an Architectury Minecraft mod for collectible figures, animated blocks and
interactive claw-machine gameplay. Each branch's `release/release-matrix.json` is the single
authoritative inventory of its Minecraft versions, loaders, Java toolchains, artifacts, packaged
lanes, source routing and canonical visual reference. Never duplicate that inventory in a workflow,
script or document; derive every consumer from the matrix. Release branch names are opaque.

In 26.x, special item renderers read `GeoRendererContext` on their first draw, after block-entity
renderer registration, rather than during model baking. From 1.21.4, favorite-color previews
select a dedicated item model with the 1.20.1 camera and scale, centered within the button;
earlier previews retain their 3D transform. The modern model bakes its preview scale and the
widget cancels it in screen space, allowing the deferred GUI to render at full resolution
instead of enlarging a tiny atlas thumbnail. Box item extents include every cube corner so
rotated GUI bounds cannot clip the model. The 26.x preview retains the legacy GUI-facing
rotation and lift. Background quads explicitly select the version’s textured
shader rather than inheriting the program bound by a preceding GUI fill. Finish queued base
fills before immediate background drawing on the earlier GUI path.

A collection screen shows each figure or plush in colour once discovered and in black and white
before. Through 1.21.1 it draws the model straight into the screen. From 1.21.4 it draws an item
bound to `FigurePreviewItemRenderer`, whose `figure_preview` model bakes that same camera and a
fourfold scale the widget cancels, because an item is the one way a model of this mod reaches
the deferred interface of 1.21.6 and later. The preview entity stands on the box block: 1.21.1
refuses a block entity on a block its type does not list.

A drawn figure or plush comes out holographic one time in 25 (`figure.Holographic`; never in the
packaged E2E). The `Holographic` flag travels in the block-entity data of the box, the figure
taken out of it, the plush and every item they drop; tooltips, the draw result card and an
announcement with its own sound show it. `HolographicRenderTypes` draws it with a shader on
every lane, by era: 1.20.1 and 1.21.1 swap in the shader instance each loader registers, 1.21.4
a shader program, and 1.21.5 onward a copy of vanilla's entity cutout pipeline, whose render
type vanilla builds only through a factory reached by reflection. The shaders follow the GLSL
eras too: `core/rendertype_holographic` (1.20.1 to 1.21.5), `_ubo` (1.21.6 to 1.21.8), `_330`
(26.1, 26.2) and `_layout` (26.3); all but the first import `include/holographic.glsl`, whose
effect the first repeats. Every lane ships every variant, and 1.21.2 to 26.2 resolve the
`#moj_import`s of all of them when resources load, failing the whole reload on a missing one: a
variant imports only vanilla includes that 1.21.2 already has and declares the rest itself. The
effect follows the view, never the clock.

`common/build.gradle` derives special-item definitions and base-model transforms for Minecraft
1.21.4 onward with `scripts/release/item_resources.py`, from the authored entity models. Older
lanes retain their original resources; generated modern resources are never authored or committed.
A box shows its flat front in a slot on every lane, as the authored 1.20.1 model does, so the
script gives each derived box base model a GUI display in place of the shulker template's corner
view. The renderer adds the half turn and one-pixel lift in code until 26.x, where a special
renderer is no longer told its display context and the display carries them instead.
The same script derives `data/blockpops/recipe/` for Minecraft 1.21 onward from the authored
1.20.1 `recipes/`: 1.21 renamed the directory and the result field, 1.21.2 reads ingredients as
bare ids, and 1.21.9 renamed `minecraft:chain` to `minecraft:iron_chain`. Author a recipe only in
`recipes/`, as a plain shaped recipe.

First-person boxes on the legacy entity-renderer path use the modern shulker template's
40% scale and mirrored 315-degree rotation. The claw machine's authored item model owns
its first-person 90-degree rotation, one-third scale and vertical pivot compensation,
preserving the legacy held appearance across both immediate drawing and 26.x submission.
It owns the GUI view on every lane as well: a side-lit perspective with the machine's front
forward, sized and centred to fill the slot as the gacha machine's item does. The immediate
renderer applies its other context transforms only outside first person and the GUI.

On POSIX, packaged Forge-family servers launch the installed JVM argument files directly so
shutdown waits for the JVM to finish writing logs before evidence export, without a shell parent.

On Windows, `gradle/build-conventions.gradle` starts every Loom run with
`-XX:StackShadowPages=20`, the value other platforms default to. With HotSpot's 8-page Windows
default, JDK 25 call-site resolution outgrows the committed stack of a worker thread and the
development game dies with an access violation, leaving no crash report.

Custom collections ([ADR 0002](../architecture/decisions/0002-load-custom-collections-at-runtime.md))
are one of the two paths by which the game loads content that does not ship with it; World
collections are the other. That content is untrusted, and only `com.theplumteam.remote` fetches
Custom content: from the single fixed CDN origin, by allowlisted per-collection paths in the
`blockpops` namespace, under the limits in `RemoteLimits`, kept only once its manifest SHA-256
matches, and parsed with `StrictJson`. For Custom content, do not add another origin, a URL from a
server, packet or file, or a path outside `RemotePaths`. A Custom definition passes
`CustomCollectionCheck` on both sides before registration. Its assets are served only by the
runtime registries in `client.runtime`, which the figure models consult before GeckoLib, and are
never written into GeckoLib's caches. They travel only in `SyncCustomCollectionsPacket`, never in
the shared collection sync. CI never contacts the CDN.

World collections
([ADR 0003](../architecture/decisions/0003-accept-operator-uploaded-world-collections.md)) are
authored in game by operators at level 2, which the server re-checks for every packet. Their files
come only from that operator's upload in `WorldUpload*Packet`s or from a datapack's
`blockpops_world_collections/` directory; never from a URL, the CDN or a path a server names.
`com.theplumteam.worldcontent` validates every file (`PngValidator`, `GeoJsonCheck`,
`AnimationJsonCheck`, `StrictJson`) and every definition (`WorldCollectionCheck`) on the server and
again on the client, under the byte quotas and decoded budgets in `WorldContentLimits`. The server
alone applies the per-player rate limits in `server.worldcollections.WorldRateLimits` and the
upload concurrency caps and timeouts in `WorldUploads`. They are stored content-addressed under
`<world>/data/blockpops-world-collections/` and travel only in `SyncWorldCollectionsPacket` and
`WorldAssetChunkPacket`, never in the shared collection sync, the Custom packet or
`blockpops-cache/`. Their ids are `w-<slug>`, and the shared sync refuses them on both sides.
Their assets are served only by the runtime registries in `client.runtime` under ids `WorldContentIds`
owns, are never written into GeckoLib's caches, are refused when a bundled resource has the same
id, and are left alone by `RuntimeAssets.clear()`. Since
[ADR 0004](../architecture/decisions/0004-extend-world-collections-for-the-redesigned-creator.md)
a record may carry optional keys (draft, author link, logo placement, plush, rarity, texture-only
skin variants, box face), each left out while it holds its default so older records keep their
bytes; the store stays at format 1, and client and server must run the same BlockPops version. A
draft is synced only to operators, whose status the server polls, and is offered by no machine. The
author link is display-only: `http` or `https`, printable ASCII, never fetched by the game, and
handed to the player's browser only through the game's own link handling and its confirmation
screen. A plush World collection is listed in the gacha machine and handed out as figure blocks,
since plush blocks are registered at start-up; a holographic one carries the flag in that figure
block's data. Every operator request, from a batch of edits to a
duplicate, a purge or a datapack import started in the editor, is an action of
`WorldCollectionEditPacket` answered by `WorldCollectionStatusPacket`; an editor import reads only
the server's own datapacks by exact name, as the command does.

The creator (`client.gui.creator`) opens only when `SettingsScreen.canOpenCreator()` allows it: a
development environment, an operator, and World collections synced. Its entry points are the
machine screen's **Creator** button, the Settings **Creator** tab and the client command
`/blockpopscreator`, which is registered only in a development environment. Packaged E2E players
run production code and are not operators, so no entry point may bypass that check. The creator
previews files that are not sent yet: it decodes them under the same content-addressed ids a synced
copy gets, and materializes the draft into a client-only overlay of the collection registry under
the reserved id `creator_preview.<slot>`. That overlay answers only on the client thread, appears
in no collection list, and is cleared when the creator closes; nothing of a preview is sent or
stored. "Use my skin" contacts no network: it reads the player's own skin from the game's local
skin cache (`skins/<first two>/<name>` under the assets directory, where the name is the SHA-1 of
the texture hash in the player's own textures property), only as a regular file of at most 64 KiB
and never through a link. The bytes pass the same 64×64 PNG checks as a picked skin, and the
default skin resource is used otherwise. "Download box template" writes two bundled images to
`blockpops/templates/` in the game folder and opens that folder; it takes no path from the user.

## Trust model

- **Protected controller, untrusted candidate.** Build and Packaged E2E run from the protected
  default branch on `pull_request_target`. They check out the candidate only as data and run all of
  its code (tests, Gradle, Minecraft) inside `scripts/ci/untrusted_runner.py`'s disposable,
  credentialless account, which is killed and locked before any artifact credential exists.
  Sealing derives Stonecutter node-local `build` and `.gradle` output roots from the authenticated
  matrix Git blob. It walks their exact parent directories and still rejects undeclared versions,
  node-local sources, linked roots and every change to tracked source bytes.
- **Governance.** Branch protection on `master` requires the App-sourced contexts
  `Trusted PR / Build and verify` and `Trusted PR / Packaged E2E gate`. When
  `site/mod-base-build-activation.json` on `master` hands a gate to the mod-base kit
  (`shared-build`: Build; `shared-build-and-e2e`: both), the kit's gate status App writes that context and
  `scripts/ci/pr_gate.py` reports it `delegated`, never writing it; see
  [`docs/operations.md`](../operations.md#shared-build-shared-build). Protected paths
  (`scripts/ci/gate_controller.py` `PROTECTED_PATHS`) change only through the SHA-approved
  controller-upgrade procedure in [`docs/operations.md`](../operations.md). In
  `shared-build-and-e2e` the kit's admission accepts `/controller-upgrade approve <head sha>` from
  any current member of `The-Plum-Team` and from the outside collaborator `Nanarng`
  (`admission.approvers` in `scripts/ci/mod-base-build.json`); `/restricted-transition` and the
  dormant native `scripts/ci/pr_gate.py` stay `AkaNebur`-only, and a pin rollback below mod-base v1.6.0 reverts
  that entry first. The evaluator
  constants in `scripts/ci/gate_controller.py`, `scripts/ci/pr_gate.py` and
  `scripts/ci/e2e_job_graph.py` are never edited to admit a pull request.
- **Restricted admission.** The generation-1 controller accepts the existing `VanillaShim.java`
  or a complete base-derived preparing-to-shared matrix activation on `restricted-transition/*`.
  Each scope has its own exact base/head declaration and fresh digest-bound owner decision.
  Both gates remain mandatory; all other scopes are inert. See the [shim](../operations.md#restricted-shim-admission)
  and [matrix](../operations.md#shared-matrix-admission) procedures.
- **Exact job graph.** `scripts/ci/e2e_job_graph.py` fixes the Packaged E2E job set that the gate,
  release attestation, visual review and public evidence authenticate. Change steps inside a job,
  never the set of jobs, unless the graph itself is deliberately upgraded.
- **Public evidence is advisory.** GitHub Pages and AI visual review never replace the
  deterministic gates.
- **Matrix scope.** A schema-2 integration matrix in `preparing` uses the trusted gate's legacy
  projection. A protected `shared` matrix requires every configured target and its full E2E graph,
  including parity for all active loader controllers. Matrix mutation remains separately restricted;
  changing the projection alone activates no lanes. Enrolled release targets remain schema 1.

## Public evidence through mod-base

The Pages pipeline is the pinned mod-base kit; the shared rules are in
`docs/ai/shared/PUBLIC-EVIDENCE.md`. BlockPops specifics:

- **One pin, six workflows plus the Build/E2E callers.** `pages.yml` (the managed caller), `notify-pages.yml`,
  `on-demand-e2e.yml`, `build-gate.yml`, `visual-review.yml` and `visual-review-drain.yml`
  reference the kit at one `@<40-hex> # vX.Y.Z` pin, and so do the kit's managed
  `mod-base-build.yml`, `mod-base-gate-status.yml` and `mod-base-packaged-e2e.yml` in every mode of
  `site/mod-base-build-activation.json` that manages them (`shadow` manages all three;
  `mod-base-guard.yml` names the pin only as data). All of them, the configuration and the adapter lie under protected
  roots, so every kit bump is a `controller-upgrade/mod-base-vX.Y.Z` pull request made with
  `python3 scripts/ci/mod_base_kit.py bump --to vX.Y.Z`.
- **Producer.** The Packaged E2E `public-evidence` job keeps its name, conditions and read-only
  permissions; after revalidating the exact aggregate it runs the kit's `prepare-evidence` step,
  which uploads `mb-handoff--<branch-token>--a<attempt>` and, for a direct canonical run, the
  lossless `mb-anchor--<branch-token>--<commit>--<run>--a<attempt>`. On `master` that is the
  nightly schedule or an operator's dispatch: release synchronization no longer dispatches it for
  each new head, so the gallery of a head waits for the next nightly run
  ([`docs/operations.md`](../operations.md#shared-build-and-packaged-e2e-shared-build-and-e2e)).
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
  roots. They were in `template.deferred` until the ordinary follow-up pull request (#11) added them;
  the list is now empty, so `template check` requires them strictly.

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
