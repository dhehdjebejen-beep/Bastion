# BastionClaims

<p><b>English</b> · <a href="README.ru.md">Русский</a> · <a href="../README.md">← Bastion</a></p>

Land claims for Fabric servers (Minecraft 1.21.11). The protection sits in
server-side mixins, not only in client-event callbacks, so a client that sends
packets past the usual path still hits it.

## Claiming

- **Wooden-axe selection.** A marked wand (ordinary axes are never intercepted) or
  `/claim pos1` / `/claim pos2`.
- **Two shapes:**
  - **2D** covers bedrock to sky and costs the base area;
  - **3D** is a cuboid and costs its volume.
- **Multi-zone claims.** Up to 8 boxes in one claim: grow a base without deleting
  and re-creating it.
- **Limits grow with play time**, read from the vanilla `play_time` statistic.
  Tiers are configurable.
- Transfer to another player with confirmation. A chest GUI for everything.

## Members and roles

- **Members** (`/claim add <name>`) build and use everything like the owner, but
  cannot manage the claim.
- **Roles** are permission sets inside a claim: `build`, `containers` (chests and
  workstations), `doors` (doors, buttons, levers), `entities` (animals, frames). A
  member with a role may do only what it grants — say, a "guest" role with doors
  only. A member without a role has full rights.
- `/claim role create <role>`, `/claim role perm <role> <perm> on|off`,
  `/claim role give <name> <role|none>`, `/claim role delete <role>`,
  `/claim add <name> <role>`, or the Roles button in the claim menu. Up to 10
  roles per claim; a new role starts with no rights.

## What is protected

| Threat | How it is closed |
|---|---|
| Breaking, placing, containers, doors, interactions, PvP | Server-side callbacks and access checks |
| Explosions (TNT, creepers, fireballs) | Blocks inside survive, entities take neither damage nor knockback, no explosion fire |
| Item frames, paintings, armor stands, minecarts, animals | Protected from *any* source, including arrows and TNT from outside the border |
| Buckets: pouring and scooping inside someone else's claim | Mixin on `BucketItem`, which also catches item-use packets that skip `UseBlockCallback` |
| Water and lava flowing in from outside | Mixin on `FlowableFluid.flow`; the owner's own fluids flow as usual |
| Pistons pushing blocks across the border | Mixin on `PistonHandler.calculatePush` |
| Hoppers and hopper minecarts pulling from chests inside | Mixin on `HopperBlockEntity` |
| Trees next to a claim | Tree guard with a configurable radius and height; a sapling in a neighbouring claim cannot grow into yours either |
| Fire | Fire does not spread inside a claim, and fire outside cannot burn or ignite blocks inside |
| Dispensers on the border | A dispenser outside cannot pour, ignite or place anything in the cell inside |
| Projectiles | Fire arrows do not prime TNT or light campfires and candles; strangers' arrows do not ring bells, power targets or break dripstone; fire charges do not ignite |
| Channeling lightning | No fire and no struck creatures in someone else's claim |
| Falling blocks | Sand, gravel or anvils that started outside break into items |
| Mobs | Endermen, the wither, ravagers, the dragon, door-breaking zombies — under the `mobs` flag |
| Farmland and Frost Walker | Strangers do not trample crops or freeze water |
| Fishing rods | Strangers cannot hook animals, villagers, armor stands or dropped items out of a claim (needs the `entities` right) |
| Sculk | A catalyst outside the border does not spread sculk into the claim; the owner's own sculk farm inside works as usual |

**Flags** relax protection on purpose. All are off by default:
`pvp`, `mobs`, `mobdmg`, `explosions`, `fluids`, `pistons`, `hoppers`, `fire`,
`containers`, `doors`, `entities`. Workstations (crafting table, anvil and
similar) count as containers, so the `containers` flag opens them to guests.

## Explainable decisions

- `/claim why` — why you can or cannot act here: whose claim it is, which flag
  applies, what you are here and which role you have.
- Operators manage any claim: the server-wide claim list in the menu,
  `/claims here`, `/claims manage`, `/claims flag`.
- `/claim trace` — prints PASS/FAIL for every click and break attempt.
- `/claim borders` — shows the claim edges with particles.

## Operator WorldEdit

