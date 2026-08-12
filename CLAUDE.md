# Obsidian — project guide

Passive, packet-driven PvP anticheat for Paper/Folia. Read-only: no packet is ever
cancelled or modified. Detection is evidence, not judgement — checks emit weighted
signals into a Bayesian confidence engine that decides.

## Working conventions

- **Commit messages read like a person wrote them.** No "Phase N:" prefixes, no
  changelog-robot voice. A short imperative subject and, when useful, a plain-language
  body explaining the why.
- **Do not add Claude / AI co-author trailers** to commits.
- Match the surrounding code's style: focused classes, meaningful names, comments that
  explain intent and trade-offs rather than restating the code.
- `main` is the v1 line; `v2` carries the general-combat and ML work.

## Architecture

Packets → typed timeline → checks → confidence engine → verdict ladder.

- `packet/PacketIngestListener` — the only class that touches raw packets. Translates
  them into `ActionRecord`s on a per-player `ActionTimeline`, and maintains trackers.
- `tracker/` — per-player state: `PlayerData` (owned by the player's netty thread),
  `EntityTracker` (players only — the sole targets checks use), `CrystalTracker`,
  `PingTracker` (median + jitter + spike), `RotationHistory`.
- `check/` — `Check` base; each check reads the timeline/rotations and calls
  `signal(...)`. Register new ones in `CheckManager`'s array.
- `engine/` — `ConfidenceEngine` (log-odds accumulation, decay, thresholds) and
  `SuspicionState`.
- `lag/LagContext` — every timing comparison goes through it; `shouldSkip()` covers
  server lag, teleport/respawn grace, and ping instability.
- `ml/` — `FeatureExtractor` → `LogisticModel` → `MlCheck`, plus `DatasetLogger` and
  `train/LogisticTrainer`. The model is one corroborating voice, never a standalone ban.
- `integration/` — soft, reflection-based bridges to GrimAC and TotemGuard (capped
  corroboration bonus; no compile-time dependency).

## Non-negotiable guardrails

- Read-only and passive. Never cancel or modify a packet.
- No allocation on the per-action hot path; per-player state lives in preallocated
  check slots (`PlayerData.checkState`).
- Every timing comparison is ping- and MSPT-compensated; skip under
  `lagContext.shouldSkip()`.
- When in doubt, stay silent. A missed signal is recoverable; a false flag is not.
- Combat checks use sustained/distribution evidence — never flag on a single hit.
- ML corroborates; it never convicts alone (capped, engine-mediated, interpretable).

## Adding a check

1. Extend `Check` in `check/impl/`; give it an id and category.
2. Read from the timeline in `onAction` / rotations in `onRotation`; emit via `signal`.
3. Keep per-player state in a `State` class via `data.checkState(slot(), State::new)`.
4. Register it in `CheckManager` and add a section to `checks.yml`.

## Build & test

```bash
mvn -q clean test       # unit tests
mvn -q clean package    # shaded jar at obsidian-core/target/Obsidian-<version>.jar
```

Targets Java 21. Mace/wind-charge/spear are resolved by material name so the build
stays on the 1.20.x API while the features work on 1.21+.

## ML: retraining

Turn on `checks.ml.dataset-logging`, label players with `/ob label <player> cheat|legit`,
then run `dev.obsidian.core.ml.train.LogisticTrainer <dataset.csv> <model.dat>`. A
`model.dat` in the data folder overrides the bundled one. Feature order is defined once
in `ml/Features.java` and shared by the extractor, model, and trainer so it can't drift.
