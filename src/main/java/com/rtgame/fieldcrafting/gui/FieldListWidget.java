package com.rtgame.fieldcrafting.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;

/**
 * 物品清单里的可滚动物品网格（客户端控件）。
 * <ul>
 * <li>鼠标滚轮或拖动右侧滚动条滚动；</li>
 * <li>点击物品取整组、右键取一半、Shift+点击放入背包；</li>
 * <li>光标上有物品时，点击网格任意位置都是存入；</li>
 * <li>断电时整体变灰，不响应任何操作。</li>
 * </ul>
 * 真正的存取由服务端完成，这里只发送点击事件并展示服务端推来的内容。
 */
public class FieldListWidget extends Widget<FieldListWidget> implements Interactable {

    public static final int COLUMNS = 9;
    public static final int CELL = 18;
    private static final int BAR_WIDTH = 6;
    private static final int BAR_GAP = 2;

    private final FieldListSync sync;
    private final Supplier<String> filterText;

    private int scrollRow;
    private boolean draggingBar;

    private int cachedVersion = -1;
    private String cachedFilter = null;
    private int cachedView = -1;
    private List<FieldListSync.Entry> filtered = new ArrayList<>();

    public FieldListWidget(FieldListSync sync, Supplier<String> filterText) {
        this.sync = sync;
        this.filterText = filterText;
        size(COLUMNS * CELL + BAR_GAP + BAR_WIDTH, 8 * CELL);
    }

    private int visibleRows() {
        return Math.max(1, getArea().height / CELL);
    }

    private List<FieldListSync.Entry> filtered() {
        String filter = filterText.get();
        if (filter == null) filter = "";
        filter = filter.trim()
            .toLowerCase(Locale.ROOT);
        if (cachedVersion != sync.getVersion() || !filter.equals(cachedFilter) || cachedView != sync.getView()) {
            List<FieldListSync.Entry> result = new ArrayList<>();
            for (FieldListSync.Entry entry : sync.getEntries()) {
                if (filter.isEmpty() || entry.name.contains(filter)) result.add(entry);
            }
            if (sync.getView() == FieldListSync.VIEW_SUMMARY) {
                result.sort((a, b) -> a.name.compareTo(b.name));
            }
            filtered = result;
            cachedVersion = sync.getVersion();
            cachedFilter = filter;
            cachedView = sync.getView();
        }
        return filtered;
    }

    private int maxScroll(int total) {
        int totalRows = (total + COLUMNS - 1) / COLUMNS;
        return Math.max(0, totalRows - visibleRows());
    }

    private int indexAt(int mx, int my, int total) {
        if (mx < 0 || my < 0 || mx >= COLUMNS * CELL || my >= visibleRows() * CELL) return -1;
        int idx = (scrollRow + my / CELL) * COLUMNS + mx / CELL;
        return idx < total ? idx : -1;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        List<FieldListSync.Entry> list = filtered();
        int rows = visibleRows();
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll(list.size())));
        int hovered = isHovering() ? indexAt(context.getMouseX(), context.getMouseY(), list.size()) : -1;
        boolean powered = sync.isPowered();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                int x = c * CELL;
                int y = r * CELL;
                GuiDraw.drawRect(x, y, CELL, CELL, 0xFF373737);
                GuiDraw.drawRect(x + 1, y + 1, CELL - 2, CELL - 2, 0xFF8B8B8B);
                int idx = (scrollRow + r) * COLUMNS + c;
                if (idx >= list.size()) continue;
                FieldListSync.Entry entry = list.get(idx);
                GuiDraw.drawItem(entry.stack, x + 1, y + 1, 16, 16, context.getCurrentDrawingZ());
                GuiDraw.drawAmountText(entry.count, null, x + 1, y + 1, CELL - 2, CELL - 2, Alignment.BottomRight);
                if (idx == hovered && powered) GuiDraw.drawRect(x + 1, y + 1, CELL - 2, CELL - 2, 0x80FFFFFF);
            }
        }

        // 滚动条
        int barX = COLUMNS * CELL + BAR_GAP;
        int trackHeight = rows * CELL;
        GuiDraw.drawRect(barX, 0, BAR_WIDTH, trackHeight, 0xFF373737);
        int max = maxScroll(list.size());
        int thumbHeight = max == 0 ? trackHeight
            : Math.max(8, trackHeight * rows / ((list.size() + COLUMNS - 1) / COLUMNS));
        int thumbY = max == 0 ? 0 : (trackHeight - thumbHeight) * scrollRow / max;
        GuiDraw.drawRect(barX + 1, thumbY + 1, BAR_WIDTH - 2, thumbHeight - 2, 0xFFC6C6C6);

        if (!powered) {
            GuiDraw.drawRect(0, 0, COLUMNS * CELL, trackHeight, 0xB0404040);
            GuiDraw.drawText(
                I18n.format("fieldcrafting.gui.unpowered"),
                COLUMNS * CELL / 2f - 24,
                trackHeight / 2f - 4,
                1f,
                0xFFFF5555,
                true);
        } else if (list.isEmpty()) {
            GuiDraw.drawText(I18n.format("fieldcrafting.gui.empty"), 6, 6, 0.8f, 0xFF404040, false);
        }
    }

    @Override
    public void drawForeground(ModularGuiContext context) {
        if (!isHovering() || !sync.isPowered()) return;
        List<FieldListSync.Entry> list = filtered();
        int idx = indexAt(context.getMouseX(), context.getMouseY(), list.size());
        if (idx < 0) return;
        FieldListSync.Entry entry = list.get(idx);
        ItemStack stack = entry.stack;
        RichTooltip tip = new RichTooltip();
        tip.parent(this);
        tip.addFromItem(stack);
        tip.addLine(IKey.str(I18n.format("fieldcrafting.gui.count", entry.count)));
        context.queueForegroundRender(() -> tip.draw(context, stack));
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public @NotNull Result onMousePressed(int mouseButton) {
        if (!sync.isPowered()) return Result.STOP;
        int mx = getContext().getMouseX();
        int my = getContext().getMouseY();
        List<FieldListSync.Entry> list = filtered();
        if (mx >= COLUMNS * CELL + BAR_GAP) {
            draggingBar = true;
            dragTo(my, list.size());
            return Result.SUCCESS;
        }
        int idx = indexAt(mx, my, list.size());
        FieldListSync.Entry entry = idx >= 0 ? list.get(idx) : null;
        sync.sendClick(entry, mouseButton, Interactable.hasShiftDown());
        Interactable.playButtonClickSound();
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseRelease(int mouseButton) {
        draggingBar = false;
        return true;
    }

    @Override
    public void onMouseDrag(int mouseButton, long timeSinceClick) {
        if (draggingBar) dragTo(getContext().getMouseY(), filtered().size());
    }

    private void dragTo(int my, int total) {
        int max = maxScroll(total);
        if (max == 0) return;
        int track = visibleRows() * CELL;
        scrollRow = Math.max(0, Math.min(max, Math.round(max * (my / (float) track))));
    }

    @Override
    public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
        int max = maxScroll(filtered().size());
        int step = Math.max(1, Math.abs(amount));
        scrollRow = Math.max(0, Math.min(max, scrollRow + (scrollDirection == UpOrDown.UP ? -step : step)));
        return true;
    }
}
