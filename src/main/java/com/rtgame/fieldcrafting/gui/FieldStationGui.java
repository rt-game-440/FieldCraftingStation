package com.rtgame.fieldcrafting.gui;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.SlotGroupWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.rtgame.fieldcrafting.FieldCrafting;
import com.rtgame.fieldcrafting.station.TileFieldStation;

/** 场域合成站界面：左侧是邻近容器的物品清单，右侧是 3×3 合成网格与玩家背包。 */
public final class FieldStationGui {

    private static final int WIDTH = 354;
    private static final int HEIGHT = 200;
    private static final int RIGHT = 185;
    private static final int TWEAK_SIZE = 14;
    /** 快捷按钮列的左边。按钮、网格、结果槽整体在背包宽度内水平居中。 */
    private static final int TWEAK_X = RIGHT + 24;
    /** 合成网格左上角，在标题与背包之间垂直居中。 */
    private static final int GRID_X = TWEAK_X + 18;
    private static final int GRID_Y = 41;
    private static final float POWER_TEXT_SCALE = 0.75f;

    private FieldStationGui() {}

    public static ModularPanel build(TileFieldStation tile, PosGuiData data, PanelSyncManager syncManager,
        UISettings settings) {
        FieldStationContainer container = new FieldStationContainer(tile);
        settings.customContainer(() -> container);

        FieldListSync list = new FieldListSync(tile);
        container.setListSync(list);
        syncManager.syncValue("field_list", list);
        syncManager.bindPlayerInventory(data.getPlayer());

        ModularPanel panel = ModularPanel.defaultPanel("field_station", WIDTH, HEIGHT);

        // ---- 左侧：清单 ----
        StringValue filter = new StringValue("");
        panel.child(
            new TextFieldWidget().value(filter)
                .setMaxLength(32)
                .pos(7, 6)
                .size(96, 14));
        panel.child(
            new ButtonWidget<>().pos(108, 5)
                .size(32, 16)
                .overlay(IKey.lang("fieldcrafting.gui.view.summary"))
                .onMousePressed(button -> {
                    list.setView(FieldListSync.VIEW_SUMMARY, 0);
                    return true;
                })
                .addTooltipLine(I18n.format("fieldcrafting.gui.view.summary.tip")));
        panel.child(
            new ButtonWidget<>().pos(142, 5)
                .size(32, 16)
                .overlay(IKey.lang("fieldcrafting.gui.view.container"))
                .onMousePressed(button -> {
                    list.setView(FieldListSync.VIEW_CONTAINER, 0);
                    return true;
                })
                .addTooltipLine(I18n.format("fieldcrafting.gui.view.container.tip")));

        // 分容器视图的容器选择行
        panel.child(
            new ButtonWidget<>().pos(7, 24)
                .size(14, 14)
                .overlay(IKey.str("<"))
                .onMousePressed(button -> {
                    cycle(list, -1);
                    return true;
                })
                .setEnabledIf(w -> list.getView() == FieldListSync.VIEW_CONTAINER));
        panel.child(
            new ButtonWidget<>().pos(160, 24)
                .size(14, 14)
                .overlay(IKey.str(">"))
                .onMousePressed(button -> {
                    cycle(list, 1);
                    return true;
                })
                .setEnabledIf(w -> list.getView() == FieldListSync.VIEW_CONTAINER));
        panel.child(
            new TextWidget<>(IKey.dynamic(() -> containerLabel(list))).pos(25, 28)
                .size(132, 8)
                .setEnabledIf(w -> list.getView() == FieldListSync.VIEW_CONTAINER));

        panel.child(new FieldListWidget(list, filter::getStringValue).pos(7, 44));

        // ---- 右侧：合成 ----
        panel.child(
            new TextWidget<>(IKey.lang("tile.fieldcrafting.station." + tile.getTier().id + ".name")).pos(RIGHT, 6));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                panel.child(
                    new ItemSlot().slot(new ModularSlot(tile.getGrid(), index))
                        .pos(GRID_X + col * 18, GRID_Y + row * 18));
            }
        }
        panel.child(
            new ItemSlot().slot(new FieldCraftingSlot(tile.getGrid(), TileFieldStation.GRID_SIZE, tile))
                .pos(GRID_X + 3 * 18 + 24, GRID_Y + 18));
        panel.child(
            new TextWidget<>(IKey.dynamic(() -> powerText(list))).scale(POWER_TEXT_SCALE)
                .pos(7, 190)
                .size(100, 8));

        panel.child(tweakButton(0, "rotate", () -> list.sendGridAction(FieldStationContainer.GRID_ROTATE)));
        panel.child(
            tweakButton(
                1,
                "balance",
                () -> list.sendGridAction(
                    GuiScreen.isShiftKeyDown() ? FieldStationContainer.GRID_SPREAD
                        : FieldStationContainer.GRID_BALANCE)));
        panel.child(tweakButton(2, "to_player", () -> list.sendGridAction(FieldStationContainer.GRID_TO_PLAYER)));
        panel.child(
            tweakButton(3, "to_field", () -> list.sendGridAction(FieldStationContainer.GRID_TO_FIELD))
                .setEnabledIf(w -> list.isPowered()));

        panel.child(
            SlotGroupWidget.playerInventory(false)
                .pos(RIGHT, 118));
        return panel;
    }

    /** 网格左侧的一个快捷按钮，从上到下排列。 */
    private static ButtonWidget<?> tweakButton(int index, String name, Runnable action) {
        return new ButtonWidget<>().pos(TWEAK_X, GRID_Y - 1 + index * TWEAK_SIZE)
            .size(TWEAK_SIZE, TWEAK_SIZE)
            .overlay(UITexture.fullImage(FieldCrafting.MODID, "gui/tweak_" + name))
            .onMousePressed(button -> {
                action.run();
                return true;
            })
            .addTooltipLine(I18n.format("fieldcrafting.gui.tweak." + name));
    }

    private static void cycle(FieldListSync list, int delta) {
        int n = list.getContainers()
            .size();
        if (n <= 0) return;
        int next = ((list.getSelected() + delta) % n + n) % n;
        list.setView(FieldListSync.VIEW_CONTAINER, next);
    }

    private static String containerLabel(FieldListSync list) {
        List<FieldListSync.ContainerInfo> containers = list.getContainers();
        if (containers.isEmpty()) return I18n.format("fieldcrafting.gui.no_container");
        int i = Math.max(0, Math.min(list.getSelected(), containers.size() - 1));
        FieldListSync.ContainerInfo info = containers.get(i);
        return String.format(
            "%d/%d %s (%d,%d,%d) %.1fm",
            i + 1,
            containers.size(),
            info.label,
            info.x,
            info.y,
            info.z,
            info.distance);
    }

    private static String powerText(FieldListSync list) {
        return list.isPowered() ? EnumChatFormatting.DARK_GREEN + I18n.format("fieldcrafting.gui.powered")
            : EnumChatFormatting.RED + I18n.format("fieldcrafting.gui.unpowered");
    }
}
