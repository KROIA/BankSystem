package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.DropDownMenu;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Task #45a (v2.0.8) — modal editor for a single {@link PayoutSchedule}.
 * <p>
 * MANAGE-gated via {@code canManage}: when false the edit widgets render read-only
 * (Save + Delete hidden, target picker disabled).
 */
public class PayoutEditScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".payout_edit_screen.";
    private static final Component TITLE_EDIT = Component.translatable(PREFIX + "title");
    private static final Component TITLE_NEW = Component.translatable(PREFIX + "title_new");
    private static final Component AMOUNT = Component.translatable(PREFIX + "amount");
    private static final Component INTERVAL = Component.translatable(PREFIX + "interval");
    private static final Component INTERVAL_1M = Component.translatable(PREFIX + "interval_1m");
    private static final Component INTERVAL_1H = Component.translatable(PREFIX + "interval_1h");
    private static final Component INTERVAL_1D = Component.translatable(PREFIX + "interval_1d");
    private static final Component INTERVAL_CUSTOM = Component.translatable(PREFIX + "interval_custom");
    private static final Component PAUSED = Component.translatable(PREFIX + "paused");
    private static final Component TARGET = Component.translatable(PREFIX + "target");
    private static final Component PICK_TARGET = Component.translatable(PREFIX + "pick_target");
    private static final Component SAVE = Component.translatable(PREFIX + "save");
    private static final Component CANCEL = Component.translatable(PREFIX + "cancel");
    private static final Component DELETE = Component.translatable(PREFIX + "delete");
    private static final Component HISTORY = Component.translatable(PREFIX + "history");
    private static final String TOTAL_PAID_KEY = PREFIX + "total_paid";
    private static final Component STATUS_OK = Component.translatable(PREFIX + "status_ok");
    private static final Component STATUS_INSUFF = Component.translatable(PREFIX + "status_insufficient_funds");
    private static final Component STATUS_TARGET = Component.translatable(PREFIX + "status_target_missing");
    private static final int COLOR_OK = 0xFF10b981;
    private static final int COLOR_INSUFF = 0xFFeab308;
    private static final int COLOR_TARGET = 0xFFe11d48;

    private static final long[] INTERVAL_PRESETS = { 1200L, 72000L, 1728000L };

    private final GuiScreen parent;
    private final int companyId;
    private final PayoutSchedule original;
    private final UUID caller;
    private final boolean canManage;
    private final Runnable onDirty;

    private UUID targetUUID;
    private String targetName = "";

    private Label titleLabel;
    private Label amountLabel;
    private TextBox amountBox;
    private Label intervalLabel;
    private DropDownMenu intervalDropDown;
    private TextBox customTicksBox;
    private CheckBox pausedCheckBox;
    private Label targetLabel;
    private Button pickTargetButton;
    private Label historyLabel;
    private Label totalPaidLabel;
    private ListView historyListView;
    private Button saveButton;
    private Button cancelButton;
    private Button deleteButton;

    public PayoutEditScreen(GuiScreen parent, int companyId, PayoutSchedule schedule,
                            UUID caller, boolean canManage, Runnable onDirty) {
        super(schedule == null ? TITLE_NEW : TITLE_EDIT);
        this.parent = parent;
        this.companyId = companyId;
        this.original = schedule;
        this.caller = caller;
        this.canManage = canManage;
        this.onDirty = onDirty;
        if (schedule != null) {
            this.targetUUID = schedule.getTargetUUID();
            if (targetUUID != null) this.targetName = truncate(targetUUID);
        }
        setupUi();
    }

    private static String truncate(UUID u) {
        String s = u.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private void setupUi() {
        titleLabel = new Label(original == null ? TITLE_NEW.getString() : TITLE_EDIT.getString());
        amountLabel = new Label(AMOUNT.getString());
        amountBox = new TextBox();
        amountBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 12, 0));
        amountBox.setText(original == null ? "100" : String.valueOf(original.getAmount()));
        amountBox.setEnabled(canManage);

        intervalLabel = new Label(INTERVAL.getString());
        intervalDropDown = new DropDownMenu(INTERVAL.getString());
        intervalDropDown.addOption(INTERVAL_1M.getString());
        intervalDropDown.addOption(INTERVAL_1H.getString());
        intervalDropDown.addOption(INTERVAL_1D.getString());
        intervalDropDown.addOption(INTERVAL_CUSTOM.getString());
        int startIdx = 1; // 1h default
        if (original != null) {
            long ticks = original.getIntervalTicks();
            if (ticks == INTERVAL_PRESETS[0]) startIdx = 0;
            else if (ticks == INTERVAL_PRESETS[1]) startIdx = 1;
            else if (ticks == INTERVAL_PRESETS[2]) startIdx = 2;
            else startIdx = 3;
        }
        intervalDropDown.setSelectedIndex(startIdx);

        customTicksBox = new TextBox();
        customTicksBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 12, 0));
        customTicksBox.setText(original == null ? "1200" : String.valueOf(original.getIntervalTicks()));
        customTicksBox.setEnabled(canManage);

        pausedCheckBox = new CheckBox(PAUSED.getString());
        pausedCheckBox.setChecked(original != null && original.isPaused());
        pausedCheckBox.setEnabled(canManage);

        targetLabel = new Label(TARGET.getString() + ": " + (targetName.isEmpty() ? "-" : targetName));
        pickTargetButton = new Button(PICK_TARGET.getString(), this::onPickTargetClicked);
        pickTargetButton.setEnabled(canManage);

        historyLabel = new Label(HISTORY.getString());
        totalPaidLabel = new Label(Component.translatable(TOTAL_PAID_KEY, "0").getString());
        historyListView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        historyListView.setLayout(layout);

        saveButton = new Button(SAVE.getString(), this::onSaveClicked);
        saveButton.setEnabled(canManage);
        cancelButton = new Button(CANCEL.getString(), this::onClose);
        if (original != null && canManage) {
            deleteButton = new Button(DELETE.getString(), this::onDeleteClicked);
        }

        addElement(titleLabel);
        addElement(amountLabel);
        addElement(amountBox);
        addElement(intervalLabel);
        addElement(intervalDropDown);
        addElement(customTicksBox);
        addElement(pausedCheckBox);
        addElement(targetLabel);
        addElement(pickTargetButton);
        addElement(historyLabel);
        addElement(totalPaidLabel);
        addElement(historyListView);
        addElement(saveButton);
        addElement(cancelButton);
        if (deleteButton != null) addElement(deleteButton);

        // Populate total-paid + last-20 history rows best-effort.
        if (original != null) {
            AsyncCompanyManager.getHistoryAsync(original.getScheduleId(), 20).thenAccept(out -> {
                if (out == null) return;
                totalPaidLabel.setText(Component.translatable(TOTAL_PAID_KEY, String.valueOf(out.totalPaid())).getString());
                historyListView.removeChilds();
                for (AsyncCompanyManager.HistoryRowWire row : out.rows()) {
                    Label rowLabel = new Label(formatHistoryRow(row));
                    rowLabel.setTextColor(colorForStatus(row.statusOrdinal()));
                    rowLabel.setHeight(15);
                    historyListView.addChild(rowLabel);
                }
            });
        }
    }

    private static String statusText(int ordinal) {
        return switch (ordinal) {
            case 0 -> STATUS_OK.getString();
            case 1 -> STATUS_INSUFF.getString();
            case 2 -> STATUS_TARGET.getString();
            default -> "?";
        };
    }

    private static int colorForStatus(int ordinal) {
        return switch (ordinal) {
            case 0 -> COLOR_OK;
            case 1 -> COLOR_INSUFF;
            case 2 -> COLOR_TARGET;
            default -> 0xFFFFFFFF;
        };
    }

    private static String formatHistoryRow(AsyncCompanyManager.HistoryRowWire row) {
        return row.time() + "  " + row.amount() + "  " + statusText(row.statusOrdinal());
    }

    private long selectedIntervalTicks() {
        int idx = intervalDropDown.getSelectedIndex();
        if (idx >= 0 && idx < INTERVAL_PRESETS.length) return INTERVAL_PRESETS[idx];
        try {
            return Long.parseLong(customTicksBox.getText().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long parsedAmount() {
        try {
            return Long.parseLong(amountBox.getText().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void onPickTargetClicked() {
        if (!canManage) return;
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getBankManagerDataAsync().thenAccept(data -> {
            if (data == null) return;
            UserSelectionScreen screen = new UserSelectionScreen(this, userData -> {
                targetUUID = userData.userUUID();
                targetName = userData.userName();
                targetLabel.setText(TARGET.getString() + ": " + targetName);
            });
            screen.setUsers(new java.util.ArrayList<>(data.userMapData().userMap().values()));
            Minecraft.getInstance().setScreen(screen);
        });
    }

    private void onSaveClicked() {
        if (!canManage) return;
        long amount = parsedAmount();
        long intervalTicks = selectedIntervalTicks();
        boolean paused = pausedCheckBox.isChecked();

        if (original == null) {
            if (targetUUID == null) return;
            AsyncCompanyManager.createPayoutAsync(companyId, targetUUID, amount, intervalTicks, 0L, caller)
                    .thenAccept(out -> {
                        if (out != null && out.resultCode() == AsyncCompanyManager.CODE_OK
                                && paused && out.scheduleId() != 0L) {
                            AsyncCompanyManager.pausePayoutAsync(companyId, out.scheduleId(), true, caller);
                        }
                        if (onDirty != null) onDirty.run();
                        Minecraft.getInstance().execute(this::closeToParent);
                    });
        } else {
            AsyncCompanyManager.updatePayoutAsync(companyId, original.getScheduleId(), amount, intervalTicks, caller);
            if (paused != original.isPaused()) {
                AsyncCompanyManager.pausePayoutAsync(companyId, original.getScheduleId(), paused, caller);
            }
            if (onDirty != null) onDirty.run();
            closeToParent();
        }
    }

    private void onDeleteClicked() {
        if (!canManage || original == null) return;
        AsyncCompanyManager.deletePayoutAsync(companyId, original.getScheduleId(), caller).thenAccept(o -> {
            if (onDirty != null) onDirty.run();
            Minecraft.getInstance().execute(this::closeToParent);
        });
    }

    private void closeToParent() {
        if (parent != null && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int spacing = 5;
        int padding = 5;
        int width = getWidth() - 2 * padding;
        if (titleLabel == null) return;

        int y = padding;
        titleLabel.setBounds(padding, y, width, 20); y += 25;

        int col = width / 2;
        amountLabel.setBounds(padding, y, col - spacing, 20);
        amountBox.setBounds(padding + col, y, col - spacing, 20);
        y += 25;

        intervalLabel.setBounds(padding, y, col - spacing, 20);
        intervalDropDown.setBounds(padding + col, y, col - spacing, 20);
        y += 25;

        customTicksBox.setBounds(padding + col, y, col - spacing, 20);
        y += 25;

        pausedCheckBox.setBounds(padding, y, col - spacing, 20);
        y += 25;

        targetLabel.setBounds(padding, y, col - spacing, 20);
        pickTargetButton.setBounds(padding + col, y, col - spacing, 20);
        y += 25;

        historyLabel.setBounds(padding, y, col - spacing, 20);
        totalPaidLabel.setBounds(padding + col, y, col - spacing, 20);
        y += 25;

        int listBottom = getHeight() - 30;
        int listHeight = Math.max(0, listBottom - y);
        historyListView.setBounds(padding, y, width, listHeight);

        int btnW = (width - 2 * spacing) / 3;
        saveButton.setBounds(padding, getHeight() - 25, btnW, 20);
        cancelButton.setBounds(padding + btnW + spacing, getHeight() - 25, btnW, 20);
        if (deleteButton != null) {
            deleteButton.setBounds(padding + 2 * (btnW + spacing), getHeight() - 25, btnW, 20);
        }
    }
}
