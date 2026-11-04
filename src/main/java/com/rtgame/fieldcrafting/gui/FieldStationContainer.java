package com.rtgame.fieldcrafting.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.integration.nei.INEIRecipeTransfer;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.widgets.slot.InventoryCraftingWrapper;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import codechicken.nei.PositionedStack;
import codechicken.nei.ServerSync;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * 场域合成站的容器：3×3 合成、结果槽，以及与物品清单、NEI "+" 的联动。
 */
public class FieldStationContainer extends ModularContainer implements INEIRecipeTransfer<GuiContainer> {

    private static final String[] NEI_IDENTS = { "crafting" };
    /** NEI 合成配方里 3×3 网格第一格的相对位置与格距。 */
    private static final int NEI_ORIGIN_X = 25;
    private static final int NEI_ORIGIN_Y = 6;
    private static final int NEI_SPACING = 18;
    /** 每格发给服务端的候选物品上限，避免数据包过大。真正有货的候选排在前面，不会被截掉。 */
    private static final int MAX_CANDIDATES = 16;
    private static final int MODE_PICKUP = 0;
    private static final int HOTBAR_SIZE = 9;
    /** 一次请求最多合成的次数，与 NEI 合成链每次请求的上限相同。 */
    private static final int MAX_CRAFTS_PER_REQUEST = 64;

    private final TileFieldStation tile;
    private final InventoryCraftingWrapper craftMatrix;
    private FieldCraftingSlot craftingSlot;
    private FieldListSync listSync;
    private boolean lastPowered;

    public FieldStationContainer(TileFieldStation tile) {
        this.tile = tile;
        this.craftMatrix = new InventoryCraftingWrapper(this, 3, 3, tile.getGrid(), 0);
    }

    public void setListSync(FieldListSync listSync) {
        this.listSync = listSync;
    }

    // ---------------------------------------------------------------- 合成

    @Override
    public void registerSlot(String panelName, ModularSlot slot) {
        super.registerSlot(panelName, slot);
        if (slot instanceof FieldCraftingSlot crafting) {
            this.craftingSlot = crafting;
            crafting.setCraftMatrix(this.craftMatrix);
        }
    }

    @Override
    public void onModularContainerOpened() {
        super.onModularContainerOpened();
        refreshResult();
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        // 检测合成网格被界面外的操作改动（如 NEI 填充之外的途径）
        craftMatrix.detectChanges();
        // 断电 / 来电时结果槽按当前状态重新计算
        if (!getPlayer().worldObj.isRemote) {
            boolean powered = tile.isPowered();
            if (powered != lastPowered) {
                lastPowered = powered;
                refreshResult();
            }
        }
    }

    private void refreshResult() {
        if (craftingSlot == null || getPlayer() == null || getPlayer().worldObj.isRemote) return;
        // 网格包装的第 10 格就是结果槽，部分配方（如染色盔甲）遍历全部格子，旧成品不能参与匹配
        tile.getGrid()
            .setStackInSlot(TileFieldStation.GRID_SIZE, null);
        craftingSlot.updateResult(
            CraftingManager.getInstance()
                .findMatchingRecipe(craftMatrix, getPlayer().worldObj));
    }

    @Override
    public void onCraftMatrixChanged(@NotNull IInventory inventory) {
        refreshResult();
    }

    // ---------------------------------------------------------------- 点击物品清单里的格子

    /**
     * 清单格子是真实的原版槽位，点击完全走原版逻辑。服务端在点击前后对账：格子被取走的物品从真实容器扣除，
     * 放进格子的物品存进真实容器（见 {@link FieldListSync#beginClick}）。
     */
    @Override
    public ItemStack slotClick(int slotId, int mouseButton, int mode, EntityPlayer player) {
        boolean validSlot = slotId >= 0 && slotId < inventorySlots.size();
        Slot clicked = validSlot ? (Slot) inventorySlots.get(slotId) : null;
        if (clicked != null && clicked == craftingSlot && mode == MODE_PICKUP) {
            mouseButton = 0; // 和原版结果槽一样，右键也取走整组成品
        }
        boolean isDeposit = clicked instanceof FieldListSlot && mode == MODE_PICKUP
            && !Stacks.isEmpty(player.inventory.getItemStack());

        if (player.worldObj.isRemote) {
            return isDeposit ? null : super.slotClick(slotId, mouseButton, mode, player); // 存入不预测，等服务端同步
        }
        if (listSync == null) return super.slotClick(slotId, mouseButton, mode, player);
        if (isDeposit) {
            listSync.depositCursor(player, mouseButton);
            resyncToPlayer(player);
            return null;
        }

        FieldListSync.Click click = listSync.beginClick();
        ItemStack result;
        try {
            result = super.slotClick(slotId, mouseButton, mode, player);
        } catch (RuntimeException | Error e) {
            listSync.abortClick();
            throw e;
        }
        listSync.finishClick(player, click);
        // 原版在点击之后的变化检测处于"静默"状态，且客户端的预测可能与服务端不同，所以每次点击后都完整同步
        resyncToPlayer(player);
        return result;
    }

