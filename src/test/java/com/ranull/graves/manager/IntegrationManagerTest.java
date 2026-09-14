package com.ranull.graves.manager;

import com.ranull.graves.Graves;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class IntegrationManagerTest {
    private IntegrationManager manager;
    private PluginManager plugins;
    private YamlConfiguration config;

    @Before public void setup() throws Exception {
        Graves graves = mock(Graves.class);
        Server server = mock(Server.class);
        plugins = mock(PluginManager.class);
        config = new YamlConfiguration();
        when(graves.getConfig()).thenReturn(config);
        java.lang.reflect.Field serverField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(graves, server);
        when(server.getPluginManager()).thenReturn(plugins);
        manager = new IntegrationManager(graves);
    }

    private void load(String method) throws Exception {
        Method loader = IntegrationManager.class.getDeclaredMethod(method);
        loader.setAccessible(true);
        loader.invoke(manager);
    }

    private Plugin dependency(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn(name);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(name, "test", "test.Main"));
        when(plugins.getPlugin(name)).thenReturn(plugin);
        when(plugins.isPluginEnabled(name)).thenReturn(true);
        return plugin;
    }

    @Test public void absentPluginsDoNotCreateIntegrations() throws Exception {
        load("loadBagOfGold");
        load("loadBetterRevive");
        assertFalse(manager.hasBagOfGold());
        assertFalse(manager.hasBetterRevive());
        assertNull(manager.getBagOfGold());
        assertNull(manager.getBetterRevive());
    }

    @Test public void disabledConfigurationDoesNotCreateIntegrations() throws Exception {
        dependency("BagOfGold");
        dependency("BetterRevive");
        config.set("settings.integration.bagofgold.enabled", false);
        config.set("settings.integration.betterrevive.enabled", false);
        load("loadBagOfGold");
        load("loadBetterRevive");
        assertNull(manager.getBagOfGold());
        assertNull(manager.getBetterRevive());
    }

    @Test public void enabledPluginsLoadAndUnload() throws Exception {
        dependency("BagOfGold");
        dependency("BetterRevive");
        load("loadBagOfGold");
        load("loadBetterRevive");
        assertTrue(manager.hasBagOfGold());
        assertTrue(manager.hasBetterRevive());
        manager.unload();
        assertNull(manager.getBagOfGold());
        assertNull(manager.getBetterRevive());
    }

    @Test public void loadingAgainReplacesInstances() throws Exception {
        dependency("BagOfGold");
        dependency("BetterRevive");
        load("loadBagOfGold");
        load("loadBetterRevive");
        Object bag = manager.getBagOfGold();
        Object revive = manager.getBetterRevive();
        load("loadBagOfGold");
        load("loadBetterRevive");
        assertNotSame(bag, manager.getBagOfGold());
        assertNotSame(revive, manager.getBetterRevive());
        manager.unload();
    }

    @Test public void disabledPluginsAreNotLoaded() throws Exception {
        when(dependency("BagOfGold").isEnabled()).thenReturn(false);
        when(dependency("BetterRevive").isEnabled()).thenReturn(false);
        load("loadBagOfGold");
        load("loadBetterRevive");
        assertNull(manager.getBagOfGold());
        assertNull(manager.getBetterRevive());
    }
}
