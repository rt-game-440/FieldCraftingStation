package com.rtgame.fieldcrafting.field;

import static com.rtgame.fieldcrafting.field.FakeInventory.APPLE;
import static com.rtgame.fieldcrafting.field.FakeInventory.BUCKET;
import static com.rtgame.fieldcrafting.field.FakeInventory.STICK;
import static com.rtgame.fieldcrafting.field.FakeInventory.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.Test;

class FieldInventoryTest {

    private static FieldContainer near(FakeInventory inv) {
        return new FieldContainer(inv, 1);
    }

    private static FieldContainer mid(FakeInventory inv) {
        return new FieldContainer(inv, 4);
    }

    private static FieldContainer far(FakeInventory inv) {
        return new FieldContainer(inv, 9);
    }

    private static FieldInventory field(FieldContainer... containers) {
        return new FieldInventory(null, Arrays.asList(containers));
    }

    // ------------------------------------------------------------ 汇总视图

    @Test
    void summaryMergesSameItemAcrossContainers() {
        FakeInventory a = new FakeInventory(3).with(0, stack(APPLE, 10))
            .with(2, stack(STICK, 5));
        FakeInventory b = new FakeInventory(3).with(1, stack(APPLE, 30));
        List<FieldInventory.SummaryEntry> summary = field(near(a), mid(b)).summary();

        assertEquals(2, summary.size());
        FieldInventory.SummaryEntry apple = summary.stream()
            .filter(e -> e.stack.getItem() == APPLE)
            .findFirst()
            .get();
        assertEquals(40, apple.count);
        FieldInventory.SummaryEntry stick = summary.stream()
            .filter(e -> e.stack.getItem() == STICK)
            .findFirst()
            .get();
        assertEquals(5, stick.count);
    }

    @Test
    void summaryTreatsDifferentDamageAsDifferentItems() {
        FakeInventory a = new FakeInventory(2).with(0, new ItemStack(APPLE, 3, 0))
            .with(1, new ItemStack(APPLE, 4, 1));
        assertEquals(
            2,
            field(near(a)).summary()
                .size());
    }

    @Test
    void unusableSlotsAreInvisible() {
        FakeInventory a = new FakeInventory(2).with(0, stack(APPLE, 10))
            .with(1, stack(STICK, 5));
        SlotRules onlyFirst = new SlotRules() {

            @Override
            public boolean isUsable(int slot) {
                return slot == 0;
            }

            @Override
            public boolean canExtract(int slot, ItemStack stack) {
                return true;
            }

            @Override
            public boolean canInsert(int slot, ItemStack stack) {
                return true;
            }
        };
        FieldInventory f = field(new FieldContainer(a, 1, "x", 0, 0, 0, onlyFirst, Integer.MAX_VALUE));
        assertEquals(
            1,
            f.summary()
                .size());
        assertNull(f.extractSummary(stack(STICK, 1), 64));
        assertEquals(5, a.total(STICK));
    }

    // ------------------------------------------------------------ 分容器视图

    @Test
    void containerViewGroupsByContainer() {
        FakeInventory a = new FakeInventory(3).with(0, stack(APPLE, 10))
            .with(2, stack(STICK, 5));
        FakeInventory b = new FakeInventory(3).with(1, stack(APPLE, 30));
        FieldInventory f = field(near(a), mid(b));

        List<FieldInventory.SlotEntry> first = f.containerItems(0);
        assertEquals(2, first.size());
        assertEquals(0, first.get(0).slot);
        assertEquals(10, first.get(0).stack.stackSize);
        assertEquals(2, first.get(1).slot);

        List<FieldInventory.SlotEntry> second = f.containerItems(1);
        assertEquals(1, second.size());
        assertEquals(1, second.get(0).slot);
        assertEquals(30, second.get(0).stack.stackSize);
    }

