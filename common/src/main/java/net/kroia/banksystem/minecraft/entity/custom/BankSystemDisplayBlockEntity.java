package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.data.filter.EqualityFilter;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.screen.widgets.BalanceHistoryChart;
import net.kroia.banksystem.util.ItemColorUtil;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.display.AbstractDisplayBlockEntity;
import net.kroia.modutilities.gui.display.ContentBuilder;
import net.kroia.modutilities.gui.display.DisplayConfig;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class BankSystemDisplayBlockEntity extends AbstractDisplayBlockEntity {

    public enum DisplayType {
        NONE("none", "Select Display"),
        BALANCE_OVERVIEW("balance_overview", "Balance Overview"),
        BALANCE_HISTORY("balance_history", "Balance History");

        private final String id;
        private final String displayName;

        DisplayType(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }

        public static DisplayType fromId(String id) {
            for (DisplayType type : values()) {
                if (type.id.equals(id)) return type;
            }
            return NONE;
        }
    }

    // ── Display config ──

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            256, 256, 2, 4096,
            -14.0f / 16.0f + 0.001f,
            facing -> switch (facing) {
                case NORTH -> Block.box(0, 0, 14, 16, 16, 16);
                case SOUTH -> Block.box(0, 0, 0, 16, 16, 2);
                case EAST  -> Block.box(0, 0, 0, 2, 16, 16);
                case WEST  -> Block.box(14, 0, 0, 16, 16, 16);
                default -> Block.box(0, 0, 14, 16, 16, 16);
            },
            1, 0
    );

    // ── Balance overview constants ──

    private static final int COLS_PER_DISPLAY = 2;
    private static final int DISPLAY_UNIT = 256;
    private static final int OV_ROW_HEIGHT = 20;
    private static final int OV_MARGIN = 8;
    private static final int OV_FRAME_PAD = 6;
    private static final int OV_HEADER_HEIGHT = 36;
    private static final int OV_FRAME_BG = ColorUtilities.getRGB(20, 20, 30, 180);
    private static final int OV_FRAME_OUTLINE = ColorUtilities.getRGB(80, 100, 140);
    private static final int OV_LABEL_COLOR = ColorUtilities.getRGB(220, 220, 220);

    // ── Balance history constants ──

    private static final int MAX_LEGEND_ITEMS = 15;
    private static final int LEGEND_ENTRY_SIZE = 20;
    private static final int LEGEND_WIDTH = 24;
    private static final int HIST_MARGIN = 6;

    // ── Backend ──

    private static BankSystemModBackend.Instances BACKEND;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND = backend;
    }

    // ── Configuration state ──

    private DisplayType displayType = DisplayType.NONE;
    private int accountNumber = 0;
    private int tickCounter = 0;
    private boolean pendingRebuild = false;

    // ── Balance overview state ──

    private int slotCount = 0;
    private boolean overviewDataPending = false;
    private volatile BankAccountData pendingOverviewData = null;
    private Label overviewAccountLabel;
    private final List<ItemView> overviewIcons = new ArrayList<>();
    private final List<Label> overviewLabels = new ArrayList<>();
    private String pendingOverviewAccountText = null;
    private ListTag pendingOverviewSlotData = null;

    // ── Balance history state ──

    private BalanceHistoryChart historyChart;
    private Label historyStatusLabel;
    private final List<Frame> legendFrames = new ArrayList<>();
    private final List<ItemView> legendIcons = new ArrayList<>();
    private boolean historyDataPending = false;
    private volatile List<BalanceHistoryRecord> pendingHistoryRecords = null;
    private CompoundTag pendingChartState = null;
    private String pendingStatusText = null;
    private ListTag pendingLegendData = null;
    private int[] legendOutlineColors = new int[0];
    private int[] legendBgColors = new int[0];
    private boolean[] legendVisible = new boolean[0];

    public BankSystemDisplayBlockEntity(BlockPos pos, BlockState blockState) {
        super(BankSystemEntities.BANKSYSTEM_DISPLAY_BLOCK_ENTITY.get(), pos, blockState);
    }

    // ── Public API ──

    public DisplayType getDisplayType() { return displayType; }
    public int getAccountNumber() { return accountNumber; }

    public void adoptConfig(DisplayType type, int account) {
        this.displayType = type;
        this.accountNumber = account;
        setChanged();
    }

    public void setConfig(DisplayType type, int account) {
        DisplayType oldType = this.displayType;
        this.displayType = type;
        this.accountNumber = account;
        this.tickCounter = 0;

        if (level != null && !level.isClientSide()) {
            propagateConfigToGroup();
            if (oldType != type) {
                net.minecraft.core.Direction facing = getBlockState().getValue(HorizontalDirectionalBlock.FACING);
                AbstractDisplayBlockEntity.recalculateGroups(level, getBlockPos(), facing);
            }
            rebuildGui();
        }
    }

    private void propagateConfigToGroup() {
        if (level == null || !isController()) return;
        BlockPos myPos = getBlockPos();
        int radius = getGroupWidth() + getGroupHeight();
        for (BlockPos checkPos : BlockPos.betweenClosed(
                myPos.offset(-radius, -radius, -radius),
                myPos.offset(radius, radius, radius))) {
            if (checkPos.equals(myPos)) continue;
            BlockEntity be = level.getBlockEntity(checkPos);
            if (be instanceof BankSystemDisplayBlockEntity other) {
                BlockPos otherCtrl = other.getControllerPos();
                if (myPos.equals(otherCtrl)) {
                    other.displayType = this.displayType;
                    other.accountNumber = this.accountNumber;
                    other.setChanged();
                }
            }
        }
    }

    // ── AbstractDisplayBlockEntity overrides ──

    @Override
    public DisplayConfig getDisplayConfig() {
        return DISPLAY_CONFIG;
    }

    @Override
    public ContentBuilder getContentBuilder() {
        return switch (displayType) {
            case NONE -> BankSystemDisplayBlockEntity::buildUnconfiguredUI;
            case BALANCE_OVERVIEW -> {
                int count = queryItemCount();
                if (count > 0) slotCount = count;
                final int c = slotCount;
                yield (gui, w, h) -> buildBalanceOverview(gui, w, h, c);
            }
            case BALANCE_HISTORY -> {
                final int[] outColors = legendOutlineColors;
                final int[] bgColors = legendBgColors;
                final boolean[] vis = legendVisible;
                yield (gui, w, h) -> buildBalanceHistory(gui, w, h, outColors, bgColors, vis);
            }
        };
    }

    @Override
    public String getChannelId() {
        return switch (displayType) {
            case NONE -> "10";
            case BALANCE_HISTORY -> "11";
            case BALANCE_OVERVIEW -> "12";
        };
    }

    @Override
    public boolean opensSyncedScreenOnUse() {
        return displayType != DisplayType.NONE && accountNumber > 0;
    }

    @Override
    protected void wireCallbacks(Gui gui) {
        clearGuiReferences();
        switch (displayType) {
            case BALANCE_OVERVIEW -> wireOverviewCallbacks(gui);
            case BALANCE_HISTORY -> wireHistoryCallbacks(gui);
            default -> {}
        }
        applyPendingState();
    }

    @Override
    protected void onControllerTick() {
        if (pendingRebuild) {
            pendingRebuild = false;
            tickCounter = 0;
            rebuildGui();
            return;
        }

        tickCounter++;

        if (displayType == DisplayType.BALANCE_OVERVIEW && accountNumber != 0) {
            BankAccountData ovData = pendingOverviewData;
            if (ovData != null) {
                pendingOverviewData = null;
                overviewDataPending = false;
                applyOverviewData(ovData);
            }
            if (tickCounter % 20 == 0 || tickCounter == 1) {
                requestOverviewData();
            }
        } else if (displayType == DisplayType.BALANCE_HISTORY && accountNumber != 0) {
            List<BalanceHistoryRecord> records = pendingHistoryRecords;
            if (records != null) {
                pendingHistoryRecords = null;
                historyDataPending = false;
                applyHistoryData(records);
            }
            if (tickCounter % 1200 == 0 || tickCounter == 1) {
                requestHistoryData();
            }
        }
    }

    @Override
    protected void saveCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("displayType", displayType.getId());
        tag.putInt("accountNumber", accountNumber);

        if (displayType == DisplayType.BALANCE_OVERVIEW) {
            saveOverviewData(tag);
        } else if (displayType == DisplayType.BALANCE_HISTORY) {
            saveHistoryData(tag);
        }
    }

    @Override
    protected void loadCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("displayType")) {
            displayType = DisplayType.fromId(tag.getString("displayType"));
        }
        if (tag.contains("accountNumber")) {
            accountNumber = tag.getInt("accountNumber");
        }
        loadOverviewData(tag);
        loadHistoryData(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        DisplayType oldType = this.displayType;
        int oldAccount = this.accountNumber;
        super.loadAdditional(tag, registries);

        if (level != null && level.isClientSide() && isController()) {
            if (oldType != this.displayType || oldAccount != this.accountNumber) {
                rebuildGui();
            }
            if (gui != null) {
                wireCallbacks(gui);
                applyPendingState();
            }
        }
    }

    @Override
    public void onInputSynced() {}

    // ── Internal helpers ──

    private void clearGuiReferences() {
        overviewAccountLabel = null;
        overviewIcons.clear();
        overviewLabels.clear();
        overviewDataPending = false;
        historyChart = null;
        historyStatusLabel = null;
        legendFrames.clear();
        legendIcons.clear();
        historyDataPending = false;
    }

    private void applyPendingState() {
        // Overview pending state
        if (pendingOverviewAccountText != null && overviewAccountLabel != null) {
            overviewAccountLabel.setText(pendingOverviewAccountText);
            pendingOverviewAccountText = null;
        }
        if (pendingOverviewSlotData != null) {
            int count = Math.min(pendingOverviewSlotData.size(), Math.min(overviewIcons.size(), overviewLabels.size()));
            for (int i = 0; i < count; i++) {
                CompoundTag entry = pendingOverviewSlotData.getCompound(i);
                if (entry.contains("item")) {
                    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getString("item")));
                    if (item != Items.AIR) {
                        overviewIcons.get(i).setItemStack(new ItemStack(item));
                    }
                }
                if (entry.contains("text")) {
                    overviewLabels.get(i).setText(entry.getString("text"));
                }
            }
            pendingOverviewSlotData = null;
        }

        // History pending state
        if (pendingChartState != null && historyChart != null) {
            historyChart.deserializeState(pendingChartState);
            pendingChartState = null;
        }
        if (pendingStatusText != null && historyStatusLabel != null) {
            historyStatusLabel.setText(pendingStatusText);
            pendingStatusText = null;
        }
        if (pendingLegendData != null) {
            int count = Math.min(pendingLegendData.size(), Math.min(legendFrames.size(), legendIcons.size()));
            for (int i = 0; i < count; i++) {
                CompoundTag entry = pendingLegendData.getCompound(i);
                Frame f = legendFrames.get(i);
                f.setOutlineColor(entry.getInt("color"));
                f.setBackgroundColor(entry.getInt("bgColor"));
                f.setEnableOutline(entry.getBoolean("visible"));
                f.setEnableBackground(entry.getBoolean("visible"));
                if (entry.contains("item")) {
                    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getString("item")));
                    if (item != Items.AIR) {
                        legendIcons.get(i).setItemStack(new ItemStack(item));
                    }
                }
            }
            for (int i = count; i < legendFrames.size(); i++) {
                legendFrames.get(i).setEnableOutline(false);
                legendFrames.get(i).setEnableBackground(false);
            }
            for (int i = count; i < legendIcons.size(); i++) {
                legendIcons.get(i).setItemStack(null);
            }
            pendingLegendData = null;
        }

        if (level != null && level.isClientSide() && historyChart != null) {
            recalculateClientColors();
        }
    }

    private void recalculateClientColors() {
        List<BalanceHistoryChart.LineSeries> series = historyChart.getSeries();
        int count = Math.min(series.size(), legendIcons.size());
        for (int i = 0; i < count; i++) {
            ItemStack stack = legendIcons.get(i).getItemStack();
            if (stack == null || stack.isEmpty()) continue;
            ItemID itemId = ItemID.getFromItemStack(stack);
            if (itemId == null) continue;
            int color = ItemColorUtil.getColor(itemId.getShort(), stack, i);
            series.get(i).color = color;
            int bgColor = ColorUtilities.getRGB(
                    (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, 40);
            if (i < legendFrames.size()) {
                legendFrames.get(i).setOutlineColor(color);
                legendFrames.get(i).setBackgroundColor(bgColor);
            }
            if (i < legendOutlineColors.length) {
                legendOutlineColors[i] = color;
            }
            if (i < legendBgColors.length) {
                legendBgColors[i] = bgColor;
            }
        }
    }

    private static int parseIndex(String id) {
        int u = id.lastIndexOf('_');
        if (u < 0) return 0;
        try { return Integer.parseInt(id.substring(u + 1)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String getItemDisplayName(short itemId) {
        ItemID id = new ItemID(itemId);
        String name = id.getName();
        return name != null ? name : "Item#" + itemId;
    }

    // =========================================================================
    // Unconfigured UI
    // =========================================================================

    private static void buildUnconfiguredUI(Gui gui, int w, int h) {
        Label title = new Label("Bank Display");
        title.setBounds(0, h / 2 - 16, w, 14);
        title.setAlignment(GuiElement.Alignment.CENTER);
        title.setTextColor(ColorUtilities.getRGB(200, 220, 255));
        gui.addElement(title);

        Label hint = new Label("Right-click to configure");
        hint.setBounds(0, h / 2 + 2, w, 12);
        hint.setAlignment(GuiElement.Alignment.CENTER);
        hint.setTextColor(ColorUtilities.getRGB(140, 140, 160));
        gui.addElement(hint);
    }

    // =========================================================================
    // Balance Overview
    // =========================================================================

    private void wireOverviewCallbacks(Gui gui) {
        for (var el : gui.getElements()) {
            if (el instanceof Label l) {
                String id = l.getId();
                if ("account_label".equals(id)) {
                    overviewAccountLabel = l;
                } else if (id != null && id.startsWith("bal_")) {
                    overviewLabels.add(l);
                }
            } else if (el instanceof ItemView iv) {
                String id = iv.getId();
                if (id != null && id.startsWith("icon_")) {
                    overviewIcons.add(iv);
                }
            }
        }
        overviewIcons.sort(Comparator.comparingInt(a -> parseIndex(a.getId())));
        overviewLabels.sort(Comparator.comparingInt(a -> parseIndex(a.getId())));
        if (level == null || !level.isClientSide()) {
            requestOverviewData();
        }
    }

    private int queryItemCount() {
        if (BACKEND == null || BACKEND.SERVER_BANK_MANAGER == null) return 0;
        ISyncServerBankManager mgr = BACKEND.SERVER_BANK_MANAGER.getSync();
        if (mgr != null) {
            var account = mgr.getBankAccount(accountNumber);
            if (account != null) {
                int maxSlots = calculateMaxSlots();
                return Math.min(account.getAccountData().bankData.size(), maxSlots);
            }
        }
        return slotCount;
    }

    private int calculateMaxSlots() {
        int w = Math.max(DISPLAY_UNIT, getGroupWidth() * getDisplayConfig().virtualWidth());
        int h = Math.max(DISPLAY_UNIT, getGroupHeight() * getDisplayConfig().virtualHeight());
        int totalCols = COLS_PER_DISPLAY * Math.max(1, w / DISPLAY_UNIT);
        int availableH = h - OV_MARGIN - OV_HEADER_HEIGHT - OV_MARGIN;
        int maxRows = Math.max(0, (availableH - OV_FRAME_PAD * 2) / OV_ROW_HEIGHT);
        return totalCols * maxRows;
    }

    private void requestOverviewData() {
        if (BACKEND == null || BACKEND.SERVER_BANK_MANAGER == null) return;
        if (overviewDataPending) return;
        overviewDataPending = true;

        BACKEND.SERVER_BANK_MANAGER.getAsync().getBankAccountDataAsync(accountNumber)
                .thenAccept(data -> {
                    pendingOverviewData = data;
                });
    }

    private void applyOverviewData(BankAccountData data) {
        if (data == null) {
            if (slotCount != 0) {
                slotCount = 0;
                pendingRebuild = true;
                return;
            }
            String notFoundText = "Account #" + accountNumber + " (not found)";
            if (overviewAccountLabel != null && !notFoundText.equals(overviewAccountLabel.getText())) {
                overviewAccountLabel.setText(notFoundText);
                syncToClientPublic();
            }
            return;
        }

        List<Map.Entry<ItemID, BankData>> sorted = new ArrayList<>(data.bankData.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().balance(), a.getValue().balance()));

        int maxSlots = calculateMaxSlots();
        int needed = Math.min(sorted.size(), maxSlots);
        if (needed != slotCount) {
            slotCount = needed;
            pendingRebuild = true;
            return;
        }

        boolean changed = false;

        if (overviewAccountLabel != null && !data.accountName.equals(overviewAccountLabel.getText())) {
            overviewAccountLabel.setText(data.accountName);
            changed = true;
        }

        for (int i = 0; i < needed; i++) {
            Map.Entry<ItemID, BankData> entry = sorted.get(i);
            ItemID itemId = entry.getKey();
            BankData bankData = entry.getValue();

            if (i < overviewIcons.size()) {
                ItemStack current = overviewIcons.get(i).getItemStack();
                ItemStack next = itemId.getStack();
                if (current == null || !ItemStack.isSameItem(current, next)) {
                    overviewIcons.get(i).setItemStack(next);
                    changed = true;
                }
            }
            if (i < overviewLabels.size()) {
                String newText = formatCompact(bankData.balance());
                if (!newText.equals(overviewLabels.get(i).getText())) {
                    overviewLabels.get(i).setText(newText);
                    changed = true;
                }
            }
        }
        if (changed) {
            syncToClientPublic();
        }
    }

    private void saveOverviewData(CompoundTag tag) {
        tag.putInt("slotCount", slotCount);
        if (overviewAccountLabel != null) {
            tag.putString("ovAccountText", overviewAccountLabel.getText());
        }
        ListTag slots = new ListTag();
        int count = Math.min(overviewIcons.size(), overviewLabels.size());
        for (int i = 0; i < count; i++) {
            CompoundTag entry = new CompoundTag();
            ItemStack stack = overviewIcons.get(i).getItemStack();
            if (stack != null && !stack.isEmpty()) {
                entry.putString("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            entry.putString("text", overviewLabels.get(i).getText());
            slots.add(entry);
        }
        tag.put("ovSlots", slots);
    }

    private void loadOverviewData(CompoundTag tag) {
        if (tag.contains("slotCount")) {
            slotCount = tag.getInt("slotCount");
        }
        pendingOverviewAccountText = tag.contains("ovAccountText") ? tag.getString("ovAccountText") : null;
        pendingOverviewSlotData = tag.contains("ovSlots") ? tag.getList("ovSlots", 10) : null;
    }

    static String formatCompact(long rawAmount) {
        double value = rawAmount / (double) BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        if (value <= 0) return "0";

        String[] suffixes = {"", "k", "M", "G", "T", "P"};
        int idx = 0;
        double d = value;
        while (d >= 1000 && idx < suffixes.length - 1) {
            d /= 1000;
            idx++;
        }

        String result;
        if (idx == 0) {
            if (d == Math.floor(d)) {
                result = String.valueOf((long) d);
            } else {
                result = String.format("%.2f", d);
                result = result.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
        } else {
            if (d >= 100) result = String.format("%.0f", d);
            else if (d >= 10) result = String.format("%.1f", d);
            else result = String.format("%.2f", d);
            if (result.contains(".")) {
                result = result.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            result += suffixes[idx];
        }
        return result;
    }

    private static void buildBalanceOverview(Gui gui, int w, int h, int itemCount) {
        int iconSize = 16;
        int iconLabelGap = 3;
        int y = OV_MARGIN;

        Label title = new Label("Balance Overview");
        title.setBounds(0, y, w, 14);
        title.setAlignment(GuiElement.Alignment.CENTER);
        title.setTextColor(ColorUtilities.getRGB(200, 220, 255));
        gui.addElement(title);
        y += 18;

        Label accountLabel = new Label("loading...");
        accountLabel.setId("account_label");
        accountLabel.setBounds(0, y, w, 12);
        accountLabel.setAlignment(GuiElement.Alignment.CENTER);
        accountLabel.setTextColor(ColorUtilities.getRGB(170, 170, 190));
        gui.addElement(accountLabel);
        y += 18;

        if (itemCount <= 0) return;

        int displaysWide = Math.max(1, w / DISPLAY_UNIT);
        int totalCols = COLS_PER_DISPLAY * displaysWide;
        int rows = (int) Math.ceil((double) itemCount / totalCols);
        int frameH = rows * OV_ROW_HEIGHT + OV_FRAME_PAD * 2;

        int gridWidth = displaysWide * DISPLAY_UNIT;
        int offsetX = (w - gridWidth) / 2;

        Frame frame = new Frame();
        frame.setBounds(offsetX, y, gridWidth, frameH);
        frame.setEnableBackground(true);
        frame.setBackgroundColor(OV_FRAME_BG);
        frame.setEnableOutline(true);
        frame.setOutlineColor(OV_FRAME_OUTLINE);
        gui.addElement(frame);

        int cellContentW = iconSize + iconLabelGap + 50;
        int slotW = DISPLAY_UNIT / COLS_PER_DISPLAY;
        int slotOffset = (slotW - cellContentW) / 2;

        for (int i = 0; i < itemCount; i++) {
            int col = i % totalCols;
            int row = i / totalCols;

            int displayIdx = col / COLS_PER_DISPLAY;
            int colInDisplay = col % COLS_PER_DISPLAY;

            int cellX = offsetX + displayIdx * DISPLAY_UNIT + colInDisplay * slotW + slotOffset;
            int cellY = y + OV_FRAME_PAD + row * OV_ROW_HEIGHT;

            ItemView icon = new ItemView(cellX, cellY + 2, iconSize, iconSize);
            icon.setId("icon_" + i);
            icon.setShowTooltip(true);
            gui.addElement(icon);

            Label label = new Label("");
            label.setId("bal_" + i);
            label.setBounds(cellX + iconSize + iconLabelGap, cellY, slotW - iconSize - iconLabelGap - slotOffset, OV_ROW_HEIGHT);
            label.setAlignment(GuiElement.Alignment.LEFT);
            label.setTextColor(OV_LABEL_COLOR);
            gui.addElement(label);
        }
    }

    // =========================================================================
    // Balance History
    // =========================================================================

    private void wireHistoryCallbacks(Gui gui) {
        for (var el : gui.getElements()) {
            if (el instanceof BalanceHistoryChart c) historyChart = c;
            if (el instanceof Label l && l.getId() != null && l.getId().equals("status"))
                historyStatusLabel = l;
            if (el instanceof Frame f && f.getId() != null && f.getId().startsWith("lf_"))
                legendFrames.add(f);
            if (el instanceof ItemView iv && iv.getId() != null && iv.getId().startsWith("li_"))
                legendIcons.add(iv);
        }
        legendFrames.sort(Comparator.comparingInt(a -> parseIndex(a.getId())));
        legendIcons.sort(Comparator.comparingInt(a -> parseIndex(a.getId())));
        if (level == null || !level.isClientSide()) {
            requestHistoryData();
        }
    }

    private void requestHistoryData() {
        if (BACKEND == null || BACKEND.BALANCE_HISTORY_MANAGER == null) return;
        if (historyDataPending) return;
        historyDataPending = true;

        BalanceHistoryManager mgr = BACKEND.BALANCE_HISTORY_MANAGER;
        mgr.getHistory(
                Optional.empty(),
                Optional.of(new EqualityFilter(accountNumber)),
                Optional.empty(),
                0
        ).thenAccept(records -> {
            pendingHistoryRecords = (records != null) ? records : List.of();
        });
    }

    private void applyHistoryData(List<BalanceHistoryRecord> records) {
        if (historyChart == null) return;

        if (records.isEmpty()) {
            historyChart.clearSeries();
            clearLegend(0);
            if (historyStatusLabel != null) historyStatusLabel.setText("Status: no data");
            syncToClientPublic();
            return;
        }

        Map<Short, List<BalanceHistoryRecord>> grouped = new LinkedHashMap<>();
        for (BalanceHistoryRecord r : records) {
            if (r.itemId() == BalanceHistoryRecord.WEALTH_ITEM_ID) continue;
            grouped.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r);
        }

        List<Map.Entry<Short, List<BalanceHistoryRecord>>> sortedItems = new ArrayList<>(grouped.entrySet());
        sortedItems.sort((a, b) -> {
            long balA = a.getValue().isEmpty() ? 0 : a.getValue().get(a.getValue().size() - 1).balance();
            long balB = b.getValue().isEmpty() ? 0 : b.getValue().get(b.getValue().size() - 1).balance();
            return Long.compare(balB, balA);
        });

        boolean firstLoad = historyChart.getSeries().isEmpty();
        historyChart.clearSeries();
        historyChart.clearHoverBindings();
        double scale = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        legendOutlineColors = new int[sortedItems.size()];
        legendBgColors = new int[sortedItems.size()];
        legendVisible = new boolean[sortedItems.size()];

        int ci = 0;
        for (var entry : sortedItems) {
            short itemId = entry.getKey();
            List<BalanceHistoryRecord> itemRecords = entry.getValue();
            int color = ItemColorUtil.getColor(itemId, ci);
            String name = getItemDisplayName(itemId);

            BalanceHistoryChart.LineSeries s = new BalanceHistoryChart.LineSeries(name, color);
            for (BalanceHistoryRecord r : itemRecords) {
                s.points.add(new BalanceHistoryChart.DataPoint(r.time(),
                        (r.balance() + r.lockedBalance()) / scale));
            }
            historyChart.addSeries(s);

            int bgColor = ColorUtilities.getRGB(
                    (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, 40);
            legendOutlineColors[ci] = color;
            legendBgColors[ci] = bgColor;
            legendVisible[ci] = true;

            if (ci < legendFrames.size()) {
                Frame f = legendFrames.get(ci);
                f.setOutlineColor(color);
                f.setBackgroundColor(bgColor);
                f.setEnableOutline(true);
                f.setEnableBackground(true);
                historyChart.bindHoverElement(f, s);
            }
            if (ci < legendIcons.size()) {
                ItemID id = new ItemID(itemId);
                legendIcons.get(ci).setItemStack(id.getStack());
                historyChart.bindHoverElement(legendIcons.get(ci), s);
            }
            ci++;
        }

        clearLegend(ci);

        if (firstLoad) {
            historyChart.autoCenterView();
        }
        historyChart.scrollToPresent();

        if (historyStatusLabel != null) {
            historyStatusLabel.setText("Status: " + sortedItems.size() + " items");
        }

        syncToClientPublic();
    }

    private void clearLegend(int fromIndex) {
        for (int i = fromIndex; i < legendFrames.size(); i++) {
            legendFrames.get(i).setEnableOutline(false);
            legendFrames.get(i).setEnableBackground(false);
        }
        for (int i = fromIndex; i < legendIcons.size(); i++) {
            legendIcons.get(i).setItemStack(null);
        }
    }

    private void saveHistoryData(CompoundTag tag) {
        if (historyChart != null && !historyChart.getSeries().isEmpty()) {
            tag.put("chartState", historyChart.serializeState());
        }
        if (historyStatusLabel != null) {
            tag.putString("statusText", historyStatusLabel.getText());
        }
        ListTag legend = new ListTag();
        int count = Math.min(legendFrames.size(), legendIcons.size());
        for (int i = 0; i < count; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("color", legendFrames.get(i).getOutlineColor());
            entry.putInt("bgColor", legendFrames.get(i).getBackgroundColor());
            entry.putBoolean("visible", legendFrames.get(i).isOutlineEnabled());
            ItemStack stack = legendIcons.get(i).getItemStack();
            if (stack != null && !stack.isEmpty()) {
                entry.putString("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            legend.add(entry);
        }
        tag.put("legend", legend);
    }

    private void loadHistoryData(CompoundTag tag) {
        pendingChartState = tag.contains("chartState") ? tag.getCompound("chartState") : null;
        pendingStatusText = tag.contains("statusText") ? tag.getString("statusText") : null;
        pendingLegendData = tag.contains("legend") ? tag.getList("legend", 10) : null;
        if (pendingLegendData != null) {
            int n = pendingLegendData.size();
            legendOutlineColors = new int[n];
            legendBgColors = new int[n];
            legendVisible = new boolean[n];
            for (int i = 0; i < n; i++) {
                CompoundTag entry = pendingLegendData.getCompound(i);
                legendOutlineColors[i] = entry.getInt("color");
                legendBgColors[i] = entry.getInt("bgColor");
                legendVisible[i] = entry.getBoolean("visible");
            }
        }
    }

    private static void buildBalanceHistory(Gui gui, int w, int h,
                                               int[] outColors, int[] bgColors, boolean[] vis) {
        int y = HIST_MARGIN;
        int legendX = w - HIST_MARGIN - LEGEND_WIDTH;
        int chartWidth = legendX - HIST_MARGIN * 2;

        Label title = new Label("Balance History");
        title.setBounds(0, y, w, 14);
        title.setAlignment(GuiElement.Alignment.CENTER);
        title.setTextColor(ColorUtilities.getRGB(200, 220, 255));
        gui.addElement(title);
        y += 18;

        Label status = new Label("Status: loading...");
        status.setId("status");
        status.setBounds(HIST_MARGIN, y, chartWidth, 10);
        status.setAlignment(GuiElement.Alignment.LEFT);
        status.setTextColor(ColorUtilities.getRGB(170, 170, 190));
        gui.addElement(status);
        y += 14;

        int chartHeight = h - y - HIST_MARGIN;
        BalanceHistoryChart chart = new BalanceHistoryChart();
        chart.setId("chart");
        chart.setBounds(HIST_MARGIN, y, chartWidth, chartHeight);
        gui.addElement(chart);

        int legendY = y;
        int legendEntryPad = 2;
        for (int i = 0; i < MAX_LEGEND_ITEMS; i++) {
            int entryY = legendY + i * LEGEND_ENTRY_SIZE;
            if (entryY + LEGEND_ENTRY_SIZE > h - HIST_MARGIN) break;

            Frame frame = new Frame(legendX, entryY, LEGEND_WIDTH, LEGEND_ENTRY_SIZE - legendEntryPad);
            frame.setId("lf_" + i);
            if (i < vis.length && vis[i]) {
                frame.setOutlineColor(outColors[i]);
                frame.setBackgroundColor(bgColors[i]);
                frame.setEnableOutline(true);
                frame.setEnableBackground(true);
            } else {
                frame.setEnableOutline(false);
                frame.setEnableBackground(false);
            }
            gui.addElement(frame);

            int iconPad = (LEGEND_WIDTH - 16) / 2;
            int iconYPad = (LEGEND_ENTRY_SIZE - legendEntryPad - 16) / 2;
            ItemView icon = new ItemView(legendX + iconPad, entryY + iconYPad, 16, 16);
            icon.setId("li_" + i);
            icon.setShowTooltip(true);
            gui.addElement(icon);
        }
    }
}
