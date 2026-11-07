# ADR 0005: Autosave World-collection drafts

## Status

Accepted. It partially supersedes
[ADR 0004](0004-extend-world-collections-for-the-redesigned-creator.md) where that record has the
creator's changes wait for an explicit Save. It changes no packet, record or store format; ADR 0004
and [ADR 0003](0003-accept-operator-uploaded-world-collections.md) stay in force for everything this
record does not change.

## Context

The creator's editor kept every change in a local working copy (`CreatorDraft`) until the operator
pressed Save, Save draft or Save figure, and asked on leaving whether to save. Operators lost work
by leaving through another path, and the three save buttons competed with the one action that
matters to players, publishing. The Quick Skin style rework of the creator drops them: a draft
saves itself, and Publish is the only explicit step.

A draft collection is synced only to operators and is in no machine, so its intermediate states are
invisible to players. They are not free for the server. Every accepted batch runs
`WorldCollectionStore.save` on the server thread, which serialises the whole store (up to 4 MiB),
writes it to a temporary file, keeps the old one as a backup and moves the new one in, and marks
the collections dirty, after which the server sends a full sync of every record and the file table
to every connected player, operators or not, at most every 20 ticks (100 when the sync takes several
parts). The server limits each player to 5 edits a second and 60 a minute (a batch costing one edit
per 16 it holds), 120 upload begins and 32 MiB a minute, one upload at a time, and 2 imports a
minute (`WorldRateLimits`, `WorldContentLimits`); a request over a limit is refused as busy.

Files make it worse. A file that uploads and is replaced stays on the server, counting towards the
1024 files and 64 MiB the server keeps, until garbage collection removes it 24 hours after its
upload, at server start or by command. And a file that comes and goes from the synced records is
released and requested again by other operators' clients, while the server sends each file at most
twice per connection (`WorldAssetSender`) and the client asks for it at most twice
(`ClientWorldCollections`); a third reference on one connection draws the fallback look until the
client reconnects.

Several creator runs can touch one collection at once: a save of its changes, a bulk add of
figures, a create, and the home's actions on the whole collection (duplicate, archive, restore,
delete, import). A plan made while another run is on its way plans against a server state that is
about to change, and would send a removal, a reorder or a publish again.

## Decision

- **Drafts save themselves; published and archived collections hold their changes.** A
  collection's saver (`AutoSaver`) owns its working copy and undo steps. For a draft collection it
  saves each change once it settles, by the kind of change: a typed text 1.5 s after the last key or
  at once when its field loses the keyboard, a number 1.5 s after the last value (0.6 s after a
  slider is let go), anything else 0.4 s after it. A published or archived collection keeps its
  changes, its bulk adds included, until Publish changes sends them all, so players never see
  half-typed names or test logos.
- **Files go only when the operator moves on.** A unit of edits with a file to upload waits for a
  trigger: another figure or tab, a screen of the editor's own, Ctrl+S, publishing or leaving. Trying
  ten skins on one figure uploads one, which bounds the orphaned files and the asset re-sends to the
  operator's explicit navigation.
- **One flight per collection, and a client-wide interval.** No plan is made for a collection while
  any creator run of it is on its way or waits for the sync after it, for at most 10 s
  (`CreatorRuns`); the server sends a status before the sync it causes, so the first sync after an
  accepted run holds it. At least 5 s pass between two autosaves of this client, of any collection,
  so one editing operator causes at most 12 autosave store writes and 12 world-wide syncs a minute
  (a bulk add adds its batches, as before).
- **A client-wide budget with a background reserve.** `ClientWorldUploads` charges every submission
  of the creator to one sliding-window ledger a little under the server's limits (4 edits a second,
  55 a minute, 110 upload begins and 30 MiB a minute, 2 imports a minute) when each part is sent, and
  holds the head of its queue until the ledger has room instead of sending it into a busy refusal.
  Autosaves and bulk adds also leave a reserve (1 edit a second, 10 a minute, 20 begins, 4 MiB) for
  what the operator does by hand. Unlocking a collection and putting it in a machine use their own
  packets, which the server does not count, and are not charged.
