package net.kroia.banksystem.screen.custom;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.general.BankAccountChangeStream;
import net.kroia.banksystem.screen.uiElements.AmountButtonGroup;
import net.kroia.banksystem.util.BankSystemGuiContainerScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.geometry.Rectangle;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.kroia.modutilities.networking.client_server.streaming.StreamSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
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

        private final TextBox amountBox;
        private final Label balanceLabel;
        private final AmountButtonGroup addAmountButtonGroup;

        BankTerminalScreen parent;

        public BankElement(BankTerminalScreen parent, ItemStack stack, ItemID itemID, long rawBalance) {
            super(0, 0, 100, HEIGHT);
            this.parent = parent;
            this.stack = stack;
            this.itemID = itemID;

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

            wholeBankBalance = (long)getBankManager().convertToRealAmount(rawBalance);
            String amountStr = ServerBank.getFormattedAmountStatic(rawBalance);
            balanceLabel.setText(amountStr);
            //addChild(receiveItemsFromMarketButton);
        }

        @Override
        protected void render() {
            drawItem(stack, itemStackHitBox.x, itemStackHitBox.y);

            if(itemStackHitBox.contains(getMousePos().x, getMousePos().y))
                drawTooltip(stack, getMousePos());
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
        public void setBankBalance(long amount) {
            wholeBankBalance = (long)getBankManager().convertToRealAmount(amount);
            String amountStr = ServerBank.getFormattedAmountStatic(amount);
            balanceLabel.setText(amountStr);

            if(targetAmount > wholeBankBalance) {
                targetAmount = wholeBankBalance;
                amountBox.setText(targetAmount);
                addAmountButtonGroup.updateButtons();
            }

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


    private int lastTickCount = 0;
    private int tickCount = 0;
    private final ArrayList<BankElement> bankElements = new ArrayList<>();

    BankTerminalContainerMenu menu;

    // Gui elements
    private final BankAccountSelectionScreen.AccountButton selectAccountButton;
    private final Button removeEmptyBankAccountsButton;
    private final Button sendItemsToBankButton;
    private final Button receiveItemsFromBankButton;
    private final Button balanceHistoryButton;
    private final VerticalListView itemListView;
    private final ContainerView<BankTerminalContainerMenu> inventoryView;

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

        itemListView = new VerticalListView(0, 0, 100, 100);
        LayoutGrid layoutGrid = new LayoutGrid();
        layoutGrid.stretchX = true;
        layoutGrid.columns = 2;
        itemListView.setLayout(layoutGrid);
        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 256, 256));
        inventoryView.setSize(176, 166);

        addElement(selectAccountButton);
        addElement(removeEmptyBankAccountsButton);
        addElement(sendItemsToBankButton);
        addElement(receiveItemsFromBankButton);
        addElement(balanceHistoryButton);
        addElement(itemListView);
        addElement(inventoryView);


        getBankManager().requestBankTerminalData(pMenu.getBlockPos()).thenAccept((bankTerminalData) -> {
            setSelectedBankAccountNr(bankTerminalData.selectedBankAccount());

            //userPermission = bankTerminalData.userPermission;

            if(selectedBankAccountNr > 0)
            {
                updateBankList();
            }
            else
            {
                getBankManager().getPersonalBankAccountDataAsync(getThisPlayerUUID()).thenAccept(this::updateBankList);
            }
        });


    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.getWidth()*widthPercentage/100;
        int height = this.getHeight();

        int padding = 5;
        int spacing = 5;
        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();
        inventoryView.setPosition(width - inventoryWidth - padding, (height - inventoryHeight) / 2);

        int historyButtonWidth = 50;
        sendItemsToBankButton.setBounds(inventoryView.getX(), padding, inventoryView.getWidth() - historyButtonWidth - spacing, 20);
        balanceHistoryButton.setBounds(sendItemsToBankButton.getRight() + spacing, padding, historyButtonWidth, 20);

        int itemListViewWidth = inventoryView.getX()-padding*2;
        selectAccountButton.setBounds(padding, padding, itemListViewWidth, 20);
        removeEmptyBankAccountsButton.setBounds(padding, selectAccountButton.getBottom()+spacing, itemListViewWidth/2-spacing, sendItemsToBankButton.getHeight());
        receiveItemsFromBankButton.setBounds(removeEmptyBankAccountsButton.getRight()+spacing, removeEmptyBankAccountsButton.getTop(), itemListViewWidth - removeEmptyBankAccountsButton.getRight(), removeEmptyBankAccountsButton.getHeight());
        itemListView.setBounds(padding, receiveItemsFromBankButton.getBottom() + padding,
                itemListViewWidth, height -receiveItemsFromBankButton.getBottom() - spacing - padding);
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

        receiveItemsFromBankButton.setEnabled(minimalBankUserData.hasPermission(thisPlayer, BankPermission.WITHDRAW));
        sendItemsToBankButton.setEnabled(minimalBankUserData.hasPermission(thisPlayer, BankPermission.DEPOSIT));

        Map<ItemID, BankData> bankMap = minimalBankUserData.bankData;
        ArrayList<Pair<ItemID, BankData>> sortedBankAccounts = new ArrayList<>();
        for(var entry : bankMap.entrySet())
        {
            ItemID itemID = entry.getKey();
            BankData bankData = entry.getValue();
            if(bankData != null)
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
            BankElement element = getBankElement(pair.getFirst());
            if (element == null) {
                ItemStack stack = pair.getFirst().getStack();
                element = new BankElement(this, stack, pair.getFirst(), balance);
                bankElements.add(element);
                itemListView.addChild(element);
            } else {
                element.setBankBalance(balance);
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
        updateBankList();
    }
}