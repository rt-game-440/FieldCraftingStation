package com.rtgame.fieldcrafting.field;

import static com.rtgame.fieldcrafting.field.FakeInventory.APPLE;
import static com.rtgame.fieldcrafting.field.FakeInventory.STICK;
import static com.rtgame.fieldcrafting.field.FakeInventory.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.Test;

class CellLedgerTest {

    /** 能看见但不能取出的容器，相当于关闭了自动输出的总线。 */
    private static final SlotRules NO_EXTRACT = new SlotRules() {

        @Override
        public boolean isUsable(int slot) {
            return true;
        }

        @Override
        public boolean canExtract(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public boolean canInsert(int slot, ItemStack stack) {
            return true;
        }
    };

    private static final int[] NO_SOURCE = { -1 };

    private static FieldInventory field(FakeInventory... inventories) {
        FieldContainer[] containers = new FieldContainer[inventories.length];
        for (int i = 0; i < inventories.length; i++) containers[i] = new FieldContainer(inventories[i], i + 1);
        return new FieldInventory(null, Arrays.asList(containers));
    }

    private static FieldInventory locked(FakeInventory inventory) {
        return new FieldInventory(
            null,
            Arrays.asList(new FieldContainer(inventory, 1, "", 0, 0, 0, NO_EXTRACT, Integer.MAX_VALUE)));
    }

    private static CellLedger.Result settle(FieldInventory field, int container, int[] source, ItemStack before,
        ItemStack after) {
        return CellLedger.settle(field, container, new ItemStack[] { before }, source, new ItemStack[] { after });
    }

    @Test
    void takingFromCellExtractsFromContainer() {
        FakeInventory chest = new FakeInventory(2).with(0, stack(APPLE, 40));
        CellLedger.Result result = settle(field(chest), CellLedger.SUMMARY, NO_SOURCE, stack(APPLE, 40), null);

        assertEquals(0, chest.total(APPLE));
        assertTrue(result.owedByPlayer.isEmpty());
        assertTrue(result.unplaced.isEmpty());
    }

    @Test
    void partialTakeOnlyExtractsDifference() {
        FakeInventory chest = new FakeInventory(2).with(0, stack(APPLE, 40));
        settle(field(chest), CellLedger.SUMMARY, NO_SOURCE, stack(APPLE, 40), stack(APPLE, 20));

        assertEquals(20, chest.total(APPLE));
    }

    /** 回归：取不出时必须记为玩家欠账，否则玩家白得一组（复制）。 */
    @Test
    void itemsThatCannotBeExtractedAreOwedByPlayer() {
        FakeInventory bus = new FakeInventory(1).with(0, stack(APPLE, 40));
        CellLedger.Result result = settle(locked(bus), CellLedger.SUMMARY, NO_SOURCE, stack(APPLE, 40), null);

        assertEquals(40, bus.total(APPLE));
        assertEquals(1, result.owedByPlayer.size());
        assertEquals(40, result.owedByPlayer.get(0).stackSize);
    }

    @Test
    void containerViewExtractsFromTheSourceSlot() {
        FakeInventory chest = new FakeInventory(2).with(0, stack(APPLE, 10))
            .with(1, stack(APPLE, 30));
        settle(field(chest), 0, new int[] { 1 }, stack(APPLE, 30), null);

        assertEquals(10, chest.slots[0].stackSize);
        assertEquals(null, chest.slots[1]);
    }

    /** 选中的容器已经不在范围内：取不出，全部记为欠账；放进去的全部退还。 */
    @Test
    void missingContainerOwesEverythingAndRefundsEverything() {
        FakeInventory chest = new FakeInventory(1).with(0, stack(APPLE, 10));
        CellLedger.Result result = settle(field(chest), 5, new int[] { 0 }, stack(APPLE, 10), stack(STICK, 3));

        assertEquals(10, chest.total(APPLE));
        assertEquals(10, result.owedByPlayer.get(0).stackSize);
        assertEquals(3, result.unplaced.get(0).stackSize);
    }

    @Test
    void addedItemsAreStoredAndOverflowIsReturned() {
        FakeInventory chest = new FakeInventory(1).with(0, stack(APPLE, 60));
        CellLedger.Result result = settle(
            field(chest),
            CellLedger.SUMMARY,
            NO_SOURCE,
            stack(APPLE, 60),
            stack(APPLE, 70));

        assertEquals(64, chest.total(APPLE));
        assertEquals(6, result.unplaced.get(0).stackSize);
    }

    /** 交换：先取出再存入，腾出的空位可以用上。 */
    @Test
    void swapExtractsBeforeInserting() {
        FakeInventory chest = new FakeInventory(1).with(0, stack(APPLE, 10));
        CellLedger.Result result = settle(
            field(chest),
            CellLedger.SUMMARY,
            NO_SOURCE,
            stack(APPLE, 10),
            stack(STICK, 5));

        assertEquals(0, chest.total(APPLE));
        assertEquals(5, chest.total(STICK));
        assertTrue(result.unplaced.isEmpty());
    }

    @Test
    void hasChangesDetectsAmountAndKind() {
        ItemStack[] before = { stack(APPLE, 10), null };
        assertFalse(CellLedger.hasChanges(before, new ItemStack[] { stack(APPLE, 10), null }));
        assertTrue(CellLedger.hasChanges(before, new ItemStack[] { stack(APPLE, 9), null }));
        assertTrue(CellLedger.hasChanges(before, new ItemStack[] { stack(STICK, 10), null }));
        assertTrue(CellLedger.hasChanges(before, new ItemStack[] { null, null }));
    }

    @Test
    void summaryAndCountsTellVisibleFromExtractable() {
        FakeInventory chest = new FakeInventory(1).with(0, stack(APPLE, 10));
        FakeInventory bus = new FakeInventory(1).with(0, stack(APPLE, 30));
        FieldInventory field = new FieldInventory(
            null,
            Arrays.asList(
                new FieldContainer(chest, 1),
                new FieldContainer(bus, 4, "", 0, 0, 0, NO_EXTRACT, Integer.MAX_VALUE)));

        List<FieldInventory.SummaryEntry> summary = field.summary();
        assertEquals(40, summary.get(0).count);
        assertEquals(10, summary.get(0).extractable);
        assertEquals(10, field.countMaterial(stack(APPLE, 1)));
    }
}