- **Held units instead of refused drafts.** A save sends what can go and holds the rest in the
  draft: SetCollection while the name or author is not plain text or a chosen box lacks its colour
  or image; SetCollectionExtras while the author link is invalid, never because SetCollection is
  held (while the server has no logo it carries the default placement, and the draft's own placement
  follows the logo); a figure missing a file, failing a file check, with a blank or invalid name, a
  box face outside its texture, or a file the server no longer has and this computer has no copy of;
  a new figure's first put while its name is typed, since that put fixes its id; everything while the
  collection would be over a cap or budget. The reorder names only figures the server will have.
  When a save of several units is refused as invalid or over a limit, the next saves send one unit to
  a batch, and the unit refused alone is held with the server's message until it changes.
- **Sent-tracking rebase.** The draft remembers what each accepted batch sent, by change key, and a
  sync applied after the server's answer judges the fields by it: a field still holding the sent
  value follows the server from then on, and one changed again since keeps its value and is sent
  next, so typing X, saving X and changing it back before the sync is not lost. A new figure that
  was put and then deleted here is not brought back by the sync that first holds it. A new figure
  keeps the id its first accepted put gave it; while that put is on its way only that figure's id
  holds still, and a name typed meanwhile decides the id of a figure whose put did not land.
- **The saver outlives the editor.** `CreatorAutosave` holds at most one saver per collection,
  ticked from the client tick whatever screen is shown. Leaving the editor saves what waits, files
  included, and goes at once; the saver finishes in the background and ends once nothing waits.
  Opening the collection again attaches to the same draft. Leaving with changes that cannot be saved
  asks whether to stay or to leave without them; a saver left in the background that cannot finish
  says so once in a toast that opens the collection again. A collection no longer synced, as one
  deleted for good, ends its saver silently. A disconnect ends every saver, and what was not sent is
  lost, as before.
- **Publish is the flush followed by Publish in the last batch.** Publishing a draft saves what
  waits first, checks the collection once more, and sends Publish with whatever changed meanwhile.
  It is atomic with the edits still pending at confirm, not with every edit of the session. A
  published collection's change that needs more than one batch puts its new figures retired, in the
  batch of their put, and brings them into the draw together in the last batches; a change that
  fails midway leaves them retired and pending.
- **Undo across saves.** Undo works while a save is on its way and the next save sends the step
  back. Its steps start again when another editor attaches to a draft and when a collection is
  published, since a draft-era step would otherwise retire or delete figures of a live collection.
  Undo past a deletion puts the figure back. Files this computer still holds are uploaded again
  when the server's file table no longer lists them. Stored files go as sha references even once the
  table leaves them out: the table lists only files that a synced record names, while the server
  keeps a file no record names for 24 hours after its upload and accepts a put that names it. When
  the server no longer has the file, it refuses the put, and the figure is held with its message
  until the file is chosen again. A file is predicted lost, and the figure held before anything is
  sent, only when the server says it lost it or a synced record still names it while the table
  does not list it.
- **No wire, record or store change.** Every save uses the existing edits of
  `WorldCollectionEditPacket` and the existing uploads, and is answered by
  `WorldCollectionStatusPacket`.

## Consequences

- Operators no longer lose a draft's changes by leaving the editor, and the editor has one explicit
  verb, Publish. A published collection still changes only when the operator asks.
- Each accepted autosave costs one full store write and one world-wide sync, as each Save did, now
  at the client's pace rather than the operator's: at most 12 a minute per editing operator, and
  syncs go to players who are not operators even when only a draft changed.
