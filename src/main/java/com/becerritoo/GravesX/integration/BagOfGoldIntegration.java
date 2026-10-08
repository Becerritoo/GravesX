package com.becerritoo.GravesX.integration;

import com.ranull.graves.Graves;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Integrates GravesX with BagOfGold physical-money items. */
public final class BagOfGoldIntegration {
    private static final String BAGOFGOLD_PLUGIN = "BagOfGold";
    private static final double EPSILON = 1.0E-9D;
    private static final int FLOOR_SCALE = 5;

    private final Graves plugin;
    private @Nullable MoneyBridge bridge;
    private boolean warnedMissingPlugin;
    private boolean warnedReflectionFailure;
    private boolean loggedHook;

    public BagOfGoldIntegration(@NotNull Graves plugin) {
        this.plugin = plugin;
    }

    /**
     * Moves the configured share of physical money from grave storage to normal
     * death drops. Money inside carried shulker boxes and bundles is extracted;
     * the portable container and all non-money contents remain in the grave.
     */
    public void splitPhysicalMoney(
            @NotNull LivingEntity livingEntity,
            @Nullable List<String> permissionList,
            @NotNull List<ItemStack> graveItems,
            @NotNull List<ItemStack> ignoredItems,
            boolean preserveSlots
    ) {
        if (!(livingEntity instanceof Player) || graveItems.isEmpty() || !isIntegrationEnabled()) return;

        ConfigurationSection section = plugin.getConfigManager()
                .getConfigSection("physical-money.bagofgold.enabled", livingEntity, permissionList);
        if (section == null || !section.getBoolean("physical-money.bagofgold.enabled", true)) return;
        if (!isAllowedByDeathScope(livingEntity,
                section.getString("physical-money.bagofgold.apply-on", "pvp_and_pve"))) return;

        MoneyBridge resolved = resolveBridge();
        if (resolved == null) return;

        double dropPercent = getDropPercent(section);
        if (dropPercent <= 0.0D) return;

        boolean includePortableContainers = section.getBoolean(
                "physical-money.bagofgold.include-portable-containers", true);
        double totalMoney = computeTotalPhysicalMoney(graveItems, resolved, includePortableContainers);
        if (totalMoney <= 0.0D) return;

        double targetDropValue = floorToScale(totalMoney * (dropPercent / 100.0D), FLOOR_SCALE);
        if (targetDropValue <= 0.0D) return;

        double moved = moveMoneyFromList(
                graveItems,
                ignoredItems,
                targetDropValue,
                preserveSlots,
                resolved,
                includePortableContainers
        );

        boolean forceMinimumDrop = section.getBoolean(
                "physical-money.bagofgold.force-minimum-drop-item", true);
        if (forceMinimumDrop && moved <= EPSILON) {
            double smallest = findSmallestUnit(graveItems, resolved, includePortableContainers);
            if (smallest < Double.MAX_VALUE) {
                moved = moveMoneyFromList(
                        graveItems,
                        ignoredItems,
                        smallest,
                        preserveSlots,
                        resolved,
                        includePortableContainers
                );
            }
        }

        if (moved > 0.0D) {
            plugin.debugMessage(
                    "BagOfGold physical split applied: moved " + floorToScale(moved, FLOOR_SCALE)
                            + " value to ground drops (" + dropPercent + "% target).",
                    1
            );
        }
    }

    private double moveMoneyFromList(
            @NotNull List<ItemStack> source,
            @NotNull List<ItemStack> drops,
            double target,
            boolean preserveSlots,
            @NotNull MoneyBridge resolved,
            boolean includePortableContainers
    ) {
        double moved = 0.0D;

        for (int i = 0; i < source.size() && moved + EPSILON < target; i++) {
            ItemStack item = source.get(i);
            if (isEmpty(item)) continue;

            double unitValue = resolved.getUnitMoney(item);
            if (unitValue > 0.0D) {
                MoveResult result = moveDirectMoney(
                        source, i, drops, target - moved, preserveSlots, resolved, unitValue);
                moved += result.value();
                if (result.removed() && !preserveSlots) i--;
                continue;
            }

            if (includePortableContainers) {
                moved += moveMoneyFromPortableContainer(item, drops, target - moved, resolved);
            }
        }

        return floorToScale(moved, FLOOR_SCALE);
    }

