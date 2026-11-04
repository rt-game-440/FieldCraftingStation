package com.rtgame.fieldcrafting.gui;

import java.util.Arrays;

import com.cleanroommc.modularui.utils.item.ItemStackHandler;

/**
 * 物品清单的 {@value #COLUMNS}×{@value #ROWS} 个格子，本身就是一个普通的物品容器（和箱子一样）。
 * <p>
 * 服务端按搜索词、滚动位置、视图把邻近容器里的物品摆进这些格子，再由 ModularUI 像同步任何原版槽位一样推给客户端；
 * 玩家对格子的点击走原版的 {@code slotClick}，服务端点击前后对账，把格子的增减落实到真实容器
 * （见 {@link FieldListSync#beginClick} / {@link FieldListSync#finishClick}）。
 * 因此 NEI 的 R / U / A、书签、合成链等依赖"槽位里真有这个物品"的功能都直接可用。
 */
public class FieldListView extends ItemStackHandler {

    public static final int COLUMNS = 9;
    public static final int ROWS = 8;
    public static final int SIZE = COLUMNS * ROWS;

    private boolean powered = true;
    private int totalRows;
    private int scrollRow;
    /** 分容器视图里每格对应的容器槽位号；汇总视图和空格为 -1。仅服务端使用。 */
    private final int[] source = new int[SIZE];
    /** 哪些格子的物品只能看不能取（如关闭了自动输出的总线）。 */
    private final boolean[] locked = new boolean[SIZE];

    public FieldListView() {
        super(SIZE);
        Arrays.fill(source, -1);
    }

    public boolean isPowered() {
        return powered;
    }

    public void setPowered(boolean powered) {
        this.powered = powered;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = Math.max(0, totalRows);
        this.scrollRow = clamp(scrollRow);
    }

    public int maxScroll() {
        return Math.max(0, totalRows - ROWS);
    }

    public int getScrollRow() {
        return scrollRow;
    }

    public void setScrollRow(int row) {
        scrollRow = clamp(row);
    }

    private int clamp(int row) {
        return Math.max(0, Math.min(row, maxScroll()));
    }

    public int sourceOf(int cell) {
        return source[cell];
    }

    public void setSource(int cell, int slot) {
        source[cell] = slot;
    }

    public boolean isLocked(int cell) {
        return locked[cell];
    }

    public void setLocked(int cell, boolean value) {
        locked[cell] = value;
    }
}
