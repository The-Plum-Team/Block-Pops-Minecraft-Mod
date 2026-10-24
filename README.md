# BlockPops

BlockPops is an Architectury Minecraft mod for collectible figures, animated
blocks, and interactive claw-machine gameplay.

`master` owns the canonical source and its complete Minecraft, loader, Java,
dependency, artifact and packaged-runtime inventory in
[`release/release-matrix.json`](release/release-matrix.json). Historical release
branches retain their own branch-local matrices. Derive supported targets from
the relevant matrix rather than maintaining another version list.

The integration matrix's `migration.mode` controls mandatory gate coverage:
`preparing` retains the legacy projection; `shared` verifies every configured
target. The matrix validator and build plan show the selected inventory.

## Datapack loot

The `blockpops:world_players_box` loot table entry rolls a complete World
Players box. See [World Players boxes in loot tables](docs/world-players-loot.md).

## Drawing a figure

Spending a token in the claw or gacha machine's screen keeps the screen open:
the figures the token could give light up one after another, slowing down until
the light stops on the one you got, which is then shown. Click to put it away.
The figure is already in your inventory when the draw starts.

## Settings and figure previews

Interact with a claw machine to open its collection screen, then select the
gear icon (tooltip **Settings**), the rightmost button at the top right. The
Server tab shows the hour of the daily guaranteed-token reset, as reported by
the connected server and displayed in your local time. Regular tokens have
their own cooldown.

Changing the hour requires operator level 2. In a single-player world that
means cheats enabled; otherwise set `guaranteedTokenResetHour` (0-23, UTC) in
`config/blockpops-server.json` while the game or server is stopped. The slider
shows `--:--` and stays locked while the server has not reported its hour, for
example on a server that runs an older BlockPops version.

The collection screen previews every figure in colour once you have discovered
it and in black and white until then. Operators also get a **Cheats** tab with one **Unlock** button
per collection: it marks that collection's figures as discovered and drops
their boxes. It changes your collection progress; it is not a preview mode.

## Special plushes

Some plush collections have special plushes, outlined in purple and counted
apart in the gacha machine's menu (`3/5 | 0/7 figures`). A token gives them
only to supporters. The supporter button at the top right of that menu (the
cup with a heart), or `/blockpops supporter`, opens the shop the supporter
service names. On PayNow the checkout of the monthly supporter subscription is
already made out to your Minecraft account; on Ko-fi a link in the chat then
opens a page where you tie the Ko-fi email you paid with to your name. The
specials unlock about a minute after that.

To tell who is a supporter, the server sends each joining player's name, and
on an online-mode server their UUID, to the service named by
`specialAccessApiUrl` in `config/blockpops-server.json` and keeps the answer
for `specialAccessCacheMinutes`. The supporter button sends the same service
the player's name, and their UUID on an online-mode server, to get the
checkout. A premium player's purchase follows their Mojang account; a
non-premium player's, made on an offline-mode server, follows their name, so
there anyone who joins with that name gets the specials too. Set the URL to
`""` to ask nobody; the special plushes then stay locked for everyone and the
button offers no checkout.

## World collections

World collections are still in progress, so for now they can be made only in a
development environment (a game started from Gradle or an IDE). There,
operators (permission level 2) make collections for one world from their own
skins, box textures, logos and Blockbench models, with
`/blockpops worldcollections` and files imported from a datapack, or with the
in-game creator, which uploads them from your computer and opens from the
**Creator** button of a machine, the **Creator** tab of its Settings or
`/blockpopscreator`. The creator keeps a collection as a draft, out of the machines and
seen only by operators, until it is published; gives figures a rarity and up to
four skin variants; places the logo on the box; makes plush collections for the
gacha machine; adds many figures at once from dropped files or a folder; and
duplicates, imports from a datapack or deletes a collection for good. The
server checks every file, stores it with the world under
`data/blockpops-world-collections/` and sends it to each player who joins;
nothing is published anywhere else. A world that already holds some still sends
them to its players outside a development environment. A datapack can ship one for `importpack`.
See [World Collections](COLLECTIONS_GUIDE.md#world-collections) for the file
rules, limits and commands, and
[ADR 0003](docs/architecture/decisions/0003-accept-operator-uploaded-world-collections.md)
and
[ADR 0004](docs/architecture/decisions/0004-extend-world-collections-for-the-redesigned-creator.md)
for the design.

## Local verification

Set `BUILD_SCOPE` to `legacy` for a `preparing` matrix or `full` for a `shared`
matrix. Set `JDK25_HOME`, `JDK21_HOME` and `JDK17_HOME` to the installed JDK homes
required by its build plan. Use a POSIX host for scoped resource generation and
artifact staging.

```bash
python3 scripts/release/matrix.py
python3 -m unittest discover -s tests -v
python3 -m unittest discover -s scripts/ci/tests -t . -v
# or both suites across every core, as the Build gate runs them:
python3 scripts/ci/parallel_unittest.py -t . scripts/ci/tests tests
python3 scripts/ci/dependency_policy.py --metadata gradle/verification-metadata.xml
python3 scripts/ci/mod_base_kit.py verify --network
python3 scripts/ci/mod_base_kit.py run template check --repo .
python3 -B scripts/release/build_matrix.py --plan --scope "$BUILD_SCOPE"
python3 -B scripts/release/build_matrix.py --scope "$BUILD_SCOPE" \
  --java-home "$JDK25_HOME" --java17-home "$JDK17_HOME" \
  --java21-home "$JDK21_HOME" --clean --discard-gradle-homes
python3 scripts/release/verify_release.py \
  --matrix release/release-matrix.json --scope "$BUILD_SCOPE" \
  --manifest build/release/artifacts.json \
  --stage build/release
python3 scripts/release/verify_release.py --scope "$BUILD_SCOPE" \
  --manifest build/release/artifacts.json --stage build/release --verify-staged
```

The serial build creates real remapped production JARs and physically
separate client-only E2E harness JARs. Packaged E2E installs those exact bytes
into genuine loader clients and dedicated servers; it never substitutes a Loom
development launch.

See [CONTRIBUTING.md](CONTRIBUTING.md),
[the release architecture](docs/release-architecture.md), and
[the E2E contract](docs/e2e.md) before changing build, runtime, UI, networking,
or release-controller code. The advisory Claude routing, durable queue, cost
limits, Claude Code token, and recovery procedures are documented in
[the visual-review architecture](docs/visual-review.md).

The advisory public gallery of packaged screenshots is published to GitHub
Pages by the pinned [mod-base](https://github.com/The-Plum-Team/mod-base) kit;
see [the public-evidence notes](scripts/pages/README.md).

## License

**All rights reserved** — the source is public, but this is not open source.

You are welcome to read the code, build it for your own use, and fork it to
send pull requests back here. You may not reupload, mirror or redistribute the
mod, publish modified versions of it, or reuse the code in another project.

The only official downloads are [Modrinth](https://modrinth.com/mod/block-pops),
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/block-pops) and this
repository. Anything else is a reupload.

The characters, names and logos in the bundled figure collections belong to
their respective owners and are not covered by this license.

Need permission for something not covered here? Ask — reasonable requests are
usually granted. Full terms in [LICENSE](LICENSE).
