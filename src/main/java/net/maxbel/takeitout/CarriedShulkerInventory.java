package net.maxbel.takeitout;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.UUID;

/** An adapter for a particular source box, independent of its current player-inventory slot. */
final class CarriedShulkerInventory extends SimpleContainer {
    private static final String SOURCE_ID = "takeitout_source_id";
    private final ItemStack box;

    private CarriedShulkerInventory(ItemStack box) {
        super(contents(box));
        this.box = box;
    }

    private static ItemStack[] contents(ItemStack box) {
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
        return contents.toArray(ItemStack[]::new);
    }

    static boolean isShulker(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem item
                && item.getBlock() instanceof ShulkerBoxBlock;
    }

    static String id(ItemStack box) {
        return box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getStringOr(SOURCE_ID, "");
    }

    static String identify(ItemStack box) {
        String id = id(box);
        if (id.isBlank()) {
            id = UUID.randomUUID().toString();
            String newId = id;
            CustomData.update(DataComponents.CUSTOM_DATA, box, tag -> tag.putString(SOURCE_ID, newId));
        }
        return id;
    }

    static CarriedShulkerInventory open(ItemStack box) {
        return new CarriedShulkerInventory(box);
    }

    static CarriedShulkerInventory find(Container inventory, String id) {
        ItemStack found = null;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isShulker(stack) || stack.getCount() != 1 || !id.equals(id(stack))) continue;
            if (found != null) return null; // Duplicated identity: never guess which box to use.
            found = stack;
        }
        return found == null ? null : open(found);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !isShulker(stack); // Vanilla forbids nesting shulker boxes.
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (box != null) box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(getItems()));
    }
}
