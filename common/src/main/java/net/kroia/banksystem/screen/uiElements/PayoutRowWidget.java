package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.screen.custom.PayoutEditScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.MoneyFormat;
import net.kroia.banksystem.util.TimeFormat;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Task #45a (v2.0.8) — one row in the payouts overview list.
 * <p>
 * Spec A.4/A.9/B.3/B.4 (v2.0.8): columns match the header row of
 * {@code PayoutsOverviewScreen} (Target | Amount | Interval | Next | Status), the
 * target renders the name snapshots ("player — account"), the currency item's icon
 * is drawn at the row start, "Next" decodes to a friendly countdown, every column
 * carries an explanatory tooltip, and a "Pay Missed" button appears when the
 * schedule has accumulated missed executions.
 */
public class PayoutRowWidget extends BankSystemGuiElement {

    /** REDESIGN 4 (v2.0.8) — shared row/header font scale for the schedules list. */
    public static final float ROW_FONT_SCALE = 0.75f;

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".payout_row_widget.";
    private static final Component EDIT = Component.translatable(PREFIX + "edit");
    private static final Component PAUSED = Component.translatable(PREFIX + "paused");
    private static final Component PAY_MISSED = Component.translatable(PREFIX + "pay_missed");
    private static final String NEXT_FMT = PREFIX + "next_run";
    private static final Component DIVIDEND = Component.translatable(PREFIX + "dividend_target");
    private static final Component TT_TARGET = Component.translatable(PREFIX + "tooltip_target");
    private static final Component TT_AMOUNT = Component.translatable(PREFIX + "tooltip_amount");
    private static final Component TT_INTERVAL = Component.translatable(PREFIX + "tooltip_interval");
    private static final Component TT_NEXT = Component.translatable(PREFIX + "tooltip_next");
    private static final Component TT_STATUS = Component.translatable(PREFIX + "tooltip_status");
    private static final String TT_PAY_MISSED_KEY = PREFIX + "tooltip_pay_missed";
    private static final Component STATUS_OK = Component.translatable(PREFIX + "status_ok");
    private static final Component STATUS_INSUFF = Component.translatable(PREFIX + "status_insufficient");
    private static final Component STATUS_TARGET = Component.translatable(PREFIX + "status_target");
    private static final Component STATUS_NO_DEPOSIT = Component.translatable(PREFIX + "status_no_deposit");
    private static final Component STATUS_CURRENCY = Component.translatable(PREFIX + "status_currency");

    private final ItemView currencyIcon;
    private final Label nameLabel;
    private final Label amountLabel;
    private final Label intervalLabel;
    private final Label nextRunLabel;
    private final Label statusLabel;
    private final Button payMissedButton;
    private final Button editButton;

    private final int companyId;
    private final PayoutSchedule schedule;
    private final UUID viewer;
    private final boolean canManage;
    private final GuiScreen parentScreen;
    private final Runnable onDirty;
    /** Master-observed tick at the time this row was rendered; anchor for the
     *  local-frame countdown recomputation (see {@link #computeNextText()}). */
    private final long serverNowTickAtRefresh;
    /** Client world tick sampled when the row was constructed — pairs with
     *  {@link #serverNowTickAtRefresh} so the countdown advances against the
     *  same clock the schedule was authored in (payoutTickCounter, NOT world time). */
    private final long clientTickAtRefresh;
    /** Cache to avoid rebuilding the label string every frame. */
    private String lastNextText = "";
    /** Bug batch 3 #1 (v2.0.8) — one-shot refresh trigger when the local countdown
     *  crosses zero; without this the row stays frozen on the stale server tick
     *  snapshot after the schedule fires master-side. */
    private boolean refreshFiredAtZero = false;

