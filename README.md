<p align="center">
  <img src="docs/images/banner-en.png" alt="Bastion — server-side security suite for Minecraft Fabric 1.21.11">
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-62B47A">
  <img alt="Clients 1.21.1–26.3 via ViaVersion" src="https://img.shields.io/badge/clients-1.21.1%E2%80%9326.3%20via%20ViaVersion-62B47A">
  <img alt="Fabric" src="https://img.shields.io/badge/loader-Fabric-DBD0B4">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-E76F00">
  <img alt="Server-side only" src="https://img.shields.io/badge/client-no%20mod%20needed-3B82F6">
  <img alt="License: PolyForm Noncommercial 1.0.0" src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-7C5CFF">
</p>

<p align="center"><b>English</b> · <a href="README.ru.md">Русский</a></p>

**Bastion** is a family of three server-side mods that protect an offline-mode
("cracked") Minecraft server: accounts, land and fair play. Everything runs on the
server — players join with an unmodified client and install nothing.

<p align="center"><img src="docs/images/modules-en.png" alt="BastionAuth, BastionClaims, BastionAC"></p>

## The idea

- **Zero trust towards every player.** Before login a player gets nothing: no
  world (empty chunks instead), no chat, no inventory, no commands, no packets
  for their own mods. Trust is never granted for a name, an IP or a "premium"
  account — it is proven with the password, 2FA or a recognised device.
- **No premium login — on purpose.** Automatic login by a Mojang license is not
  implemented: in the author's view a Mojang session can be stolen, and then it
  opens the account without a single question. Instead there are Bastion's own
  mechanisms: a session bound to the IP, the client profile and the launcher
  installation, 2FA and a code word. A regular player gets in without the
  password; a stolen name does not.
- **An anti-cheat that is precise rather than heavy.** The checks rest on vanilla
  physics and protocol, and the disputed cases are investigated by Argus over the
  whole session — instead of simulating every player on every tick.
- **For the sport of it.** The whole project was written out of curiosity: to
  understand how cheats and the defences against them work, and to see what can
  come of it.

## Status

- **Built for a Minecraft 1.21.11 server on Fabric only.** No other server version
  or mod loader has been built or tested.
- **Clients 1.21.1 – 26.3 can play** when ViaVersion and ViaBackwards run on the
  server: Via translates their packets, and the mods work with them unchanged.
  BastionAC knows each player's real client version and adapts the checks that
  depend on it (clients older than 1.21.2 send no tick ends and no keys of their own).
- **Running in production** on the MaxCora server: `MaxCore.mcje.in:36651`
  (Java Edition 1.21.1 – 26.3, any launcher). You are welcome to join and try to break it.
- **The published code can lag behind the live build.** For security reasons,
  updates reach this repository on a schedule and with a delay, so the server may run
  a newer and stronger build than the one published here.
