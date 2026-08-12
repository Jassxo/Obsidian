# Obsidian

A passive, statistics-driven PvP anticheat for Paper and Folia, built entirely on
packets. Obsidian never cancels or edits a packet. It watches, measures, and reasons
about behaviour, then reports a calibrated confidence that a player is cheating. The
guiding principle is simple: **a missed signal is recoverable, a false flag is not.**

> Proprietary software. Source-visible, but not open source. **Contributions to this
> project are welcome**, but the code and its ideas may not be redistributed or reused
> in other projects. See [LICENSE](LICENSE).

## What it detects

**v1 (this release, `main`)** focuses on crystal PvP:

- Spawn reaction — inhuman reaction time to a crystal appearing
- Place / break cycle — machine-regular attack→place→attack timing
- Opportunity reaction — placing the instant a spot opens, repeatedly
- Anchor cycle — glowstone→anchor macro timing
- Snap and GCD rotation — locked aim vectors and sensitivity-GCD breaks
- Aim consistency — hits piling onto one angular band of the hitbox

**v2 (in development, `v2` branch)** grows Obsidian into a general modern-PvP anticheat:
aim assist / aimbot, killaura and auto-mace, autoclicker / CPS, and reach / hitbox
detection, plus a lightweight, corroborating machine-learning layer. It is designed to
run alongside dedicated checks like TotemGuard (autototem/inventory) without conflict.

## How it works

Packets are translated into a typed, per-player action timeline. Independent *checks*
read that timeline and emit weighted **signals** — never verdicts. A Bayesian confidence
engine accumulates those signals in log-odds space, decays stale evidence exponentially,
and walks a ladder: watch → suspicious alert → flag (persisted) → optional punishment.

Every timing measurement is compensated for the player's ping (rolling median of the last
keepalive round-trips) and scaled by server MSPT, and evaluation is skipped during lag
spikes, teleports, and respawns. Checks that are more speculative are marked
*experimental* and self-disable if the engine exceeds its performance budget.

## Requirements

- Paper or Folia, Java 21+
- [PacketEvents](https://github.com/retrooper/packetevents) (standalone plugin)
- Optional: PlaceholderAPI, GrimAC (corroboration bridge)

## Building

Maven multi-module project (`obsidian-api`, `obsidian-core`):

```bash
mvn -q clean package
```

The shaded plugin jar is produced at `obsidian-core/target/Obsidian-<version>.jar`.

## Commands

`/obsidian` (alias `/ob`):

- `alerts` — toggle staff alerts
- `check <player>` — show a player's current confidence and active signals
- `history <player> [page]` — flag history from the ledger
- `debug <player>` — stream a player's signals to yourself
- `stats` — flags today, baseline sample count, engine overhead
- `export <player>` — export a player's ledger rows
- `reload` — reload configuration

## Configuration

- `config.yml` — thresholds, decay half-life, punishment, integrations, exemptions
- `checks.yml` — per-check tuning (all timings are post-compensation)
- `messages.yml` — all user-facing text (MiniMessage)

## License

Copyright (c) 2026 Jassxo. All rights reserved. This project is proprietary; see
[LICENSE](LICENSE). You are welcome to contribute improvements back to Obsidian, but
you may not take its code or ideas into other projects or redistribute it.