    @Test
    void containersAreSortedNearToFar() {
        FakeInventory nearInv = new FakeInventory(1).with(0, stack(APPLE, 1));
        FakeInventory farInv = new FakeInventory(1).with(0, stack(STICK, 1));
        FieldInventory f = field(far(farInv), near(nearInv));
        assertEquals(nearInv, f.container(0).inventory);
        assertEquals(farInv, f.container(1).inventory);
    }

    // ------------------------------------------------------------ 取出

    @Test
    void extractFromSlotTakesRequestedAmount() {
        FakeInventory a = new FakeInventory(2).with(1, stack(APPLE, 40));
        FieldInventory f = field(near(a));
        ItemStack taken = f.extractFromSlot(0, 1, 20);
        assertEquals(20, taken.stackSize);
        assertEquals(20, a.total(APPLE));
        ItemStack rest = f.extractFromSlot(0, 1, 64);
        assertEquals(20, rest.stackSize);
        assertNull(a.slots[1]);
    }

    @Test
    void summaryExtractTakesNearestContainerFirst() {
        FakeInventory nearInv = new FakeInventory(1).with(0, stack(APPLE, 10));
        FakeInventory midInv = new FakeInventory(1).with(0, stack(APPLE, 10));
        FakeInventory farInv = new FakeInventory(1).with(0, stack(APPLE, 10));
        // 故意乱序传入
        FieldInventory f = field(far(farInv), near(nearInv), mid(midInv));

        ItemStack taken = f.extractSummary(stack(APPLE, 1), 25);
        assertEquals(25, taken.stackSize);
        assertEquals(0, nearInv.total(APPLE));
        assertEquals(0, midInv.total(APPLE));
        assertEquals(5, farInv.total(APPLE));
    }

    @Test
    void summaryExtractNeverExceedsMaxStack() {
        FakeInventory a = new FakeInventory(3).with(0, stack(BUCKET, 16))
            .with(1, stack(BUCKET, 16))
            .with(2, stack(BUCKET, 16));
        ItemStack taken = field(near(a)).extractSummary(stack(BUCKET, 1), 40);
        assertEquals(16, taken.stackSize);
        assertEquals(32, a.total(BUCKET));
    }

    @Test
    void summaryExtractReturnsWhatExistsWhenNotEnough() {
        FakeInventory a = new FakeInventory(2).with(0, stack(APPLE, 7));
        ItemStack taken = field(near(a)).extractSummary(stack(APPLE, 1), 64);
        assertEquals(7, taken.stackSize);
        assertNull(field(near(a)).extractSummary(stack(APPLE, 1), 64));
    }

    // ------------------------------------------------------------ 汇总存入

    @Test
    void summaryInsertPrefersContainerWithSameItem() {
        FakeInventory nearInv = new FakeInventory(2); // 最近但是没有苹果
        FakeInventory midInv = new FakeInventory(2).with(0, stack(STICK, 1));
        FakeInventory farInv = new FakeInventory(2).with(0, stack(APPLE, 10));
        FieldInventory f = field(near(nearInv), mid(midInv), far(farInv));

        assertNull(f.insertSummary(stack(APPLE, 20)));
        assertEquals(30, farInv.total(APPLE));
        assertEquals(0, nearInv.total(APPLE));
        assertEquals(0, midInv.total(APPLE));
    }

    @Test
    void summaryInsertFallsBackToFirstContainerThatFits() {
        FakeInventory nearInv = new FakeInventory(1).with(0, stack(STICK, 5)); // 已满格（不同物品）
        FakeInventory midInv = new FakeInventory(1);
        FakeInventory farInv = new FakeInventory(1);
        FieldInventory f = field(near(nearInv), mid(midInv), far(farInv));

        assertNull(f.insertSummary(stack(APPLE, 20)));
        assertEquals(20, midInv.total(APPLE));
        assertEquals(0, farInv.total(APPLE));
    }

