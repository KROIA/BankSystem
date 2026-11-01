package net.kroia.banksystem.screen.custom;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TabElement;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Task #51 (v2.1.0) — Dedicated company management UI.
 *
 * <p><b>Status: SKELETON.</b> Full implementation is spec'd in
 * {@code .claude/Features/CompanyManagementScreenSpec.md} and will be delivered
 * by a subsequent implementation pass. This class currently ships:
 *
 * <ul>
 *   <li>Screen entry: extends {@link BankSystemGuiScreen}, opened via right-click
 *       on a stamped share OR via {@code /company manage &lt;name&gt;} + the S2C
 *       open packet. Both entry points are already wired outside this file.</li>
 *   <li>Header: title label ({@code "Company Management: &lt;name&gt; (ID=&lt;id&gt;)"})
 *       + {@link CloseButton}.</li>
 *   <li>{@link TabElement} with six PLACEHOLDER tab bodies (Overview, Workers,
 *       Payouts, Shares, Market, Danger). Each body is a single label saying
 *       the tab is not yet implemented — the implementation agent replaces each
 *       body per the spec.</li>
 *   <li>Client info fetch via {@code AsyncCompanyManager.getCompanyInfoByIdAsync}
 *       + rights resolution via {@code listCompanyNamesForCallerAsync} — both
 *       populate {@link CompanyInfoCache} and refresh the header title. This
 *       machinery is spec-compliant and does NOT need rewriting.</li>
 *   <li>Tab visibility gates: Workers/Payouts require MANAGE or founder;
 *       Danger requires founder; Market requires StockMarket installed AND
 *       the {@code STOCKMARKET_INTEGRATION_READY} feature flag flipped by the
 *       implementation agent.</li>
 * </ul>
 *
 * <p><b>DO NOT resurrect the old tab-body implementations.</b> The previous
 * iteration accumulated layout-timing bugs (LayoutGrid with default rows=4
 * silently dropped children, TabElement's deferred child attachment prevented
 * LayoutGrid.apply from firing on non-selected tabs, etc.). Follow the spec's
 * §0.5 layout discipline: subclass {@link GuiElement} per tab and set every
 * child's bounds explicitly in a {@code layoutChanged()} override. See
 * {@code BankAccountManagementScreen.updateLayout} for the working pattern.
 */
