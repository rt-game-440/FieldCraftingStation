package com.rtgame.fieldcrafting.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import com.rtgame.fieldcrafting.FieldCrafting;
import com.rtgame.fieldcrafting.field.CellLedger;
import com.rtgame.fieldcrafting.field.FieldContainer;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import io.netty.buffer.Unpooled;

/**
 * 物品清单的数据与同步。
 * <p>
 * 服务端按视图把邻近容器的内容打成快照发给客户端，客户端自己按搜索词和滚动位置摆格子（{@link FieldListLayout}），
 * 所以滚动、搜索立刻生效，也不会被在路上的旧数据闪回。服务端用同一套规则摆自己的格子；玩家对格子的所有操作
 * （点击、Shift、双击）都走原版逻辑，服务端在点击前后对账（{@link #beginClick} / {@link #finishClick}），
 * 把格子的增减落实到真实容器。
 */
public class FieldListSync {

    public static final int VIEW_SUMMARY = 0;
    public static final int VIEW_CONTAINER = 1;

    private static final int ID_SNAPSHOT = 1; // S2C
    private static final int ID_CRAFT_RESULT = 2; // S2C：NEI 合成链实际完成的次数
    private static final int ID_SET_VIEW = 3; // C2S
    private static final int ID_FILL = 4; // C2S：NEI "+" 填充网格
    private static final int ID_CRAFT = 5; // C2S：NEI 合成链自动合成指定次数
    private static final int ID_SET_FILTER = 6; // C2S
    private static final int ID_SET_SCROLL = 7; // C2S
    private static final int ID_CLIENT_READY = 8; // C2S：客户端界面已就绪，请求完整同步
    private static final int ID_GRID_ACTION = 9; // C2S：网格快捷操作（旋转、均分、平铺、清空）

    /** {@link Click} 的目标容器已不在范围内时用的下标，取出和存入都会失败，物品退还给玩家。 */
    private static final int CONTAINER_GONE = Integer.MAX_VALUE;

    private static final int UPDATE_INTERVAL_TICKS = 5;

    // ------------------------------------------------------------ 数据类型

    /** 分容器视图里的一个容器。 */
    public static final class ContainerInfo {

        public final String label;
        public final int x;
        public final int y;
        public final int z;
        public final float distance;

        ContainerInfo(String label, int x, int y, int z, float distance) {
            this.label = label;
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
        }
    }

    /** 一次点击开始前的格子快照，点击结束后与格子现状对账。 */
    public static final class Click {

        private final ItemStack[] before = new ItemStack[FieldListView.SIZE];
        private final int[] source = new int[FieldListView.SIZE];
        private int view;
        private int[] containerPos;
    }

    private final TileFieldStation tile;
    private final EntityPlayer player;
    /** 窗口号在容器构造之后才由原版赋值，所以用时才取。 */
    private final IntSupplier windowId;
    private final FieldListView cells = new FieldListView();

    /** 当前视图（客户端表示期望的视图；服务端表示该玩家正在看的视图）。 */
    private int view = VIEW_SUMMARY;
    private int selected = 0;

    // 客户端
    /** 所有容器的汇总，NEI 判断材料是否齐全用；NEI 的后台线程也会读，所以整体替换。 */
    private volatile List<FieldListLayout.Entry> summaryEntries = Collections.emptyList();
    /** 当前视图要显示的条目。 */
    private List<FieldListLayout.Entry> displayEntries = Collections.emptyList();
    private List<ContainerInfo> containers = Collections.emptyList();
    private String sentFilter = "";
    /** 最新一次视图请求的序号；快照带回服务端已处理到的序号，对不上的说明是旧应答。 */
    private int viewRequest;
    private volatile int lastCraftResult = -1;

    // 服务端
    private String filter = "";
    private int effectiveSelected;
    /** 选中容器的坐标。下标会随附近容器增减而漂移，所以服务端按坐标记住选中的是哪个容器。 */
    private int[] selectedPos;
    private int handledViewRequest;
    private byte[] lastSnapshot;
    private long lastSendTick = -1000;
    private boolean forceSend = true;
    /** 正在对账中的点击。此期间格子不能重排，否则点击前后的差异会被算错，造成物品复制或丢失。 */
    private boolean clickInProgress;

