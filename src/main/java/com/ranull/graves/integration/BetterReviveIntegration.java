package com.ranull.graves.integration;

import com.ranull.graves.Graves;
import com.ranull.graves.type.Grave;
import dev.cwhead.GravesX.event.GraveCreateEvent;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Preserves original death attribution only for confirmed BetterRevive deaths. */
public final class BetterReviveIntegration implements Listener {
    private static final String BR_META_ENSURE_FATAL_DAMAGE = "betterrevive-ensure-fatal-damage";
    private static final String BR_META_PLAYER_BLED_OUT = "betterrevive-player-bled-out";
    private static final String BR_META_PLAYER_GAVE_UP = "betterrevive-player-gave-up";
    private static final String BR_META_BLED_OUT_MESSAGE = "betterrevive-bled-out-message";
    private static final long BETTER_REVIVE_SNAPSHOT_MAX_AGE_MS = 300_000L;
    private static final long BETTER_REVIVE_MESSAGE_SNAPSHOT_MAX_AGE_MS = 30_000L;

    private final Graves plugin;
    private final Plugin betterRevive;
    private final Map<UUID, Long> finalDamageSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, LastDamageSnapshot> betterReviveCauseSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, BetterReviveMessageSnapshot> betterReviveMessageSnapshots = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class LastDamageSnapshot {
        private final EntityDamageEvent.DamageCause cause;
        private final UUID killerUUID;
        private final EntityType killerType;
        private final String killerName;
        private final long timestamp;

        private LastDamageSnapshot(EntityDamageEvent.DamageCause cause, UUID killerUUID, EntityType killerType,
                                   String killerName, long timestamp) {
            this.cause = cause;
            this.killerUUID = killerUUID;
            this.killerType = killerType;
            this.killerName = killerName;
            this.timestamp = timestamp;
        }
    }

    private static final class BetterReviveMessageSnapshot {
        private final String message;
        private final long timestamp;

        private BetterReviveMessageSnapshot(String message, long timestamp) {
            this.message = message;
            this.timestamp = timestamp;
        }
    }


    public BetterReviveIntegration(Graves plugin, Plugin betterRevive) {
        this.plugin = plugin;
        this.betterRevive = betterRevive;
    }

    private boolean isActive() {
        return betterRevive.isEnabled()
                && plugin.getConfig().getBoolean("settings.integration.betterrevive.enabled", true);
    }

    private boolean hasOwnedMetadata(Player player, String key) {
        return player.getMetadata(key).stream()
                .anyMatch(value -> value.getOwningPlugin() == betterRevive);
    }

    public void unregisterListeners() {
        HandlerList.unregisterAll(this);
        betterReviveCauseSnapshots.clear();
        betterReviveMessageSnapshots.clear();
        finalDamageSnapshots.clear();
    }

