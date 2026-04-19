package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.menu.custom.BankDownloadContainerMenu;
import net.kroia.banksystem.networking.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.entity.SyncBankDownloadDataPacket;
import net.kroia.banksystem.util.BankSystemGuiContainerScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.Layout;
import net.kroia.modutilities.gui.layout.LayoutVertical;
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
            itemView = new ItemView(selectedItemID.getStack());
            itemView.setSize(16, 16);
            bankBalanceLabel = new Label("0");
            amountTextBox = new TextBox();
            amountTextBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 100,0));
            amountTextBox.setText("1");
            conditionSelectionDropDown = new DropDownMenu(TEXT.CONDITION.getString());
            //conditionSelectionDropDown.setZ(1000);
            //conditionSelectionDropDown.setExpanded(true);
            for(int i=0; i<BankDownloadBlockEntity.WithdrawCondition.values().length; i++)
            {
                BankDownloadBlockEntity.WithdrawCondition cond = BankDownloadBlockEntity.WithdrawCondition.values()[i];
                switch(cond)
                {
                    case NONE:
                    {
                        conditionSelectionDropDown.addOption(TEXT.CONDITION_NONE.getString());
                        //GuiElement el = conditionSelectionDropDown.getChilds().get(i);
                        //el.setHoverTooltipSupplier(TEXT.CONDITION_NONE_TOOLTIP::getString);
                        //el.setHoverTooltipMousePositionAlignment(Alignment.BOTTOM);
                        break;
                    }
                    case HAS_MORE_THEN:
                    {
                        conditionSelectionDropDown.addOption(TEXT.CONDITION_MORE_THAN.getString());
                        //GuiElement el = conditionSelectionDropDown.getChilds().get(i);
                        //el.setHoverTooltipSupplier(TEXT.CONDITION_MORE_THAN_TOOLTIP::getString);
                        //el.setHoverTooltipMousePositionAlignment(Alignment.BOTTOM);
                        break;
                    }
                    case HAS_LESS_THEN:
                    {
                        conditionSelectionDropDown.addOption(TEXT.CONDITION_LESS_THAN.getString());
                        //GuiElement el = conditionSelectionDropDown.getChilds().get(i);
                        //el.setHoverTooltipSupplier(TEXT.CONDITION_LESS_THAN_TOOLTIP::getString);
                        //el.setHoverTooltipMousePositionAlignment(Alignment.BOTTOM);
                        break;
                    }
                }
            }
            conditionSelectionDropDown.setOnOptionSelected((index, label) -> {
                condition = BankDownloadBlockEntity.WithdrawCondition.values()[index];
                conditionSelectionDropDown.setLabelText(WithdrawOrderGuiElement.getConditionString(condition));
                conditionSelectionDropDown.collapse();
            });

            conditionAmountTextBox = new TextBox();
            conditionAmountTextBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 100,0));
            conditionAmountTextBox.setText("0");
            deleteButton = new Button("X", () -> onRemoveOrder.accept(this));

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
        public ItemsList()
        {
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
            if(currentAccount == null) {
                // remove all bank balances
                toRemove.addAll(orderElements.values());
            }else {
                for (WithdrawOrderGuiElement element : orderElements.values()) {
                    element.updateBankBalance(account);
                }
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

        private final Button connectDisconnectButton;
        private final Button applyButton;
        private final ItemsList itemView;

        private final BankDownloadScreen parent;
        private int accountNr = 0;

        public SettingsMenu(BankDownloadScreen parent, int accountNr)
        {
            this.parent = parent;
            this.accountNr = accountNr;
            connectDisconnectButton = new Button((accountNr>0?TEXT.DISCONNECT_BUTTON.getString():TEXT.CONNECT_BUTTON.getString()), this::onSelectBankAccountButtonClicked);
            itemView = new ItemsList();
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

        @Override
        protected void render() {

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
            parent.minecraft.setScreen(selectionScreen);
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

    public BankDownloadScreen(BankDownloadContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        instance = this;
        this.pos = pMenu.getBlockPos();

        jeiModScreenWidthPercentage = (isJeiModLoaded()?70:100);



        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, TEXT.INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 256, 256));
        inventoryView.setSize(176, 166);
        inventoryView.setOnCloseEvent(this::onCloseCleanup);
        settingsMenu = new SettingsMenu(this, accountNr);



        addElement(inventoryView);
        addElement(settingsMenu);

        getBankManager().requestAllowdItems().thenAccept((allowedItems) -> {
            settingsMenu.setBankableItems(allowedItems);
            settingsMenu.setOrders(withdrawOrders);
            settingsMenu.setBankAccountNumber(accountNr);
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
        int width = (this.getWidth()*jeiModScreenWidthPercentage)/100;
        int height = this.getHeight();
        int spacing = 5;
        int padding = 5;

        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();

        settingsMenu.setBounds(padding,padding, width - inventoryWidth - spacing - padding*2, height - padding*2);
        inventoryView.setPosition(settingsMenu.getRight() + spacing, (height - inventoryHeight) / 2);
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
    }
    @Override
    public void onClose() {
        onCloseCleanup();
        super.onClose();
    }

    private void onConnectDisconnectButtonClicked(int accountNr)
    {
        BankDownloadScreen.accountNr = accountNr;
        settingsMenu.setBankAccountNumber(accountNr);
        getBankManager().getBankAccountDataAsync(accountNr).thenAccept(settingsMenu::setBankAccountData);
    }
    private void applySettigns()
    {
        sendUpdatePacket();
    }

    private void sendUpdatePacket()
    {
        UpdateBankDownloadBlockEntityPacket.sendPacket(pos, settingsMenu.getOrders(), accountNr);
    }
}
