package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.menu.custom.BankDownloadContainerMenu;
import net.kroia.banksystem.networking.entity.SyncBankDownloadDataPacket;
import net.kroia.banksystem.networking.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.util.BankSystemGuiContainerScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.client.ContainerView;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.Layout;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BankDownloadScreen extends BankSystemGuiContainerScreen<BankDownloadContainerMenu> {
    private static final class TEXT {
        private static final String prefix = "gui." + BankSystemMod.MOD_ID + ".bank_download_screen.";
        private static final Component INVENTORY_NAME_TEXT = Component.translatable(prefix + "inventory_name");
        private static final Component CONNECT_BUTTON = Component.translatable(prefix + "connect_button");
        private static final Component DISCONNECT_BUTTON = Component.translatable(prefix + "disconnect_button");
        private static final Component BANK_BALANCE = Component.translatable(prefix + "bank_balance");
        private static final Component TARGET_AMOUNT = Component.translatable(prefix + "target_amount");
        private static final Component CONDITION = Component.translatable(prefix + "condition");
        private static final Component CONDITION_VALUE = Component.translatable(prefix + "condition_value");
        private static final Component CONDITION_NONE = Component.translatable(prefix + "condition_none");
        private static final Component CONDITION_MORE_THAN = Component.translatable(prefix + "condition_more_than");
        private static final Component CONDITION_LESS_THAN = Component.translatable(prefix + "condition_less_than");
        private static final Component SAVE_BUTTON = Component.translatable(prefix + "save_button");



        private static final Component BANK_BALANCE_TOOLTIP = Component.translatable(prefix + "bank_balance.tooltip");
        private static final Component TARGET_AMOUNT_TOOLTIP = Component.translatable(prefix + "target_amount.tooltip");
        private static final Component CONDITION_TOOLTIP = Component.translatable(prefix + "condition.tooltip");
        private static final Component CONDITION_VALUE_TOOLTIP = Component.translatable(prefix + "condition_value.tooltip");
        private static final Component CONDITION_NONE_TOOLTIP = Component.translatable(prefix + "condition_none.tooltip");
        private static final Component CONDITION_MORE_THAN_TOOLTIP = Component.translatable(prefix + "condition_more_than.tooltip");
        private static final Component CONDITION_LESS_THAN_TOOLTIP = Component.translatable(prefix + "condition_less_than.tooltip");

        private static final Component ORDER_ITEM_TOOLTIP = Component.translatable(prefix + "order_item.tooltip");
        private static final Component ORDER_BALANCE_TOOLTIP = Component.translatable(prefix + "order_balance.tooltip");
        private static final Component ORDER_AMOUNT_TOOLTIP = Component.translatable(prefix + "order_amount.tooltip");
        private static final Component ORDER_CONDITION_TOOLTIP = Component.translatable(prefix + "order_condition.tooltip");
        private static final Component ORDER_CONDITION_VALUE_TOOLTIP = Component.translatable(prefix + "order_condition_value.tooltip");
        private static final Component ORDER_DELETE_TOOLTIP = Component.translatable(prefix + "order_delete.tooltip");
    }

    /** Condition-row tooltip. Hidden while a condition drop-down is open, except on its options: the open list covers the rows below. */
    private static void rowTooltip(GuiElement el, Supplier<String> text)
    {
        el.setHoverTooltipSupplier(() -> isCoveredByOpenDropDown(el) ? null : text.get());
        el.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.LEFT); // vertically centred; autoSide picks left/right
        GuiElement.HoverTooltipData data = el.getHoverTooltipData();
        data.autoSide = true;
        data.maxWidthFraction = 0.35f;
    }
    private static boolean isCoveredByOpenDropDown(GuiElement el)
    {
        if(instance == null)
            return false;
        for(WithdrawOrderGuiElement row : instance.settingsMenu.itemView.orderElements.values())
        {
            DropDownMenu dropDown = row.conditionSelectionDropDown;
            if(dropDown.isExpanded() && !dropDown.getChilds().contains(el))
                return true;
        }
        return false;
    }


    private static class WithdrawOrderGuiElement extends GuiElement
    {
        private final ItemID selectedItemID;
        private final ItemView itemView;
        private final Label bankBalanceLabel;
        private final TextBox amountTextBox;
        private final DropDownMenu conditionSelectionDropDown;
        private final TextBox conditionAmountTextBox;
        private final Button deleteButton;
        private BankDownloadBlockEntity.WithdrawCondition condition = BankDownloadBlockEntity.WithdrawCondition.NONE;


        public WithdrawOrderGuiElement(@NotNull ItemID selectedItemID, @NotNull Consumer<GuiElement> onRemoveOrder)
        {
            this.selectedItemID = selectedItemID;
            ItemStack stack = selectedItemID.getStack();
            itemView = new ItemView(stack);
            itemView.setSize(16, 16);
            itemView.setShowTooltip(false); // item name goes into the row tooltip instead of a second, stacked tooltip
            String itemName = stack.getHoverName().getString();
            rowTooltip(itemView, () -> itemName + "\n" + TEXT.ORDER_ITEM_TOOLTIP.getString());
            bankBalanceLabel = new Label("0");
            rowTooltip(bankBalanceLabel, TEXT.ORDER_BALANCE_TOOLTIP::getString);
            amountTextBox = new TextBox();
            amountTextBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 100,0));
            amountTextBox.setText("1");
            rowTooltip(amountTextBox, TEXT.ORDER_AMOUNT_TOOLTIP::getString);
            conditionSelectionDropDown = new DropDownMenu(TEXT.CONDITION.getString());
            rowTooltip(conditionSelectionDropDown, TEXT.ORDER_CONDITION_TOOLTIP::getString);
            for(BankDownloadBlockEntity.WithdrawCondition cond : BankDownloadBlockEntity.WithdrawCondition.values())
            {
                // DropDownMenu hit-tests its open list itself (it hangs below this 20px row), so plain
                // options get hover/tooltips. addOption() returns nothing: the new label is the last child.
                conditionSelectionDropDown.addOption(getConditionString(cond));
                GuiElement option = conditionSelectionDropDown.getChilds().getLast();
                Component tooltip = switch(cond) {
                    case NONE -> TEXT.CONDITION_NONE_TOOLTIP;
                    case HAS_MORE_THEN -> TEXT.CONDITION_MORE_THAN_TOOLTIP;
                    case HAS_LESS_THEN -> TEXT.CONDITION_LESS_THAN_TOOLTIP;
                };
                rowTooltip(option, tooltip::getString);
            }
            conditionSelectionDropDown.setOnOptionSelected((index, label) -> {
                condition = BankDownloadBlockEntity.WithdrawCondition.values()[index];
                conditionSelectionDropDown.setLabelText(WithdrawOrderGuiElement.getConditionString(condition));
                conditionSelectionDropDown.collapse();
            });

            conditionAmountTextBox = new TextBox();
            conditionAmountTextBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 100,0));
            conditionAmountTextBox.setText("0");
            rowTooltip(conditionAmountTextBox, TEXT.ORDER_CONDITION_VALUE_TOOLTIP::getString);
            deleteButton = new Button("X", () -> onRemoveOrder.accept(this));
            rowTooltip(deleteButton, TEXT.ORDER_DELETE_TOOLTIP::getString);

            this.addChild(itemView);
            this.addChild(bankBalanceLabel);
            this.addChild(amountTextBox);
            this.addChild(conditionSelectionDropDown);
            this.addChild(conditionAmountTextBox);
            this.addChild(deleteButton);

            this.setHeight(20);
        }

        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {
            int width = getWidth();
            int height = getHeight();

            int elementWidth = (width-40)/5;

            itemView.setBounds(0,0,20,height);
            bankBalanceLabel.setBounds(itemView.getRight(), 0, elementWidth, height);
            amountTextBox.setBounds(bankBalanceLabel.getRight(), 0, elementWidth, height);
            conditionSelectionDropDown.setBounds(amountTextBox.getRight(), 0, elementWidth*2, height);
            conditionAmountTextBox.setBounds(conditionSelectionDropDown.getRight(), 0, elementWidth, height);
            deleteButton.setBounds(conditionAmountTextBox.getRight(), 0, 20, height);
        }
        public int getDropDownExpandedHeight()
        {
            return conditionSelectionDropDown.getExpandedHeight();
        }
        public static String getConditionString(BankDownloadBlockEntity.WithdrawCondition condition)
        {
            switch(condition)
            {
                case NONE -> {
                    return TEXT.CONDITION_NONE.getString();
                }
                case HAS_MORE_THEN -> {
                    return TEXT.CONDITION_MORE_THAN.getString();
                }
                case HAS_LESS_THEN -> {
                    return TEXT.CONDITION_LESS_THAN.getString();
                }
            }
            return "";
        }
        public ItemID getSelectedItemID()
        {
            return selectedItemID;
        }
        public BankDownloadBlockEntity.WithdrawOrder getWithdrawOrder()
        {
            int amount = amountTextBox.getInt();
            if(amount < 0)
                amount = 0;
            amountTextBox.setText(String.valueOf(amount));

            int conditionAmount = conditionAmountTextBox.getInt();
            if(conditionAmount < 0)
                conditionAmount = 0;
            conditionAmountTextBox.setText(String.valueOf(conditionAmount));

            return new BankDownloadBlockEntity.WithdrawOrder(selectedItemID, amount, condition, conditionAmount);
        }
        public void updateBankBalance(BankAccountData account)
        {
            if(account == null)
            {
                bankBalanceLabel.setText("0");
                return;
            }
            BankData bank = account.bankData.get(selectedItemID);
            if(bank == null)
            {
                bankBalanceLabel.setText("0");
                return;
            }
            bankBalanceLabel.setText(bank.getNormalizedBalance());
        }
    }
    private static class ItemsList extends GuiElement
    {
        private final BankDownloadScreen screen;
        private final ItemSelectionView itemSelectionView;
        private final Label bankBalanceLabel;
        private final Label targetAmountLabel;
        private final Label conditionLabel;
        private final Label conditionValueLabel;
        private final ListView ordersListView;
        private BankAccountData currentAccount = null;
        private final Frame spacer;

        private final List<WithdrawOrderGuiElement> toRemove = new ArrayList<>();
        private final Map<ItemID, WithdrawOrderGuiElement> orderElements = new HashMap<>();
        public ItemsList(BankDownloadScreen screen)
        {
            this.screen = screen;
            itemSelectionView = new ItemSelectionView(this::onNewItemSelected);
            itemSelectionView.clearItems();

            bankBalanceLabel = new Label(TEXT.BANK_BALANCE.getString());
            targetAmountLabel = new Label(TEXT.TARGET_AMOUNT.getString());
            conditionLabel = new Label(TEXT.CONDITION.getString());
            conditionValueLabel = new Label(TEXT.CONDITION_VALUE.getString());
            this.addChild(bankBalanceLabel);
            this.addChild(targetAmountLabel);
            this.addChild(conditionLabel);
            this.addChild(conditionValueLabel);

            bankBalanceLabel.setAlignment(Alignment.CENTER);
            targetAmountLabel.setAlignment(Alignment.CENTER);
            conditionLabel.setAlignment(Alignment.CENTER);
            conditionValueLabel.setAlignment(Alignment.CENTER);

            bankBalanceLabel.setHoverTooltipSupplier(TEXT.BANK_BALANCE_TOOLTIP::getString);
            targetAmountLabel.setHoverTooltipSupplier(TEXT.TARGET_AMOUNT_TOOLTIP::getString);
            conditionLabel.setHoverTooltipSupplier(()->{
                return TEXT.CONDITION_TOOLTIP.getString()+"\n"+
                TEXT.CONDITION_NONE.getString() + ": " + TEXT.CONDITION_NONE_TOOLTIP.getString()+"\n"+
                TEXT.CONDITION_MORE_THAN.getString() + ": " + TEXT.CONDITION_MORE_THAN_TOOLTIP.getString()+"\n"+
                TEXT.CONDITION_LESS_THAN.getString() + ": " + TEXT.CONDITION_LESS_THAN_TOOLTIP.getString();
            });
            conditionValueLabel.setHoverTooltipSupplier(TEXT.CONDITION_VALUE_TOOLTIP::getString);

            bankBalanceLabel.setHoverTooltipMousePositionAlignment(Alignment.LEFT);
            targetAmountLabel.setHoverTooltipMousePositionAlignment(Alignment.LEFT);
            conditionLabel.setHoverTooltipMousePositionAlignment(Alignment.LEFT);
            conditionValueLabel.setHoverTooltipMousePositionAlignment(Alignment.LEFT);

            spacer = new Frame();
            spacer.setEnableBackground(false);
            spacer.setEnableOutline(false);
            //spacer.setHeight(0);


            ordersListView = new VerticalListView();
            Layout layout = new LayoutVertical();
            layout.stretchX = true;
            ordersListView.setLayout(layout);

            this.addChild(itemSelectionView);
            this.addChild(ordersListView);
        }

        public void setBankableItems(List<ItemID> items)
        {
            List<ItemStack> stacks = new ArrayList<>();
            for(ItemID itemID : items)
            {
                stacks.add(itemID.getStack());
            }
            itemSelectionView.setItems(stacks);
        }
        public void setBankAccountData(BankAccountData account)
        {
            currentAccount = account;
            for (WithdrawOrderGuiElement element : orderElements.values()) {
                element.updateBankBalance(account);
            }
        }
        public List<BankDownloadBlockEntity.WithdrawOrder> getOrders()
        {
            List<BankDownloadBlockEntity.WithdrawOrder> orders = new ArrayList<>();
            for(WithdrawOrderGuiElement element : orderElements.values())
            {
                orders.add(element.getWithdrawOrder());
            }
            return orders;
        }
        /** Order fields as typed (no normalising write-back like getOrders()); rows pending removal left out. */
        public Map<ItemID, String> getOrdersState()
        {
            Map<ItemID, String> state = new HashMap<>();
            for(WithdrawOrderGuiElement element : orderElements.values())
            {
                if(!toRemove.contains(element))
                    state.put(element.selectedItemID, element.amountTextBox.getText() + "|" + element.condition + "|" + element.conditionAmountTextBox.getText());
            }
            return state;
        }
        public void setOrders(List<BankDownloadBlockEntity.WithdrawOrder> orders)
        {
            ordersListView.removeChilds();
            orderElements.clear();
            for(BankDownloadBlockEntity.WithdrawOrder order : orders)
            {
                if(order == null || order.itemID == null)
                    continue;
                WithdrawOrderGuiElement element = new WithdrawOrderGuiElement(order.itemID, this::onRemoveOrder);
                orderElements.put(order.itemID, element);
                ordersListView.addChild(element);
                element.updateBankBalance(currentAccount);

                element.amountTextBox.setText(String.valueOf(order.targetAmount));
                element.condition = order.condition;
                switch(order.condition)
                {
                    case NONE -> element.conditionSelectionDropDown.setSelectedIndex(0);
                    case HAS_MORE_THEN -> element.conditionSelectionDropDown.setSelectedIndex(1);
                    case HAS_LESS_THEN -> element.conditionSelectionDropDown.setSelectedIndex(2);
                }
                element.conditionSelectionDropDown.setLabelText(WithdrawOrderGuiElement.getConditionString(order.condition));
                element.conditionAmountTextBox.setText(String.valueOf(order.conditionValue));
                spacer.setHeight(element.getDropDownExpandedHeight());
            }
            ordersListView.addChild(spacer);

        }


        @Override
        protected void renderBackground()
        {
            if(!toRemove.isEmpty()) {
                for (WithdrawOrderGuiElement element : toRemove) {
                    ordersListView.removeChild(element);
                    orderElements.remove(((WithdrawOrderGuiElement) element).getSelectedItemID());
                }
                toRemove.clear();
            }
            super.renderBackground();
        }
        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {
            int width = getWidth();
            int height = getHeight();


            itemSelectionView.setBounds(0,0,width,height/2);

            int elementWidth = (width-40)/5;
            bankBalanceLabel.setBounds(0, itemSelectionView.getBottom(), elementWidth+20, 20);
            targetAmountLabel.setBounds(bankBalanceLabel.getRight(), bankBalanceLabel.getTop(), elementWidth, bankBalanceLabel.getHeight());
            conditionLabel.setBounds(targetAmountLabel.getRight(), targetAmountLabel.getTop(), elementWidth*2, targetAmountLabel.getHeight());
            conditionValueLabel.setBounds(conditionLabel.getRight(), conditionLabel.getTop(), elementWidth, conditionLabel.getHeight());


            ordersListView.setBounds(0, bankBalanceLabel.getBottom(), width, height-itemSelectionView.getBottom() - conditionLabel.getHeight());
        }
        private void onNewItemSelected(ItemStack itemStack)
        {
            if(BankDownloadScreen.accountNr <= 0)
            {
                screen.settingsMenu.flashConnectButton(); // a withdraw order needs a bank
                return;
            }
            if(currentAccount == null)
                return;
            ItemID itemID = ItemID.of(itemStack);
            if(orderElements.containsKey(itemID))
                return;
            WithdrawOrderGuiElement orderElement = new WithdrawOrderGuiElement(itemID, this::onRemoveOrder);
            orderElements.put(itemID, orderElement);
            ordersListView.removeChild(spacer);
            ordersListView.addChild(orderElement);
            spacer.setHeight(orderElement.getDropDownExpandedHeight());
            ordersListView.addChild(spacer);
            orderElement.updateBankBalance(currentAccount);
        }
        private void onRemoveOrder(GuiElement element)
        {
            toRemove.add((WithdrawOrderGuiElement)element);
        }
    }

    private static class SettingsMenu extends BankSystemGuiElement {
        private static final int CONNECT_HINT_COLOR = 0xFFE8711C;  // orange: no bank connected
        private static final int CONNECT_FLASH_COLOR = 0xFFFFC04D; // bright orange: flash phase
        private static final int UNSAVED_COLOR = 0xFFD99A26;       // amber: Save button while there are unsaved edits
        private static final long FLASH_DURATION_MS = 1500;
        private static final long FLASH_PHASE_MS = 150;

        private final Button connectDisconnectButton;
        private final int defaultBackgroundColor;
        private final int defaultHoverColor;
        private final int defaultPressedColor;
        private long flashEndMs = 0;
        private final Button applyButton;
        private final ItemsList itemView;

        private final BankDownloadScreen parent;
        private int accountNr = 0;

        public SettingsMenu(BankDownloadScreen parent, int accountNr)
        {
            this.parent = parent;
            this.accountNr = accountNr;
            connectDisconnectButton = new Button((accountNr>0?TEXT.DISCONNECT_BUTTON.getString():TEXT.CONNECT_BUTTON.getString()), this::onSelectBankAccountButtonClicked);
            defaultBackgroundColor = connectDisconnectButton.getBackgroundColor();
            defaultHoverColor = connectDisconnectButton.getHoverColor();
            defaultPressedColor = connectDisconnectButton.getPressedColor();
            itemView = new ItemsList(parent);
            applyButton = new Button(TEXT.SAVE_BUTTON.getString(), parent::applySettigns);


            this.addChild(connectDisconnectButton);
            this.addChild(itemView);
            this.addChild(applyButton);
            //layoutChanged();
        }

        public void setBankAccountNumber(int accountNr)
        {
            this.accountNr = accountNr;
            if(accountNr>0)
            {
                connectDisconnectButton.setLabel(TEXT.DISCONNECT_BUTTON.getString());
            }
            else
            {
                connectDisconnectButton.setLabel(TEXT.CONNECT_BUTTON.getString());
            }
        }

        public void setBankableItems(List<ItemID> items)
        {
            itemView.setBankableItems(items);
        }
        public void setBankAccountData(BankAccountData account)
        {
            itemView.setBankAccountData(account);
        }
        public List<BankDownloadBlockEntity.WithdrawOrder> getOrders()
        {
            return itemView.getOrders();
        }
        public void setOrders(List<BankDownloadBlockEntity.WithdrawOrder> orders)
        {
            itemView.setOrders(orders);
        }

        public void flashConnectButton()
        {
            flashEndMs = System.currentTimeMillis() + FLASH_DURATION_MS;
        }

        @Override
        protected void render() {
            setButtonColor(applyButton, parent.hasUnsavedChanges() ? UNSAVED_COLOR : 0);
            // Orange while no bank is connected, flashing after a blocked action.
            long now = System.currentTimeMillis();
            int connectColor = accountNr > 0 ? 0
                    : (now < flashEndMs && (now / FLASH_PHASE_MS) % 2 == 0) ? CONNECT_FLASH_COLOR : CONNECT_HINT_COLOR;
            setButtonColor(connectDisconnectButton, connectColor);
        }
        /** color 0 = default button colors */
        private void setButtonColor(Button button, int color)
        {
            button.setBackgroundColor(color == 0 ? defaultBackgroundColor : color);
            button.setHoverColor(color == 0 ? defaultHoverColor : ColorUtilities.setBrightness(color, 0.8f));
            button.setPressedColor(color == 0 ? defaultPressedColor : ColorUtilities.setBrightness(color, 0.6f));
        }

        @Override
        protected void layoutChanged() {
            int padding = 2;
            int width = this.getWidth()-padding*2;
            int height = this.getHeight()-padding*2;

            connectDisconnectButton.setBounds(padding, padding, width/2, 20);
            applyButton.setBounds(connectDisconnectButton.getRight(), padding, width-connectDisconnectButton.getWidth() , connectDisconnectButton.getHeight());
            itemView.setBounds(padding, connectDisconnectButton.getBottom()+padding, width, height-padding-20);

        }
        private void onSelectBankAccountButtonClicked()
        {
            if(accountNr>0)
            {
                parent.onConnectDisconnectButtonClicked(0); // Disconnect
                return;
            }
            BankAccountSelectionScreen selectionScreen = new BankAccountSelectionScreen(parent, parent.minecraft.player.getUUID(), parent::onConnectDisconnectButtonClicked, BankPermission.DEPOSIT.getValue());
            net.kroia.modutilities.gui.client.ClientCompat.setScreen(selectionScreen);
        }

    }


    private final BlockPos pos;
    private final ContainerView<BankDownloadContainerMenu> inventoryView;
    private final SettingsMenu settingsMenu;





    public static List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders;
    public static int blockInventorySlotCount;
    public static int accountNr = 0; // 0 means no account selected



    private static BankDownloadScreen instance;
    private static int jeiModScreenWidthPercentage = 100;
    private int tickCount = 0;
    private Object savedState = null; // orders + connection as last loaded/saved; null until loaded

    public BankDownloadScreen(BankDownloadContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        //setGuiScale(BankSystemGuiScreen.guiScale-0.2f);
        instance = this;
        this.pos = pMenu.getBlockPos();

        // Hide JEI's ingredient list + bookmark overlays (and their overlay
        // buttons) while this screen is open. The ModUtilities base class
        // wires the lifecycle — the flag is applied on init() and released
        // on onClose() / removed().
        setHideJeiOverlay(true);

        jeiModScreenWidthPercentage = (isJeiModLoaded()?70:100);



        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, TEXT.INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 256, 256)) {
            @Override
            public void onClose() {
                // Inventory key / touchscreen close path; bypasses BankDownloadScreen.onClose().
                if (hasUnsavedChanges()) {
                    openUnsavedChangesPrompt();
                    return;
                }
                super.onClose();
            }
        };
        inventoryView.setSize(176, 166);
        inventoryView.setOnCloseEvent(this::onCloseCleanup);
        settingsMenu = new SettingsMenu(this, accountNr);



        addElement(inventoryView);
        addElement(settingsMenu);

        getBankManager().requestAllowdItems().thenAccept((allowedItems) -> {
            settingsMenu.setBankableItems(allowedItems);
            settingsMenu.setOrders(withdrawOrders);
            settingsMenu.setBankAccountNumber(accountNr);
            markClean(); // settings loaded from the block entity
        });
        getBankManager().getBankAccountDataAsync(accountNr).thenAccept(settingsMenu::setBankAccountData);
    }
    @Override
    public void containerTick() {
        super.containerTick();
        handleTick();
    }

    public void handleTick() {
        tickCount++;
        if(tickCount > 10)
        {
            tickCount = 0;
            getBankManager().getBankAccountDataAsync(accountNr).thenAccept(settingsMenu::setBankAccountData);
        }
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.getWidth();
        int height = this.getHeight();
        int spacing = 5;
        int padding = 5;

        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();

        settingsMenu.setBounds(padding,padding, width - inventoryWidth - spacing - padding*2, height - padding*2);
        inventoryView.setPosition(settingsMenu.getRight() + spacing, (height - inventoryHeight) / 2);
    }

    /**
     * Screen-space bounds of the visible GUI elements, used by the JEI plugin
     * as exclusion areas. Without them JEI only knows about the vanilla
     * centered container rectangle and overlaps the settings panel with its
     * ingredient list and overlay buttons.
     */
    public List<Rect2i> getJeiExclusionAreas() {
        if (!isInitialized())
            return List.of();
        return buildJeiExclusionAreas(settingsMenu, inventoryView);
    }

    public static void handlePacket(SyncBankDownloadDataPacket packet) {
        accountNr = packet.getAccountNr();
        withdrawOrders = packet.getWithdrawOrders();
        blockInventorySlotCount = packet.getBlockInventorySlotCount();
    }


    private void onCloseCleanup()
    {
        instance = null;
        blockInventorySlotCount = 0;
        accountNr = 0; // Reset account number when closing the screen
        // JEI overlay restore is handled by the ModUtilities base class.
    }
    @Override
    public void onClose() {
        if(hasUnsavedChanges())
        {
            openUnsavedChangesPrompt();
            return;
        }
        onCloseCleanup();
        super.onClose();
    }

    /** Unsaved edits: orders or connection differ from the last loaded/saved state, so edits reverted by hand count as clean. */
    private boolean hasUnsavedChanges()
    {
        return savedState != null && !savedState.equals(currentState());
    }
    private Object currentState()
    {
        return List.of(accountNr, settingsMenu.itemView.getOrdersState());
    }
    private void markClean()
    {
        savedState = currentState();
    }

    /**
     * Save / Discard prompt. Both callbacks run after the popup has switched back
     * to this screen; the state is marked clean first so the close does not prompt
     * again and closes the container menu as usual.
     */
    private void openUnsavedChangesPrompt()
    {
        AskPopupScreen.openUnsavedChangesPopup(this,
                () -> { markClean(); onClose(); },
                () -> { applySettigns(); onClose(); });
    }

    private void onConnectDisconnectButtonClicked(int accountNr)
    {
        BankDownloadScreen.accountNr = accountNr;
        settingsMenu.setBankAccountNumber(accountNr);
        if(accountNr <= 0) {
            sendUpdatePacket();
            return;
        }
        // the connection is only stored on Save (hasUnsavedChanges() sees the changed accountNr)
        getBankManager().getBankAccountDataAsync(accountNr).thenAccept(settingsMenu::setBankAccountData);
    }
    private void applySettigns()
    {
        sendUpdatePacket();
    }

    private void sendUpdatePacket()
    {
        UpdateBankDownloadBlockEntityPacket.sendPacket(pos, settingsMenu.getOrders(), accountNr);
        markClean(); // after getOrders(): it normalises the text fields
    }
}
