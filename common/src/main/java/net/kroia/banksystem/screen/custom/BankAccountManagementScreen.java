package net.kroia.banksystem.screen.custom;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.networking.packet.client_sender.update.UpdateBankAccountPacket;
import net.kroia.banksystem.screen.uiElements.BankAccountManagementItem;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BankAccountManagementScreen extends BankSystemGuiScreen {
    private static final String PREFIX = "gui."+ BankSystemMod.MOD_ID+".bank_account_management_screen.";
    private static final Component TITLE = Component.translatable(PREFIX+"title");

    private static final Component SAVE_CHANGES = Component.translatable(PREFIX+"save_changes");
    private static final Component CREATE_NEW_BANK = Component.translatable(PREFIX+"create_new_bank");

    private final int accountNumber;
    //private String playerName;
    private final GuiScreen parent;

    private final Label accountNumberLabel;
    private final CloseButton closeButton;
    private final Button saveChangesButton;
    private final Button createNewBankButton;
    private final ListView bankElementListView;

    private int lastTickCount = 0;
    private final HashMap<ItemID, BankAccountManagementItem> bankAccountManagementItems = new HashMap<>();
    private static boolean screenIsOpen = false;


    public BankAccountManagementScreen(GuiScreen parent, int accountNumber) {
        super(TITLE);
        this.parent = parent;
        this.accountNumber = accountNumber;


        accountNumberLabel = new Label();

        closeButton = new CloseButton(this::onClose);
        closeButton.setIdleColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);

        saveChangesButton = new Button(SAVE_CHANGES.getString(), this::onSaveChangesButtonClicked);
        bankElementListView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 2;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        bankElementListView.setLayout(layout);

        createNewBankButton = new Button(CREATE_NEW_BANK.getString());
        createNewBankButton.setOnFallingEdge(() -> {
            BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestMinimalBankManagerData((minimalBankManagerData) -> {
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

        addElement(accountNumberLabel);
        addElement(closeButton);
        addElement(saveChangesButton);
        addElement(createNewBankButton);
        addElement(bankElementListView);

        updateBankData();
    }
    public BankAccountManagementScreen(int accountNumber)
    {
        this(null, accountNumber);
    }

    public static void openScreen(int accountNumber, GuiScreen parent)
    {
        BankAccountManagementScreen screen = new BankAccountManagementScreen(parent, accountNumber);
        Minecraft.getInstance().setScreen(screen);
        screenIsOpen = true;
    }
    public static void openScreen(int accountNumber)
    {
        BankAccountManagementScreen screen = new BankAccountManagementScreen(accountNumber);
        Minecraft.getInstance().setScreen(screen);
        screenIsOpen = true;
    }

    @Override
    protected void updateLayout(Gui gui) {
        int spacing = 5;
        int padding = 10;
        int width = getWidth()-2*padding;
        int height = getHeight()-2*padding;

        closeButton.setBounds(getWidth() - 20-padding, padding,20,20);
        int textWidth = getGui().getFont().width(SAVE_CHANGES.getString())+10;
        saveChangesButton.setBounds(closeButton.getLeft()-spacing-textWidth, padding, textWidth, closeButton.getHeight());
        textWidth = getGui().getFont().width(CREATE_NEW_BANK.getString())+10;
        createNewBankButton.setBounds(saveChangesButton.getLeft()-spacing-textWidth, padding, textWidth, closeButton.getHeight());
        accountNumberLabel.setBounds(padding, padding, createNewBankButton.getLeft()-padding-spacing, 20);

        bankElementListView.setBounds(padding, closeButton.getBottom()+spacing, width, height-(closeButton.getBottom()+spacing)+padding);

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
        ArrayList<UpdateBankAccountPacket.BankData> bankData = new ArrayList<>();
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
        UpdateBankAccountPacket.sendPacket(accountNumber, bankData);
    }
    private void updateBankData()
    {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestMinimalBankUserData(accountNumber, (minimalBankUserData) -> {
            if(!screenIsOpen)
                return;
            if(minimalBankUserData == null)
            {
                error("Failed to update bank data for bankaccount: " + accountNumber + ". MinimalBankUserData is null.");
                return;
            }

            Map<ItemID, BankData> bankMap = minimalBankUserData.bankData;
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
            accountNumberLabel.setText(BankSystemTextMessages.getBankAccountManagementBankOwnerMessage(accountNumber));
            HashMap<ItemID, BankAccountManagementItem> toRemove = new HashMap<>(bankAccountManagementItems);
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
        });

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
    private void onCreateNewBank(ItemStack item)
    {
        ItemID itemID = new ItemID(item);
        if(bankAccountManagementItems.containsKey(itemID))
            return;
        UpdateBankAccountPacket.BankData data = new UpdateBankAccountPacket.BankData();


        ArrayList<UpdateBankAccountPacket.BankData> bankData = new ArrayList<>();
        data.itemID = itemID;
        data.createBank = true;
        bankData.add(data);
        UpdateBankAccountPacket.sendPacket(accountNumber, bankData);
    }

    @Override
    public void tick() {
        ++lastTickCount;
        if(lastTickCount > 20)
        {
            lastTickCount = 0;
            updateBankData();
        }
    }
}
