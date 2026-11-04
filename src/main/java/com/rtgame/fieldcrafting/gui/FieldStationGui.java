package com.rtgame.fieldcrafting.gui;

import java.util.List;

import net.minecraft.client.resources.I18n;

import com.cleanroommc.modularui.api.drawable.IKey;
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
import com.rtgame.fieldcrafting.station.TileFieldStation;

/** 场域合成站界面：左侧是邻近容器的物品清单，右侧是 3×3 合成网格与玩家背包。 */
public final class FieldStationGui {

    private static final int WIDTH = 354;
    private static final int HEIGHT = 200;
    private static final int RIGHT = 185;

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
                        .pos(RIGHT + 8 + col * 18, 24 + row * 18));
            }
        }
        panel.child(
            new ItemSlot().slot(new FieldCraftingSlot(tile.getGrid(), TileFieldStation.GRID_SIZE, tile))
                .pos(RIGHT + 8 + 3 * 18 + 28, 24 + 18));
        panel.child(
            new TextWidget<>(IKey.dynamic(() -> powerText(list))).pos(RIGHT + 8 + 3 * 18 + 8, 24 + 3 * 18 + 2)
                .size(100, 8));

        panel.child(
            SlotGroupWidget.playerInventory(false)
                .pos(RIGHT, 118));
        return panel;
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
        return list.isPowered() ? I18n.format("fieldcrafting.gui.powered") : I18n.format("fieldcrafting.gui.unpowered");
    }
}
