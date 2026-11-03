package com.rtgame.fieldcrafting.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import com.cleanroommc.modularui.network.NetworkUtils;
import com.cleanroommc.modularui.value.sync.SyncHandler;
import com.rtgame.fieldcrafting.field.FieldContainer;
import com.rtgame.fieldcrafting.field.FieldInventory;
import com.rtgame.fieldcrafting.field.Stacks;
import com.rtgame.fieldcrafting.station.TileFieldStation;

import io.netty.buffer.Unpooled;

/**
 * 物品清单的同步处理器。
 * <p>
 * 服务端定时扫描邻近容器，把当前视图的内容推给客户端；客户端的点击操作回传给服务端，由服务端通过
 * {@link FieldInventory} 完成真正的存取。所有存取规则都只在服务端判定，客户端只负责展示。
 */
public class FieldListSync extends SyncHandler<FieldListSync> {

    public static final int VIEW_SUMMARY = 0;
    public static final int VIEW_CONTAINER = 1;

    private static final int ID_SNAPSHOT = 1; // S2C
    private static final int ID_SET_VIEW = 2; // C2S
    private static final int ID_CLICK = 3; // C2S
    private static final int ID_FILL = 4; // C2S：NEI "+" 填充网格

    private static final int OP_TAKE = 0;
    private static final int OP_STORE_CURSOR = 1;

    private static final int UPDATE_INTERVAL_TICKS = 5;

    // ------------------------------------------------------------ 客户端数据

    /** 清单里的一项。 */
    public static final class Entry {

        /** 数量为 1 的物品样本。 */
        public final ItemStack stack;
        public final long count;
        /** 分容器视图里的槽位号，汇总视图为 -1。 */
        public final int slot;
        public final String name;

