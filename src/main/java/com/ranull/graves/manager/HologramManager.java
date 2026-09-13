package com.ranull.graves.manager;

import com.ranull.graves.Graves;
import com.ranull.graves.data.EntityData;
import com.ranull.graves.data.HologramData;
import com.ranull.graves.type.Grave;
import dev.cwhead.GravesX.keys.GraveHologramKeys;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The HologramManager class is responsible for managing holograms associated with graves.
 */
public class HologramManager extends EntityDataManager {
    private static final String HOLOGRAM_TAG = "graveHologram";
    private static final String GRAVE_UUID_TAG_PREFIX = "graveHologramGraveUUID:";

    private final Graves plugin;

    public HologramManager(Graves plugin) {
        super(plugin);
        this.plugin = plugin;
    }

    /**
     * Creates a hologram at the specified location for a given grave.
     *
     * @param location the hologram location
     * @param grave    the grave
     */
    public void createHologram(Location location, Grave grave) {
        if (grave == null) {
            plugin.debugMessage("[Holograms] createHologram skipped: grave is null", 1);
            return;
        }

        plugin.debugMessage("[Holograms] Creating hologram for grave=" + grave.getUUID()
                + " at " + (location != null ? toLocKey(location) : "null"), 1);

        if (plugin.getVersionManager().isHasTextDisplays()) {
            plugin.debugMessage("[Holograms] Using TextDisplay backend for grave=" + grave.getUUID(), 2);
            plugin.getTextDisplayManager().createHologram(location, grave);
        } else {
            plugin.debugMessage("[Holograms] Using ArmorStand backend for grave=" + grave.getUUID(), 2);
            plugin.getArmorStandManager().createHologram(location, grave);
        }
    }

    /**
     * Removes all holograms associated with a grave.
     * <p>
     * This is the only supported public removal entrypoint.
     * </p>
     *
     * @param grave the grave whose holograms should be removed
     */
    public void removeHologram(Grave grave) {
        if (grave == null) {
            plugin.debugMessage("[Holograms] removeHologram(grave) skipped: grave is null", 1);
            return;
        }

        if (grave.getUUID() == null) {
            plugin.debugMessage("[Holograms] removeHologram(grave) skipped: grave UUID is null", 1);
            return;
        }

        plugin.debugMessage("[Holograms] removeHologram(grave=" + grave.getUUID() + ") starting", 1);

        List<HologramData> holograms = new java.util.ArrayList<>();
        for (EntityData entityData : plugin.getCacheManager().getEntityMap().values()) {
            if (entityData instanceof HologramData hologramData
                    && grave.getUUID().equals(hologramData.getUUIDGrave())) {
                holograms.add(hologramData);
            }
        }

        if (holograms.isEmpty()) {
            // The cache may already have been cleared; recover any remaining rows from storage.
            plugin.getDataManager().removeHologramData(grave);
        } else {
            for (HologramData hologramData : holograms) {
                removeTrackedHologram(hologramData);
            }
        }

        plugin.debugMessage("[Holograms] removeHologram(grave=" + grave.getUUID() + ") finished dispatch", 1);
    }

    /**
     * Loads the stored chunk, removes the matching physical hologram, and only
     * then deletes its cache/database record.
     */
    public void removeTrackedHologram(HologramData hologramData) {
        if (hologramData == null || hologramData.getUUIDEntity() == null) {
            return;
        }

        plugin.getSchedulerManager().runTask(() -> {
            Location location = hologramData.getLocation();
            if (location == null || location.getWorld() == null) {
                plugin.getLogger().warning("Unable to load hologram location for entity "
                        + hologramData.getUUIDEntity() + "; keeping its database record for retry.");
                return;
            }

            boolean scheduled = plugin.getChunkManager().ensureLoadedAndExecute(
                    location, location, false, false,
                    () -> removeTrackedHologramInLoadedChunk(hologramData)
            );

            if (!scheduled) {
                plugin.getLogger().warning("Unable to schedule hologram removal for entity "
                        + hologramData.getUUIDEntity() + "; keeping its database record for retry.");
            }
        });
    }

    private void removeTrackedHologramInLoadedChunk(HologramData hologramData) {
        Location location = hologramData.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        Chunk chunk = location.getChunk();
        UUID graveUuid = hologramData.getUUIDGrave();
        UUID entityUuid = hologramData.getUUIDEntity();
        int removed = 0;

        for (Entity entity : chunk.getEntities()) {
            if (!isGravesHologram(entity)) {
                continue;
            }

            UUID taggedGraveUuid = getTaggedGraveUuid(entity);
            if (entityUuid.equals(entity.getUniqueId())
                    || (graveUuid != null && graveUuid.equals(taggedGraveUuid))) {
                entity.remove();
                removed++;
            }
        }

        plugin.debugMessage("[Holograms] Removed " + removed + " physical hologram(s) for grave="
                + graveUuid + " in chunk " + chunk.getX() + "," + chunk.getZ(), 1);
        plugin.getDataManager().deleteHologramData(hologramData);
    }

