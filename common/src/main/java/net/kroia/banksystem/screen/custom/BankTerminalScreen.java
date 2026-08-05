package net.kroia.banksystem.screen.custom;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.entity.FillBankTerminalCraftingGridPacket;
import net.kroia.banksystem.networking.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.entity.UpdateBankTerminalCraftingSettingsPacket;
import net.kroia.banksystem.networking.general.BankAccountChangeStream;
import net.kroia.banksystem.screen.uiElements.AmountButtonGroup;
import net.kroia.banksystem.screen.uiElements.BankTerminalCraftingView;
import net.kroia.banksystem.util.BankCraftingMatcher;
import net.kroia.banksystem.util.BankSystemGuiContainerScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.geometry.Rectangle;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.kroia.modutilities.networking.client_server.streaming.StreamSystem;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BankTerminalScreen extends BankSystemGuiContainerScreen<BankTerminalContainerMenu>
{
    private class BankElement extends BankSystemGuiElement
    {
        public static final int HEIGHT = 20;
        private long targetAmount = 0;
        private ItemStack stack;
        private long wholeBankBalance;
        private final Rectangle itemStackHitBox;
        public final ItemID itemID;
        /** Task #38b: per-slot raw-units-per-item ratio; defaults to
         *  {@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR} until the
         *  first bank-data snapshot arrives (or forever, for unbound slots). */
        private long rawUnitsPerItem = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        private final TextBox amountBox;
        private final Label balanceLabel;
        private final AmountButtonGroup addAmountButtonGroup;
        /** Lower-case display name + item tag paths, matched against the filter query. */
        public final String searchableName;

        BankTerminalScreen parent;

        public BankElement(BankTerminalScreen parent, ItemStack stack, ItemID itemID, long rawBalance, long rawUnitsPerItem) {
            super(0, 0, 100, HEIGHT);
            this.parent = parent;
            this.stack = stack;
            this.itemID = itemID;
            this.searchableName = buildSearchableName(stack.getHoverName().getString(), stack);

            //int boxPadding = 2;

            addAmountButtonGroup = AmountButtonGroup.create(new long[]{1L, 10L, 32L, 64L},
                    this::addAmountFromButton,
                    ()->{setTargetAmount(0);},
                    this::getTargetAmount);
            addChild(addAmountButtonGroup);

            balanceLabel = new Label("");
            balanceLabel.setTextFontScale(0.8f);
            itemStackHitBox = new Rectangle(1,1,16,16);

            this.amountBox = new TextBox(0,0,0);
            this.amountBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 10,0));

            addChild(balanceLabel);
            addChild(amountBox);
            amountBox.setOnTextChanged((textBox) -> {
                saveAmount();
            });
            amountBox.setText(0);
            setHeight(addAmountButtonGroup.getHeight());
            addAmountButtonGroup.updateButtons();

            this.rawUnitsPerItem = rawUnitsPerItem > 0 ? rawUnitsPerItem : BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            wholeBankBalance = rawBalance / this.rawUnitsPerItem;
            balanceLabel.setText(formatCompactBalance(rawBalance));
            //addChild(receiveItemsFromMarketButton);
        }

        @Override
        protected void render() {
            drawItem(stack, itemStackHitBox.x, itemStackHitBox.y);

            if(itemStackHitBox.contains(getMousePos().x, getMousePos().y))
                drawTooltip(stack, getMousePos());
        }

        @Override
        protected boolean mouseClickedOverElement(int button) {
            // Clicking the item ICON selects this item as the desired crafting
            // product (recipe lookup + grid setup). The icon is the only part of
            // the row without its own child element, so this cannot conflict
            // with the amount textbox or the +/- buttons (children consume
            // their clicks first).
            if (button == 0 && itemStackHitBox.contains(getMousePos().x, getMousePos().y)) {
                parent.onBankItemProductClicked(itemID);
                return true;
            }
            return super.mouseClickedOverElement(button);
        }

        @Override
        protected void layoutChanged() {
            int width = getWidth()-17;
            int height = getHeight();
            int padding = 2;
            int balanceLabelWidth = width/4;
            itemStackHitBox.y = (height-16)/2;

            balanceLabel.setBounds(padding+17, padding, balanceLabelWidth, height-padding*2);
            amountBox.setBounds(balanceLabel.getRight(), padding, width/3, height-padding*2);
            addAmountButtonGroup.setBounds(amountBox.getRight()+1, 0, getWidth()-amountBox.getRight()-1, height);
        }
        public void setBankBalance(long amount, long rawUnitsPerItem) {
            this.rawUnitsPerItem = rawUnitsPerItem > 0 ? rawUnitsPerItem : BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            wholeBankBalance = amount / this.rawUnitsPerItem;
            balanceLabel.setText(formatCompactBalance(amount));

            if(targetAmount > wholeBankBalance) {
                targetAmount = wholeBankBalance;
                amountBox.setText(targetAmount);
                addAmountButtonGroup.updateButtons();
            }

        }
        private String formatCompactBalance(long rawAmount) {
            long ratio = rawUnitsPerItem > 0 ? rawUnitsPerItem : BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            double real = (double) rawAmount / ratio;
            if (real >= 1_000_000_000) return String.format(Locale.ROOT, "%.3fB", real / 1_000_000_000);
            if (real >= 1_000_000) return String.format(Locale.ROOT, "%.3fM", real / 1_000_000);
            if (real >= 10_000) return String.format(Locale.ROOT, "%.3fk", real / 1_000);
            return ServerBank.getFormattedAmountStatic(rawAmount, (int) ratio);
        }

        public long getTargetAmount()
        {
            //saveAmount();
            return targetAmount;
        }
        public void setTargetAmount(long amount)
        {
            this.targetAmount = amount;
            if(targetAmount > wholeBankBalance) {
                targetAmount = wholeBankBalance;
            }
            else if(targetAmount < 0)
            {
                targetAmount = 0;
            }

            amountBox.setText(targetAmount);
        }
        private void saveAmount() {
            try {
                String text = this.amountBox.getText();
                targetAmount = (text == null || text.isEmpty()) ? 0 : Long.parseLong(text);
            } catch (NumberFormatException e) {
                targetAmount = 0;
            }
            if(targetAmount > wholeBankBalance) {
                targetAmount = wholeBankBalance;
            }
            else if(targetAmount < 0)
            {
                targetAmount = 0;
            }
            amountBox.setText(targetAmount);
        }

        private void addAmountFromButton(long amount)
        {
            setTargetAmount(getTargetAmount() + amount);
        }
    }

    private static final Component REMOVE_EMPTY_BANKS_BUTTON_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.remove_empty_banks_button");
    private static final Component SEND_ITEMS_TO_BANK_BUTTON_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.send_items_to_bank_button");
    private static final Component RECEIVE_ITEMS_FROM_BANK_BUTTON_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.receive_items_from_bank_button");
    private static final Component INVENTORY_NAME_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.inventory_name");
    private static final Component FILTER_LABEL_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".filter");
    private static final Component USE_BANK_ITEMS_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.use_bank_items");
    private static final Component AUTO_DEPOSIT_OUTPUT_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.deposit_crafted_to_bank");


    private int lastTickCount = 0;
    private int tickCount = 0;
    private final ArrayList<BankElement> bankElements = new ArrayList<>();

    /**
     * Minimum comfortable width (in mod-scaled GUI pixels) for a single
     * {@link BankElement} — below this, the +/- amount buttons get squashed
     * horizontally. Calibrated empirically against the user's "GUI scale 5 +
     * JEI installed = 1 column" baseline: the bank list is ~220 px wide at
     * that setup, so 200 keeps it single-column while wider setups (small GUI
     * scales, JEI absent, high-res monitors) unlock more columns.
     */
    private static final int MIN_ELEMENT_WIDTH = 200;
    /** Hard cap on columns; anything beyond this is unreadable regardless of width. */
    private static final int MAX_COLUMNS = 6;

    BankTerminalContainerMenu menu;

    // Gui elements
    private final BankAccountSelectionScreen.AccountButton selectAccountButton;
    private final Button removeEmptyBankAccountsButton;
    private final Button sendItemsToBankButton;
    private final Button receiveItemsFromBankButton;
    private final Button balanceHistoryButton;
    private final Label searchLabel;
    private final TextBox searchField;
    private final VerticalListView itemListView;
    private final LayoutGrid bankListLayout;
    private final BankTerminalCraftingView inventoryView;
    private final CheckBox useBankItemsCheckBox;
    private final CheckBox autoDepositCheckBox;

    /** Current filter query (trimmed, lower-case). Transient — never persisted. */
    private String searchQuery = "";

    // --- Crafting state (Task: crafting table integration) ---
    // The checkbox preference is a GLOBAL per-player setting stored in
    // User.customData (master-side, follows the player across servers):
    //   customData -> "bankTerminalCrafting" -> "useBankItems"/"depositOutput"
    // Loaded on screen open via getUserCustomData(); saved via
    // updateUserCustomData() ONLY on genuine user toggles — never when the
    // checkboxes are forced off programmatically (permission gating, invalid
    // account), so a revoked account can never wipe the saved preference.
    private static final String CRAFT_PREFS_KEY = "bankTerminalCrafting";
    private static final String KEY_USE_BANK_ITEMS = "useBankItems";
    private static final String KEY_DEPOSIT_OUTPUT = "depositOutput";
    /**
     * customData key holding the player's last-selected account across ALL bank terminals.
     * Preferred over the per-block, per-player selection stored on the terminal block entity
     * so that opening any terminal restores the user's most recent choice.
     */
    private static final String KEY_LAST_ACCOUNT = "bankTerminalLastAccount";
    /** Guard so programmatic checkbox initialization does not echo a settings packet. */
    private boolean initializingCraftSettings = false;
    /** False while checkboxes change programmatically — suppresses preference writes. */
    private boolean persistCraftPreference = true;
    /** Saved global preference, valid once {@link #craftPreferenceLoaded}. */
    private boolean savedCraftUseBankItems = false;
    private boolean savedCraftDepositOutput = false;
    private boolean craftPreferenceLoaded = false;
    /** One-shot: the preference is applied once per screen open. */
    private boolean craftPreferenceApplied = false;
    /** Latest streamed bank data — client-side preview source for ghost icons. */
    private BankAccountData latestAccountData = null;
    /** Set when the ghost preview inputs (bank data / settings) changed. */
    private boolean ghostsDirty = false;
    /** Cheap signature of the crafting grid contents to detect changes per tick. */
    private int lastGridSignature = 0;

    public static int widthPercentage = 100;
    private final UUID playerUUID;
    private final String playerName;
    private static boolean screenIsOpen = false;
    private int selectedBankAccountNr = -1;
    //private int userPermission = 0;

    private UUID bankChangeStreamID = null;


    public BankTerminalScreen(BankTerminalContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        //super(pTitle);
        menu = pMenu;

        widthPercentage = (isJeiModLoaded()?70:100);

        // Hide only JEI's bookmark toggle + lookup-history toggle while the
        // Terminal is open. Ingredient list stays visible on the right (that
        // area is reserved via widthPercentage=70 and the exclusion areas
        // returned by getJeiExclusionAreas). The ModUtilities base class
        // wires the lifecycle — the flag is applied on init() and released
        // on onClose() / removed().
        setHideJeiButtons("bookmarkButton", "historyButton");

        screenIsOpen = true;
        playerUUID = getThisPlayerUUID();
        playerName = getThisPlayerName();

        selectAccountButton = new BankAccountSelectionScreen.AccountButton();
        selectAccountButton.setOnFallingEdge(() -> {
            BankAccountSelectionScreen selectionScreen = new BankAccountSelectionScreen(this, playerUUID, this::onBankaccountSelected);
            minecraft.setScreen(selectionScreen);
        });

        removeEmptyBankAccountsButton = new Button(REMOVE_EMPTY_BANKS_BUTTON_TEXT.getString());
        removeEmptyBankAccountsButton.setOnFallingEdge(() -> {
            if(selectedBankAccountNr > 0) {
                getBankManager().requestRemoveEmptyBanks(selectedBankAccountNr).thenAccept((removed) -> {
                    updateBankList();
                });
            }
        });
        sendItemsToBankButton = new Button(SEND_ITEMS_TO_BANK_BUTTON_TEXT.getString());
        sendItemsToBankButton.setOnFallingEdge(this::onTransmitItemsToBank);

        receiveItemsFromBankButton = new Button(RECEIVE_ITEMS_FROM_BANK_BUTTON_TEXT.getString());
        receiveItemsFromBankButton.setOnFallingEdge(this::onReceiveItemsFromBank);

        balanceHistoryButton = new Button("History");
        balanceHistoryButton.setOnFallingEdge(() -> {
            if (selectedBankAccountNr > 0) {
                BalanceHistoryScreen historyScreen = new BalanceHistoryScreen(this, selectedBankAccountNr);
                minecraft.setScreen(historyScreen);
            }
        });

        searchLabel = new Label(FILTER_LABEL_TEXT.getString());
        searchField = new TextBox();
        searchField.setOnTextChanged(this::onSearchChanged);

        itemListView = new VerticalListView(0, 0, 100, 100);
        bankListLayout = new LayoutGrid();
        bankListLayout.stretchX = true;
        // Column count is recomputed each layout pass in updateLayout() from the
        // actual bank-list width so it adapts to GUI scale + JEI presence.
        bankListLayout.columns = 1;
        itemListView.setLayout(bankListLayout);
        inventoryView = new BankTerminalCraftingView(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/bank_terminal.png", 256, 256));

        useBankItemsCheckBox = new CheckBox(USE_BANK_ITEMS_TEXT.getString(), (checked) -> onCraftingSettingsChanged());
        autoDepositCheckBox = new CheckBox(AUTO_DEPOSIT_OUTPUT_TEXT.getString(), (checked) -> onCraftingSettingsChanged());

        addElement(selectAccountButton);
        addElement(removeEmptyBankAccountsButton);
        addElement(sendItemsToBankButton);
        addElement(receiveItemsFromBankButton);
        addElement(balanceHistoryButton);
        addElement(searchLabel);
        addElement(searchField);
        addElement(itemListView);
        addElement(useBankItemsCheckBox);
        addElement(autoDepositCheckBox);
        addElement(inventoryView);


        // Initial account resolution + crafting-preference fetch. Both come from the
        // player's GLOBAL customData (persisted server-side, follows the player across
        // terminals and servers). The customData fetch is authoritative for account
        // selection; only when no last-selected account is stored do we fall back to
        // the per-block, per-player value on the terminal block entity, and finally to
        // the personal account.
        getBankManager().getUserCustomData().thenAccept(customData ->
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (!screenIsOpen) return;
                    int lastAccount = 0;
                    if (customData != null) {
                        var prefs = customData.getCompound(CRAFT_PREFS_KEY);
                        savedCraftUseBankItems = prefs.getBoolean(KEY_USE_BANK_ITEMS);
                        savedCraftDepositOutput = prefs.getBoolean(KEY_DEPOSIT_OUTPUT);
                        lastAccount = customData.getInt(KEY_LAST_ACCOUNT);
                    }
                    craftPreferenceLoaded = true;
                    ghostsDirty = true;

                    if (lastAccount > 0) {
                        setSelectedBankAccountNr(lastAccount);
                        // updateBankList falls back to the personal account if this
                        // one no longer exists / the user lost access to it.
                        updateBankList();
                        applyCraftPreferenceIfReady();
                    } else {
                        // No user-scoped last selection — legacy fallback: read the
                        // per-block, per-player value written by previous transfers.
                        getBankManager().requestBankTerminalData(pMenu.getBlockPos()).thenAccept(btd ->
                                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                    if (!screenIsOpen) return;
                                    setSelectedBankAccountNr(btd.selectedBankAccount());
                                    if (selectedBankAccountNr > 0) {
                                        updateBankList();
                                    } else {
                                        getBankManager().getPersonalBankAccountDataAsync(getThisPlayerUUID())
                                                .thenAccept(this::updateBankList);
                                    }
                                }));
                    }
                }));
    }

    /**
     * Applies the saved global crafting preference once per screen open, as soon
     * as both preconditions hold: the preference has been fetched AND a bank
     * account is selected (the settings packet needs a valid account and the L3
     * guard would otherwise revert the checkboxes). Restoring a checked box
     * fires the regular callback, which SENDS the settings packet (the server
     * menu starts with the flags off and runs its per-account permission
     * validation) but must not re-persist the value it just loaded. Boxes
     * already disabled by the permission gating are skipped — the preference
     * stays saved and re-applies on accounts where the permission exists.
     */
    private void applyCraftPreferenceIfReady() {
        if (craftPreferenceApplied || !craftPreferenceLoaded || selectedBankAccountNr <= 0)
            return;
        craftPreferenceApplied = true;
        persistCraftPreference = false;
        if (useBankItemsCheckBox.isCheckable())
            useBankItemsCheckBox.setChecked(savedCraftUseBankItems);
        if (autoDepositCheckBox.isCheckable())
            autoDepositCheckBox.setChecked(savedCraftDepositOutput);
        persistCraftPreference = true;
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.getWidth()*widthPercentage/100;
        int height = this.getHeight();

        int padding = 5;
        int spacing = 5;
        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();
        int columnX = width - inventoryWidth - padding;

        int historyButtonWidth = 50;
        sendItemsToBankButton.setBounds(columnX, padding, inventoryWidth - historyButtonWidth - spacing, 20);
        balanceHistoryButton.setBounds(sendItemsToBankButton.getRight() + spacing, padding, historyButtonWidth, 20);

        // Crafting checkboxes + container view, vertically centered in the space
        // below the buttons row.
        int checkboxHeight = 14;
        int columnContentHeight = checkboxHeight * 2 + 2 + spacing + inventoryHeight;
        int columnTop = sendItemsToBankButton.getBottom() + spacing;
        int freeSpace = height - columnTop - padding - columnContentHeight;
        int y = columnTop + Math.max(0, freeSpace / 2);
        useBankItemsCheckBox.setBounds(columnX, y, inventoryWidth, checkboxHeight);
        autoDepositCheckBox.setBounds(columnX, useBankItemsCheckBox.getBottom() + 2, inventoryWidth, checkboxHeight);
        inventoryView.setPosition(columnX, autoDepositCheckBox.getBottom() + spacing);

        int itemListViewWidth = inventoryView.getX()-padding*2;
        selectAccountButton.setBounds(padding, padding, itemListViewWidth, 20);
        removeEmptyBankAccountsButton.setBounds(padding, selectAccountButton.getBottom()+spacing, itemListViewWidth/2-spacing, sendItemsToBankButton.getHeight());
        receiveItemsFromBankButton.setBounds(removeEmptyBankAccountsButton.getRight()+spacing, removeEmptyBankAccountsButton.getTop(), itemListViewWidth - removeEmptyBankAccountsButton.getRight(), removeEmptyBankAccountsButton.getHeight());
        int searchHeight = 14;
        int searchLabelWidth = searchLabel.getTextWidth(searchLabel.getText()) + searchLabel.getPadding()*2;
        searchLabel.setBounds(padding, receiveItemsFromBankButton.getBottom()+spacing, searchLabelWidth, searchHeight);
        searchField.setBounds(searchLabel.getRight()+spacing, searchLabel.getTop(), itemListViewWidth - searchLabelWidth - spacing, searchHeight);
        // Dynamic column count: divide the available bank-list width by the
        // minimum comfortable element width. The layout is applied when
        // itemListView.setBounds triggers the scrollContainer relayout below,
        // so the columns update takes effect immediately.
        bankListLayout.columns = Math.min(MAX_COLUMNS, Math.max(1, itemListViewWidth / MIN_ELEMENT_WIDTH));
        itemListView.setBounds(padding, searchField.getBottom() + padding,
                itemListViewWidth, height -searchField.getBottom() - spacing - padding);
    }

    /**
     * Screen-space bounds of the visible GUI elements, used by the JEI plugin
     * as exclusion areas so JEI can place its ingredient list and overlay
     * buttons in the free right margin (reserved via {@code widthPercentage}
     * when JEI is loaded) instead of overlapping the bank list.
     */
    public List<Rect2i> getJeiExclusionAreas() {
        if (!isInitialized())
            return List.of();
        return buildJeiExclusionAreas(
                selectAccountButton,
                removeEmptyBankAccountsButton,
                receiveItemsFromBankButton,
                sendItemsToBankButton,
                balanceHistoryButton,
                searchLabel,
                searchField,
                itemListView,
                useBankItemsCheckBox,
                autoDepositCheckBox,
                inventoryView);
    }

    @Override
    public void onClose() {
        super.onClose();
        screenIsOpen = false;
        if(bankChangeStreamID != null)
        {
            StreamSystem.stopStream(bankChangeStreamID);
            bankChangeStreamID = null;
        }
        // JEI overlay button restore is handled by the ModUtilities base class.
    }
    @Override
    public void containerTick() {
        super.containerTick();
        handleTick();
    }

    public void handleTick() {
        /*tickCount++;
        if(tickCount - lastTickCount > 10)
        {
            lastTickCount = tickCount;
            updateBankList();
        }*/
        updateGhostPreviewIfNeeded();
    }

    /**
     * Recomputes the ghost icons when the crafting grid contents or the preview
     * inputs (bank data, checkbox state) changed. The grid is polled with a cheap
     * per-tick signature because slot contents change through the vanilla menu
     * sync, which offers no client-side callback.
     */
    private void updateGhostPreviewIfNeeded() {
        int signature = 1;
        var craftSlots = menu.getCraftSlots();
        for (int i = 0; i < craftSlots.getContainerSize(); i++) {
            ItemStack stack = craftSlots.getItem(i);
            signature = 31 * signature + (stack.isEmpty() ? 0 : (ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount()));
        }
        var ghostRecipe = menu.getGhostRecipe();
        signature = 31 * signature + (ghostRecipe != null ? ghostRecipe.id().hashCode() : 0);
        if (signature == lastGridSignature && !ghostsDirty)
            return;
        lastGridSignature = signature;
        ghostsDirty = false;
        recomputeGhostPreview();
    }

    /**
     * Client-side best-effort preview of the bank-sourced ingredients: runs the
     * same matcher as the server on the streamed bank data and shows the picked
     * items faded in the empty grid slots. Purely cosmetic — the server rematches
     * authoritatively and the take-result flow locks the real balances.
     * <p>
     * With an explicitly selected ghost recipe (JEI "+" in bank mode) whose
     * ingredients the bank cannot currently satisfy, the recipe's representative
     * layout is shown instead, so the player still sees what is needed.
     */
    private void recomputeGhostPreview() {
        var ghostRecipe = menu.getGhostRecipe();
        if (!useBankItemsCheckBox.isChecked() || minecraft == null || minecraft.level == null) {
            inventoryView.clearGhostStacks();
            return;
        }
        BankCraftingMatcher.Match match = latestAccountData == null ? null : BankCraftingMatcher.findMatch(
                minecraft.level, menu.getCraftSlots().getItems(), true, latestAccountData, ghostRecipe);
        if (match != null && match.usesBankItems()) {
            // Preview of the actual bank picks.
            ItemStack[] ghosts = new ItemStack[BankCraftingMatcher.GRID_SIZE];
            for (int i = 0; i < BankCraftingMatcher.GRID_SIZE; i++) {
                ItemID bankItem = match.bankPerSlot()[i];
                ghosts[i] = bankItem != null ? bankItem.getStack() : null;
            }
            inventoryView.setGhostStacks(ghosts);
        } else if (ghostRecipe != null) {
            // Selected recipe not (currently) bank-satisfiable — show its layout.
            inventoryView.setGhostStacks(BankCraftingMatcher.ghostLayout(ghostRecipe, menu.getCraftSlots().getItems()));
        } else {
            inventoryView.clearGhostStacks();
        }
    }

    /**
     * Called when either crafting checkbox is toggled by the user (or forced off
     * by a permission change): mirrors the state into the client menu (click-path
     * decision) and sends the settings to the server, which validates permissions
     * and persists the result.
     */
    private void onCraftingSettingsChanged() {
        if (initializingCraftSettings)
            return;
        // No account selected yet (terminal data still loading): a settings packet
        // sent now would carry an invalid account number and the server would
        // force everything off. Revert the toggle instead of sending; the boxes
        // become usable as soon as the account arrives moments later.
        if (selectedBankAccountNr <= 0 && (useBankItemsCheckBox.isChecked() || autoDepositCheckBox.isChecked())) {
            initializingCraftSettings = true;
            useBankItemsCheckBox.setChecked(false);
            autoDepositCheckBox.setChecked(false);
            initializingCraftSettings = false;
            menu.setClientCraftingSettings(false, false);
            return;
        }
        menu.setClientCraftingSettings(useBankItemsCheckBox.isChecked(), autoDepositCheckBox.isChecked());
        UpdateBankTerminalCraftingSettingsPacket.sendPacketToServer(
                menu.getBlockPos(),
                useBankItemsCheckBox.isChecked(),
                autoDepositCheckBox.isChecked(),
                selectedBankAccountNr);
        if (persistCraftPreference)
            saveCraftingPreference();
        ghostsDirty = true;
    }

    /**
     * Persists the current checkbox states as the player's global crafting
     * preference. Fetch-modify-write on the shared User.customData tag so keys
     * owned by other screens (e.g. the balance-history chart settings) are never
     * clobbered by a stale cached copy.
     */
    private void saveCraftingPreference() {
        final boolean useBankItems = useBankItemsCheckBox.isChecked();
        final boolean depositOutput = autoDepositCheckBox.isChecked();
        getBankManager().getUserCustomData().thenAccept(customData -> {
            var data = customData != null ? customData : new net.minecraft.nbt.CompoundTag();
            var prefs = data.getCompound(CRAFT_PREFS_KEY);
            prefs.putBoolean(KEY_USE_BANK_ITEMS, useBankItems);
            prefs.putBoolean(KEY_DEPOSIT_OUTPUT, depositOutput);
            data.put(CRAFT_PREFS_KEY, prefs);
            getBankManager().updateUserCustomData(data);
        });
    }

    private void setSelectedBankAccountNr(int selectedBankAccountNr) {
        if(selectedBankAccountNr == this.selectedBankAccountNr)
            return;
        if(bankChangeStreamID != null)
        {
            StreamSystem.stopStream(bankChangeStreamID);
            bankChangeStreamID = null;
        }

        this.selectedBankAccountNr = selectedBankAccountNr;

        // An account just became known — the saved crafting preference may now be
        // applicable (covers terminals whose per-block selection is 0 and the
        // personal-account fallback path; one-shot inside).
        if (selectedBankAccountNr > 0)
            applyCraftPreferenceIfReady();

        BankAccountChangeStream.InputData inputData = new BankAccountChangeStream.InputData(selectedBankAccountNr);
        bankChangeStreamID = BankSystemNetworking.BANKSYSTEM_ACCOUNT_CHANGE_STREAM.startServerToClient(inputData, (changedData)->
                {
                    updateBankList(changedData.changedData());
                },
                ()->
                {
                    bankChangeStreamID = null;
                });
    }

    private void updateBankList()
    {
        if(selectedBankAccountNr <= 0)
            return;
        getBankManager().getBankAccountDataAsync(selectedBankAccountNr).thenAccept(this::updateBankList);
    }
    private void updateBankList(BankAccountData minimalBankUserData)
    {
        if(!screenIsOpen)
            return;
        if(minimalBankUserData == null)
        {
            error("Failed to update bank data for player: " + playerName + ". BankAccountData is null.");
            getBankManager().getPersonalBankAccountDataAsync(getThisPlayerUUID()).thenAccept((data)->{
                if(data != null)
                    setSelectedBankAccountNr(data.accountNumber);
            });
            return;
        }
        selectAccountButton.setAccountData(minimalBankUserData);
        setSelectedBankAccountNr(minimalBankUserData.accountNumber);
        UUID thisPlayer = getThisPlayerUUID();

        boolean canWithdraw = minimalBankUserData.hasPermission(thisPlayer, BankPermission.WITHDRAW);
        boolean canDeposit = minimalBankUserData.hasPermission(thisPlayer, BankPermission.DEPOSIT);
        receiveItemsFromBankButton.setEnabled(canWithdraw);
        sendItemsToBankButton.setEnabled(canDeposit);

        // Crafting checkboxes mirror the account permissions: WITHDRAW gates
        // "Use Bank Items", DEPOSIT gates "Auto-deposit output". Forcing a checked
        // box off fires its callback, which also informs the server (which
        // enforces the same rules independently) — but must NOT overwrite the
        // player's saved global preference: the force-off is a per-account
        // session state, and the preference should re-apply on accounts/terminals
        // where the permission exists.
        useBankItemsCheckBox.setCheckable(canWithdraw);
        autoDepositCheckBox.setCheckable(canDeposit);
        if (!canWithdraw && useBankItemsCheckBox.isChecked()) {
            persistCraftPreference = false;
            useBankItemsCheckBox.setChecked(false);
            persistCraftPreference = true;
        }
        if (!canDeposit && autoDepositCheckBox.isChecked()) {
            persistCraftPreference = false;
            autoDepositCheckBox.setChecked(false);
            persistCraftPreference = true;
        }

        // Bank contents changed — the ghost preview may need to update.
        latestAccountData = minimalBankUserData;
        ghostsDirty = true;

        Map<ItemID, BankData> bankMap = minimalBankUserData.bankData;
        ArrayList<Pair<ItemID, BankData>> sortedBankAccounts = new ArrayList<>();
        for(var entry : bankMap.entrySet())
        {
            ItemID itemID = entry.getKey();
            BankData bankData = entry.getValue();
            // Task #24: skip items this client can't resolve (a mod present on the master but
            // not here) — they would render as minecraft:air / a wrong item. Display-only; the
            // balance still exists on the master and shows on servers that have the item.
            if(bankData != null && ItemIDManager.isResolvableOnThisServer(itemID))
                sortedBankAccounts.add(new Pair<>(itemID, bankData));
        }
        sortedBankAccounts.sort((a, b) -> Long.compare(b.getSecond().balance(), a.getSecond().balance()));

        int x = 0;
        int y = 0;

        boolean needsResize = sortedBankAccounts.size() != bankElements.size();
        HashMap<ItemID,ItemID> availableItems = new HashMap<>();
        for (Pair<ItemID, BankData> pair : sortedBankAccounts)
        {
            long balance = pair.getSecond().balance();
            long ratio = pair.getSecond().rawUnitsPerItem();
            BankElement element = getBankElement(pair.getFirst());
            if (element == null) {
                ItemStack stack = pair.getFirst().getStack();
                element = new BankElement(this, stack, pair.getFirst(), balance, ratio);
                bankElements.add(element);
                if (matchesFilter(element))
                    itemListView.addChild(element);
            } else {
                element.setBankBalance(balance, ratio);
            }
            if (needsResize)
                availableItems.put(pair.getFirst(), pair.getFirst());
        }

        if(needsResize)
        {
            // Remove the buttons that are not in the list
            ArrayList<BankElement> toRemove = new ArrayList<>();
            for (BankElement bankElement : bankElements) {
                if(!availableItems.containsKey(bankElement.itemID))
                    toRemove.add(bankElement);
            }
            bankElements.removeAll(toRemove);
            for(BankElement element : toRemove)
            {
                itemListView.removeChild(element);
            }
        }
    }

    private BankElement getBankElement(ItemID itemID)
    {
        for (BankElement button : bankElements) {
            if(button.itemID.equals(itemID))
                return button;
        }
        return null;
    }

    /**
     * Builds a search string from the item's display name + all tag paths.
     * Allows searching by tag (e.g. "log" matches items tagged minecraft:logs).
     * Same semantics as {@code BalanceHistoryScreen.buildSearchableName}.
     */
    private static String buildSearchableName(String displayName, ItemStack stack) {
        StringBuilder sb = new StringBuilder(displayName.toLowerCase());
        if (stack != null && !stack.isEmpty()) {
            stack.getTags().forEach(tagKey -> {
                String path = tagKey.location().getPath();
                sb.append(' ').append(path.replace('_', ' ').replace('/', ' '));
            });
        }
        return sb.toString();
    }

    private boolean matchesFilter(BankElement element) {
        return searchQuery.isEmpty() || element.searchableName.contains(searchQuery);
    }

    /**
     * Called when the filter textbox text changes. Rebuilds the visible list
     * from {@link #bankElements}, keeping only matching entries. An empty
     * query restores the full list. The query is transient — it is never
     * persisted and a reopened screen starts unfiltered.
     */
    private void onSearchChanged(String text) {
        searchQuery = text == null ? "" : text.trim().toLowerCase();
        itemListView.removeChilds();
        for (BankElement element : bankElements) {
            if (matchesFilter(element))
                itemListView.addChild(element);
        }
    }

    /**
     * Bank-list "craft this item": clicking an item icon in the bank list makes
     * that item the desired crafting product. Looks up every eligible crafting
     * recipe producing it, checks which are currently satisfiable (bank mode:
     * via the matcher against the streamed bank data; physical mode: against
     * the player + terminal inventories and grid — the same source range the
     * physical fill uses), then:
     * <ul>
     *   <li>exactly ONE satisfiable recipe → applied immediately</li>
     *   <li>otherwise (several satisfiable, or none) → the recipe selection
     *       window opens, satisfiable entries first, unsatisfiable ones marked
     *       but still selectable (ghost layouts intentionally support showing
     *       what is missing; the physical fill is best-effort)</li>
     *   <li>no recipe at all → the same window shows an explanatory message</li>
     * </ul>
     * Preview only — the server re-validates the selection on apply (existing
     * ghost / physical-fill packets).
     */
    void onBankItemProductClicked(ItemID productItemID) {
        if (minecraft == null || minecraft.level == null)
            return;
        ItemStack product = productItemID.getStack();
        if (product.isEmpty())
            return;
        List<RecipeHolder<CraftingRecipe>> recipes =
                BankCraftingMatcher.findRecipesForProduct(minecraft.level, product);

        boolean bankMode = useBankItemsCheckBox.isChecked();
        List<ItemStack> physicalStacks = bankMode ? List.of() : collectPhysicalSourceStacks();
        List<ItemStack> grid = menu.getCraftSlots().getItems();

        List<CraftingRecipeSelectionScreen.Candidate> satisfiable = new ArrayList<>();
        List<CraftingRecipeSelectionScreen.Candidate> unsatisfiable = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            boolean ok;
            if (bankMode) {
                BankCraftingMatcher.Match match = latestAccountData == null ? null
                        : BankCraftingMatcher.findMatch(minecraft.level, grid, true, latestAccountData, holder);
                // Physical-first stage 1 may return a DIFFERENT recipe for the
                // current grid — only count a match of THIS candidate.
                ok = match != null && match.recipe().id().equals(holder.id());
            } else {
                ok = BankCraftingMatcher.canSatisfyPhysically(holder, physicalStacks);
            }
            (ok ? satisfiable : unsatisfiable).add(new CraftingRecipeSelectionScreen.Candidate(holder, ok));
        }

        if (satisfiable.size() == 1) {
            applyProductRecipe(satisfiable.get(0).recipe());
            return;
        }
        List<CraftingRecipeSelectionScreen.Candidate> candidates = new ArrayList<>(satisfiable);
        candidates.addAll(unsatisfiable);
        minecraft.setScreen(new CraftingRecipeSelectionScreen(this, product, candidates, this::applyProductRecipe));
    }

    /** Applies a picked recipe: ghost layout in bank mode, physical fill otherwise. */
    private void applyProductRecipe(RecipeHolder<CraftingRecipe> recipe) {
        if (useBankItemsCheckBox.isChecked())
            menu.requestGhostRecipe(recipe); // existing ghost path (server-validated)
        else
            FillBankTerminalCraftingGridPacket.sendPacketToServer(menu.getBlockPos(), recipe.id());
        ghostsDirty = true;
    }

    /**
     * Read-only snapshot of the client-visible physical ingredient sources:
     * player hotbar + inventory, terminal block inventory and the current grid
     * contents (menu slots 0..71 — everything except the result slot), matching
     * the server-side fill's source range.
     */
    private List<ItemStack> collectPhysicalSourceStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < BankTerminalContainerMenu.CRAFT_RESULT_SLOT_INDEX; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty())
                stacks.add(stack);
        }
        return stacks;
    }

    private void onTransmitItemsToBank() {

        /*for(BankElement element : bankElements)
        {
            element.saveAmount();
            info("Sending item: "+element.itemID + " amount: "+element.getTargetAmount());
        }*/

        HashMap<ItemID, Long> itemTransferToBankAmounts = new HashMap<>();
        UpdateBankTerminalBlockEntityPacket.sendPacketToServer(this.menu.getBlockPos(), itemTransferToBankAmounts, true, selectedBankAccountNr);
        updateBankList();
    }
    private void onReceiveItemsFromBank() {
        for(BankElement element : bankElements)
        {
            element.saveAmount();

        }
        HashMap<ItemID, Long> itemTransferToMarketAmounts = new HashMap<>();
        for(BankElement button : bankElements)
        {
            long amount = button.getTargetAmount();
            if(amount > 0)
            {
                debug("Sending item: "+button.itemID + " amount: "+amount);
                itemTransferToMarketAmounts.put(button.itemID, amount);
            }
        }
        UpdateBankTerminalBlockEntityPacket.sendPacketToServer(this.menu.getBlockPos(), itemTransferToMarketAmounts, false, selectedBankAccountNr);
        updateBankList();
    }
    private void onBankaccountSelected(int accountNumber)
    {
        if(!screenIsOpen)
            return;
        setSelectedBankAccountNr(accountNumber);
        bankElements.clear();
        itemListView.removeChilds();
        latestAccountData = null;
        ghostsDirty = true;
        updateBankList();
        saveLastAccountPreference(accountNumber);
        // Re-target the server-side crafting settings to the newly selected
        // account (permissions are re-validated there; the streamed bank data of
        // the new account re-enables/disables the checkboxes via updateBankList).
        if (useBankItemsCheckBox.isChecked() || autoDepositCheckBox.isChecked())
            onCraftingSettingsChanged();
    }

    /**
     * Persists the user's last-selected account as a GLOBAL customData entry so any
     * terminal opened next restores this choice. Fetch-modify-write so keys owned by
     * other screens (crafting prefs, history chart settings) are not clobbered by a
     * stale cached copy.
     */
    private void saveLastAccountPreference(int accountNumber) {
        getBankManager().getUserCustomData().thenAccept(customData -> {
            var data = customData != null ? customData : new net.minecraft.nbt.CompoundTag();
            data.putInt(KEY_LAST_ACCOUNT, accountNumber);
            getBankManager().updateUserCustomData(data);
        });
    }
}