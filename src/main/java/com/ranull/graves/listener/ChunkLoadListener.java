package com.ranull.graves.listener;

import com.ranull.graves.Graves;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Cleans persistent GravesX holograms as their chunks return to memory.
 */
public class ChunkLoadListener implements Listener {
    private static final int MAX_READY_RETRIES = 20;

    private final Graves plugin;

    public ChunkLoadListener(Graves plugin) {
        this.plugin = plugin;
    }

    public void scheduleInitialSweep() {
        scheduleLoadedChunkSweep(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        purgeChunkWhenReady(event.getChunk(), 0);
    }

    private void scheduleLoadedChunkSweep(int attempt) {
        if (!plugin.getDataManager().isGraveMapLoaded()) {
            if (attempt < MAX_READY_RETRIES) {
                plugin.getSchedulerManager().runTaskLater(
                        () -> scheduleLoadedChunkSweep(attempt + 1), 20L
                );
            }
            return;
        }

        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Location anchor = chunk.getBlock(0, world.getMinHeight(), 0).getLocation();
                plugin.getSchedulerManager().execute(anchor,
                        () -> plugin.getHologramManager().purgeOrphanedHolograms(chunk));
            }
        }
    }

    private void purgeChunkWhenReady(Chunk chunk, int attempt) {
        if (plugin.getDataManager().isGraveMapLoaded()) {
            plugin.getHologramManager().purgeOrphanedHolograms(chunk);
            return;
        }

        if (attempt >= MAX_READY_RETRIES) {
            return;
        }

        Location anchor = chunk.getBlock(0, chunk.getWorld().getMinHeight(), 0).getLocation();
        plugin.getSchedulerManager().runTaskLater(anchor,
                () -> purgeChunkWhenReady(chunk, attempt + 1), 20L);
    }
}
