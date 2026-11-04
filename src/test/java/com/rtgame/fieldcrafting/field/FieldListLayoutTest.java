package com.rtgame.fieldcrafting.field;

import static com.rtgame.fieldcrafting.field.FakeInventory.APPLE;
import static com.rtgame.fieldcrafting.field.FakeInventory.STICK;
import static com.rtgame.fieldcrafting.field.FakeInventory.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rtgame.fieldcrafting.gui.FieldListLayout;

class FieldListLayoutTest {

    private static int max() {
        return stack(APPLE, 1).getMaxStackSize();
    }

    @Test
    void summarySplitsIntoFullStacksAndStuckLast() {
        int max = max();
        List<FieldListLayout.Entry> entries = Arrays
            .asList(new FieldListLayout.Entry(stack(APPLE, 1), max + 1L, 2, -1));

        FieldListLayout.Result r = FieldListLayout.compute(entries, "", 0, 9, 18);

        assertEquals(max, r.stacks[0].stackSize);
        assertEquals(1, r.stacks[1].stackSize);
        assertFalse(r.locked[1]);
        assertEquals(2, r.stacks[2].stackSize);
        assertTrue(r.locked[2]);
        assertNull(r.stacks[3]);
        assertEquals(1, r.totalRows);
    }

    @Test
    void scrollSkipsWholeRowsAndCountsAllRows() {
        int max = max();
        List<FieldListLayout.Entry> entries = Arrays
            .asList(new FieldListLayout.Entry(stack(APPLE, 1), max * 20L, 0, -1));

        FieldListLayout.Result r = FieldListLayout.compute(entries, "", 2, 9, 9);

        assertEquals(3, r.totalRows);
        for (int i = 0; i < 2; i++) assertEquals(max, r.stacks[i].stackSize);
        assertNull(r.stacks[2]);
    }

    @Test
    void containerViewUsesOneCellPerSlotAndKeepsSource() {
        List<FieldListLayout.Entry> entries = Arrays.asList(
            new FieldListLayout.Entry(stack(APPLE, 1), 3, 0, 7),
            new FieldListLayout.Entry(stack(STICK, 1), 0, 2, 9));

        FieldListLayout.Result r = FieldListLayout.compute(entries, "", 0, 9, 9);

        assertEquals(7, r.source[0]);
        assertEquals(3, r.stacks[0].stackSize);
        assertEquals(9, r.source[1]);
        assertTrue(r.locked[1]);
    }
}
