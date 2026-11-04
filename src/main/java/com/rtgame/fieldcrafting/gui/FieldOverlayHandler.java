package com.rtgame.fieldcrafting.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import codechicken.nei.PositionedStack;
import codechicken.nei.ServerSync;
import codechicken.nei.api.API;
import codechicken.nei.api.GuiInfo;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.api.IStackPositioner;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * NEI 的 "+" 和合成链。NEI 自带的处理器只会搬动槽位里的物品，够不着邻近容器，所以让服务端按候选物品直接填充或合成。
 */
public class FieldOverlayHandler implements IOverlayHandler {

    private static final String IDENT = "crafting";
    /** NEI 合成配方里 3×3 网格第一格的相对位置与格距。 */
    private static final int NEI_ORIGIN_X = 25;
    private static final int NEI_ORIGIN_Y = 6;
    private static final int NEI_SPACING = 18;
    /** 每格发给服务端的候选物品上限，避免数据包过大。真正有货的候选排在前面，不会被截掉。 */
    private static final int MAX_CANDIDATES = 16;

    public static void register() {
        API.registerGuiOverlayHandler(FieldStationScreen.class, new FieldOverlayHandler(), IDENT);
        API.registerGuiOverlay(FieldStationScreen.class, IDENT, new Positioner());
        // 滚轮用来滚动清单，不能让 NEI 拿去搬动光标下的物品
        GuiInfo.customSlotGuis.add(FieldStationScreen.class);
    }

    /** 网格位置随界面高度变化，NEI 的半透明配方预览按当前位置摆。 */
    private static final class Positioner implements IStackPositioner {

        @Override
        public ArrayList<PositionedStack> positionStacks(ArrayList<PositionedStack> stacks) {
            if (Minecraft.getMinecraft().currentScreen instanceof FieldStationScreen screen) {
                for (PositionedStack stack : stacks) {
                    stack.relx += screen.gridX() - NEI_ORIGIN_X;
                    stack.rely += screen.gridY() - NEI_ORIGIN_Y;
                }
            }
            return stacks;
        }
    }

    private static FieldListSync sync(GuiContainer gui) {
        return gui.inventorySlots instanceof FieldStationContainer container ? container.sync() : null;
    }

    private static int gridIndex(PositionedStack ingredient) {
        int col = Math.round((ingredient.relx - NEI_ORIGIN_X) / (float) NEI_SPACING);
        int row = Math.round((ingredient.rely - NEI_ORIGIN_Y) / (float) NEI_SPACING);
        return col < 0 || col > 2 || row < 0 || row > 2 ? -1 : row * 3 + col;
    }

    /**
     * 把 NEI 配方换算成每格的候选物品；配方里没有能放进 3×3 网格的材料时返回 null。
     * 优先列出玩家实际拥有的物品（带真实的损伤值和 NBT），否则磨损的工具等会因与配方样本不全等而填不上。
     */
    private static ItemStack[][] buildCandidates(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        List<ItemStack> available = ((FieldStationContainer) gui.inventorySlots).materials();
        ItemStack[][] candidates = new ItemStack[TileFieldStation.GRID_SIZE][];
        boolean any = false;
        for (PositionedStack ingredient : recipe.getIngredientStacks(recipeIndex)) {
            int index = gridIndex(ingredient);
            if (index < 0) continue;
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
            candidates[index] = options.toArray(new ItemStack[0]);
            any = true;
        }
        return any ? candidates : null;
    }

    private static void addCandidate(List<ItemStack> options, ItemStack stack) {
        if (options.size() >= MAX_CANDIDATES) return;
        for (ItemStack option : options) {
            if (Stacks.same(option, stack)) return;
        }
        options.add(Stacks.copyWithSize(stack, 1));
    }

    @Override
    public void overlayRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        transferRecipe(gui, recipe, recipeIndex, maxTransfer ? 0 : 1);
    }

    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        FieldListSync sync = sync(gui);
        if (sync == null) return 0;
        ItemStack[][] candidates = buildCandidates(gui, recipe, recipeIndex);
        if (candidates == null) return 0;
        sync.sendFill(candidates, multiplier == Integer.MAX_VALUE ? 0 : multiplier);
        return 1;
    }

    @Override
    public boolean canCraft(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        if (sync(gui) == null) return false;
        boolean fits = false;
        for (PositionedStack ingredient : recipe.getIngredientStacks(recipeIndex)) {
            fits |= gridIndex(ingredient) >= 0;
        }
        if (!fits) return false;
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
        FieldListSync sync = sync(gui);
        if (sync == null) return false;
        ItemStack[][] candidates = buildCandidates(gui, recipe, recipeIndex);
        if (candidates == null) return false;
        sync.sendCraft(candidates, Math.max(1, multiplier));
        ServerSync.request();
        long deadline = System.currentTimeMillis() + 5000;
        try {
            while ((ServerSync.pending() || sync.getLastCraftResult() < 0) && gui.mc.currentScreen == gui
                && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return false;
        }
        return gui.mc.currentScreen == gui && sync.getLastCraftResult() > 0;
    }

    @Override
    public List<GuiOverlayButton.ItemOverlayState> presenceOverlay(GuiContainer gui, IRecipeHandler recipe,
        int recipeIndex) {
        List<ItemStack> pool = ((FieldStationContainer) gui.inventorySlots).materials();
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
}
