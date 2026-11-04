package com.rtgame.fieldcrafting.field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * 场域库存：把一组邻近容器聚合成统一视图。
 * <p>
 * 对外提供汇总视图、分容器视图、取出、存入，以及合成材料的消耗。上层对真实容器的读写都经过它
 * （物品清单的格子由 {@link CellLedger} 结算后落到这里）。本类不依赖游戏世界，只依赖 {@link IInventory}，
 * 因此可以用假容器做 JUnit 测试。
 * <p>
 * 约定：
 * <ul>
 * <li>邻近容器按离站由近到远排序，距离相同保持传入顺序。</li>
 * <li>存入返回放不下的剩余物（全部放下返回 null），绝不丢弃、不多吞。</li>
 * <li>玩家背包是一个特殊的容器，只在合成材料消耗和成品、剩余物的发放中参与。</li>
 * </ul>
 */
public final class FieldInventory {

    /** 汇总视图中的一项：同种物品合并后的总数。 */
    public static final class SummaryEntry {

        /** 数量固定为 1 的物品样本。 */
        public final ItemStack stack;
        public final long count;
        /** 其中真正能取出的数量（如关闭了自动输出的总线里的物品可见但不能取）。 */
        public final long extractable;

        SummaryEntry(ItemStack stack, long count, long extractable) {
            this.stack = stack;
            this.count = count;
            this.extractable = extractable;
        }
    }

    /** 分容器视图中的一项：某容器某槽位里的物品。 */
    public static final class SlotEntry {

        public final int container;
        public final int slot;
        /** 槽位物品的拷贝，数量为槽位中的真实数量。 */
        public final ItemStack stack;
        /** 这个槽位的物品能否取出。 */
        public final boolean extractable;

        SlotEntry(int container, int slot, ItemStack stack, boolean extractable) {
            this.container = container;
            this.slot = slot;
            this.stack = stack;
            this.extractable = extractable;
        }
    }

    private final FieldContainer backpack;
    private final List<FieldContainer> containers;

    /**
     * @param backpack   玩家背包，可为 null（如只需要邻近容器视图时）
     * @param containers 邻近容器，内部会按距离由近到远（稳定）排序
     */
    public FieldInventory(FieldContainer backpack, List<FieldContainer> containers) {
        this.backpack = backpack;
        this.containers = new ArrayList<>(containers);
        this.containers.sort(Comparator.comparingDouble(c -> c.distanceSq));
    }

    public int containerCount() {
        return containers.size();
    }

    public FieldContainer container(int index) {
        return containers.get(index);
    }

