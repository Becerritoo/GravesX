package com.ranull.graves.integration;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.TownyPermission.ActionType;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import com.ranull.graves.Graves;
import com.ranull.graves.type.Grave;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/** Bounded, loaded-chunk-only relocation for graves denied by Towny. */
public class TownyIntegration {
    private static final String CONFIG = "settings.integration.towny.";
    private final Graves plugin;
    private final TownyAPI api;

    public TownyIntegration(Graves plugin) {
        this(plugin, TownyAPI.getInstance());
    }

    TownyIntegration(Graves plugin, TownyAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    public record Placement(Location location, boolean relocated) {}

    public record DeathSnapshot(List<ItemStack> drops, int droppedExp, boolean keepLevel,
                                int newExp, int newLevel, int newTotalExp) {
        public static DeathSnapshot capture(PlayerDeathEvent event) {
            return new DeathSnapshot(event.getDrops().stream().map(ItemStack::clone).toList(),
                    event.getDroppedExp(), event.getKeepLevel(), event.getNewExp(),
                    event.getNewLevel(), event.getNewTotalExp());
        }

        public void restore(PlayerDeathEvent event) {
            event.getDrops().clear();
            drops.forEach(item -> event.getDrops().add(item.clone()));
            event.setDroppedExp(droppedExp);
            event.setKeepLevel(keepLevel);
            event.setNewExp(newExp);
            event.setNewLevel(newLevel);
            event.setNewTotalExp(newTotalExp);
        }
    }

    Material graveMaterial(Grave grave) {
        Material material = Material.matchMaterial(plugin.getConfigManager()
                .getConfigSection("block.material", grave).getString("block.material", "PLAYER_HEAD"));
        return material == null ? Material.PLAYER_HEAD : material;
    }

    Location blockLocation(Location anchor, Grave grave) {
        Location block = anchor.clone();
        for (String axis : new String[] {"x", "y", "z"}) {
            String key = "block.offset." + axis;
            int value = plugin.getConfigManager().getConfigSection(key, grave).getInt(key);
            if (axis.equals("x")) block.add(value, 0, 0);
            if (axis.equals("y")) block.add(0, value, 0);
            if (axis.equals("z")) block.add(0, 0, value);
        }
        return block;
    }

    private boolean canAccessGrave(Player player, Location anchor, Material material, Grave grave) {
        return canAccess(player, anchor, material)
                && canAccess(player, blockLocation(anchor, grave), material);
    }

    /** Recheck event-modified destinations before persisting inventory or dropping split money. */
    public boolean validateFinal(Player player, Location death, Location target, Grave grave, boolean relocated) {
        try {
            if (target == null || !death.getWorld().equals(target.getWorld())) return false;
            if (!canAccessGrave(player, target, graveMaterial(grave), grave)) return false;
            if (!relocated) return true;
            UUID town = townAt(target);
            int radius = limit("search-radius", 64, 1, 128);
            if (Math.abs(target.getBlockX() - death.getBlockX()) > radius
                    || Math.abs(target.getBlockZ() - death.getBlockZ()) > radius) return false;
            return (town == null || town.equals(townAt(death))) && safeCandidate(player, target, grave);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Towny grave destination validation failed: " + exception.getMessage());
            return false;
        }
    }

    /** A null location means no permitted destination was found within the budget. */
    public Placement resolve(Player player, Location death, Location proposed, Grave grave) {
        try {
            Material material = graveMaterial(grave);
            boolean originAllowed = canAccess(player, death, material);
            if (canAccessGrave(player, proposed, material, grave)) {
                if (originAllowed) return new Placement(proposed, false);
                if (validateFinal(player, death, proposed, grave, true)) return new Placement(proposed, true);
            }

            World world = death.getWorld();
            if (world == null) return new Placement(null, false);
            UUID originTown = townAt(death);
            int radius = limit("search-radius", 64, 1, 128);
            int vertical = limit("vertical-range", 8, 0, 32);
            int maxChecks = limit("max-block-checks", 4096, 1, 32768);
            int maxColumns = limit("max-columns", 4096, 1, 16384);
            int maxChunks = limit("max-chunks", 16, 1, 64);
            long deadline = nanoTime() + limit("max-search-ms", 10, 1, 50) * 1_000_000L;
            Set<Long> chunks = new HashSet<>();
            int checks = 0;
            int columns = 0;
            Location wilderness = null;

            // Expanding square rings prefer nearby columns; retain wilderness as a fallback.
            search:
            for (int ring = 0; ring <= radius; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        if (++columns > maxColumns || nanoTime() >= deadline) break search;
                        int x = death.getBlockX() + dx;
                        int z = death.getBlockZ() + dz;
                        int cx = x >> 4;
                        int cz = z >> 4;
                        if (!world.isChunkLoaded(cx, cz)) continue;
                        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                        if (!chunks.contains(key) && chunks.size() >= maxChunks) continue;
                        chunks.add(key);
                        Location column = new Location(world, x, death.getBlockY(), z);
                        UUID town = townAt(column);
                        if (town != null && !town.equals(originTown)) continue;
                        if (!canAccessGrave(player, column, material, grave)) continue;

                        height:
                        for (int dy = 0; dy <= vertical; dy++) {
                            for (int sign : new int[] {1, -1}) {
                                if (dy == 0 && sign == -1) continue;
                                if (++checks > maxChecks || nanoTime() >= deadline) break search;
                                Location candidate = column.clone().add(0, dy * sign, 0);
                                Location block = blockLocation(candidate, grave);
                                int bx = block.getBlockX() >> 4;
                                int bz = block.getBlockZ() >> 4;
                                long blockKey = ((long) bx << 32) ^ (bz & 0xffffffffL);
                                if (!world.isChunkLoaded(bx, bz)) continue;
                                if (!chunks.contains(blockKey) && chunks.size() >= maxChunks) continue;
                                chunks.add(blockKey);
                                UUID blockTown = townAt(block);
                                if (blockTown != null && !blockTown.equals(originTown)) continue;
                                if (!safeCandidate(player, candidate, grave)) continue;
                                if (town != null && town.equals(originTown)) {
                                    return new Placement(candidate, true);
                                }
                                if (wilderness == null || candidate.distanceSquared(death) < wilderness.distanceSquared(death)) {
                                    wilderness = candidate;
                                }
                                break height;
                            }
                        }
                    }
                }
            }
            return new Placement(wilderness, wilderness != null);
        } catch (RuntimeException exception) {
            // A protection failure must not turn into permission to place a grave.
            plugin.getLogger().warning("Towny grave relocation failed: " + exception.getMessage());
            return new Placement(null, false);
        }
    }

    private int limit(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt(CONFIG + key, fallback)));
    }

    long nanoTime() {
        return System.nanoTime();
    }

    UUID townAt(Location location) {
        return api.getTownUUID(location);
    }

    boolean canAccess(Player player, Location location, Material material) {
        if (location == null || location.getWorld() == null) return false;
        var townyWorld = api.getTownyWorld(location.getWorld());
        if (townyWorld == null || !townyWorld.isUsingTowny()) return true;
        return PlayerCacheUtil.getCachePermission(player, location, material, ActionType.BUILD)
                && PlayerCacheUtil.getCachePermission(player, location, material, ActionType.DESTROY)
                && PlayerCacheUtil.getCachePermission(player, location, material, ActionType.SWITCH);
    }

    boolean safeCandidate(Player player, Location location, Grave grave) {
        Location block = blockLocation(location, grave);
        return safeBlock(player, location, grave)
                && (block.equals(location) || safeBlock(player, block, grave));
    }

    private boolean safeBlock(Player player, Location location, Grave grave) {
        World world = location.getWorld();
        if (world == null || location.getBlockY() <= world.getMinHeight()
                || location.getBlockY() + 1 >= world.getMaxHeight()) return false;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        if (!location.getBlock().getType().isAir()
                || !location.clone().add(0, 1, 0).getBlock().getType().isAir()) return false;
        if (!plugin.getSafeLocationManager().isLocationSafeGrave(location)
                || plugin.getLocationManager().hasCachedGraveAt(location)) return false;
        if (!plugin.getConfigManager().getConfigSection("placement.nether-roof", grave)
                .getBoolean("placement.nether-roof")
                && plugin.getSafeLocationManager().isAboveNetherRoof(location, grave)) return false;
        // Respect other protection plugins even if the ordinary placement checks are disabled.
        return plugin.getCompatibility().canBuild(player, location, plugin)
                && (!plugin.getIntegrationManager().hasProtectionLib()
                || plugin.getIntegrationManager().getProtectionLib().canBuild(location, player));
    }

    /** Called before item filtering, physical-money splitting or inventory mutation. */
    public void handleFailure(PlayerDeathEvent event) {
        if (plugin.getConfig().getBoolean(CONFIG + "failure-keep-inventory", true)) {
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            event.getDrops().clear();
            event.getEntity().sendMessage(plugin.getConfig().getString(CONFIG + "message-failure-kept",
                    "No accessible grave location was found. Your inventory was preserved."));
        } else {
            event.getEntity().sendMessage(plugin.getConfig().getString(CONFIG + "message-failure-dropped",
                    "No accessible grave location was found. Your items will drop at your death location."));
        }
    }

    public void notifyRelocation(Player player, Location location) {
        String message = plugin.getConfig().getString(CONFIG + "message-relocated",
                "Your grave was moved outside a restricted plot to {world}: {x}, {y}, {z}.");
        player.sendMessage(message.replace("{world}", location.getWorld().getName())
                .replace("{x}", Integer.toString(location.getBlockX()))
                .replace("{y}", Integer.toString(location.getBlockY()))
                .replace("{z}", Integer.toString(location.getBlockZ())));
    }
}