- Measured cost, from the soak of 2026-11-07 on `f719a1c` (two runs, the second in brackets where
  it differed). Setup: Fabric 1.20.1 in the development environment, a dedicated server in offline
  mode with a flat world, view distance 4 and the world's own saving off, on a 16-core Linux
  machine that other builds shared (load average about 6 to 12), clients under Xvfb. One operator
  at level 2 drove the editor through its own input: keys typed into the name, drags of the logo's
  size slider, files dropped on the logo zone, clicks on the tab rows and the bulk add's own
  buttons. One player who is not an operator stayed idle. The world held two published collections
  of 13 figures and the draft, a copy of a 3-figure collection that grew to 43, in a 23 KB store.
  The soak lasted 197 s: 60 s of typing with pauses between words, ten drags of the slider, ten
  logos in one visit to Aspecto, ten visits with a logo each, and a bulk add of 40 skins while
  typing; idle baselines of the same length ran before and after it. Throwaway hooks outside the
  product counted the server's own time for each tick (`MinecraftServer.tickTimes`, since
  `/debug stop` on 1.20.1 reports only ticks per second, 20.0 in every window), each move of the
  store file into place, the statuses and syncs on each player's connection and the sync payloads
  the non-operator received. Both runs counted the same events.
  - No busy refusal: pass. The server answered no request with busy in either run, setup and
    checks included; the soak's 40 batches and 51 uploads, the bulk add's 40 files and 5 batches
    among them, were all accepted.
  - At most 12 autosave store writes a minute: fail. Autosaves stayed within the client's bound,
    35 in the soak and at most 12 in any minute, and so did the store while no file went: 12
    writes in the minute of typing, 18 over typing and dragging. But an autosave that uploads a
    file writes the store twice: `WorldCollections.addBlob` saves the whole store and marks the
    collections dirty when the file is stored, and the batch that names the file saves it again.
    Ten visits with a logo each wrote it 20 times in 48 s, 22 times in the worst minute outside
    the bulk add, and the two writes usually leave as two world-wide syncs, since a sync goes as
    soon as a second has passed since the last one: 21 syncs in that minute. The bulk add of 40
    wrote the store 45 times in 4 s. The "at most 12 autosave store writes and 12 world-wide syncs
    a minute" above holds only for autosaves that send no file; one that sends a file costs two of
    each.
  - One upload per visit: pass. Ten logos tried in one visit uploaded one file, when the operator
    left the tab; ten visits with a logo each uploaded one file each.
  - Average tick time within 2 ms of an idle baseline: pass. 0.68 ms a tick over the soak against
    0.80 ms [0.67 ms] over the baseline after it and 0.82 ms [0.75 ms] over the one before, a
    difference of -0.13 ms [+0.01 ms], within the noise of the shared machine. The slowest soak
    tick, 43 ms [36 ms], was the game's own five-minute save, which fell into a baseline as well;
    the next slowest was 3.8 ms [8.4 ms], against up to 13 ms [8.1 ms] in the baselines.
  - Sync bytes received by the non-operator: 55,488 bytes a minute over the soak, 182,100 bytes in
    50 syncs of 3,642 bytes (the two published collections and their file table, as payload before
    the connection's compression), 94,692 bytes in the worst minute, the bulk add's, and none in
    either baseline. Every change of the soak was to a draft this player never sees, so skipping
    the public sync when only a draft changed would have saved all of it. The operator received
    about 95,000 bytes a minute.
  - The other checks behaved as this record says. Leaving with a save on its way, the save started
    the next tick, and the collection opened during it attached the same saver and draft; leaving
    and staying away, the save finished in the background and the saver ended without a word. A
    purge confirmed while a name change waited for the client's interval reached the server with
    nothing after it; the saver ended and the only message was the deletion's own. Two operators,
    one renaming the draft while the other, on a third client, picked its background colour, both
    kept their change on the server and in both editors, and no sync undid either. A disconnect
    while an autosave's upload had begun ended every saver and upload at once; the server dropped
    the upload, and after joining again the collection was as before, its old logo in the file
    table and nothing waiting, the logo tried last lost as a disconnect loses it.
- Orphaned files grow only with explicit navigation; a file that uploads and is replaced still
  counts towards the server's files and bytes until garbage collection, 24 hours after its upload.
- The asset re-send limit remains: a file that toggles in and out of the synced records across
  navigations may draw the fallback look for other operators until they reconnect. Two server-side
  follow-ups, each needing its own ADR, are possible: resetting a player's per-file send count when a
  sync stops naming the file, together with the client's request count; and skipping the public sync
  to players who are not operators when only operator-visible content changed.
- A publish is no longer atomic with every change of the session, only with those pending at
  confirm; the saves before it are each atomic on their own.
- Mixed client and server versions are unaffected: nothing on the wire changed.