    FieldListSync(TileFieldStation tile, EntityPlayer player, IntSupplier windowId) {
        this.tile = tile;
        this.player = player;
        this.windowId = windowId;
    }

    private void syncToServer(int id, FieldNet.Writer writer) {
        FieldNet.sendToServer(windowId.getAsInt(), id, writer);
    }

    private void syncToClient(int id, FieldNet.Writer writer) {
        if (player instanceof EntityPlayerMP mp) FieldNet.sendToPlayer(mp, windowId.getAsInt(), id, writer);
    }

    // ------------------------------------------------------------ 客户端 API

    public FieldListView cells() {
        return cells;
    }

    public int getView() {
        return view;
    }

    public int getSelected() {
        return selected;
    }

    public boolean isPowered() {
        return cells.isPowered();
    }

    public List<FieldListLayout.Entry> getSummaryEntries() {
        return summaryEntries;
    }

    public List<ContainerInfo> getContainers() {
        return containers;
    }

    public void setView(int newView, int newSelected) {
        if (newView == view && newSelected == selected) return;
        boolean sameView = newView == view;
        view = newView;
        selected = Math.max(0, newSelected);
        cells.setScrollRow(0);
        if (!sameView || newView == VIEW_CONTAINER) displayEntries = Collections.emptyList();
        relayout();
        int request = ++viewRequest;
        syncToServer(ID_SET_VIEW, buf -> {
            buf.writeByte(view);
            buf.writeVarIntToBuffer(selected);
            buf.writeVarIntToBuffer(request);
        });
    }

    /** 客户端界面就绪后调用一次，请求一份完整数据。 */
    public void notifyClientReady() {
        syncToServer(ID_CLIENT_READY, buf -> {});
    }

    /** 搜索词变化时立刻重摆，并通知服务端（滚动位置回到顶部）。 */
    public void setFilter(String text) {
        String normalized = FieldListLayout.normalize(text);
        if (normalized.equals(sentFilter)) return;
        sentFilter = normalized;
        cells.setScrollRow(0);
        relayout();
        syncToServer(ID_SET_FILTER, buf -> buf.writeStringToBuffer(normalized));
    }

    public void setScroll(int row) {
        int clamped = Math.max(0, Math.min(row, cells.maxScroll()));
        if (clamped == cells.getScrollRow()) return;
        cells.setScrollRow(clamped);
        relayout();
        syncToServer(ID_SET_SCROLL, buf -> buf.writeVarIntToBuffer(clamped));
    }

    /** 界面告诉清单现在显示几行（随屏幕高度和视图变化）。 */
    public void setVisibleRows(int rows) {
        if (rows == cells.getVisibleRows()) return;
        cells.setVisibleRows(rows);
        relayout();
    }

    /** 客户端：按最新数据、搜索词、滚动位置摆显示中的格子，其余清空。 */
    private void relayout() {
        int columns = FieldListView.COLUMNS;
        int shown = cells.getVisibleRows() * columns;
        List<FieldListLayout.Entry> entries = cells.isPowered() ? displayEntries : Collections.emptyList();
        int scroll = cells.getScrollRow();
        FieldListLayout.Result layout = FieldListLayout.compute(entries, sentFilter, scroll, columns, shown);
        cells.setTotalRows(layout.totalRows);
        if (cells.getScrollRow() != scroll) {
            // 物品变少后滚动位置超出了范围，按收回后的位置重摆，并告诉服务端
            int row = cells.getScrollRow();
            layout = FieldListLayout.compute(entries, sentFilter, row, columns, shown);
            syncToServer(ID_SET_SCROLL, buf -> buf.writeVarIntToBuffer(row));
        }
        apply(layout, shown);
    }

