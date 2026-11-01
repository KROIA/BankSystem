package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.networking.currency.BindExternalAccountRequest;
import net.kroia.banksystem.networking.currency.ListBindableAccountsRequest;
import net.kroia.banksystem.networking.currency.ListBindingsForAccountRequest;
import net.kroia.banksystem.networking.currency.ListCurrencyProvidersRequest;
import net.kroia.banksystem.networking.currency.UnbindExternalAccountRequest;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.InfoPopupScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Currency-binding management screen (Task #33 Stage 3, v2.0.5).
 * <p>
 * Reached from {@link BankAccountManagementScreen} via a "Bindings" button. Shows one row per
 * {@code IServerBank} slot on the account, letting the manager bind each slot to an external
 * currency-mod account (Numismatics, Lightman's, ...) or unbind an existing binding.
 * <p>
 * All server writes go through {@link BindExternalAccountRequest} /
 * {@link UnbindExternalAccountRequest}; the master enforces the same MANAGE-permission +
 * untrusted-slave gate that the bind service itself enforces. Non-MANAGE viewers see the screen
 * in read-only mode: bind/unbind buttons are grayed out via {@code setClickable(false)} + a hover
 * tooltip explaining the missing right (matching the FR-002 discipline).
 * <p>
 * <b>Client staleness note.</b> Bound-slot balances change externally without triggering the
 * BankSystem change stream (a Numismatics/LC deposit doesn't call {@code hasChanges()}). The
 * screen mitigates this with a manual Refresh button. A push-based invalidation path is a
 * Task #34/#35 polish item — see the Stage 3 spec, Part D.
 */
public class BankAccountBindingsScreen extends BankSystemGuiScreen {

    // ---------------------------------------------------------------------------------------
    // Translation keys
    // ---------------------------------------------------------------------------------------
    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".bank_account_bindings_screen.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final String KEY_TITLE = PREFIX + "title";
    private static final String KEY_NOT_BOUND = PREFIX + "not_bound";
    private static final String KEY_BIND_BUTTON = PREFIX + "bind_button";
    private static final String KEY_UNBIND_BUTTON = PREFIX + "unbind_button";
    private static final String KEY_PICK_PROVIDER = PREFIX + "pick_provider";
    private static final String KEY_PICK_ACCOUNT = PREFIX + "pick_account";
    private static final String KEY_CONFIRM_BIND_TITLE = PREFIX + "confirm_bind_title";
    private static final String KEY_CONFIRM_BIND_BODY = PREFIX + "confirm_bind_body";
    private static final String KEY_UNBIND_DIALOG_TITLE = PREFIX + "unbind_dialog.title";
    private static final String KEY_UNBIND_DIALOG_MESSAGE = PREFIX + "unbind_dialog.message";
    private static final String KEY_UNBIND_DIALOG_KEEP_ON_BANKSYSTEM = PREFIX + "unbind_dialog.keep_on_banksystem";
    private static final String KEY_UNBIND_DIALOG_KEEP_ON_PROVIDER = PREFIX + "unbind_dialog.keep_on_provider";
    private static final String KEY_UNBIND_DIALOG_KEEP_ON_PROVIDER_WARNING = PREFIX + "unbind_dialog.keep_on_provider.warning";
    private static final String KEY_UNBIND_DIALOG_CANCEL = PREFIX + "unbind_dialog.cancel";
    private static final String KEY_BIND_ERROR_TITLE = PREFIX + "bind_error.title";
    private static final String KEY_BIND_ERROR_OVERFLOW = PREFIX + "bind_error.overflow";
    private static final String KEY_BIND_ERROR_ITEM_MISMATCH = PREFIX + "bind_error.item_mismatch";
    private static final String KEY_UNBIND_ERROR_TITLE = PREFIX + "unbind_error.title";
    private static final String KEY_UNBIND_ERROR_OVERFLOW = PREFIX + "unbind_error.overflow";
    private static final String KEY_UNBIND_ERROR_GENERIC = PREFIX + "unbind_error.generic";
    private static final String KEY_NO_PROVIDERS = PREFIX + "no_providers";
    private static final String KEY_PROVIDER_UNAVAILABLE = PREFIX + "provider_unavailable";
    private static final String KEY_REQUIRES_MANAGE = PREFIX + "requires_manage";
    private static final String KEY_SHARED_BADGE = PREFIX + "shared_badge";
    private static final String KEY_REFRESH = PREFIX + "refresh";
    private static final String KEY_STATUS_SUCCESS = PREFIX + "status.success";
    private static final String KEY_STATUS_INVALID_ACCOUNT = PREFIX + "status.invalid_account";
    private static final String KEY_STATUS_SHARED_MISMATCH = PREFIX + "status.shared_mismatch";
    private static final String KEY_STATUS_INVALID_REQUEST = PREFIX + "status.invalid_request";
    private static final String KEY_STATUS_EXTERNAL_UNAVAILABLE = PREFIX + "status.external_unavailable";
    private static final String KEY_STATUS_NO_MASTER = PREFIX + "status.no_master";
    private static final String KEY_STATUS_GENERIC_FAILURE = PREFIX + "status.generic_failure";

    private static final int GRAY_TEXT_COLOR = 0xFF808080;

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------
    private final GuiScreen parent;
    private final int accountNumber;
    private final boolean isAdminMode;

    private @org.jetbrains.annotations.Nullable BankAccountData accountData;
    private final Map<ItemID, ExternalAccountRef> activeBindings = new HashMap<>();
    /** Cached provider list from the master. Populated on open + refresh. */
    private final List<ListCurrencyProvidersRequest.ProviderInfo> availableProviders = new ArrayList<>();
    private boolean canManage = false;
    private static boolean screenIsOpen = false;

    // ---------------------------------------------------------------------------------------
    // Widgets
    // ---------------------------------------------------------------------------------------
    private Label titleLabel;
    private Label subtitleLabel;
    private CloseButton closeButton;
    private Button refreshButton;
    private Label emptyLabel;
    private ListView rowListView;

    private final Map<ItemID, BindingRow> rowWidgets = new HashMap<>();

    public BankAccountBindingsScreen(GuiScreen parent, int accountNumber, boolean isAdminMode) {
        super(TITLE, parent);
        this.parent = parent;
        this.accountNumber = accountNumber;
        this.isAdminMode = isAdminMode;

        titleLabel = new Label("");
        titleLabel.setAlignment(GuiElement.Alignment.CENTER);
        addElement(titleLabel);

        subtitleLabel = new Label("");
        subtitleLabel.setAlignment(GuiElement.Alignment.CENTER);
        addElement(subtitleLabel);

        closeButton = new CloseButton(this::onClose);
        closeButton.setBackgroundColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);
        addElement(closeButton);

        refreshButton = new Button(Component.translatable(KEY_REFRESH).getString(), this::refreshAll);
        addElement(refreshButton);

        rowListView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        rowListView.setLayout(layout);
        addElement(rowListView);

        emptyLabel = new Label(Component.translatable(KEY_NO_PROVIDERS).getString());
        emptyLabel.setAlignment(GuiElement.Alignment.CENTER);
        emptyLabel.setTextColor(GRAY_TEXT_COLOR);
        emptyLabel.setEnabled(false);
        addElement(emptyLabel);
    }

    public static void openScreen(GuiScreen parent, int accountNumber, boolean isAdminMode) {
        screenIsOpen = true;
        BankAccountBindingsScreen screen = new BankAccountBindingsScreen(parent, accountNumber, isAdminMode);
        Minecraft.getInstance().setScreen(screen);
        screen.refreshAll();
    }

    // ---------------------------------------------------------------------------------------
    // Data loading
    // ---------------------------------------------------------------------------------------

    /**
     * Kicks off a full refresh cycle: account data + binding list + provider list. Called on open
     * and via the Refresh button, and again after any bind/unbind action.
     */
    private void refreshAll() {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER
                .getBankAccountDataAsync(accountNumber)
                .thenAccept(this::onAccountDataReceived);
        ListCurrencyProvidersRequest.sendRequest()
                .thenAccept(this::onProvidersReceived);
        ListBindingsForAccountRequest.sendRequest(accountNumber)
                .thenAccept(this::onBindingsReceived);
    }

    private void onAccountDataReceived(@org.jetbrains.annotations.Nullable BankAccountData data) {
        if (!screenIsOpen) return;
        this.accountData = data;
        if (data == null) {
            titleLabel.setText(Component.translatable(KEY_TITLE).getString().replace("{account}", "?"));
            subtitleLabel.setText("");
            canManage = false;
            rowListView.removeChilds();
            rowWidgets.clear();
            return;
        }
        UUID me = getThisPlayerUUID();
        canManage = isAdminMode || data.hasPermission(me, BankPermission.MANAGE);
        titleLabel.setText(Component.translatable(KEY_TITLE).getString()
                .replace("{account}", data.accountName != null ? data.accountName : ""));
        subtitleLabel.setText("#" + data.accountNumber);
        rebuildRows();
    }

    private void onProvidersReceived(List<ListCurrencyProvidersRequest.ProviderInfo> providers) {
        if (!screenIsOpen) return;
        availableProviders.clear();
        if (providers != null) availableProviders.addAll(providers);
        // No-providers empty state visibility is decided in rebuildRows().
        rebuildRows();
    }

    private void onBindingsReceived(List<ListBindingsForAccountRequest.BindingEntry> bindings) {
        if (!screenIsOpen) return;
        activeBindings.clear();
        if (bindings != null) {
            for (ListBindingsForAccountRequest.BindingEntry entry : bindings) {
                activeBindings.put(entry.itemId(), entry.ref());
            }
        }
        rebuildRows();
    }

    /**
     * Rebuilds the visible per-slot rows from {@link #accountData} + {@link #activeBindings}.
     * Called after every data-refresh callback. Row widgets are re-created wholesale rather than
     * patched — the row set is tiny (a handful of slots) so the simpler approach beats a diff.
     */
    private void rebuildRows() {
        rowListView.removeChilds();
        rowWidgets.clear();
        if (accountData == null) return;
        boolean hasAvailableProvider = !availableProviders.isEmpty();
        emptyLabel.setEnabled(!hasAvailableProvider);
        for (Map.Entry<ItemID, BankData> entry : accountData.bankData.entrySet()) {
            ItemID itemId = entry.getKey();
            BankData bank = entry.getValue();
            if (bank == null) continue;
            // Task #24: skip slots the client can't resolve locally (mod present on master but
            // not here). Consistent with BankAccountManagementScreen's own display filter.
            if (!ItemIDManager.isResolvableOnThisServer(itemId)) continue;
            ExternalAccountRef ref = activeBindings.get(itemId);
            BindingRow row = new BindingRow(itemId, bank, ref);
            rowWidgets.put(itemId, row);
            rowListView.addChild(row);
        }
        updateLayout(getGui());
    }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    @Override
    protected void updateLayout(Gui gui) {
        int padding = 5;
        int spacing = 5;
        int width = getWidth();
        int height = getHeight();
        if (closeButton == null) return;

        closeButton.setBounds(width - 20 - padding, padding, 20, 20);
        int refreshWidth = closeButton.getTextWidth(Component.translatable(KEY_REFRESH).getString()) + 20;
        refreshButton.setBounds(closeButton.getLeft() - spacing - refreshWidth, padding, refreshWidth, 20);

        int titleWidth = width / 2;
        titleLabel.setBounds((width - titleWidth) / 2, padding, titleWidth, 15);
        subtitleLabel.setBounds((width - titleWidth) / 2, titleLabel.getBottom(), titleWidth, 12);

        int listWidth = Math.min(width - 2 * padding, 380);
        int listX = (width - listWidth) / 2;
        int listY = subtitleLabel.getBottom() + spacing;
        int listH = height - listY - padding;
        rowListView.setBounds(listX, listY, listWidth, listH);

        emptyLabel.setBounds(listX, listY + listH / 2 - 10, listWidth, 20);
    }

    @Override
    public void onClose() {
        screenIsOpen = false;
        if (parent != null && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bind flow
    // ---------------------------------------------------------------------------------------

    /**
     * Entry point for the "Bind..." button on a row. Walks through provider pick, account pick,
     * and confirmation dialog before firing the request. Skips step 1 when only one provider is
     * available; refuses to open at all when zero providers are available.
     * <p>
     * Auto-transfer: The server now auto-transfers any local free balance to external
     * (Task #33 v2.0.5 refinement). No client-side balance check here — the server
     * handles overflow / locked-balance refusal and returns the appropriate status.
     */
    private void beginBindFlow(BindingRow row) {
        if (!canManage) return;
        if (availableProviders.isEmpty()) {
            showInfoPopup(
                    Component.translatable(KEY_BIND_ERROR_TITLE).getString(),
                    Component.translatable(KEY_NO_PROVIDERS).getString());
            return;
        }
        if (availableProviders.size() == 1) {
            openAccountPicker(row, availableProviders.get(0));
        } else {
            Minecraft.getInstance().setScreen(new ProviderPickerScreen(this, provider -> openAccountPicker(row, provider)));
        }
    }

    private void openAccountPicker(BindingRow row, ListCurrencyProvidersRequest.ProviderInfo provider) {
        // Client-side item-match pre-check: refuse instantly with a clear message rather than
        // walking the user through the picker only to fail on the server. Server still enforces
        // the same rule as defense-in-depth.
        String providerCurrency = provider.baseCurrencyItemIdOrNull();
        if (providerCurrency != null && !providerCurrency.equals(row.itemId.getName())) {
            showInfoPopup(
                    Component.translatable(KEY_BIND_ERROR_TITLE).getString(),
                    Component.translatable(KEY_BIND_ERROR_ITEM_MISMATCH).getString()
                            .replace("{item}", row.itemId.getName())
                            .replace("{provider}", provider.displayName())
                            .replace("{provider_currency}", providerCurrency));
            return;
        }
        boolean accountIsShared = accountData != null && accountData.personalBankOwnerData == null;
        AccountPickerScreen picker = new AccountPickerScreen(this, provider, accountIsShared,
                ref -> confirmBind(row, provider, ref));
        Minecraft.getInstance().setScreen(picker);
        picker.load();
    }

    private void confirmBind(BindingRow row, ListCurrencyProvidersRequest.ProviderInfo provider, ExternalAccountRef ref) {
        String title = Component.translatable(KEY_CONFIRM_BIND_TITLE).getString();
        String body = Component.translatable(KEY_CONFIRM_BIND_BODY).getString()
                .replace("{item}", row.itemId.getName())
                .replace("{provider}", provider.displayName())
                .replace("{account}", ref.label().isEmpty() ? ref.accountKey() : ref.label())
                .replace("{balance}", row.bank.getFormattedBalance());
        AskPopupScreen popup = new AskPopupScreen(
                this,
                () -> BindExternalAccountRequest.sendRequest(row.accountNumber(), row.itemId, ref)
                        .thenAccept(status -> {
                            if (!isBankStatusSuccess(status)) {
                                showInfoPopup(
                                        Component.translatable(KEY_BIND_ERROR_TITLE).getString(),
                                        mapBindStatusToMessage(status, row, provider));
                            }
                            refreshAll();
                        }),
                () -> {},
                title, body);
        popup.setSize(420, 130);
        popup.setColors(0xFFe8711c, 0xFFe04c12, 0xFF70e815, 0xFFf22718);
        Minecraft.getInstance().setScreen(popup);
    }

    // ---------------------------------------------------------------------------------------
    // Unbind flow
    // ---------------------------------------------------------------------------------------

    private void beginUnbindFlow(BindingRow row, ExternalAccountRef ref) {
        if (!canManage) return;
        UnbindChoiceDialog dialog = new UnbindChoiceDialog(this, row, ref);
        Minecraft.getInstance().setScreen(dialog);
    }

    // ---------------------------------------------------------------------------------------
    // Status mapping + generic popup
    // ---------------------------------------------------------------------------------------

    private static boolean isBankStatusSuccess(BankStatus status) {
        return status == BankStatus.SUCCESS;
    }

    /**
     * Bind-response status → user-facing message with contextual filling. For the shared /
     * item-mismatch overload of {@code FAILED_WRONG_INSTANCE_TYPE} we compare the slot's item
     * against the provider's declared base currency to decide which of the two flavors to show —
     * client-side item pre-check catches this case first anyway, so this branch is only hit when
     * the provider's base-currency changed between provider-list fetch and bind, or when a
     * mismatch snuck through some other path.
     */
    private static String mapBindStatusToMessage(BankStatus status, BindingRow row,
                                                 ListCurrencyProvidersRequest.ProviderInfo provider) {
        if (status == null) return Component.translatable(KEY_STATUS_GENERIC_FAILURE).getString();
        return switch (status) {
            case SUCCESS -> Component.translatable(KEY_STATUS_SUCCESS).getString();
            case FAILED_NO_BANK -> Component.translatable(KEY_STATUS_INVALID_ACCOUNT).getString();
            case FAILED_WRONG_INSTANCE_TYPE -> {
                String providerCurrency = provider.baseCurrencyItemIdOrNull();
                if (providerCurrency != null && row != null && !providerCurrency.equals(row.itemId.getName())) {
                    yield Component.translatable(KEY_BIND_ERROR_ITEM_MISMATCH).getString()
                            .replace("{item}", row.itemId.getName())
                            .replace("{provider}", provider.displayName())
                            .replace("{provider_currency}", providerCurrency);
                }
                yield Component.translatable(KEY_STATUS_SHARED_MISMATCH).getString();
            }
            case FAILED_OVERFLOW -> Component.translatable(KEY_BIND_ERROR_OVERFLOW).getString();
            case FAILED_INVALID_ITEM_ID -> Component.translatable(KEY_STATUS_INVALID_REQUEST).getString();
            case FAILED_EXTERNAL_UNAVAILABLE -> Component.translatable(KEY_STATUS_EXTERNAL_UNAVAILABLE).getString();
            case FAILED_NO_MASTER_CONNECTION -> Component.translatable(KEY_STATUS_NO_MASTER).getString();
            default -> Component.translatable(KEY_STATUS_GENERIC_FAILURE).getString();
        };
    }

    /**
     * Unbind-response status → user-facing message. No item/provider context needed here — the
     * failure modes are simpler than bind.
     */
    private static String mapUnbindStatusToMessage(BankStatus status) {
        if (status == null) return Component.translatable(KEY_UNBIND_ERROR_GENERIC).getString();
        return switch (status) {
            case SUCCESS -> Component.translatable(KEY_STATUS_SUCCESS).getString();
            case FAILED_NO_BANK -> Component.translatable(KEY_STATUS_INVALID_ACCOUNT).getString();
            case FAILED_OVERFLOW -> Component.translatable(KEY_UNBIND_ERROR_OVERFLOW).getString();
            case FAILED_EXTERNAL_UNAVAILABLE -> Component.translatable(KEY_STATUS_EXTERNAL_UNAVAILABLE).getString();
            case FAILED_NO_MASTER_CONNECTION -> Component.translatable(KEY_STATUS_NO_MASTER).getString();
            default -> Component.translatable(KEY_UNBIND_ERROR_GENERIC).getString() + " (" + status + ")";
        };
    }

    /**
     * Shows a single-button OK acknowledgment popup. Use this instead of {@link AskPopupScreen}
     * for informational messages (errors, warnings) where the user is not being asked a Yes/No
     * question.
     */
    private void showInfoPopup(String title, String message) {
        InfoPopupScreen popup = new InfoPopupScreen(this, title, message);
        popup.setSize(420, 110);
        popup.setColors(0xFF806040, 0xFF503020, 0xFF70e815);
        Minecraft.getInstance().setScreen(popup);
    }

    // ---------------------------------------------------------------------------------------
    // Three-button unbind choice dialog
    // ---------------------------------------------------------------------------------------

    /**
     * Custom 3-button dialog for the unbind flow (Task #33 v2.0.5 refinement).
     * User chooses:
     * - Keep on BankSystem (default)
     * - Keep on <provider> (with fractional-loss warning)
     * - Cancel
     */
    private class UnbindChoiceDialog extends BankSystemGuiScreen {
        private final BankAccountBindingsScreen parent;
        private final BindingRow row;
        private final ExternalAccountRef ref;
        private Label titleLabel;
        private Label messageLabel;
        private Label warningLabel;
        private Button keepOnBankSystemButton;
        private Button keepOnProviderButton;
        private Button cancelButton;

        UnbindChoiceDialog(BankAccountBindingsScreen parent, BindingRow row, ExternalAccountRef ref) {
            super(Component.translatable(KEY_UNBIND_DIALOG_TITLE), parent);
            this.parent = parent;
            this.row = row;
            this.ref = ref;

            titleLabel = new Label(Component.translatable(KEY_UNBIND_DIALOG_TITLE).getString());
            titleLabel.setAlignment(GuiElement.Alignment.CENTER);
            addElement(titleLabel);

            messageLabel = new Label(Component.translatable(KEY_UNBIND_DIALOG_MESSAGE).getString());
            messageLabel.setAlignment(GuiElement.Alignment.CENTER);
            addElement(messageLabel);

            warningLabel = new Label(Component.translatable(KEY_UNBIND_DIALOG_KEEP_ON_PROVIDER_WARNING).getString());
            warningLabel.setAlignment(GuiElement.Alignment.CENTER);
            warningLabel.setTextColor(0xFFFFAA00);
            warningLabel.setTextFontScale(0.8f);
            addElement(warningLabel);

            keepOnBankSystemButton = new Button(
                    Component.translatable(KEY_UNBIND_DIALOG_KEEP_ON_BANKSYSTEM).getString(),
                    () -> doUnbind(true));
            addElement(keepOnBankSystemButton);

            String providerLabel = Component.translatable(KEY_UNBIND_DIALOG_KEEP_ON_PROVIDER)
                    .getString().replace("%s", ref.providerId());
            keepOnProviderButton = new Button(providerLabel, () -> doUnbind(false));
            addElement(keepOnProviderButton);

            cancelButton = new Button(Component.translatable(KEY_UNBIND_DIALOG_CANCEL).getString(), this::onClose);
            addElement(cancelButton);
        }

        private void doUnbind(boolean keepOnBankSystem) {
            UnbindExternalAccountRequest.sendRequest(row.accountNumber(), row.itemId, keepOnBankSystem)
                    .thenAccept(status -> {
                        parent.refreshAll();
                        if (!isBankStatusSuccess(status)) {
                            // showInfoPopup sets the screen to the popup with parent as its parent;
                            // don't call our own onClose() afterwards or it would replace the popup
                            // with the plain parent screen before the user can dismiss it.
                            parent.showInfoPopup(
                                    Component.translatable(KEY_UNBIND_ERROR_TITLE).getString(),
                                    mapUnbindStatusToMessage(status));
                        } else {
                            onClose();
                        }
                    });
        }

        @Override
        protected void updateLayout(Gui gui) {
            int padding = 10;
            int spacing = 5;
            int width = getWidth();
            int height = getHeight();

            int contentWidth = Math.min(width - 2 * padding, 400);
            int x = (width - contentWidth) / 2;
            int y = padding;

            titleLabel.setBounds(x, y, contentWidth, 20);
            y += 25;

            messageLabel.setBounds(x, y, contentWidth, 15);
            y += 20;

            warningLabel.setBounds(x, y, contentWidth, 12);
            y += 20;

            int buttonWidth = (contentWidth - spacing) / 2;
            int buttonHeight = 20;

            keepOnBankSystemButton.setBounds(x, y, buttonWidth, buttonHeight);
            keepOnProviderButton.setBounds(x + buttonWidth + spacing, y, buttonWidth, buttonHeight);
            y += buttonHeight + spacing;

            cancelButton.setBounds(x + (contentWidth - buttonWidth) / 2, y, buttonWidth, buttonHeight);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Per-row widget
    // ---------------------------------------------------------------------------------------

    private class BindingRow extends BankSystemGuiElement {
        private static final int HEIGHT = 30;
        private final ItemID itemId;
        private final BankData bank;
        private final @org.jetbrains.annotations.Nullable ExternalAccountRef ref;
        private final ItemView itemView;
        private final Label nameLabel;
        private final Label balanceLabel;
        private final Label statusLabel;
        private final Button actionButton;

        BindingRow(ItemID itemId, BankData bank, @org.jetbrains.annotations.Nullable ExternalAccountRef ref) {
            super();
            setHeight(HEIGHT);
            this.itemId = itemId;
            this.bank = bank;
            this.ref = ref;

            itemView = new ItemView();
            itemView.setItemStack(itemId.getStack());
            addChild(itemView);

            nameLabel = new Label(itemId.getName());
            nameLabel.setAlignment(Alignment.LEFT);
            addChild(nameLabel);

            balanceLabel = new Label(bank.getFormattedBalance());
            balanceLabel.setAlignment(Alignment.LEFT);
            balanceLabel.setTextFontScale(0.8f);
            addChild(balanceLabel);

            statusLabel = new Label();
            statusLabel.setAlignment(Alignment.LEFT);
            statusLabel.setTextFontScale(0.8f);
            addChild(statusLabel);

            if (ref == null) {
                statusLabel.setText(Component.translatable(KEY_NOT_BOUND).getString());
                actionButton = new Button(Component.translatable(KEY_BIND_BUTTON).getString(),
                        () -> beginBindFlow(this));
            } else {
                boolean providerAvailable = isProviderAvailable(ref.providerId());
                String providerLabel = ref.providerId() + ": " + (ref.label().isEmpty() ? ref.accountKey() : ref.label());
                if (!providerAvailable) {
                    statusLabel.setText(providerLabel);
                    statusLabel.setTextColor(GRAY_TEXT_COLOR);
                    final String unavailableTooltip = Component.translatable(KEY_PROVIDER_UNAVAILABLE).getString();
                    statusLabel.setHoverTooltipSupplier(() -> unavailableTooltip);
                } else {
                    statusLabel.setText(providerLabel);
                }
                actionButton = new Button(Component.translatable(KEY_UNBIND_BUTTON).getString(),
                        () -> beginUnbindFlow(this, ref));
            }
            addChild(actionButton);

            // Read-only mode: gray out the action button, keep it hoverable for the tooltip.
            if (!canManage) {
                final String reason = Component.translatable(KEY_REQUIRES_MANAGE).getString();
                actionButton.setClickable(false);
                actionButton.setTextColor(GRAY_TEXT_COLOR);
                actionButton.setHoverTooltipSupplier(() -> reason);
            }
        }

        int accountNumber() {
            return BankAccountBindingsScreen.this.accountNumber;
        }

        @Override
        protected void render() {}

        @Override
        protected void layoutChanged() {
            int padding = 3;
            int width = getWidth();
            int height = getHeight();
            int iconSize = height - 2 * padding;
            itemView.setBounds(padding, padding, iconSize, iconSize);

            int actionWidth = 70;
            actionButton.setBounds(width - padding - actionWidth, (height - 18) / 2, actionWidth, 18);

            int textLeft = itemView.getRight() + padding;
            int textRight = actionButton.getLeft() - padding;
            int textWidth = Math.max(0, textRight - textLeft);
            int lineHeight = (height - 2 * padding) / 2;
            nameLabel.setBounds(textLeft, padding, textWidth * 3 / 5, lineHeight);
            balanceLabel.setBounds(nameLabel.getRight(), padding, textWidth - nameLabel.getWidth(), lineHeight);
            statusLabel.setBounds(textLeft, nameLabel.getBottom(), textWidth, lineHeight);
        }
    }

    private boolean isProviderAvailable(String providerId) {
        for (ListCurrencyProvidersRequest.ProviderInfo info : availableProviders) {
            if (info.providerId().equals(providerId)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Nested picker screens
    // ---------------------------------------------------------------------------------------

    /**
     * Step 1 of the bind flow: pick a currency provider. Only opened when 2+ providers are
     * available; a single-provider case skips straight to {@link AccountPickerScreen}.
     */
    private static class ProviderPickerScreen extends BankSystemGuiScreen {
        private final BankAccountBindingsScreen parent;
        private final java.util.function.Consumer<ListCurrencyProvidersRequest.ProviderInfo> onPicked;
        private final Label titleLabel;
        private final ListView list;

        ProviderPickerScreen(BankAccountBindingsScreen parent,
                             java.util.function.Consumer<ListCurrencyProvidersRequest.ProviderInfo> onPicked) {
            super(Component.translatable(KEY_PICK_PROVIDER), parent);
            this.parent = parent;
            this.onPicked = onPicked;

            titleLabel = new Label(Component.translatable(KEY_PICK_PROVIDER).getString());
            titleLabel.setAlignment(GuiElement.Alignment.CENTER);
            addElement(titleLabel);

            list = new VerticalListView();
            LayoutGrid layout = new LayoutGrid();
            layout.columns = 1;
            layout.spacing = 0;
            layout.padding = 0;
            layout.stretchX = true;
            layout.alignment = GuiElement.Alignment.TOP;
            list.setLayout(layout);
            addElement(list);

            for (ListCurrencyProvidersRequest.ProviderInfo info : parent.availableProviders) {
                Button b = new Button(info.displayName());
                b.setHeight(20);
                b.setOnFallingEdge(() -> {
                    onPicked.accept(info);
                    // onPicked opens the AccountPickerScreen via setScreen(). Calling onClose()
                    // here would immediately replace it with the parent bindings screen before
                    // the user can see the account list — same pattern as AccountPickerScreen's
                    // bug #3 fix below.
                });
                list.addChild(b);
            }
        }

        @Override
        protected void updateLayout(Gui gui) {
            int padding = 5;
            int spacing = 5;
            int width = getWidth();
            int height = getHeight();
            int contentWidth = width / 2;
            titleLabel.setBounds((width - contentWidth) / 2, padding, contentWidth, 20);
            list.setBounds((width - contentWidth) / 2, titleLabel.getBottom() + spacing,
                    contentWidth, height - titleLabel.getBottom() - 2 * spacing);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
        }
    }

    /**
     * Step 2 of the bind flow: pick a specific external account exposed by the chosen provider.
     * Filters by shared-state to match the BankSystem account kind (personal → non-shared refs
     * only, shared → shared refs only). Defense-in-depth against a stale list; the master
     * enforces the same rule via {@code shared-state mismatch} in bindExternalAccount.
     */
    private static class AccountPickerScreen extends BankSystemGuiScreen {
        private final BankAccountBindingsScreen parent;
        private final ListCurrencyProvidersRequest.ProviderInfo provider;
        private final boolean bankAccountIsShared;
        private final java.util.function.Consumer<ExternalAccountRef> onPicked;
        private final Label titleLabel;
        private final Label emptyLabel;
        private final ListView list;

        AccountPickerScreen(BankAccountBindingsScreen parent,
                            ListCurrencyProvidersRequest.ProviderInfo provider,
                            boolean bankAccountIsShared,
                            java.util.function.Consumer<ExternalAccountRef> onPicked) {
            super(Component.translatable(KEY_PICK_ACCOUNT), parent);
            this.parent = parent;
            this.provider = provider;
            this.bankAccountIsShared = bankAccountIsShared;
            this.onPicked = onPicked;

            titleLabel = new Label(Component.translatable(KEY_PICK_ACCOUNT).getString()
                    + " (" + provider.displayName() + ")");
            titleLabel.setAlignment(GuiElement.Alignment.CENTER);
            addElement(titleLabel);

            emptyLabel = new Label(Component.translatable(KEY_NO_PROVIDERS).getString());
            emptyLabel.setAlignment(GuiElement.Alignment.CENTER);
            emptyLabel.setTextColor(GRAY_TEXT_COLOR);
            emptyLabel.setEnabled(false);
            addElement(emptyLabel);

            list = new VerticalListView();
            LayoutGrid layout = new LayoutGrid();
            layout.columns = 1;
            layout.spacing = 0;
            layout.padding = 0;
            layout.stretchX = true;
            layout.alignment = GuiElement.Alignment.TOP;
            list.setLayout(layout);
            addElement(list);
        }

        /** Fires the ARRS request. Called after the screen is shown so the loading state is visible. */
        void load() {
            ListBindableAccountsRequest.sendRequest(provider.providerId(), getThisPlayerUUID())
                    .thenAccept(this::onAccountsLoaded);
        }

        private void onAccountsLoaded(List<ExternalAccountRef> refs) {
            list.removeChilds();
            int shown = 0;
            if (refs != null) {
                for (ExternalAccountRef ref : refs) {
                    // Defense-in-depth: filter by shared-state to match this BankSystem account kind.
                    if (ref.shared() != bankAccountIsShared) continue;
                    String label = ref.label().isEmpty() ? ref.accountKey() : ref.label();
                    if (ref.shared()) {
                        label = label + "  [" + Component.translatable(KEY_SHARED_BADGE).getString() + "]";
                    }
                    Button b = new Button(label);
                    b.setHeight(20);
                    final ExternalAccountRef finalRef = ref;
                    b.setOnFallingEdge(() -> {
                        onPicked.accept(finalRef);
                        // onPicked opens the confirmation dialog, which handles returning to the
                        // parent screen on its own (both Yes and No buttons call setScreen(parent)).
                        // Calling onClose() here would immediately replace the confirm dialog with
                        // the parent before the user can interact with it (bug #3 root cause).
                    });
                    list.addChild(b);
                    shown++;
                }
            }
            emptyLabel.setEnabled(shown == 0);
            updateLayout(getGui());
        }

        @Override
        protected void updateLayout(Gui gui) {
            int padding = 5;
            int spacing = 5;
            int width = getWidth();
            int height = getHeight();
            int contentWidth = Math.min(width - 2 * padding, 380);
            int x = (width - contentWidth) / 2;
            titleLabel.setBounds(x, padding, contentWidth, 20);
            int y = titleLabel.getBottom() + spacing;
            int h = height - y - padding;
            list.setBounds(x, y, contentWidth, h);
            emptyLabel.setBounds(x, y + h / 2 - 10, contentWidth, 20);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
        }
    }
}
