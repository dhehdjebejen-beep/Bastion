# BastionAC load benchmark (21.10.2026)

<p><b>English</b> · <a href="BENCHMARK.ru.md">Русский</a></p>

How much CPU the anti-cheat costs on a two-core server — the kind of server
Bastion was written for. The same measurement was made with Grim for comparison.

## Setup

- **Server:** Fabric 1.21.11 + Fabric API, flat world, peaceful, view and
  simulation distance 4 chunks, network compression off. The server process is
  pinned to **two cores** (Ryzen 7 8845H), 2 GB of memory.
- **Configurations:** plain server; + BastionAC 3.5.0-beta+build.4 (without
  BastionAuth and BastionClaims); + Grim 2.3.74-39d7eb1 (the Fabric build from
  Modrinth). Both anti-cheats on their default settings.
- **Players are bots** (protocol 1.21.11, on the other two cores) behaving like
  the vanilla client: 70 % walk a circle with the vanilla physics bit for bit
  (float rounding, Minecraft's sine table, grass friction, the airborne first
  tick after a teleport) and turn in whole steps of the sensitivity grid; 30 %
  hit their own invulnerable pig every 11–16 ticks. They answer keep-alives and
  ping transactions and acknowledge teleports and chunks. The bots stand on a
  96-block grid, out of each other's sight.
- **Honest bots, checked:** over all runs BastionAC recorded 0 violations and
  Grim 0 alerts (with verbose on, 0 prediction mismatches); no kicks and no
  setbacks inside the measured window.
- **Measurement:** one minute of warm-up, then 120 seconds — the server
  process's CPU time (% of one core) and the average tick time (`/tick query`).

## Results

50 players, three repeats of each configuration in rotated order:

| | CPU, % of one core | over the plain server | tick time |
|---|---:|---:|---:|
| plain server | 88.6 ± 5.1 | — | 33.2 ms |
| + BastionAC | 89.3 ± 1.9 | +0.6 | 33.7 ms |
| + Grim | 100.3 ± 3.1 | +11.6 | 36.2 ms |

Single runs:

| players | plain server | + BastionAC | + Grim |
|---:|---:|---:|---:|
| 50 | 88.4 % · 33.8 ms | 90.2 % · 34.1 ms | 102.9 % · 36.8 ms |
| 100 | 120.5 % · 76.3 ms | 122.0 % · 74.0 ms | 134.3 % · 82.0 ms |

What BastionAC adds is within the scatter of the measurement (up to ~2 % of one
core for 50–100 players). At 100 players the server is overloaded by itself
(ticks over 50 ms): nearly all the time goes into the world, each bot keeping
its own patch of chunks loaded. Two cores do not hold 200 players even without
an anti-cheat: a tick runs past 60 seconds and the watchdog stops the server.

Ping transactions sent to clients during one 50-player run: BastionAC ~4.7
thousand (knockback and latency checks), Grim ~106 thousand (a transaction on
every player tick).

## Limits

- The bots are a model of play: honest, no cheats. Argus hardly ran (no alerts,
  no cases); the cost of a case was measured separately: ~150 ms per ten
  minutes of play on a background thread, never more than a quarter of a core.
- Grim on Fabric is an alpha build; its history store (SQLite) did not start
  (no driver), everything else ran.
- One kind of load (walking in circles and hitting a mob) on a flat world.

## Reproduce

Scripts in [`tools/acbench`](tools/acbench): `bench_bots.py` — the bots,
`bench_run.py` — runs the configurations, `bench_repeat.py` — repeats in
rotated order. Raw results in `tools/acbench/results/`.

Windows (core affinity and process CPU time come from kernel32), Python 3 and
JDK 21. Put the `libraries/` and `versions/` of a Fabric 1.21.11 server into
`$ACBENCH_ROOT/server` and point `ACBENCH_JAVA`, `ACBENCH_FABRIC_API`,
`ACBENCH_BASTIONAC`, `ACBENCH_GRIM` at the jars:

```bash
python bench_repeat.py 50 3 60 120          # 50 players, 3 repeats, 60 s warm-up, 120 s measured
python bench_run.py vanilla,bastion,grim 100 60 120
```
