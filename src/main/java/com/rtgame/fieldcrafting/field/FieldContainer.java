package com.rtgame.fieldcrafting.field;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * 场域库存眼中的一个容器：一个物品栏加上它到场域合成站的距离。
 */
public final class FieldContainer {

    public final IInventory inventory;
    /** 到场域合成站的距离平方，用于由近到远排序。 */
    public final double distanceSq;
    /** 展示用名称（方块名或坐标），可为空。 */
    public final String label;
    public final int x;
    public final int y;
    public final int z;
    private final SlotRules rules;
    /** 只暴露前 slotLimit 个槽位（用于玩家背包只取主物品栏 36 格）。 */
    private final int slotLimit;

    public FieldContainer(IInventory inventory, double distanceSq, String label, int x, int y, int z, SlotRules rules,
        int slotLimit) {
        this.inventory = inventory;
        this.distanceSq = distanceSq;
        this.label = label == null ? "" : label;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rules = rules == null ? SlotRules.ALL : rules;
        this.slotLimit = slotLimit;
    }

    public FieldContainer(IInventory inventory, double distanceSq) {
        this(inventory, distanceSq, "", 0, 0, 0, SlotRules.ALL, Integer.MAX_VALUE);
    }

    public int slotCount() {
        return Math.min(inventory.getSizeInventory(), slotLimit);
    }

    public boolean isUsable(int slot) {
        return slot >= 0 && slot < slotCount() && rules.isUsable(slot);
    }

    public boolean canExtract(int slot, ItemStack stack) {
        return isUsable(slot) && rules.canExtract(slot, stack);
    }

    public boolean canInsert(int slot, ItemStack stack) {
        return isUsable(slot) && rules.canInsert(slot, stack) && inventory.isItemValidForSlot(slot, stack);
    }
}
