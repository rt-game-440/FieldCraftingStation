package com.rtgame.fieldcrafting.gui;

import java.util.function.Supplier;

import net.minecraft.client.resources.I18n;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;

/**
 * 物品清单：{@value FieldListView#COLUMNS}×{@value FieldListView#ROWS} 个原版槽位加一条滚动条。
 * <ul>
 * <li>每个格子都是真正的原版槽位，点击、Shift、双击、拖拽、NEI 的 R / U / A、书签、合成链都按原版容器的方式工作；</li>
 * <li>鼠标滚轮或拖动右侧滚动条滚动，搜索框内容变化时通知服务端重排；</li>
 * <li>断电时格子清空并显示提示。</li>
 * </ul>
 */
public class FieldListWidget extends ParentWidget<FieldListWidget> implements Interactable {

    public static final int CELL = 18;
    private static final int BAR_WIDTH = 6;
    private static final int BAR_GAP = 2;
    private static final int GRID_WIDTH = FieldListView.COLUMNS * CELL;
    private static final int GRID_HEIGHT = FieldListView.ROWS * CELL;

    private final FieldListSync sync;
    private final FieldListView view;
    private final Supplier<String> filterText;
    private boolean draggingBar;
    private boolean readySent;

    public FieldListWidget(FieldListSync sync, Supplier<String> filterText) {
        this.sync = sync;
        this.view = sync.cells();
        this.filterText = filterText;
        size(GRID_WIDTH + BAR_GAP + BAR_WIDTH, GRID_HEIGHT);
        for (int i = 0; i < FieldListView.SIZE; i++) {
            int row = i / FieldListView.COLUMNS;
            int col = i % FieldListView.COLUMNS;
            child(
                new ItemSlot().slot(new FieldListSlot(view, i))
                    .pos(col * CELL, row * CELL));
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!readySent) {
            readySent = true;
            sync.notifyClientReady();
        }
        sync.setFilter(filterText.get());
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        int barX = GRID_WIDTH + BAR_GAP;
        GuiDraw.drawRect(barX, 0, BAR_WIDTH, GRID_HEIGHT, 0xFF373737);
        int max = view.maxScroll();
        int thumbHeight = max == 0 ? GRID_HEIGHT : Math.max(8, GRID_HEIGHT * FieldListView.ROWS / view.getTotalRows());
        int thumbY = max == 0 ? 0 : (GRID_HEIGHT - thumbHeight) * view.getScrollRow() / max;
        GuiDraw.drawRect(barX + 1, thumbY + 1, BAR_WIDTH - 2, thumbHeight - 2, 0xFFC6C6C6);
    }

    @Override
    public void drawOverlay(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        super.drawOverlay(context, widgetTheme);
        if (!view.isPowered()) {
            GuiDraw.drawRect(0, 0, GRID_WIDTH, GRID_HEIGHT, 0xB0404040);
            GuiDraw.drawText(
                I18n.format("fieldcrafting.gui.unpowered"),
                GRID_WIDTH / 2f - 24,
                GRID_HEIGHT / 2f - 4,
                1f,
                0xFFFF5555,
                true);
        } else if (view.getTotalRows() == 0) {
            GuiDraw.drawText(I18n.format("fieldcrafting.gui.empty"), 6, 6, 0.8f, 0xFF404040, false);
        }
    }

    // ---------------------------------------------------------------- 滚动

    @Override
    public @NotNull Result onMousePressed(int mouseButton) {
        // 格子区域的点击由格子控件处理，这里只会收到滚动条上的点击
        if (getContext().getMouseX() >= GRID_WIDTH + BAR_GAP) {
            draggingBar = true;
            dragTo(getContext().getMouseY());
            return Result.SUCCESS;
        }
        return Result.IGNORE;
    }

    @Override
    public boolean onMouseRelease(int mouseButton) {
        draggingBar = false;
        return true;
    }

    @Override
    public void onMouseDrag(int mouseButton, long timeSinceClick) {
        if (draggingBar) dragTo(getContext().getMouseY());
    }

    private void dragTo(int my) {
        int max = view.maxScroll();
        if (max == 0) return;
        sync.setScroll(Math.round(max * (my / (float) GRID_HEIGHT)));
    }

    @Override
    public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
        int step = Math.max(1, Math.abs(amount));
        sync.setScroll(view.getScrollRow() + (scrollDirection == UpOrDown.UP ? -step : step));
        return true;
    }
}