    /** 坐标在 (x, y, z) 的邻近容器的下标；不存在返回 -1。 */
    public int indexOf(int x, int y, int z) {
        for (int i = 0; i < containers.size(); i++) {
            FieldContainer c = containers.get(i);
            if (c.x == x && c.y == y && c.z == z) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 视图

    /** 汇总视图：所有邻近容器里的同种物品合并为一项，按首次出现的顺序排列。 */
    public List<SummaryEntry> summary() {
        List<ItemStack> samples = new ArrayList<>();
        List<long[]> totals = new ArrayList<>(); // { 总数, 可取出数 }
        for (FieldContainer c : containers) {
            for (int slot = 0; slot < c.slotCount(); slot++) {
                ItemStack stack = c.inventory.getStackInSlot(slot);
                if (Stacks.isEmpty(stack) || !c.isUsable(slot)) continue;
                int free = c.canExtract(slot, stack) ? stack.stackSize : 0;
                int found = -1;
                for (int i = 0; i < samples.size(); i++) {
                    if (Stacks.same(samples.get(i), stack)) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    samples.add(Stacks.copyWithSize(stack, 1));
                    totals.add(new long[] { stack.stackSize, free });
                } else {
                    totals.get(found)[0] += stack.stackSize;
                    totals.get(found)[1] += free;
                }
            }
        }
        List<SummaryEntry> result = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            result.add(new SummaryEntry(samples.get(i), totals.get(i)[0], totals.get(i)[1]));
        }
        return result;
    }

    /** 分容器视图：某个容器里的全部物品。 */
    public List<SlotEntry> containerItems(int containerIndex) {
        List<SlotEntry> result = new ArrayList<>();
        FieldContainer c = containers.get(containerIndex);
        for (int slot = 0; slot < c.slotCount(); slot++) {
            ItemStack stack = c.inventory.getStackInSlot(slot);
            if (Stacks.isEmpty(stack) || !c.isUsable(slot)) continue;
            result.add(new SlotEntry(containerIndex, slot, stack.copy(), c.canExtract(slot, stack)));
        }
        return result;
    }

    // ---------------------------------------------------------------- 取出

    /**
     * 按（容器 + 槽位）取出最多 amount 个物品，不会超过该物品的最大堆叠数。
     *
     * @return 取出的物品；取不到返回 null
     */
    public ItemStack extractFromSlot(int containerIndex, int slot, int amount) {
        if (containerIndex < 0 || containerIndex >= containers.size()) return null;
        return extractFromSlot(containers.get(containerIndex), slot, amount, null);
    }

    /**
     * 汇总视图取出：按容器离站由近到远，取出最多 amount 个与 template 相同的物品，
     * 不会超过最大堆叠数。
     *
     * @return 取出的物品；取不到返回 null
     */
    public ItemStack extractSummary(ItemStack template, int amount) {
        return extractFrom(containers, template, amount);
    }

    private static ItemStack extractFromSlot(FieldContainer c, int slot, int amount, ItemStack onlyThisKind) {
        if (amount <= 0 || !c.isUsable(slot)) return null;
        ItemStack inSlot = c.inventory.getStackInSlot(slot);
        if (Stacks.isEmpty(inSlot)) return null;
        if (onlyThisKind != null && !Stacks.same(inSlot, onlyThisKind)) return null;
        if (!c.canExtract(slot, inSlot)) return null;
        int want = Math.min(Math.min(amount, inSlot.stackSize), inSlot.getMaxStackSize());
        if (want <= 0) return null;
        ItemStack taken = c.inventory.decrStackSize(slot, want);
        if (Stacks.isEmpty(taken)) return null;
        c.inventory.markDirty();
        return taken;
    }

    private static ItemStack extractFrom(List<FieldContainer> sources, ItemStack template, int amount) {
        if (Stacks.isEmpty(template) || amount <= 0) return null;
        int limit = Math.min(amount, template.getMaxStackSize());
        ItemStack result = null;
        for (FieldContainer c : sources) {
            for (int slot = 0; slot < c.slotCount(); slot++) {
                int missing = limit - (result == null ? 0 : result.stackSize);
                if (missing <= 0) return result;
                ItemStack taken = extractFromSlot(c, slot, missing, template);
                if (taken == null) continue;
                if (result == null) result = taken;
                else result.stackSize += taken.stackSize;
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 存入

    /**
     * 汇总视图存入：优先放进已有同种物品的容器，其次放进第一个放得下的容器。
     *
     * @return 放不下的剩余物；全部放下返回 null
     */
    public ItemStack insertSummary(ItemStack stack) {
        if (Stacks.isEmpty(stack)) return null;
        ItemStack rest = stack.copy();
        for (FieldContainer c : containers) {
            if (!containsKind(c, rest)) continue;
            rest = insert(c, rest);
            if (rest == null) return null;
        }
        for (FieldContainer c : containers) {
            if (containsKind(c, stack)) continue;
            rest = insert(c, rest);
            if (rest == null) return null;
        }
        return rest;
    }

    /**
     * 分容器视图存入：只进选中的容器。
     *
     * @return 放不下的剩余物；全部放下返回 null
     */
    public ItemStack insertInto(int containerIndex, ItemStack stack) {
        if (Stacks.isEmpty(stack)) return null;
        if (containerIndex < 0 || containerIndex >= containers.size()) return stack.copy();
        return insert(containers.get(containerIndex), stack.copy());
    }

    private static boolean containsKind(FieldContainer c, ItemStack stack) {
        for (int slot = 0; slot < c.slotCount(); slot++) {
            ItemStack inSlot = c.inventory.getStackInSlot(slot);
            if (!Stacks.isEmpty(inSlot) && c.isUsable(slot) && Stacks.same(inSlot, stack)) return true;
        }
        return false;
    }

    /** 把 stack（会被修改）尽量放进容器，返回剩余物（放完返回 null）。 */
    private static ItemStack insert(FieldContainer c, ItemStack stack) {
        IInventory inv = c.inventory;
        int perSlotLimit = Math.min(inv.getInventoryStackLimit(), stack.getMaxStackSize());
        boolean changed = false;
        // 先并入已有的同种物品
        for (int slot = 0; slot < c.slotCount() && stack.stackSize > 0; slot++) {
            ItemStack inSlot = inv.getStackInSlot(slot);
            if (Stacks.isEmpty(inSlot) || !Stacks.same(inSlot, stack) || !c.canInsert(slot, stack)) continue;
            int space = perSlotLimit - inSlot.stackSize;
            if (space <= 0) continue;
            int moved = Math.min(space, stack.stackSize);
            inSlot.stackSize += moved;
            stack.stackSize -= moved;
            inv.setInventorySlotContents(slot, inSlot);
            changed = true;
        }
        // 再放进空槽位
        for (int slot = 0; slot < c.slotCount() && stack.stackSize > 0; slot++) {
            ItemStack inSlot = inv.getStackInSlot(slot);
            if (!Stacks.isEmpty(inSlot) || !c.canInsert(slot, stack)) continue;
            int moved = Math.min(perSlotLimit, stack.stackSize);
            if (moved <= 0) continue;
            inv.setInventorySlotContents(slot, Stacks.copyWithSize(stack, moved));
            stack.stackSize -= moved;
            changed = true;
        }
        if (changed) inv.markDirty();
        return stack.stackSize > 0 ? stack : null;
    }

    // ---------------------------------------------------------------- 合成

    /** 背包与邻近容器里与 template 相同、能取出的物品总数。 */
    public long countMaterial(ItemStack template) {
        long total = 0;
        if (backpack != null) total += count(backpack, template);
        for (FieldContainer c : containers) total += count(c, template);
        return total;
    }

    private static long count(FieldContainer c, ItemStack template) {
        long total = 0;
        for (int slot = 0; slot < c.slotCount(); slot++) {
            ItemStack inSlot = c.inventory.getStackInSlot(slot);
            if (!Stacks.isEmpty(inSlot) && Stacks.same(inSlot, template) && c.canExtract(slot, inSlot)) {
                total += inSlot.stackSize;
            }
        }
        return total;
    }

    /**
     * 合成材料消耗：先取玩家背包，不够再取邻近容器（由近到远）。
     *
     * @return 实际取到的物品（数量不超过最大堆叠数）；一个都取不到返回 null
     */
    public ItemStack consumeMaterial(ItemStack template, int amount) {
        if (Stacks.isEmpty(template) || amount <= 0) return null;
        int limit = Math.min(amount, template.getMaxStackSize());
        ItemStack result = null;
        if (backpack != null) {
            result = extractFrom(Collections.singletonList(backpack), template, limit);
        }
        int got = result == null ? 0 : result.stackSize;
        if (got < limit) {
            ItemStack more = extractFrom(containers, template, limit - got);
            if (more != null) {
                if (result == null) result = more;
                else result.stackSize += more.stackSize;
            }
        }
        return result;
    }

    /**
     * 发放成品或剩余物：先进玩家背包，放不下再放回邻近容器（汇总存入规则）。
     *
     * @return 两边都放不下的部分；全部放下返回 null
     */
    public ItemStack giveToPlayerOrField(ItemStack stack) {
        if (Stacks.isEmpty(stack)) return null;
        ItemStack rest = stack.copy();
        if (backpack != null) {
            rest = insert(backpack, rest);
            if (rest == null) return null;
        }
        return insertSummary(rest);
    }

    /** 只从玩家背包取出最多 amount 个（不超过最大堆叠数），取不到返回 null。 */
    public ItemStack takeFromBackpack(ItemStack template, int amount) {
        if (backpack == null) return null;
        return extractFrom(Collections.singletonList(backpack), template, amount);
    }

    /** 只发放进玩家背包（不放进邻近容器），返回放不下的部分。 */
    public ItemStack giveToPlayerOnly(ItemStack stack) {
        if (Stacks.isEmpty(stack)) return null;
        if (backpack == null) return stack.copy();
        return insert(backpack, stack.copy());
    }
}
