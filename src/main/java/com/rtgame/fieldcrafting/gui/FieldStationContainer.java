package com.rtgame.fieldcrafting.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.GridTweaks;
import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

/**
 * 场域合成站的容器，继承原版工作台：槽位 0 是结果，1..9 是网格，10..45 是玩家背包，下标与原版完全一致，
 * 其他 mod 按原版工作台处理就能用。物品清单的格子排在后面。
 */
public class FieldStationContainer extends ContainerWorkbench {

    /** 网格快捷操作。 */
    public static final int GRID_ROTATE = 0;
    public static final int GRID_BALANCE = 1;
    public static final int GRID_SPREAD = 2;
    public static final int GRID_TO_PLAYER = 3;
    public static final int GRID_TO_FIELD = 4;

    public static final int RESULT_SLOT = 0;
    public static final int FIRST_GRID_SLOT = 1;
    public static final int FIRST_PLAYER_SLOT = 10;
    public static final int FIRST_CELL_SLOT = 46;

    private static final int MODE_PICKUP = 0;
    private static final double MAX_DISTANCE_SQ = 64.0;
    /** 一次请求最多合成的次数，与 NEI 合成链每次请求的上限相同。 */
    private static final int MAX_CRAFTS_PER_REQUEST = 64;

    private final TileFieldStation tile;
    private final EntityPlayer player;
    private final FieldCraftMatrix matrix;
    private final FieldCraftingSlot craftingSlot;
    private final FieldListSync listSync;
    private final ItemStack[] lastGrid = new ItemStack[TileFieldStation.GRID_SIZE];

    public FieldStationContainer(EntityPlayer player, TileFieldStation tile) {
        super(player.inventory, player.worldObj, tile.xCoord, tile.yCoord, tile.zCoord);
        this.tile = tile;
        this.player = player;
        this.matrix = new FieldCraftMatrix(this, tile);
        this.craftMatrix = matrix;

        // 原版的结果槽和网格槽绑在它自己的网格上，换成绑定站内网格的，坐标由界面设置
        this.craftingSlot = new FieldCraftingSlot(player, tile, matrix, craftResult, 0, 0);
        replaceSlot(RESULT_SLOT, craftingSlot);
        for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
            replaceSlot(FIRST_GRID_SLOT + i, new Slot(matrix, i, 0, 0));
        }

