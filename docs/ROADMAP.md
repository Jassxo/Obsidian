# Obsidian — Scaling & Performance Roadmap

A long-term plan for growing Obsidian from a strong single-server anticheat into a
network-scale platform, without ever compromising its core principle: **a missed signal
is recoverable, a false flag is not.** Every item below is measured against that.

This is a living document. Items are tagged by **impact** (◆ high / ◇ medium) and rough
**effort** (S / M / L / XL). Nothing here changes the guardrails in
[CLAUDE.md](../CLAUDE.md); it extends them.

---

## Where we are today (honest baseline)

**Strengths**
- Passive, packet-driven, read-only. No packet is cancelled or modified.
- Bayesian confidence engine (`engine/`) with decay and a watch→flag→punish ladder.
- Lag-, MSPT- and ping-instability-compensated; sustained-evidence checks.
- Combat + crystal detection, plus a corroborating in-JVM ML layer (`ml/`).
- Clean module split (`obsidian-api`, `obsidian-core`), config migration, SQLite/MySQL
  ledger via Hikari, soft bridges to GrimAC and TotemGuard.

**Current limits (what the roadmap attacks)**
- **Single-server.** No cross-server state; a cheater flagged on one backend is unknown
  to the next. No proxy awareness.
- **Some hot-path work still calls the main thread** from netty (`player.getGameMode()`,
  `getInventory()`, `getEyeHeight()`), which is a correctness risk on Folia and a small
  cost everywhere.
- **No prediction engine** for movement — detection is statistical, not simulation-based.
- **ML is bootstrapped, not trained.** No data pipeline, model registry, or shadow eval.
- **No hot-reload of checks/model, no jar integrity, no CI**, and profiling
  (`perf/SelfProfiler`) is coarse (one aggregate budget, not per-check/per-percentile).
- **No staff dashboard** — review and labelling happen through chat commands.

---

## Workstream A — Single-server performance ◆

Make one server cheap so a hundred servers stay cheap.

- **A1 ◆ M — Packet-source all player state.** Stop reading Bukkit from netty threads.
  Track gamemode, held item, sneak/pose, eye height, and position from packets into
  `PlayerData`. Removes every cross-thread main-thread read in `PacketIngestListener`,
  fixes latent Folia hazards, and drops per-attack `Location`/inventory allocations.
- **A2 ◆ M — Per-check, per-percentile profiling.** Extend `SelfProfiler` to record
  p50/p95/p99 per check and per packet type, exposed via `/ob stats` and metrics. You
  cannot scale what you cannot measure; this also drives smarter `experimental` degrade.
- **A3 ◇ M — Interest management for entity tracking.** `EntityTracker` already tracks
  players only; next, only retain position history for players near a *fighting* viewer,
  and allocate the history ring lazily on first combat. Cuts memory on big lobbies.
- **A4 ◇ S — Adaptive sampling.** Deep-sample only watched players (already have
  `isWatched`); down-sample rotation-heavy checks for the calm majority.
- **A5 ◇ M — Allocation audit + GC.** Sweep for boxing and per-event allocation on the
  hot path; size Caffeine caches from real occupancy; consider a small arena for
  transient geometry math.
- **A6 ◆ L — Folia-first threading pass.** Audit every `SchedulerAdapter` use for
  region correctness; make all trackers strictly single-writer; document the threading
  contract per class.

## Workstream B — Detection quality & depth ◆

- **B1 ◆ L — Calibration & replay harness.** Record real packet traces (legit + cheat)
  and replay them offline to measure false-positive / false-negative rates per check.
  This is the single highest-leverage quality investment — it turns tuning from guesswork
  into regression-tested numbers, and becomes the CI gate for every new check.
- **B2 ◇ M — Correlated-signal handling in the engine.** Ensure overlapping checks
  (e.g. hit-while-not-looking vs a future silent-aim) don't double-count evidence;
  add per-category caps so one cheat family can't stack to a false ban.
- **B3 ◇ M–L — New check families.** Movement (fly/speed/step/nofall), timer, velocity,
  scaffold, more protocol/packet-order checks, and additional crystal/anchor variants.
  Each ships behind `experimental` with a calibration report from B1.
- **B4 ◆ XL — Movement prediction engine (long-term).** A physics-simulation core (the
  Grim approach) for setback-grade movement certainty. A major, multi-quarter effort;
  scope it only after B1 exists to prove it beats the statistical checks.

## Workstream C — Machine-learning maturation ◆

- **C1 ◆ M — Data pipeline & schema versioning.** The dataset is already a stable,
  named-column file. Add a schema version, central aggregation from many servers, and a
  train/validation/test split with held-out evaluation. (See the parked
  encrypted-submission option for how servers contribute data.)
- **C2 ◆ M — Shadow evaluation.** Run any candidate model in "score but never act" mode
  in production, logging its scores next to outcomes, before it can contribute a signal.
  No model reaches players unproven.
- **C3 ◇ M — Stronger models + calibration.** Move `LogisticModel` → gradient-boosted
  trees / small MLP behind the existing `Model` interface (already swappable), with
  probability calibration (Platt/isotonic) so scores mean what they say.
- **C4 ◇ L — Weak/active learning.** Use high-confidence deterministic flags as weak
  labels; queue borderline windows for staff review (feeds the dashboard, F-series).
  Reduces the manual `/ob label` burden as the network grows.
- **C5 ◇ M — Model registry + auto-retrain.** Versioned, signed `model.dat` artifacts;
  a scheduled retrain from aggregated data; one-click rollback. Ties into E-series
  hot-reload so a new model ships without restarts.
