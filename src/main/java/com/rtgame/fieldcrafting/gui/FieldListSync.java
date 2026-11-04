package com.rtgame.fieldcrafting.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import com.cleanroommc.modularui.network.NetworkUtils;
import com.cleanroommc.modularui.value.sync.SyncHandler;
import com.rtgame.fieldcrafting.FieldCrafting;
import com.rtgame.fieldcrafting.field.CellLedger;
import com.rtgame.fieldcrafting.field.FieldContainer;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import io.netty.buffer.Unpooled;

/**
 * 物品清单的同步处理器。
 * <p>
 * 清单的 {@link FieldListView#SIZE} 个格子是真正的原版槽位（{@link FieldListView}）：服务端按视图、搜索词、滚动位置
 * 把邻近容器里的物品摆进格子，由 ModularUI 的槽位同步推给客户端。玩家对格子的所有操作（点击、Shift、双击、拖拽）
 * 都走原版逻辑，服务端在点击前后对账（{@link #beginClick} / {@link #finishClick}），把格子的增减落实到真实容器。
 * <p>
 * 这里另外同步的只有：供电状态、容器列表、总行数，以及完整的物品汇总（供 NEI 判断材料是否齐全）。
 */
public class FieldListSync extends SyncHandler<FieldListSync> {

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

    /** 物品汇总里的一项。 */
    public static final class Entry {

        /** 数量为 1 的物品样本。 */
        public final ItemStack stack;
        public final long count;

        Entry(ItemStack stack, long count) {
            this.stack = stack;
            this.count = count;
        }
    }

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
    private final FieldListView cells = new FieldListView();

    /** 当前视图（客户端表示期望的视图；服务端表示该玩家正在看的视图）。 */
    private int view = VIEW_SUMMARY;
    private int selected = 0;

    // 客户端
    private List<Entry> entries = Collections.emptyList();
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

