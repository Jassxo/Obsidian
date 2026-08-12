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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.CrystalTracker;
import dev.obsidian.core.tracker.PlayerData;
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
            int entityId = interact.getEntityId();
            CrystalTracker.CrystalRecord crystal = plugin.crystals().getCrystal(entityId);
            if (crystal == null) {
                return;
            }
            data.lastCombatNanos = now; // fighting crystals is combat
            float angle = angleToPoint(player, data, crystal.x, crystal.y + 1.0, crystal.z);
            ingest(data, player, ActionType.CRYSTAL_ATTACK, now, rec -> {
                rec.targetEntityId = entityId;
                rec.angleToTarget = angle;
            });
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
            ingest(data, player, ActionType.USE_ITEM, now, null);
            return;
        }

        if (type == PacketType.Play.Client.ANIMATION) {
            ingest(data, player, ActionType.SWING, now, null);
        }
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
            if (spawn.getEntityType() != EntityTypes.END_CRYSTAL) {
                return;
            }
            Vector3d pos = spawn.getPosition();
            plugin.crystals().onCrystalSpawn(spawn.getEntityId(), pos.getX(), pos.getY(), pos.getZ(), now);
            // Per-viewer spawn time: this is the moment the crystal became
            // visible to THIS player, the basis of the spawn-reaction check.
            data.crystalSeenNanos.put(spawn.getEntityId(), now);
            if (data.crystalSeenNanos.size() > 256) {
                data.crystalSeenNanos.clear(); // leak guard; entries normally die with the crystal
            }
            return;
        }

        if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(event);
            for (int entityId : destroy.getEntityIds()) {
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
        boolean recentTeleport = data.lastTeleportNanos > 0
                && now - data.lastTeleportNanos < plugin.configs().postTeleportGraceNanos();
        boolean recentRespawn = data.lastRespawnNanos > 0
                && now - data.lastRespawnNanos < plugin.configs().postRespawnGraceNanos();
        data.lagContext.refresh(data.ping.medianPing(), plugin.tps().mspt(),
                recentTeleport, recentRespawn, plugin.configs().maxMspt());
    }

    private static Material mainHand(Player player) {
        return player.getInventory().getItemInMainHand().getType();
    }

    /** Angle in degrees between the player's crosshair and a world point. */
    private static float angleToPoint(Player player, PlayerData data, double x, double y, double z) {
        if (data.rotations.size() == 0) {
            return -1f;
        }
        Location loc = player.getLocation();
        double dx = x - loc.getX();
        double dy = y - (loc.getY() + player.getEyeHeight());
        double dz = z - loc.getZ();
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
