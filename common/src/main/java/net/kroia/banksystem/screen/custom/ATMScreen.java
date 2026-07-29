package net.kroia.banksystem.screen.custom;

import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.ConverterDropAllPacket;
import net.kroia.banksystem.networking.entity.GetConverterCachePacket;
import net.kroia.banksystem.screen.custom.atm.ConverterView;
import net.kroia.banksystem.screen.custom.atm.WithdrawView;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * ATM screen — Task #39 refactor. Container hosting a two-button tab bar and two
 * panels ({@link WithdrawView}, {@link ConverterView}).
 *
 * <p>The {@code selectAccountButton} lives at the screen level and drives both:
 * <ul>
 *     <li>{@link WithdrawView} — the account whose money balance is dispensed.</li>
 *     <li>{@link ConverterView} — carried through to the "deposit remainder to bank"
 *         button so the player doesn't have to re-pick.</li>
 * </ul>
 *
 * <p>On close, if the server-side converter cache is nonzero, a
 * {@link ConverterDropAllPacket} is fired so the player never loses the remainder.
 * Backend also hooks {@code PlayerEvent.PLAYER_QUIT} as an authoritative safety net
 * for crashed / force-killed clients.
 */
public class ATMScreen extends BankSystemGuiScreen {
    private static final String PREFIX = "gui.";
    private static final String NAME = ".atm_screen.";
    public static final String COMPONENT_STR_START = PREFIX + BankSystemMod.MOD_ID + NAME;
    public static final Component TITLE = Component.translatable(COMPONENT_STR_START + "title");

    public static final Component TAB_WITHDRAW = Component.translatable(COMPONENT_STR_START + "tab_withdraw");
    public static final Component TAB_CONVERT = Component.translatable(COMPONENT_STR_START + "tab_convert");

    // Tooltip keys added by the backend agent — see task instructions.
    private static final String TOOLTIP_TAB_WITHDRAW_KEY   = COMPONENT_STR_START + "tooltip.tab_withdraw";
    private static final String TOOLTIP_TAB_CONVERT_KEY    = COMPONENT_STR_START + "tooltip.tab_convert";
    private static final String TOOLTIP_SELECT_ACCOUNT_KEY = COMPONENT_STR_START + "tooltip.select_account";

    private static final int TAB_ACTIVE_COLOR   = ColorUtilities.getRGB(80, 150, 220);
    private static final int TAB_INACTIVE_COLOR = ColorUtilities.getRGB(60, 60, 60);

    private static final int TAB_WITHDRAW_IDX = 0;
    private static final int TAB_CONVERT_IDX  = 1;

    private final Frame rootElement;

    private final Button tabWithdrawButton;
    private final Button tabConvertButton;

    private final BankAccountSelectionScreen.AccountButton selectAccountButton;

    private final WithdrawView withdrawView;
    private final ConverterView converterView;

    private static ATMScreen instance = null;
    private static boolean tickListenerRegistered = false;
    private static long lastTickCount = 0;

    private int activeTab = TAB_WITHDRAW_IDX;
    private int currentSelectedAccountNumber = 0;

    public ATMScreen() {
        super(TITLE);
        rootElement = new Frame();
        addElement(rootElement);

        // Zero the client-side cache mirror on open — the previous ATMScreen may
        // have left a stale value that would flash for up to 1 s before the first
        // poll response overwrites it. Fire an immediate poll so the first display
        // value is authoritative rather than a static 0. Polish-round item 2.
        ConverterView.ClientCache.clear();
        try {
            GetConverterCachePacket.sendRequest().thenAccept(ConverterView.ClientCache::set);
        } catch (Throwable t) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn(
                        "[ATMScreen] Failed to send initial GetConverterCachePacket: " + t.getMessage());
        }

        // --- Shared: account selector ------------------------------------------------
        selectAccountButton = new BankAccountSelectionScreen.AccountButton();
        selectAccountButton.setOnFallingEdge(() -> {
            BankAccountSelectionScreen selectionScreen = new BankAccountSelectionScreen(
                    this, minecraft.player.getUUID(),
                    (accountNumber) -> this.currentSelectedAccountNumber = accountNumber);
            minecraft.setScreen(selectionScreen);
        });
        applyTopTooltip(selectAccountButton, TOOLTIP_SELECT_ACCOUNT_KEY);
        rootElement.addChild(selectAccountButton);

        // --- Tab bar -----------------------------------------------------------------
        tabWithdrawButton = new Button(TAB_WITHDRAW.getString(), () -> setActiveTab(TAB_WITHDRAW_IDX));
        tabConvertButton  = new Button(TAB_CONVERT.getString(),  () -> setActiveTab(TAB_CONVERT_IDX));
        tabWithdrawButton.setHeight(20);
        tabConvertButton.setHeight(20);
        applyTopTooltip(tabWithdrawButton, TOOLTIP_TAB_WITHDRAW_KEY);
        applyTopTooltip(tabConvertButton, TOOLTIP_TAB_CONVERT_KEY);
        rootElement.addChild(tabWithdrawButton);
        rootElement.addChild(tabConvertButton);

        // --- Panels ------------------------------------------------------------------
        withdrawView  = new WithdrawView(() -> currentSelectedAccountNumber);
        converterView = new ConverterView(() -> currentSelectedAccountNumber);
        rootElement.addChild(withdrawView);
        rootElement.addChild(converterView);