    public FieldListSync(TileFieldStation tile) {
        this.tile = tile;
        allowC2S();
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

    public List<Entry> getEntries() {
        return entries;
    }

    public List<ContainerInfo> getContainers() {
        return containers;
    }

    public void setView(int newView, int newSelected) {
        if (newView == view && newSelected == selected) return;
        view = newView;
        selected = Math.max(0, newSelected);
        cells.setScrollRow(0);
        int request = ++viewRequest;
        syncToServer(ID_SET_VIEW, buf -> {
            buf.writeByte(view);
            buf.writeVarIntToBuffer(selected);
            buf.writeVarIntToBuffer(request);
        });
    }

    /**
     * 客户端界面就绪后调用一次。服务端打开界面时发出的槽位内容可能先于界面到达客户端而被丢弃，
     * 而原版的变化检测认为已经发过，不会再发，所以等界面就绪后再请求一次完整同步。
     */
    public void notifyClientReady() {
        syncToServer(ID_CLIENT_READY, buf -> {});
    }

    /** 搜索词变化时通知服务端（滚动位置回到顶部）。 */
    public void setFilter(String text) {
        String normalized = normalize(text);
        if (normalized.equals(sentFilter)) return;
        sentFilter = normalized;
        cells.setScrollRow(0);
        syncToServer(ID_SET_FILTER, buf -> writeLabel(buf, normalized));
    }

    public void setScroll(int row) {
        int clamped = Math.max(0, Math.min(row, cells.maxScroll()));
        if (clamped == cells.getScrollRow()) return;
        cells.setScrollRow(clamped);
        syncToServer(ID_SET_SCROLL, buf -> buf.writeVarIntToBuffer(clamped));
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
                for (int j = 0; j < n; j++) NetworkUtils.writeItemStack(buf, list[j]);
            }
        });
    }

    @Override
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
        for (int i = 0; i < FieldListView.SIZE; i++) cells.setLocked(i, buf.readBoolean());
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
        int totalRows = buf.readVarIntFromBuffer();
        int n = buf.readVarIntFromBuffer();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ItemStack stack = NetworkUtils.readItemStack(buf);
            long count = buf.readLong();
            if (stack != null) list.add(new Entry(stack, count));
        }
        cells.setPowered(newPowered);
        entries = list;
        if (handled != viewRequest) return; // 这是对旧请求的应答，等服务端处理完最新的视图请求再更新
        view = newView;
        selected = newSelected;
        containers = infos;
        cells.setTotalRows(totalRows);
    }

    // ------------------------------------------------------------ 服务端：刷新

    @Override
    public void detectAndSendChanges(boolean init) {
        if (tile.getWorldObj() == null || tile.getWorldObj().isRemote) return;
        // ModularContainer.slotClick 内部会调用 detectAndSendChanges，点击结束时 finishClick 会统一重排
        if (clickInProgress) return;
        long now = tile.getWorldObj()
            .getTotalWorldTime();
        if (!forceSend && !init && now - lastSendTick < UPDATE_INTERVAL_TICKS) return;
        lastSendTick = now;
        refreshNow();
    }

    /** 立刻按邻近容器的现状重排格子，并把变化推给客户端。 */
    public void refreshNow() {
        forceSend = false;
        EntityPlayer player = getSyncManager().getPlayer();
        FieldInventory field = tile.isPowered() ? tile.createFieldInventory(player) : null;
        List<FieldInventory.SummaryEntry> summary = field == null ? Collections.emptyList() : field.summary();
        rebuildCells(field, summary);
        byte[] data = buildSnapshot(field, summary);
        if (lastSnapshot != null && Arrays.equals(lastSnapshot, data)) return;
        lastSnapshot = data;
        syncToClient(ID_SNAPSHOT, buf -> buf.writeBytes(data));
    }

    private static String normalize(String text) {
        return text == null ? ""
            : text.trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String displayName(ItemStack stack) {
        try {
            return normalize(stack.getDisplayName());
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeLabel(PacketBuffer buf, String label) {
        try {
            buf.writeStringToBuffer(label);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按视图、搜索词、滚动位置把物品摆进格子；field 为 null 表示断电，格子全部清空。 */
    private void rebuildCells(FieldInventory field, List<FieldInventory.SummaryEntry> summary) {
        ItemStack[] out = new ItemStack[FieldListView.SIZE];
        boolean[] lock = new boolean[FieldListView.SIZE];
        int[] src = new int[FieldListView.SIZE];
        Arrays.fill(src, -1);
        cells.setPowered(field != null);
        effectiveSelected = 0;
        int columns = FieldListView.COLUMNS;

        if (field == null) {
            cells.setTotalRows(0);
        } else if (view == VIEW_SUMMARY) {
            List<FieldInventory.SummaryEntry> shown = new ArrayList<>();
            long totalCells = 0;
            for (FieldInventory.SummaryEntry e : summary) {
                if (filter.isEmpty() || displayName(e.stack).contains(filter)) {
                    shown.add(e);
                    totalCells += chunks(e);
                }
            }
            shown.sort((a, b) -> displayName(a.stack).compareTo(displayName(b.stack)));
            cells.setTotalRows((int) Math.min(Integer.MAX_VALUE, (totalCells + columns - 1) / columns));
            long from = (long) cells.getScrollRow() * columns;
            long pos = 0;
            outer: for (FieldInventory.SummaryEntry e : shown) {
                long n = chunks(e);
                if (pos + n <= from) {
                    pos += n;
                    continue;
                }
                int max = maxStack(e.stack);
                // 能取出的排在前面，只能看不能取的单独成格
                long free = e.extractable;
                long stuck = e.count - e.extractable;
                for (long c = 0; c < n; c++, pos++) {
                    boolean isStuck = free == 0;
                    int size = (int) Math.min(isStuck ? stuck : free, max);
                    if (isStuck) stuck -= size;
                    else free -= size;
                    if (pos < from) continue;
                    if (pos >= from + FieldListView.SIZE) break outer;
                    out[(int) (pos - from)] = Stacks.copyWithSize(e.stack, size);
                    lock[(int) (pos - from)] = isStuck;
                }
            }
        } else {
            int count = field.containerCount();
            effectiveSelected = resolveSelected(field);
            List<FieldInventory.SlotEntry> shown = new ArrayList<>();
            if (count > 0) {
                for (FieldInventory.SlotEntry e : field.containerItems(effectiveSelected)) {
                    if (filter.isEmpty() || displayName(e.stack).contains(filter)) shown.add(e);
                }
            }
            cells.setTotalRows((shown.size() + columns - 1) / columns);
            int from = cells.getScrollRow() * columns;
            for (int i = 0; i < FieldListView.SIZE && from + i < shown.size(); i++) {
                FieldInventory.SlotEntry e = shown.get(from + i);
                out[i] = Stacks.copyWithSize(e.stack, Math.min(e.stack.stackSize, maxStack(e.stack)));
                lock[i] = !e.extractable;
                src[i] = e.slot;
            }
        }
        for (int i = 0; i < FieldListView.SIZE; i++) {
            cells.setStackInSlot(i, out[i]);
            cells.setSource(i, src[i]);
            cells.setLocked(i, lock[i]);
        }
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

    private static int maxStack(ItemStack stack) {
        return Math.max(1, stack.getMaxStackSize());
    }

    private static long chunks(FieldInventory.SummaryEntry e) {
        int max = maxStack(e.stack);
        return (e.extractable + max - 1) / max + (e.count - e.extractable + max - 1) / max;
    }

    private byte[] buildSnapshot(FieldInventory field, List<FieldInventory.SummaryEntry> summary) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeBoolean(field != null);
        buf.writeByte(view);
        buf.writeVarIntToBuffer(effectiveSelected);
        buf.writeVarIntToBuffer(handledViewRequest);
        for (int i = 0; i < FieldListView.SIZE; i++) buf.writeBoolean(cells.isLocked(i));
        if (view == VIEW_CONTAINER && field != null) {
            int count = field.containerCount();
            buf.writeVarIntToBuffer(count);
            for (int i = 0; i < count; i++) {
                FieldContainer c = field.container(i);
                writeLabel(buf, c.label.length() > 200 ? c.label.substring(0, 200) : c.label);
                buf.writeInt(c.x);
                buf.writeInt(c.y);
                buf.writeInt(c.z);
                buf.writeFloat((float) Math.sqrt(c.distanceSq));
            }
        } else {
            buf.writeVarIntToBuffer(0);
        }
        buf.writeVarIntToBuffer(cells.getTotalRows());
        buf.writeVarIntToBuffer(summary.size());
        for (FieldInventory.SummaryEntry e : summary) {
            NetworkUtils.writeItemStack(buf, e.stack);
            buf.writeLong(e.extractable);
        }
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    // ------------------------------------------------------------ 服务端：客户端请求

    @Override
    public void readOnServer(int id, PacketBuffer buf) throws IOException {
        EntityPlayer player = getSyncManager().getPlayer();
        switch (id) {
            case ID_CLIENT_READY -> {
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
                forceSend = true;
            }
            case ID_SET_FILTER -> {
                filter = normalize(buf.readStringFromBuffer(64));
                cells.setScrollRow(0);
                forceSend = true;
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
                    for (int j = 0; j < n; j++) candidates[i][j] = NetworkUtils.readItemStack(buf);
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
    public void finishClick(EntityPlayer player, Click click) {
        if (click == null) return;
        clickInProgress = false;
        ItemStack[] after = new ItemStack[FieldListView.SIZE];
        for (int i = 0; i < FieldListView.SIZE; i++) after[i] = cells.getStackInSlot(i);
        if (CellLedger.hasChanges(click.before, after)) {
            FieldInventory field = tile.createFieldInventory(player);
            CellLedger.Result result = CellLedger
                .settle(field, clickTarget(field, click), click.before, click.source, after);
            for (ItemStack stack : result.owedByPlayer) takeBack(player, field, stack);
            for (ItemStack stack : result.unplaced) refund(player, stack);
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
    private static void takeBack(EntityPlayer player, FieldInventory field, ItemStack owed) {
        int missing = owed.stackSize;
        ItemStack cursor = player.inventory.getItemStack();
        if (!Stacks.isEmpty(cursor) && Stacks.same(cursor, owed)) {
            int taken = Math.min(cursor.stackSize, missing);
            cursor.stackSize -= taken;
            missing -= taken;
            if (cursor.stackSize <= 0) player.inventory.setItemStack(null);
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
    private static void refund(EntityPlayer player, ItemStack stack) {
        ItemStack cursor = player.inventory.getItemStack();
        if (Stacks.isEmpty(cursor)) {
            player.inventory.setItemStack(stack);
        } else if (Stacks.same(cursor, stack) && cursor.stackSize + stack.stackSize <= cursor.getMaxStackSize()) {
            cursor.stackSize += stack.stackSize;
        } else {
            player.inventory.addItemStackToInventory(stack);
            if (stack.stackSize > 0) player.dropPlayerItemWithRandomChoice(stack, false);
        }
    }

    /**
     * 用光标上的物品点击清单：左键存入整组，右键存入 1 个，放不下的留在光标上。
     * 存入与点到哪一格无关，物品去向只由当前视图决定。
     */
    public void depositCursor(EntityPlayer player, int mouseButton) {
        ItemStack cursor = player.inventory.getItemStack();
        if (Stacks.isEmpty(cursor)) return;
        int amount = mouseButton == 1 ? 1 : cursor.stackSize;
        ItemStack rest = store(player, Stacks.copyWithSize(cursor, amount));
        cursor.stackSize -= amount - (rest == null ? 0 : rest.stackSize);
        if (cursor.stackSize <= 0) player.inventory.setItemStack(null);
        refreshNow();
    }

    /**
     * 按当前视图把物品存入真实容器：汇总视图走汇总存入，分容器视图只进选中的容器。
     *
     * @return 放不下的部分；全部放下返回 null
     */
    public ItemStack store(EntityPlayer player, ItemStack stack) {
        if (!tile.isPowered()) return stack;
        FieldInventory field = tile.createFieldInventory(player);
        forceSend = true;
        if (view == VIEW_CONTAINER) {
            if (field.containerCount() == 0) return stack;
            return field.insertInto(resolveSelected(field), stack);
        }
        return field.insertSummary(stack);
    }
}