    /** 双击收集同种物品时不能把结果槽算进去，否则会触发合成。 */
    @Override
    public boolean func_94530_a(ItemStack stack, Slot slot) {
        return slot != craftingSlot && super.func_94530_a(stack, slot);
    }

    // ---------------------------------------------------------------- Shift+点击

    @Override
    public ItemStack transferStackInSlot(@NotNull EntityPlayer player, int index) {
        ModularSlot slot = getModularSlot(index);
        if (slot instanceof FieldListSlot) return quickMoveToPlayer(slot, target -> true);
        boolean powered = player.worldObj.isRemote ? listSync != null && listSync.isPowered() : tile.isPowered();
        // 断电时降级为普通工作台，Shift+点击走默认逻辑
        if (powered && ModularSlot.isPlayerSlot(slot) && listSync != null) {
            if (player.worldObj.isRemote) return null; // 客户端不预测，等服务端同步
            ItemStack stack = slot.getStack();
            if (Stacks.isEmpty(stack)) return null;
            ItemStack rest = listSync.store(player, stack.copy());
            int moved = stack.stackSize - (rest == null ? 0 : rest.stackSize);
            if (moved <= 0) return null;
            ItemStack copy = stack.copy();
            slot.putStack(rest);
            return copy;
        }
        // 和普通工作台一样，背包里的物品在主物品栏与快捷栏之间移动
        if (ModularSlot.isPlayerSlot(slot)) {
            boolean fromHotbar = slot.getSlotIndex() < HOTBAR_SIZE;
            return quickMoveToPlayer(slot, target -> target.getSlotIndex() < HOTBAR_SIZE != fromHotbar);
        }
        return super.transferStackInSlot(player, index);
    }