`/we` offers:
- set, replace, walls, faces, hollow;
- copy, cut, paste, rotate, flip, stack, move;
- undo and redo.

Large jobs are **spread over ticks** (20 000 blocks a tick; 100 000 per
operation by default), so they do not freeze the server. Undo restores **block-entity contents** — chest items, sign text,
spawners — and replaced containers never spill items. `move` carries contents
along; its undo carries back whatever is inside the moved chests *now*, so nothing
can be duplicated. Builders in another mod's build mode may use it only inside
their own claim.

## Robustness and API

- **Fail closed.** If `claims.json` cannot be read, the mod recovers from the last
  known-good `claims.json.bak`, or refuses to start. It never runs a world without
  protection.
- **Writes** go through a single I/O thread, a temp file and an atomic move.
- **`ClaimApi`** lets other mods:
  - look up the claim at a position or a player;
  - list every claim with its owner and cost (for a land tax);
  - **arrest** a claim (for example for unpaid land tax). An arrested claim locks
    everyone out, the owner included, until the arrest is lifted.

## Technical reference

### Defaults (`config/bastionclaims/config.json`)

| Key | Default | Meaning |
|---|---|---|
| `progression.tiers` | 0 h: 2 claims × 80 000 · 5 h: 4 × 150 000 · 15 h: 8 × 250 000 | limits by play time; cost is the footprint for 2D, the volume for 3D |
| `fullHeightByDefault` | true | plain `create` makes a 2D claim |
| `minSideLength` | 5 | shortest horizontal side (no micro-claims) |
| `maxZonesPerClaim` / `zoneMaxDistance` | 8 / 256 | boxes per claim, how far a new box may be from the others |
| `wandItem` | `minecraft:wooden_axe` | the wand; only a marked one selects |
| `wandFreeBurst` / `wandCooldownSeconds` / `wandBurstResetMinutes` | 3 / 15 / 5 | how often a new wand can be taken |
| `opBypassLimits` / `opBypassProtection` | true / false | operators ignore limits; protection only after `/claim bypass` |
| `protection.*` | all on | block, interact, explosion, PvP, mob grief, mob damage, fluid, piston, hopper, fire, tree and entity protection, each switchable server-wide |
| `protection.treeGuardRadius` / `treeGuardHeight` | 6 / 32 | how far a growing tree is checked |
| `workstationBlocks` | crafting table, anvils, loom, smithing table, stonecutter, grindstone, cartography and fletching tables, enchanting table, composter, cauldrons, respawn anchor, bell | blocks with a screen but no inventory; they count as containers, so the `containers` flag opens them to guests |
| `serviceEntityNames` | | NPC names anyone may right-click inside any claim |
| `worldEdit.maxBlocks` / `blocksPerTick` / `undoDepth` | 100 000 / 20 000 / 10 | per operation (up to 5 000 000 in the config), per tick, undo steps |
| `worldEdit.builderMaxBlocks` / `buildersOnlyInsideOwnClaim` / `respectClaims` | 20 000 / true / true | limits for builders who are not operators |
| `worldEditSessionKeepMinutes` | 30 | selection, clipboard and undo kept after a disconnect |

A broken config is backed up and replaced with defaults; `/claims reload` applies
changes without a restart. Old configs are migrated only where they still carry
the old defaults.

### Flags

All off by default (off = protected).

| Flag (aliases) | On means |
|---|---|
| `pvp` | players may fight inside |
| `mobs` (`mobgrief`) | mobs may grief: endermen, the wither, ravagers, the dragon, door breakers, mob explosions |
| `mobdmg` (`mobdamage`) | hostile mobs may hurt players inside |
| `explosions` (`tnt`) | explosions break blocks and hurt entities inside |
| `fluids` (`liquids`, `water`) | water and lava flow in from outside |
| `pistons` | pistons outside push blocks across the border |
| `hoppers` | hoppers and hopper minecarts outside pull from or push into containers inside |
| `fire` (`огонь`) | fire ticks and spreads inside |
| `containers` (`chests`) | guests open containers and workstations |
| `doors` (`buttons`) | guests use doors, trapdoors, gates, buttons, levers, pressure plates |
| `entities` (`существа`) | guests use and hit animals, villagers, frames and stands |

### Server-side hooks

