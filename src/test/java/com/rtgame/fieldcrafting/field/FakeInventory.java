package com.rtgame.fieldcrafting.field;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 测试用的假容器，不依赖游戏世界。 */
final class FakeInventory implements IInventory {

    /** 测试用物品：不需要注册表即可使用。 */
    static final class TestItem extends Item {

        final String name;

        TestItem(String name, int maxStack) {
            this.name = name;
            setMaxStackSize(maxStack);
            setHasSubtypes(true);
        }
    }

    static final Item APPLE = new TestItem("apple", 64);
    static final Item STICK = new TestItem("stick", 64);
    static final Item BUCKET = new TestItem("bucket", 16);

    final ItemStack[] slots;
    int dirtyCount;

    FakeInventory(int size) {
        slots = new ItemStack[size];
    }

    static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count, 0);
    }

    FakeInventory with(int slot, ItemStack stack) {
        slots[slot] = stack;
        return this;
    }

    int total(Item item) {
        int n = 0;
        for (ItemStack s : slots) if (s != null && s.getItem() == item) n += s.stackSize;
        return n;
    }

    @Override
    public int getSizeInventory() {
        return slots.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return slots[slot];
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        if (slots[slot] == null) return null;
        ItemStack result;
        if (slots[slot].stackSize <= amount) {
            result = slots[slot];
            slots[slot] = null;
        } else {
            result = slots[slot].splitStack(amount);
            if (slots[slot].stackSize == 0) slots[slot] = null;
        }
        return result;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        ItemStack s = slots[slot];
        slots[slot] = null;
        return s;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        slots[slot] = stack;
    }

    @Override
    public String getInventoryName() {
        return "fake";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {
        dirtyCount++;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return true;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return true;
    }
}
