package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.screen.custom.PayoutEditScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Task #45a (v2.0.8) — one row in the payouts overview list. Mirrors {@link BankUserWidget}'s
 * shape: static label columns + trailing Edit button. Non-manage viewers see the Edit button
 * disabled (opens read-only, delete hidden).
 */
public class PayoutRowWidget extends BankSystemGuiElement {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".payout_row_widget.";
    private static final Component EDIT = Component.translatable(PREFIX + "edit");
    private static final Component PAUSED = Component.translatable(PREFIX + "paused");
    private static final String NEXT_FMT = "gui." + BankSystemMod.MOD_ID + ".payout_row_widget.next_run";

    private final Label nameLabel;
    private final Label amountLabel;
    private final Label intervalLabel;
    private final Label nextRunLabel;
    private final Label statusLabel;
    private final Button editButton;

    private final int companyId;
    private final PayoutSchedule schedule;
    private final UUID viewer;
    private final boolean canManage;
    private final GuiScreen parentScreen;
    private final Runnable onDirty;

    public PayoutRowWidget(int companyId, PayoutSchedule schedule, UUID viewer,
                           boolean canManage, GuiScreen parentScreen, Runnable onDirty) {
        super();
        setHeight(20);
        this.companyId = companyId;
        this.schedule = schedule;
        this.viewer = viewer;
        this.canManage = canManage;
        this.parentScreen = parentScreen;
        this.onDirty = onDirty;

        nameLabel = new Label(resolveName(schedule.getTargetUUID()));
        amountLabel = new Label(String.valueOf(schedule.getAmount()));
        intervalLabel = new Label(formatInterval(schedule.getIntervalTicks()));
        nextRunLabel = new Label(schedule.isPaused()
                ? PAUSED.getString()
                : Component.translatable(NEXT_FMT, formatTicks(schedule.getNextRunTick())).getString());
        statusLabel = new Label("");
        editButton = new Button(EDIT.getString(), this::onEditClicked);

        addChild(nameLabel);
        addChild(amountLabel);
        addChild(intervalLabel);
        addChild(nextRunLabel);
        addChild(statusLabel);
        addChild(editButton);

        // Best-effort last-status lookup (may fail on slave — that's fine, label just stays blank).
        try {
            AsyncCompanyManager.getHistoryAsync(schedule.getScheduleId(), 1).thenAccept(out -> {
                if (out != null && !out.rows().isEmpty()) {
                    int s = out.rows().get(0).statusOrdinal();
                    statusLabel.setText(formatStatus(s));
                }
            });
        } catch (Throwable ignored) { /* offline / test env */ }
    }

    private static String resolveName(UUID uuid) {
        if (uuid == null) return "-";
        // Deviation from spec: no sync user-name cache is exposed on IClientBankManager, so we render
        // a truncated UUID prefix. Future improvement — populate an async name cache on layout.
        String s = uuid.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String formatInterval(long ticks) {
        if (ticks == 1200L) return "1m";
        if (ticks == 72000L) return "1h";
        if (ticks == 1728000L) return "1d";
        return ticks + "t";
    }

    private static String formatTicks(long ticks) {
        return ticks + "t";
    }

    private static String formatStatus(int ordinal) {
        return switch (ordinal) {
            case 0 -> "OK";
            case 1 -> "!$";
            case 2 -> "!?";
            default -> "";
        };
    }

    private void onEditClicked() {
        PayoutEditScreen edit = new PayoutEditScreen(parentScreen, companyId, schedule, viewer, canManage, onDirty);
        Minecraft.getInstance().setScreen(edit);
    }

    @Override
    protected void render() {}

    @Override
    protected void layoutChanged() {
        int width = getWidth();
        int height = getHeight();
        int btnW = getTextWidth(EDIT.getString()) + spacing * 2;
        int rest = Math.max(0, width - btnW);
        int col = rest / 5;
        nameLabel.setBounds(0, 0, col, height);
        amountLabel.setBounds(col, 0, col, height);
        intervalLabel.setBounds(col * 2, 0, col, height);
        nextRunLabel.setBounds(col * 3, 0, col, height);
        statusLabel.setBounds(col * 4, 0, rest - col * 4, height);
        editButton.setBounds(rest, 0, btnW, height);
    }
}