    /**
     * Cache the last "real" damage cause for players so GravesX can preserve the original cause
     * when BetterRevive later applies fatal plugin damage (give up / bled out).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityDamageSnapshot(EntityDamageEvent event) {
        if (!isActive() || !(event.getEntity() instanceof Player player)) {
            return;
        }

        if (isBetterReviveFinalState(player)) {
            finalDamageSnapshots.put(player.getUniqueId(), System.currentTimeMillis());
        }
        if (shouldIgnoreDamageSnapshot(player, event)) {
            return;
        }

        UUID killerUUID = null;
        EntityType killerType = null;
        String killerName = null;

        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            Entity damager = byEntityEvent.getDamager();
            killerUUID = damager.getUniqueId();
            killerType = damager.getType();
            killerName = plugin.getEntityManager().getEntityName(damager);
        }

        betterReviveCauseSnapshots.put(
                player.getUniqueId(),
                new LastDamageSnapshot(event.getCause(), killerUUID, killerType, killerName, System.currentTimeMillis())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuitCleanup(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawnCleanup(PlayerRespawnEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    private void clearPlayer(UUID playerUUID) {
        finalDamageSnapshots.remove(playerUUID);
        betterReviveCauseSnapshots.remove(playerUUID);
        betterReviveMessageSnapshots.remove(playerUUID);
    }

    /** Capture final-state evidence before BetterRevive clears its death metadata. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerDeathPreCapture(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isActive()) return;
        if (isBetterReviveFinalState(player)) {
            finalDamageSnapshots.put(player.getUniqueId(), System.currentTimeMillis());
        }
        if (!hasOwnedMetadata(player, BR_META_BLED_OUT_MESSAGE)) {
            return;
        }

        List<MetadataValue> metadataValues = player.getMetadata(BR_META_BLED_OUT_MESSAGE);
        if (metadataValues.isEmpty()) {
            return;
        }

        String message = metadataValues.stream()
                .filter(value -> value.getOwningPlugin() == betterRevive)
                .map(MetadataValue::asString).findFirst().orElse("");
        if (message == null || message.isBlank()) {
            return;
        }

        betterReviveMessageSnapshots.put(
                player.getUniqueId(),
                new BetterReviveMessageSnapshot(message, System.currentTimeMillis())
        );
    }

    private boolean shouldIgnoreDamageSnapshot(Player player, EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.CUSTOM) {
            return true;
        }

        // BetterRevive finalization damage should never overwrite the original cause snapshot.
        return hasOwnedMetadata(player, BR_META_ENSURE_FATAL_DAMAGE)
                || hasOwnedMetadata(player, BR_META_PLAYER_BLED_OUT)
                || hasOwnedMetadata(player, BR_META_PLAYER_GAVE_UP);
    }

    private boolean isBetterReviveFinalState(Player player) {
        return hasOwnedMetadata(player, BR_META_PLAYER_BLED_OUT)
                || hasOwnedMetadata(player, BR_META_PLAYER_GAVE_UP)
                || hasOwnedMetadata(player, BR_META_ENSURE_FATAL_DAMAGE);
    }

    private void applySnapshotAsKiller(GraveCreateEvent graveCreateEvent, Grave grave, LastDamageSnapshot snapshot) {
        if (snapshot.killerType != null && snapshot.killerName != null) {
            graveCreateEvent.setKillerUUID(snapshot.killerUUID);
            graveCreateEvent.setKillerType(snapshot.killerType);
            graveCreateEvent.setKillerName(snapshot.killerName);
            graveCreateEvent.setKillerNameDisplay(snapshot.killerName);
            return;
        }

        if (snapshot.cause != null) {
            String damageReason = plugin.getGraveManager().getDamageReason(snapshot.cause, grave);
            graveCreateEvent.setKillerUUID(null);
            graveCreateEvent.setKillerType(null);
            graveCreateEvent.setKillerName(damageReason);
            graveCreateEvent.setKillerNameDisplay(damageReason);
        }
    }


    public boolean applyKiller(GraveCreateEvent graveCreateEvent, Grave grave, LivingEntity livingEntity) {
        if (livingEntity instanceof Player player && isActive()) {
            UUID playerUUID = player.getUniqueId();
            LastDamageSnapshot snapshot = betterReviveCauseSnapshots.remove(playerUUID);
            BetterReviveMessageSnapshot messageSnapshot = betterReviveMessageSnapshots.remove(playerUUID);

            boolean finalStateMetadata = isBetterReviveFinalState(player);
            Long finalDamageTime = finalDamageSnapshots.remove(playerUUID);
            boolean observedFinalDamage = finalDamageTime != null
                    && System.currentTimeMillis() - finalDamageTime <= BETTER_REVIVE_MESSAGE_SNAPSHOT_MAX_AGE_MS;

            boolean freshMessage = messageSnapshot != null
                    && System.currentTimeMillis() - messageSnapshot.timestamp <= BETTER_REVIVE_MESSAGE_SNAPSHOT_MAX_AGE_MS;
            boolean freshSnapshot = snapshot != null
                    && (System.currentTimeMillis() - snapshot.timestamp) <= BETTER_REVIVE_SNAPSHOT_MAX_AGE_MS;
            boolean meaningfulSnapshot = freshSnapshot
                    && snapshot.cause != null
                    && snapshot.cause != EntityDamageEvent.DamageCause.MAGIC
                    && snapshot.cause != EntityDamageEvent.DamageCause.CUSTOM;

            if (meaningfulSnapshot && (finalStateMetadata || observedFinalDamage || freshMessage)) {
                applySnapshotAsKiller(graveCreateEvent, grave, snapshot);
                return true;
            }

            if (freshMessage) {
                graveCreateEvent.setKillerUUID(null);
                graveCreateEvent.setKillerType(null);
                graveCreateEvent.setKillerName(messageSnapshot.message);
                graveCreateEvent.setKillerNameDisplay(messageSnapshot.message);
                return true;
            }
        }


        return false;
    }
}
