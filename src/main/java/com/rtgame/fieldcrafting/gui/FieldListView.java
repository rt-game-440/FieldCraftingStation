package com.rtgame.fieldcrafting.gui;

import java.util.Arrays;

import net.minecraft.inventory.InventoryBasic;

/**
 * 物品清单的格子，本身就是一个普通的物品容器（和箱子一样）。
 * <p>
 * 格子数按最大行数预留，界面按屏幕高度只显示其中前若干行，其余移到屏幕外。服务端按搜索词、滚动位置、视图把邻近容器里的
 * 物品摆进格子用于对账；客户端用同一套规则（{@link FieldListLayout}）自己摆，所以滚动和搜索不用等服务端。
 */
public class FieldListView extends InventoryBasic {

    public static final int COLUMNS = 9;
    public static final int MAX_ROWS = 30;
    public static final int SIZE = COLUMNS * MAX_ROWS;
    /** 没有显示的格子放到这个横坐标，不可能被鼠标点到。 */
    public static final int HIDDEN_X = -10000;

    private boolean powered = true;
    private int totalRows;
    private int scrollRow;
    /** 当前显示的行数。服务端不知道，按 1 算，只防止滚动位置超出总行数。 */
    private int visibleRows = 1;
    /** 分容器视图里每格对应的容器槽位号；汇总视图和空格为 -1。仅服务端使用。 */
    private final int[] source = new int[SIZE];
    /** 哪些格子的物品只能看不能取（如关闭了自动输出的总线）。 */
    private final boolean[] locked = new boolean[SIZE];

    public FieldListView() {
        super("", false, SIZE);
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

    public int getVisibleRows() {
        return visibleRows;
    }

    public void setVisibleRows(int rows) {
        visibleRows = Math.max(1, Math.min(rows, MAX_ROWS));
        scrollRow = clamp(scrollRow);
    }

    public int maxScroll() {
        return Math.max(0, totalRows - visibleRows);
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
