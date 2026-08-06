package net.kroia.banksystem.screen.custom;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.client.company.SharePresetRegistry;
import net.kroia.banksystem.networking.entity.SetStamperBindingRequest;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.PayoutsOverviewPanel;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TabElement;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task #51 (v2.0.8) — Dedicated company management UI.
 *
 * <p>Non-container client screen. All mutations go through existing ARRS surfaces
 * ({@link AsyncCompanyManager}). Tab visibility is decided from client-cached rights:
 * founder membership (name compared against the {@code CompanyInfoOutput.founderNames}
 * list, since the wire form ships names not UUIDs) and MANAGE (via
 * {@link AsyncCompanyManager#listCompanyNamesForCallerAsync}).
 *
 * <p>Task #51 fix — tabs are now backed by ModUtilities {@link TabElement} instead of
 * hand-rolled Buttons, and Overview labels resolve via {@link CompanyInfoCache} so the
 * canonical Company.name is visible even without the client opening the visuals editor.
 */
public class CompanyManagementScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";
    private static final Component TITLE_KEY = Component.translatable(PREFIX + "title");

    private static final Component TAB_OVERVIEW = Component.translatable(PREFIX + "tab.overview");
    private static final Component TAB_WORKERS  = Component.translatable(PREFIX + "tab.workers");
    private static final Component TAB_PAYOUTS  = Component.translatable(PREFIX + "tab.payouts");
    private static final Component TAB_SHARES   = Component.translatable(PREFIX + "tab.shares");
    private static final Component TAB_MARKET   = Component.translatable(PREFIX + "tab.market");
    private static final Component TAB_DANGER   = Component.translatable(PREFIX + "tab.danger");

    private static final Component NAME_LABEL   = Component.translatable(PREFIX + "name");
    private static final Component DESC_LABEL   = Component.translatable(PREFIX + "description");
    private static final Component MAX_SUPPLY   = Component.translatable(PREFIX + "max_supply");
    private static final Component ISSUED       = Component.translatable(PREFIX + "issued");
    private static final Component HOLDERS      = Component.translatable(PREFIX + "holders");
    private static final Component FOUNDERS     = Component.translatable(PREFIX + "founders");
    private static final Component EDIT_DESC    = Component.translatable(PREFIX + "edit_description");
    private static final Component SAVE         = Component.translatable(PREFIX + "save");

    private static final Component WORKERS_INFO = Component.translatable(PREFIX + "workers_info");
    private static final Component OPEN_BANK    = Component.translatable(PREFIX + "open_bank_account");

    private static final Component STAMPER_BINDINGS = Component.translatable(PREFIX + "stamper_bindings");
    private static final Component UNBIND        = Component.translatable(PREFIX + "unbind");
    private static final Component NO_STAMPERS   = Component.translatable(PREFIX + "no_stampers");
    private static final Component PRESET        = Component.translatable(PREFIX + "preset");
    private static final Component TINT          = Component.translatable(PREFIX + "tint");
    private static final Component DISPLAY_NAME  = Component.translatable(PREFIX + "display_name");
    private static final Component SHARES_INTRO  = Component.translatable(PREFIX + "shares_intro");

    private static final Component MARKET_UNAVAILABLE = Component.translatable(PREFIX + "market_unavailable");
    private static final Component CREATE_MARKET  = Component.translatable(PREFIX + "create_market");
    private static final Component OPEN_MARKET    = Component.translatable(PREFIX + "open_market");

    private static final Component TRANSFER_FOUNDER = Component.translatable(PREFIX + "transfer_founder");
    private static final Component DISSOLVE         = Component.translatable(PREFIX + "dissolve");
    private static final Component TRANSFER_HINT    = Component.translatable(PREFIX + "transfer_hint");
    private static final Component DISSOLVE_HINT    = Component.translatable(PREFIX + "dissolve_hint");
    private static final Component DISSOLVE_CONFIRM_TITLE = Component.translatable(PREFIX + "dissolve_confirm_title");
    private static final Component DISSOLVE_CONFIRM_MSG   = Component.translatable(PREFIX + "dissolve_confirm_msg");

    // Task #1 gate: flip to true when StockMarket integration lands.
    private static final boolean STOCKMARKET_INTEGRATION_READY = false;

    private final int companyId;
    private final String companyName;
    private final UUID callerUUID;

    private AsyncCompanyManager.CompanyInfoOutput info;
    private boolean isFounder = false;
    private boolean canManage = false;
    private boolean rightsResolved = false;

    // Persistent header
    private CloseButton closeButton;
    private Label titleLabel;

    // Tab container
    private TabElement tabs;

    // Shares tab state (kept across rebuilds so save reads the latest)
    private String sharesSelectedPreset = "";
    private int sharesTint = 0xFFFFFFFF;
    private String sharesDisplayName = "";
    private String sharesDescription = "";
    private TextBox sharesPresetBox;
    private TextBox sharesTintBox;
    private TextBox sharesDisplayNameBox;
    private TextBox sharesDescriptionBox;

    private TextBox descriptionEditBox;
    private TextBox transferTargetBox;
    private TextBox dissolveConfirmBox;

    private static boolean screenIsOpen = false;

    public CompanyManagementScreen(int companyId, String companyName) {
        super(TITLE_KEY);
        this.companyId = companyId;
        this.companyName = companyName == null ? "" : companyName;
        LocalPlayerHolder h = safeCaller();
        this.callerUUID = h.uuid;
        screenIsOpen = true;
        buildHeader();
        rebuildTabs();
        loadInfoAsync();
        loadRightsAsync();
    }

    public int getCompanyId() { return companyId; }
    public String getCompanyName() { return companyName; }

    private static class LocalPlayerHolder { UUID uuid; String name; }
    private LocalPlayerHolder safeCaller() {
        LocalPlayerHolder h = new LocalPlayerHolder();
        try {
            h.uuid = Minecraft.getInstance().player.getUUID();
            h.name = Minecraft.getInstance().player.getDisplayName().getString();
        } catch (Throwable t) {
            h.uuid = new UUID(0L, 0L);
            h.name = "";
        }
        return h;
    }

    private void loadInfoAsync() {
        AsyncCompanyManager.getCompanyInfoByIdAsync(companyId).thenAccept(out -> {
            if (!screenIsOpen) return;
            Minecraft.getInstance().execute(() -> {
                if (!screenIsOpen) return;
                info = out;
                if (info != null && info.present()) {
                    // Populate the shared client cache so tooltips + other screens self-heal.
                    CompanyInfoCache.put(info);
                    loadRightsAsync();
                    String myName = safeCaller().name;
                    isFounder = info.founderNames() != null && info.founderNames().contains(myName);
                    if (titleLabel != null) {
                        titleLabel.setText(TITLE_KEY.getString() + ": " + info.name());
                    }
                    ShareVisuals sv = ShareVisualCache.getVisualsOrPlaceholder(info.companyId());
                    if (sv != null) {
                        sharesSelectedPreset = sv.getIconPresetId() == null ? "" : sv.getIconPresetId();
                        sharesTint = sv.getTint();
                        sharesDisplayName = sv.getDisplayName() == null ? "" : sv.getDisplayName();
                        sharesDescription = sv.getDescription() == null ? "" : sv.getDescription();
                    }
                }
                rebuildTabs();
            });
        });
    }

    private void loadRightsAsync() {
        AsyncCompanyManager.listCompanyNamesForCallerAsync(callerUUID, AsyncCompanyManager.FILTER_MANAGE)
                .thenAccept(names -> {
                    if (!screenIsOpen) return;
                    Minecraft.getInstance().execute(() -> {
                        if (!screenIsOpen) return;
                        String nameForRights = (info != null && info.present()) ? info.name() : companyName;
                        canManage = names != null && !nameForRights.isEmpty() && names.contains(nameForRights);
                        rightsResolved = true;
                        rebuildTabs();
                    });
                });
    }

    private void buildHeader() {
        titleLabel = new Label(TITLE_KEY.getString() + ": "
                + (companyName.isEmpty() ? ("#" + companyId) : companyName));
        closeButton = new CloseButton(this::onClose);
        closeButton.setBackgroundColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);
    }

    private void rebuildTabs() {
        int selectedIndex = tabs != null ? tabs.getSelectedTab() : 0;
        removeAllElements();
        addElement(titleLabel);
        addElement(closeButton);

        tabs = new TabElement();
        // Overview (always visible)
        tabs.addTab(TAB_OVERVIEW.getString(), buildOverviewBody());
        if (canManage || isFounder) tabs.addTab(TAB_WORKERS.getString(), buildWorkersBody());
        if (canManage || isFounder) tabs.addTab(TAB_PAYOUTS.getString(), buildPayoutsBody());
        tabs.addTab(TAB_SHARES.getString(), buildSharesBody());
        if (STOCKMARKET_INTEGRATION_READY && Platform.isModLoaded("stockmarket")) {
            tabs.addTab(TAB_MARKET.getString(), buildMarketBody());
        }
        if (isFounder) tabs.addTab(TAB_DANGER.getString(), buildDangerBody());

        if (selectedIndex >= 0 && selectedIndex < tabs.getTabCount()) {
            tabs.selectTab(selectedIndex);
        }
        addElement(tabs);
        updateLayout(getGui());
    }

    private VerticalListView newTabBody() {
        VerticalListView body = new VerticalListView();
        LayoutGrid l = new LayoutGrid();
        l.columns = 1;
        l.spacing = 4;
        l.padding = 4;
        l.stretchX = true;
        l.stretchY = false;
        l.alignment = GuiElement.Alignment.TOP;
        body.setLayout(l);
        return body;
    }

    // ---------------- OVERVIEW ----------------
    private VerticalListView buildOverviewBody() {
        VerticalListView body = newTabBody();
        CompanyInfoCache.Snapshot snap = CompanyInfoCache.get(companyId);
        String name;
        String desc;
        long maxSupply;
        long issued;
        List<String> founders;
        if (info != null && info.present()) {
            name = info.name();
            desc = info.description();
            maxSupply = info.maxSupply();
            issued = info.totalSharesIssued();
            founders = info.founderNames();
        } else if (snap != null) {
            name = snap.name();
            desc = snap.description();
            maxSupply = snap.maxSupply();
            issued = snap.totalSharesIssued();
            founders = snap.founderNames();
        } else {
            name = companyName.isEmpty() ? ("#" + companyId) : companyName;
            desc = "-";
            maxSupply = 0L;
            issued = 0L;
            founders = List.of();
            CompanyInfoCache.tryLookup(companyId);
        }
        long cacheIssued = ShareVisualCache.getIssued(companyId);
        long cacheMax = ShareVisualCache.getMax(companyId);
        if (cacheMax > 0) maxSupply = cacheMax;
        if (cacheIssued > 0) issued = cacheIssued;

        body.addChild(new Label(NAME_LABEL.getString() + ": " + name));
        body.addChild(new Label(MAX_SUPPLY.getString() + ": " + maxSupply));
        body.addChild(new Label(ISSUED.getString() + ": " + issued));
        // TODO(v2.0.9): expose a holder count via a cheap ARRS query; leave "-" for now.
        body.addChild(new Label(HOLDERS.getString() + ": -"));
        body.addChild(new Label(FOUNDERS.getString() + ": "
                + (founders != null && !founders.isEmpty() ? String.join(", ", founders) : "-")));
        body.addChild(new Label(DESC_LABEL.getString() + ":"));

        if (canManage || isFounder) {
            descriptionEditBox = new TextBox();
            descriptionEditBox.setText(desc == null ? "" : desc);
            body.addChild(descriptionEditBox);
            Button save = new Button(EDIT_DESC.getString(), this::onSaveDescription);
            body.addChild(save);
        } else {
            body.addChild(new Label(desc == null ? "-" : desc));
        }
        return body;
    }

    private void onSaveDescription() {
        if (descriptionEditBox == null) return;
        String text = descriptionEditBox.getText();
        String cname = (info != null && info.present()) ? info.name() : companyName;
        AsyncCompanyManager.updateDescriptionAsync(cname, callerUUID, text)
                .thenAccept(out -> Minecraft.getInstance().execute(this::loadInfoAsync));
    }

    // ---------------- WORKERS ----------------
    private VerticalListView buildWorkersBody() {
        VerticalListView body = newTabBody();
        body.addChild(new Label(WORKERS_INFO.getString()));
        if (info != null && info.present()) {
            final int acc = info.bankAccountNr();
            Button open = new Button(OPEN_BANK.getString(),
                    () -> BankAccountManagementScreen.openScreen(acc, this, false));
            open.setEnabled(canManage || isFounder);
            body.addChild(open);
        }
        return body;
    }

    // ---------------- PAYOUTS ----------------
    private VerticalListView buildPayoutsBody() {
        VerticalListView body = newTabBody();
        if (info != null && info.present()) {
            PayoutsOverviewPanel panel = new PayoutsOverviewPanel(
                    this, companyId, info.bankAccountNr(), canManage || isFounder);
            body.addChild(panel);
        }
        return body;
    }

    // ---------------- SHARES ----------------
    private VerticalListView buildSharesBody() {
        boolean editable = canManage || isFounder;
        VerticalListView body = newTabBody();

        // Task #51 fix — read-only intro clarifying what the Shares tab governs.
        body.addChild(new Label(SHARES_INTRO.getString()));

        body.addChild(new Label(PRESET.getString() + ":"));
        sharesPresetBox = new TextBox();
        sharesPresetBox.setText(sharesSelectedPreset);
        sharesPresetBox.setEnabled(editable);
        body.addChild(sharesPresetBox);

        ListView presetList = new VerticalListView();
        LayoutGrid pl = new LayoutGrid();
        pl.columns = 1; pl.spacing = 0; pl.padding = 0;
        pl.stretchX = true; pl.stretchY = false;
        pl.alignment = GuiElement.Alignment.TOP;
        presetList.setLayout(pl);
        for (String id : SharePresetRegistry.orderedIds()) {
            Button row = new Button(id, () -> {
                if (!editable) return;
                sharesSelectedPreset = id;
                sharesPresetBox.setText(id);
            });
            row.setEnabled(editable);
            presetList.addChild(row);
        }
        body.addChild(presetList);

        body.addChild(new Label(TINT.getString() + " (hex ARGB):"));
        sharesTintBox = new TextBox();
        sharesTintBox.setText(String.format("%08X", sharesTint));
        sharesTintBox.setEnabled(editable);
        body.addChild(sharesTintBox);

        body.addChild(new Label(DISPLAY_NAME.getString() + ":"));
        sharesDisplayNameBox = new TextBox();
        sharesDisplayNameBox.setText(sharesDisplayName);
        sharesDisplayNameBox.setEnabled(editable);
        body.addChild(sharesDisplayNameBox);

        body.addChild(new Label(DESC_LABEL.getString() + ":"));
        sharesDescriptionBox = new TextBox();
        sharesDescriptionBox.setText(sharesDescription);
        sharesDescriptionBox.setEnabled(editable);
        body.addChild(sharesDescriptionBox);

        if (editable) {
            Button save = new Button(SAVE.getString(), this::onSaveShares);
            body.addChild(save);
        }

        body.addChild(new Label(STAMPER_BINDINGS.getString() + ":"));
        ListView stamperList = new VerticalListView();
        LayoutGrid sl = new LayoutGrid();
        sl.columns = 1; sl.spacing = 0; sl.padding = 0;
        sl.stretchX = true; sl.stretchY = false;
        sl.alignment = GuiElement.Alignment.TOP;
        stamperList.setLayout(sl);
        body.addChild(stamperList);
        Label emptyLabel = new Label(NO_STAMPERS.getString());
        body.addChild(emptyLabel);

        AsyncCompanyManager.listStamperBindingsAsync(companyId).thenAccept(out -> {
            if (!screenIsOpen) return;
            Minecraft.getInstance().execute(() -> {
                if (!screenIsOpen) return;
                stamperList.removeChilds();
                List<BlockPos> positions = out == null ? List.of() : out.positions();
                for (BlockPos pos : positions) {
                    Button row = new Button(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                            + "   [" + UNBIND.getString() + "]",
                            () -> {
                                if (!editable) return;
                                SetStamperBindingRequest.send(pos, 0);
                            });
                    row.setEnabled(editable);
                    stamperList.addChild(row);
                }
                emptyLabel.setText(positions.isEmpty() ? NO_STAMPERS.getString() : "");
            });
        });
        return body;
    }

    private void onSaveShares() {
        try {
            String hex = sharesTintBox.getText().trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            sharesTint = (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException ignored) { /* keep prior */ }
        String preset = sharesPresetBox.getText().trim();
        sharesSelectedPreset = preset;
        sharesDisplayName = sharesDisplayNameBox.getText();
        sharesDescription = sharesDescriptionBox.getText();
        AsyncCompanyManager.updateShareVisualsAsync(companyId, preset, sharesTint,
                sharesDisplayName, sharesDescription, callerUUID);
    }

    // ---------------- MARKET ----------------
    private VerticalListView buildMarketBody() {
        VerticalListView body = newTabBody();
        body.addChild(new Label(MARKET_UNAVAILABLE.getString()));
        Button create = new Button(CREATE_MARKET.getString(), () -> {});
        create.setEnabled(false);
        Button open = new Button(OPEN_MARKET.getString(), () -> {});
        open.setEnabled(false);
        body.addChild(create);
        body.addChild(open);
        return body;
    }

    // ---------------- DANGER ----------------
    private VerticalListView buildDangerBody() {
        VerticalListView body = newTabBody();
        body.addChild(new Label(TRANSFER_HINT.getString()));
        transferTargetBox = new TextBox();
        body.addChild(transferTargetBox);
        Button transferBtn = new Button(TRANSFER_FOUNDER.getString(), this::onTransferConfirmed);
        body.addChild(transferBtn);

        body.addChild(new Label(DISSOLVE_HINT.getString()));
        dissolveConfirmBox = new TextBox();
        body.addChild(dissolveConfirmBox);
        Button dissolveBtn = new Button(DISSOLVE.getString(), this::onDissolveClicked);
        body.addChild(dissolveBtn);
        return body;
    }

    private void onTransferConfirmed() {
        if (transferTargetBox == null) return;
        String target = transferTargetBox.getText().trim();
        if (target.isEmpty()) return;
        String cname = (info != null && info.present()) ? info.name() : companyName;
        AsyncCompanyManager.transferFounderAsync(cname, callerUUID, target)
                .thenAccept(out -> Minecraft.getInstance().execute(() -> {
                    if (out != null && out.resultCode() == 0) onClose();
                    else warn("[CompanyManagementScreen] transferFounder failed: "
                            + (out == null ? "null" : out.resultCode()));
                }));
    }

    private void onDissolveClicked() {
        if (dissolveConfirmBox == null) return;
        String typed = dissolveConfirmBox.getText();
        String cname = (info != null && info.present()) ? info.name() : companyName;
        if (!typed.equals(cname)) {
            warn("[CompanyManagementScreen] dissolve refused: typed name mismatch");
            return;
        }
        AskPopupScreen popup = new AskPopupScreen(
                this,
                () -> AsyncCompanyManager.dissolveCompanyAsync(cname, callerUUID)
                        .thenAccept(out -> Minecraft.getInstance().execute(() -> {
                            if (out != null && out.resultCode() == 0) onClose();
                            else warn("[CompanyManagementScreen] dissolve failed: "
                                    + (out == null ? "null" : out.resultCode()));
                        })),
                () -> {},
                DISSOLVE_CONFIRM_TITLE.getString(),
                DISSOLVE_CONFIRM_MSG.getString() + " " + cname);
        popup.setSize(400, 120);
        Minecraft.getInstance().setScreen(popup);
    }

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
