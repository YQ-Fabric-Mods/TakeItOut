package net.maxbel.takeitout;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Session-only, type-level provenance. Ordinary materials receive no per-stack provenance tags. */
public final class ContainerReturnTracker<S> {
    private record Entry<S>(S source, ItemStack item) {}

    private final List<Entry<S>> entries = new ArrayList<>();

    /** A single take commits only after a destination exists; failed takes restore the source slot. */
    public boolean take(Container inventory, int selectedSlot, S source, Container container,
                        int sourceSlot, boolean singleItemMode, int sourceShulkerSlot,
                        Function<S, Container> resolve) {
        if (sourceSlot < 0 || sourceSlot >= container.getContainerSize()) return false;
        ItemStack original = container.getItem(sourceSlot);
        if (original.isEmpty()) return false;
        ItemStack extracted = original.copyWithCount(singleItemMode ? 1 : original.getCount());
        if (CarriedShulkerInventory.isShulker(extracted)) CarriedShulkerInventory.identify(extracted);

        for (int i = 0; i < size(inventory); i++) {
            if (i == sourceShulkerSlot) continue;
            ItemStack held = inventory.getItem(i);
            if (!matches(extracted, held)) continue;
            int moved = Math.min(extracted.getCount(), held.getMaxStackSize() - held.getCount());
            if (moved <= 0) continue;
            container.setItem(sourceSlot, original.copyWithCount(original.getCount() - moved));
            held.grow(moved);
            container.setChanged();
            recordTake(inventory, source, extracted);
            inventory.setChanged();
            return true;
        }

        // Returning to this same source may use the space vacated by the take.
        container.setItem(sourceSlot, original.copyWithCount(original.getCount() - extracted.getCount()));
        if (!inventory.getItem(selectedSlot).isEmpty() && emptySlot(inventory) == -1
                && !makeRoom(inventory, extracted, sourceShulkerSlot,
                        target -> target.equals(source) ? container : resolve.apply(target))) {
            container.setItem(sourceSlot, original);
            container.setChanged();
            return false;
        }

        // Flush a source shulker before copying it out of the main hand.
        container.setChanged();
        ItemStack hand = inventory.getItem(selectedSlot);
        if (!hand.isEmpty()) {
            int free = emptySlot(inventory);
            if (free == -1) throw new IllegalStateException("A complete return must free an inventory slot");
            inventory.setItem(free, hand.copy());
        }
        inventory.setItem(selectedSlot, extracted);
        recordTake(inventory, source, extracted);
        inventory.setChanged();
        return true;
    }

    private static int emptySlot(Container inventory) {
        for (int i = 0; i < size(inventory); i++) {
            if (inventory.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    public void recordTake(Container inventory, S source, ItemStack item) {
        prune(inventory);
        if (item.isEmpty()) return;
        for (Entry<S> entry : entries) {
            if (entry.source().equals(source) && matches(entry.item(), item)) return;
        }
        // Insertion order is the first take order, unchanged by later refills.
        entries.add(new Entry<>(source, item.copyWithCount(1)));
    }

    /** Return the oldest available material to its recorded source, never to an arbitrary container. */
    public boolean makeRoom(Container inventory, ItemStack requested, int excludedSlot,
                            Function<S, Container> resolve) {
        prune(inventory);
        for (Entry<S> entry : new ArrayList<>(entries)) {
            if (entry.item().is(requested.getItem())) continue;
            if (returnEntry(inventory, entry, excludedSlot, resolve) > 0) {
                prune(inventory);
                return true;
            }
        }
        return false;
    }

    /** The manual return action uses exactly the same provenance rules as automatic returns. */
    public int returnAll(Container inventory, Function<S, Container> resolve) {
        prune(inventory);
        int moved = 0;
        for (Entry<S> entry : new ArrayList<>(entries)) {
            moved += returnEntry(inventory, entry, -1, resolve);
        }
        prune(inventory);
        return moved;
    }

    private int returnEntry(Container inventory, Entry<S> entry, int excludedSlot,
                            Function<S, Container> resolve) {
        boolean present = false;
        for (int i = 0; i < size(inventory); i++) {
            if (i != excludedSlot && matches(entry.item(), inventory.getItem(i))) {
                present = true;
                break;
            }
        }
        if (!present) return 0;
        Container target = resolve.apply(entry.source());
        if (target == null || target == inventory) return 0;

        int moved = 0;
        for (int i = 0; i < size(inventory); i++) {
            if (i == excludedSlot) continue;
            ItemStack stack = inventory.getItem(i);
            if (!matches(entry.item(), stack) || !insertWhole(target, stack)) continue;
            moved += stack.getCount();
            inventory.setItem(i, ItemStack.EMPTY);
        }
        return moved;
    }

    private void prune(Container inventory) {
        entries.removeIf(entry -> {
            for (int i = 0; i < size(inventory); i++) {
                if (matches(entry.item(), inventory.getItem(i))) return false;
            }
            return true;
        });
    }

    private static int size(Container inventory) {
        return Math.min(36, inventory.getContainerSize());
    }

    private static boolean matches(ItemStack key, ItemStack stack) {
        if (CarriedShulkerInventory.isShulker(key) && CarriedShulkerInventory.isShulker(stack)) {
            String id = CarriedShulkerInventory.id(key);
            if (!id.isBlank() && id.equals(CarriedShulkerInventory.id(stack))) {
                // A known box remains the same material when its contents change.
                ItemStack left = key.copyWithCount(1);
                ItemStack right = stack.copyWithCount(1);
                left.remove(DataComponents.CONTAINER);
                right.remove(DataComponents.CONTAINER);
                return ItemStack.isSameItemSameComponents(left, right);
            }
        }
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(key, stack);
    }

    /** Preflight the entire stack before changing either inventory. A partial return frees no slot. */
    static boolean insertWhole(Container target, ItemStack stack) {
        int capacity = 0;
        for (int i = 0; i < target.getContainerSize(); i++) {
            if (!target.canPlaceItem(i, stack)) continue;
            ItemStack existing = target.getItem(i);
            int max = Math.min(target.getMaxStackSize(), stack.getMaxStackSize());
            if (existing.isEmpty()) capacity += max;
            else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                capacity += Math.max(0, max - existing.getCount());
            }
            if (capacity >= stack.getCount()) break;
        }
        if (capacity < stack.getCount()) return false;

        ItemStack remaining = stack.copy();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < target.getContainerSize() && !remaining.isEmpty(); i++) {
                if (!target.canPlaceItem(i, remaining)) continue;
                ItemStack existing = target.getItem(i);
                if (pass == 0 ? existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)
                        : !existing.isEmpty()) continue;
                int max = Math.min(target.getMaxStackSize(), remaining.getMaxStackSize());
                int move = Math.min(remaining.getCount(), Math.max(0, max - existing.getCount()));
                if (move == 0) continue;
                ItemStack replacement = remaining.copyWithCount(existing.getCount() + move);
                target.setItem(i, replacement);
                remaining.shrink(move);
            }
        }
        target.setChanged();
        return true;
    }
}
