package com.rtgame.fieldcrafting.gui;

import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;

import com.rtgame.fieldcrafting.station.TileFieldStation;

/**
 * 原版的合成网格，只是物品实际存在站的方块实体里：关闭界面不丢，多人打开看到同一份。
 * 配方匹配、其他 mod 看到的都是标准的 {@link InventoryCrafting}。
 */
public class FieldCraftMatrix extends InventoryCrafting {

    private final Container owner;
    private final TileFieldStation tile;

    public FieldCraftMatrix(Container owner, TileFieldStation tile) {
        super(owner, 3, 3);
        this.owner = owner;
        this.tile = tile;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return slot >= getSizeInventory() ? null : tile.getGridStack(slot);
    }

    /** 原版工作台关闭时用它把网格物品掉出来；站的网格是持久的，什么也不掉。 */
    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        return null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        ItemStack taken = decrQuietly(slot, amount);
        if (taken != null) notifyChanged();
        return taken;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        tile.setGridStack(slot, stack);
        notifyChanged();
    }

    /** 减少格子里的物品但不重新匹配配方。批量操作结束后调用 {@link #notifyChanged}。 */
    public ItemStack decrQuietly(int slot, int amount) {
        ItemStack stack = tile.getGridStack(slot);
        if (stack == null) return null;
        if (stack.stackSize <= amount) {
            tile.setGridStack(slot, null);
            return stack;
        }
        ItemStack taken = stack.splitStack(amount);
        tile.markDirty();
        return taken;
    }

    /** 设置格子但不重新匹配配方。批量操作结束后调用 {@link #notifyChanged}。 */
    public void setQuietly(int slot, ItemStack stack) {
        tile.setGridStack(slot, stack);
    }

    public void notifyChanged() {
        owner.onCraftMatrixChanged(this);
    }

    @Override
    public void markDirty() {
        tile.markDirty();
    }
}