public class CompanyManagementScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";
    private static final Component TITLE_KEY = Component.translatable(PREFIX + "title");

    private static final Component TAB_OVERVIEW    = Component.translatable(PREFIX + "tab.overview");
    private static final Component TAB_WORKERS     = Component.translatable(PREFIX + "tab.workers");
    private static final Component TAB_PAYOUTS     = Component.translatable(PREFIX + "tab.payouts");
    private static final Component TAB_SHARES      = Component.translatable(PREFIX + "tab.shares");
    private static final Component TAB_MARKET      = Component.translatable(PREFIX + "tab.market");
    private static final Component TAB_STATISTICS  = Component.translatable(PREFIX + "tab.statistics");
    private static final Component TAB_DANGER      = Component.translatable(PREFIX + "tab.danger");

    /**
     * v2.1.0 — Market tab body is spec-compliant (see
     * {@link net.kroia.banksystem.screen.uiElements.tabbody.MarketTabBody}); the tab
     * shows whenever StockMarket is installed. Backend: {@code StockMarketBridge} +
     * {@code AsyncCompanyManager} functions {@code OPEN_SHARE_MARKET /
     * CLOSE_SHARE_MARKET / SET_MARKET_OPEN / IS_MARKET_OPEN /
     * MARKET_EXISTS_FOR_COMPANY}.
     */
    private static final boolean STOCKMARKET_INTEGRATION_READY = true;

    // ----- identity + resolved state -----
    private final int companyId;
    private final String companyName;
    private final UUID callerUUID;

    /** Filled by {@link #loadInfoAsync()}; consumed by tab bodies to render labels. */
    private AsyncCompanyManager.CompanyInfoOutput info;
    private boolean isFounder = false;
    private boolean canManage = false;
    /** True when rights were pre-resolved by the server — suppresses client-side re-fetch. */
    private boolean rightsPreloaded = false;

    // ----- persistent widgets -----
    private CloseButton closeButton;
    private Label titleLabel;
    private TabElement tabs;

    /** Screen-open latch — every async continuation gates on this so callbacks after
     *  close are dropped. */
    private static boolean screenIsOpen = false;

    public CompanyManagementScreen(int companyId, String companyName) {
        super(TITLE_KEY);
        this.companyId = companyId;
        this.companyName = companyName == null ? "" : companyName;
        this.callerUUID = safeCallerUUID();
        screenIsOpen = true;
        buildHeader();
        rebuildTabs();
        loadInfoAsync();
        loadRightsAsync();
    }

    /**
     * Constructor used when the server has pre-resolved rights (e.g. via
     * {@link net.kroia.banksystem.networking.general.S2COpenCompanyManagementPacket}).
     * Skips the client-side rights ARRS call — correct tabs are shown immediately.
     * Still fetches company info async for the overview tab content.
     */
    public CompanyManagementScreen(int companyId, String companyName,
                                   boolean serverIsFounder, boolean serverCanManage) {
        super(TITLE_KEY);
        this.companyId  = companyId;
        this.companyName = companyName == null ? "" : companyName;
        this.callerUUID = safeCallerUUID();
        this.isFounder  = serverIsFounder;
        this.canManage  = serverCanManage;
        this.rightsPreloaded = true; // suppress any rights re-fetch from loadInfoAsync
        screenIsOpen = true;
        buildHeader();
        rebuildTabs(); // correct tabs immediately (rights pre-loaded)
        loadInfoAsync(); // still fetch overview data async
        // loadRightsAsync() suppressed by rightsPreloaded flag
    }

    public int getCompanyId() { return companyId; }
    public String getCompanyName() { return companyName; }

    // ----- accessors consumed by the tab bodies (package screen.uiElements.tabbody) -----
    public AsyncCompanyManager.CompanyInfoOutput info() { return info; }
    public boolean canManageNow() { return canManage; }
    public boolean isFounderNow() { return isFounder; }
    public UUID callerUUID() { return callerUUID; }
    public net.kroia.banksystem.api.bankmanager.IClientBankManager clientBankManager() { return getBankManager(); }

    /** Spec §0.7 — re-fetch authoritative info after a mutation; rebuilds the tab tree. */
    public void refreshInfo() { loadInfoAsync(); }

    private UUID safeCallerUUID() {
        try { return Minecraft.getInstance().player.getUUID(); }
        catch (Throwable t) { return new UUID(0L, 0L); }
    }

    private String safeCallerName() {
        try { return Minecraft.getInstance().player.getDisplayName().getString(); }
        catch (Throwable t) { return ""; }
    }

    // ------------------------------------------------------------------
    // Async info + rights resolution
    // ------------------------------------------------------------------

    /**
     * Fetches the full {@link AsyncCompanyManager.CompanyInfoOutput} via the by-id
     * ARRS surface (uses {@code dispatchInput} internally, so it works on
     * singleplayer + slave + dedicated master). On success: populates
     * {@link CompanyInfoCache}, updates the title label, re-triggers the rights
     * lookup with the canonical name, and rebuilds the tab tree.
     */
    private void loadInfoAsync() {
        AsyncCompanyManager.getCompanyInfoByIdAsync(companyId).thenAccept(out -> {
            if (!screenIsOpen) return;
            Minecraft.getInstance().execute(() -> {
                if (!screenIsOpen) return;
                info = out;
                if (info != null && info.present()) {
                    CompanyInfoCache.put(info);
                    if (!rightsPreloaded) loadRightsAsync();
                    String myName = safeCallerName();
                    isFounder = info.founderNames() != null && info.founderNames().contains(myName);
                    if (titleLabel != null) {
                        titleLabel.setText(TITLE_KEY.getString() + ": " + info.name()
                                + " (ID=" + info.companyId() + ")");
                    }
                }
                rebuildTabs();
            });
        });
    }

    /**
     * Resolves whether the caller has MANAGE on the company's bound bank account
     * by asking master for the list of company names visible under the MANAGE
     * filter. Uses the canonical company name from the async info when available
     * so the compare is name-accurate (the constructor param may be blank when
     * the screen was opened by right-clicking a stamped share).
     */
    private void loadRightsAsync() {
        AsyncCompanyManager.listCompanyNamesForCallerAsync(callerUUID, AsyncCompanyManager.FILTER_MANAGE)
                .thenAccept(names -> {
                    if (!screenIsOpen) return;
                    Minecraft.getInstance().execute(() -> {
                        if (!screenIsOpen) return;
                        String nameForRights = (info != null && info.present()) ? info.name() : companyName;
                        canManage = names != null && !nameForRights.isEmpty() && names.contains(nameForRights);
                        rebuildTabs();
                    });
                });
    }

    // ------------------------------------------------------------------
    // Header + tab tree
    // ------------------------------------------------------------------

    private void buildHeader() {
        String initialName = companyName.isEmpty() ? "?" : companyName;
        titleLabel = new Label(TITLE_KEY.getString() + ": "
                + initialName + " (ID=" + companyId + ")");
        closeButton = new CloseButton(this::onClose);
        closeButton.setBackgroundColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);
    }

    /**
     * Rebuild the tab tree from scratch. Called from the constructor and from
     * every async continuation that changes visibility (info arrives → founder
     * status changes → Danger tab appears; rights arrive → Workers/Payouts
     * appear).
     */
    private void rebuildTabs() {
        int selectedIndex = tabs != null ? tabs.getSelectedTab() : 0;
        removeAllElements();
        addElement(titleLabel);
        addElement(closeButton);

        tabs = new TabElement();
        tabs.addTab(TAB_OVERVIEW.getString(), buildOverviewBody());
        if (canManage || isFounder) tabs.addTab(TAB_WORKERS.getString(), buildWorkersBody());
        if (canManage || isFounder) tabs.addTab(TAB_PAYOUTS.getString(), buildPayoutsBody());
        tabs.addTab(TAB_SHARES.getString(), buildSharesBody());
        if (STOCKMARKET_INTEGRATION_READY && Platform.isModLoaded("stockmarket")) {
            tabs.addTab(TAB_MARKET.getString(), buildMarketBody());
        }
        tabs.addTab(TAB_STATISTICS.getString(), buildStatisticsBody());
        if (isFounder) tabs.addTab(TAB_DANGER.getString(), buildDangerBody());

        if (selectedIndex >= 0 && selectedIndex < tabs.getTabCount()) {
            tabs.selectTab(selectedIndex);
        }
        addElement(tabs);
        updateLayout(getGui());
    }

    // ------------------------------------------------------------------
    // Tab bodies — GuiElement subclasses with explicit setBounds layout
    // (spec §0.5). See net.kroia.banksystem.screen.uiElements.tabbody.
    // ------------------------------------------------------------------

    private GuiElement buildOverviewBody() {
        return new net.kroia.banksystem.screen.uiElements.tabbody.OverviewTabBody(this);
    }

    private GuiElement buildWorkersBody() {
        return new net.kroia.banksystem.screen.uiElements.tabbody.WorkersTabBody(this);
    }

    private GuiElement buildPayoutsBody() {
        return new net.kroia.banksystem.screen.uiElements.tabbody.PayoutsTabBody(this);
    }

    private GuiElement buildSharesBody() {
        return new net.kroia.banksystem.screen.uiElements.tabbody.SharesTabBody(this);
    }

    private GuiElement buildMarketBody() {
        return new net.kroia.banksystem.screen.uiElements.tabbody.MarketTabBody(this);
    }

    private GuiElement buildStatisticsBody() {
        return new net.kroia.banksystem.screen.uiElements.tabbody.StatisticsTabBody(this);
    }

    private GuiElement buildDangerBody() {
        return new net.kroia.banksystem.screen.uiElements.tabbody.DangerTabBody(this);
    }

    // ------------------------------------------------------------------
    // Screen lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onClose() {
        screenIsOpen = false;
        super.onClose();
    }

    @Override
    protected void updateLayout(Gui gui) {
        if (titleLabel == null || closeButton == null) return;
        int padding = 5;
        int width = getWidth() - 2 * padding;

        titleLabel.setBounds(padding, padding, width - 25, 20);
        closeButton.setBounds(getWidth() - 20 - padding, padding, 20, 20);

        if (tabs != null) {
            int bodyTop = padding + 25;
            int bodyHeight = getHeight() - bodyTop - padding;
            if (bodyHeight < 40) bodyHeight = 40;
            tabs.setBounds(padding, bodyTop, width, bodyHeight);
        }
    }
}
