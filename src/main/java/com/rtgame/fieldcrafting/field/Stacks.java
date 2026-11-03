package com.rtgame.fieldcrafting.field;

import net.minecraft.item.ItemStack;

/** ItemStack 的小工具：忽略数量比较物品种类。 */
public final class Stacks {

    private Stacks() {}

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getItem() == null || stack.stackSize <= 0;
    }

    /** 两个物品是否为同一种（物品、损伤值、NBT 都相同，不比较数量）。 */
    public static boolean same(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage()
            && ItemStack.areItemStackTagsEqual(a, b);
    }

    /** 复制并设置数量。 */
    public static ItemStack copyWithSize(ItemStack stack, int size) {
        ItemStack copy = stack.copy();
        copy.stackSize = size;
        return copy;
    }
}
