package com.rtgame.fieldcrafting.field;

import static com.rtgame.fieldcrafting.field.FakeInventory.APPLE;
import static com.rtgame.fieldcrafting.field.FakeInventory.STICK;
import static com.rtgame.fieldcrafting.field.FakeInventory.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.Test;

class GridTweaksTest {

    private static int total(ItemStack[] grid) {
        int sum = 0;
        for (ItemStack s : grid) if (s != null) sum += s.stackSize;
        return sum;
    }

    @Test
    void rotateMovesClockwiseAndKeepsCenter() {
        ItemStack[] grid = new ItemStack[9];
        grid[0] = stack(APPLE, 1); // 左上 → 右上
        grid[3] = stack(STICK, 2); // 左中 → 上中
        grid[4] = stack(APPLE, 3); // 中心不动

        ItemStack[] out = GridTweaks.rotate(grid);

        assertEquals(1, out[2].stackSize);
        assertEquals(2, out[1].stackSize);
        assertEquals(3, out[4].stackSize);
        assertNull(out[0]);
    }

    @Test
    void fourRotationsRestoreTheGrid() {
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < 9; i++) grid[i] = stack(APPLE, i + 1);

        ItemStack[] out = grid;
        for (int i = 0; i < 4; i++) out = GridTweaks.rotate(out);

        for (int i = 0; i < 9; i++) assertSame(grid[i], out[i]);
    }

    @Test
    void balanceSplitsEachKindOnlyAmongItsOwnSlots() {
        ItemStack[] grid = new ItemStack[9];
        grid[0] = stack(APPLE, 10);
        grid[1] = stack(APPLE, 1);
        grid[2] = stack(APPLE, 2);
        grid[3] = stack(STICK, 5);

        ItemStack[] out = GridTweaks.balance(grid);

        assertEquals(5, out[0].stackSize);
        assertEquals(4, out[1].stackSize);
        assertEquals(4, out[2].stackSize);
        assertEquals(5, out[3].stackSize);
        assertNull(out[4]);
        assertEquals(10, grid[0].stackSize); // 不修改传入的网格
    }

    @Test
    void spreadFillsEmptySlotsWithoutLosingItems() {
        ItemStack[] grid = new ItemStack[9];
        grid[0] = stack(APPLE, 18);

        ItemStack[] out = GridTweaks.spread(grid);

        for (ItemStack s : out) assertEquals(2, s.stackSize);
        assertEquals(18, total(out));
    }

    @Test
    void spreadStopsWhenNothingCanBeSplit() {
        ItemStack[] grid = new ItemStack[9];
        grid[0] = stack(APPLE, 2);
        grid[1] = stack(STICK, 1);

        ItemStack[] out = GridTweaks.spread(grid);

        assertEquals(3, total(out));
        assertEquals(1, out[0].stackSize);
        assertEquals(1, out[1].stackSize);
        assertEquals(1, out[2].stackSize);
        assertNull(out[3]);
    }

    @Test
    void clearKeepsWhatTheSinkCannotTake() {
        ItemStack[] grid = new ItemStack[9];
        grid[0] = stack(APPLE, 10);
        grid[1] = stack(STICK, 4);

        ItemStack[] out = GridTweaks.clear(grid, s -> s.getItem() == APPLE ? null : Stacks.copyWithSize(s, 1));

        assertNull(out[0]);
        assertEquals(1, out[1].stackSize);
    }
}
