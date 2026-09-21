package dev.jarochitoland.gravesx.towny;

import dev.cwhead.GravesX.event.GravePreCreateEvent;
import dev.cwhead.GravesX.module.GravesXModule;
import dev.cwhead.GravesX.module.ModuleContext;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TownyModule extends GravesXModule implements Listener {
    private ModuleContext context;
    private TownyBridge towny;

    @Override
    public void onModuleLoad(ModuleContext ctx) {
        this.context = ctx;
        ctx.saveDefaultConfig();
    }

    @Override
    public void onModuleEnable(ModuleContext ctx) {
        this.context = ctx;
        this.towny = TownyBridge.create();
        if (!towny.available()) {
            ctx.getLogger().warning("[GravesX-Towny] Towny API bridge is unavailable; placement checks are disabled.");
        }
        ctx.registerListener(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreCreate(GravePreCreateEvent event) {
        FileConfiguration config = context.getConfig();
        if (!config.getBoolean("enabled", true)) return;
        if (towny == null || !towny.available()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.hasPermission(config.getString("bypass-permission", "graves.towny.bypass"))) return;

        Location death = event.getLocation();
        if (death == null) death = event.getEntity().getLocation();
        Location current = event.getLocationDeath();
        if (current == null) current = death;

        Material material = Material.matchMaterial(config.getString("grave-material", "PLAYER_HEAD"));
        if (material == null) material = Material.PLAYER_HEAD;

        if (canPlace(player, current, material, config)) {
            debug(config, "Allowed original grave location for " + player.getName() + " at " + describe(current));
            return;
        }

        Location relocated = findAllowedLocation(player, death, current, material, config);
        if (relocated == null) {
            debug(config, "No Towny-safe relocation found for " + player.getName()
                    + " death=" + describe(death) + " proposed=" + describe(current)
                    + "; leaving GravesX default placement active.");
            return;
        }

        event.setDeathLocation(relocated);
        debug(config, "Relocated grave for " + player.getName() + " from " + describe(current)
                + " to " + describe(relocated));
        if (config.getBoolean("send-relocated-message", true)) {
            send(player, formatLocation(config.getString("messages.relocated",
                    "&eTu tumba fue movida a una ubicacion permitida por Towny: &f%world% %x% %y% %z%"), relocated));
        }
    }

    private Location findAllowedLocation(Player player, Location death, Location current, Material material, FileConfiguration config) {
        World world = death != null && death.getWorld() != null ? death.getWorld() : current.getWorld();
        if (world == null) return null;

        UUID originTown = townAt(death);
        SearchBudget budget = new SearchBudget(
                clamp(config.getInt("max-block-checks", 8192), 1, 65536),
                clamp(config.getInt("max-chunks", 32), 1, 128)
        );

        for (Location center : searchCenters(death, current)) {
            Location found = searchAround(player, center, originTown, material, config, budget);
            if (found != null) return found;
        }

        return null;
    }

    private Location searchAround(Player player, Location center, UUID originTown, Material material,
                                  FileConfiguration config, SearchBudget budget) {
        World world = center.getWorld();
        if (world == null) return null;

        int radius = clamp(config.getInt("search-radius", 64), 1, 128);
        int vertical = clamp(config.getInt("vertical-range", 24), 0, 64);
        boolean allowOtherTowns = config.getBoolean("allow-other-towns", false);

        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;

                    int x = center.getBlockX() + dx;
                    int z = center.getBlockZ() + dz;
                    if (!rememberLoadedChunk(world, x, z, budget.chunks, budget.maxChunks)) continue;

                    Location column = new Location(world, x, center.getBlockY(), z);
                    UUID candidateTown = townAt(column);
                    if (!allowOtherTowns && candidateTown != null && originTown != null
                            && !candidateTown.equals(originTown)) continue;

                    for (int dy = 0; dy <= vertical; dy++) {
                        for (int sign : new int[] {1, -1}) {
                            if (dy == 0 && sign < 0) continue;
                            if (++budget.checks > budget.maxChecks) return null;

                            Location candidate = column.clone().add(0, dy * sign, 0);
                            if (!rememberLoadedChunk(world, candidate.getBlockX(), candidate.getBlockZ(),
                                    budget.chunks, budget.maxChunks)) continue;
                            if (isSafe(candidate) && canPlace(player, candidate, material, config)) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private List<Location> searchCenters(Location death, Location current) {
        List<Location> centers = new ArrayList<>();
        addCenter(centers, death);
        addCenter(centers, current);
        return centers;
    }

    private void addCenter(List<Location> centers, Location location) {
        if (location == null || location.getWorld() == null) return;
        for (Location existing : centers) {
            if (existing.getWorld().equals(location.getWorld())
                    && existing.getBlockX() == location.getBlockX()
                    && existing.getBlockY() == location.getBlockY()
                    && existing.getBlockZ() == location.getBlockZ()) {
                return;
            }
        }
        centers.add(location);
    }

    private boolean canPlace(Player player, Location location, Material material, FileConfiguration config) {
        if (location == null || location.getWorld() == null) return false;
        if (!towny.isUsingTowny(location.getWorld())) return true;

        boolean build = !config.getBoolean("require-build", true)
                || towny.hasPermission(player, location, material, "BUILD");
        boolean destroy = !config.getBoolean("require-destroy", true)
                || towny.hasPermission(player, location, material, "DESTROY");
        boolean switchPerm = !config.getBoolean("require-switch", true)
                || towny.hasPermission(player, location, material, "SWITCH");

        return build && destroy && switchPerm;
    }

    private boolean isSafe(Location location) {
        World world = location.getWorld();
        if (world == null) return false;
        if (location.getBlockY() <= world.getMinHeight() || location.getBlockY() + 1 >= world.getMaxHeight()) return false;
        return location.getBlock().getType().isAir()
                && location.clone().add(0.0D, 1.0D, 0.0D).getBlock().getType().isAir()
                && location.clone().add(0.0D, -1.0D, 0.0D).getBlock().getType().isSolid();
    }

    private boolean rememberLoadedChunk(World world, int x, int z, Set<Long> chunks, int maxChunks) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (!world.isChunkLoaded(cx, cz)) return false;
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        if (!chunks.contains(key) && chunks.size() >= maxChunks) return false;
        chunks.add(key);
        return true;
    }

    private UUID townAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return towny.townUUID(location);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatLocation(String message, Location location) {
        String world = location.getWorld() == null ? "world" : location.getWorld().getName();
        return message
                .replace("%world%", world)
                .replace("%x%", Integer.toString(location.getBlockX()))
                .replace("%y%", Integer.toString(location.getBlockY()))
                .replace("%z%", Integer.toString(location.getBlockZ()));
    }

    private void send(Player player, String message) {
        if (message == null || message.isBlank()) return;
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private void debug(FileConfiguration config, String message) {
        if (config.getBoolean("debug", false)) {
            context.getLogger().info("[GravesX-Towny] " + message);
        }
    }

    private String describe(Location location) {
        if (location == null || location.getWorld() == null) return "unknown";
        return location.getWorld().getName() + " "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private static final class SearchBudget {
        private final int maxChecks;
        private final int maxChunks;
        private final Set<Long> chunks = new HashSet<>();
        private int checks;

        private SearchBudget(int maxChecks, int maxChunks) {
            this.maxChecks = maxChecks;
            this.maxChunks = maxChunks;
        }
    }

    private static final class TownyBridge {
        private final Object townyApi;
        private final Method getTownyWorld;
        private final Method isUsingTowny;
        private final Method getTownUUID;
        private final Method getCachePermission;
        private final Class<?> actionTypeClass;

        private TownyBridge(Object townyApi,
                            Method getTownyWorld,
                            Method isUsingTowny,
                            Method getTownUUID,
                            Method getCachePermission,
                            Class<?> actionTypeClass) {
            this.townyApi = townyApi;
            this.getTownyWorld = getTownyWorld;
            this.isUsingTowny = isUsingTowny;
            this.getTownUUID = getTownUUID;
            this.getCachePermission = getCachePermission;
            this.actionTypeClass = actionTypeClass;
        }

        static TownyBridge create() {
            try {
                Plugin plugin = Bukkit.getPluginManager().getPlugin("Towny");
                if (plugin == null || !plugin.isEnabled()) return unavailable();
                ClassLoader loader = plugin.getClass().getClassLoader();
                Class<?> apiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI", true, loader);
                Class<?> playerCacheUtilClass = Class.forName("com.palmergames.bukkit.towny.utils.PlayerCacheUtil", true, loader);
                Class<?> actionTypeClass = Class.forName("com.palmergames.bukkit.towny.object.TownyPermission$ActionType", true, loader);
                Object api = apiClass.getMethod("getInstance").invoke(null);
                Method getTownyWorld = apiClass.getMethod("getTownyWorld", World.class);
                Method getTownUUID = apiClass.getMethod("getTownUUID", Location.class);
                Method getCachePermission = playerCacheUtilClass.getMethod(
                        "getCachePermission", Player.class, Location.class, Material.class, actionTypeClass);
                return new TownyBridge(api, getTownyWorld, null, getTownUUID, getCachePermission, actionTypeClass);
            } catch (ReflectiveOperationException | LinkageError ex) {
                return unavailable();
            }
        }

        private static TownyBridge unavailable() {
            return new TownyBridge(null, null, null, null, null, null);
        }

        boolean available() {
            return townyApi != null;
        }

        boolean isUsingTowny(World world) {
            try {
                Object townyWorld = getTownyWorld.invoke(townyApi, world);
                if (townyWorld == null) return false;
                Method method = isUsingTowny;
                if (method == null) {
                    method = townyWorld.getClass().getMethod("isUsingTowny");
                }
                return Boolean.TRUE.equals(method.invoke(townyWorld));
            } catch (ReflectiveOperationException | LinkageError ex) {
                return false;
            }
        }

        UUID townUUID(Location location) {
            try {
                Object value = getTownUUID.invoke(townyApi, location);
                return value instanceof UUID uuid ? uuid : null;
            } catch (ReflectiveOperationException | LinkageError ex) {
                return null;
            }
        }

        boolean hasPermission(Player player, Location location, Material material, String actionName) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object action = Enum.valueOf((Class<? extends Enum>) actionTypeClass.asSubclass(Enum.class), actionName);
                Object value = getCachePermission.invoke(null, player, location, material, action);
                return Boolean.TRUE.equals(value);
            } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError ex) {
                return false;
            }
        }
    }
}