    public PayoutRowWidget(int companyId, PayoutSchedule schedule, UUID viewer,
                           boolean canManage, GuiScreen parentScreen, Runnable onDirty,
                           long serverNowTick) {
        super();
        setHeight(20);
        this.companyId = companyId;
        this.schedule = schedule;
        this.viewer = viewer;
        this.canManage = canManage;
        this.parentScreen = parentScreen;
        this.onDirty = onDirty;

        boolean money = schedule.isMoneyCurrency();
        ItemStack currencyStack = money
                ? net.kroia.banksystem.minecraft.item.BankSystemItems.MONEY.get().getDefaultInstance()
                : ItemIDManager.getItemStack(new ItemID(schedule.getCurrencyItem()));
        currencyIcon = new ItemView(currencyStack);
        currencyIcon.setShowCount(false);

        nameLabel = new Label(targetDisplay(schedule));
        nameLabel.setHoverTooltipSupplier(TT_TARGET::getString);
        // BUG 1 fix (v2.0.8) — fixed-point applies to ALL currencies (money AND items).
        amountLabel = new Label(MoneyFormat.format(schedule.getAmount()));
        amountLabel.setHoverTooltipSupplier(TT_AMOUNT::getString);
        intervalLabel = new Label(schedule.getMode() == PayoutSchedule.Mode.ONE_TIME
                ? Component.translatable(PREFIX + "one_time").getString()
                : TimeFormat.formatTickDuration(schedule.getIntervalTicks()));
        intervalLabel.setHoverTooltipSupplier(TT_INTERVAL::getString);
        // Bug C fix (v2.0.8) — cache the master-observed "now" so the countdown can
        // be recomputed each frame from the client's world tick without waiting on
        // another RPC. Falls back to Minecraft.level.getGameTime() when master
        // hasn't observed a tick yet (getLastObservedTick == 0) — otherwise the
        // "Next" column shows the RAW absolute tick (e.g. "7m" for a 1m interval).
        this.serverNowTickAtRefresh = serverNowTick;
        long ct = 0L;
        try {
            var mc = Minecraft.getInstance();
            if (mc.level != null) ct = mc.level.getGameTime();
        } catch (Throwable ignored) { /* not in-world */ }
        this.clientTickAtRefresh = ct;
        nextRunLabel = new Label(computeNextText());
        nextRunLabel.setHoverTooltipSupplier(TT_NEXT::getString);
        statusLabel = new Label("");
        statusLabel.setHoverTooltipSupplier(TT_STATUS::getString);

        // REDESIGN 4 — smaller row font so more text fits per column.
        for (Label l : new Label[]{nameLabel, amountLabel, intervalLabel, nextRunLabel, statusLabel}) {
            l.setTextFontScale(ROW_FONT_SCALE);
        }

        payMissedButton = new Button(PAY_MISSED.getString(), this::onPayMissedClicked);
        payMissedButton.setEnabled(canManage && schedule.getMissedCount() > 0);
        // BUG 1 fix — fixed-point applies to all currencies.
        payMissedButton.setHoverTooltipSupplier(() -> Component.translatable(TT_PAY_MISSED_KEY,
                String.valueOf(schedule.getMissedCount()),
                MoneyFormat.format(schedule.getMissedAmount())).getString());

        editButton = new Button(EDIT.getString(), this::onEditClicked);

        addChild(currencyIcon);
        addChild(nameLabel);
        addChild(amountLabel);
        addChild(intervalLabel);
        addChild(nextRunLabel);
        addChild(statusLabel);
        if (schedule.getMissedCount() > 0) addChild(payMissedButton);
        addChild(editButton);

        // Best-effort last-status lookup (may fail on slave — that's fine, label just stays blank).
        // Bug D fix (v2.0.8) — hop to render thread; mutating a Label off-thread races
        // with GUI init/layout iteration (VerticalListView.updateElementPositions).
        try {
            AsyncCompanyManager.getHistoryAsync(schedule.getScheduleId(), 1).thenAccept(out ->
                    Minecraft.getInstance().tell(() -> {
                        if (out != null && !out.rows().isEmpty()) {
                            int s = out.rows().get(0).statusOrdinal();
                            statusLabel.setText(formatStatus(s));
                        }
                    }));
        } catch (Throwable ignored) { /* offline / test env */ }
    }

    /** Spec A.9 — "player — account" from the write-time snapshots; dividend rows show a fixed label. */
    private static String targetDisplay(PayoutSchedule s) {
        if (s.getMode() == PayoutSchedule.Mode.DIVIDEND) return DIVIDEND.getString();
        String player = s.getTargetPlayerName();
        if (player.isEmpty()) {
            UUID uuid = s.getTargetUUID();
            if (uuid == null) return "-";
            String str = uuid.toString();
            player = str.length() > 8 ? str.substring(0, 8) : str;
        }
        return s.getTargetAccountName().isEmpty() ? player
                : "Player: \"" + player + "\", Bank: \"" + s.getTargetAccountName() + "\"";
    }

    /** Spec A.8 — translated per-reason status text (was a cryptic "!?" glyph). */
    private static String formatStatus(int ordinal) {
        return switch (ordinal) {
            case 0 -> STATUS_OK.getString();
            case 1 -> STATUS_INSUFF.getString();
            case 2 -> STATUS_TARGET.getString();
            case 3 -> STATUS_NO_DEPOSIT.getString();
            case 4 -> STATUS_CURRENCY.getString();
            default -> "";
        };
    }

