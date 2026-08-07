package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.InfoPopupScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Task #51 / Task #1 (v2.0.8, spec §5) — Market tab: create / pause-resume /
 * permanently close a StockMarket market for the company's share item.
 *
 * <p>Three-state FSM ({@code LOADING → UNAVAILABLE | NO_MARKET | HAS_MARKET}); on
 * {@code HAS_MARKET} an additional {@code marketOpen} boolean toggles the
 * Pause / Resume button. All widgets are created once and shown / hidden per
 * state; positions are set with explicit {@code setBounds} in
 * {@link #layoutChanged()} (spec §5.5).
 */
public class MarketTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private enum MarketState { LOADING, UNAVAILABLE, NO_MARKET, HAS_MARKET }

    private final Label statusLabel;
    private final Label priceLabel;
    private final TextBox priceBox;
    private final Button createButton;
    private final Button pauseResumeButton;
    private final Label closeHintLabel;
    private final TextBox closeConfirmBox;
    private final Button closeButton;

    private final boolean authorised;
    private final boolean founder;

    private MarketState state = MarketState.LOADING;
    private boolean marketOpen = true;

    public MarketTabBody(CompanyManagementScreen screen) {
        super(screen);
        this.authorised = screen.canManageNow() || screen.isFounderNow();
        this.founder = screen.isFounderNow();

        statusLabel = new Label(tr("market_checking"));
        statusLabel.setAlignment(Label.Alignment.LEFT);

        priceLabel = new Label(tr("initial_price") + ":");
        priceLabel.setAlignment(Label.Alignment.RIGHT);
        priceBox = new TextBox();
        priceBox.setText("100");
        priceBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 9, 2));
        priceBox.setHoverTooltipSupplier(() -> tr("initial_price_hint"));

        createButton = new Button(tr("create_market"), this::onCreate);
        pauseResumeButton = new Button(tr("pause_trading"), this::onPauseResume);
        closeHintLabel = new Label(tr("close_market_confirm_msg"));
        closeHintLabel.setAlignment(Label.Alignment.LEFT);
        closeConfirmBox = new TextBox();
        closeButton = new Button(tr("close_market"), this::onClose);

        addChild(statusLabel);
        addChild(priceLabel);
        addChild(priceBox);
        addChild(createButton);
        addChild(pauseResumeButton);
        addChild(closeHintLabel);
        addChild(closeConfirmBox);
        addChild(closeButton);

        applyState(MarketState.LOADING);
        refreshState();
    }

    private static String tr(String key) {
        return Component.translatable(PREFIX + key).getString();
    }

    // ------------------------------------------------------------------
    // FSM
    // ------------------------------------------------------------------

    /** Spec §5.4 — re-probe authoritative SM state and re-render. */
    private void refreshState() {
        AsyncCompanyManager.marketExistsForCompanyAsync(screen.getCompanyId())
                .thenAccept(code -> onClientThread(() -> {
                    int existsCode = code == null ? AsyncCompanyManager.MARKET_EXISTS_UNAV : code;
                    switch (existsCode) {
                        case AsyncCompanyManager.MARKET_EXISTS_YES -> {
                            applyState(MarketState.HAS_MARKET);
                            refreshOpenFlag();
                        }
                        case AsyncCompanyManager.MARKET_EXISTS_NO -> applyState(MarketState.NO_MARKET);
                        default -> applyState(MarketState.UNAVAILABLE);
                    }
                }));
    }

    private void refreshOpenFlag() {
        AsyncCompanyManager.isMarketOpenAsync(screen.getCompanyId())
                .thenAccept(code -> onClientThread(() -> {
                    marketOpen = code != null && code == AsyncCompanyManager.MARKET_OPEN_YES;
                    pauseResumeButton.setText(marketOpen ? tr("pause_trading") : tr("resume_trading"));
                }));
    }

    private void applyState(MarketState newState) {
        state = newState;
        boolean noMarket = state == MarketState.NO_MARKET;
        boolean hasMarket = state == MarketState.HAS_MARKET;
        priceLabel.setEnabled(noMarket);
        priceBox.setEnabled(noMarket && authorised);
        createButton.setEnabled(noMarket && authorised);
        pauseResumeButton.setEnabled(hasMarket && authorised);
        closeHintLabel.setEnabled(hasMarket && founder);
        closeConfirmBox.setEnabled(hasMarket && founder);
        closeButton.setEnabled(hasMarket && founder);
        statusLabel.setText(switch (state) {
            case LOADING -> tr("market_checking");
            case UNAVAILABLE -> tr("market_unavailable");
            case NO_MARKET -> tr("market_not_created");
            case HAS_MARKET -> tr("market_exists");
        });
        layoutChangedInternal();
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onCreate() {
        float price;
        try {
            price = Float.parseFloat(priceBox.getText());
        } catch (NumberFormatException e) {
            price = 0f;
        }
        if (price <= 0f) {
            popup(tr("error_title"), tr("invalid_price"));
            return;
        }
        AsyncCompanyManager.openShareMarketAsync(screen.getCompanyId(), price, screen.callerUUID())
                .thenAccept(out -> onClientThread(() -> {
                    int status = out == null ? AsyncCompanyManager.SM_STATUS_UNAVAILABLE : out.status();
                    if (status == AsyncCompanyManager.SM_STATUS_SUCCESS) {
                        popup(tr("tab.market"), tr("create_market_success"));
                    } else {
                        popup(tr("error_title"), createErrorText(status));
                    }
                    refreshState();
                }));
    }

    private String createErrorText(int status) {
        return switch (status) {
            case AsyncCompanyManager.SM_STATUS_ALREADY_EXISTS -> tr("create_market_already_exists");
            case AsyncCompanyManager.SM_STATUS_ITEM_BLACKLISTED -> tr("create_market_blacklisted");
            case AsyncCompanyManager.SM_STATUS_NO_PERMISSION -> tr("market_no_permission");
            case AsyncCompanyManager.SM_STATUS_NOT_FOUND -> tr("market_not_found");
            case AsyncCompanyManager.SM_STATUS_UNAVAILABLE -> tr("market_unavailable");
            default -> tr("create_market_failed");
        };
    }

    private void onPauseResume() {
        boolean targetOpen = !marketOpen;
        String confirmMsg = targetOpen ? tr("resume_confirm_msg") : tr("pause_confirm_msg");
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(AskPopupScreen.warningPopup(screen,
                () -> AsyncCompanyManager.setMarketOpenAsync(screen.getCompanyId(), targetOpen, screen.callerUUID())
                        .thenAccept(out -> onClientThread(() -> {
                            int status = out == null ? AsyncCompanyManager.SM_STATUS_UNAVAILABLE : out.status();
                            if (status != AsyncCompanyManager.SM_STATUS_SUCCESS) {
                                popup(tr("error_title"), createErrorText(status));
                            }
                            // Spec §5.4 — reconcile with authoritative SM state.
                            refreshState();
                        })),
                () -> {},
                targetOpen ? tr("resume_trading") : tr("pause_trading"),
                confirmMsg));
    }

    private void onClose() {
        var info = screen.info();
        String canonical = info != null && info.present() ? info.name() : screen.getCompanyName();
        if (!closeConfirmBox.getText().equals(canonical)) {
            popup(tr("error_title"), tr("name_mismatch"));
            return;
        }
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(AskPopupScreen.warningPopup(screen,
                () -> AsyncCompanyManager.closeShareMarketAsync(screen.getCompanyId(), screen.callerUUID())
                        .thenAccept(out -> onClientThread(() -> {
                            int status = out == null ? AsyncCompanyManager.SM_STATUS_UNAVAILABLE : out.status();
                            if (status == AsyncCompanyManager.SM_STATUS_SUCCESS) {
                                popup(tr("tab.market"), tr("close_market_success"));
                            } else {
                                popup(tr("error_title"), createErrorText(status));
                            }
                            refreshState();
                        })),
                () -> {},
                tr("close_market_confirm_title"),
                tr("close_market_permanent_msg")));
    }

    private void popup(String title, String message) {
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new InfoPopupScreen(screen, title, message));
    }

    // ------------------------------------------------------------------
    // Layout (spec §5.5 — explicit positions, no list view)
    // ------------------------------------------------------------------

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int inputX = PADDING + LABEL_WIDTH + ROW_SPACING;
        int y = PADDING;
        statusLabel.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        if (state == MarketState.NO_MARKET) {
            priceLabel.setBounds(PADDING, y, LABEL_WIDTH, ROW_HEIGHT);
            priceBox.setBounds(inputX, y, Math.min(120, w - inputX - PADDING), ROW_HEIGHT);
            y += ROW_HEIGHT + ROW_SPACING;
            createButton.setBounds(w - PADDING - 140, y, 140, ROW_HEIGHT);
        } else if (state == MarketState.HAS_MARKET) {
            pauseResumeButton.setBounds(PADDING, y, Math.min(160, w - 2 * PADDING), ROW_HEIGHT);
            y += ROW_HEIGHT + SECTION_SPACING * 2;
            closeHintLabel.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
            y += ROW_HEIGHT + ROW_SPACING;
            closeConfirmBox.setBounds(PADDING, y, w - 2 * PADDING - 110 - ROW_SPACING, ROW_HEIGHT);
            closeButton.setBounds(w - PADDING - 110, y, 110, ROW_HEIGHT);
        }
    }
}
