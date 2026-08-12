package dev.obsidian.core.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.CrystalTracker;
import dev.obsidian.core.tracker.PlayerData;
import dev.obsidian.core.tracker.TrackedEntity;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The only class that touches raw packets. Everything it does is read-only —
 * no packet is ever cancelled or modified — and everything it learns is written
 * into the typed per-player structures the checks consume.
 *
 * <p>Runs on netty threads. The Bukkit calls used here (main-hand item type,
 * player location) are plain field reads that anticheats conventionally do off
 * the main thread; nothing here mutates game state.</p>
 */
public final class PacketIngestListener extends PacketListenerAbstract {

    /**
     * Extra slack, on top of the player's ping, for the lag-compensation window
     * used by reach: covers entity interpolation and packet jitter. Generous on
     * purpose — reach must never fire on a legit laggy hit.
     */
    private static final long REACH_WINDOW_SLACK_MS = 100L;

    // 1.21+ items resolved by name so this compiles and runs on 1.20.x too; null
    // there, which simply means the mace/wind-charge paths stay dormant.
    private static final Material MACE = Material.getMaterial("MACE");
    private static final Material WIND_CHARGE = Material.getMaterial("WIND_CHARGE");
    /** High-reach melee weapon; null on versions without it, so the path stays dormant. */
    private static final Material SPEAR = Material.getMaterial("SPEAR");

    /** Standard eye height, used only as a fallback before the first position packet. */
    private static final double DEFAULT_EYE_HEIGHT = 1.62;

    private final ObsidianPlugin plugin;

    public PacketIngestListener(ObsidianPlugin plugin) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // client -> server
    // ------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        PlayerData data = plugin.players().get(player.getUniqueId());
        if (data == null) {
            return;
        }
        long now = System.nanoTime();

