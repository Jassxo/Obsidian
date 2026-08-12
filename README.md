# Obsidian

A passive, statistics-driven PvP anticheat for Paper and Folia, built entirely on
packets. Obsidian never cancels or edits a packet. It watches, measures, and reasons
about behaviour, then reports a calibrated confidence that a player is cheating. The
guiding principle is simple: **a missed signal is recoverable, a false flag is not.**

> Proprietary software. Source-visible, but not open source. **Contributions to this
> project are welcome**, but the code and its ideas may not be redistributed or reused
> in other projects. See [LICENSE](LICENSE).

## What it detects

**Crystal PvP**
- Spawn reaction, place/break cycle, opportunity reaction, anchor cycle
- Snap and GCD rotation, aim consistency

**General combat**
- **Reach / hitbox** — hits landing beyond the allowed distance, measured leniently
- **Killaura / silent aim** — hits while the crosshair is off the target
- **Multi-target aura** — several distinct players hit within an inhuman window
- **Autoclicker** — machine-regular click intervals during combat
- **Aim assist / aimbot** — snap-onto-target then mirror-return flicks
- **Auto-mace** — machine-timed wind-charge → mace-smash combos

**Machine learning** — a lightweight logistic model scores each window of combat over
engineered features and adds one corroborating signal when several agree. It is pure
Java, ships calibrated out of the box, and can be retrained on your own server's data
(see below). It never convicts on its own.

## How it works

Packets become a typed, per-player action timeline. Independent *checks* read that
timeline and emit weighted **signals** — never verdicts. A Bayesian confidence engine
accumulates them in log-odds space, decays stale evidence exponentially, and walks a
ladder: watch → suspicious alert → flag (persisted) → optional punishment.

Every timing measurement is compensated for the player's ping and scaled by server
MSPT. Evaluation is skipped during lag spikes, teleports, respawns, and — importantly —
whenever a connection is **jittery or spiking**, so a laggy player is never flagged or
punished for their connection. Speculative checks are marked *experimental* and
self-disable if the engine exceeds its performance budget.

### Built to avoid false flags

- **Ping**: stable high ping compensates cleanly and stays checkable; unstable ping
  makes every check stand down.
- **Spear / high-reach weapons**: reach limits are raised per-hit while a spear is held.
- **Gamemode**: creative and spectator are fully exempt; reach also raises its limit in
  creative as a safeguard.
- Every combat check needs sustained evidence — none act on a single hit.

## Works alongside TotemGuard

Obsidian handles combat and aim; [TotemGuard](https://github.com/Bram1903/TotemGuard)
handles autototem and inventory. Both are passive PacketEvents listeners with separate
concerns, so they coexist with no setup. An optional bridge lets a TotemGuard flag add a
small, capped confidence bonus to the same player — corroboration, never conviction.

## Requirements

- Paper or Folia, Java 21+
- [PacketEvents](https://github.com/retrooper/packetevents) (standalone plugin)
- Optional: TotemGuard, GrimAC (corroboration bridges), PlaceholderAPI

## Building

Maven multi-module project (`obsidian-api`, `obsidian-core`):

```bash
mvn -q clean package
```

The shaded plugin jar is produced at `obsidian-core/target/Obsidian-<version>.jar`.

## Commands

`/obsidian` (alias `/ob`):

- `alerts` — toggle staff alerts
- `check <player>` — a player's current confidence and active signals
- `history <player> [page]` — flag history from the ledger
- `debug <player>` — stream a player's signals to yourself
- `label <player> <cheat|legit|clear>` — tag a player for ML dataset collection
- `ml` — show the loaded model and whether dataset logging is on
- `stats` — flags today, baseline samples, engine overhead
- `export <player>` — export a player's ledger rows
- `reload` — reload configuration

## Training your own model

The shipped model is a conservative bootstrap. To make it genuinely accurate for your
server, collect labelled data and retrain:

1. Set `checks.ml.dataset-logging: true` in `checks.yml` and `/ob reload`.
2. As players fight, label them with `/ob label <player> cheat` or `legit`. Each scored
   combat window is written to `plugins/Obsidian/dataset.csv` with that label.
3. Once you have a good spread of labelled rows, train a new model:

   ```bash
   java -cp Obsidian-<version>.jar dev.obsidian.core.ml.train.LogisticTrainer \
        plugins/Obsidian/dataset.csv plugins/Obsidian/model.dat
   ```

4. `/ob reload`. A `model.dat` in the data folder is loaded in preference to the bundled
   one. The trainer prints training accuracy and log-loss so you can judge the fit.

`/ob dataset` shows how many rows you have collected and how many are labelled.

**Contributing data.** `dataset.csv` is a plain, human-readable file: a `timestamp`, a
`label`, the `player`, then one named column per feature. If you would like to help
improve the shared model, you are welcome to submit your labelled `dataset.csv` (open an
issue or pull request). The trainer reads columns by name, so extra columns are fine.

## Configuration

- `config.yml` — thresholds, decay, punishment, integrations, exemptions, reach limits,
  ping-stability gating
- `checks.yml` — per-check tuning (all timings are post-compensation), plus the ML block
- `messages.yml` — all user-facing text (MiniMessage)

## License

Copyright (c) 2026 Jassxo. All rights reserved. This project is proprietary; see
[LICENSE](LICENSE). You are welcome to contribute improvements back to Obsidian, but
you may not take its code or ideas into other projects or redistribute it.