    @Test
    void summaryInsertSpillsOverToNextContainer() {
        FakeInventory nearInv = new FakeInventory(1).with(0, stack(APPLE, 60));
        FakeInventory midInv = new FakeInventory(1);
        FieldInventory f = field(near(nearInv), mid(midInv));

        assertNull(f.insertSummary(stack(APPLE, 10)));
        assertEquals(64, nearInv.total(APPLE));
        assertEquals(6, midInv.total(APPLE));
    }

    // ------------------------------------------------------------ 分容器存入

    @Test
    void containerInsertOnlyGoesToSelectedContainer() {
        FakeInventory nearInv = new FakeInventory(2).with(0, stack(APPLE, 10));
        FakeInventory farInv = new FakeInventory(2);
        FieldInventory f = field(near(nearInv), far(farInv));

        assertNull(f.insertInto(1, stack(APPLE, 20)));
        assertEquals(10, nearInv.total(APPLE));
        assertEquals(20, farInv.total(APPLE));
    }

    @Test
    void containerInsertDoesNotOverflowIntoOtherContainers() {
        FakeInventory nearInv = new FakeInventory(2);
        FakeInventory farInv = new FakeInventory(1).with(0, stack(STICK, 1));
        FieldInventory f = field(near(nearInv), far(farInv));

        ItemStack rest = f.insertInto(1, stack(APPLE, 20));
        assertEquals(20, rest.stackSize);
        assertEquals(0, nearInv.total(APPLE));
        assertEquals(0, farInv.total(APPLE));
    }

    // ------------------------------------------------------------ 放不下

    @Test
    void unplacedItemsAreReturnedExactly() {
        FakeInventory a = new FakeInventory(1).with(0, stack(APPLE, 50));
        FakeInventory b = new FakeInventory(1).with(0, stack(STICK, 1));
        FieldInventory f = field(near(a), far(b));

        ItemStack rest = f.insertSummary(stack(APPLE, 40));
        assertNotNull(rest);
        assertEquals(26, rest.stackSize);
        assertEquals(64, a.total(APPLE));
        assertEquals(0, b.total(APPLE));
    }

    @Test
    void insertConservesItemCountForRandomisedLoad() {
        FakeInventory a = new FakeInventory(4).with(0, stack(APPLE, 33));
        FakeInventory b = new FakeInventory(3).with(1, stack(APPLE, 64))
            .with(2, stack(STICK, 9));
        FieldInventory f = field(near(a), mid(b));
        int before = a.total(APPLE) + b.total(APPLE);
        int remaining = 0;
        for (int i = 0; i < 20; i++) {
            ItemStack rest = f.insertSummary(stack(APPLE, 17));
            remaining += rest == null ? 0 : rest.stackSize;
        }
        assertEquals(before + 20 * 17, a.total(APPLE) + b.total(APPLE) + remaining);
        assertTrue(remaining > 0, "容量有限，应该有放不下的物品");
    }

    @Test
    void insertedStackIsNotMutatedOrAliased() {
        FakeInventory a = new FakeInventory(2);
        FieldInventory f = field(near(a));
        ItemStack in = stack(APPLE, 10);
        assertNull(f.insertSummary(in));
        in.stackSize = 1;
        assertEquals(10, a.total(APPLE));
    }

    // ------------------------------------------------------------ 合成材料

    @Test
    void craftingMaterialComesFromBackpackFirst() {
        FakeInventory backpackInv = new FakeInventory(36).with(3, stack(APPLE, 5));
        FakeInventory chest = new FakeInventory(2).with(0, stack(APPLE, 10));
        FieldContainer backpack = new FieldContainer(backpackInv, 0, "", 0, 0, 0, SlotRules.ALL, 36);
        FieldInventory f = new FieldInventory(backpack, Arrays.asList(near(chest)));

        ItemStack got = f.consumeMaterial(stack(APPLE, 1), 3);
        assertEquals(3, got.stackSize);
        assertEquals(2, backpackInv.total(APPLE));
        assertEquals(10, chest.total(APPLE));
    }

