package net.maxbel.takeitout;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContainerReturnTrackerTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(DataComponentInitializers.PendingComponents::apply);
    }

    private static SimpleContainer fullInventory() {
        SimpleContainer inventory = new SimpleContainer(36);
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        return inventory;
    }

    @Test
    void allMatchingStacksGoToOldestRecordedSourceWithoutPerStackOrigins() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(0, new ItemStack(Items.STONE, 64));
        inventory.setItem(1, new ItemStack(Items.STONE, 12));
        var a = new SimpleContainer(3);
        var b = new SimpleContainer(3);
        tracker.recordTake(inventory, "a", inventory.getItem(0));
        tracker.recordTake(inventory, "b", inventory.getItem(1));
        assertTrue(tracker.makeRoom(inventory, new ItemStack(Items.DIRT), -1, Map.of("a", a, "b", b)::get));
        assertEquals(76, a.countItem(Items.STONE));
        assertTrue(b.isEmpty());
        assertTrue(inventory.getItem(0).isEmpty());
        assertTrue(inventory.getItem(1).isEmpty());
    }

    @Test
    void refillingMaterialDoesNotRefreshItsAge() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(0, new ItemStack(Items.STONE, 64));
        inventory.setItem(1, new ItemStack(Items.DIRT, 64));
        var a = new SimpleContainer(2);
        var b = new SimpleContainer(2);
        tracker.recordTake(inventory, "a", inventory.getItem(0));
        tracker.recordTake(inventory, "b", inventory.getItem(1));
        tracker.recordTake(inventory, "a", inventory.getItem(0));
        assertTrue(tracker.makeRoom(inventory, new ItemStack(Items.SAND), -1, Map.of("a", a, "b", b)::get));
        assertEquals(64, a.countItem(Items.STONE));
        assertTrue(b.isEmpty());
    }

    @Test
    void unknownInventoryCannotBeDumpedIntoNewSource() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        var source = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        assertFalse(tracker.take(inventory, 0, "new", source, 0, false, -1, ignored -> null));
        assertEquals(64, source.countItem(Items.DIRT));
        assertEquals(36 * 64, inventory.countItem(Items.COBBLESTONE));
    }

    @Test
    void fullOriginalContainerDoesNotReceivePartialStackOrLoseNewTake() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(1, new ItemStack(Items.STONE, 64));
        var home = new SimpleContainer(new ItemStack(Items.STONE, 32));
        var source = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        tracker.recordTake(inventory, "home", inventory.getItem(1));
        assertFalse(tracker.take(inventory, 0, "new", source, 0, false, -1, ignored -> home));
        assertEquals(32, home.countItem(Items.STONE));
        assertEquals(64, inventory.countItem(Items.STONE));
        assertEquals(64, source.countItem(Items.DIRT));
    }

    @Test
    void unavailableSourceCanBeSkippedWithoutRedirectingItsMaterial() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(1, new ItemStack(Items.STONE, 64));
        inventory.setItem(2, new ItemStack(Items.DIRT, 64));
        var home = new SimpleContainer(2);
        tracker.recordTake(inventory, "unloaded", inventory.getItem(1));
        tracker.recordTake(inventory, "available", inventory.getItem(2));
        assertTrue(tracker.makeRoom(inventory, new ItemStack(Items.SAND), -1,
                id -> id.equals("available") ? home : null));
        assertEquals(64, inventory.countItem(Items.STONE));
        assertEquals(64, home.countItem(Items.DIRT));
        assertEquals(0, home.countItem(Items.STONE));
    }

    @Test
    void requestedMaterialIsNeverReturnedToMakeRoom() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(1, new ItemStack(Items.STONE, 64));
        var home = new SimpleContainer(2);
        tracker.recordTake(inventory, "home", inventory.getItem(1));
        assertFalse(tracker.makeRoom(inventory, new ItemStack(Items.STONE), -1, ignored -> home));
        assertTrue(home.isEmpty());
    }

    @Test
    void customizedStacksRemainDistinctFromPlainMaterials() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(0, new ItemStack(Items.STONE, 64));
        var named = new ItemStack(Items.STONE, 12);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
        inventory.setItem(1, named);
        var home = new SimpleContainer(3);
        tracker.recordTake(inventory, "home", inventory.getItem(0));
        assertTrue(tracker.makeRoom(inventory, new ItemStack(Items.DIRT), -1, ignored -> home));
        assertSame(named, inventory.getItem(1));
        assertEquals(64, home.countItem(Items.STONE));
    }

    @Test
    void trackedToolsAndWholeShulkerItemsCanReturnToWorldSource() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
        inventory.setItem(2, new ItemStack(Items.SHULKER_BOX));
        var home = new SimpleContainer(3);
        tracker.recordTake(inventory, "home", inventory.getItem(1));
        tracker.recordTake(inventory, "home", inventory.getItem(2));
        assertEquals(2, tracker.returnAll(inventory, ignored -> home));
        assertEquals(1, home.countItem(Items.DIAMOND_PICKAXE));
        assertEquals(1, home.countItem(Items.SHULKER_BOX));
    }

    @Test
    void activeSourceShulkerIsNeverReturnedWhileTakingItsContents() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(0, new ItemStack(Items.SHULKER_BOX));
        tracker.recordTake(inventory, "world", inventory.getItem(0));
        var home = new SimpleContainer(2);
        var box = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        assertFalse(tracker.take(inventory, 0, "box", box, 0, false, 0, ignored -> home));
        assertEquals(1, inventory.countItem(Items.SHULKER_BOX));
        assertEquals(64, box.countItem(Items.DIRT));
        assertTrue(home.isEmpty());
    }

    @Test
    void knownMaterialCanReturnIntoSlotVacatedInSameSource() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(0, new ItemStack(Items.STONE, 64));
        tracker.recordTake(inventory, "same", inventory.getItem(0));
        var source = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        assertTrue(tracker.take(inventory, 0, "same", source, 0, false, -1, ignored -> null));
        assertEquals(64, source.countItem(Items.STONE));
        assertEquals(64, inventory.countItem(Items.DIRT));
    }

    @Test
    void singleTakeRollsBackWhenSameSourceHasInsufficientSpaceForReturn() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(0, new ItemStack(Items.STONE, 64));
        tracker.recordTake(inventory, "same", inventory.getItem(0));
        var source = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        assertFalse(tracker.take(inventory, 0, "same", source, 0, true, -1, ignored -> null));
        assertEquals(64, source.countItem(Items.DIRT));
        assertEquals(64, inventory.countItem(Items.STONE));
    }

    @Test
    void heldSourceShulkerIsFlushedBeforeMovingToFreeInventorySlot() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = new SimpleContainer(36);
        var heldBox = new ItemStack(Items.SHULKER_BOX);
        inventory.setItem(0, heldBox);
        var source = new SimpleContainer(27) {
            @Override public void setChanged() {
                heldBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(getItems()));
            }
        };
        source.setItem(0, new ItemStack(Items.DIRT, 64));
        assertTrue(tracker.take(inventory, 0, "box", source, 0, false, 0, ignored -> null));
        assertEquals(64, inventory.countItem(Items.DIRT));
        assertTrue(inventory.getItem(1).is(Items.SHULKER_BOX));
        assertEquals(0, inventory.getItem(1).get(DataComponents.CONTAINER).nonEmptyItemCopyStream()
                .mapToInt(ItemStack::getCount).sum());
    }

    @Test
    void insertionRestrictionsApplyToMergingAsWellAsEmptySlots() {
        Container restricted = new SimpleContainer(new ItemStack(Items.STONE, 1)) {
            @Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
        };
        assertFalse(ContainerReturnTracker.insertWhole(restricted, new ItemStack(Items.STONE, 1)));
        assertEquals(1, restricted.countItem(Items.STONE));
    }

    @Test
    void mergingIntoExistingStackNeedsNoReturnAndRemovesOnlyMergedAmount() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = fullInventory();
        inventory.setItem(2, new ItemStack(Items.DIRT, 60));
        var source = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        assertTrue(tracker.take(inventory, 0, "home", source, 0, false, -1,
                ignored -> { throw new AssertionError("No return should be needed"); }));
        assertEquals(64, inventory.countItem(Items.DIRT));
        assertEquals(60, source.countItem(Items.DIRT));
    }

    @Test
    void carriedShulkerMaterialsReturnAfterBoxMovesToAnotherSlot() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = new SimpleContainer(36);
        var box = new ItemStack(Items.SHULKER_BOX);
        String id = CarriedShulkerInventory.identify(box);
        inventory.setItem(5, box);
        var contents = CarriedShulkerInventory.open(box);
        contents.setItem(0, new ItemStack(Items.STONE, 64));
        assertTrue(tracker.take(inventory, 0, id, contents, 0, false, 5,
                source -> CarriedShulkerInventory.find(inventory, source)));
        inventory.setItem(14, box.copy());
        inventory.setItem(5, ItemStack.EMPTY);
        assertEquals(64, tracker.returnAll(inventory, source -> CarriedShulkerInventory.find(inventory, source)));
        assertEquals(64, CarriedShulkerInventory.open(inventory.getItem(14)).countItem(Items.STONE));
        assertEquals(0, inventory.countItem(Items.STONE));
    }

    @Test
    void unavailableOrDuplicatedSourceBoxCannotRedirectReturns() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = new SimpleContainer(36);
        inventory.setItem(0, new ItemStack(Items.STONE, 64));
        var box = new ItemStack(Items.SHULKER_BOX);
        String id = CarriedShulkerInventory.identify(box);
        tracker.recordTake(inventory, id, inventory.getItem(0));
        assertEquals(0, tracker.returnAll(inventory, source -> CarriedShulkerInventory.find(inventory, source)));
        inventory.setItem(5, box);
        inventory.setItem(6, box.copy());
        assertNull(CarriedShulkerInventory.find(inventory, id));
        assertEquals(0, tracker.returnAll(inventory, source -> CarriedShulkerInventory.find(inventory, source)));
        assertEquals(64, inventory.countItem(Items.STONE));
    }

    @Test
    void worldTakenShulkerCanReturnAsWholeBoxAfterItsContentsChange() {
        var tracker = new ContainerReturnTracker<String>();
        var inventory = new SimpleContainer(36);
        var world = new SimpleContainer(new ItemStack(Items.SHULKER_BOX));
        assertTrue(tracker.take(inventory, 0, "world", world, 0, false, -1, ignored -> null));
        assertFalse(CarriedShulkerInventory.id(inventory.getItem(0)).isBlank());
        CarriedShulkerInventory.open(inventory.getItem(0)).setItem(2, new ItemStack(Items.DIRT, 17));
        assertEquals(1, tracker.returnAll(inventory, ignored -> world));
        assertTrue(inventory.getItem(0).isEmpty());
        assertEquals(17, CarriedShulkerInventory.open(world.getItem(0)).countItem(Items.DIRT));
    }

    @Test
    void shulkerCannotBeNestedDuringReturn() {
        var outer = new ItemStack(Items.SHULKER_BOX);
        var contents = CarriedShulkerInventory.open(outer);
        assertFalse(ContainerReturnTracker.insertWhole(contents, new ItemStack(Items.SHULKER_BOX)));
        assertTrue(contents.isEmpty());
    }
}
