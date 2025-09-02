package net.kroia.banksystem.screen.custom;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.networking.request.UpdateBankAccountRequest;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.BankAccountManagementItem;
import net.kroia.banksystem.screen.uiElements.BankUserWidget;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ModChecker;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.kroia.modutilities.gui.screens.CreativeModeItemSelectionScreen;
import net.kroia.modutilities.gui.screens.ItemSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public class BankAccountManagementScreen extends BankSystemGuiScreen {
    private static final String PREFIX = "gui."+ BankSystemMod.MOD_ID+".bank_account_management_screen.";
    private static final Component TITLE = Component.translatable(PREFIX+"title");


    private static final Component SAVE_CHANGES = Component.translatable(PREFIX+"save_changes");
    private static final Component DELETE_ACCOUNT = Component.translatable(PREFIX+"delete_account");

    private static final Component CREATE_NEW_BANK = Component.translatable(PREFIX+"create_new_bank");
    private static final Component ADD_USER = Component.translatable(PREFIX+"add_user");

    private static class ItemViewButton extends Button{

        private final ItemView itemView;
        public ItemViewButton(ItemID item) {
            super("");

            itemView = new ItemView();
            if(item != null)
                itemView.setItemStack(item.getStack());
            else
                itemView.setItemStack(ItemStack.EMPTY);
            addChild(itemView);
            setSize(20, 20);
        }

        @Override
        protected void layoutChanged() {
            int width = getWidth();
            int height = getHeight();
            itemView.setBounds(0, 0, width, height);
        }
        public void setItem(ItemID item) {
            if(item != null)
                itemView.setItemStack(item.getStack());
            else
                itemView.setItemStack(ItemStack.EMPTY);
        }
        public void setItem(ItemStack itemStack) {
            itemView.setItemStack(itemStack);
        }
        public ItemID getItemID() {
            return new ItemID(itemView.getItemStack());
        }
    }

    private int accountNumber;
    private UserData personalBankOwnerData;
    private String bankAccountName;
    //private String playerName;
    private final GuiScreen parent;


    private Button selectAccountButton;
    private ItemViewButton accountIconButton;
    private Label accountNameLabel;
    private TextBox accountNameTextBox;
    private CloseButton closeButton;
    private Button saveChangesButton;
    private Button deleteBankAccountButton;
    private Button createNewBankButton;
    private Button addUserButton;
    private ListView userElementListView;
    private ListView bankElementListView;



    private int lastTickCount = 0;
    private final Map<ItemID, BankAccountManagementItem> bankAccountManagementItems = new HashMap<>();
    private final Map<ItemID, BankAccountManagementItem> createBankData = new HashMap<>();
    private final Map<UUID, BankUserWidget> bankUserWidgets = new HashMap<>();
    private final List<BankUserWidget> toRemoveUserWidgets = new ArrayList<>();
    private final boolean isAdminMode;
    private boolean canManage = false;
    private static boolean screenIsOpen = false;


    public BankAccountManagementScreen(GuiScreen parent, int accountNumber, boolean isAdminMode) {
        super(TITLE);
        super.setGuiScale(0.8f);
        this.isAdminMode = isAdminMode;
        this.parent = parent;
        this.accountNumber = accountNumber;



        setupGui(this.accountNumber);
    }
    public BankAccountManagementScreen(int accountNumber, boolean isAdminMode)
    {
        this(null, accountNumber, isAdminMode);
    }

    private void setupGui(int accountNumber)
    {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankAccountData(accountNumber, (bankAccountData) -> {
            assert bankAccountData != null;
            setupGui(bankAccountData);
        });
    }
    private void setupGui(BankAccountData bankAccountData)
    {
        boolean isEditingPlayer = false;
        UUID thisPlayerUUID = minecraft.player.getUUID();
        for(BankUserData userData : bankAccountData.users.values())
        {
            if(userData.userUUID.equals(thisPlayerUUID))
            {
                isEditingPlayer = BankPermission.hasPermission(userData.permissions, BankPermission.MANAGE.getValue());
                break;
            }
        }
        personalBankOwnerData = bankAccountData.personalBankOwnerData;
        bankAccountName = bankAccountData.accountName;
        this.canManage = (personalBankOwnerData != null && personalBankOwnerData.userUUID.equals(thisPlayerUUID)) || isAdminMode || isEditingPlayer;

        bankAccountManagementItems.clear();
        createBankData.clear();
        bankUserWidgets.clear();
        removeAllElements();
        setupCommon(bankAccountData);
        if(this.isAdminMode)
            setupAdminWindow();
        else
            setupUserWindow();


        updateBankData(bankAccountData);
        updateAccountData(bankAccountData);
        updateLayout(getGui());
    }
    private void setupCommon(BankAccountData data)
    {
        saveChangesButton = new Button(SAVE_CHANGES.getString(), this::onSaveChangesButtonClicked);
        addUserButton = new Button(ADD_USER.getString(), this::onAddUserButtonClicked);

        accountIconButton = new ItemViewButton(data.accountIcon);
        addElement(accountIconButton);
        if(canManage) {
            accountIconButton.setOnFallingEdge(this::onIconButtonClicked);
            accountNameTextBox = new TextBox();
            accountNameTextBox.setText(data.accountName);
            addElement(accountNameTextBox);

            deleteBankAccountButton = new Button(DELETE_ACCOUNT.getString(), this::onDeleteAccountButtonClicked);
            addElement(deleteBankAccountButton);
        }
        else {
            accountNameLabel = new Label();
            accountNameLabel.setText(data.accountName);
            addElement(accountNameLabel);

            addUserButton.setEnabled(false);
            saveChangesButton.setEnabled(false);
        }

        selectAccountButton = new Button(BankAccountSelectionScreen.TEXT.SELECT_ACCOUNT_BUTTON.getString());
        selectAccountButton.setOnFallingEdge(() -> {
            BankAccountSelectionScreen selectionScreen = new BankAccountSelectionScreen(this, Minecraft.getInstance().player.getUUID(), (accountNumber) -> {
                if(!screenIsOpen)
                    return;
                this.accountNumber = accountNumber;
                setupGui(accountNumber);
            });
            minecraft.setScreen(selectionScreen);
        });


        closeButton = new CloseButton(this::onClose);
        closeButton.setIdleColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);

        userElementListView = new VerticalListView();
        LayoutGrid userLayout = new LayoutGrid();
        userLayout.columns = 1;
        userLayout.spacing = 0;
        userLayout.padding = 0;
        userLayout.stretchX = true;
        userLayout.stretchY = false;
        userLayout.alignment = GuiElement.Alignment.TOP;
        userElementListView.setLayout(userLayout);


        bankElementListView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        bankElementListView.setLayout(layout);



        addElement(selectAccountButton);
        addElement(closeButton);
        addElement(userElementListView);
        addElement(bankElementListView);
        addElement(saveChangesButton);
        addElement(addUserButton);



    }
    private void setupAdminWindow()
    {
        createNewBankButton = new Button(CREATE_NEW_BANK.getString());
        createNewBankButton.setOnFallingEdge(() -> {
            BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankManagerData((minimalBankManagerData) -> {
                if(!screenIsOpen)
                    return;
                List<ItemStack> allowedItemStacks;
                if(minimalBankManagerData == null)
                {
                    allowedItemStacks = new ArrayList<>();
                }
                else {
                    allowedItemStacks = minimalBankManagerData.getAllowedItemStacks();
                }
                ItemSelectionScreen itemSelectionScreen = new ItemSelectionScreen(
                        this,
                        allowedItemStacks,
                        this::onCreateNewBank);
                itemSelectionScreen.sortItems();
                this.minecraft.setScreen(itemSelectionScreen);
            });
        });

        addElement(createNewBankButton);


    }
    private void setupUserWindow()
    {

    }

    public static void openScreen(int accountNumber, GuiScreen parent, boolean isAdminMode)
    {
        screenIsOpen = true;
        BankAccountManagementScreen screen = new BankAccountManagementScreen(parent, accountNumber, isAdminMode);
        Minecraft.getInstance().setScreen(screen);
    }
    public static void openScreen(int accountNumber, boolean isAdminMode)
    {
        screenIsOpen = true;
        BankAccountManagementScreen screen = new BankAccountManagementScreen(accountNumber, isAdminMode);
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int spacing = 5;
        int padding = 5;
        int width = getWidth()-2*padding;
        int height = getHeight()-2*padding;

        if(getElements().isEmpty())
            return; // Not initialized yet



        closeButton.setBounds(getWidth() - 20-padding, padding,20,20);
        int textWidth = closeButton.getTextWidth(SAVE_CHANGES.getString())+10;
        saveChangesButton.setBounds(closeButton.getLeft()-spacing-textWidth, padding, textWidth, closeButton.getHeight());


        int nameWidth = (width-spacing)/2;
        selectAccountButton.setBounds(padding, padding, nameWidth, 20);
        int accountNameY = selectAccountButton.getBottom()+spacing;
        accountIconButton.setBounds(padding, accountNameY, 20, 20);
        if(canManage) {
            textWidth = deleteBankAccountButton.getTextWidth(DELETE_ACCOUNT.getString())+10;
            deleteBankAccountButton.setBounds(saveChangesButton.getLeft()-spacing-textWidth, padding, textWidth, closeButton.getHeight());
            accountNameTextBox.setBounds(accountIconButton.getRight()+spacing, accountNameY, nameWidth-(accountIconButton.getRight()), 20);
        }else {
            accountNameLabel.setBounds(accountIconButton.getRight()+spacing, accountNameY, nameWidth-(accountIconButton.getRight()), 20);
        }

        addUserButton.setBounds(padding, selectAccountButton.getBottom()+accountNameY, nameWidth, closeButton.getHeight());
        userElementListView.setBounds(padding, addUserButton.getBottom()+spacing, nameWidth, height-(addUserButton.getBottom()+spacing)+padding);
        bankElementListView.setBounds(userElementListView.getRight()+spacing, closeButton.getBottom()+spacing, userElementListView.getWidth(), height-(closeButton.getBottom()+spacing)+padding);


        if(isAdminMode)
        {
            textWidth = createNewBankButton.getTextWidth(CREATE_NEW_BANK.getString())+10;
            int xOffset = deleteBankAccountButton!=null?deleteBankAccountButton.getWidth()+spacing:0;
            createNewBankButton.setBounds(saveChangesButton.getLeft()-spacing-textWidth-xOffset, padding, textWidth, closeButton.getHeight());
        }
    }

    @Override
    public void onClose() {
        screenIsOpen = false;
        if(parent != null && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
        else
            super.onClose();
    }


    private void onSaveChangesButtonClicked()
    {
        List<UpdateBankAccountRequest.InputData.BankData> bankData = new ArrayList<>();
        if(canManage) {
            for (BankAccountManagementItem item : bankAccountManagementItems.values()) {
                UpdateBankAccountRequest.InputData.BankData data = new UpdateBankAccountRequest.InputData.BankData();
                data.removeBank = item.deleteAccount();
                if(isAdminMode) {
                    data.resetLockedBalance = item.freeLockedBalance();
                    data.setBalance = item.balanceHasChanged();
                    data.balance = item.getBalance();
                }
                data.itemID = item.getItemID();
                bankData.add(data);
            }
        }
        if(isAdminMode) {
            for (BankAccountManagementItem item : createBankData.values()) {
                UpdateBankAccountRequest.InputData.BankData data = new UpdateBankAccountRequest.InputData.BankData();
                data.resetLockedBalance = item.freeLockedBalance();
                data.removeBank = item.deleteAccount();
                data.createBank = true;
                data.setBalance = item.balanceHasChanged();
                data.balance = item.getBalance();
                data.itemID = item.getItemID();
                bankElementListView.removeChild(item);
                bankData.add(data);
            }
            createBankData.clear();
        }
        Map<UUID, Integer> setUsers = new HashMap<>();
        for(BankUserWidget userWidget : bankUserWidgets.values())
        {
            BankUserData userData = userWidget.getUserData();
            setUsers.put(userData.userUUID, userData.permissions);
        }

        String accountName = null;
        if(accountNameTextBox != null)
        {
            accountName = accountNameTextBox.getText();
        }
        else if(accountNameLabel != null)
        {
            accountName = accountNameLabel.getText();
        }

        UpdateBankAccountRequest.InputData input = new UpdateBankAccountRequest.InputData(accountNumber, accountName, accountIconButton.getItemID(), bankData, setUsers);
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestUpdateBankAccount(input, this::setupGui);
    }
    private void updateBankData(BankAccountData bankAccountData)
    {

        if(!screenIsOpen)
            return;
        if(bankAccountData == null)
        {
            error("Failed to update bank data for bankaccount: " + accountNumber + ". MinimalBankUserData is null.");
            return;
        }

        personalBankOwnerData = bankAccountData.personalBankOwnerData;
        if(personalBankOwnerData != null)
        {
            deleteBankAccountButton.setEnabled(false);
        }
        if(!this.canManage)
        {
            accountNameLabel.setText(bankAccountData.accountName);
        }


        Map<ItemID, BankData> bankMap = bankAccountData.bankData;
        ArrayList<Pair<ItemID, BankData>> sortedBankAccounts = new ArrayList<>();
        for(var entry : bankMap.entrySet())
        {
            ItemID itemID = entry.getKey();
            BankData bankData = entry.getValue();
            if(bankData != null)
                sortedBankAccounts.add(new Pair<>(itemID, bankData));
        }
        sortedBankAccounts.sort((a, b) -> Long.compare(b.getSecond().balance, a.getSecond().balance));

        Map<ItemID, BankAccountManagementItem> toRemove = new HashMap<>(bankAccountManagementItems);
        for(Pair<ItemID, BankData> pair : sortedBankAccounts)
        {
            BankAccountManagementItem item = bankAccountManagementItems.get(pair.getFirst());
            BankData bankData = pair.getSecond();
            if(item == null)
            {
                item = new BankAccountManagementItem(pair.getFirst(), accountNumber, bankData.itemFractionScaleFactor, isAdminMode, canManage);
                item.setBalance(bankData.balance);
                bankAccountManagementItems.put(pair.getFirst(), item);
                bankElementListView.addChild(item);
            }
            toRemove.remove(pair.getFirst());
            item.setBalanceLabel(bankData.balance);
            item.setLockedBalance(bankData.lockedBalance);
            item.setTotalBalance(bankData.balance + bankData.lockedBalance);
        }
        for(BankAccountManagementItem item : toRemove.values())
        {
            bankElementListView.removeChild(item);
            bankAccountManagementItems.remove(item.getItemID());
        }






/*
        ArrayList<Pair<ItemID, SyncBankDataPacket.BankData>> sortedBankAccounts = BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getSortedBankData();
        playerName = BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getBankDataPlayerName();
        playerNameLabel.setText(BankSystemTextMessages.getBankAccountManagementBankOwnerMessage(playerName));
        HashMap<ItemID, BankAccountManagementItem> stillExistingItems = new HashMap<>();
        for(Pair<ItemID, SyncBankDataPacket.BankData> pair : sortedBankAccounts)
        {
            BankAccountManagementItem item = bankAccountManagementItems.get(pair.getFirst());
            SyncBankDataPacket.BankData bankData = pair.getSecond();
            if(item == null)
            {
                item = new BankAccountManagementItem(pair.getFirst(), playerName);
                item.setBalance(bankData.getBalance());
                bankAccountManagementItems.put(pair.getFirst(), item);
                bankElementListView.addChild(item);
            }
            stillExistingItems.put(pair.getFirst(), item);
            item.setBalanceLabel(bankData.getBalance());
            item.setLockedBalance(bankData.getLockedBalance());
            item.setTotalBalance(bankData.getBalance()+bankData.getLockedBalance());
        }
        HashMap<ItemID, BankAccountManagementItem> toRemove = new HashMap<>(bankAccountManagementItems);
        for(ItemID key : stillExistingItems.keySet())
            toRemove.remove(key);
        for(BankAccountManagementItem item : toRemove.values())
        {
            bankElementListView.removeChild(item);
            bankAccountManagementItems.remove(item.getItemID());
        }*/
    }
    public void updateAccountData(BankAccountData bankAccountData)
    {
        if(!screenIsOpen)
            return;
        if(bankAccountData == null)
        {
            error("Failed to update bank data for bankaccount: " + accountNumber + ". MinimalBankUserData is null.");
            return;
        }

        this.canManage = isAdminMode || bankAccountData.hasPermission(minecraft.player.getUUID(),BankPermission.MANAGE.getValue());
        this.personalBankOwnerData = bankAccountData.personalBankOwnerData;
        this.bankAccountName = bankAccountData.accountName;
        Map<UUID, BankUserWidget> toRemoveUsers = new HashMap<>(bankUserWidgets);
        for(BankUserData userData : bankAccountData.users.values())
        {
            UUID userUUID = userData.userUUID;
            BankUserWidget userWidget = bankUserWidgets.get(userUUID);
            if(userWidget == null)
            {

                userWidget = new BankUserWidget(userData, toRemoveUserWidgets::add, canManage, this);
                bankUserWidgets.put(userUUID, userWidget);
                userElementListView.addChild(userWidget);
            }
            else
            {
                toRemoveUsers.remove(userUUID);
            }
        }
        for(BankUserWidget userWidget : toRemoveUsers.values())
        {
            userElementListView.removeChild(userWidget);
            bankUserWidgets.remove(userWidget.getUserData().userUUID);
        }
    }
    private void onCreateNewBank(ItemStack item)
    {
        ItemID itemID = new ItemID(item);
        if(bankAccountManagementItems.containsKey(itemID) ||
                createBankData.containsKey(itemID))
            return;
       // UpdateBankAccountPacket.BankData data = new UpdateBankAccountPacket.BankData();

/*
        List<UpdateBankAccountPacket.BankData> bankData = new ArrayList<>();
        data.itemID = itemID;
        data.createBank = true;*/
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestItemFractionScaleFactor(itemID, (scaleFac)->
        {
            BankAccountManagementItem accountItem = new BankAccountManagementItem(itemID, accountNumber, scaleFac, isAdminMode, canManage);
            accountItem.setBalance(0);
            createBankData.put(itemID, accountItem);
            bankElementListView.addChild(accountItem);
            accountItem.setBalanceLabel(0);
            accountItem.setLockedBalance(0);
            accountItem.setTotalBalance(0);
        });


        //UpdateBankAccountPacket.sendPacket(accountNumber, bankData);
    }

    private void onAddUserButtonClicked()
    {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankManagerData((bankManagerData) -> {
            if(!screenIsOpen || bankManagerData == null)
                return;

            UserSelectionScreen userSelectionScreen = new UserSelectionScreen(
                    this,
                    (userData) -> {
                        if(bankUserWidgets.containsKey(userData.userUUID))
                            return;
                        BankUserData bankUserData = new BankUserData(userData.userUUID, userData.userName, false, BankPermission.DEPOSIT.getValue());
                        BankUserWidget userWidget = new BankUserWidget(bankUserData, toRemoveUserWidgets::add, canManage, this);
                        bankUserWidgets.put(userData.userUUID, userWidget);
                        userElementListView.addChild(userWidget);
                    });
            List<UserData> userDataList = new ArrayList<>(bankManagerData.userMapData.userMap.values().stream().toList());
            // remove already added users
            userDataList.removeIf(userData -> bankUserWidgets.containsKey(userData.userUUID));
            // remove bank owner
            if(personalBankOwnerData != null)
            {
                userDataList.removeIf(userData -> userData.userUUID.equals(personalBankOwnerData.userUUID));
            }

            userSelectionScreen.setUsers(userDataList);
            minecraft.setScreen(userSelectionScreen);
        });
    }

    private void onIconButtonClicked() {
        FeatureFlagSet enabledFeatures = minecraft.player.level().enabledFeatures();
        boolean showOperatorTab = false; // Set this to `true` if you need the operator tab

        boolean isPlayerInCreativeMode = minecraft.player.isCreative();

        if (isPlayerInCreativeMode)
        {
            CreativeModeItemSelectionScreen creativeModeItemSelectionScreen = new CreativeModeItemSelectionScreen((itemStack) ->
            {
                accountIconButton.setItem(itemStack);
                minecraft.setScreen(this);
            }, () ->
            {
                minecraft.setScreen(this);
            });
            Minecraft.getInstance().setScreen(creativeModeItemSelectionScreen);
        }
        else
        {
            List<ItemStack> allItems = ItemUtilities.getSearchCreativeItems("");
            ItemSelectionScreen itemSelectionScreen = new ItemSelectionScreen(
                    this,
                    allItems,
                    (itemStack)->{
                        accountIconButton.setItem(itemStack);
                        minecraft.setScreen(this);
                    }
            );
            itemSelectionScreen.sortItems();
            Minecraft.getInstance().setScreen(itemSelectionScreen);
        }
    }
    private void onDeleteAccountButtonClicked()
    {
        if(!canManage)
        {
            error("You do not have permission to delete this bank account.");
            return;
        }
        AskPopupScreen askPopupScreen = new AskPopupScreen(
                this,
                ()->{
                    // delete
                    getBankManager().requestDeleteBankAccount(accountNumber, (success) -> {
                        if(success)
                        {
                            info("Bank account deleted successfully.");
                            onClose();
                        }
                        else
                        {
                            error("Failed to delete bank account.");
                        }
                    });
                },
                ()->{},
                BankSystemTextMessages.getDeleteAccountAskPopupTitleMessage(bankAccountName),
                BankSystemTextMessages.getDeleteAccountAskPopupMessage(bankAccountName)
        );
        askPopupScreen.setSize(400,100);
        askPopupScreen.setColors(0xFFe8711c, 0xFFe04c12, 0xFFf22718, 0xFF70e815);
        minecraft.setScreen(askPopupScreen);
    }

    @Override
    public void tick() {
        ++lastTickCount;
        if(!toRemoveUserWidgets.isEmpty())
        {
            for(BankUserWidget widget : toRemoveUserWidgets)
            {
                userElementListView.removeChild(widget);
                bankUserWidgets.remove(widget.getUserData().userUUID);
            }
            toRemoveUserWidgets.clear();
        }
        if(lastTickCount > 20)
        {
            lastTickCount = 0;
            BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankAccountData(accountNumber, this::updateBankData);
        }
    }
}
