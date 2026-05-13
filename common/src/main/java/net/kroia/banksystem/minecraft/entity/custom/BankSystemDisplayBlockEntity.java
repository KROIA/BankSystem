package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.data.filter.EqualityFilter;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.display.AbstractDisplayBlockEntity;
import net.kroia.modutilities.gui.display.ContentBuilder;
import net.kroia.modutilities.gui.display.DisplayConfig;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.Plot;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
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

    // Server-side GUI element references (set in wireCallbacks)
    private Label balanceOverviewAccountLabel;
    private final List<Label> balanceLabels = new ArrayList<>();
    private Plot historyPlot;
    private Label historyStatusLabel;
    private boolean historyDataPending = false;

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
        historyPlot = null;
        historyStatusLabel = null;

        if (displayType == DisplayType.NONE) {
            wireSelectionCallbacks(gui);
        } else if (accountNumber == 0) {
            wireAccountInputCallbacks(gui);
        } else if (displayType == DisplayType.BALANCE_OVERVIEW) {
            wireBalanceOverviewCallbacks(gui);
        } else if (displayType == DisplayType.BALANCE_HISTORY) {
            wireBalanceHistoryCallbacks(gui);
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

        tickCounter++;

        if (displayType == DisplayType.BALANCE_OVERVIEW && accountNumber != 0) {
            if (tickCounter % 20 == 0) {
                updateBalanceOverview();
            }
        } else if (displayType == DisplayType.BALANCE_HISTORY && accountNumber != 0) {
            if (tickCounter % 1200 == 0 || tickCounter == 1) {
                updateBalanceHistory();
            }
        }
    }

    private void rebuildGui() {
        if (gui == null) return;
        gui.removeAllElements();
        gui.resetStructureVersion();
        DisplayConfig config = getDisplayConfig();
        int w = getGroupWidth() * config.virtualWidth();
        int h = getGroupHeight() * config.virtualHeight();
        getContentBuilder().build(gui, w, h);
        gui.resetStructureVersion();
        wireCallbacks(gui);
        syncToClientPublic();
    }

    @Override
    protected void saveCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("displayType", displayType.getId());
        tag.putInt("accountNumber", accountNumber);
    }

    @Override
    protected void loadCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("displayType")) {
            displayType = DisplayType.fromId(tag.getString("displayType"));
        }
        if (tag.contains("accountNumber")) {
            accountNumber = tag.getInt("accountNumber");
        }
    }

    // -------------------------------------------------------------------------
    // Selection UI
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Account Input UI
    // -------------------------------------------------------------------------

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

    @Override
    public void onInputSynced() {
        // TextBox values are synced via the interaction screen packet
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

    // -------------------------------------------------------------------------
    // Balance Overview
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Balance History
    // -------------------------------------------------------------------------

    private void wireBalanceHistoryCallbacks(Gui gui) {
        for (var el : gui.getElements()) {
            if (el instanceof Plot p) {
                historyPlot = p;
            } else if (el instanceof Label l) {
                String text = l.getText();
                if (text != null && text.startsWith("Account:")) {
                    historyStatusLabel = l;
                }
            } else if (el instanceof Button btn && "Reconfigure".equals(btn.getText())) {
                btn.setOnFallingEdge(() -> pendingType = DisplayType.NONE);
            }
        }
        updateBalanceHistory();
    }

    private void updateBalanceHistory() {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER == null) return;
        if (historyDataPending) return;

        ISyncServerBankManager syncManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER != null
                ? BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() : null;

        if (syncManager != null && historyStatusLabel != null) {
            var account = syncManager.getBankAccount(accountNumber);
            if (account != null) {
                historyStatusLabel.setText("Account: " + account.getAccountName());
            } else {
                historyStatusLabel.setText("Account: #" + accountNumber + " (not found)");
            }
        }

        BalanceHistoryManager historyManager = BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER;
        historyDataPending = true;
        historyManager.getHistory(
                Optional.empty(),
                Optional.of(new EqualityFilter(accountNumber)),
                Optional.empty(),
                500
        ).thenAccept(records -> {
            historyDataPending = false;
            if (historyPlot == null || records.isEmpty()) {
                if (historyPlot != null) {
                    historyPlot.clearPlotData();
                    syncToClientPublic();
                }
                return;
            }

            Map<Short, List<BalanceHistoryRecord>> byItem = new LinkedHashMap<>();
            for (BalanceHistoryRecord r : records) {
                byItem.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r);
            }

            historyPlot.clearPlotData();

            int colorIdx = 0;
            int[] colors = {0xFF55AAFF, 0xFFFF5555, 0xFF55FF55, 0xFFFFAA00, 0xFFAA55FF, 0xFF55FFFF};
            float globalMin = Float.MAX_VALUE;
            float globalMax = Float.MIN_VALUE;

            for (var entry : byItem.entrySet()) {
                short itemId = entry.getKey();
                if (itemId == BalanceHistoryRecord.WEALTH_ITEM_ID) continue;
                List<BalanceHistoryRecord> itemRecords = entry.getValue();

                Plot.PlotData series = new Plot.PlotData();
                series.color = colors[colorIdx % colors.length];
                series.thickness = 1.5f;
                colorIdx++;

                for (BalanceHistoryRecord r : itemRecords) {
                    float val = (float) BankManager.convertToRealAmountStatic(r.balance());
                    series.yValues.add(val);
                    if (val < globalMin) globalMin = val;
                    if (val > globalMax) globalMax = val;
                }

                historyPlot.addPlotData(series);
            }

            if (globalMin == globalMax) {
                globalMin -= 1;
                globalMax += 1;
            }
            float padding = (globalMax - globalMin) * 0.1f;
            historyPlot.setYRange(globalMin - padding, globalMax + padding);
            historyPlot.setXRange(0, Math.max(1, records.size() / Math.max(1, byItem.size())));

            syncToClientPublic();
        });
    }

    private static void buildBalanceHistory(Gui gui, int w, int h) {
        int margin = 10;
        int y = margin;

        Label title = new Label("Balance History");
        title.setBounds(0, y, w, 16);
        title.setAlignment(GuiElement.Alignment.CENTER);
        gui.addElement(title);
        y += 22;

        Label accountLabel = new Label("Account: loading...");
        accountLabel.setBounds(margin, y, w - margin * 2, 12);
        accountLabel.setAlignment(GuiElement.Alignment.LEFT);
        gui.addElement(accountLabel);
        y += 16;

        int plotHeight = h - y - margin - 24;
        Plot plot = new Plot();
        plot.setBounds(margin, y, w - margin * 2, plotHeight);
        plot.setXRange(0, 100);
        plot.setYRange(0, 100);
        plot.setYAxisValueConversion("%.1f");
        plot.setXAxisValueConversion("%.0f");
        gui.addElement(plot);

        Button reconfigure = new Button("Reconfigure");
        reconfigure.setId("reconfigure");
        reconfigure.setBounds(margin, h - margin - 20, w - margin * 2, 20);
        gui.addElement(reconfigure);
    }
}
