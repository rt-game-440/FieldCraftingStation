package com.rtgame.fieldcrafting.field;

import java.util.function.UnaryOperator;

import net.minecraft.item.ItemStack;

/**
 * 合成网格的快捷操作。每个方法都不修改传入的数组，返回新的 3×3 网格。
 */
public final class GridTweaks {

    private static final int SIDE = 3;

    private GridTweaks() {}

    /** 顺时针旋转 90 度。 */
    public static ItemStack[] rotate(ItemStack[] grid) {
        ItemStack[] out = new ItemStack[grid.length];
        for (int row = 0; row < SIDE; row++) {
            for (int col = 0; col < SIDE; col++) {
                out[col * SIDE + (SIDE - 1 - row)] = grid[row * SIDE + col];
            }
        }
        return out;
    }

    /** 同种物品在各自占用的格子之间平分，除不尽的多给靠前的格子。 */
    public static ItemStack[] balance(ItemStack[] grid) {
        ItemStack[] out = copy(grid);
        boolean[] done = new boolean[out.length];
        for (int i = 0; i < out.length; i++) {
            if (done[i] || Stacks.isEmpty(out[i])) continue;
            int total = 0;
            int slots = 0;
            for (int j = i; j < out.length; j++) {
                if (!Stacks.isEmpty(out[j]) && Stacks.same(out[i], out[j])) {
                    total += out[j].stackSize;
                    slots++;
                }
            }
            int seen = 0;
            ItemStack kind = out[i].copy();
            for (int j = i; j < out.length; j++) {
                if (!Stacks.isEmpty(out[j]) && Stacks.same(kind, out[j])) {
                    out[j].stackSize = total / slots + (seen < total % slots ? 1 : 0);
                    done[j] = true;
                    seen++;
                }
            }
        }
        return out;
    }

    /** 把物品摊到空格：每个空格分走当前最大一堆的一半，再整体均分。 */
    public static ItemStack[] spread(ItemStack[] grid) {
        ItemStack[] out = copy(grid);
        for (int i = 0; i < out.length; i++) {
            if (!Stacks.isEmpty(out[i])) continue;
            int largest = -1;
            for (int j = 0; j < out.length; j++) {
                if (Stacks.isEmpty(out[j]) || out[j].stackSize < 2) continue;
                if (largest < 0 || out[j].stackSize > out[largest].stackSize) largest = j;
            }
            if (largest < 0) break;
            int half = out[largest].stackSize / 2;
            out[largest].stackSize -= half;
            out[i] = Stacks.copyWithSize(out[largest], half);
        }
        return balance(out);
    }

    /** 把每格物品交给 sink，sink 返回放不下的部分，这部分留在格子里。 */
    public static ItemStack[] clear(ItemStack[] grid, UnaryOperator<ItemStack> sink) {
        ItemStack[] out = new ItemStack[grid.length];
        for (int i = 0; i < grid.length; i++) {
            out[i] = Stacks.isEmpty(grid[i]) ? null : sink.apply(grid[i].copy());
        }
        return out;
    }

    private static ItemStack[] copy(ItemStack[] grid) {
        ItemStack[] out = new ItemStack[grid.length];
        for (int i = 0; i < grid.length; i++) {
            if (!Stacks.isEmpty(grid[i])) out[i] = grid[i].copy();
        }
        return out;
    }
}
