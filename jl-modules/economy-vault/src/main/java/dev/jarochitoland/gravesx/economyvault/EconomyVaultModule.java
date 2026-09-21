package dev.jarochitoland.gravesx.economyvault;

import com.ranull.graves.type.Grave;
import dev.cwhead.GravesX.event.GravePreTeleportEvent;
import dev.cwhead.GravesX.module.GravesXModule;
import dev.cwhead.GravesX.module.ModuleContext;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;

public final class EconomyVaultModule extends GravesXModule implements Listener {
    private ModuleContext context;
    private Economy economy;

    @Override
    public void onModuleLoad(ModuleContext ctx) {
        this.context = ctx;
        ctx.saveDefaultConfig();
    }

    @Override
    public void onModuleEnable(ModuleContext ctx) {
        this.context = ctx;
        reloadEconomy();
        ctx.registerListener(this);
        if (economy == null) {
            ctx.getLogger().warning("[GravesX-EconomyVault] Vault economy provider not found; teleport charges are disabled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreTeleport(GravePreTeleportEvent event) {
        FileConfiguration config = context.getConfig();
        if (!config.getBoolean("teleport.enabled", true)) return;
        if (!event.isPlayer()) return;

        Player player = event.getPlayer();
        if (player.hasPermission(config.getString("teleport.bypass-permission", "graves.economyvault.teleport.free"))) {
            return;
        }

        Economy provider = economy;
        if (provider == null) {
            reloadEconomy();
            provider = economy;
        }
        if (provider == null) {
            if (config.getBoolean("teleport.cancel-if-no-economy", false)) {
                event.setCancelled(true);
                send(player, config.getString("messages.no-economy", "&cLa economia no esta disponible."));
            }
            return;
        }

        Grave grave = event.getGrave();
        Location from = player.getLocation();
        Location to = grave.getLocationDeath();
        double cost = calculateCost(from, to, config);

        if (cost <= 0.0D) return;

        if (!provider.has(player, cost)) {
            event.setCancelled(true);
            send(player, format(config.getString("messages.not-enough-money",
                    "&cNecesitas %cost% para teletransportarte a tu tumba."), cost, from, to));
            return;
        }

        if (!provider.withdrawPlayer(player, cost).transactionSuccess()) {
            event.setCancelled(true);
            send(player, config.getString("messages.withdraw-failed", "&cNo se pudo cobrar el teletransporte."));
            return;
        }

        if (config.getBoolean("teleport.send-paid-message", true)) {
            send(player, format(config.getString("messages.charged",
                    "&aPagaste %cost% por teletransportarte a tu tumba."), cost, from, to));
        }
    }

    private void reloadEconomy() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
    }

    private double calculateCost(Location from, Location to, FileConfiguration config) {
        double cost = Math.max(0.0D, config.getDouble("teleport.base-cost", 2.0D));

        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return roundMoney(cost);
        }

        if (config.getBoolean("teleport.cost-distance-increase", true)
                && from.getWorld().equals(to.getWorld())) {
            double distance = horizontalDistance(from, to);
            double blocksPerStep = Math.max(1.0D, config.getDouble("teleport.distance-blocks-per-step", 16.0D));
            cost = config.getBoolean("teleport.multiply-base-by-distance", true)
                    ? cost * (distance / blocksPerStep)
                    : cost + (distance / blocksPerStep) * config.getDouble("teleport.distance-step-cost", 1.0D);
        }

        if (!from.getWorld().equals(to.getWorld())) {
            cost += Math.max(0.0D, config.getDouble("teleport.cost-different-world", 0.0D));
        }

        if (config.getBoolean("teleport.round-cost", true)) {
            cost = Math.round(cost);
        }

        return roundMoney(Math.max(0.0D, cost));
    }

    private double horizontalDistance(Location from, Location to) {
        double dx = from.getBlockX() - to.getBlockX();
        double dz = from.getBlockZ() - to.getBlockZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double roundMoney(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private String format(String message, double cost, Location from, Location to) {
        double distance = (from != null && to != null && from.getWorld() != null && from.getWorld().equals(to.getWorld()))
                ? horizontalDistance(from, to)
                : -1.0D;
        return message
                .replace("%cost%", String.format(Locale.US, "%.2f", cost))
                .replace("%distance%", distance < 0.0D ? "otro mundo" : String.valueOf(Math.round(distance)));
    }

    private void send(Player player, String message) {
        if (message == null || message.isBlank()) return;
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
