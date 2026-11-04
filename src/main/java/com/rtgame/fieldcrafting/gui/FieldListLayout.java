package com.rtgame.fieldcrafting.gui;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.item.ItemStack;

import com.rtgame.fieldcrafting.field.Stacks;

/**
 * 把一串清单条目按搜索词和滚动位置摆进格子。服务端和客户端用同一个方法算，结果一致：
 * 客户端立刻显示，服务端据此对账。
 */
public final class FieldListLayout {

    private FieldListLayout() {}

    /** 清单里的一项。汇总视图按整组拆成多格；分容器视图的一项对应容器的一个槽位，只占一格。 */
    public static final class Entry {

        /** 数量为 1 的样本。 */
        public final ItemStack stack;
        /** 能取出的数量。 */
        public final long free;
        /** 只能看不能取的数量。 */
        public final long stuck;
        /** 分容器视图里的容器槽位号；汇总视图为 -1。 */
        public final int source;
        private String name;

        public Entry(ItemStack stack, long free, long stuck, int source) {
            this.stack = stack;
            this.free = free;
            this.stuck = stuck;
            this.source = source;
        }

        /** 小写的显示名称。有些 mod 的物品取名字会抛异常，当作没有名字。 */
        public String name() {
            if (name == null) {
                try {
                    name = normalize(stack.getDisplayName());
                } catch (Throwable t) {
                    name = "";
                }
            }
            return name;
        }

        private int maxStack() {
            return Math.max(1, stack.getMaxStackSize());
        }

        long chunks() {
            if (source >= 0) return 1;
            long max = maxStack();
            return (free + max - 1) / max + (stuck + max - 1) / max;
        }
    }

    /** 摆放结果。 */
    public static final class Result {

        public final ItemStack[] stacks;
        public final boolean[] locked;
        public final int[] source;
        public int totalRows;

        Result(int cells) {
            stacks = new ItemStack[cells];
            locked = new boolean[cells];
            source = new int[cells];
            Arrays.fill(source, -1);
        }
    }

    public static String normalize(String text) {
        return text == null ? ""
            : text.trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * @param filter 已 {@link #normalize} 的搜索词，空串表示不过滤
     * @param cells  要摆的格子数
     */
    public static Result compute(List<Entry> entries, String filter, int scrollRow, int columns, int cells) {
        Result out = new Result(cells);
        long from = (long) scrollRow * columns;
        long pos = 0;
        for (Entry e : entries) {
            if (!filter.isEmpty() && !e.name()
                .contains(filter)) continue;
            long n = e.chunks();
            if (pos + n > from && pos < from + cells) place(e, n, pos, from, cells, out);
            pos += n;
        }
        out.totalRows = (int) Math.min(Integer.MAX_VALUE, (pos + columns - 1) / columns);
        return out;
    }

    /** 能取出的排在前面，只能看不能取的单独成格。 */
    private static void place(Entry e, long n, long start, long from, int cells, Result out) {
        int max = e.source >= 0 ? Integer.MAX_VALUE : e.maxStack();
        long free = e.free;
        long stuck = e.stuck;
        for (long c = 0; c < n; c++) {
            boolean isStuck = free == 0;
            int size = (int) Math.min(isStuck ? stuck : free, max);
            if (e.source >= 0) size = Math.min(size, e.maxStack());
            if (isStuck) stuck -= size;
            else free -= size;
            long cell = start + c - from;
            if (cell < 0) continue;
            if (cell >= cells) return;
            out.stacks[(int) cell] = Stacks.copyWithSize(e.stack, size);
            out.locked[(int) cell] = isStuck;
            out.source[(int) cell] = e.source;
        }
    }
}