    private MoveResult moveDirectMoney(
            @NotNull List<ItemStack> source,
            int index,
            @NotNull List<ItemStack> drops,
            double target,
            boolean preserveSlots,
            @NotNull MoneyBridge resolved,
            double unitValue
    ) {
        ItemStack stack = source.get(index);
        if (isEmpty(stack) || target <= EPSILON) return MoveResult.NONE;

        int available = stack.getAmount();
        if (available == 1 && target + EPSILON < unitValue) {
            double dropValue = floorToScale(target, FLOOR_SCALE);
            double keepValue = floorToScale(unitValue - dropValue, FLOOR_SCALE);
            ItemStack droppedPartial = resolved.rewriteMoney(stack, dropValue, 1);
            ItemStack keptPartial = resolved.rewriteMoney(stack, keepValue, 1);
            if (droppedPartial == null || keptPartial == null) return MoveResult.NONE;

            drops.add(droppedPartial);
            source.set(index, keptPartial);
            return new MoveResult(dropValue, false);
        }

        int take = Math.min(available, (int) Math.floor((target + EPSILON) / unitValue));
        if (take <= 0) return MoveResult.NONE;

        if (take >= available) {
            drops.add(stack.clone());
            if (preserveSlots) {
                source.set(index, null);
            } else {
                source.remove(index);
            }
            return new MoveResult(unitValue * available, !preserveSlots);
        }

        ItemStack dropped = resolved.rewriteMoney(stack, unitValue, take);
        ItemStack remaining = resolved.rewriteMoney(stack, unitValue, available - take);
        if (dropped == null || remaining == null) return MoveResult.NONE;

        drops.add(dropped);
        source.set(index, remaining);
        return new MoveResult(unitValue * take, false);
    }

    private double moveMoneyFromPortableContainer(
            @NotNull ItemStack container,
            @NotNull List<ItemStack> drops,
            double target,
            @NotNull MoneyBridge resolved
    ) {
        ItemMeta meta = container.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox shulker) {
            Inventory inventory = shulker.getSnapshotInventory();
            List<ItemStack> contents = new ArrayList<>(
                    Arrays.asList(inventory.getStorageContents()));
            double moved = moveMoneyFromList(contents, drops, target, true, resolved, false);
            if (moved > 0.0D) {
                inventory.setStorageContents(contents.toArray(new ItemStack[0]));
                blockStateMeta.setBlockState(shulker);
                container.setItemMeta(blockStateMeta);
            }
            return moved;
        }

        if (meta instanceof BundleMeta bundleMeta && bundleMeta.hasItems()) {
            List<ItemStack> contents = new ArrayList<>(bundleMeta.getItems());
            double moved = moveMoneyFromList(contents, drops, target, false, resolved, false);
            if (moved > 0.0D) {
                contents.removeIf(BagOfGoldIntegration::isEmpty);
                bundleMeta.setItems(contents);
                container.setItemMeta(bundleMeta);
            }
            return moved;
        }