    /**
     * 把 slot 里的物品挪进玩家背包里满足条件的格子，先并入同种物品，再放进空格。
     * 两端用同一套确定的规则，客户端的预测与服务端一致。
     */
    private ItemStack quickMoveToPlayer(ModularSlot slot, Predicate<ModularSlot> allowed) {
        ItemStack stack = slot.getStack();
        if (Stacks.isEmpty(stack) || !slot.canTakeStack(getPlayer())) return null;
        ItemStack copy = stack.copy();
        ItemStack rest = stack.copy();
        List<ModularSlot> targets = new ArrayList<>();
        for (Object o : inventorySlots) {
            if (o instanceof ModularSlot candidate && candidate != slot
                && ModularSlot.isPlayerSlot(candidate)
                && allowed.test(candidate)) {
                targets.add(candidate);
            }
        }
        for (int pass = 0; pass < 2 && rest.stackSize > 0; pass++) {
            for (int i = targets.size() - 1; i >= 0 && rest.stackSize > 0; i--) {
                ModularSlot target = targets.get(i);
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

    // ---------------------------------------------------------------- NEI "+"

    @Override
    public String[] getIdents() {
        return NEI_IDENTS;
    }

    /**
     * 把 NEI 配方换算成每格的候选物品；配方里没有能放进 3×3 网格的材料时返回 null。
     * 优先列出玩家实际拥有的物品（带真实的损伤值和 NBT），否则磨损的工具等会因与配方样本不全等而填不上。
     */
    private ItemStack[][] buildCandidates(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        List<ItemStack> available = availableMaterials(gui);
        ItemStack[][] candidates = new ItemStack[TileFieldStation.GRID_SIZE][];
        boolean any = false;
        List<PositionedStack> ingredients = recipe.getIngredientStacks(recipeIndex);
        for (PositionedStack ingredient : ingredients) {
            int col = Math.round((ingredient.relx - NEI_ORIGIN_X) / (float) NEI_SPACING);
            int row = Math.round((ingredient.rely - NEI_ORIGIN_Y) / (float) NEI_SPACING);
            if (col < 0 || col > 2 || row < 0 || row > 2) continue;
            List<ItemStack> options = new ArrayList<>();
            for (ItemStack have : available) {
                if (ingredient.contains(have)) addCandidate(options, have);
            }
            if (ingredient.items != null) {
                for (ItemStack option : ingredient.items) {
                    if (option != null) addCandidate(options, option);
                }
            }
            if (options.isEmpty()) continue;
            candidates[row * 3 + col] = options.toArray(new ItemStack[0]);
            any = true;
        }
        return any ? candidates : null;
    }

    private static void addCandidate(List<ItemStack> options, ItemStack stack) {
        if (options.size() < MAX_CANDIDATES && indexOf(options, stack) < 0) options.add(Stacks.copyWithSize(stack, 1));
    }

    /** 配方是否至少有一种材料落在 3×3 网格内。 */
    private static boolean fitsGrid(IRecipeHandler recipe, int recipeIndex) {
        for (PositionedStack ingredient : recipe.getIngredientStacks(recipeIndex)) {
            int col = Math.round((ingredient.relx - NEI_ORIGIN_X) / (float) NEI_SPACING);
            int row = Math.round((ingredient.rely - NEI_ORIGIN_Y) / (float) NEI_SPACING);
            if (col >= 0 && col <= 2 && row >= 0 && row <= 2) return true;
        }
        return false;
    }

    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (listSync == null) return 0;
        ItemStack[][] candidates = buildCandidates(gui, recipe, recipeIndex);
        if (candidates == null) return 0;
        listSync.sendFill(candidates, multiplier);
        return 1;
    }

    // ---------------------------------------------------------------- NEI 合成链（自动合成）

    @Override
    public boolean canCraft(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        if (listSync == null || craftingSlot == null || !fitsGrid(recipe, recipeIndex)) return false;
        for (GuiOverlayButton.ItemOverlayState state : presenceOverlay(gui, recipe, recipeIndex)) {
            if (!state.isPresent()) return false;
        }
        return true;
    }

    /**
     * NEI 合成链自动合成：请求服务端填充并精确合成 multiplier 次，成品进玩家背包，然后等服务端处理完成。
     * 此方法运行在 NEI 的后台任务线程，阻塞等待是允许的。
     *
     * @return 服务端是否真的合成了。一次也没合成时必须返回 false，否则 NEI 会认为有进展而一直重试
     */
    @Override
    public boolean craft(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (listSync == null) return false;
        ItemStack[][] candidates = buildCandidates(gui, recipe, recipeIndex);
        if (candidates == null) return false;
        listSync.sendCraft(candidates, Math.max(1, multiplier));
        ServerSync.request();
        long deadline = System.currentTimeMillis() + 5000;
        try {
            while ((ServerSync.pending() || listSync.getLastCraftResult() < 0) && gui.mc.currentScreen == gui
                && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return false;
        }
        return gui.mc.currentScreen == gui && listSync.getLastCraftResult() > 0;
    }

    /**
     * 服务端：填充网格后精确合成 crafts 次，成品放进玩家背包，最后把网格清空，并把容器完整同步给客户端。
     * 供 NEI 合成链调用。一轮填充不一定够：工具限制每格只能填 1 份，所以合成中断后把空出来的格子再填一轮，
     * 留在网格里的工具继续用，直到够数或填不出配方。成品放不下（连邻近容器也满）时掉在脚边并停止，
     * 否则玩家看不到产物，NEI 会一直合成到材料耗尽。
     *
     * @return 实际合成的次数
     */
    public int craftExact(EntityPlayer player, ItemStack[][] candidates, int requested) {
        if (player.worldObj.isRemote || craftingSlot == null) return 0;
        int crafts = Math.max(1, Math.min(requested, MAX_CRAFTS_PER_REQUEST));
        FieldInventory field = tile.createFieldInventory(player);
        clearGrid(field);
        int done = 0;
        boolean full = false;
        while (done < crafts && !full) {
            fillEmptySlots(field, candidates, crafts - done);
            if (Stacks.isEmpty(craftingSlot.getStack())) break;
            while (done < crafts && !Stacks.isEmpty(craftingSlot.getStack())) {
                ItemStack out = craftingSlot.getStack()
                    .copy();
                craftingSlot.onPickupFromSlot(player, out);
                done++;
                ItemStack rest = field.giveToPlayerOrField(out);
                if (rest != null) {
                    player.dropPlayerItemWithRandomChoice(rest, false);
                    full = true;
                    break;
                }
            }
        }
        clearGrid(field);
        craftMatrix.notifyContainer();
        if (listSync != null) listSync.refreshNow();
        resyncToPlayer(player);
        return done;
    }

    /** 把整个容器（含光标物品）重新发给玩家。用于原版变化检测会被"静默"的场合。 */
    public void resyncToPlayer(EntityPlayer player) {
        if (player instanceof EntityPlayerMP mp) {
            mp.sendContainerAndContentsToPlayer(this, this.getInventory());
        }
    }

    /**
     * 服务端：按候选材料填充合成网格。先把网格里原有物品挪走，
     * 再按"背包优先、邻近容器其次"取材料；断电时邻近容器不参与，只用背包。
     *
     * @param multiplier 每格的份数；NEI 的 Shift+"+" 传 0，表示按库存尽量多填
     */
    public void fillGrid(EntityPlayer player, ItemStack[][] candidates, int multiplier) {
        if (player.worldObj.isRemote) return;
        FieldInventory field = tile.createFieldInventory(player);
        clearGrid(field);
        fillEmptySlots(field, candidates, multiplier);
    }

    /** 把网格里的物品挪走（背包优先，其次邻近容器）；放不下的留在格子里。 */
    private void clearGrid(FieldInventory field) {
        for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
            ItemStack inSlot = tile.getGrid()
                .getStackInSlot(i);
            if (Stacks.isEmpty(inSlot)) continue;
            tile.getGrid()
                .setStackInSlot(i, field.giveToPlayerOrField(inSlot.copy()));
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
            if (candidates[i] == null || !Stacks.isEmpty(
                tile.getGrid()
                    .getStackInSlot(i))) {
                continue;
            }
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
            if (got != null) tile.getGrid()
                .setStackInSlot(i, got);
        }
        craftMatrix.notifyContainer();
    }

    private static int indexOf(List<ItemStack> samples, ItemStack stack) {
        for (int i = 0; i < samples.size(); i++) {
            if (Stacks.same(samples.get(i), stack)) return i;
        }
        return -1;
    }

    @Override
    public List<GuiOverlayButton.ItemOverlayState> presenceOverlay(GuiContainer gui, IRecipeHandler recipe,
        int recipeIndex) {
        List<ItemStack> pool = availableMaterials(gui);
        List<GuiOverlayButton.ItemOverlayState> states = new ArrayList<>();
        for (PositionedStack ingredient : recipe.getIngredientStacks(recipeIndex)) {
            ItemStack found = null;
            for (ItemStack have : pool) {
                if (have.stackSize > 0 && ingredient.contains(have)) {
                    found = have;
                    break;
                }
            }
            states.add(new GuiOverlayButton.ItemOverlayState(ingredient, found != null));
            if (found != null) found.stackSize -= 1;
        }
        return states;
    }

    /** 客户端已知的可用材料：背包 + 合成网格 + 邻近容器里能取出的物品，按背包优先排列。 */
    private List<ItemStack> availableMaterials(GuiContainer gui) {
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack stack : gui.mc.thePlayer.inventory.mainInventory) {
            if (!Stacks.isEmpty(stack)) pool.add(stack.copy());
        }
        for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
            ItemStack stack = craftMatrix.getStackInSlot(i);
            if (!Stacks.isEmpty(stack)) pool.add(stack.copy());
        }
        if (listSync != null && listSync.isPowered()) {
            for (FieldListSync.Entry entry : listSync.getEntries()) {
                if (entry.count <= 0) continue;
                pool.add(Stacks.copyWithSize(entry.stack, (int) Math.min(entry.count, Integer.MAX_VALUE)));
            }
        }
        return pool;
    }
}