    /**
     * Removes GravesX holograms in a loaded chunk when their grave no longer exists.
     */
    public int purgeOrphanedHolograms(Chunk chunk) {
        if (chunk == null || !plugin.getDataManager().isGraveMapLoaded()) {
            return 0;
        }

        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!isGravesHologram(entity)) {
                continue;
            }

            UUID graveUuid = getTaggedGraveUuid(entity);
            if (graveUuid != null && plugin.getCacheManager().getGraveMap().containsKey(graveUuid)) {
                continue;
            }

            UUID entityUuid = entity.getUniqueId();
            entity.remove();
            plugin.getDataManager().deleteHologramData(entityUuid);
            removed++;
        }

        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " orphaned grave hologram(s) from "
                    + chunk.getWorld().getName() + " chunk " + chunk.getX() + "," + chunk.getZ() + ".");
        }
        return removed;
    }

    private boolean isGravesHologram(Entity entity) {
        if (!(entity instanceof TextDisplay) && !(entity instanceof ArmorStand)) {
            return false;
        }

        try {
            if (entity.getScoreboardTags().contains(HOLOGRAM_TAG)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            return pdc.has(GraveHologramKeys.GRAVE_UUID, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private UUID getTaggedGraveUuid(Entity entity) {
        try {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            String value = pdc.get(GraveHologramKeys.GRAVE_UUID, PersistentDataType.STRING);
            if (value != null) {
                return UUID.fromString(value);
            }
        } catch (Throwable ignored) {
        }

        try {
            for (String tag : entity.getScoreboardTags()) {
                if (tag.startsWith(GRAVE_UUID_TAG_PREFIX)) {
                    return UUID.fromString(tag.substring(GRAVE_UUID_TAG_PREFIX.length()));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * @deprecated Use {@link #removeHologram(Grave)} instead.
     *
     * @param entityDataList the old entity-data list removal input
     */
    @Deprecated(forRemoval = true)
    public void removeHologram(List<EntityData> entityDataList) {
        String message = "HologramManager#removeHologram(List<EntityData>) is no longer supported. "
                + "Use removeHologram(Grave) instead.";

        plugin.getLogger().severe(message + " size=" + (entityDataList != null ? entityDataList.size() : 0));
        plugin.debugMessage("[Holograms] " + message, 1);

        throw new UnsupportedOperationException(message);
    }

    /**
     * @deprecated Use {@link #removeHologram(Grave)} instead.
     *
     * @param entityData the old entity-data removal input
     */
    @Deprecated(forRemoval = true)
    public void removeHologram(EntityData entityData) {
        String message = "HologramManager#removeHologram(EntityData) is no longer supported. "
                + "Use removeHologram(Grave) instead.";

        plugin.getLogger().severe(message + " entity=" + (entityData != null ? entityData.getUUIDEntity() : "null"));
        plugin.debugMessage("[Holograms] " + message, 1);

        throw new UnsupportedOperationException(message);
    }

    /**
     * @deprecated Use {@link #removeHologram(Grave)} instead.
     *
     * @param entityDataMap the old entity/entity-data map removal input
     */
    @Deprecated(forRemoval = true)
    public void removeHologram(Map<EntityData, Entity> entityDataMap) {
        String message = "HologramManager#removeHologram(Map<EntityData, Entity>) is no longer supported. "
                + "Use removeHologram(Grave) instead.";

        plugin.getLogger().severe(message + " size=" + (entityDataMap != null ? entityDataMap.size() : 0));
        plugin.debugMessage("[Holograms] " + message, 1);

        throw new UnsupportedOperationException(message);
    }

    /**
     * Purges lingering hologram entities that appear to belong to GravesX.
     */
    public void purgeLingeringHolograms() {
        plugin.debugMessage("[Cleanup] Starting hologram purge dispatch", 1);

        if (plugin.getVersionManager().isHasTextDisplays()) {
            plugin.debugMessage("[Cleanup] Purging TextDisplay and ArmorStand holograms", 2);
            plugin.getTextDisplayManager().purgeLingeringHolograms();
            plugin.getArmorStandManager().purgeLingeringHolograms();
        } else {
            plugin.debugMessage("[Cleanup] Purging ArmorStand holograms only", 2);
            plugin.getArmorStandManager().purgeLingeringHolograms();
        }

        plugin.debugMessage("[Cleanup] Finished hologram purge dispatch", 1);
    }

    /**
     * Returns the cached grave by UUID (if present).
     *
     * @param graveUUID the grave UUID
     * @return the grave, or null if not cached
     */
    public Grave hasGrave(UUID graveUUID) {
        if (graveUUID == null) {
            plugin.debugMessage("[Holograms] hasGrave lookup skipped: graveUUID is null", 2);
            return null;
        }

        Grave grave = plugin.getCacheManager().getGraveMap().get(graveUUID);

        plugin.debugMessage("[Holograms] hasGrave lookup for " + graveUUID + " -> "
                + (grave != null ? "hit" : "miss"), 3);

        return grave;
    }

    private String toLocKey(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }

        return loc.getWorld().getName() + ":"
                + loc.getBlockX() + ":"
                + loc.getBlockY() + ":"
                + loc.getBlockZ();
    }
}
