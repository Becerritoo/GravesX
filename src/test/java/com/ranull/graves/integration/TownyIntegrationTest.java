package com.ranull.graves.integration;

import com.palmergames.bukkit.towny.TownyAPI;
import com.ranull.graves.Graves;
import com.ranull.graves.type.Grave;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TownyIntegrationTest {
    private static final String CONFIG = "settings.integration.towny.";
    private Graves plugin;
    private YamlConfiguration config;
    private World world;
    private Player player;
    private Grave grave;
    private Location death;
    private TownyIntegration integration;
    private UUID town;

    @Before public void setup() {
        plugin = mock(Graves.class);
        config = new YamlConfiguration();
        config.set(CONFIG + "search-radius", 2);
        config.set(CONFIG + "vertical-range", 0);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(256);
        player = mock(Player.class);
        grave = new Grave(UUID.randomUUID());
        death = new Location(world, 0, 64, 0);
        town = UUID.randomUUID();
        integration = spy(new TownyIntegration(plugin, mock(TownyAPI.class)));
        doReturn(Material.PLAYER_HEAD).when(integration).graveMaterial(any());
        doAnswer(call -> ((Location) call.getArgument(0)).clone())
                .when(integration).blockLocation(any(), any());
        doReturn(0L).when(integration).nanoTime();
        doReturn(town).when(integration).townAt(any(Location.class));
        doReturn(false).when(integration).canAccess(any(), any(), any());
        doReturn(true).when(integration).safeCandidate(any(), any(), any());
    }

    private TownyIntegration.Placement resolve() {
        return integration.resolve(player, death, death, grave);
    }

    @Test public void permittedDeathIsUnchanged() {
        doReturn(true).when(integration).canAccess(any(), any(), any());
        TownyIntegration.Placement result = resolve();
        assertSame(death, result.location());
        assertFalse(result.relocated());
        verify(integration, never()).safeCandidate(any(), any(), any());
    }

    @Test public void prefersSameTownOverCloserWilderness() {
        doAnswer(call -> ((Location) call.getArgument(1)).getBlockX() != 0)
                .when(integration).canAccess(any(), any(), any());
        doAnswer(call -> ((Location) call.getArgument(0)).getBlockX() < 0 ? null : town)
                .when(integration).townAt(any());
        TownyIntegration.Placement result = resolve();
        assertNotNull(result.location());
        assertTrue(result.location().getBlockX() > 0);
        assertTrue(result.relocated());
    }

    @Test public void usesWildernessWhenNoSameTownLocationIsAvailable() {
        doAnswer(call -> ((Location) call.getArgument(1)).getBlockX() < 0)
                .when(integration).canAccess(any(), any(), any());
        doAnswer(call -> ((Location) call.getArgument(0)).getBlockX() < 0 ? null : town)
                .when(integration).townAt(any());
        assertEquals(-1, resolve().location().getBlockX());
    }

    @Test public void neverSelectsAnotherTownEvenWithPermission() {
        UUID other = UUID.randomUUID();
        doAnswer(call -> ((Location) call.getArgument(0)).getBlockX() == 0 ? town : other)
                .when(integration).townAt(any());
        doAnswer(call -> ((Location) call.getArgument(1)).getBlockX() != 0)
                .when(integration).canAccess(any(), any(), any());
        assertNull(resolve().location());
        verify(integration, never()).safeCandidate(any(), any(), any());
    }

    @Test public void skipsUnloadedChunksWithoutAccessingBlocks() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertNull(resolve().location());
        verify(integration, never()).safeCandidate(any(), any(), any());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test public void blockCheckBudgetIsEnforced() {
        config.set(CONFIG + "max-block-checks", 3);
        doAnswer(call -> !death.equals(call.getArgument(1)))
                .when(integration).canAccess(any(), any(), any());
        doReturn(false).when(integration).safeCandidate(any(), any(), any());
        assertNull(resolve().location());
        verify(integration, times(3)).safeCandidate(any(), any(), any());
    }

    @Test public void deadlineIsEnforced() {
        doReturn(0L, 100_000_000L).when(integration).nanoTime();
        assertNull(resolve().location());
        verify(integration, never()).safeCandidate(any(), any(), any());
    }

    @Test public void columnBudgetIsEnforced() {
        config.set(CONFIG + "max-columns", 1);
        assertNull(resolve().location());
        // The death/proposed checks plus only the origin column are permitted.
        verify(integration, times(3)).canAccess(any(), any(), any());
    }

    @Test public void chunkBudgetLimitsBlockInspection() {
        config.set(CONFIG + "max-chunks", 1);
        doAnswer(call -> !death.equals(call.getArgument(1)))
                .when(integration).canAccess(any(), any(), any());
        java.util.Set<String> inspected = new java.util.HashSet<>();
        doAnswer(call -> {
            Location location = call.getArgument(1);
            inspected.add((location.getBlockX() >> 4) + ":" + (location.getBlockZ() >> 4));
            return false;
        }).when(integration).safeCandidate(any(), any(), any());
        assertNull(resolve().location());
        assertEquals(1, inspected.size());
    }

    @Test public void permissionErrorsFailClosed() {
        doThrow(new IllegalStateException("test protection failure"))
                .when(integration).canAccess(any(), any(), any());
        assertNull(resolve().location());
    }

    @Test public void eventDestinationStillRequiresPermission() {
        assertFalse(integration.validateFinal(player, death, death, grave, true));
    }

    @Test public void eventDestinationCannotMoveToAnotherWorld() {
        doReturn(true).when(integration).canAccess(any(), any(), any());
        assertFalse(integration.validateFinal(player, death,
                new Location(mock(World.class), 0, 64, 0), grave, true));
    }

    @Test public void realSafetyCheckRejectsUnloadedChunk() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        doCallRealMethod().when(integration).safeCandidate(any(), any(), any());
        assertFalse(integration.safeCandidate(player, death, grave));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test public void failureKeepsInventoryAndSuppressesDuplicateDropsAndExperience() {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(Material.STONE, 3));
        when(event.getDrops()).thenReturn(drops);
        when(event.getEntity()).thenReturn(player);
        integration.handleFailure(event);
        assertTrue(drops.isEmpty());
        verify(event).setKeepInventory(true);
        verify(event).setKeepLevel(true);
        verify(event).setDroppedExp(0);
    }

    @Test public void failureCanLeaveNormalDropsUntouched() {
        config.set(CONFIG + "failure-keep-inventory", false);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player);
        integration.handleFailure(event);
        verify(event, never()).setKeepInventory(anyBoolean());
        verify(event, never()).getDrops();
    }

    @Test public void snapshotRestoresOriginalDropsOnceAfterFiltering() {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        ItemStack stack = new ItemStack(Material.STONE, 3);
        List<ItemStack> drops = new ArrayList<>(List.of(stack));
        when(event.getDrops()).thenReturn(drops);
        when(event.getDroppedExp()).thenReturn(7);
        TownyIntegration.DeathSnapshot snapshot = TownyIntegration.DeathSnapshot.capture(event);
        stack.setAmount(1);
        drops.clear();
        drops.add(new ItemStack(Material.DIRT, 2));
        snapshot.restore(event);
        assertEquals(1, drops.size());
        assertEquals(Material.STONE, drops.get(0).getType());
        assertEquals(3, drops.get(0).getAmount());
        verify(event).setDroppedExp(7);
    }
}
