package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spec B.1 (v2.0.8) — split-screen payout target picker. Left pane: all known
 * players; right pane: the selected player's bank accounts that grant the player
 * DEPOSIT right, fetched lazily via the {@code LIST_PLAYER_ACCOUNTS_WITH_FILTER}
 * ARRS function (MANAGE-gated on master when caller != subject). Confirm returns
 * the {@code (playerUUID, playerName, accountId, accountName)} tuple.
 */
public class SplitPlayerAccountPickerPopup extends BankSystemGuiScreen {

    /** Callback for the confirmed selection. */
    @FunctionalInterface
    public interface OnPick {
        void picked(UUID playerUUID, String playerName, int accountId, String accountName);
    }

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".split_account_picker.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final Component PLAYERS_HEADER = Component.translatable(PREFIX + "players");
    private static final String ACCOUNTS_HEADER_KEY = PREFIX + "accounts_of";
    private static final Component ACCOUNTS_HEADER_NONE = Component.translatable(PREFIX + "accounts");
    private static final Component NO_ACCOUNTS = Component.translatable(PREFIX + "no_accounts");
    private static final Component NO_PLAYERS = Component.translatable(PREFIX + "no_players");
    private static final Component LOADING = Component.translatable(PREFIX + "loading");
    private static final Component CONFIRM = Component.translatable(PREFIX + "confirm");
    private static final Component CANCEL = Component.translatable(PREFIX + "cancel");
    private static final Component DEPOSIT_OK = Component.translatable(PREFIX + "deposit_ok");

    private final GuiScreen parent;
    private final int companyId;
    private final UUID caller;
    private final OnPick onPick;

    private final Frame frame = new Frame();
    private final Label titleLabel;
    private final Label playersHeader;
    private final Label accountsHeader;
    private final VerticalListView playerList;
    private final VerticalListView accountList;
    private final Button confirmButton;
    private final Button cancelButton;

    private UUID selectedPlayer;
    private String selectedPlayerName = "";
    private int selectedAccountId = -1;
    private String selectedAccountName = "";

    private int frameWidth = 460;
    private int frameHeight = 260;

    public SplitPlayerAccountPickerPopup(GuiScreen parent, int companyId, UUID caller, OnPick onPick) {
        super(TITLE);
        this.parent = parent;
        this.companyId = companyId;
        this.caller = caller;
        this.onPick = onPick;

        titleLabel = new Label(TITLE.getString());
        playersHeader = new Label(PLAYERS_HEADER.getString());
        accountsHeader = new Label(ACCOUNTS_HEADER_NONE.getString());
        playerList = new VerticalListView();
        accountList = new VerticalListView();
        for (VerticalListView list : new VerticalListView[]{playerList, accountList}) {
            LayoutGrid l = new LayoutGrid();
            l.columns = 1; l.rows = 0; l.spacing = 2; l.padding = 2;
            l.stretchX = true; l.stretchY = false;
            l.alignment = GuiElement.Alignment.TOP;
            list.setLayout(l);
        }

        confirmButton = new Button(CONFIRM.getString(), this::onConfirm);
        confirmButton.setEnabled(false);
        // BUG 1 fix — deferred swap; direct setScreen inside a click callback CMEs.
        cancelButton = new Button(CANCEL.getString(), () -> switchScreen(parent));

        // Sync seed + populate list BEFORE attaching it to the frame — mirrors the
        // working PlayerPickerPopup pattern; VerticalListView does not always
        // re-layout when children are added after attachment.
        loadPlayers();

        addElement(frame);
        frame.addChild(titleLabel);
        frame.addChild(playersHeader);
        frame.addChild(accountsHeader);
        frame.addChild(playerList);
        frame.addChild(accountList);
        frame.addChild(confirmButton);
        frame.addChild(cancelButton);
    }

    /**
     * BUG 2 fix (v2.0.8) — the popup previously relied solely on the async
     * bank-manager user map; when that response was empty/late the left pane
     * stayed blank. Seed the list synchronously from the currently-online
     * players (same source as the working {@link PlayerPickerPopup}), then
     * merge in all additional known bank users once the async data arrives.
     */
    private final java.util.Map<UUID, String> knownPlayers = new java.util.LinkedHashMap<>();

