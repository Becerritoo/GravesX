package dev.jarochitoland.gravesx.randomdrop;

import dev.cwhead.GravesX.event.GravePreCreateEvent;
import dev.cwhead.GravesX.module.GravesXModule;
import dev.cwhead.GravesX.module.ModuleContext;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class RandomDropModule extends GravesXModule implements Listener {
    private final Random random = new SecureRandom();
    private ModuleContext context;
    private BagOfGoldMoneyDetector bagOfGoldMoneyDetector;

    @Override
    public void onModuleLoad(ModuleContext ctx) {
        this.context = ctx;
        ctx.saveDefaultConfig();
    }

    @Override
    public void onModuleEnable(ModuleContext ctx) {
        this.context = ctx;
        this.bagOfGoldMoneyDetector = new BagOfGoldMoneyDetector();
        ctx.registerListener(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreCreate(GravePreCreateEvent event) {
        FileConfiguration config = context.getConfig();
        if (!config.getBoolean("enabled", true)) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.hasPermission(config.getString("bypass-permission", "graves.randomdrop.bypass"))) return;

        int requested = Math.max(0, config.getInt("drop-count", 1));
        if (requested <= 0) return;

        List<ItemStack> graveItems = cloneItems(event.getGraveItemStackList());
        if (graveItems.isEmpty()) return;

        boolean leaveOne = config.getBoolean("leave-at-least-one-stack-in-grave", true);
        boolean ignoreBagOfGoldMoney = config.getBoolean("ignore-bagofgold-money", true);
        String mode = config.getString("selection-mode", "stacks");
        List<ItemStack> dropped = "items".equalsIgnoreCase(mode)
                ? takeRandomItems(graveItems, requested, leaveOne, ignoreBagOfGoldMoney)
                : takeRandomStacks(graveItems, requested, leaveOne, ignoreBagOfGoldMoney);

        if (dropped.isEmpty()) return;

        List<ItemStack> ignored = cloneItems(event.getIgnoredItems());
        ignored.addAll(dropped);
        event.setGraveItemStackList(graveItems);
        event.setIgnoredItems(ignored);

        if (config.getBoolean("send-message", false)) {
            send(player, config.getString("messages.dropped",
                    "&eUn objeto aleatorio cayo al suelo en vez de guardarse en tu tumba."));
        }

        if (config.getBoolean("debug", false)) {
            context.getLogger().info("[GravesX-RandomDrop] Dropped " + dropped.size()
                    + " random " + normalizeMode(mode) + " for " + player.getName() + ".");
        }
    }

    private List<ItemStack> takeRandomStacks(List<ItemStack> items, int requested, boolean leaveOne,
                                             boolean ignoreBagOfGoldMoney) {
        List<ItemStack> dropped = new ArrayList<>();

        for (int i = 0; i < requested; i++) {
            List<Integer> candidates = candidateStackIndexes(items, leaveOne, ignoreBagOfGoldMoney);
            if (candidates.isEmpty()) break;

            int index = candidates.get(random.nextInt(candidates.size()));
            dropped.add(items.remove(index));
        }

        return dropped;
    }

    private List<ItemStack> takeRandomItems(List<ItemStack> items, int requested, boolean leaveOne,
                                            boolean ignoreBagOfGoldMoney) {
        List<ItemStack> dropped = new ArrayList<>();

        for (int i = 0; i < requested; i++) {
            List<Integer> candidates = candidateItemIndexes(items, leaveOne, ignoreBagOfGoldMoney);
            if (candidates.isEmpty()) break;

            int index = candidates.get(random.nextInt(candidates.size()));
            ItemStack source = items.get(index);
            ItemStack single = source.clone();
            single.setAmount(1);
            dropped.add(single);

            source.setAmount(source.getAmount() - 1);
            if (source.getAmount() <= 0) {
                items.remove(index);
            }
        }

        return dropped;
    }

    private List<Integer> candidateStackIndexes(List<ItemStack> items, boolean leaveOne, boolean ignoreBagOfGoldMoney) {
        List<Integer> candidates = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (!isSelectable(item, ignoreBagOfGoldMoney)) continue;
            candidates.add(i);
        }

        if (leaveOne && candidates.size() <= 1) {
            candidates.clear();
        }

        return candidates;
    }

    private List<Integer> candidateItemIndexes(List<ItemStack> items, boolean leaveOne, boolean ignoreBagOfGoldMoney) {
        List<Integer> candidates = new ArrayList<>();
        int totalAmount = totalAmount(items, ignoreBagOfGoldMoney);

        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (!isSelectable(item, ignoreBagOfGoldMoney)) continue;
            if (leaveOne && totalAmount <= 1) continue;
            candidates.add(i);
        }

        return candidates;
    }

    private int totalAmount(List<ItemStack> items, boolean ignoreBagOfGoldMoney) {
        int total = 0;
        for (ItemStack item : items) {
            if (isSelectable(item, ignoreBagOfGoldMoney)) {
                total += Math.max(0, item.getAmount());
            }
        }
        return total;
    }

    private boolean isSelectable(ItemStack item, boolean ignoreBagOfGoldMoney) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) return false;
        return !ignoreBagOfGoldMoney || !bagOfGoldMoneyDetector.isMoney(item);
    }

    private List<ItemStack> cloneItems(Collection<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        if (items == null) return result;

        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                result.add(item.clone());
            }
        }

        return result;
    }

    private String normalizeMode(String mode) {
        if (mode == null) return "stacks";
        return mode.toLowerCase(Locale.ROOT);
    }

    private void send(Player player, String message) {
        if (message == null || message.isBlank()) return;
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private static final class BagOfGoldMoneyDetector {
        private Method rewardIsReward;
        private Method rewardGetReward;
        private Method rewardIsMoney;
        private Method rewardCheckHash;
        private boolean initialized;

        private boolean isMoney(ItemStack itemStack) {
            if (itemStack == null || itemStack.getType() == Material.AIR) return false;
            if (!initialized && !initialize()) return false;

            try {
                Object isReward = rewardIsReward.invoke(null, itemStack);
                if (!(isReward instanceof Boolean) || !((Boolean) isReward)) return false;

                Object rewardObj = rewardGetReward.invoke(null, itemStack);
                if (rewardObj == null) return false;

                if (rewardCheckHash != null) {
                    Object checkHash = rewardCheckHash.invoke(rewardObj);
                    if (!(checkHash instanceof Boolean) || !((Boolean) checkHash)) return false;
                }

                Object isMoney = rewardIsMoney.invoke(rewardObj);
                return isMoney instanceof Boolean && ((Boolean) isMoney);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean initialize() {
            initialized = true;
            try {
                Class<?> rewardClass = Class.forName("one.lindegaard.CustomItemsLib.rewards.Reward");
                rewardIsReward = rewardClass.getMethod("isReward", ItemStack.class);
                rewardGetReward = rewardClass.getMethod("getReward", ItemStack.class);
                rewardIsMoney = rewardClass.getMethod("isMoney");

                try {
                    rewardCheckHash = rewardClass.getMethod("checkHash");
                } catch (NoSuchMethodException ignored) {
                    rewardCheckHash = null;
                }
                return true;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return false;
            }
        }
    }
}