    private void apply(FieldListLayout.Result layout, int shown) {
        for (int i = 0; i < FieldListView.SIZE; i++) {
            boolean inRange = i < shown;
            cells.setInventorySlotContents(i, inRange ? layout.stacks[i] : null);
            cells.setLocked(i, inRange && layout.locked[i]);
            cells.setSource(i, inRange ? layout.source[i] : -1);
        }
    }

    /** 请求服务端用 candidates 填充合成网格（每格若干候选物品，按优先级排列）。 */
    public void sendFill(ItemStack[][] candidates, int multiplier) {
        sendCandidates(ID_FILL, candidates, multiplier);
    }

    /** 请求服务端用 candidates 填充网格并精确合成 crafts 次，成品放入玩家背包（NEI 合成链用）。 */
    public void sendCraft(ItemStack[][] candidates, int crafts) {
        lastCraftResult = -1;
        sendCandidates(ID_CRAFT, candidates, crafts);
    }

    /** 请求服务端执行网格快捷操作，取值见 {@link FieldStationContainer}。 */
    public void sendGridAction(int action) {
        syncToServer(ID_GRID_ACTION, buf -> buf.writeByte(action));
    }

    /** 最近一次 {@link #sendCraft} 实际完成的次数；服务端还没应答时为 -1。 */
    public int getLastCraftResult() {
        return lastCraftResult;
    }

    private void sendCandidates(int id, ItemStack[][] candidates, int multiplier) {
        syncToServer(id, buf -> {
            buf.writeVarIntToBuffer(multiplier);
            for (int i = 0; i < TileFieldStation.GRID_SIZE; i++) {
                ItemStack[] list = candidates[i];
                int n = list == null ? 0 : list.length;
                buf.writeVarIntToBuffer(n);
                for (int j = 0; j < n; j++) buf.writeItemStackToBuffer(list[j]);
            }
        });
    }

    public void readOnClient(int id, PacketBuffer buf) throws IOException {
        if (id == ID_CRAFT_RESULT) {
            lastCraftResult = buf.readVarIntFromBuffer();
            return;
        }
        if (id != ID_SNAPSHOT) return;
        boolean newPowered = buf.readBoolean();
        int newView = buf.readByte();
        int newSelected = buf.readVarIntFromBuffer();
        int handled = buf.readVarIntFromBuffer();
        int containerCount = buf.readVarIntFromBuffer();
        List<ContainerInfo> infos = new ArrayList<>(containerCount);
        for (int i = 0; i < containerCount; i++) {
            String label = buf.readStringFromBuffer(256);
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            float distance = buf.readFloat();
            infos.add(new ContainerInfo(label, x, y, z, distance));
        }
        List<FieldListLayout.Entry> summary = readEntries(buf);
        List<FieldListLayout.Entry> display = newView == VIEW_CONTAINER ? readEntries(buf) : summary;
        cells.setPowered(newPowered);
        summaryEntries = summary;
        if (handled == viewRequest) { // 否则是对旧请求的应答，等服务端处理完最新的视图请求再更新
            view = newView;
            selected = newSelected;
            containers = infos;
            displayEntries = display;
        }
        relayout();
    }

