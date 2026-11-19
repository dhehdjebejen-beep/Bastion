# BastionAC load benchmark (19.11.2026)

<p><b>English</b> · <a href="BENCHMARK.ru.md">Русский</a></p>

How much CPU the anti-cheat costs on a two-core server — the kind of server
Bastion was written for. The same measurement was made with Grim for comparison.

## Setup

- **Server:** Fabric 1.21.11 + Fabric API, flat world, peaceful, view and
  simulation distance 4 chunks, network compression off. The server process is
  pinned to **two cores** (Ryzen 7 8845H), 2 GB of memory.
- **Configurations:** plain server; + BastionAC 4.0.0+build.1 (without
  BastionAuth and BastionClaims); + Grim 2.3.74-39d7eb1 (the Fabric build from
  Modrinth). Both anti-cheats on their default settings (clean `config/` before
  each suite).
- **Players are bots** (protocol 1.21.11, on the other two cores) behaving like
  the vanilla client: 70 % walk a circle with the vanilla physics bit for bit
  (float rounding, Minecraft's sine table, grass friction, the airborne first
  tick after a teleport) and turn in whole steps of the sensitivity grid; 30 %
  hit their own invulnerable pig every 11–16 ticks. They answer keep-alives and
  ping transactions and acknowledge teleports and chunks. Primary spacing is a
  96-block grid, out of each other's sight; clustered spacing (32 blocks) is
  measured as a separate single run.
- **Honest bots, checked:** over all 50-player runs BastionAC recorded 0 alerts,
  0 exceptions, and 0 setbacks; Grim logged 2 startup/status anti-cheat lines
  per run.
- **Measurement:** one minute of warm-up, then 120 seconds — the server
  process's CPU time (% of one core) and the average tick time (`/tick query`).

## Results

50 players (`spacing=96`), three repeats of each configuration in rotated order:

| | CPU, % of one core | over the plain server | tick time |
|---|---:|---:|---:|
| plain server | 101.9 ± 4.5 | — | 41.6 ms |
| + BastionAC | 107.7 ± 1.1 | +5.8 | 40.1 ms |
| + Grim | 108.2 ± 3.5 | +6.3 | 40.2 ms |

Single runs (50 players):

| spacing | plain server | + BastionAC | + Grim |
|---:|---:|---:|---:|
| 96 blocks | 97.9 % · 37.8 ms | 108.4 % · 37.7 ms | 117.6 % · 43.2 ms |
| 32 blocks (clustered) | 84.9 % · 21.6 ms | 82.7 % · 17.6 ms | 108.8 % · 24.5 ms |

BastionAC adds +5.8 % of one core on average in the rotated 50-player series
(within ~0.5 % of Grim on CPU while keeping average MSPT slightly lower:
40.1 ms vs 41.6 ms plain, and 17.6 ms vs 21.6 ms plain / 24.5 ms Grim when
clustered at 32 blocks). On this 2-core pinned VM alongside host background load,
80–100 players already push the plain server past 77–95 ms/tick and
intermittently trigger Vanilla's 60-second Server Watchdog (`max-tick-time`),
just as 200 players did on an idle host previously.

## Limits

- The bots are a model of play: honest, no cheats. Argus hardly ran on the
  50-player runs (no alerts, no cases); its background cost per 10 minutes of
  telemetry (~150 ms) runs off the main server thread.
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
