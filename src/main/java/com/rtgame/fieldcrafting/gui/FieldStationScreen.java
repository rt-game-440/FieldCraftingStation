package com.rtgame.fieldcrafting.gui;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.rtgame.fieldcrafting.FieldCrafting;

import cpw.mods.fml.common.Optional;
import yalter.mousetweaks.api.IMTModGuiContainer;

/**
 * 场域合成站界面，普通的原版 {@link GuiContainer}。左侧是邻近容器的物品清单，随屏幕高度增减行数；
 * 右侧是 3×3 合成网格与玩家背包。
 */
@Optional.Interface(iface = "yalter.mousetweaks.api.IMTModGuiContainer", modid = "MouseTweaks")
public class FieldStationScreen extends GuiContainer implements IMTModGuiContainer {

    private static final int CELL = 18;
    private static final int WIDTH = 354;
    private static final int MIN_HEIGHT = 200;
    private static final int SCREEN_MARGIN = 8;
    private static final int LIST_X = 7;
    /** 搜索框下面就是清单；分容器视图多一行容器选择。 */
    private static final int LIST_Y = 24;
    private static final int SELECTOR_HEIGHT = 18;
    /** 清单下方留给电力指示的高度。 */
    private static final int LIST_BOTTOM = 12;
    private static final int BAR_X = LIST_X + FieldListView.COLUMNS * CELL + 2;
    private static final int BAR_WIDTH = 6;
    private static final int RIGHT = 185;
    private static final int PLAYER_HEIGHT = 76;
    private static final int HOTBAR_GAP = 4;
    private static final int BOTTOM_MARGIN = 6;
    private static final int TITLE_BOTTOM = 16;
    private static final int TWEAK_SIZE = 14;
    private static final int TWEAK_GAP = 3;
    private static final int TWEAK_X = RIGHT + 24;
    private static final int GRID_X = TWEAK_X + 18;
    private static final float POWER_TEXT_SCALE = 0.75f;

    private static final int ID_SUMMARY = 0;
    private static final int ID_CONTAINER = 1;
    private static final int ID_PREV = 2;
    private static final int ID_NEXT = 3;
    private static final int ID_TWEAK = 10;

    private static final String CRAFTING_TWEAKS_PACKAGE = "net.blay09.mods.craftingtweaks.";
    private static final String[] TWEAK_NAMES = { "rotate", "balance", "to_player", "to_field" };

    private final FieldStationContainer container;
    private final FieldListSync sync;
    private final FieldListView view;
    private final String title;
    private GuiTextField search;
    private GuiButton prev;
    private GuiButton next;
    private GuiButton toField;
    private int gridY;
    private boolean draggingBar;
    private boolean readySent;

    public FieldStationScreen(FieldStationContainer container) {
        super(container);
        this.container = container;
        this.sync = container.sync();
        this.view = sync.cells();
        this.xSize = WIDTH;
        this.title = I18n.format(container.titleKey());
    }

    int gridX() {
        return GRID_X + 1;
    }

    int gridY() {
        return gridY + 1;
    }

    // ---------------------------------------------------------------- 布局

    @Override
    public void initGui() {
        ySize = Math.max(MIN_HEIGHT, Math.min(height - SCREEN_MARGIN * 2, maxPanelHeight()));
        super.initGui();

        String text = search == null ? "" : search.getText();
        search = new GuiTextField(fontRendererObj, guiLeft + LIST_X, guiTop + 6, 96, 14);
        search.setMaxStringLength(32);
        search.setText(text);

        buttonList.clear();
        buttonList.add(button(ID_SUMMARY, 108, 5, 32, 16, I18n.format("fieldcrafting.gui.view.summary"), null));
        buttonList.add(button(ID_CONTAINER, 142, 5, 32, 16, I18n.format("fieldcrafting.gui.view.container"), null));
        prev = button(ID_PREV, LIST_X, LIST_Y, 14, 14, "<", null);
        next = button(ID_NEXT, 160, LIST_Y, 14, 14, ">", null);
        buttonList.add(prev);
        buttonList.add(next);

        int playerY = ySize - PLAYER_HEIGHT - BOTTOM_MARGIN;
        gridY = TITLE_BOTTOM + (playerY - TITLE_BOTTOM - 3 * CELL) / 2;
        for (int i = 0; i < TWEAK_NAMES.length; i++) {
            FlatButton tweak = button(
                ID_TWEAK + i,
                TWEAK_X,
                gridY + (3 * CELL - TWEAK_NAMES.length * TWEAK_SIZE - (TWEAK_NAMES.length - 1) * TWEAK_GAP) / 2
                    + i * (TWEAK_SIZE + TWEAK_GAP),
                TWEAK_SIZE,
                TWEAK_SIZE,
                "",
                new ResourceLocation(FieldCrafting.MODID, "textures/gui/tweak_" + TWEAK_NAMES[i] + ".png"));
            tweak.tooltip = I18n.format("fieldcrafting.gui.tweak." + TWEAK_NAMES[i]);
            buttonList.add(tweak);
            if (i == 3) toField = tweak;
        }

        placeFixedSlots(playerY);
        placeCells();

        if (!readySent) {
            readySent = true;
            sync.notifyClientReady();
        }
    }