        this.listSync = new FieldListSync(tile, player, () -> windowId);
        for (int i = 0; i < FieldListView.SIZE; i++) {
            addSlotToContainer(new FieldListSlot(listSync.cells(), i));
        }
        onCraftMatrixChanged(matrix);
    }

    @SuppressWarnings("unchecked")
    private void replaceSlot(int index, Slot slot) {
        slot.slotNumber = index;
        inventorySlots.set(index, slot);
    }

    public FieldListSync sync() {
        return listSync;
    }

    /** 站名的语言键。 */
    public String titleKey() {
        return "tile.fieldcrafting.station." + tile.getTier().id + ".name";
    }

    @Override
    public boolean canInteractWith(EntityPlayer who) {
        return who.worldObj.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile
            && who.getDistanceSq(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5) <= MAX_DISTANCE_SQ;
    }

    // ---------------------------------------------------------------- 同步

    /** 清单格子不走原版同步：客户端自己按快照摆，否则每次滚动都要发一遍所有格子。 */
    @Override
    @SuppressWarnings("unchecked")
    public void detectAndSendChanges() {
        for (int i = 0; i < FIRST_CELL_SLOT; i++) {
            ItemStack now = ((Slot) inventorySlots.get(i)).getStack();
            ItemStack sent = (ItemStack) inventoryItemStacks.get(i);
            if (ItemStack.areItemStacksEqual(sent, now)) continue;
            sent = now == null ? null : now.copy();
            inventoryItemStacks.set(i, sent);
            for (Object crafter : crafters) {
                ((ICrafting) crafter).sendSlotContents(this, i, sent);
            }
        }
        if (player.worldObj.isRemote) return;
        sync().detectAndSendChanges();
        // 同一个站可能被别的玩家改了网格，这边的结果槽也要跟着变
        boolean changed = false;
        for (int i = 0; i < lastGrid.length; i++) {
            ItemStack now = tile.getGridStack(i);
            if (!ItemStack.areItemStacksEqual(lastGrid[i], now)) {
                lastGrid[i] = now == null ? null : now.copy();
                changed = true;
            }
        }
        if (changed) onCraftMatrixChanged(matrix);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ItemStack> getInventory() {
        List<ItemStack> all = new ArrayList<>(super.getInventory());
        for (int i = FIRST_CELL_SLOT; i < all.size(); i++) all.set(i, null);
        return all;
    }

    @Override
    public void putStacksInSlots(ItemStack[] stacks) {
        for (int i = 0; i < stacks.length && i < FIRST_CELL_SLOT; i++) {
            getSlot(i).putStack(stacks[i]);
        }
    }

    // ---------------------------------------------------------------- 网格快捷操作

    /** 服务端：执行网格快捷操作。清空时放不下的物品留在格子里；断电时邻近容器不接收物品。 */
    public void applyGridAction(EntityPlayer who, int action) {
        if (who.worldObj.isRemote) return;
        ItemStack[] grid = new ItemStack[TileFieldStation.GRID_SIZE];
        for (int i = 0; i < grid.length; i++) grid[i] = tile.getGridStack(i);
        FieldInventory field = tile.createFieldInventory(who);
        ItemStack[] out = switch (action) {
            case GRID_ROTATE -> GridTweaks.rotate(grid);
            case GRID_BALANCE -> GridTweaks.balance(grid);
            case GRID_SPREAD -> GridTweaks.spread(grid);
            case GRID_TO_PLAYER -> GridTweaks.clear(grid, field::giveToPlayerOnly);
            case GRID_TO_FIELD -> GridTweaks.clear(grid, stack -> sync().store(who, stack));
            default -> grid;
        };
        for (int i = 0; i < out.length; i++) matrix.setQuietly(i, out[i]);
        matrix.notifyChanged();
        sync().refreshNow();
        resyncToPlayer(who);
    }

    // ---------------------------------------------------------------- 点击

    /**
     * 清单格子是真实的原版槽位，点击完全走原版逻辑。服务端在点击前后对账：格子被取走的物品从真实容器扣除，
     * 放进格子的物品存进真实容器（见 {@link FieldListSync#beginClick}）。
     */
    @Override
    public ItemStack slotClick(int slotId, int mouseButton, int mode, EntityPlayer who) {
        if (slotId >= inventorySlots.size()) return null;
        Slot clicked = slotId >= 0 ? (Slot) inventorySlots.get(slotId) : null;
        if (clicked == craftingSlot && mode == MODE_PICKUP) {
            mouseButton = 0; // 右键也取走整组成品，否则没取走的部分会被下一次配方计算覆盖而丢失
        }
        boolean isDeposit = clicked instanceof FieldListSlot && mode == MODE_PICKUP
            && !Stacks.isEmpty(who.inventory.getItemStack());

        if (who.worldObj.isRemote) {
            return isDeposit ? null : super.slotClick(slotId, mouseButton, mode, who); // 存入不预测，等服务端同步
        }
        FieldListSync list = sync();
        if (isDeposit) {
            list.depositCursor(who, mouseButton);
            resyncToPlayer(who);
            return null;
        }

        FieldListSync.Click click = list.beginClick();
        ItemStack result;
        try {
            result = super.slotClick(slotId, mouseButton, mode, who);
        } catch (RuntimeException | Error e) {
            list.abortClick();
            throw e;
        }
        list.finishClick(who, click);
        // 原版在点击之后的变化检测处于"静默"状态，且客户端的预测可能与服务端不同，所以每次点击后都完整同步
        resyncToPlayer(who);
        return result;
    }

    // ---------------------------------------------------------------- Shift+点击

    @Override
    public ItemStack transferStackInSlot(EntityPlayer who, int index) {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot instanceof FieldListSlot) return quickMoveToPlayer(slot);
        boolean powered = who.worldObj.isRemote ? listSync.isPowered() : tile.isPowered();
        if (powered && index >= FIRST_PLAYER_SLOT && index < FIRST_CELL_SLOT) {
            if (who.worldObj.isRemote) return null; // 客户端不预测，等服务端同步
            ItemStack stack = slot.getStack();
            if (Stacks.isEmpty(stack)) return null;
            ItemStack rest = sync().store(who, stack.copy());
            int moved = stack.stackSize - (rest == null ? 0 : rest.stackSize);
            if (moved <= 0) return null;
            ItemStack copy = stack.copy();
            slot.putStack(rest);
            return copy;
        }
        // 结果槽、网格，以及断电时的背包，都和原版工作台一样
        return super.transferStackInSlot(who, index);
    }

    /**
     * 把清单格子里的物品挪进玩家背包，先并入同种物品，再放进空格，顺序和原版工作台一样从后往前。
     * 两端用同一套确定的规则，客户端的预测与服务端一致。
     */
    private ItemStack quickMoveToPlayer(Slot slot) {
        ItemStack stack = slot.getStack();
        if (Stacks.isEmpty(stack) || !slot.canTakeStack(player)) return null;
        ItemStack copy = stack.copy();
        ItemStack rest = stack.copy();
        for (int pass = 0; pass < 2 && rest.stackSize > 0; pass++) {
            for (int i = FIRST_CELL_SLOT - 1; i >= FIRST_PLAYER_SLOT && rest.stackSize > 0; i--) {
                Slot target = (Slot) inventorySlots.get(i);
                ItemStack inSlot = target.getStack();
                int limit = Math.min(rest.getMaxStackSize(), target.getSlotStackLimit());
                if (pass == 0) {
                    if (Stacks.isEmpty(inSlot) || !Stacks.same(inSlot, rest)) continue;
                    int moved = Math.min(rest.stackSize, limit - inSlot.stackSize);
                    if (moved <= 0) continue;
                    target.putStack(Stacks.copyWithSize(inSlot, inSlot.stackSize + moved));
                    rest.stackSize -= moved;
                } else {
                    if (!Stacks.isEmpty(inSlot) || !target.isItemValid(rest)) continue;
                    int moved = Math.min(rest.stackSize, limit);
                    if (moved <= 0) continue;
                    target.putStack(Stacks.copyWithSize(rest, moved));
                    rest.stackSize -= moved;
                }
            }
        }
        if (rest.stackSize == copy.stackSize) return null;
        slot.putStack(rest.stackSize > 0 ? rest : null);
        return copy;
    }

    // ---------------------------------------------------------------- NEI 合成链

    /**
     * 服务端：填充网格后精确合成 crafts 次，成品放进玩家背包，最后把网格清空，并把容器完整同步给客户端。
     * 供 NEI 合成链调用。一轮填充不一定够：工具限制每格只能填 1 份，所以合成中断后把空出来的格子再填一轮，
     * 留在网格里的工具继续用，直到够数或填不出配方。成品放不下（连邻近容器也满）时掉在脚边并停止，
     * 否则玩家看不到产物，NEI 会一直合成到材料耗尽。
     *
     * @return 实际合成的次数
     */
    public int craftExact(EntityPlayer who, ItemStack[][] candidates, int requested) {
        if (who.worldObj.isRemote) return 0;
        int crafts = Math.max(1, Math.min(requested, MAX_CRAFTS_PER_REQUEST));
        FieldInventory field = tile.createFieldInventory(who);
        clearGrid(field);
        int done = 0;
        boolean full = false;
        while (done < crafts && !full) {
            fillEmptySlots(field, candidates, crafts - done);
            if (Stacks.isEmpty(craftingSlot.getStack())) break;
            while (done < crafts && !Stacks.isEmpty(craftingSlot.getStack())) {
                ItemStack out = craftingSlot.getStack()
                    .copy();
                craftingSlot.onPickupFromSlot(who, out);
                done++;
                ItemStack rest = field.giveToPlayerOrField(out);
                if (rest != null) {
                    who.dropPlayerItemWithRandomChoice(rest, false);
                    full = true;
                    break;
                }
            }
        }
        clearGrid(field);
        matrix.notifyChanged();
        sync().refreshNow();
        resyncToPlayer(who);
        return done;
    }

    /** 把整个容器（含光标物品）重新发给玩家。用于原版变化检测会被"静默"的场合。 */
    public void resyncToPlayer(EntityPlayer who) {
        if (who instanceof EntityPlayerMP mp) {
            mp.sendContainerAndContentsToPlayer(this, getInventory());
        }
    }

    /**
     * 服务端：按候选材料填充合成网格。先把网格里原有物品挪走，
     * 再按"背包优先、邻近容器其次"取材料；断电时邻近容器不参与，只用背包。
     *
     * @param multiplier 每格的份数；NEI 的 Shift+"+" 传 0，表示按库存尽量多填
     */
    public void fillGrid(EntityPlayer who, ItemStack[][] candidates, int multiplier) {
        if (who.worldObj.isRemote) return;
        FieldInventory field = tile.createFieldInventory(who);
        clearGrid(field);
        fillEmptySlots(field, candidates, multiplier);
    }

    /** 把网格里的物品挪走（背包优先，其次邻近容器）；放不下的留在格子里。 */
    private void clearGrid(FieldInventory field) {
        for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
            ItemStack inSlot = tile.getGridStack(i);
            if (Stacks.isEmpty(inSlot)) continue;
            matrix.setQuietly(i, field.giveToPlayerOrField(inSlot.copy()));
        }
    }

    /** 按候选材料填充空着的格子，已经有物品的格子不动。 */
    private void fillEmptySlots(FieldInventory field, ItemStack[][] candidates, int multiplier) {
        int size = TileFieldStation.GRID_SIZE;

        // 1. 为每格挑选材料：按候选顺序取第一个还有剩余的
        List<ItemStack> samples = new ArrayList<>();
        List<Long> remaining = new ArrayList<>();
        ItemStack[] chosen = new ItemStack[size];
        int[] chosenIndex = new int[size];
        for (int i = 0; i < size; i++) {
            if (candidates[i] == null || !Stacks.isEmpty(tile.getGridStack(i))) continue;
            for (ItemStack candidate : candidates[i]) {
                if (Stacks.isEmpty(candidate)) continue;
                int idx = indexOf(samples, candidate);
                if (idx < 0) {
                    samples.add(Stacks.copyWithSize(candidate, 1));
                    remaining.add(field.countMaterial(candidate));
                    idx = samples.size() - 1;
                }
                if (remaining.get(idx) >= 1) {
                    remaining.set(idx, remaining.get(idx) - 1);
                    chosen[i] = samples.get(idx);
                    chosenIndex[i] = idx;
                    break;
                }
            }
        }

        // 2. 每格数量：不超过 multiplier 和最大堆叠，同种材料出现在多格时平分库存
        int[] uses = new int[samples.size()];
        for (int i = 0; i < size; i++) if (chosen[i] != null) uses[chosenIndex[i]]++;
        // NEI 的 Shift+"+" 传 0，表示按库存尽量多填
        long amount = multiplier > 0 ? multiplier : Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            if (chosen[i] == null) continue;
            int idx = chosenIndex[i];
            long total = field.countMaterial(chosen[i]);
            amount = Math.min(amount, Math.max(1, total / uses[idx]));
            amount = Math.min(amount, chosen[i].getMaxStackSize());
        }
        for (int i = 0; i < size; i++) {
            if (chosen[i] == null) continue;
            ItemStack got = field.consumeMaterial(chosen[i], (int) amount);
            if (got != null) matrix.setQuietly(i, got);
        }
        matrix.notifyChanged();
    }

    private static int indexOf(List<ItemStack> samples, ItemStack stack) {
        for (int i = 0; i < samples.size(); i++) {
            if (Stacks.same(samples.get(i), stack)) return i;
        }
        return -1;
    }

    /**
     * 客户端已知的全部可用材料：背包 + 合成网格 + 邻近容器里能取出的物品（断电时没有），按背包优先排列。
     * 不受清单当前显示哪一屏影响。NEI 的后台线程也会调用。
     */
    public List<ItemStack> materials() {
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack stack : player.inventory.mainInventory) {
            if (!Stacks.isEmpty(stack)) pool.add(stack.copy());
        }
        for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
            ItemStack stack = tile.getGridStack(i);
            if (!Stacks.isEmpty(stack)) pool.add(stack.copy());
        }
        if (listSync.isPowered()) {
            for (FieldListLayout.Entry entry : listSync.getSummaryEntries()) {
                if (entry.free > 0) {
                    pool.add(Stacks.copyWithSize(entry.stack, (int) Math.min(entry.free, Integer.MAX_VALUE)));
                }
            }
        }
        return pool;
    }
}
