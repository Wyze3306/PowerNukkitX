# Minecraft 1.26.50 (protocol 2193) data

These files describe the content a 1.26.50 client knows. The server translates block, item, entity and
biome ids to them for sessions on protocol 2193; see `org.powernukkitx.network.protocol.legacy`.

Source: the PowerNukkitX gamedata repository, branch `r/26_u5`, commit
`9365be75a4b12370fbbba93324524bae996397fe`, directory `src/main/resources/gamedata/kaooot/`.
The files are copied unchanged.

| File                     | Bytes   | SHA-256                                                            |
|--------------------------|---------|--------------------------------------------------------------------|
| `block_palette.nbt`      | 252077  | `d83f382577fdecfca2e9e98ae9a591f3735e32891c476a79cb2ddca8f866db9f` |
| `item_palette.json`      | 259878  | `a400cd230acaeec957afa44ab227559af5b4b2e4ed312b91fe73dcc01170060a` |
| `entity_identifiers.nbt` | 1560    | `3214a260feaa3db611c9baf513722fdbf26726d0582fca0be6771051a455f0c8` |
| `biomes.json`            | 3017    | `6e91fc91c295d032010a67d3d47023c700f79040e6989cf6d7f460238d34c650` |

`LegacyTablesTest` checks these checksums, so a changed file fails the build instead of shipping silently.
Everything else a 1.26.50 client needs (creative items, recipes, biome definitions, item components) is
derived from the native 1.26.60 data by dropping what 1.26.50 does not know.