    private static int maxPanelHeight() {
        return LIST_Y + SELECTOR_HEIGHT + FieldListView.MAX_ROWS * CELL + LIST_BOTTOM;
    }

    private FlatButton button(int id, int x, int y, int w, int h, String text, ResourceLocation icon) {
        return new FlatButton(id, guiLeft + x, guiTop + y, w, h, text, icon);
    }

    private void placeFixedSlots(int playerY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                place(
                    FieldStationContainer.FIRST_GRID_SLOT + row * 3 + col,
                    GRID_X + 1 + col * CELL,
                    gridY + 1 + row * CELL);
            }
        }
        place(FieldStationContainer.RESULT_SLOT, GRID_X + 1 + 3 * CELL + 24, gridY + 1 + CELL);
        for (int i = 0; i < 27; i++) {
            place(
                FieldStationContainer.FIRST_PLAYER_SLOT + i,
                RIGHT + 1 + (i % 9) * CELL,
                playerY + 1 + (i / 9) * CELL);
        }
        for (int i = 0; i < 9; i++) {
            place(
                FieldStationContainer.FIRST_PLAYER_SLOT + 27 + i,
                RIGHT + 1 + i * CELL,
                playerY + 1 + 3 * CELL + HOTBAR_GAP);
        }
    }

    private void place(int index, int x, int y) {
        Slot slot = container.getSlot(index);
        slot.xDisplayPosition = x;
        slot.yDisplayPosition = y;
    }

    private int listTop() {
        return sync.getView() == FieldListSync.VIEW_CONTAINER ? LIST_Y + SELECTOR_HEIGHT : LIST_Y;
    }

    private int visibleRows() {
        return Math.max(1, Math.min((ySize - listTop() - LIST_BOTTOM) / CELL, FieldListView.MAX_ROWS));
    }

    /** 把显示中的清单格子摆好，其余移到屏幕外。 */
    private void placeCells() {
        int rows = visibleRows();
        sync.setVisibleRows(rows);
        int top = listTop();
        for (int i = 0; i < FieldListView.SIZE; i++) {
            int row = i / FieldListView.COLUMNS;
            boolean shown = row < rows;
            place(
                FieldStationContainer.FIRST_CELL_SLOT + i,
                shown ? LIST_X + 1 + (i % FieldListView.COLUMNS) * CELL : FieldListView.HIDDEN_X,
                top + 1 + row * CELL);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        search.updateCursorCounter();
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        boolean containerView = sync.getView() == FieldListSync.VIEW_CONTAINER;
        prev.visible = containerView;
        next.visible = containerView;
        toField.visible = sync.isPowered();
        placeCells();
        // Crafting Tweaks 认出这是工作台，会在网格左边加自己的按钮，和本界面的快捷按钮重叠
        buttonList.removeIf(
            b -> b.getClass()
                .getName()
                .startsWith(CRAFTING_TWEAKS_PACKAGE));
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (Object o : buttonList) {
            if (o instanceof FlatButton b && b.visible && b.tooltip != null && b.func_146115_a()) {
                func_146283_a(Collections.singletonList(b.tooltip), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1f, 1f, 1f, 1f);
        int x = guiLeft;
        int y = guiTop;
        drawRect(x, y, x + xSize, y + ySize, 0xFF000000);
        drawRect(x + 1, y + 1, x + xSize - 1, y + ySize - 1, 0xFFFFFFFF);
        drawRect(x + 2, y + 2, x + xSize - 1, y + ySize - 1, 0xFF555555);
        drawRect(x + 2, y + 2, x + xSize - 2, y + ySize - 2, 0xFFC6C6C6);

        int top = listTop();
        int rows = visibleRows();
        for (int i = 0; i < rows * FieldListView.COLUMNS; i++) {
            drawSlotFrame(
                x + LIST_X + (i % FieldListView.COLUMNS) * CELL,
                y + top + (i / FieldListView.COLUMNS) * CELL);
        }
        for (int i = FieldStationContainer.RESULT_SLOT; i < FieldStationContainer.FIRST_CELL_SLOT; i++) {
            Slot slot = container.getSlot(i);
            drawSlotFrame(x + slot.xDisplayPosition - 1, y + slot.yDisplayPosition - 1);
        }

        // 滚动条
        int barHeight = rows * CELL;
        drawRect(x + BAR_X, y + top, x + BAR_X + BAR_WIDTH, y + top + barHeight, 0xFF373737);
        int max = view.maxScroll();
        int thumb = max == 0 ? barHeight : Math.max(8, barHeight * rows / view.getTotalRows());
        int thumbY = max == 0 ? 0 : (barHeight - thumb) * view.getScrollRow() / max;
        drawRect(
            x + BAR_X + 1,
            y + top + thumbY + 1,
            x + BAR_X + BAR_WIDTH - 1,
            y + top + thumbY + thumb - 1,
            0xFFC6C6C6);

        search.drawTextBox();
    }

    private void drawSlotFrame(int x, int y) {
        drawRect(x, y, x + CELL, y + CELL, 0xFFFFFFFF);
        drawRect(x, y, x + CELL - 1, y + CELL - 1, 0xFF373737);
        drawRect(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF8B8B8B);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(title, RIGHT, 6, 0x404040);
        if (sync.getView() == FieldListSync.VIEW_CONTAINER) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(containerLabel(), 132),
                LIST_X + 18,
                LIST_Y + 3,
                0x404040);
        }

        int top = listTop();
        int gridWidth = FieldListView.COLUMNS * CELL;
        if (!view.isPowered()) {
            int gridHeight = visibleRows() * CELL;
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            drawRect(LIST_X, top, LIST_X + gridWidth, top + gridHeight, 0xB0404040);
            String text = I18n.format("fieldcrafting.gui.unpowered");
            fontRendererObj.drawStringWithShadow(
                text,
                LIST_X + (gridWidth - fontRendererObj.getStringWidth(text)) / 2,
                top + gridHeight / 2 - 4,
                0xFF5555);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else if (view.getTotalRows() == 0) {
            fontRendererObj.drawString(I18n.format("fieldcrafting.gui.empty"), LIST_X + 6, top + 6, 0x404040);
        }

        GL11.glPushMatrix();
        GL11.glTranslatef(LIST_X, ySize - 9, 0f);
        GL11.glScalef(POWER_TEXT_SCALE, POWER_TEXT_SCALE, 1f);
        fontRendererObj.drawString(powerText(), 0, 0, 0xFFFFFF);
        GL11.glPopMatrix();
    }

    private String containerLabel() {
        List<FieldListSync.ContainerInfo> containers = sync.getContainers();
        if (containers.isEmpty()) return I18n.format("fieldcrafting.gui.no_container");
        int i = Math.max(0, Math.min(sync.getSelected(), containers.size() - 1));
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

    private String powerText() {
        return sync.isPowered() ? EnumChatFormatting.DARK_GREEN + I18n.format("fieldcrafting.gui.powered")
            : EnumChatFormatting.RED + I18n.format("fieldcrafting.gui.unpowered");
    }

    // ---------------------------------------------------------------- 输入

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_SUMMARY -> sync.setView(FieldListSync.VIEW_SUMMARY, 0);
            case ID_CONTAINER -> sync.setView(FieldListSync.VIEW_CONTAINER, 0);
            case ID_PREV -> cycle(-1);
            case ID_NEXT -> cycle(1);
            case ID_TWEAK -> sync.sendGridAction(FieldStationContainer.GRID_ROTATE);
            case ID_TWEAK + 1 -> sync.sendGridAction(
                GuiScreen.isShiftKeyDown() ? FieldStationContainer.GRID_SPREAD : FieldStationContainer.GRID_BALANCE);
            case ID_TWEAK + 2 -> sync.sendGridAction(FieldStationContainer.GRID_TO_PLAYER);
            case ID_TWEAK + 3 -> sync.sendGridAction(FieldStationContainer.GRID_TO_FIELD);
            default -> {}
        }
    }

    private void cycle(int delta) {
        int n = sync.getContainers()
            .size();
        if (n <= 0) return;
        sync.setView(FieldListSync.VIEW_CONTAINER, ((sync.getSelected() + delta) % n + n) % n);
    }

    /** 搜索框有焦点时按键全归它，每输入一个字立刻过滤。 */
    @Override
    protected void keyTyped(char typed, int keyCode) {
        if (search.isFocused() && keyCode != 1) {
            search.textboxKeyTyped(typed, keyCode);
            sync.setFilter(search.getText());
            return;
        }
        super.keyTyped(typed, keyCode);
    }

    private boolean overBar(int mouseX, int mouseY) {
        int x = mouseX - guiLeft;
        int y = mouseY - guiTop - listTop();
        return x >= BAR_X && x < BAR_X + BAR_WIDTH && y >= 0 && y < visibleRows() * CELL;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        search.mouseClicked(mouseX, mouseY, button);
        if (button == 0 && overBar(mouseX, mouseY)) {
            draggingBar = true;
            dragBarTo(mouseY);
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long sinceClick) {
        if (draggingBar) {
            dragBarTo(mouseY);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, sinceClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button >= 0 && draggingBar) {
            draggingBar = false;
            return;
        }
        super.mouseMovedOrUp(mouseX, mouseY, button);
    }

    private void dragBarTo(int mouseY) {
        int max = view.maxScroll();
        if (max == 0) return;
        float ratio = (mouseY - guiTop - listTop()) / (float) (visibleRows() * CELL);
        sync.setScroll(Math.round(max * ratio));
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth - guiLeft;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1 - guiTop - listTop();
        boolean overList = mouseX >= LIST_X && mouseX < BAR_X + BAR_WIDTH
            && mouseY >= 0
            && mouseY < visibleRows() * CELL;
        if (overList) sync.setScroll(view.getScrollRow() + (wheel > 0 ? -1 : 1));
    }

    // ---------------------------------------------------------------- MouseTweaks：滚轮归清单，其余功能照常

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public int getAPIVersion() {
        return 1;
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public String getModName() {
        return FieldCrafting.MODID;
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public boolean isMouseTweaksDisabled() {
        return false;
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public boolean isWheelTweakDisabled() {
        return true;
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public boolean isCraftingOutputSlot(Object modContainer, Object slot) {
        return slot instanceof SlotCrafting;
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public Object getModContainer() {
        return container;
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public int getModSlotCount(Object modContainer) {
        return container.inventorySlots.size();
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public Object getModSlot(Object modContainer, int index) {
        return container.getSlot(index);
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public Object getModSelectedSlot(Object modContainer, int slotCount) {
        int mouseX = Mouse.getX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getY() * height / mc.displayHeight - 1;
        for (int i = 0; i < slotCount; i++) {
            Slot slot = container.getSlot(i);
            if (func_146978_c(slot.xDisplayPosition, slot.yDisplayPosition, 16, 16, mouseX, mouseY)) return slot;
        }
        return null;
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public void clickModSlot(Object modContainer, Object slot, int button, boolean shift) {
        mc.playerController
            .windowClick(container.windowId, ((Slot) slot).slotNumber, button, shift ? 1 : 0, mc.thePlayer);
    }

    @Override
    @Optional.Method(modid = "MouseTweaks")
    public void disableRMBDragIfRequired(Object modContainer, Object firstSlot, boolean shouldClick) {}

    // ---------------------------------------------------------------- 按钮

    /** 面板风格的扁平按钮，可带图标。 */
    private static final class FlatButton extends GuiButton {

        private final ResourceLocation icon;
        String tooltip;

        FlatButton(int id, int x, int y, int w, int h, String text, ResourceLocation icon) {
            super(id, x, y, w, h, text);
            this.icon = icon;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            field_146123_n = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0xFF000000);
            drawRect(
                xPosition + 1,
                yPosition + 1,
                xPosition + width - 1,
                yPosition + height - 1,
                field_146123_n ? 0xFF8B9BB4 : 0xFF6F6F6F);
            if (icon != null) {
                GL11.glColor4f(1f, 1f, 1f, 1f);
                GL11.glEnable(GL11.GL_BLEND);
                mc.getTextureManager()
                    .bindTexture(icon);
                func_146110_a(xPosition, yPosition, 0, 0, width, height, width, height);
                GL11.glDisable(GL11.GL_BLEND);
            }
            if (!displayString.isEmpty()) {
                drawCenteredString(
                    mc.fontRenderer,
                    displayString,
                    xPosition + width / 2,
                    yPosition + (height - 8) / 2,
                    0xFFFFFF);
            }
        }
    }
}