    private void loadPlayers() {
        // Synchronous seed: everyone currently online (always available client-side).
        // BUG batch 4 (v2.0.8) — hard invariant: opening this popup while in-world
        // MUST always show at least the local player. Seed the caller UUID first
        // (guaranteed non-null on the client path — the popup requires MANAGE) and
        // then the local player, so we never fall back to "no players" when the
        // client-side player entity list has not populated yet.
        try {
            var mc = Minecraft.getInstance();
            String callerName = "";
            if (mc.player != null) callerName = mc.player.getDisplayName().getString();
            if (caller != null) {
                knownPlayers.put(caller, callerName.isEmpty() ? caller.toString().substring(0, 8) : callerName);
            }
            if (mc.player != null) {
                knownPlayers.putIfAbsent(mc.player.getUUID(), mc.player.getDisplayName().getString());
            }
            if (mc.level != null) {
                for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                    knownPlayers.putIfAbsent(p.getUUID(), p.getDisplayName().getString());
                }
            }
        } catch (Throwable t) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                BACKEND_INSTANCES.LOGGER.warn("[SplitPlayerAccountPickerPopup] sync seed failed: " + t);
            }
        }
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[SplitPlayerAccountPickerPopup] sync seed complete: "
                    + knownPlayers.size() + " player(s) — caller=" + caller);
        }
        rebuildPlayerList();

        // Async merge: all users known to the master bank manager (covers offline
        // players — userMap is the persistent registry of every player who has
        // ever been introduced to the bank system).
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getBankManagerDataAsync()
                .whenComplete((data, err) -> Minecraft.getInstance().execute(() -> {
                    if (err != null) {
                        if (BACKEND_INSTANCES.LOGGER != null) {
                            BACKEND_INSTANCES.LOGGER.warn(
                                    "[SplitPlayerAccountPickerPopup] async merge failed: " + err);
                        }
                        return;
                    }
                    if (data == null || data.userMapData() == null
                            || data.userMapData().userMap() == null) {
                        if (BACKEND_INSTANCES.LOGGER != null) {
                            BACKEND_INSTANCES.LOGGER.info(
                                    "[SplitPlayerAccountPickerPopup] async merge: null userMap");
                        }
                        return;
                    }
                    boolean changed = false;
                    int scanned = 0;
                    for (var user : data.userMapData().userMap().values()) {
                        scanned++;
                        if (user == null || user.userUUID() == null) continue;
                        String label = user.userName() != null && !user.userName().isEmpty()
                                ? user.userName() : user.userUUID().toString().substring(0, 8);
                        if (knownPlayers.putIfAbsent(user.userUUID(), label) == null) {
                            changed = true;
                        }
                    }
                    if (BACKEND_INSTANCES.LOGGER != null) {
                        BACKEND_INSTANCES.LOGGER.info(
                                "[SplitPlayerAccountPickerPopup] async merge: scanned="
                                        + scanned + " total=" + knownPlayers.size()
                                        + " changed=" + changed);
                    }
                    if (changed) rebuildPlayerList();
                }));
    }

    private void rebuildPlayerList() {
        playerList.removeChilds();
        if (knownPlayers.isEmpty()) {
            Label empty = new Label(NO_PLAYERS.getString());
            empty.setHeight(20);
            playerList.addChild(empty);
        } else {
            for (var entry : knownPlayers.entrySet()) {
                UUID uuid = entry.getKey();
                String name = entry.getValue();
                Button row = new Button(name, () -> onPlayerSelected(uuid, name));
                // BUG (v2.0.8) — Button(text, callback) default bounds are 0x0.
                // LayoutGrid.stretchY=false leaves the height untouched, so rows
                // rendered as invisible zero-height slivers. Explicitly size the
                // row (matches CurrencyRow in ItemBalancePickerPopup).
                row.setHeight(20);
                playerList.addChild(row);
            }
        }
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info(
                    "[SplitPlayerAccountPickerPopup] rebuildPlayerList: rendering "
                            + knownPlayers.size() + " player row(s)");
        }
        updateLayout(getGui());
    }

    private void onPlayerSelected(UUID uuid, String name) {
        selectedPlayer = uuid;
        selectedPlayerName = name;
        selectedAccountId = -1;
        selectedAccountName = "";
        confirmButton.setEnabled(false);
        accountsHeader.setText(Component.translatable(ACCOUNTS_HEADER_KEY, name).getString());
        accountList.removeChilds();
        Label loading = new Label(LOADING.getString());
        loading.setHeight(20);
        accountList.addChild(loading);
        AsyncCompanyManager.listPlayerAccountsWithFilterAsync(companyId, uuid,
                (byte) BankPermission.DEPOSIT.getValue(), caller).thenAccept(out -> {
            Minecraft.getInstance().execute(() -> {
                if (selectedPlayer == null || !selectedPlayer.equals(uuid)) return; // stale
                accountList.removeChilds();
                List<AsyncCompanyManager.AccountEntry> accounts =
                        out != null && out.resultCode() == AsyncCompanyManager.CODE_OK
                                ? out.accounts() : List.of();
                if (accounts.isEmpty()) {
                    Label empty = new Label(NO_ACCOUNTS.getString());
                    empty.setHeight(20);
                    accountList.addChild(empty);
                } else {
                    for (AsyncCompanyManager.AccountEntry entry : new ArrayList<>(accounts)) {
                        String label = entry.accountName() + "  (" + DEPOSIT_OK.getString() + ")";
                        Button row = new Button(label, () -> {
                            selectedAccountId = entry.accountId();
                            selectedAccountName = entry.accountName();
                            confirmButton.setEnabled(true);
                        });
                        // See rebuildPlayerList for the 0-height rationale.
                        row.setHeight(20);
                        accountList.addChild(row);
                    }
                }
                updateLayout(getGui());
            });
        });
    }

    private void onConfirm() {
        if (selectedPlayer == null || selectedAccountId < 0) return;
        switchScreen(parent);
        if (onPick != null) {
            onPick.picked(selectedPlayer, selectedPlayerName, selectedAccountId, selectedAccountName);
        }
    }

    @Override
    public void onClose() {
        // BUG 1 fix — deferred swap; direct setScreen inside a click callback CMEs.
        switchScreen(parent);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();
        frame.setBounds((width - frameWidth) / 2, (height - frameHeight) / 2, frameWidth, frameHeight);
        int p = 5;
        int colW = (frame.getWidth() - 3 * p) / 2;
        titleLabel.setBounds(p, p, frame.getWidth() - 2 * p, 15);
        playersHeader.setBounds(p, titleLabel.getBottom() + 2, colW, 15);
        accountsHeader.setBounds(p * 2 + colW, titleLabel.getBottom() + 2, colW, 15);
        int listTop = playersHeader.getBottom() + 2;
        int listBottom = frame.getHeight() - p - 20 - 5;
        playerList.setBounds(p, listTop, colW, listBottom - listTop);
        accountList.setBounds(p * 2 + colW, listTop, colW, listBottom - listTop);
        cancelButton.setBounds(p, frame.getHeight() - p - 20, 80, 20);
        confirmButton.setBounds(frame.getWidth() - p - 80, frame.getHeight() - p - 20, 80, 20);
    }
}
