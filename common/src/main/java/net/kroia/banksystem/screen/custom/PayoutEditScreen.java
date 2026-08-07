package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.screen.uiElements.ItemBalancePickerPopup;
import net.kroia.banksystem.screen.uiElements.SplitPlayerAccountPickerPopup;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.MoneyFormat;
import net.kroia.banksystem.util.TimeFormat;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Task #45a (v2.0.8) — modal editor for a single {@link PayoutSchedule}.
 * <p>
 * MANAGE-gated via {@code canManage}: when false the edit widgets render read-only
 * (Save + Delete hidden, target picker disabled).
 * <p>
 * Spec A.5/A.6/A.7/A.8/A.9 + B.1/B.2/B.3 (v2.0.8): right-aligned labels, decimal
 * money input, formatted timestamps, typed failure reasons, name-snapshot target
 * display, split player+account target picker, dividend mode toggle, and a
 * currency-item slot next to the amount input.
 */
public class PayoutEditScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".payout_edit_screen.";
    private static final Component TITLE_EDIT = Component.translatable(PREFIX + "title");
    private static final Component TITLE_NEW = Component.translatable(PREFIX + "title_new");
    private static final Component AMOUNT = Component.translatable(PREFIX + "amount");
    private static final Component AMOUNT_TOOLTIP_MONEY = Component.translatable(PREFIX + "amount_tooltip_money");
    private static final Component AMOUNT_TOOLTIP_ITEM = Component.translatable(PREFIX + "amount_tooltip_item");
    private static final Component CURRENCY_TOOLTIP = Component.translatable(PREFIX + "currency_tooltip");
    private static final Component INTERVAL = Component.translatable(PREFIX + "interval");
    private static final Component INTERVAL_1M = Component.translatable(PREFIX + "interval_1m");
    private static final Component INTERVAL_1H = Component.translatable(PREFIX + "interval_1h");
    private static final Component INTERVAL_1D = Component.translatable(PREFIX + "interval_1d");
    private static final Component INTERVAL_CUSTOM = Component.translatable(PREFIX + "interval_custom");
    private static final Component PAUSED = Component.translatable(PREFIX + "paused");
    private static final Component PAUSED_TOOLTIP = Component.translatable(PREFIX + "paused_tooltip");
    private static final Component MODE_DIVIDEND = Component.translatable(PREFIX + "mode_dividend");
    private static final Component MODE_DIVIDEND_TOOLTIP = Component.translatable(PREFIX + "mode_dividend_tooltip");
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
    private static final Component STATUS_NO_DEPOSIT = Component.translatable(PREFIX + "status_no_deposit_right");
    private static final Component STATUS_CURRENCY = Component.translatable(PREFIX + "status_currency_missing");
    private static final Component STATUS_PAUSED = Component.translatable(PREFIX + "status_paused");
    private static final Component STATUS_UNKNOWN = Component.translatable(PREFIX + "status_unknown");
    private static final Component TYPE_CATCH_UP = Component.translatable(PREFIX + "type_catch_up");
    private static final int COLOR_OK = 0xFF10b981;
    private static final int COLOR_INSUFF = 0xFFeab308;
    private static final int COLOR_TARGET = 0xFFe11d48;

    private static final Component INTERVAL_CUSTOM_TOOLTIP = Component.translatable(PREFIX + "interval_custom_tooltip");

    /** 1 real-time minute / 1 real-time hour / 1 Minecraft day (24000 ticks). */
    private static final long[] INTERVAL_PRESETS = { 1200L, 72000L, 24000L };
    /** Ticks per real-time minute (custom interval is entered in minutes). */
    private static final long TICKS_PER_MINUTE = 1200L;
    private static final int RADIO_SELECTED_COLOR = 0xFF3d7a48;
    private static final int RADIO_IDLE_COLOR = GuiElement.DEFAULT_BACKGROUND_COLOR;

    private final GuiScreen parent;
    private final int companyId;
    private final PayoutSchedule original;
    private final UUID caller;
    private final boolean canManage;
    private final Runnable onDirty;

    private UUID targetUUID;
    private String targetName = "";
    private int targetAccountNr = PayoutSchedule.NO_TARGET_ACCOUNT;
    private String targetAccountName = "";
    private short currencyItem = PayoutSchedule.MONEY_CURRENCY;

    private Label titleLabel;
    private Label amountLabel;
    private TextBox amountBox;
    private Button currencyButton;
    private ItemView currencyIcon;
    private Label intervalLabel;
    /** REDESIGN 1 (v2.0.8) — 4 radio-style buttons: 1 min | 1 hour | 1 MC day | Custom. */
    private Button[] intervalButtons;
    private int selectedIntervalIdx;
    private TextBox customMinutesBox;
    private CheckBox pausedCheckBox;
    private CheckBox dividendCheckBox;
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
            this.targetName = schedule.getTargetPlayerName();
            this.targetAccountNr = schedule.getTargetAccountNr();
            this.targetAccountName = schedule.getTargetAccountName();
            this.currencyItem = schedule.getCurrencyItem();
            if (targetName.isEmpty() && targetUUID != null) this.targetName = truncate(targetUUID);
        }
        setupUi();
    }

    private static String truncate(UUID u) {
        String s = u.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private boolean isMoneyCurrency() {
        return currencyItem == PayoutSchedule.MONEY_CURRENCY;
    }

    private void setupUi() {
        titleLabel = new Label(original == null ? TITLE_NEW.getString() : TITLE_EDIT.getString());
        amountLabel = new Label(AMOUNT.getString());
        amountLabel.setAlignment(Label.Alignment.RIGHT); // Spec A.5
        amountBox = new TextBox();
        amountBox.setHoverTooltipSupplier(() ->
                (isMoneyCurrency() ? AMOUNT_TOOLTIP_MONEY : AMOUNT_TOOLTIP_ITEM).getString());
        amountBox.setEnabled(canManage);

        // Spec B.3 — currency slot next to the amount input.
        currencyButton = new Button("", this::onPickCurrencyClicked);
        currencyButton.setEnabled(canManage);
        currencyButton.setHoverTooltipSupplier(CURRENCY_TOOLTIP::getString);
        currencyIcon = new ItemView();
        currencyIcon.setShowCount(false);
        currencyButton.addChild(currencyIcon);
        // BUG 1 fix (v2.0.8) — fixed-point applies to all currencies (money AND items).
        applyCurrency(currencyItem, original == null ? MoneyFormat.format(100L * MoneyFormat.SCALE)
                : MoneyFormat.format(original.getAmount()));

        intervalLabel = new Label(INTERVAL.getString());
        intervalLabel.setAlignment(Label.Alignment.RIGHT); // Spec A.5
        // REDESIGN 1 (v2.0.8) — radio-button row replaces the DropDownMenu.
        int startIdx = 1; // 1h default
        if (original != null) {
            long ticks = original.getIntervalTicks();
            if (ticks == INTERVAL_PRESETS[0]) startIdx = 0;
            else if (ticks == INTERVAL_PRESETS[1]) startIdx = 1;
            else if (ticks == INTERVAL_PRESETS[2]) startIdx = 2;
            else startIdx = 3;
        }
        String[] intervalOptionTexts = {
                INTERVAL_1M.getString(), INTERVAL_1H.getString(),
                INTERVAL_1D.getString(), INTERVAL_CUSTOM.getString()
        };
        intervalButtons = new Button[intervalOptionTexts.length];
        for (int i = 0; i < intervalOptionTexts.length; i++) {
            final int idx = i;
            intervalButtons[i] = new Button(intervalOptionTexts[i], () -> onIntervalSelected(idx));
            intervalButtons[i].setEnabled(canManage);
        }

        // Custom interval is entered in real-time MINUTES (>= 1); stored in ticks.
        customMinutesBox = new TextBox();
        customMinutesBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 9, 0));
        long loadedMinutes = original == null ? 1L
                : Math.max(1L, original.getIntervalTicks() / TICKS_PER_MINUTE);
        customMinutesBox.setText(String.valueOf(loadedMinutes));
        customMinutesBox.setHoverTooltipSupplier(INTERVAL_CUSTOM_TOOLTIP::getString);
        customMinutesBox.setEnabled(canManage);

        selectedIntervalIdx = startIdx;

        pausedCheckBox = new CheckBox(PAUSED.getString());
        pausedCheckBox.setChecked(original != null && original.isPaused());
        pausedCheckBox.setEnabled(canManage);
        // Bug batch 3 #3 (v2.0.8) — explain pause semantics on hover. Missed runs
        // are NOT accumulated while paused: PayoutExecutor skips paused schedules
        // via the isPaused() early-continue, and never invokes recordMissedExecution
        // for them (verified in PayoutExecutor.tick loop).
        pausedCheckBox.setHoverTooltipSupplier(PAUSED_TOOLTIP::getString);

        // Spec B.2 — mode toggle. Checked = dividend (split among shareholders); the
        // target picker row is hidden while checked.
        dividendCheckBox = new CheckBox(MODE_DIVIDEND.getString());
        dividendCheckBox.setChecked(original != null && original.getMode() == PayoutSchedule.Mode.DIVIDEND);
        dividendCheckBox.setEnabled(canManage);
        dividendCheckBox.setHoverTooltipSupplier(MODE_DIVIDEND_TOOLTIP::getString);
        dividendCheckBox.setOnStateChanged(this::applyModeVisibility);

        targetLabel = new Label(TARGET.getString() + ": " + targetDisplay());
        pickTargetButton = new Button(PICK_TARGET.getString(), this::onPickTargetClicked);
        pickTargetButton.setEnabled(canManage);

        historyLabel = new Label(HISTORY.getString());
        totalPaidLabel = new Label(Component.translatable(TOTAL_PAID_KEY, "0.00").getString());
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
        addElement(currencyButton);
        addElement(intervalLabel);
        for (Button b : intervalButtons) addElement(b);
        addElement(customMinutesBox);
        addElement(pausedCheckBox);
        addElement(dividendCheckBox);
        addElement(targetLabel);
        addElement(pickTargetButton);
        addElement(historyLabel);
        addElement(totalPaidLabel);
        addElement(historyListView);
        addElement(saveButton);
        addElement(cancelButton);
        if (deleteButton != null) addElement(deleteButton);

        applyModeVisibility(dividendCheckBox.isChecked());
        applyIntervalSelection();

        // Populate total-paid + last-20 history rows best-effort.
        // Bug D fix (v2.0.8) — mutate on render thread; async completion off-thread
        // races GUI init and CMEs during layout iteration.
        if (original != null) {
            AsyncCompanyManager.getHistoryAsync(original.getScheduleId(), 20).thenAccept(out ->
                    Minecraft.getInstance().tell(() -> {
                        if (out == null) return;
                        totalPaidLabel.setText(Component.translatable(TOTAL_PAID_KEY,
                                MoneyFormat.format(out.totalPaid())).getString());
                        historyListView.removeChilds();
                        for (AsyncCompanyManager.HistoryRowWire row : out.rows()) {
                            historyListView.addChild(new HistoryRowElement(row));
                        }
                    }));
        }
    }

    /** REDESIGN 1 — radio behavior: exactly one interval button "pressed" at a time. */
    private void onIntervalSelected(int idx) {
        if (!canManage) return;
        selectedIntervalIdx = idx;
        applyIntervalSelection();
    }

    private void applyIntervalSelection() {
        for (int i = 0; i < intervalButtons.length; i++) {
            intervalButtons[i].setBackgroundColor(
                    i == selectedIntervalIdx ? RADIO_SELECTED_COLOR : RADIO_IDLE_COLOR);
        }
        // The custom-minutes TextBox only shows while "Custom" is selected.
        customMinutesBox.setEnabled(canManage && selectedIntervalIdx == intervalButtons.length - 1);
    }

    private void applyModeVisibility(boolean dividend) {
        targetLabel.setEnabled(!dividend);
        pickTargetButton.setEnabled(!dividend && canManage);
    }

    private String targetDisplay() {
        if (targetName.isEmpty()) return "-";
        // Spec A.9 — "<player-name> — <bank-account-name>".
        return targetAccountName.isEmpty() ? targetName : targetName + " — " + targetAccountName;
    }

    /** Apply the chosen currency: icon, amount regex (2 decimals for money, integers for items). */
    private void applyCurrency(short newCurrency, String amountText) {
        currencyItem = newCurrency;
        boolean money = isMoneyCurrency();
        // BUG 1 fix — all currencies use fixed-point with 2 decimals.
        amountBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 12, 2));
        if (amountText != null) amountBox.setText(amountText);
        ItemStack stack;
        if (money) {
            stack = net.kroia.banksystem.minecraft.item.BankSystemItems.MONEY.get().getDefaultInstance();
        } else {
            stack = ItemIDManager.getItemStack(new ItemID(newCurrency));
        }
        currencyIcon.setItemStack(stack);
    }

    private static String statusText(int ordinal) {
        return switch (ordinal) {
            case 0 -> STATUS_OK.getString();
            case 1 -> STATUS_INSUFF.getString();
            case 2 -> STATUS_TARGET.getString();
            case 3 -> STATUS_NO_DEPOSIT.getString();
            case 4 -> STATUS_CURRENCY.getString();
            case 5 -> STATUS_PAUSED.getString();
            default -> STATUS_UNKNOWN.getString();
        };
    }

    private static int colorForStatus(int ordinal) {
        return switch (ordinal) {
            case 0 -> COLOR_OK;
            case 1, 4 -> COLOR_INSUFF;
            case 2, 3 -> COLOR_TARGET;
            default -> 0xFFFFFFFF;
        };
    }

    /**
     * Spec A.7/A.8/A.9/B.3 — one history row: item icon (currency), formatted
     * timestamp, amount, snapshot target names, and the typed status text.
     */
    private static class HistoryRowElement extends GuiElement {
        private final ItemView icon;
        private final Label label;

        HistoryRowElement(AsyncCompanyManager.HistoryRowWire row) {
            super();
            setHeight(15);
            boolean money = row.currencyItem() == PayoutSchedule.MONEY_CURRENCY;
            ItemStack stack = money
                    ? net.kroia.banksystem.minecraft.item.BankSystemItems.MONEY.get().getDefaultInstance()
                    : ItemIDManager.getItemStack(new ItemID(row.currencyItem()));
            icon = new ItemView(stack);
            icon.setShowCount(false);
            // BUG 1 fix — fixed-point applies to all currencies.
            String amount = MoneyFormat.format(row.amount());
            String target = row.targetPlayerName().isEmpty() ? "" :
                    (row.targetAccountName().isEmpty() ? row.targetPlayerName()
                            : row.targetPlayerName() + " — " + row.targetAccountName());
            String catchUp = row.typeOrdinal() == 1 ? " [" + TYPE_CATCH_UP.getString() + "]" : "";
            label = new Label(TimeFormat.formatTimestamp(row.time()) + "  " + amount
                    + (target.isEmpty() ? "" : "  " + target)
                    + "  " + statusText(row.statusOrdinal()) + catchUp);
            label.setTextColor(colorForStatus(row.statusOrdinal()));
            addChild(icon);
            addChild(label);
        }

        @Override
        protected void render() {}

        @Override
        protected void layoutChanged() {
            icon.setBounds(0, 0, 14, getHeight());
            label.setBounds(16, 0, Math.max(0, getWidth() - 16), getHeight());
        }
    }

    private long selectedIntervalTicks() {
        int idx = selectedIntervalIdx;
        if (idx >= 0 && idx < INTERVAL_PRESETS.length) return INTERVAL_PRESETS[idx];
        try {
            // Custom is entered in real-time minutes (min 1); storage stays in ticks.
            long minutes = Long.parseLong(customMinutesBox.getText().trim());
            if (minutes < 1L) return 0L;
            return Math.multiplyExact(minutes, TICKS_PER_MINUTE);
        } catch (NumberFormatException | ArithmeticException e) {
            return 0L;
        }
    }

    /** BUG 1 fix (v2.0.8) — fixed-point applies to all currencies (money AND items). */
    private long parsedAmount() {
        return MoneyFormat.parseToRaw(amountBox.getText());
    }

    private void onPickTargetClicked() {
        if (!canManage || dividendCheckBox.isChecked()) return;
        // Spec B.1 — split player + account picker (accounts with DEPOSIT right).
        // Deferred swap (switchScreen) — setScreen inside a click callback CMEs.
        switchScreen(new SplitPlayerAccountPickerPopup(this, companyId, caller,
                (playerUUID, playerName, accountId, accountName) -> {
                    targetUUID = playerUUID;
                    targetName = playerName;
                    targetAccountNr = accountId;
                    targetAccountName = accountName;
                    targetLabel.setText(TARGET.getString() + ": " + targetDisplay());
                }));
    }

    private void onPickCurrencyClicked() {
        if (!canManage) return;
        switchScreen(new ItemBalancePickerPopup(this, companyId, caller,
                picked -> applyCurrency(picked, null)));
    }

    private void onSaveClicked() {
        if (!canManage) return;
        long amount = parsedAmount();
        if (amount <= 0L) return;
        long intervalTicks = selectedIntervalTicks();
        if (intervalTicks <= 0L) return;
        boolean paused = pausedCheckBox.isChecked();
        boolean dividend = dividendCheckBox.isChecked();
        byte mode = (byte) (dividend ? PayoutSchedule.Mode.DIVIDEND.ordinal()
                : PayoutSchedule.Mode.FIXED_PAYOUT.ordinal());
        UUID target = dividend ? null : targetUUID;
        int accountNr = dividend ? PayoutSchedule.NO_TARGET_ACCOUNT : targetAccountNr;

        if (original == null) {
            if (!dividend && targetUUID == null) return;
            AsyncCompanyManager.createPayoutAsync(companyId, target, amount, intervalTicks, 0L, caller,
                            accountNr, mode, currencyItem)
                    // Bug D fix (v2.0.8) — hop to render thread before firing onDirty
                    // (refresh) and swapping screens. Off-thread refresh races GUI init.
                    .thenAccept(out -> Minecraft.getInstance().tell(() -> {
                        if (out != null && out.resultCode() == AsyncCompanyManager.CODE_OK
                                && paused && out.scheduleId() != 0L) {
                            AsyncCompanyManager.pausePayoutAsync(companyId, out.scheduleId(), true, caller);
                        }
                        if (onDirty != null) onDirty.run();
                        closeToParent();
                    }));
        } else {
            AsyncCompanyManager.updatePayoutAsync(companyId, original.getScheduleId(), amount, intervalTicks,
                    caller, target, accountNr, mode, currencyItem);
            if (paused != original.isPaused()) {
                AsyncCompanyManager.pausePayoutAsync(companyId, original.getScheduleId(), paused, caller);
            }
            if (onDirty != null) onDirty.run();
            closeToParent();
        }
    }

    private void onDeleteClicked() {
        if (!canManage || original == null) return;
        // Bug D fix (v2.0.8) — hop to render thread before onDirty/refresh + screen swap.
        AsyncCompanyManager.deletePayoutAsync(companyId, original.getScheduleId(), caller).thenAccept(o ->
                Minecraft.getInstance().tell(() -> {
                    if (onDirty != null) onDirty.run();
                    closeToParent();
                }));
    }

    /**
     * BUG 1 fix (v2.0.8) — always defer the screen swap via {@link #switchScreen}.
     * The previous direct {@code minecraft.setScreen(parent)} crashed with a
     * ConcurrentModificationException when invoked from inside a click callback
     * (ModUtilities GuiElement.init was still iterating the child list).
     */
    private void closeToParent() {
        if (parent != null) {
            switchScreen(parent);
        } else {
            switchScreen(null);
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
        amountBox.setBounds(padding + col, y, col - spacing - 24, 20);
        currencyButton.setBounds(padding + col + (col - spacing - 22), y, 22, 20);
        currencyIcon.setBounds(3, 2, 16, 16);
        y += 25;

        intervalLabel.setBounds(padding, y, col - spacing, 20);
        // BUG 5 fix (v2.0.8) — weighted radio widths: "1 MC day" text overflowed
        // the equal-width slot. Weights 0.9 / 0.9 / 1.4 / 0.8 (sum = 4).
        int radioTotal = col - spacing - 3 * 2;
        float[] radioWeights = { 0.9f, 0.9f, 1.4f, 0.8f };
        int[] radioWs = new int[intervalButtons.length];
        int consumed = 0;
        for (int i = 0; i < intervalButtons.length - 1; i++) {
            radioWs[i] = Math.round(radioTotal * radioWeights[i] / 4.0f);
            consumed += radioWs[i];
        }
        radioWs[intervalButtons.length - 1] = radioTotal - consumed;
        int rx = padding + col;
        for (int i = 0; i < intervalButtons.length; i++) {
            intervalButtons[i].setBounds(rx, y, radioWs[i], 20);
            rx += radioWs[i] + 2;
        }
        y += 25;

        customMinutesBox.setBounds(padding + col, y, col - spacing, 20);
        y += 25;

        pausedCheckBox.setBounds(padding, y, col - spacing, 20);
        dividendCheckBox.setBounds(padding + col, y, col - spacing, 20);
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
