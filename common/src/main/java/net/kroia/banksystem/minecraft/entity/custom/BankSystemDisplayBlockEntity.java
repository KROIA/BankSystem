package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.bank.ServerBank;
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
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
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

    private static final int[] SERIES_COLORS = {
            ColorUtilities.getRGB(63, 255, 139),
            ColorUtilities.getRGB(255, 113, 108),
            ColorUtilities.getRGB(100, 149, 237),
            ColorUtilities.getRGB(255, 215, 0),
            ColorUtilities.getRGB(186, 85, 211),
            ColorUtilities.getRGB(0, 206, 209),
            ColorUtilities.getRGB(255, 165, 0),
            ColorUtilities.getRGB(144, 238, 144),
            ColorUtilities.getRGB(255, 105, 180),
            ColorUtilities.getRGB(176, 196, 222),
    };
    private static final int MAX_TOGGLE_ROWS = 15;

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private DisplayType displayType = DisplayType.NONE;
    private int accountNumber = 0;

    private DisplayType pendingType = null;
    private int pendingAccountNumber = -1;
    private boolean pendingRebuild = false;

    private int tickCounter = 0;

    // Server-side GUI references
    private Label balanceOverviewAccountLabel;
    private final List<Label> balanceLabels = new ArrayList<>();
    private BalanceHistoryChart historyChart;
    private Label historyAccountLabel;
    private final List<CheckBox> historyToggles = new ArrayList<>();
    private boolean historyDataPending = false;
    private volatile List<BalanceHistoryRecord> pendingHistoryRecords = null;

    private final Set<Short> disabledItems = new HashSet<>();

    // Buffered GUI state for client-side sync via custom data
    private CompoundTag pendingChartState = null;
    private CompoundTag pendingOverviewState = null;
    private String pendingHistoryAccountText = null;

    public BankSystemDisplayBlockEntity(BlockPos pos, BlockState blockState) {
        super(BankSystemEntities.BANKSYSTEM_DISPLAY_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Override
    public DisplayConfig getDisplayConfig() {
        return DISPLAY_CONFIG;
    }

    @Override
    public ContentBuilder getContentBuilder() {
        if (accountNumber == 0) {
            return switch (displayType) {
                case NONE -> BankSystemDisplayBlockEntity::buildSelectionUI;
                case BALANCE_OVERVIEW, BALANCE_HISTORY -> BankSystemDisplayBlockEntity::buildAccountInput;
            };
        }
        return switch (displayType) {
            case NONE -> BankSystemDisplayBlockEntity::buildSelectionUI;
            case BALANCE_OVERVIEW -> BankSystemDisplayBlockEntity::buildBalanceOverview;
            case BALANCE_HISTORY -> BankSystemDisplayBlockEntity::buildBalanceHistory;
        };
    }

    @Override
    public String getChannelId() {
        return "10";
    }

    @Override
    protected void wireCallbacks(Gui gui) {
        balanceOverviewAccountLabel = null;
        balanceLabels.clear();
        historyChart = null;
        historyAccountLabel = null;
        historyToggles.clear();

        if (displayType == DisplayType.NONE) {
            wireSelectionCallbacks(gui);
        } else if (accountNumber == 0) {
            wireAccountInputCallbacks(gui);
        } else if (displayType == DisplayType.BALANCE_OVERVIEW) {
            wireBalanceOverviewCallbacks(gui);
        } else if (displayType == DisplayType.BALANCE_HISTORY) {
            wireBalanceHistoryCallbacks(gui);
        }

        applyPendingStates();
    }

    private void applyPendingStates() {
        if (pendingChartState != null && historyChart != null) {
            historyChart.deserializeState(pendingChartState);
            pendingChartState = null;
        }
        if (pendingOverviewState != null && balanceOverviewAccountLabel != null) {
            balanceOverviewAccountLabel.setText(pendingOverviewState.getString("account"));
            ListTag labels = pendingOverviewState.getList("labels", 10);
            for (int i = 0; i < Math.min(labels.size(), balanceLabels.size()); i++) {
                balanceLabels.get(i).setText(labels.getCompound(i).getString("t"));
            }
            pendingOverviewState = null;
        }
        if (pendingHistoryAccountText != null && historyAccountLabel != null) {
            historyAccountLabel.setText(pendingHistoryAccountText);
            pendingHistoryAccountText = null;
        }
    }

    @Override
    protected void onControllerTick() {
        if (pendingType != null) {
            displayType = pendingType;
            accountNumber = 0;
            pendingType = null;
            pendingRebuild = true;
        }
        if (pendingAccountNumber >= 0) {
            accountNumber = pendingAccountNumber;
            pendingAccountNumber = -1;
            pendingRebuild = true;
        }
        if (pendingRebuild) {
            pendingRebuild = false;
            tickCounter = 0;
            rebuildGui();
            return;
        }

        List<BalanceHistoryRecord> records = pendingHistoryRecords;
        if (records != null) {
            pendingHistoryRecords = null;
            historyDataPending = false;
            applyHistoryData(records);
        }

        tickCounter++;

        if (displayType == DisplayType.BALANCE_OVERVIEW && accountNumber != 0) {
            if (tickCounter % 20 == 0) {
                updateBalanceOverview();
            }
        } else if (displayType == DisplayType.BALANCE_HISTORY && accountNumber != 0) {
            if (tickCounter % 1200 == 0 || tickCounter == 1) {
                requestBalanceHistory();
            }
        }
    }

    private void rebuildGui() {
        if (gui == null) return;
        gui.removeAllElements();
        DisplayConfig config = getDisplayConfig();
        int w = getGroupWidth() * config.virtualWidth();
        int h = getGroupHeight() * config.virtualHeight();
        getContentBuilder().build(gui, w, h);
        wireCallbacks(gui);
        syncToClientPublic();
    }

    @Override
    protected void saveCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("displayType", displayType.getId());
        tag.putInt("accountNumber", accountNumber);
        if (!disabledItems.isEmpty()) {
            ListTag list = new ListTag();
            for (short id : disabledItems) {
                list.add(ShortTag.valueOf(id));
            }
            tag.put("disabledItems", list);
        }
        if (historyChart != null && !historyChart.getSeries().isEmpty()) {
            tag.put("chartState", historyChart.serializeState());
        }
        if (historyAccountLabel != null) {
            tag.putString("histAcct", historyAccountLabel.getText());
        }
        if (balanceOverviewAccountLabel != null) {
            CompoundTag ov = new CompoundTag();
            ov.putString("account", balanceOverviewAccountLabel.getText());
            ListTag labels = new ListTag();
            for (Label l : balanceLabels) {
                CompoundTag lt = new CompoundTag();
                lt.putString("t", l.getText());
                labels.add(lt);
            }
            ov.put("labels", labels);
            tag.put("overviewState", ov);
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
        disabledItems.clear();
        if (tag.contains("disabledItems")) {
            ListTag list = tag.getList("disabledItems", Tag.TAG_SHORT);
            for (Tag t : list) {
                if (t instanceof ShortTag st) {
                    disabledItems.add(st.getAsShort());
                }
            }
        }
        pendingChartState = tag.contains("chartState") ? tag.getCompound("chartState") : null;
        pendingOverviewState = tag.contains("overviewState") ? tag.getCompound("overviewState") : null;
        pendingHistoryAccountText = tag.contains("histAcct") ? tag.getString("histAcct") : null;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (gui == null) return;
        applyPendingStatesToGui();
    }

    private void applyPendingStatesToGui() {
        if (pendingChartState != null) {
            for (var el : gui.getElements()) {
                if (el instanceof BalanceHistoryChart c) {
                    c.deserializeState(pendingChartState);
                    break;
                }
            }
            pendingChartState = null;
        }
        if (pendingOverviewState != null) {
            for (var el : gui.getElements()) {
                if (el instanceof Label l && l.getText() != null && l.getText().startsWith("Account:")) {
                    l.setText(pendingOverviewState.getString("account"));
                    break;
                }
            }
            ListTag labels = pendingOverviewState.getList("labels", 10);
            int idx = 0;
            for (var el : gui.getElements()) {
                if (el instanceof Label l && l.getText() != null && l.getText().startsWith("  ")) {
                    if (idx < labels.size()) {
                        l.setText(labels.getCompound(idx).getString("t"));
                    }
                    idx++;
                }
            }
            pendingOverviewState = null;
        }
        if (pendingHistoryAccountText != null) {
            for (var el : gui.getElements()) {
                if (el instanceof Label l && l.getText() != null && l.getText().startsWith("Account:")) {
                    l.setText(pendingHistoryAccountText);
                    break;
                }
            }
            pendingHistoryAccountText = null;
        }
    }

    @Override
    public void onInputSynced() {}

    // =========================================================================
    // Selection UI
    // =========================================================================

    private void wireSelectionCallbacks(Gui gui) {
        for (var el : gui.getElements()) {
            if (el instanceof Button btn) {
                for (DisplayType type : DisplayType.values()) {
                    if (type == DisplayType.NONE) continue;
                    if (type.getDisplayName().equals(btn.getText())) {
                        btn.setOnFallingEdge(() -> pendingType = type);
                        break;
                    }
                }
            }
        }
    }

    private static void buildSelectionUI(Gui gui, int w, int h) {
        int margin = 10;
        int y = margin;

        Label title = new Label("Configure Display");
        title.setBounds(0, y, w, 16);
        title.setAlignment(GuiElement.Alignment.CENTER);
        gui.addElement(title);
        y += 28;

        Label subtitle = new Label("Select a display type:");
        subtitle.setBounds(margin, y, w - margin * 2, 12);
        subtitle.setAlignment(GuiElement.Alignment.LEFT);
        gui.addElement(subtitle);
        y += 20;

        for (DisplayType type : DisplayType.values()) {
            if (type == DisplayType.NONE) continue;
            Button btn = new Button(type.getDisplayName());
            btn.setId("select_" + type.getId());
            btn.setBounds(margin, y, w - margin * 2, 20);
            gui.addElement(btn);
            y += 26;
        }
    }

    // =========================================================================
    // Account Input UI
    // =========================================================================

    private void wireAccountInputCallbacks(Gui gui) {
        TextBox accountInput = null;
        Button confirmBtn = null;
        Button backBtn = null;

        for (var el : gui.getElements()) {
            if (el instanceof TextBox tb) {
                accountInput = tb;
            } else if (el instanceof Button btn) {
                if ("Confirm".equals(btn.getText())) confirmBtn = btn;
                else if ("Back".equals(btn.getText())) backBtn = btn;
            }
        }

        if (accountInput != null && confirmBtn != null) {
            final TextBox input = accountInput;
            confirmBtn.setOnFallingEdge(() -> {
                String text = input.getText().trim();
                try {
                    int num = Integer.parseInt(text);
                    if (num > 0) {
                        pendingAccountNumber = num;
                    }
                } catch (NumberFormatException ignored) {}
            });
        }
        if (backBtn != null) {
            backBtn.setOnFallingEdge(() -> pendingType = DisplayType.NONE);
        }
    }

    private static void buildAccountInput(Gui gui, int w, int h) {
        int margin = 10;
        int y = margin;

        Label title = new Label("Enter Account Number");
        title.setBounds(0, y, w, 16);
        title.setAlignment(GuiElement.Alignment.CENTER);
        gui.addElement(title);
        y += 28;

        Label hint = new Label("Account #:");
        hint.setBounds(margin, y, 70, 14);
        hint.setAlignment(GuiElement.Alignment.LEFT);
        gui.addElement(hint);

        TextBox accountInput = new TextBox(margin + 72, y, w - margin * 2 - 72);
        accountInput.setId("account_input");
        accountInput.setText("");
        accountInput.setMaxChars(10);
        gui.addElement(accountInput);
        y += 24;

        Button confirmBtn = new Button("Confirm");
        confirmBtn.setId("confirm_account");
        confirmBtn.setBounds(margin, y, w - margin * 2, 20);
        gui.addElement(confirmBtn);
        y += 26;

        Button backBtn = new Button("Back");
        backBtn.setId("back");
        backBtn.setBounds(margin, y, w - margin * 2, 20);
        gui.addElement(backBtn);
    }

    // =========================================================================
    // Balance Overview
    // =========================================================================

    private void wireBalanceOverviewCallbacks(Gui gui) {
        for (var el : gui.getElements()) {
            if (el instanceof Label l) {
                String text = l.getText();
                if (text != null && text.startsWith("Account:")) {
                    balanceOverviewAccountLabel = l;
                } else if (text != null && text.startsWith("  ")) {
                    balanceLabels.add(l);
                }
            } else if (el instanceof Button btn && "Reconfigure".equals(btn.getText())) {
                btn.setOnFallingEdge(() -> pendingType = DisplayType.NONE);
            }
        }
        updateBalanceOverview();
    }

    private void updateBalanceOverview() {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) return;
        ISyncServerBankManager syncManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (syncManager == null) return;

        var account = syncManager.getBankAccount(accountNumber);
        if (account == null) {
            if (balanceOverviewAccountLabel != null) {
                balanceOverviewAccountLabel.setText("Account: #" + accountNumber + " (not found)");
            }
            syncToClientPublic();
            return;
        }

        BankAccountData data = account.getAccountData();
        if (balanceOverviewAccountLabel != null) {
            balanceOverviewAccountLabel.setText("Account: " + data.accountName);
        }

        int idx = 0;
        for (Map.Entry<ItemID, BankData> entry : data.bankData.entrySet()) {
            if (idx >= balanceLabels.size()) break;
            ItemID itemId = entry.getKey();
            BankData bankData = entry.getValue();
            String name = itemId.getName();
            if (name == null) name = "Item#" + itemId.getShort();
            String bal = ServerBank.getFormattedAmountStatic(bankData.balance());
            balanceLabels.get(idx).setText("  " + name + ": " + bal);
            idx++;
        }
        for (int i = idx; i < balanceLabels.size(); i++) {
            balanceLabels.get(i).setText("  ");
        }

        syncToClientPublic();
    }

    private static void buildBalanceOverview(Gui gui, int w, int h) {
        int margin = 10;
        int y = margin;

        Label title = new Label("Balance Overview");
        title.setBounds(0, y, w, 16);
        title.setAlignment(GuiElement.Alignment.CENTER);
        gui.addElement(title);
        y += 24;

        Label accountLabel = new Label("Account: loading...");
        accountLabel.setBounds(margin, y, w - margin * 2, 12);
        accountLabel.setAlignment(GuiElement.Alignment.LEFT);
        gui.addElement(accountLabel);
        y += 18;

        for (int i = 0; i < 10; i++) {
            Label itemLabel = new Label("  ");
            itemLabel.setBounds(margin, y, w - margin * 2, 12);
            itemLabel.setAlignment(GuiElement.Alignment.LEFT);
            gui.addElement(itemLabel);
            y += 14;
        }

        Button reconfigure = new Button("Reconfigure");
        reconfigure.setId("reconfigure");
        reconfigure.setBounds(margin, h - margin - 20, w - margin * 2, 20);
        gui.addElement(reconfigure);
    }

    // =========================================================================
    // Balance History
    // =========================================================================

    private void wireBalanceHistoryCallbacks(Gui gui) {
        for (var el : gui.getElements()) {
            if (el instanceof BalanceHistoryChart c) {
                historyChart = c;
            } else if (el instanceof Label l && l.getText() != null && l.getText().startsWith("Account:")) {
                historyAccountLabel = l;
            } else if (el instanceof Button btn && "Reconfigure".equals(btn.getText())) {
                btn.setOnFallingEdge(() -> pendingType = DisplayType.NONE);
            }
        }

        findTogglesRecursive(gui.getElements());
        requestBalanceHistory();
    }

    private void findTogglesRecursive(List<GuiElement> elements) {
        for (var el : elements) {
            if (el instanceof CheckBox cb && cb.getId() != null && cb.getId().startsWith("toggle_")) {
                historyToggles.add(cb);
            }
            if (el instanceof VerticalListView lv) {
                findTogglesRecursive(lv.getChilds());
            }
        }
    }

    private void requestBalanceHistory() {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER == null) return;
        if (historyDataPending) return;

        ISyncServerBankManager syncManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER != null
                ? BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() : null;

        if (syncManager != null && historyAccountLabel != null) {
            var account = syncManager.getBankAccount(accountNumber);
            if (account != null) {
                historyAccountLabel.setText("Account: " + account.getAccountName());
            } else {
                historyAccountLabel.setText("Account: #" + accountNumber + " (not found)");
            }
        }

        BalanceHistoryManager historyManager = BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER;
        historyDataPending = true;
        historyManager.getHistory(
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
            syncToClientPublic();
            return;
        }

        Map<Short, List<BalanceHistoryRecord>> grouped = new LinkedHashMap<>();
        for (BalanceHistoryRecord r : records) {
            grouped.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r);
        }

        boolean firstLoad = historyChart.getSeries().isEmpty();
        historyChart.clearSeries();
        historyChart.clearHoverBindings();
        double scaleFactor = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        List<BalanceHistoryRecord> wealthRecords = grouped.remove(BalanceHistoryRecord.WEALTH_ITEM_ID);
        if (wealthRecords != null && !wealthRecords.isEmpty()) {
            int wealthColor = ColorUtilities.getRGB(255, 215, 0);
            BalanceHistoryChart.LineSeries wealthSeries =
                    new BalanceHistoryChart.LineSeries("Total Wealth", wealthColor);
            wealthSeries.visible = !disabledItems.contains(BalanceHistoryRecord.WEALTH_ITEM_ID);
            for (BalanceHistoryRecord r : wealthRecords) {
                wealthSeries.points.add(new BalanceHistoryChart.DataPoint(r.time(), r.balance() / scaleFactor));
            }
            historyChart.addSeries(wealthSeries);
            historyChart.setPinnedSeries(wealthSeries);
        }

        int colorIdx = 0;
        int toggleIdx = 0;
        for (var entry : grouped.entrySet()) {
            short itemId = entry.getKey();
            List<BalanceHistoryRecord> itemRecords = entry.getValue();

            String name = getItemDisplayName(itemId);
            int color = ItemColorUtil.getColor(itemId, colorIdx);
            colorIdx++;

            BalanceHistoryChart.LineSeries series =
                    new BalanceHistoryChart.LineSeries(name, color);
            series.visible = !disabledItems.contains(itemId);

            for (BalanceHistoryRecord r : itemRecords) {
                double totalBalance = (r.balance() + r.lockedBalance()) / scaleFactor;
                series.points.add(new BalanceHistoryChart.DataPoint(r.time(), totalBalance));
            }
            historyChart.addSeries(series);

            if (toggleIdx < historyToggles.size()) {
                CheckBox cb = historyToggles.get(toggleIdx);
                cb.setText(name);
                cb.setChecked(series.visible);
                cb.setEnabled(true);
                final short fItemId = itemId;
                final BalanceHistoryChart.LineSeries toggleSeries = series;
                cb.setOnStateChanged(checked -> {
                    toggleSeries.visible = checked;
                    if (checked) disabledItems.remove(fItemId);
                    else disabledItems.add(fItemId);
                    syncToClientPublic();
                });
                historyChart.bindHoverElement(cb, series);
                toggleIdx++;
            }
        }

        for (int i = toggleIdx; i < historyToggles.size(); i++) {
            historyToggles.get(i).setEnabled(false);
            historyToggles.get(i).setText("");
        }

        if (firstLoad) {
            historyChart.autoCenterView();
        }
        syncToClientPublic();
    }

    private static String getItemDisplayName(short itemId) {
        ItemID id = new ItemID(itemId);
        String name = id.getName();
        return name != null ? name : "Item#" + itemId;
    }

    private static void buildBalanceHistory(Gui gui, int w, int h) {
        int margin = 6;
        int y = margin;
        int toggleWidth = Math.max(60, w / 5);
        int chartWidth = w - toggleWidth - margin * 3;

        Label title = new Label("Balance History");
        title.setBounds(0, y, w, 14);
        title.setAlignment(GuiElement.Alignment.CENTER);
        gui.addElement(title);
        y += 18;

        Label accountLabel = new Label("Account: loading...");
        accountLabel.setBounds(margin, y, chartWidth, 10);
        accountLabel.setAlignment(GuiElement.Alignment.LEFT);
        gui.addElement(accountLabel);
        y += 14;

        int chartHeight = h - y - margin - 22;
        BalanceHistoryChart chart = new BalanceHistoryChart();
        chart.setId("history_chart");
        chart.setBounds(margin, y, chartWidth, chartHeight);
        gui.addElement(chart);

        int toggleX = margin + chartWidth + margin;
        int toggleY = y;
        int toggleHeight = chartHeight;

        VerticalListView toggleList = new VerticalListView();
        toggleList.setBounds(toggleX, toggleY, toggleWidth, toggleHeight);
        LayoutGrid layout = new LayoutGrid();
        layout.stretchX = true;
        layout.columns = 1;
        toggleList.setLayout(layout);
        gui.addElement(toggleList);

        for (int i = 0; i < MAX_TOGGLE_ROWS; i++) {
            CheckBox cb = new CheckBox("");
            cb.setId("toggle_" + i);
            cb.setHeight(14);
            cb.setEnabled(false);
            toggleList.addChild(cb);
        }

        Button reconfigure = new Button("Reconfigure");
        reconfigure.setId("reconfigure");
        reconfigure.setBounds(margin, h - margin - 18, w - margin * 2, 18);
        gui.addElement(reconfigure);
    }
}