| Mixin | Target | Closes |
|---|---|---|
| `BucketItemMixin` | `BucketItem.placeFluid`, `use` | pouring and scooping, also from item-use packets that skip `UseBlockCallback` |
| `FlowableFluidMixin` | `FlowableFluid.flow` | fluids flowing in |
| `PistonHandlerMixin` | `PistonHandler.calculatePush` | pushes across the border |
| `HopperBlockEntityMixin` | `getInputInventory`, `getOutputInventory` | hoppers pulling from or pushing into a claim |
| `DispenserBlockMixin` | `DispenserBlock.dispense` | a dispenser outside acting on the cell inside |
| `ExplosionImplMixin` | `getBlocksToDestroy`, `damageEntities` | blocks, explosion fire, damage and knockback |
| `EntityDamageMixin` | `damage` of frames, paintings, vehicles, armour stands | entities hit by any source |
| `FireBlockMixin` | `scheduledTick`, `trySpreadingFire` | fire inside and fire reaching in |
| `ProjectileBlockHitMixin`, `SmallFireballMixin` | `onBlockHit` | arrows, tridents and fire charges acting on blocks |
| `LightningMixin` | `spawnFire`, `tick` | Channeling fire and struck mobs |
| `FallingBlockMixin` | `FallingBlockEntity.tick` | blocks that started falling outside |
| `MobGriefMixins` | endermen, wither, ravager, dragon, `BreakDoorGoal`, `StepAndDestroyBlockGoal` | mob griefing |
| `FarmlandMixin`, `FrostWalkerMixin` | `onLandedUpon`, the Frost Walker effect | trampling and freezing |
| `SaplingGrowthMixin` | `SaplingBlock.generate` | trees growing into a claim |
| `FishingBobberMixin` | `pullHookedEntity` | pulling entities and items out |
| `SculkSpreadMixins` | `SculkSpreadManager`, `SculkBlock`, `SculkVeinBlock` | sculk spreading in |

### Storage

`claims.json` is a flat list; a chunk index (`dim → chunk → claims`) is built at
load, so the owner of a block is found without scanning. Every change is
serialised on the server thread (claims are changed in place) and written by the
single `BastionClaims-IO` thread through a temp file and an atomic move. A copy
that parsed cleanly is kept as `claims.json.bak`. If both files are unreadable
the mod refuses to start, and it never overwrites a file it could not read.

### For other mods

`dev.bastionclaims.api.ClaimApi`, made to be called through reflection:
`claimAt(player)`, `isInClaim(player)`, `statusLine(player)` (the sidebar line),
`claimIdAt(dim, x, y, z)`, `claimNameAt(…)`, `guestContainersAt(…)`,
`countOf(player)`, `openMenu(player)`, `taxRoll()` (each claim as
`{id, ownerUuid, ownerName, name, cost, dim, arrested}`), `setArrested(id, on,
reason)`, `isArrested(id)`.

Bridges, all optional: BastionAuth's code word must be spoken before a claim is
transferred or deleted (a broken bridge fails closed and logs it); MaxCore's
build mode lets builders use WorldEdit under its creative block rules.

## Commands

| Player — `/claim` (alias `/приват`) |
|---|
| `wand` · `pos1` · `pos2` · `create <name>` · `create2d` · `create3d` · `list` · `info` · `rename` |
| `trust` · `untrust` · `add <name> [role]` · `role` (`list`, `create`, `perm`, `give`, `delete`) · `transfer` · `flag <flag> <on\|off>` · `addzone` · `zones` · `delzone` · `borders` · `why` · `trace` · `delete` → `delete confirm` |

| Staff — `/claims` (op 3+) |
|---|
| `reload` · `bypass` · `list <name>` · `delete <name> <claim>` · `here` · `manage <name> <claim>` · `flag <name> <flag> on\|off <claim>` · `/we …` |

For operators, `/claim bypass` works the same as `/claims bypass`.

## Build

```bash
./gradlew build   # JDK 21 → build/libs/bastionclaims-<version>.jar
./gradlew test    # 58 unit tests
```

The config lives in `config/bastionclaims/config.json` (hot-reload) and the claims
in `config/bastionclaims/claims.json`.

Full Russian documentation and version history: [`DETAILS.ru.md`](DETAILS.ru.md).