    private void onPayMissedClicked() {
        if (!canManage || schedule.getMissedCount() <= 0) return;
        // BUG 1 fix — deferred swap; direct setScreen inside a click callback CMEs.
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(
                new PayMissedPopupScreen(parentScreen, companyId, schedule, viewer, onDirty));
    }

    private void onEditClicked() {
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(
                new PayoutEditScreen(parentScreen, companyId, schedule, viewer, canManage, onDirty));
    }

    /** Bug 2 fix (v2.0.8) — build the "Next" cell text from the ticks elapsed on the
     *  CLIENT since the row was refreshed, applied to the master-observed delta.
     *  <p>Root cause of the previous "0s" bug: {@code nextRunTick} lives in the
     *  master's {@code payoutTickCounter} clock (a per-session counter that starts
     *  at 0), NOT the persisted world-time returned by {@code level.getGameTime()}.
     *  Subtracting one from the other yielded a large negative that clamped to 0. */
    private String computeNextText() {
        if (schedule.isPaused()) return PAUSED.getString();
        long baseDelta = Math.max(0L, schedule.getNextRunTick() - serverNowTickAtRefresh);
        long elapsedSinceRefresh = 0L;
        try {
            var mc = Minecraft.getInstance();
            if (mc.level != null && clientTickAtRefresh > 0L) {
                elapsedSinceRefresh = Math.max(0L, mc.level.getGameTime() - clientTickAtRefresh);
            }
        } catch (Throwable ignored) { /* not in-world */ }
        long delta = Math.max(0L, baseDelta - elapsedSinceRefresh);
        return Component.translatable(NEXT_FMT, TimeFormat.formatTickDuration(delta)).getString();
    }

    @Override
    protected void render() {
        // Bug C fix (v2.0.8) — live countdown; refreshes each frame with no RPCs.
        String next = computeNextText();
        if (!next.equals(lastNextText)) {
            lastNextText = next;
            nextRunLabel.setText(next);
        }
        // Bug batch 3 #1 (v2.0.8) — when the row's cached snapshot elapses, master
        // has already fired (or refused) the schedule; re-fetch once so nextRunTick /
        // missedCount / status stay live. Debounced by the boolean so we don't spam
        // listSchedulesAsync every frame while the label reads "0s".
        if (!refreshFiredAtZero && !schedule.isPaused() && onDirty != null) {
            long baseDelta = Math.max(0L, schedule.getNextRunTick() - serverNowTickAtRefresh);
            long elapsed = 0L;
            try {
                var mc = Minecraft.getInstance();
                if (mc.level != null && clientTickAtRefresh > 0L) {
                    elapsed = Math.max(0L, mc.level.getGameTime() - clientTickAtRefresh);
                }
            } catch (Throwable ignored) { }
            if (elapsed >= baseDelta) {
                refreshFiredAtZero = true;
                try { onDirty.run(); } catch (Throwable ignored) { }
            }
        }
    }

    @Override
    protected void layoutChanged() {
        int width = getWidth();
        int height = getHeight();
        int editW = getTextWidth(EDIT.getString()) + spacing * 2;
        // BUG 3 fix (v2.0.8) — ALWAYS reserve the Pay-Missed button slot (even when
        // the button is absent) so column widths don't reshuffle when a schedule
        // starts failing. Slot is left empty when missedCount == 0.
        int missedW = getTextWidth(PAY_MISSED.getString()) + spacing * 2;
        int iconW = 20;
        int rest = Math.max(0, width - editW - missedW - iconW);
        // BUG 3 rebalance — Target 35 / Amount 12 / Interval 12 / Next 15 / Status 26.
        int targetW = rest * 35 / 100;
        int amountW = rest * 12 / 100;
        int intervalW = rest * 12 / 100;
        int nextW = rest * 15 / 100;
        int statusW = rest - targetW - amountW - intervalW - nextW;
        currencyIcon.setBounds(2, (height - 16) / 2, 16, 16);
        int x = iconW;
        nameLabel.setBounds(x, 0, targetW, height); x += targetW;
        amountLabel.setBounds(x, 0, amountW, height); x += amountW;
        intervalLabel.setBounds(x, 0, intervalW, height); x += intervalW;
        nextRunLabel.setBounds(x, 0, nextW, height); x += nextW;
        statusLabel.setBounds(x, 0, statusW, height); x += statusW;
        if (schedule.getMissedCount() > 0) payMissedButton.setBounds(x, 0, missedW, height);
        editButton.setBounds(x + missedW, 0, editW, height);
    }
}