    @Test
    void craftingMaterialFallsBackToNearbyContainers() {
        FakeInventory backpackInv = new FakeInventory(36).with(3, stack(APPLE, 2));
        FakeInventory nearInv = new FakeInventory(1).with(0, stack(APPLE, 1));
        FakeInventory farInv = new FakeInventory(1).with(0, stack(APPLE, 10));
        FieldContainer backpack = new FieldContainer(backpackInv, 0, "", 0, 0, 0, SlotRules.ALL, 36);
        FieldInventory f = new FieldInventory(backpack, Arrays.asList(far(farInv), near(nearInv)));

        ItemStack got = f.consumeMaterial(stack(APPLE, 1), 5);
        assertEquals(5, got.stackSize);
        assertEquals(0, backpackInv.total(APPLE));
        assertEquals(0, nearInv.total(APPLE));
        assertEquals(8, farInv.total(APPLE));
    }

    @Test
    void craftingMaterialReturnsNullWhenNothingAvailable() {
        FakeInventory backpackInv = new FakeInventory(36);
        FieldContainer backpack = new FieldContainer(backpackInv, 0, "", 0, 0, 0, SlotRules.ALL, 36);
        FieldInventory f = new FieldInventory(backpack, Arrays.asList());
        assertNull(f.consumeMaterial(stack(APPLE, 1), 1));
    }

    @Test
    void backpackOnlyExposesMainInventorySlots() {
        // 40 格的玩家物品栏，只有前 36 格是背包，后 4 格是装备
        FakeInventory backpackInv = new FakeInventory(40).with(38, stack(APPLE, 5));
        FieldContainer backpack = new FieldContainer(backpackInv, 0, "", 0, 0, 0, SlotRules.ALL, 36);
        FieldInventory f = new FieldInventory(backpack, Arrays.asList());
        assertNull(f.consumeMaterial(stack(APPLE, 1), 1));
        assertEquals(5, backpackInv.total(APPLE));
    }

    @Test
    void leftoversGoToBackpackFirst() {
        FakeInventory backpackInv = new FakeInventory(36);
        FakeInventory chest = new FakeInventory(2);
        FieldContainer backpack = new FieldContainer(backpackInv, 0, "", 0, 0, 0, SlotRules.ALL, 36);
        FieldInventory f = new FieldInventory(backpack, Arrays.asList(near(chest)));

        assertNull(f.giveToPlayerOrField(stack(BUCKET, 2)));
        assertEquals(2, backpackInv.total(BUCKET));
        assertEquals(0, chest.total(BUCKET));
    }

    @Test
    void leftoversGoBackToNearbyContainersWhenBackpackIsFull() {
        FakeInventory backpackInv = new FakeInventory(2).with(0, stack(STICK, 1))
            .with(1, stack(STICK, 1));
        FakeInventory chest = new FakeInventory(2);
        FieldContainer backpack = new FieldContainer(backpackInv, 0, "", 0, 0, 0, SlotRules.ALL, 36);
        FieldInventory f = new FieldInventory(backpack, Arrays.asList(near(chest)));

        assertNull(f.giveToPlayerOrField(stack(BUCKET, 2)));
        assertEquals(0, backpackInv.total(BUCKET));
        assertEquals(2, chest.total(BUCKET));
    }

    @Test
    void leftoversThatFitNowhereAreReturned() {
        FakeInventory backpackInv = new FakeInventory(1).with(0, stack(STICK, 1));
        FakeInventory chest = new FakeInventory(1).with(0, stack(STICK, 1));
        FieldContainer backpack = new FieldContainer(backpackInv, 0, "", 0, 0, 0, SlotRules.ALL, 36);
        FieldInventory f = new FieldInventory(backpack, Arrays.asList(near(chest)));

        ItemStack rest = f.giveToPlayerOrField(stack(BUCKET, 2));
        assertEquals(2, rest.stackSize);
    }
}