        // --- Tick registration --------------------------------------------------------
        lastTickCount = System.currentTimeMillis();
        if (!tickListenerRegistered) {
            TickEvent.PLAYER_POST.register(ATMScreen::onClientTick);
            tickListenerRegistered = true;
        }
        instance = this;

        // Default: withdraw tab.
        setActiveTab(TAB_WITHDRAW_IDX);
        updateBalanceView();

        getBankManager().getPersonalBankAccountDataAsync(Minecraft.getInstance().player.getUUID())
                .thenAccept((accountData) -> {
                    if (accountData != null && !accountData.bankData.isEmpty()) {
                        currentSelectedAccountNumber = accountData.accountNumber;
                        selectAccountButton.setAccountData(accountData);
                        updateBalanceView();
                    }
                });
    }

    public static void openScreen() {
        ATMScreen screen = new ATMScreen();
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    public void onClose() {
        super.onClose();
        instance = null;

        // Task #39 acceptance criterion G — auto-drop remainder on close so the
        // player never silently loses cached banknotes. Server has an authoritative
        // PLAYER_QUIT safety net for the crashed / force-killed client case.
        long cache = ConverterView.ClientCache.get();
        if (cache > 0) {
            try {
                ConverterDropAllPacket.sendPacket();
            } catch (Throwable t) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.warn(
                            "[ATMScreen] Failed to send ConverterDropAllPacket on close (cache="
                                    + cache + "): " + t.getMessage());
            }
        }
        // Polish-round item 2 — unconditionally clear the client mirror so the next
        // ATMScreen open starts from an authoritative 0 (further overwritten by the
        // ctor's immediate poll).
        ConverterView.ClientCache.clear();
    }

    /**
     * Top-of-screen tooltip helper — tooltip renders BELOW the cursor so it clears
     * the screen title area. Applied to the tab-bar buttons and shared account
     * selector.
     */
    private static void applyTopTooltip(GuiElement el, String translationKey) {
        final String text = Component.translatable(translationKey).getString();
        el.setHoverTooltipSupplier(() -> text);
        el.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP);
    }

    private void setActiveTab(int tabIdx) {
        this.activeTab = tabIdx;
        boolean withdrawActive = (tabIdx == TAB_WITHDRAW_IDX);
        withdrawView.setEnabled(withdrawActive);
        converterView.setEnabled(!withdrawActive);
        tabWithdrawButton.setBackgroundColor(withdrawActive ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
        tabConvertButton.setBackgroundColor(!withdrawActive ? TAB_ACTIVE_COLOR : TAB_INACTIVE_COLOR);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = (getWidth() * 3) / 4;
        int height = getHeight();

        int padding = 5;
        int spacing = 5;

        int leftEdge = (getWidth() - width) / 2 + padding;

        rootElement.setBounds(leftEdge, padding, width - 2 * padding, height - 2 * padding);
        int innerPadding = 5;
        int innerWidth = rootElement.getWidth() - 2 * innerPadding;
        int innerHeight = rootElement.getHeight() - 2 * innerPadding;

        // Top row: account selector = 50% of innerWidth, Withdraw = 25%, Convert = 25%
        // (each share is inclusive of its trailing spacing gap; the two gaps sum to 2*spacing).
        int topRowSpacing = spacing;
        int accountWidth  = innerWidth / 2 - topRowSpacing;
        int withdrawWidth = innerWidth / 4 - topRowSpacing;
        selectAccountButton.setBounds(innerPadding, innerPadding, accountWidth, 20);
        tabWithdrawButton.setBounds(selectAccountButton.getRight() + topRowSpacing, innerPadding, withdrawWidth, 20);
        tabConvertButton.setBounds(tabWithdrawButton.getRight() + topRowSpacing, innerPadding,
                innerWidth - selectAccountButton.getWidth() - tabWithdrawButton.getWidth() - 2 * topRowSpacing, 20);

        // Panel row: active panel fills the remaining space. The inactive panel is
        // disabled (hidden) via setEnabled(false) in setActiveTab.
        int panelTop = selectAccountButton.getBottom() + spacing;
        int panelHeight = innerHeight - panelTop + innerPadding;
        withdrawView.setBounds(innerPadding, panelTop, innerWidth, panelHeight);
        converterView.setBounds(innerPadding, panelTop, innerWidth, panelHeight);
    }

    private static void onClientTick(Player player) {
        if (Minecraft.getInstance().screen != instance || instance == null)
            return;

        long currentTickCount = System.currentTimeMillis();
        if (currentTickCount - lastTickCount > 1000) {
            lastTickCount = currentTickCount;
            instance.updateBalanceView();
            instance.converterView.tick();
        }
    }

    /**
     * Refresh the withdraw tab's balance from the currently selected account's
     * money bank. Async — completes on the render thread thanks to the client
     * bank manager's futures.
     */
    private void updateBalanceView() {
        getBankManager().getBankAccountDataAsync(currentSelectedAccountNumber).thenAccept((accountData) -> {
            if (instance == null || accountData == null)
                return;

            selectAccountButton.setAccountData(accountData);
            long balance = 0;
            if (accountData.bankData.containsKey(MoneyItem.getItemID())) {
                BankData minimalBankData = accountData.bankData.get(MoneyItem.getItemID());
                balance = minimalBankData.balance();
            }
            withdrawView.setBalance(balance);
        });
    }
}
