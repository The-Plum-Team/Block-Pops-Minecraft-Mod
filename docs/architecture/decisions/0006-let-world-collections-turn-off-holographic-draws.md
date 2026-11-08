# ADR 0006: Let a World collection turn off holographic draws

## Status

Accepted. It extends
[ADR 0004](0004-extend-world-collections-for-the-redesigned-creator.md) with one more optional
record key and one more edit, under that record's rules, which stay in force.
[ADR 0003](0003-accept-operator-uploaded-world-collections.md) and
[ADR 0005](0005-autosave-world-collection-drafts.md) are unchanged; unlike ADR 0005, this record
changes the record and the wire.

## Context

Every machine draw comes out holographic one time in 25 (`Holographic.CHANCE`), whatever the
collection. The flag then travels with the box, the figure taken out of it, the plush and every item
they drop, and the client draws such a figure with its own shader and announces it. An operator who
makes a World collection for one purpose, such as an event's prizes or a server's staff, may want
its figures always to look as they were made, and the redesigned creator offers that as a
per-collection toggle.

The roll happens only on the server, in `DropBoxPacket`, for boxes and plushes alike. An operator's
Unlock, `/blockpops getbox` and the creator's test draws never give a holographic figure; a test
draw only shows one, on the client. A setting the client alone honoured would change nothing that
players are given, so it has to be enforced where the server rolls.

The record and the wire constrain how the setting is stored and sent, as ADR 0004 describes: the
record is strict JSON on both sides, the store file stays at `format: 1`, records travel as opaque
bytes and are registered again only when their bytes change, and there is no protocol version
between client and server. Edits travel as `WorldCollectionEdit` actions named by a byte, and a
server refuses a packet whose action it does not know.

## Decision

- **An optional record key, left out at its default.** The record gains `holographic`, written only
  as `false`: a collection's draws may come out holographic unless it says otherwise. Every existing
  record keeps its bytes, nothing is registered or synced again on upgrade, and
  `WorldCollectionStore.FORMAT` stays 1. A stored or synced record that writes `holographic: true`
  is refused, so each value has one form; a datapack may write either value.
- **An appended edit.** `SET_HOLOGRAPHIC` (action 15) carries the collection's id and the flag. It
  goes in batches like any other edit, costs what they cost and is answered by
  `WorldCollectionStatusPacket`. It applies to drafts, published and archived collections alike and
  names no file. It is appended to `WorldCollectionEdit.Action` rather than added to
  `SET_COLLECTION_EXTRAS`, so the wire form of every existing edit stays the same and a server that
  does not know it refuses the packet instead of misreading the edits after it.
- **The server enforces it where it rolls.** The materializer carries the flag into the registered
  `FigureCollection` (`allowsHolographic()`), and `WorldCollectionCheck` refuses, on both sides, a
  collection whose flag differs from its record's. `Holographic.roll(collection)` is the one roll,
  used for every machine draw of a box or a plush, and never comes out holographic for a collection
  that does not allow it. Bundled and Custom collections always allow it, and the flag is not part
  of their JSON. Figures already drawn keep their flag: turning it off changes only the draws after
  it.
- **Duplicates and imports carry it.** A duplicate copies it with the other extras. A datapack's
  `collection.json` may say `"holographic": false`. An import defines it as it defines the other
  extras, so importing a pack without the key into an existing collection turns holographic draws
  back on.
- **The creator.** `CreatorDraft` tracks it under the change key `holographic`, a discrete change
  that a draft saves 0.4 s after it and a published or archived collection keeps until Publish
  changes, as ADR 0005 has every change. It is a save unit of its own, so it never waits for a held
  identity or author link. The creator's test draws follow it.
- **Same mod version on both sides**, as ADR 0004 already requires: an older client refuses a sync
  whose records carry `holographic`, and an older server refuses the new edit.

## Consequences

- Worlds whose collections never turn it off keep exactly the bytes they had.
- A world where a collection turns it off cannot be edited by an older BlockPops build until it is
  upgraded again, with the downgrade behaviour ADR 0004 describes.
- Mixed client and server versions refuse each other's World collections once a collection uses
  it, and an older server refuses an editor that sends the edit.
- A World collection with holographic draws turned off never hands out a holographic figure or plush
  from a machine; turned back on, it rolls again from the next draw.
- Bundled and Custom collections draw exactly as before.
- Tests for the key and the edit are in the common module's unit tests (`common/src/test`), added
  by a protected follow-up because their build wiring is protected.