        return 0.0D;
    }

    private double computeTotalPhysicalMoney(
            @NotNull List<ItemStack> items,
            @NotNull MoneyBridge resolved,
            boolean includePortableContainers
    ) {
        double total = 0.0D;
        for (ItemStack item : items) {
            if (isEmpty(item)) continue;

            double unit = resolved.getUnitMoney(item);
            if (unit > 0.0D) {
                total += unit * item.getAmount();
            } else if (includePortableContainers) {
                total += computePortableMoney(item, resolved);
            }
        }
        return floorToScale(total, FLOOR_SCALE);
    }

    private double computePortableMoney(@NotNull ItemStack container, @NotNull MoneyBridge resolved) {
        ItemMeta meta = container.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox shulker) {
            return computeTotalPhysicalMoney(
                    Arrays.asList(shulker.getSnapshotInventory().getStorageContents()), resolved, false);
        }
        if (meta instanceof BundleMeta bundleMeta && bundleMeta.hasItems()) {
            return computeTotalPhysicalMoney(bundleMeta.getItems(), resolved, false);
        }
        return 0.0D;
    }

    private double findSmallestUnit(
            @NotNull List<ItemStack> items,
            @NotNull MoneyBridge resolved,
            boolean includePortableContainers
    ) {
        double smallest = Double.MAX_VALUE;
        for (ItemStack item : items) {
            if (isEmpty(item)) continue;

            double unit = resolved.getUnitMoney(item);
            if (unit > 0.0D) {
                smallest = Math.min(smallest, unit);
            } else if (includePortableContainers) {
                smallest = Math.min(smallest, findSmallestPortableUnit(item, resolved));
            }
        }
        return smallest;
    }

    private double findSmallestPortableUnit(@NotNull ItemStack container, @NotNull MoneyBridge resolved) {
        ItemMeta meta = container.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox shulker) {
            return findSmallestUnit(
                    Arrays.asList(shulker.getSnapshotInventory().getStorageContents()), resolved, false);
        }
        if (meta instanceof BundleMeta bundleMeta && bundleMeta.hasItems()) {
            return findSmallestUnit(bundleMeta.getItems(), resolved, false);
        }
        return Double.MAX_VALUE;
    }

    private boolean isIntegrationEnabled() {
        FileConfiguration config = plugin.getConfig();
        if (config == null) return true;

        String path = "settings.integration.bagofgold.enabled";
        return !config.contains(path) || config.getBoolean(path, true);
    }

    private boolean isAllowedByDeathScope(@NotNull LivingEntity livingEntity, @Nullable String modeRaw) {
        String mode = modeRaw == null ? "pvp_and_pve" : modeRaw.trim().toLowerCase();
        boolean pvp = livingEntity.getKiller() != null;

        return switch (mode) {
            case "pvp_only" -> pvp;
            case "pve_only" -> !pvp;
            default -> true;
        };
    }

    private double getDropPercent(@NotNull ConfigurationSection section) {
        final String dropPath = "physical-money.bagofgold.drop-on-ground-percent";
        final String keepPath = "physical-money.bagofgold.keep-in-grave-percent";

        double drop = section.contains(dropPath)
                ? section.getDouble(dropPath, 30.0D)
                : (100.0D - section.getDouble(keepPath, 70.0D));

        if (!Double.isFinite(drop) || drop < 0.0D) return 0.0D;
        return Math.min(drop, 100.0D);
    }

    private @Nullable MoneyBridge resolveBridge() {
        if (bridge != null) return bridge;

        Plugin bag = plugin.getServer().getPluginManager().getPlugin(BAGOFGOLD_PLUGIN);
        if (bag == null || !bag.isEnabled()) {
            if (!warnedMissingPlugin) {
                warnedMissingPlugin = true;
                plugin.debugMessage(
                        "BagOfGold physical split is enabled, but BagOfGold is not loaded. Skipping split.", 2);
            }
            return null;
        }

        try {
            bridge = new MoneyBridge(bag);
            if (!loggedHook) {
                loggedHook = true;
                plugin.integrationMessage(bridge.usesNativeApi()
                        ? "Hooked into the BagOfGold native money API."
                        : "Hooked into BagOfGold physical item metadata through the compatibility bridge.");
            }
            return bridge;
        } catch (Throwable throwable) {
            if (!warnedReflectionFailure) {
                warnedReflectionFailure = true;
                plugin.integrationMessage(
                        "Failed to initialize BagOfGold physical-money bridge: " + throwable.getMessage(), "warn");
            }
            return null;
        }
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private static double floorToScale(double value, int scale) {
        if (!Double.isFinite(value)) return 0.0D;
        double factor = Math.pow(10, scale);
        return Math.floor(value * factor) / factor;
    }

    private record MoveResult(double value, boolean removed) {
        private static final MoveResult NONE = new MoveResult(0.0D, false);
    }

    private static final class MoneyBridge {
        private final @Nullable Object nativeApi;
        private final @Nullable Method nativeIsMoney;
        private final @Nullable Method nativeGetValue;
        private final Method rewardIsReward;
        private final Method rewardGetReward;
        private final Method rewardIsMoney;
        private final Method rewardGetMoney;
        private final Method rewardSetMoney;
        private final Method rewardSetDisplayAndLore;
        private final @Nullable Method rewardCheckHash;
        private final @Nullable Method rewardRotateToken;

        private MoneyBridge(@NotNull Plugin bag) throws ReflectiveOperationException {
            Object api = null;
            Method apiIsMoney = null;
            Method apiGetValue = null;
            try {
                Method getApi = bag.getClass().getMethod("getAPI");
                api = getApi.invoke(null);
                if (api != null) {
                    apiIsMoney = api.getClass().getMethod("isMoney", ItemStack.class);
                    apiGetValue = api.getClass().getMethod("getValue", ItemStack.class);
                }
            } catch (Throwable ignored) {
                api = null;
                apiIsMoney = null;
                apiGetValue = null;
            }
            this.nativeApi = api;
            this.nativeIsMoney = apiIsMoney;
            this.nativeGetValue = apiGetValue;

            Class<?> rewardClass = Class.forName("one.lindegaard.CustomItemsLib.rewards.Reward");
            this.rewardIsReward = rewardClass.getMethod("isReward", ItemStack.class);
            this.rewardGetReward = rewardClass.getMethod("getReward", ItemStack.class);
            this.rewardIsMoney = rewardClass.getMethod("isMoney");
            this.rewardGetMoney = rewardClass.getMethod("getMoney");
            this.rewardSetMoney = rewardClass.getMethod("setMoney", double.class);
            this.rewardSetDisplayAndLore = rewardClass.getMethod(
                    "setDisplayNameAndHiddenLores", ItemStack.class, rewardClass);
            this.rewardCheckHash = optionalMethod(rewardClass, "checkHash");
            this.rewardRotateToken = optionalMethod(rewardClass, "rotateTokenAndSign");
        }

        private boolean usesNativeApi() {
            return nativeApi != null && nativeIsMoney != null && nativeGetValue != null;
        }

        private double getUnitMoney(@Nullable ItemStack item) {
            if (isEmpty(item)) return 0.0D;

            if (usesNativeApi()) {
                try {
                    Object money = nativeIsMoney.invoke(nativeApi, item);
                    if (!(money instanceof Boolean) || !((Boolean) money)) return 0.0D;
                    Object value = nativeGetValue.invoke(nativeApi, item);
                    return positiveNumber(value);
                } catch (Throwable ignored) {
                    // Fall through for older or partially compatible BagOfGold builds.
                }
            }

            try {
                Object isReward = rewardIsReward.invoke(null, item);
                if (!(isReward instanceof Boolean) || !((Boolean) isReward)) return 0.0D;

                Object reward = rewardGetReward.invoke(null, item);
                if (reward == null || !isValidReward(reward)) return 0.0D;

                Object isMoney = rewardIsMoney.invoke(reward);
                if (!(isMoney instanceof Boolean) || !((Boolean) isMoney)) return 0.0D;
                return positiveNumber(rewardGetMoney.invoke(reward));
            } catch (Throwable ignored) {
                return 0.0D;
            }
        }

        private @Nullable ItemStack rewriteMoney(@Nullable ItemStack base, double value, int amount) {
            if (isEmpty(base) || !Double.isFinite(value) || value <= 0.0D || amount <= 0) return null;

            try {
                Object reward = rewardGetReward.invoke(null, base);
                if (reward == null || !isValidReward(reward)) return null;

                Object isMoney = rewardIsMoney.invoke(reward);
                if (!(isMoney instanceof Boolean) || !((Boolean) isMoney)) return null;

                rewardSetMoney.invoke(reward, value);
                if (rewardRotateToken != null) rewardRotateToken.invoke(reward);

                ItemStack rewritten = (ItemStack) rewardSetDisplayAndLore.invoke(null, base.clone(), reward);
                if (isEmpty(rewritten)) return null;
                rewritten.setAmount(amount);
                return rewritten;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean isValidReward(@NotNull Object reward) throws ReflectiveOperationException {
            if (rewardCheckHash == null) return true;
            Object valid = rewardCheckHash.invoke(reward);
            return valid instanceof Boolean && ((Boolean) valid);
        }

        private static double positiveNumber(@Nullable Object value) {
            if (!(value instanceof Number number)) return 0.0D;
            double result = number.doubleValue();
            return Double.isFinite(result) && result > 0.0D ? result : 0.0D;
        }

        private static @Nullable Method optionalMethod(@NotNull Class<?> type, @NotNull String name) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }
}
