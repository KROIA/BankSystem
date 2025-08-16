package net.kroia.banksystem.screen.custom;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.networking.packet.client_sender.update.UpdateBankAccountPacket;
import net.kroia.banksystem.screen.uiElements.BankAccountManagementItem;
import net.kroia.banksystem.screen.uiElements.BankUserWidget;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.kroia.modutilities.gui.screens.ItemSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public class BankAccountManagementScreen extends BankSystemGuiScreen {
    private static final String PREFIX = "gui."+ BankSystemMod.MOD_ID+".bank_account_management_screen.";
    private static final Component TITLE = Component.translatable(PREFIX+"title");

    private static final Component SAVE_CHANGES = Component.translatable(PREFIX+"save_changes");
    private static final Component CREATE_NEW_BANK = Component.translatable(PREFIX+"create_new_bank");
    private static final Component ADD_USER = Component.translatable(PREFIX+"add_user");

    private final int accountNumber;
    private UserData personalBankOwnerData;
    //private String playerName;
    private final GuiScreen parent;

    private Label accountNameLabel;
    private CloseButton closeButton;
    private Button saveChangesButton;
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
    private static boolean screenIsOpen = false;


    public BankAccountManagementScreen(GuiScreen parent, int accountNumber, boolean isAdminMode) {
        super(TITLE);
        this.isAdminMode = isAdminMode;
        this.parent = parent;
        this.accountNumber = accountNumber;

        if(this.isAdminMode)
            setupAdminWindow();
        else
            setupUserWindow();

        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankAccountData(accountNumber, (bankAccountData) -> {
            updateBankData(bankAccountData);
            updateAccountData(bankAccountData);
        });
    }
    public BankAccountManagementScreen(int accountNumber, boolean isAdminMode)
    {
        this(null, accountNumber, isAdminMode);
    }
    private void setupAdminWindow()
    {
        accountNameLabel = new Label();

        closeButton = new CloseButton(this::onClose);
        closeButton.setIdleColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);

        saveChangesButton = new Button(SAVE_CHANGES.getString(), this::onSaveChangesButtonClicked);

        addUserButton = new Button(ADD_USER.getString(), this::onAddUserButtonClicked);

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

        addElement(accountNameLabel);
        addElement(closeButton);
        addElement(saveChangesButton);
        addElement(createNewBankButton);
        addElement(addUserButton);
        addElement(userElementListView);
        addElement(bankElementListView);
    }
    private void setupUserWindow()
    {

    }

    public static void openScreen(int accountNumber, GuiScreen parent, boolean isAdminMode)
    {
        BankAccountManagementScreen screen = new BankAccountManagementScreen(parent, accountNumber, isAdminMode);
        Minecraft.getInstance().setScreen(screen);
        screenIsOpen = true;
    }
    public static void openScreen(int accountNumber, boolean isAdminMode)
    {
        BankAccountManagementScreen screen = new BankAccountManagementScreen(accountNumber, isAdminMode);
        Minecraft.getInstance().setScreen(screen);
        screenIsOpen = true;
    }

    @Override
    protected void updateLayout(Gui gui) {
        int spacing = 5;
        int padding = 5;
        int width = getWidth()-2*padding;
        int height = getHeight()-2*padding;

        closeButton.setBounds(getWidth() - 20-padding, padding,20,20);
        int textWidth = getGui().getFont().width(SAVE_CHANGES.getString())+10;
        saveChangesButton.setBounds(closeButton.getLeft()-spacing-textWidth, padding, textWidth, closeButton.getHeight());
        textWidth = getGui().getFont().width(CREATE_NEW_BANK.getString())+10;
        createNewBankButton.setBounds(saveChangesButton.getLeft()-spacing-textWidth, padding, textWidth, closeButton.getHeight());
        accountNameLabel.setBounds(padding, padding, createNewBankButton.getLeft()-padding-spacing, 20);

        addUserButton.setBounds(padding, closeButton.getBottom()+spacing, (width-spacing)/2, closeButton.getHeight());
        userElementListView.setBounds(padding, addUserButton.getBottom()+spacing, (width-spacing)/2, height-(addUserButton.getBottom()+spacing)+padding);
        bankElementListView.setBounds(userElementListView.getRight()+spacing, closeButton.getBottom()+spacing, userElementListView.getWidth(), height-(closeButton.getBottom()+spacing)+padding);

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
        List<UpdateBankAccountPacket.BankData> bankData = new ArrayList<>();
        for(BankAccountManagementItem item : bankAccountManagementItems.values())
        {
            UpdateBankAccountPacket.BankData data = new UpdateBankAccountPacket.BankData();
            data.resetLockedBalance = item.freeLockedBalance();
            data.removeBank = item.deleteAccount();
            data.setBalance = item.balanceHasChanged();
            data.balance = item.getBalance();
            data.itemID = item.getItemID();
            bankData.add(data);
        }
        for(BankAccountManagementItem item : createBankData.values())
        {
            UpdateBankAccountPacket.BankData data = new UpdateBankAccountPacket.BankData();
            data.resetLockedBalance = item.freeLockedBalance();
            data.removeBank = item.deleteAccount();
            data.createBank = true;
            data.setBalance = item.balanceHasChanged();
            data.balance = item.getBalance();
            data.itemID = item.getItemID();
            bankElementListView.removeChild(item);
            bankData.add(data);

        }
        Map<UUID, Integer> setUsers = new HashMap<>();
        for(BankUserWidget userWidget : bankUserWidgets.values())
        {
            BankUserData userData = userWidget.getUserData();
            setUsers.put(userData.userUUID, userData.permissions);
        }
        UpdateBankAccountPacket.sendPacket(accountNumber, bankData, setUsers);
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankAccountData(accountNumber, (bankAccountData) -> {
            updateBankData(bankAccountData);
            updateAccountData(bankAccountData);
        });
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

        // playerName = minimalBankUserData.userName;
        accountNameLabel.setText(bankAccountData.accountName);
        //accountNameLabel.setText(BankSystemTextMessages.getBankAccountManagementBankOwnerMessage(accountNumber));
        Map<ItemID, BankAccountManagementItem> toRemove = new HashMap<>(bankAccountManagementItems);
        for(Pair<ItemID, BankData> pair : sortedBankAccounts)
        {
            BankAccountManagementItem item = bankAccountManagementItems.get(pair.getFirst());
            BankData bankData = pair.getSecond();
            if(item == null)
            {
                item = new BankAccountManagementItem(pair.getFirst(), accountNumber, bankData.itemFractionScaleFactor);
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

        boolean canChange = isAdminMode;
        if(bankAccountData.personalBankOwnerData != null)
        {
            if(bankAccountData.personalBankOwnerData.userUUID.equals(minecraft.player.getUUID()))
            {
                canChange = true;
            }
        }
        Map<UUID, BankUserWidget> toRemoveUsers = new HashMap<>(bankUserWidgets);
        for(BankUserData userData : bankAccountData.users.values())
        {
            UUID userUUID = userData.userUUID;
            BankUserWidget userWidget = bankUserWidgets.get(userUUID);
            if(userWidget == null)
            {

                userWidget = new BankUserWidget(userData, toRemoveUserWidgets::add, canChange, this);
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
            BankAccountManagementItem accountItem = new BankAccountManagementItem(itemID, accountNumber, scaleFac);
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
                        BankUserWidget userWidget = new BankUserWidget(bankUserData, toRemoveUserWidgets::add, isAdminMode, this);
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
