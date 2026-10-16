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
`containers`, `doors`, `entities`. Workstations (crafting table, anvil and similar) can stay
usable for guests.

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

Large jobs are **spread over ticks**, so a million blocks does not freeze the
server. Undo restores **block-entity contents** — chest items, sign text,
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
  - look up the claim at a position;
  - read the owner and the claimed area;
  - **arrest** a claim (for example for unpaid land tax). An arrested claim locks
    everyone out, the owner included, until the arrest is lifted.

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