    private static List<FieldListLayout.Entry> readEntries(PacketBuffer buf) throws IOException {
        int n = buf.readVarIntFromBuffer();
        List<FieldListLayout.Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ItemStack stack = buf.readItemStackFromBuffer();
            long free = buf.readLong();
            long stuck = buf.readLong();
            int source = buf.readInt();
            if (stack != null) list.add(new FieldListLayout.Entry(stack, free, stuck, source));
        }
        return list;
    }

    private static void writeEntries(PacketBuffer buf, List<FieldListLayout.Entry> entries) throws IOException {
        buf.writeVarIntToBuffer(entries.size());
        for (FieldListLayout.Entry e : entries) {
            buf.writeItemStackToBuffer(e.stack);
            buf.writeLong(e.free);
            buf.writeLong(e.stuck);
            buf.writeInt(e.source);
        }
    }

    // ------------------------------------------------------------ 服务端：刷新

    public void detectAndSendChanges() {
        if (tile.getWorldObj() == null || tile.getWorldObj().isRemote) return;
        // 原版点击过程中可能调用 detectAndSendChanges，点击结束时 finishClick 会统一重排
        if (clickInProgress) return;
        long now = tile.getWorldObj()
            .getTotalWorldTime();
        if (!forceSend && now - lastSendTick < UPDATE_INTERVAL_TICKS) return;
        lastSendTick = now;
        refreshNow();
    }

    /** 立刻按邻近容器的现状重排格子，并把变化推给客户端。 */
    public void refreshNow() {
        forceSend = false;
        FieldInventory field = tile.isPowered() ? tile.createFieldInventory(player) : null;
        List<FieldListLayout.Entry> summary = summaryOf(field);
        List<FieldListLayout.Entry> display = displayOf(field, summary);
        rebuildCells(field != null, display);
        byte[] data = buildSnapshot(field, summary, display);
        if (lastSnapshot != null && Arrays.equals(lastSnapshot, data)) return;
        lastSnapshot = data;
        syncToClient(ID_SNAPSHOT, buf -> buf.writeBytes(data));
    }

    /** 所有容器的汇总，按名称排序。 */
    private static List<FieldListLayout.Entry> summaryOf(FieldInventory field) {
        if (field == null) return Collections.emptyList();
        List<FieldListLayout.Entry> out = new ArrayList<>();
        for (FieldInventory.SummaryEntry e : field.summary()) {
            ItemStack sample = Stacks.copyWithSize(e.stack, 1);
            out.add(new FieldListLayout.Entry(sample, e.extractable, e.count - e.extractable, -1));
        }
        out.sort(
            (a, b) -> a.name()
                .compareTo(b.name()));
        return out;
    }

    private List<FieldListLayout.Entry> displayOf(FieldInventory field, List<FieldListLayout.Entry> summary) {
        effectiveSelected = 0;
        if (field == null || view == VIEW_SUMMARY) return summary;
        effectiveSelected = resolveSelected(field);
        List<FieldListLayout.Entry> out = new ArrayList<>();
        if (field.containerCount() > 0) {
            for (FieldInventory.SlotEntry e : field.containerItems(effectiveSelected)) {
                int size = e.stack.stackSize;
                ItemStack sample = Stacks.copyWithSize(e.stack, 1);
                out.add(new FieldListLayout.Entry(sample, e.extractable ? size : 0, e.extractable ? 0 : size, e.slot));
            }
        }
        return out;
    }

    /** 按视图、搜索词、滚动位置把物品摆进格子；断电时格子全部清空。 */
    private void rebuildCells(boolean powered, List<FieldListLayout.Entry> display) {
        cells.setPowered(powered);
        FieldListLayout.Result layout = FieldListLayout
            .compute(display, filter, cells.getScrollRow(), FieldListView.COLUMNS, FieldListView.SIZE);
        cells.setTotalRows(layout.totalRows);
        apply(layout, FieldListView.SIZE);
    }

    /** 选中容器现在的下标。选中的容器不在了就退到相同下标（或最后一个）的容器。 */
    private int resolveSelected(FieldInventory field) {
        int count = field.containerCount();
        if (count == 0) {
            selectedPos = null;
            return 0;
        }
        int index = selectedPos == null ? -1 : field.indexOf(selectedPos[0], selectedPos[1], selectedPos[2]);
        if (index < 0) index = Math.min(selected, count - 1);
        FieldContainer c = field.container(index);
        selectedPos = new int[] { c.x, c.y, c.z };
        selected = index;
        return index;
    }

    private byte[] buildSnapshot(FieldInventory field, List<FieldListLayout.Entry> summary,
        List<FieldListLayout.Entry> display) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            buf.writeBoolean(field != null);
            buf.writeByte(view);
            buf.writeVarIntToBuffer(effectiveSelected);
            buf.writeVarIntToBuffer(handledViewRequest);
            if (view == VIEW_CONTAINER && field != null) {
                int count = field.containerCount();
                buf.writeVarIntToBuffer(count);
                for (int i = 0; i < count; i++) {
                    FieldContainer c = field.container(i);
                    buf.writeStringToBuffer(c.label.length() > 200 ? c.label.substring(0, 200) : c.label);
                    buf.writeInt(c.x);
                    buf.writeInt(c.y);
                    buf.writeInt(c.z);
                    buf.writeFloat((float) Math.sqrt(c.distanceSq));
                }
            } else {
                buf.writeVarIntToBuffer(0);
            }
            writeEntries(buf, summary);
            if (view == VIEW_CONTAINER) writeEntries(buf, display);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    // ------------------------------------------------------------ 服务端：客户端请求

    public void readOnServer(int id, PacketBuffer buf) throws IOException {
        switch (id) {
            case ID_CLIENT_READY -> {
                lastSnapshot = null;
                refreshNow();
                if (player.openContainer instanceof FieldStationContainer container) {
                    container.resyncToPlayer(player);
                }
            }
            case ID_GRID_ACTION -> {
                int action = buf.readByte();
                if (player.openContainer instanceof FieldStationContainer container) {
                    container.applyGridAction(player, action);
                }
            }
            case ID_SET_VIEW -> {
                int newView = buf.readByte();
                int newSelected = buf.readVarIntFromBuffer();
                handledViewRequest = buf.readVarIntFromBuffer();
                view = newView == VIEW_CONTAINER ? VIEW_CONTAINER : VIEW_SUMMARY;
                selected = Math.max(0, newSelected);
                selectedPos = null;
                cells.setScrollRow(0);
                refreshNow(); // 之后的点击要面对新视图的格子
            }
            case ID_SET_FILTER -> {
                filter = FieldListLayout.normalize(buf.readStringFromBuffer(64));
                cells.setScrollRow(0);
                refreshNow();
            }
            case ID_SET_SCROLL -> {
                cells.setScrollRow(buf.readVarIntFromBuffer());
                forceSend = true;
            }
            case ID_FILL, ID_CRAFT -> {
                int multiplier = buf.readVarIntFromBuffer();
                ItemStack[][] candidates = new ItemStack[TileFieldStation.GRID_SIZE][];
                for (int i = 0; i < candidates.length; i++) {
                    int n = Math.max(0, Math.min(buf.readVarIntFromBuffer(), 64));
                    candidates[i] = new ItemStack[n];
                    for (int j = 0; j < n; j++) candidates[i][j] = buf.readItemStackFromBuffer();
                }
                if (player.openContainer instanceof FieldStationContainer container) {
                    if (id == ID_FILL) {
                        container.fillGrid(player, candidates, multiplier);
                    } else {
                        int done = container.craftExact(player, candidates, multiplier);
                        syncToClient(ID_CRAFT_RESULT, out -> out.writeVarIntToBuffer(done));
                    }
                }
                forceSend = true;
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------ 服务端：原版点击对账

    /**
     * 在原版 {@code slotClick} 之前调用：按邻近容器的现状重排格子（保证点击面对的是真实、最新的内容），并记录快照。
     * 重排同时会刷新供电状态，刚断电时格子已被清空、不可操作，不存在"格子还在但对账不了"的窗口。
     *
     * @return 断电时返回 null（格子不可操作，无需对账）
     */
    public Click beginClick() {
        refreshNow();
        if (!cells.isPowered()) return null;
        clickInProgress = true;
        Click click = new Click();
        click.view = view;
        click.containerPos = selectedPos;
        for (int i = 0; i < FieldListView.SIZE; i++) {
            ItemStack stack = cells.getStackInSlot(i);
            click.before[i] = stack == null ? null : stack.copy();
            click.source[i] = cells.sourceOf(i);
        }
        return click;
    }

    /**
     * 在原版 {@code slotClick} 之后调用：把格子的变化结算到真实容器（见 {@link CellLedger}），
     * 玩家多拿到的收回，存不下的退还，最后重排格子。
     */
    public void finishClick(EntityPlayer who, Click click) {
        if (click == null) return;
        clickInProgress = false;
        ItemStack[] after = new ItemStack[FieldListView.SIZE];
        for (int i = 0; i < FieldListView.SIZE; i++) after[i] = cells.getStackInSlot(i);
        if (CellLedger.hasChanges(click.before, after)) {
            FieldInventory field = tile.createFieldInventory(who);
            CellLedger.Result result = CellLedger
                .settle(field, clickTarget(field, click), click.before, click.source, after);
            for (ItemStack stack : result.owedByPlayer) takeBack(who, field, stack);
            for (ItemStack stack : result.unplaced) refund(who, stack);
        }
        refreshNow();
    }

    private static int clickTarget(FieldInventory field, Click click) {
        if (click.view != VIEW_CONTAINER) return CellLedger.SUMMARY;
        if (click.containerPos == null) return CONTAINER_GONE;
        int index = field.indexOf(click.containerPos[0], click.containerPos[1], click.containerPos[2]);
        return index < 0 ? CONTAINER_GONE : index;
    }

    /** 点击处理抛异常时调用，避免格子从此停止刷新。 */
    public void abortClick() {
        clickInProgress = false;
    }

    /** 真实容器取不出的物品从玩家那里收回：先光标，再背包。 */
    private static void takeBack(EntityPlayer who, FieldInventory field, ItemStack owed) {
        int missing = owed.stackSize;
        ItemStack cursor = who.inventory.getItemStack();
        if (!Stacks.isEmpty(cursor) && Stacks.same(cursor, owed)) {
            int taken = Math.min(cursor.stackSize, missing);
            cursor.stackSize -= taken;
            missing -= taken;
            if (cursor.stackSize <= 0) who.inventory.setItemStack(null);
        }
        while (missing > 0) {
            ItemStack taken = field.takeFromBackpack(owed, missing);
            if (taken == null) break;
            missing -= taken.stackSize;
        }
        if (missing > 0) {
            FieldCrafting.LOG.warn("物品清单对账时有 {} 个 {} 无法收回", missing, owed.getDisplayName());
        }
    }

    /** 放不进真实容器的物品还给玩家：先光标，再背包，最后丢在脚边。 */
    private static void refund(EntityPlayer who, ItemStack stack) {
        ItemStack cursor = who.inventory.getItemStack();
        if (Stacks.isEmpty(cursor)) {
            who.inventory.setItemStack(stack);
        } else if (Stacks.same(cursor, stack) && cursor.stackSize + stack.stackSize <= cursor.getMaxStackSize()) {
            cursor.stackSize += stack.stackSize;
        } else {
            who.inventory.addItemStackToInventory(stack);
            if (stack.stackSize > 0) who.dropPlayerItemWithRandomChoice(stack, false);
        }
    }

    /**
     * 用光标上的物品点击清单：左键存入整组，右键存入 1 个，放不下的留在光标上。
     * 存入与点到哪一格无关，物品去向只由当前视图决定。
     */
    public void depositCursor(EntityPlayer who, int mouseButton) {
        ItemStack cursor = who.inventory.getItemStack();
        if (Stacks.isEmpty(cursor)) return;
        int amount = mouseButton == 1 ? 1 : cursor.stackSize;
        ItemStack rest = store(who, Stacks.copyWithSize(cursor, amount));
        cursor.stackSize -= amount - (rest == null ? 0 : rest.stackSize);
        if (cursor.stackSize <= 0) who.inventory.setItemStack(null);
        refreshNow();
    }

    /**
     * 按当前视图把物品存入真实容器：汇总视图走汇总存入，分容器视图只进选中的容器。
     *
     * @return 放不下的部分；全部放下返回 null
     */
    public ItemStack store(EntityPlayer who, ItemStack stack) {
        if (!tile.isPowered()) return stack;
        FieldInventory field = tile.createFieldInventory(who);
        forceSend = true;
        if (view == VIEW_CONTAINER) {
            if (field.containerCount() == 0) return stack;
            return field.insertInto(resolveSelected(field), stack);
        }
        return field.insertSummary(stack);
    }
}
