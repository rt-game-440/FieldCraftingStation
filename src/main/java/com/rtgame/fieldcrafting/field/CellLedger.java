package com.rtgame.fieldcrafting.field;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * 物品清单格子的结算：比较一次点击前后的格子，把差异落实到真实容器。
 * <p>
 * 先取出后存入，这样交换物品时腾出的空位可以用上。结算不假设操作一定成功，取不出和存不下的部分都如实返回，
 * 由调用方从玩家那里收回或退还给玩家，保证物品总数不变。
 */
public final class CellLedger {

    /** 目标容器下标：汇总视图（由近到远取出、按汇总规则存入）。 */
    public static final int SUMMARY = -1;

    /** 一次结算的结果。 */
    public static final class Result {

        /** 玩家已经从格子拿走、但真实容器取不出的物品，要从玩家那里收回。 */
        public final List<ItemStack> owedByPlayer = new ArrayList<>();
        /** 玩家放进格子、但真实容器存不下的物品，要退还给玩家。 */
        public final List<ItemStack> unplaced = new ArrayList<>();
    }

    private CellLedger() {}

    public static boolean hasChanges(ItemStack[] before, ItemStack[] after) {
        for (int i = 0; i < before.length; i++) {
            boolean had = !Stacks.isEmpty(before[i]);
            boolean has = !Stacks.isEmpty(after[i]);
            if (had != has) return true;
            if (had && (!Stacks.same(before[i], after[i]) || before[i].stackSize != after[i].stackSize)) return true;
        }
        return false;
    }

    /**
     * @param field     真实容器
     * @param container 分容器视图选中的容器下标，或 {@link #SUMMARY}
     * @param before    点击前的格子
     * @param source    分容器视图里每格对应的容器槽位号
     * @param after     点击后的格子
     */
    public static Result settle(FieldInventory field, int container, ItemStack[] before, int[] source,
        ItemStack[] after) {
        Result result = new Result();
        for (int i = 0; i < before.length; i++) {
            int removed = removedAmount(before[i], after[i]);
            if (removed <= 0) continue;
            ItemStack taken = container == SUMMARY ? field.extractSummary(before[i], removed)
                : field.extractFromSlot(container, source[i], removed);
            int got = taken == null ? 0 : taken.stackSize;
            if (got < removed) result.owedByPlayer.add(Stacks.copyWithSize(before[i], removed - got));
        }
        for (int i = 0; i < before.length; i++) {
            int added = addedAmount(before[i], after[i]);
            if (added <= 0) continue;
            ItemStack stack = Stacks.copyWithSize(after[i], added);
            ItemStack rest = container == SUMMARY ? field.insertSummary(stack) : field.insertInto(container, stack);
            if (!Stacks.isEmpty(rest)) result.unplaced.add(rest);
        }
        return result;
    }

    private static int removedAmount(ItemStack before, ItemStack after) {
        if (Stacks.isEmpty(before)) return 0;
        if (!Stacks.isEmpty(after) && Stacks.same(before, after)) {
            return Math.max(0, before.stackSize - after.stackSize);
        }
        return before.stackSize;
    }

    private static int addedAmount(ItemStack before, ItemStack after) {
        if (Stacks.isEmpty(after)) return 0;
        if (!Stacks.isEmpty(before) && Stacks.same(before, after)) {
            return Math.max(0, after.stackSize - before.stackSize);
        }
        return after.stackSize;
    }
}