        Entry(ItemStack stack, long count, int slot) {
            this.stack = stack;
            this.count = count;
            this.slot = slot;
            String display;
            try {
                display = stack.getDisplayName();
            } catch (Throwable t) {
                display = "";
            }
            this.name = display == null ? "" : display.toLowerCase();
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

    private final TileFieldStation tile;

    /** 当前视图（客户端表示期望的视图；服务端表示该玩家正在看的视图）。 */
    private int view = VIEW_SUMMARY;
    private int selected = 0;

    // 客户端
    private boolean powered = true;
    private List<Entry> entries = Collections.emptyList();
    private List<ContainerInfo> containers = Collections.emptyList();
    private int version;

    // 服务端
    private byte[] lastSnapshot;
    private long lastSendTick = -1000;
    private boolean forceSend = true;

    public FieldListSync(TileFieldStation tile) {
        this.tile = tile;
        allowC2S();
    }

    // ------------------------------------------------------------ 客户端 API

    public int getView() {
        return view;
    }

    public int getSelected() {
        return selected;
    }

    public boolean isPowered() {
        return powered;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public List<ContainerInfo> getContainers() {
        return containers;
    }

    /** 每次收到新数据递增，用于让界面缓存失效。 */
    public int getVersion() {
        return version;
    }

    public void setView(int newView, int newSelected) {
        if (newView == view && newSelected == selected) return;
        view = newView;
        selected = Math.max(0, newSelected);
        entries = Collections.emptyList();
        version++;
        syncToServer(ID_SET_VIEW, buf -> {
            buf.writeByte(view);
            buf.writeVarIntToBuffer(selected);
        });
    }

    /**
     * 点击清单中的一项。
     *
     * @param entry 被点击的项，空白处点击传 null
     */
    public void sendClick(Entry entry, int button, boolean shift) {
        syncToServer(ID_CLICK, buf -> {
            buf.writeByte(entry == null ? OP_STORE_CURSOR : OP_TAKE);
            buf.writeByte(view);
            buf.writeVarIntToBuffer(selected);
            buf.writeByte(button);
            buf.writeBoolean(shift);
            if (entry != null) {
                buf.writeVarIntToBuffer(entry.slot + 1);
                NetworkUtils.writeItemStack(buf, entry.stack);
            }
        });
    }

    /** 请求服务端用 candidates 填充合成网格（每格若干候选物品，按优先级排列）。 */
    public void sendFill(ItemStack[][] candidates, int multiplier) {
        syncToServer(ID_FILL, buf -> {
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
        if (id != ID_SNAPSHOT) return;
        boolean newPowered = buf.readBoolean();
        int newView = buf.readByte();
        int newSelected = buf.readVarIntFromBuffer();
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
        int n = buf.readVarIntFromBuffer();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int slot = buf.readVarIntFromBuffer() - 1;
            ItemStack stack = NetworkUtils.readItemStack(buf);
            long count = buf.readLong();
            if (stack != null) list.add(new Entry(stack, count, slot));
        }
        powered = newPowered;
        if (newView != view || (newView == VIEW_CONTAINER && newSelected != selected)) {
            // 这是对旧请求的应答，等服务端处理完最新的视图请求再更新
            return;
        }
        containers = infos;
        entries = list;
        version++;
    }

    // ------------------------------------------------------------ 服务端

    @Override
    public void detectAndSendChanges(boolean init) {
        if (tile.getWorldObj() == null || tile.getWorldObj().isRemote) return;
        long now = tile.getWorldObj()
            .getTotalWorldTime();
        if (!forceSend && !init && now - lastSendTick < UPDATE_INTERVAL_TICKS) return;
        lastSendTick = now;
        forceSend = false;
        byte[] data = buildSnapshot();
        if (lastSnapshot != null && Arrays.equals(lastSnapshot, data)) return;
        lastSnapshot = data;
        syncToClient(ID_SNAPSHOT, buf -> buf.writeBytes(data));
    }

    private static void writeLabel(PacketBuffer buf, String label) {
        try {
            buf.writeStringToBuffer(label);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] buildSnapshot() {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        EntityPlayer player = getSyncManager().getPlayer();
        boolean isPowered = tile.isPowered();
        FieldInventory field = isPowered ? tile.createFieldInventory(player) : null;
        buf.writeBoolean(isPowered);
        buf.writeByte(view);
        int count = field == null ? 0 : field.containerCount();
        int sel = count == 0 ? 0 : Math.min(selected, count - 1);
        buf.writeVarIntToBuffer(sel);
        if (view == VIEW_CONTAINER && field != null) {
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
        if (field == null) {
            buf.writeVarIntToBuffer(0);
        } else if (view == VIEW_SUMMARY) {
            List<FieldInventory.SummaryEntry> summary = field.summary();
            buf.writeVarIntToBuffer(summary.size());
            for (FieldInventory.SummaryEntry e : summary) {
                buf.writeVarIntToBuffer(0);
                NetworkUtils.writeItemStack(buf, e.stack);
                buf.writeLong(e.count);
            }
        } else if (count == 0) {
            buf.writeVarIntToBuffer(0);
        } else {
            List<FieldInventory.SlotEntry> items = field.containerItems(sel);
            buf.writeVarIntToBuffer(items.size());
            for (FieldInventory.SlotEntry e : items) {
                buf.writeVarIntToBuffer(e.slot + 1);
                NetworkUtils.writeItemStack(buf, Stacks.copyWithSize(e.stack, 1));
                buf.writeLong(e.stack.stackSize);
            }
        }
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    @Override
    public void readOnServer(int id, PacketBuffer buf) throws IOException {
        EntityPlayer player = getSyncManager().getPlayer();
        switch (id) {
            case ID_SET_VIEW -> {
                int newView = buf.readByte();
                int newSelected = buf.readVarIntFromBuffer();
                view = newView == VIEW_CONTAINER ? VIEW_CONTAINER : VIEW_SUMMARY;
                selected = Math.max(0, newSelected);
                forceSend = true;
            }
            case ID_CLICK -> {
                int op = buf.readByte();
                int clickView = buf.readByte();
                int clickSelected = buf.readVarIntFromBuffer();
                int button = buf.readByte();
                boolean shift = buf.readBoolean();
                int slot = -1;
                ItemStack template = null;
                if (op == OP_TAKE) {
                    slot = buf.readVarIntFromBuffer() - 1;
                    template = NetworkUtils.readItemStack(buf);
                }
                handleClick(player, op, clickView, clickSelected, slot, template, button, shift);
            }
            case ID_FILL -> {
                int multiplier = buf.readVarIntFromBuffer();
                ItemStack[][] candidates = new ItemStack[TileFieldStation.GRID_SIZE][];
                for (int i = 0; i < candidates.length; i++) {
                    int n = Math.min(buf.readVarIntFromBuffer(), 64);
                    candidates[i] = new ItemStack[n];
                    for (int j = 0; j < n; j++) candidates[i][j] = NetworkUtils.readItemStack(buf);
                }
                handleFill(player, candidates, multiplier);
            }
            default -> {}
        }
    }

    private void handleClick(EntityPlayer player, int op, int clickView, int clickSelected, int slot,
        ItemStack template, int button, boolean shift) {
        if (!tile.isPowered()) return; // 断电：面板不可操作
        FieldInventory field = tile.createFieldInventory(player);
        boolean containerView = clickView == VIEW_CONTAINER;
        if (containerView && (clickSelected < 0 || clickSelected >= field.containerCount())) return;

        ItemStack cursor = player.inventory.getItemStack();
        if (!Stacks.isEmpty(cursor) && !shift) {
            // 光标上有物品：点击清单任意位置都是存入（左键整组，右键一个）
            storeCursor(player, field, containerView, clickSelected, cursor, button == 1);
        } else if (op == OP_TAKE && template != null) {
            take(player, field, containerView, clickSelected, slot, template, button, shift);
        }
        forceSend = true;
    }

    private void storeCursor(EntityPlayer player, FieldInventory field, boolean containerView, int selectedContainer,
        ItemStack cursor, boolean onlyOne) {
        ItemStack toStore = onlyOne ? Stacks.copyWithSize(cursor, 1) : cursor.copy();
        ItemStack rest = containerView ? field.insertInto(selectedContainer, toStore) : field.insertSummary(toStore);
        int stored = toStore.stackSize - (rest == null ? 0 : rest.stackSize);
        if (stored <= 0) return;
        cursor.stackSize -= stored;
        player.inventory.setItemStack(cursor.stackSize <= 0 ? null : cursor);
        syncCursor(player);
    }

    private void take(EntityPlayer player, FieldInventory field, boolean containerView, int selectedContainer, int slot,
        ItemStack template, int button, boolean shift) {
        int maxStack = Math.max(1, template.getMaxStackSize());
        int available;
        if (containerView) {
            ItemStack inSlot = selectedContainer < field.containerCount()
                ? field.container(selectedContainer).inventory.getStackInSlot(slot)
                : null;
            if (Stacks.isEmpty(inSlot) || !Stacks.same(inSlot, template)) return;
            available = inSlot.stackSize;
        } else {
            long total = field.countInField(template);
            if (total <= 0) return;
            available = (int) Math.min(total, Integer.MAX_VALUE);
        }
        int whole = Math.min(available, maxStack);
        int amount = shift || button == 0 ? whole : (whole + 1) / 2;
        ItemStack taken = containerView ? field.extractFromSlot(selectedContainer, slot, amount)
            : field.extractSummary(template, amount);
        if (Stacks.isEmpty(taken)) return;

        if (shift) {
            ItemStack rest = field.giveToPlayerOnly(taken);
            if (rest != null) {
                // 背包放不下的部分放回清单，实在放不回去就丢在玩家脚边
                ItemStack back = containerView ? field.insertInto(selectedContainer, rest) : field.insertSummary(rest);
                if (back != null) player.dropPlayerItemWithRandomChoice(back, false);
            }
        } else {
            ItemStack cursor = player.inventory.getItemStack();
            if (Stacks.isEmpty(cursor)) {
                player.inventory.setItemStack(taken);
            } else if (Stacks.same(cursor, taken) && cursor.stackSize + taken.stackSize <= maxStack) {
                cursor.stackSize += taken.stackSize;
            } else {
                ItemStack back = containerView ? field.insertInto(selectedContainer, taken)
                    : field.insertSummary(taken);
                if (back != null) player.dropPlayerItemWithRandomChoice(back, false);
            }
            syncCursor(player);
        }
    }

    private static void syncCursor(EntityPlayer player) {
        if (player instanceof EntityPlayerMP mp) mp.updateHeldItem();
    }

    /**
     * Shift+点击背包物品存入。按当前视图：汇总视图走汇总存入，分容器视图只进选中的容器。
     *
     * @return 放不下的部分；全部放下返回 null
     */
    public ItemStack storeFromBackpack(EntityPlayer player, ItemStack stack) {
        if (!tile.isPowered()) return stack;
        FieldInventory field = tile.createFieldInventory(player);
        forceSend = true;
        if (view == VIEW_CONTAINER) {
            if (selected < 0 || selected >= field.containerCount()) return stack;
            return field.insertInto(selected, stack);
        }
        return field.insertSummary(stack);
    }

    // ------------------------------------------------------------ NEI 填充

    private void handleFill(EntityPlayer player, ItemStack[][] candidates, int multiplier) {
        if (player.openContainer instanceof FieldStationContainer container) {
            container.fillGrid(player, candidates, multiplier);
        }
        forceSend = true;
    }

    /** 清单变化后让下一次 tick 立刻推送。 */
    public void markDirty() {
        forceSend = true;
    }
}