        var type = event.getPacketType();
        if (type == PacketType.Play.Client.KEEP_ALIVE) {
            data.ping.onKeepAliveReceived(new WrapperPlayClientKeepAlive(event).getId(), now);
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            if (flying.hasPositionChanged()) {
                updateVelocity(data, flying.getLocation().getX(),
                        flying.getLocation().getY(), flying.getLocation().getZ());
            }
            if (flying.hasRotationChanged()) {
                data.rotations.add(flying.getLocation().getYaw(), flying.getLocation().getPitch(), now);
                if (!isExempt(data, now)) {
                    refreshLag(data, now);
                    plugin.checkManager().dispatchRotation(data);
                }
            }
            return;
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
            if (slot < 0 || slot > 8 || slot == data.heldSlot) {
                return; // out-of-range slots aren't ours to police; duplicate slots are noise
            }
            data.heldSlot = slot;
            data.holdingCrystal = mainHand(player) == Material.END_CRYSTAL;
            ingest(data, player, ActionType.HOTBAR_SWITCH, now, rec -> rec.hotbarSlot = slot);
            return;
        }

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return;
            }
            onAttack(data, player, interact.getEntityId(), now);
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement place = new WrapperPlayClientPlayerBlockPlacement(event);
            Vector3i pos = place.getBlockPosition();
            long packed = ActionRecord.packBlockPos(pos.getX(), pos.getY(), pos.getZ());
            Material held = mainHand(player);

            if (held == Material.END_CRYSTAL) {
                data.lastCombatNanos = now;
                plugin.crystals().recordPlacementAttempt(player.getUniqueId(),
                        pos.getX(), pos.getY(), pos.getZ(), now);
                ingest(data, player, ActionType.CRYSTAL_PLACE, now, rec -> rec.targetBlockPos = packed);
            } else if (held == Material.GLOWSTONE) {
                // Charging an anchor and placing a glowstone block look identical
                // at packet level; the anchor-cycle check disambiguates by what
                // happens at this position next.
                data.lastAnchorChargePos = packed;
                data.lastAnchorChargeNanos = now;
                ingest(data, player, ActionType.GLOWSTONE_PLACE, now, rec -> rec.targetBlockPos = packed);
            } else if (packed == data.lastAnchorChargePos
                    && now - data.lastAnchorChargeNanos < 2_000_000_000L) {
                // Non-glowstone click on a just-charged block = detonation.
                ingest(data, player, ActionType.ANCHOR_INTERACT, now, rec -> rec.targetBlockPos = packed);
            } else {
                ingest(data, player, ActionType.BLOCK_PLACE, now, rec -> rec.targetBlockPos = packed);
            }
            return;
        }

        if (type == PacketType.Play.Client.USE_ITEM) {
            if (WIND_CHARGE != null && mainHand(player) == WIND_CHARGE) {
                ingest(data, player, ActionType.WIND_CHARGE_USE, now, null);
            } else {
                ingest(data, player, ActionType.USE_ITEM, now, null);
            }
            return;
        }

        if (type == PacketType.Play.Client.ANIMATION) {
            ingest(data, player, ActionType.SWING, now, null);
        }
    }

    /**
     * Attack on some entity. Crystals keep their existing dedicated path; any
     * other tracked entity becomes an ENTITY_ATTACK carrying the geometry the
     * combat checks need (lenient reach, angle-to-target, target kind, mace and
     * fall context).
     */
    private void onAttack(PlayerData data, Player player, int entityId, long now) {
        // Attacker eye, from the player's own packet position when we have it (no
        // Location allocation, and more packet-accurate than the Bukkit snapshot).
        double eyeHeight = player.getEyeHeight();
        double eyeX;
        double eyeY;
        double eyeZ;
        if (data.hasPos) {
            eyeX = data.lastPosX;
            eyeY = data.lastPosY + eyeHeight;
            eyeZ = data.lastPosZ;
        } else {
            Location loc = player.getLocation();
            eyeX = loc.getX();
            eyeY = loc.getY() + (eyeHeight > 0 ? eyeHeight : DEFAULT_EYE_HEIGHT);
            eyeZ = loc.getZ();
        }

        CrystalTracker.CrystalRecord crystal = plugin.crystals().getCrystal(entityId);
        if (crystal != null) {
            data.lastCombatNanos = now; // fighting crystals is combat
            float angle = angleTo(data, eyeX, eyeY, eyeZ, crystal.x, crystal.y + 1.0, crystal.z);
            ingest(data, player, ActionType.CRYSTAL_ATTACK, now, rec -> {
                rec.targetEntityId = entityId;
                rec.angleToTarget = angle;
            });
            return;
        }

        TrackedEntity target = data.entities.get(entityId); // non-null == a tracked player
        data.lastCombatNanos = now;
        boolean isPlayer = target != null;
        Material held = mainHand(player);
        boolean mace = MACE != null && held == MACE;
        float angle = target == null ? -1f
                : angleTo(data, eyeX, eyeY, eyeZ, target.centerX(), target.centerY(), target.centerZ());
        double reach = -1;
        if (target != null) {
            long window = (data.ping.medianPing() + REACH_WINDOW_SLACK_MS) * 1_000_000L;
            double d = target.minReachDistance(eyeX, eyeY, eyeZ, now - window);
            reach = d == Double.MAX_VALUE ? -1 : d;
        }
        double limit = effectiveReachLimit(player, held);
        double vy = data.verticalVelocity;
        double reachFinal = reach;
        ingest(data, player, ActionType.ENTITY_ATTACK, now, rec -> {
            rec.targetEntityId = entityId;
            rec.targetIsPlayer = isPlayer;
            rec.angleToTarget = angle;
            rec.reachDistance = reachFinal;
            rec.reachLimit = limit;
            rec.withMace = mace;
            rec.verticalVelocity = vy;
        });
    }

    // ------------------------------------------------------------------
    // server -> client
    // ------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        PlayerData data = plugin.players().get(player.getUniqueId());
        if (data == null) {
            return;
        }
        long now = System.nanoTime();

        var type = event.getPacketType();
        if (type == PacketType.Play.Server.KEEP_ALIVE) {
            data.ping.onKeepAliveSent(new WrapperPlayServerKeepAlive(event).getId(), now);
            return;
        }

        if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
            Vector3d pos = spawn.getPosition();
            if (spawn.getEntityType() == EntityTypes.END_CRYSTAL) {
                plugin.crystals().onCrystalSpawn(spawn.getEntityId(), pos.getX(), pos.getY(), pos.getZ(), now);
                // Per-viewer spawn time: the moment the crystal became visible to
                // THIS player, the basis of the spawn-reaction check.
                data.crystalSeenNanos.put(spawn.getEntityId(), now);
                if (data.crystalSeenNanos.size() > 256) {
                    data.crystalSeenNanos.clear(); // leak guard; entries normally die with the crystal
                }
                return;
            }
            // Only players are tracked; everything else is skipped entirely.
            if (spawn.getEntityType() == EntityTypes.PLAYER) {
                data.entities.spawnPlayer(spawn.getEntityId(), pos.getX(), pos.getY(), pos.getZ(), now);
            }
            return;
        }

        if (type == PacketType.Play.Server.SPAWN_PLAYER) {
            WrapperPlayServerSpawnPlayer spawn = new WrapperPlayServerSpawnPlayer(event);
            Vector3d pos = spawn.getPosition();
            data.entities.spawnPlayer(spawn.getEntityId(), pos.getX(), pos.getY(), pos.getZ(), now);
            return;
        }

        if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            WrapperPlayServerEntityRelativeMove move = new WrapperPlayServerEntityRelativeMove(event);
            data.entities.move(move.getEntityId(), move.getDeltaX(), move.getDeltaY(), move.getDeltaZ(), now);
            return;
        }

        if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            WrapperPlayServerEntityRelativeMoveAndRotation move =
                    new WrapperPlayServerEntityRelativeMoveAndRotation(event);
            data.entities.move(move.getEntityId(), move.getDeltaX(), move.getDeltaY(), move.getDeltaZ(), now);
            return;
        }

        if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(event);
            Vector3d p = tp.getPosition();
            data.entities.teleport(tp.getEntityId(), p.getX(), p.getY(), p.getZ(), now);
            return;
        }

        if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(event);
            for (int entityId : destroy.getEntityIds()) {
                data.entities.remove(entityId);
                CrystalTracker.CrystalRecord crystal =
                        plugin.crystals().getCrystalOrRecentlyDestroyed(entityId);
                if (crystal == null) {
                    continue;
                }
                plugin.crystals().onCrystalDestroy(entityId);
                data.crystalSeenNanos.remove(entityId);
                // The block under the crystal is placeable again: opportunity onset.
                long base = ActionRecord.packBlockPos(
                        (int) Math.floor(crystal.x),
                        (int) Math.floor(crystal.y) - 1,
                        (int) Math.floor(crystal.z));
                data.recordOpportunity(base, now);
            }
        }
    }

    // ------------------------------------------------------------------

    private void ingest(PlayerData data, Player player, ActionType actionType, long now,
                        java.util.function.Consumer<ActionRecord> fill) {
        ActionRecord rec = data.timeline.begin(actionType, now, data.ping.medianPing());
        if (fill != null) {
            fill.accept(rec);
        }
        if (plugin.configs().debugTimeline()) {
            plugin.getLogger().info("[timeline] " + data.name() + " " + actionType
                    + " ping=" + rec.pingSnapshot + "ms");
        }
        if (!isExempt(data, now)) {
            refreshLag(data, now);
            plugin.checkManager().dispatchAction(data, rec);
        }
    }

    private boolean isExempt(PlayerData data, long now) {
        return plugin.exemptions().isExempt(data, now);
    }

    private void refreshLag(PlayerData data, long now) {
        var cfg = plugin.configs();
        boolean recentTeleport = data.lastTeleportNanos > 0
                && now - data.lastTeleportNanos < cfg.postTeleportGraceNanos();
        boolean recentRespawn = data.lastRespawnNanos > 0
                && now - data.lastRespawnNanos < cfg.postRespawnGraceNanos();
        data.ping.setSpikeThresholdMs(cfg.pingSpikeThresholdMs());
        boolean unstablePing = data.ping.jitterMillis() > cfg.maxPingJitterMs()
                || data.ping.spikedWithin(now, cfg.pingSpikeGraceNanos());
        data.lagContext.refresh(data.ping.medianPing(), plugin.tps().mspt(),
                recentTeleport, recentRespawn, cfg.maxMspt(), unstablePing);
    }

    /**
     * Allowed reach for an attack, raised above the vanilla limit for a spear
     * (high-reach weapon) or creative mode, so neither becomes a false flag.
     */
    private double effectiveReachLimit(Player player, Material held) {
        var cfg = plugin.configs();
        double limit = cfg.reachSurvivalLimit();
        if (SPEAR != null && held == SPEAR) {
            limit = Math.max(limit, cfg.reachSpearLimit());
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            limit = Math.max(limit, cfg.reachCreativeLimit());
        }
        return limit;
    }

    /** Track the player's own vertical velocity from position-bearing flying packets. */
    private static void updateVelocity(PlayerData data, double x, double y, double z) {
        if (data.hasPos) {
            data.verticalVelocity = y - data.lastPosY;
        }
        data.lastPosX = x;
        data.lastPosY = y;
        data.lastPosZ = z;
        data.hasPos = true;
    }

    private static Material mainHand(Player player) {
        return player.getInventory().getItemInMainHand().getType();
    }

    /** Angle in degrees between the player's crosshair and a world point from a given eye. */
    private static float angleTo(PlayerData data, double eyeX, double eyeY, double eyeZ,
                                 double x, double y, double z) {
        if (data.rotations.size() == 0) {
            return -1f;
        }
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            return 0f;
        }
        double yawRad = Math.toRadians(data.rotations.yaw(0));
        double pitchRad = Math.toRadians(data.rotations.pitch(0));
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        double dot = (dx * lookX + dy * lookY + dz * lookZ) / len;
        return (float) Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }
}
