package com.ranull.graves.integration;

import com.ranull.graves.Graves;
import dev.cwhead.GravesX.event.GraveCreateEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import com.ranull.graves.manager.GraveManager;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BetterReviveIntegrationTest {
    private Graves graves;
    private Plugin dependency;
    private Player player;
    private YamlConfiguration config;
    private BetterReviveIntegration integration;
    private GraveCreateEvent creation;

    @Before
    public void setup() {
        graves = mock(Graves.class);
        dependency = mock(Plugin.class);
        player = mock(Player.class);
        creation = mock(GraveCreateEvent.class);
        config = new YamlConfiguration();
        when(graves.getConfig()).thenReturn(config);
        when(dependency.isEnabled()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        integration = new BetterReviveIntegration(graves, dependency);
    }

    private void captureMessage(Plugin owner) {
        MetadataValue value = mock(MetadataValue.class);
        when(value.getOwningPlugin()).thenReturn(owner);
        when(value.asString()).thenReturn("Bled out");
        when(player.getMetadata("betterrevive-bled-out-message")).thenReturn(List.of(value));
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        integration.onPlayerDeathPreCapture(death);
        when(player.getMetadata("betterrevive-bled-out-message")).thenReturn(List.of());
    }

    @Test public void preservesOwnedMessageAfterMetadataIsClearedAndConsumesIt() {
        captureMessage(dependency);
        assertTrue(integration.applyKiller(creation, null, player));
        verify(creation).setKillerName("Bled out");
        assertFalse(integration.applyKiller(creation, null, player));
    }

    @Test public void ignoresMetadataFromAnotherPlugin() {
        captureMessage(mock(Plugin.class));
        assertFalse(integration.applyKiller(creation, null, player));
        verifyNoInteractions(creation);
    }

    @Test public void disabledConfigurationDoesNotCapture() {
        config.set("settings.integration.betterrevive.enabled", false);
        captureMessage(dependency);
        config.set("settings.integration.betterrevive.enabled", true);
        assertFalse(integration.applyKiller(creation, null, player));
    }

    @Test public void disabledDependencyDoesNotApply() {
        captureMessage(dependency);
        when(dependency.isEnabled()).thenReturn(false);
        assertFalse(integration.applyKiller(creation, null, player));
        verifyNoInteractions(creation);
    }

    @Test public void quitClearsCapturedState() {
        captureMessage(dependency);
        PlayerQuitEvent quit = new PlayerQuitEvent(player, "");
        integration.onPlayerQuitCleanup(quit);
        assertFalse(integration.applyKiller(creation, null, player));
    }

    @Test public void ordinaryDeathDoesNotOverrideKiller() {
        assertFalse(integration.applyKiller(creation, null, player));
        verifyNoInteractions(creation);
    }

    private void captureFall() {
        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        when(damage.getEntity()).thenReturn(player);
        when(damage.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        integration.onEntityDamageSnapshot(damage);
    }

    @Test public void originalDamageIsRestoredOnlyWithBetterReviveEvidence() {
        captureFall();
        GraveManager graveManager = mock(GraveManager.class);
        when(graves.getGraveManager()).thenReturn(graveManager);
        when(graveManager.getDamageReason(EntityDamageEvent.DamageCause.FALL, null)).thenReturn("Fall");
        captureMessage(dependency);
        assertTrue(integration.applyKiller(creation, null, player));
        verify(creation).setKillerName("Fall");
    }

    @Test public void magicDamageAloneDoesNotReusePreviousDamage() {
        captureFall();
        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        when(damage.getCause()).thenReturn(EntityDamageEvent.DamageCause.MAGIC);
        when(player.getLastDamageCause()).thenReturn(damage);
        assertFalse(integration.applyKiller(creation, null, player));
        verifyNoInteractions(creation);
    }
}
