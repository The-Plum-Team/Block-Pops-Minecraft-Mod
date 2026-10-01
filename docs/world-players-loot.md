# World Players boxes in loot tables

The loot pool entry `blockpops:world_players_box` rolls a complete box from the
world's World Players collection. It produces the colored box item with its
`FigureId`, `CollectionId` and `Color` already set, so a datapack needs neither a
`blockpops:box_block` item variant nor a hand-written player UUID.

## Datapack example

Add the entry to any loot table of a server-side datapack, for example
`data/example/loot_tables/world_players_box.json` on Minecraft 1.20.1
(`loot_table` instead of `loot_tables` from 1.21):

```json
{
  "type": "minecraft:chest",
  "pools": [
    {
      "rolls": 1,
      "entries": [{"type": "blockpops:world_players_box"}]
    }
  ]
}
```

Reload datapacks and roll the table as an operator:

```mcfunction
reload
loot give @s loot example:world_players_box
```

Place the box, open it with shears and interact again to take the figure out.

The entry has no options of its own. It accepts the standard fields of a
singleton entry such as `minecraft:item` (weight, quality, conditions and item
functions) in the format of the running Minecraft version.

## Roll behavior

- Each roll picks one figure of the collection uniformly at random, whether that
  player is online or offline. The loot context needs no player or entity.
- If the collection is absent or empty, the entry produces no item.
- The box takes the selected player's box color, or the server's default player
  color when the player has not chosen one.
- The box stores the skin of a selected player who is online and, when Quick
  Skin is installed and has one, the selected player's Quick Skin appearance.
  Rolling never contacts the session service; without a stored skin the figure
  uses the renderer's normal skin lookup for that player.
- Rolling spends no token and unlocks no discovery for anyone.
