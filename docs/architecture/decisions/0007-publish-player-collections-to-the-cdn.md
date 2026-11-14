# ADR 0007: Publish players' collections to the Custom CDN


## Status

Accepted on 2026-11-14. It is being built in slices, and no game loads a player collection until
the keys it names are compiled in: a build with no key believes nothing that is signed.

It amends [ADR 0002](0002-load-custom-collections-at-runtime.md) and the rule in
[`docs/ai/PROJECT.md`](../../ai/PROJECT.md) that only `com.theplumteam.remote` fetches Custom content,
from a single fixed origin. Player collections are read under their own prefix `p/` of the same
bucket, from a player read origin compiled into the jar (`https://blockpops-cdn.quickskin.net`, a
Cloudflare-proxied hostname on the project's domain), and the game gains an upload origin
(`https://blockpops-uploads.quickskin.net`), used only to write. A release jar therefore holds three
HTTPS origins and three public keys, and nothing else: staging origins and keys exist only in
staging jars that are never published. ADR 0002 remains in force for official Custom collections.
[ADR 0003](0003-accept-operator-uploaded-world-collections.md),
[ADR 0004](0004-extend-world-collections-for-the-redesigned-creator.md),
[ADR 0005](0005-autosave-world-collection-drafts.md) and
[ADR 0006](0006-let-world-collections-turn-off-holographic-draws.md) are unchanged: player
collections reuse the World record's grammar and validators, not World storage or packets.

## Context

Players, not only server operators, want to make a collection in game and share it with a code that
anyone can add, as official collections are shared today. The author decided on 2026-11-06 that:

- players create a collection in game and upload it, getting a share code;
- only the owner, identified by a verified Minecraft UUID, may edit and re-upload it;
- uploads publish directly, with no review beforehand;
- only supporters may upload, as kofi-linker defines them (Ko-fi by name within 35 days, or
  PayNow);
- no secret goes in the jar.

What exists constrains how:

- **Custom collections trust whoever writes the bucket.** A downloaded file is checked for its
  SHA-256 against the manifest and, for a PNG, its IHDR; models and animations are parsed
  leniently and handed to GeckoLib. The definition parser ignores unknown keys and bounds neither
  text nor numbers. None of ADR 0003's validators run on Custom files.
- **The CDN's documents cannot grow.** `manifest.json` and `codes/<code>.json` are read with exact key
  sets by every released jar, so an owner key or any other field breaks them. One manifest, capped at
  256 KiB, holds about 1,500 files for every collection together; a path listed twice fails it
  whole; and B2 has no conditional write to protect it from concurrent writers.
- **Re-uploads break running servers.** A server pins each definition's file hashes at start and
  never refreshes until a restart, and box and logo paths are fixed per id.
- **1.5.0 and 2.0.x jars read the same bucket**, 1.5.0 with weaker checks. Anything added to the
  manifest reaches them.
- **The generic collection sync reaches every client.** On every join each loader sends
  `SyncDynamicCollectionsPacket` with every registered collection; its constructor leaves out only
  Custom and World ids, and an old client registers whatever it receives without a check. Any new
  family of dynamically registered collections must be filtered there, or old clients receive model
  and texture ids they cannot load.
- **Hiding a B2 file does not delete it.** B2 answers every download with the file's id, and on this
  public bucket a download by id works without authentication after the name is hidden, until a
  version delete or a lifecycle rule removes it.
- **The client sends nothing to any service today.** Supporter checks run on the game server, which
  an offline-mode or modified server can lie to; kofi-linker's public `/api/check` trusts whatever
  name and UUID it is given; kofi-linker's `/api/link` ties a paying email to any Minecraft name
  without proof that the payer owns that account, and Mojang names change hands.
- **Mojang's session service can prove an account to a third party.** The client calls `joinServer`
  with a `serverId`, and anyone can ask `hasJoined` who joined with it. The access token goes only
  to Mojang. The call has three shapes across the release's authlib versions (4.0.43 to 10.0.77),
  and authlib 10 (26.3) finds its session host through Mojang's discovery document. Whoever runs an
  online-mode server learns the `serverId` of every player who joins it.
- **Packets constrain old clients.** Below 1.21 `PacketNetworking.canPlayerReceive` always answers
  true, because Architectury's 1.20.1 Forge networking reports a client's server-bound ids; an
  unknown packet there is logged and dropped. `CustomCollectionStatusPacket` writes its result by
  ordinal, so an old client cannot decode a new value.
- **The creator is built around World collections**, the server and operators, and opens only in
  a development environment. Its draft model, previews, file checks and test draw do not need a
  server; its saving does.
- The validators in `com.theplumteam.worldcontent` import only the JDK and Gson, but a Cloudflare
  Worker cannot run Java directly, and a second implementation in another language would drift.
- **The author's machines are reachable by agents.** The always-on server that runs agents and
  Neb Bot can reach the Mac with a BatchMode SSH key and passwordless sudo, so a key kept on either
  is only as safe as that server.

## Decision

- **A personal creator.** A Settings tab, "Mis colecciones", opens the existing creator in a
  personal mode in any world. Drafts are strict World records and canonical files kept on the
  player's own computer (`<gameDir>/blockpops/creator/`): atomic writes, paths resolved inside the
  folder without links, caps of 32 records and 256 MiB, and every read validated again. Drafting
  needs no account; publishing does. The tab is hidden in the packaged E2E, where the creator itself
  stays reachable and offline. The World-mode creator keeps its gate and gains a "Publicar en
  BlockPops" entry for operators.
- **What is published is the player record.** The World record grammar of ADR 0003, 0004 and 0006
  without `id`, `author`, `author_url`, `archived`, `revision`, `draft` and `edited_at`, and without
  retired figures; its files in canonical form (PNG as `PngValidator.canonicalize` writes it, model
  and animation JSON minified with every token kept, number tokens of at most 24 characters); and a
  signed index carrying the facts of every file. Each game materializes it with
  `WorldCollectionMaterializer` under its own id family, so rarity, holographic draws, plush
  collections, box colours, variants and logo layout behave as in World collections.
- **One validator, two hosts.** The pure parts of `com.theplumteam.remote` and
  `com.theplumteam.worldcontent`, plus the new player-content classes, form a content core declared
  in `common/content-core.txt` and limited by a boundary test to an explicit class allowlist of the
  JDK and Gson's tree and stream APIs. The jar runs it as it always has; the upload service runs the
  same source compiled with TeaVM, or, failing that, the same source in a JVM container. There is no
  port. A golden corpus must give the same verdict in both and on every JDK and Gson version the
  release matrix uses, and every revision records the validator's digest. Games compare a file's
  integer facts with the signed index exactly and fit models from the index's bounds, because
  floating-point bounds are not bit-identical across hosts.
- **The game is the final gate.** Every server and client that loads a player collection runs the
  content core on everything it uses: documents, record and, on clients, every file with its role,
  its canonical form, the decoded budgets per collection and across the session, and the facts the
  index states. A file that fails leaves only its own asset missing. No client registers a player
  collection except through that path.
- **Its own namespace on the same bucket.** Player content lives under `p/` of the existing bucket,
  written only by the upload service through a B2 key restricted to `p/` and without `deleteFiles`.
  A separate purge key, limited to `p/c/` and to listing and deleting, deletes every version of the
  files of a collection removed for policy or legal reasons (held by the service and used for
  removals only, each use audited); everything else hidden is deleted a day later by a
  lifecycle rule the service monitors. Ids are `p_` plus 12 base32 characters, reserved both ways;
  codes are two words and two digits the service assigns (`maple-orbit-42`), a shape official codes
  never take, so a code is looked up in exactly one tree; codes are never reissued and are public
  identifiers. The legacy manifest and `codes/` are never written for player content, and new jars
  refuse a `p_` id there.
- **Old jars never see player content.** Player collections travel only in a new pointer packet,
  sent only to clients that announced the capability with a new hello packet (and, from 1.21, can
  receive the packet). The generic `SyncDynamicCollectionsPacket` leaves out Custom, World and player
  ids alike on the server, which is the only protection old clients have, and new clients refuse
  all three families there too; one id-family helper decides every such branch. Result values added
  to `CustomCollectionStatusPacket` are sent only to capable clients; others get `ADDED` or
  `FAILED`. A client without the capability cannot draw from a player collection. Player
  collections are stored per world in their own file, `blockpops-player-collections.json`, at most
  32.
- **Signed, immutable, expiring documents.** Each collection's head (`p/codes/<code>.json`), every
  revision index, the loading status and the revocation list are Ed25519 envelopes whose signature
  covers the exact payload bytes. Records and files are content-addressed and never overwritten; a
  change is a new revision, and a rollback is a new revision reusing an older one's content. The
  status and the revocation list expire after 72 hours and are re-signed daily, and the
  status names the exact revocation list in force. Games persist, with the signing key's id, the
  highest sequence number they accepted per document and refuse a lower one or an implausible
  jump; a game without such state accepts only an unexpired status and the list it names, and
  otherwise leaves player collections off; a game with state keeps what it verified when the
  service is unreachable, but adds nothing new. The release jar holds public keys only: an online
  key, a pre-provisioned successor, and an offline root key whose `root/status.json` (outside the
  service's prefix) can switch loading off and revoke an online key. Each root document starts a
  new epoch: stored sequence numbers reset and everything a revoked key established is discarded and
  fetched again, so a compromised online key cannot lock games out of its successor.
- **Offline keys stay offline.** The root key and the successor key are kept sealed with a
  passphrase on removable media, or the root key on a hardware token that signs raw Ed25519. They
  are used only in a signing ceremony, and never stored where an agent's SSH key can read them or
  beside the key MV releases are signed with.
- **Every URL is computed.** Clients and servers derive each path from the fixed origin, the `p_` id,
  a SHA-256 and the file's type. No path or URL comes from a server, a packet or a file. The pointer
  packet carries the collection's signed head; everything else in it is a hint, and games persist
  only signed values and show the author named by the signed index.
- **Worlds stay on the revision they added.** A world keeps the revision it added until an operator
  moves it ("Hay una versión nueva", one click or a command) or turns on following for that entry;
  an owner can withdraw an old revision, which stops worlds pinned to it from showing the
  collection until they update. Takedowns ignore pins: servers poll the status and revocation list
  every 15 minutes and the heads every 30, and an operator can refresh at once. Clients refresh
  the status and revocation list whenever a session names a collection they have not verified in
  the last 5 minutes, poll them every 60 minutes while a player collection is loaded, drop a
  revoked one and delete its cached files.
- **An upload origin, write-only.** `UploadApi.ORIGIN` is a compile-time constant used only by
  `com.theplumteam.client.upload`, only on an explicit player action (publish, list, download for
  editing, roll back, withdraw, delete, link), never on a dedicated server
  and never in the packaged E2E. Responses are bounded and strict; the game shows only lang keys for
  the enumerated errors it receives, and shows the collection names and codes of the player's own
  list as data, only after they pass the text policy and the code grammar.
- **Identity is a Mojang session; ownership is the UUID.** For each action the client joins with a
  `serverId` derived from a single-use nonce and the digest of that exact action, through the
  game's own session service; the service refuses any name outside `[A-Za-z0-9_]{1,16}` before it
  stores anything, asks `hasJoined` with a URL it builds from parameters, never by concatenation,
  and keys ownership on the UUID only when Mojang names the same account the challenge named. No
  Minecraft server takes part. Non-premium accounts cannot publish in this version.
- **Uploads need a purchase tied to the UUID.** Who counts as a supporter stays kofi-linker's rule,
  asked through an internal RPC, never through the public check. For uploads, a purchase counts when
  PayNow recorded it for that UUID, or when its payer linked it to that UUID: a one-time code shown
  in game after a verified session, entered with the purchase's email on kofi-linker's link page,
  and confirmed from that inbox. Links live in kofi-linker's own database, survive a rename,
  and lapse with the payment; a purchase made by name alone never grants uploads, and nothing binds
  a name. `/api/check`, `/api/link` and in-game perks are unchanged. A lapsed supporter keeps
  the collections they have published and may still change, roll back, withdraw and delete them,
  but cannot publish a new one; a PayNow refund or chargeback, resolved through the links, freezes
  the owner and quarantines their collections.
- **Publish directly; take down by hand.** Nothing is reviewed before it is published. In this
  version there is no report from the game, no automatic quarantine and no public listing of
  codes: a code is shared by its owner with friends and added by a world's operator, and the author
  alone takes content down, by hand, when it is needed. What makes that possible stays: the signed
  revocation list, the kill switches, and a removal that deletes the files and blocks the removed
  images' SHA-256 and perceptual hash from being uploaded, published or rolled back to again, in
  any collection. Reports, moderators besides the author, a published contact for rights holders
  and a review of the terms are decided again before codes are ever listed publicly. Names follow
  a text policy with an exact table of allowed code points (Latin letters, digits and simple
  punctuation; no `§`, no-break space or soft hyphen; no leading, trailing or repeated spaces) and
  a banned and reserved term list matched after folding case, accents, leetspeak and Latin
  confusables; the author shown is always the verified Minecraft name, or no name when that name
  contains a reserved term; links are not accepted. Quotas per owner (5 live collections, 2 in the
  closed beta; 50 publishes a day; 64 MiB stored) and globally, rate limits by network prefix, an
  owner breaker and a global circuit breaker bound volume, and every state change goes to a
  hash-chained audit log.
- **Kill switches.** Pause uploads (a service flag, also tripped by the global breaker); switch
  loading off with the online key; switch loading off or revoke the online key with the offline
  root key; revoke the service's B2 keys.
- **The Usage Guidelines position.** Publishing is a perk of supporting BlockPops; published
  collections are free for everyone to add and use; nothing is sold; Mojang and Microsoft marks are
  not allowed.

## Consequences

- Supporters can share collections they made in game with a code, and edit them from any computer;
  official collections, old jars and their players are untouched.
- Content nobody reviewed is visible to whoever was given its code until the author takes it down. The
  validators bound what it can be (images, geometry and animations within the World limits, no
  scripts, sounds or URLs); the revocation list and the kill switches bound how long it stays; pins keep an
  edit from reaching a world its operator did not move.
- An owner's fixes reach a world only when its operator updates it or follows the collection; an
  owner who must take an old revision back everywhere withdraws it.
- Player collections need the reader release on both the server and the client. Old servers answer
  "unknown code"; old clients on a new server see neither the collections nor their figures, and
  placed figures of them draw as the default figure.
- The game now persists files outside `blockpops-cache/`: the personal drafts. ADR 0003's rule that
  the client keeps downloaded World files in memory only still holds for World collections.
- The game sends requests to a service other than the CDN and Mojang, but only when a player acts.
  That service sees the player's IP address, Minecraft name and UUID, and stores only hashes of
  network prefixes; kofi-linker sees the email of a purchase its payer links. The read origin sees
  every downloading player's IP address, and now also clients refreshing the status and revocation
  list.
- Codes are public identifiers: anyone can enumerate them and see each collection's author, and kept
  revisions show the names an account used while they are kept.
- Publishing depends on Mojang's session service, on Cloudflare, on kofi-linker and, for purchases
  made by name, on email delivery; each failure makes publishing fail closed and leaves loading
  unaffected. A service outage longer than the documents' validity stops games without saved state
  from loading player collections, and a game with a clock more than a day off has them off.
- The service holds the B2 keys for `p/` and the online signing key. A compromise can publish, alter
  or delete player content, never official content, until the offline root switches it off and the
  online key rotates; rotation needs no release while the successor key is compiled in, and the
  root's epoch undoes whatever sequence numbers the compromised key used.
- A removed file stops being downloadable from the bucket, by name and by id, once the purge runs;
  anyone who downloaded it before keeps their copy, and the legal page says so.
- The validator's rules change once, in Java, and reach the service through a pinned mod commit; a
  rules version lets the service refuse outdated clients and outdated rollbacks, and games always
  enforce their own rules.
- B2 transactions and egress grow with servers and clients that use player collections; the poll
  intervals are tunable through the signed status document, and the Cloudflare-proxied read origin
  absorbs most of it at the edge.
- Taking content down, and answering whoever asks for it, are the author's ongoing duties. A
  published contact for rights holders, a repeat-infringer rule and a lawyer's review of the terms
  are owed before codes are listed publicly or publishing opens beyond people the author knows.
  Signing ceremonies with the offline keys become part of key rotation and of switching loading off
  with the root.
- CI and the packaged E2E never contact the CDN, the upload service or Mojang; the packaged E2E
  covers player collections through verified documents placed in the cache, and checks that the
  generic sync never carries them.