- In-game texts are in Russian (the server's language). Code comments and this
  documentation are in English.

## Modules

| Module | Version | In one line |
|---|---|---|
| [**BastionAuth**](BastionAuth) | 1.13.0 | Login and registration for offline-mode servers: Argon2id with a server pepper, a packet firewall before login, TOTP 2FA, multi-account detection, a growing per-IP account quota, sessions bound to IP, client profile and launcher installation. Before the password the player waits in spectator mode, away from their real location, and gets empty chunks instead of the world. |
| [**BastionClaims**](BastionClaims) | 1.20.0 | Land claims protected by server-side mixins, not only client callbacks: pistons, fluids, hoppers, dispensers and crafters, explosions, fire, projectiles, falling blocks, griefing mobs, allays, copper golems, portals, fishing rods, sculk. Member roles inside a claim. Also includes an operator WorldEdit with undo. |
| [**BastionAC**](BastionAC) | 3.5.0-beta | Anti-cheat engine: 41 checks, buffered violation levels with decay, lag-compensated combat, protocol invariants, Meteor and Wurst signatures. Full session telemetry and **Argus**, an analytical investigation that reads a player's session and returns a verdict; its model is trained in Argus Lab on synthetic and real sessions and looks at the worst fight of a session (cheats switched on for one fight). 3.5 beta adds a **proactive mode**: the checks only raise signals and Argus's verdict switches the sanctions on within seconds. Exploits (book/NBT bombs, creative smuggling, packet floods) are blocked at the protocol. |

Each mod works on its own. When they run together, they talk through soft
reflection bridges, and no mod has a hard dependency on another:

| From → to | What crosses |
|---|---|
| BastionAuth → BastionAC | graded account links (`noteLinkedAccount`); a warning before a player leaves limbo (`expectServerMove`), so the move is not a teleport |
| BastionAC → BastionAuth | whether a player is still held before login (checks stand down); the client brand captured at configuration; the link ledger, read and given verdicts from the `/bac` network tab |
| BastionClaims → BastionAuth | the code word, required before a claim is transferred or deleted; a broken bridge fails closed |
| BastionClaims ↔ MaxCore | Claims reads MaxCore's build mode (builders get WorldEdit under its creative block rules); MaxCore reads the sidebar line (`ClaimApi.statusLine`) and arrests claims for unpaid land tax (`taxRoll`, `setArrested`) |

A missing mod simply leaves its bridge off.

## How they fit together

```mermaid
flowchart LR
    C([Vanilla client]) -->|every packet| FW
    subgraph Auth [BastionAuth]
        FW[Packet firewall<br/>whitelist until login] --> LG[Login / register<br/>Argon2id + pepper, 2FA]
        LG --> LK[(Account-link ledger)]
    end
    LG -->|authenticated| W((World))
    W --> AC
    subgraph AC [BastionAC]
        CH[41 checks] --> VL[Buffers → violation levels<br/>with decay] --> ACT[Alert · mitigate · kick<br/>tempban ladder]
        CH --> TL[(Session telemetry)]
        VL -->|alerts, EDR chains,<br/>suspicious moments| AR[Argus: features vs.<br/>server norms → verdict]
        TL --> AR
    end
    W --> CL
    subgraph CL [BastionClaims]
        MX[Server-side mixins<br/>blocks, fluids, pistons, hoppers,<br/>explosions, entities] --> CF[Claims · flags · trust]
    end
    LK -. same person? .-> ACT
```

## Principles

- **Server authority.** Protection lives in server-side hooks and mixins, so a
  client that skips the vanilla code path cannot skip the protection.
- **Fail closed.** An unreadable claims file refuses to start instead of silently
  unprotecting the world. Credentials are never written in plain text.
- **Evidence over hunches.** The anti-cheat buffers every suspicion and punishes
  only what repeats. Checks prefer outcomes and protocol facts over client-specific
  signatures.
- **Explainable decisions.** `/claim why`, `/claim trace`, `/auth links` and the
  `/bac` panel say *why* something was allowed, refused or flagged.
- **Light by design.** Live checks are cheap per packet; the heavy analysis (a
  physics replay of the whole session, combat statistics, the X-ray world test)
  runs once per case on its own thread and pauses when the server is busy.
- **Proactive, not only reactive.** What a cheat could read is not sent (other
  players' health and gear details, far sound origins, the Nether past its fog,
  the account before login); what a vanilla client could never send (book and
  NBT bombs, creative smuggling, floods, clicks through walls) is dropped on the
  spot.

## Building

Each module is a standalone Gradle project. You need JDK 21; the Gradle wrapper
downloads everything else.

```bash
cd BastionAC        # or BastionAuth / BastionClaims
./gradlew build     # → build/libs/<mod>-<version>+build.<N>.jar
```

The version of a mod changes only with new features; every build in between
gets the next number (`3.5.0-beta+build.4`). The counter is in
`<mod>/build.number`, the shared script in [`buildnumber.gradle`](buildnumber.gradle).

To install, put the jar into the server's `mods/` folder next to
[Fabric API](https://modrinth.com/mod/fabric-api). Each mod writes its config to
`config/<mod>/` on first start.

## Tests

The repository has 383 unit tests: 100 in BastionAuth, 62 in BastionClaims and 221
in BastionAC. Run them with `./gradlew test` in a module folder.
The Argus model is trained and measured on the Argus Lab bench
(`./gradlew argusLab` in `BastionAC`).

## Documentation

- Every module has an English overview (`README.md`) and a Russian one
  (`README.ru.md`).
- `DETAILS.ru.md` in each module holds the full Russian documentation and the
  version history.
- [`BastionAC/ARGUS-LAB.ru.md`](BastionAC/ARGUS-LAB.ru.md) is the Argus Lab report:
  how the Argus model is trained and how well it detects each cheat.

## License

[PolyForm Noncommercial 1.0.0](LICENSE). You may use, fork, change and
distribute the mods for any noncommercial purpose, as long as every copy carries
the license and the `Required Notice` line. Commercial use is **not** allowed:
selling the mods or forks of them, or running them on a server that earns money
(donations for perks, paid ranks, paid access). For commercial terms, contact the
author.

## Author

[@dhehdjebejen-beep](https://github.com/dhehdjebejen-beep)