- **C6 ◇ M — Per-server personalization.** Light per-server baseline adaptation and drift
  detection so a model fits each community without a full retrain.

## Workstream D — Network / multi-server scaling ◆ (the big one)

Adapt the patterns the reference project (TotemGuard) already proves at scale.

- **D1 ◆ L — Redis fleet coordination.** Shared, cross-server player suspicion and alert
  fan-out to one staff feed; an **offline grace window** so proxy transfers (quit→join)
  never fire false alerts. Optional; degrades to standalone when Redis is absent.
- **D2 ◆ L — Proxy bridge module (Velocity/BungeeCord).** Carry a player's confidence
  across server switches via Redis pub/sub — a single source of truth per player, not a
  fresh start on every hop. New module beside `obsidian-api`.
- **D3 ◆ M — Central "Obsidian Hub" service.** A small off-server service that aggregates
  data, trains/serves models, distributes config + `model.dat` to all nodes, and hosts
  dashboards. This is the piece that turns N servers into one learning system.
- **D4 ◇ M — Database scaling.** Tune Hikari pools; partition/retain the flag ledger
  (TotemGuard-style configurable retention); read replicas for dashboards; keep SQLite as
  the zero-setup single-server default.
- **D5 ◇ L — Optional global-ban / threat-intel network.** Opt-in cross-network sharing
  of *hashed* offender signals (privacy-preserving). Powerful, but gated behind strong
  consent and abuse-resistance design.

## Workstream E — Operations, delivery, integrity ◆

- **E1 ◆ M — Hot-reload loader.** Swap checks and models without a restart; keep builds
  side-by-side keyed by SHA-256 prefix; fleet-aware version pinning
  (fixed / latest / experimental). Mirrors TotemGuard's loader.
- **E2 ◆ S — Jar integrity verification.** Embed a SHA-256 manifest, refuse to enable a
  tampered build. Reinforces the proprietary license and stops cracked redistributions.
- **E3 ◆ S — CI/CD.** GitHub Actions: build, run the unit + calibration (B1) suites on
  every push, and automate signed releases. Non-negotiable once contributors arrive.
- **E4 ◇ M — Observability.** Prometheus/bStats metrics, structured logs, per-check health,
  and an **FP-spike alarm** that auto-degrades a check if its flag rate jumps abnormally.
- **E5 ◇ M — Config/model registry with push.** Central config + model distribution to all
  nodes with runtime reload (builds on E1 + D3).

## Workstream F — Product & ecosystem ◇

- **F1 ◆ M — Staff web dashboard.** Flags, player profiles, packet replays, and a labelling
  queue. This is what makes ML data collection scale and staff trust the system.
- **F2 ◇ S — API maturation.** Harden and version `obsidian-api` (javadoc, nullability,
  thread-safety) for third-party integrations, as TotemGuard does for its public API.
- **F3 ◇ M — Platform breadth.** Formalize a Paper/Folia (and eventually Fabric
  server-side) abstraction layer so one codebase spans platforms.
- **F4 ◇ S — Version matrix.** Track supported Minecraft versions via PacketEvents; keep
  1.20 → latest working, with the name-resolved item pattern already used for mace/spear.

---

## Phased timeline

Sequenced so each phase de-risks the next. Prediction (B4), the Hub (D3) and the
dashboard (F1) are deliberately late — they're only worth it once measurement (B1),
threading (A1/A6) and delivery (E1–E3) exist to support them.

**Phase 1 — Foundation (0–3 months): make one server fast, measured, and shippable.**
A1 packet-sourced state · A2 deep profiling · B1 calibration/replay harness · B2 engine
correlation · E3 CI · E2 integrity · C2 shadow-mode ML scaffolding.

**Phase 2 — Depth & data (3–6 months): more detection, real ML.**
B3 new check families (movement/timer first) · C1 data pipeline · C3 stronger model ·
C5 model registry · D4 database scaling · E4 observability + FP alarm.

**Phase 3 — Network scale (6–12 months): from N servers to one system.**
D1 Redis fleet · D2 proxy bridge · D3 Obsidian Hub · E1 hot-reload loader · E5 config/model
push · F1 staff dashboard · C4 active-learning queue.

**Phase 4 — Frontier (12+ months): certainty and autonomy.**
B4 movement prediction engine · C6 per-server personalization + auto-retrain loop ·
D5 opt-in threat-intel network · F3 platform breadth.

---

## Guiding principles as we scale

1. **Optional everything.** Redis, the Hub, the proxy bridge, ML — each degrades cleanly
   to a working standalone plugin. Never a hard dependency.
2. **Measure before you tune, tune before you ship.** B1's calibration numbers gate every
   detection change; C2's shadow mode gates every model.
3. **Corroborate, never over-count.** More checks and a model must not become more ways to
   false-flag the same behaviour (B2).
4. **The hot path stays allocation-free and off the main thread** (A-series) — this is
   what keeps per-server cost flat as features grow.
5. **Ship without restarts** (E1) so a network can improve continuously.

## Key risks

- **False-positive regression** as breadth grows → mitigated by B1/B2/C2 and E4's alarm.
- **Prediction engine (B4) is a tar pit** → only start it once B1 proves the need and
  measures the win.
- **Operational complexity of fleet mode** → keep standalone the default; Redis/Hub are
  opt-in for networks that need them.
- **Data privacy at network scale** (C1/D5) → anonymized/encrypted submission, consent,
  and retention limits before any cross-network sharing.
