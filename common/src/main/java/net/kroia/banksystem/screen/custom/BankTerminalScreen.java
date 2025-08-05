package net.kroia.banksystem.screen.custom;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.util.BankSystemGuiContainerScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.geometry.Rectangle;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class BankTerminalScreen extends BankSystemGuiContainerScreen<BankTerminalContainerMenu>
{
    private class BankElement extends BankSystemGuiElement
    {
        public static final int HEIGHT = 20;
        private long targetAmount = 0;
        private ItemStack stack;
        public long stackSize;
        private final Rectangle itemStackHitBox;
        public final ItemID itemID;

        private final TextBox amountBox;
        private final Label balanceLabel;

        BankTerminalScreen parent;

        public BankElement(BankTerminalScreen parent, ItemStack stack, ItemID itemID, long stackSize) {
            super(0, 0, 100, HEIGHT);
            this.parent = parent;
            this.stack = stack;
            this.itemID = itemID;
            this.stackSize = stackSize;

            int boxPadding = 2;

            balanceLabel = new Label("");
            itemStackHitBox = new Rectangle(1,1,16,16);

            this.amountBox = new TextBox(0,0,0);
            this.amountBox.setMaxChars(10); // Max length of input
            this.amountBox.setAllowLetters(false); // Allow only digits

            addChild(balanceLabel);
            addChild(amountBox);
            amountBox.setOnTextChanged((textBox) -> {
                saveAmount();
            });
            amountBox.setText(0);
            //addChild(receiveItemsFromMarketButton);
        }

        @Override
        protected void render() {
            drawItem(stack, itemStackHitBox.x, itemStackHitBox.y);
            String amountStr = Bank.getFormattedAmount(stackSize);
            balanceLabel.setText(amountStr);
            if(itemStackHitBox.contains(getMousePos().x, getMousePos().y))
                drawTooltip(stack, getMousePos());
        }

        @Override
        protected void layoutChanged() {
            int width = getWidth()-17;
            int height = getHeight();
            int padding = 2;
            int balanceLabelWidth = width/2;
            itemStackHitBox.y = (height-16)/2;

            balanceLabel.setBounds(padding+17, padding, balanceLabelWidth, height-padding*2);
            amountBox.setBounds(balanceLabel.getRight(), padding, getWidth()-balanceLabel.getRight()-padding, height-padding*2);

        }
        public long getTargetAmount()
        {
            saveAmount();
            return targetAmount;
        }
        public void setTargetAmount(int amount)
        {
            this.targetAmount = amount;
            amountBox.setText(amount);
        }
        private void saveAmount() {
            targetAmount = this.amountBox.getInt();
            if(targetAmount > stackSize) {
                targetAmount = stackSize;
            }
            else if(targetAmount < 0)
            {
                targetAmount = 0;
            }
            amountBox.setText(targetAmount);

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
    private final Button removeEmptyBankAccountsButton;
    private final Button sendItemsToBankButton;
    private final Button receiveItemsFromBankButton;
    private final VerticalListView itemListView;
    private final ContainerView<BankTerminalContainerMenu> inventoryView;

    public static int widthPercentage = 100;
    private final UUID playerUUID;
    private final String playerName;
    private static boolean screenIsOpen = false;



    public BankTerminalScreen(BankTerminalContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        //super(pTitle);
        menu = pMenu;
        screenIsOpen = true;
        playerUUID = Minecraft.getInstance().player.getUUID();
        playerName = Minecraft.getInstance().player.getName().getString();

        removeEmptyBankAccountsButton = new Button(REMOVE_EMPTY_BANKS_BUTTON_TEXT.getString());
        removeEmptyBankAccountsButton.setOnFallingEdge(() -> {
            getBankManager().requestRemoveEmptyBanks(playerUUID, (removed) -> {
                updateBankList();
            });
        });
        sendItemsToBankButton = new Button(SEND_ITEMS_TO_BANK_BUTTON_TEXT.getString());
        sendItemsToBankButton.setOnFallingEdge(this::onTransmittItemsToBank);

        receiveItemsFromBankButton = new Button(RECEIVE_ITEMS_FROM_BANK_BUTTON_TEXT.getString());
        receiveItemsFromBankButton.setOnFallingEdge(this::onReceiveItemsFromBank);

        itemListView = new VerticalListView(0, 0, 100, 100);
        itemListView.setLayout(new LayoutVertical(0,0,true, false));
        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 176, 166));

        addElement(removeEmptyBankAccountsButton);
        addElement(sendItemsToBankButton);
        addElement(receiveItemsFromBankButton);
        addElement(itemListView);
        addElement(inventoryView);

        updateBankList();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.width*widthPercentage/100;
        int height = this.height;

        int padding = 5;
        int spacing = 5;
        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();
        inventoryView.setPosition(width - inventoryWidth - padding, (height - inventoryHeight) / 2);

        sendItemsToBankButton.setBounds(inventoryView.getX(), padding, inventoryView.getWidth(), 20);

        int itemListViewWidth = inventoryView.getX()-padding*2;
        removeEmptyBankAccountsButton.setBounds(padding, padding, itemListViewWidth/2-spacing, sendItemsToBankButton.getHeight());
        receiveItemsFromBankButton.setBounds(removeEmptyBankAccountsButton.getRight()+spacing, removeEmptyBankAccountsButton.getTop(), itemListViewWidth/2, removeEmptyBankAccountsButton.getHeight());
        itemListView.setBounds(padding, receiveItemsFromBankButton.getY() + receiveItemsFromBankButton.getHeight() + padding,
                itemListViewWidth, height - padding - receiveItemsFromBankButton.getHeight() - padding*2);
    }

    @Override
    public void onClose() {
        super.onClose();
        screenIsOpen = false;
    }
    @Override
    public void containerTick() {
        super.containerTick();
        handleTick();
    }

    public void handleTick() {
        tickCount++;
        if(tickCount - lastTickCount > 10)
        {
            lastTickCount = tickCount;
            updateBankList();
        }
    }

    private void updateBankList()
    {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestMinimalBankUserData(playerUUID, (minimalBankUserData) -> {
            if(!screenIsOpen)
                return;
            if(minimalBankUserData == null)
            {
                error("Failed to update bank data for player: " + playerName + ". MinimalBankUserData is null.");
                return;
            }

            HashMap<ItemID, MinimalBankData> bankMap = minimalBankUserData.bankMap;
            ArrayList<Pair<ItemID, MinimalBankData>> sortedBankAccounts = new ArrayList<>();
            for(var entry : bankMap.entrySet())
            {
                ItemID itemID = entry.getKey();
                MinimalBankData minimalBankData = entry.getValue();
                if(minimalBankData != null)
                    sortedBankAccounts.add(new Pair<>(itemID, minimalBankData));
            }
            sortedBankAccounts.sort((a, b) -> Long.compare(b.getSecond().balance, a.getSecond().balance));

            int x = 0;
            int y = 0;

            boolean needsResize = sortedBankAccounts.size() != bankElements.size();
            HashMap<ItemID,ItemID> availableItems = new HashMap<>();
            for (Pair<ItemID, MinimalBankData> pair : sortedBankAccounts)
            {
                long balance = pair.getSecond().balance;
                BankElement element = getBankElement(pair.getFirst());
                if (element == null) {
                    ItemStack stack = pair.getFirst().getStack();
                    element = new BankElement(this, stack, pair.getFirst(), balance);
                    bankElements.add(element);
                    itemListView.addChild(element);
                } else {
                    element.stackSize = balance;
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
        });
    }

    private BankElement getBankElement(ItemID itemID)
    {
        for (BankElement button : bankElements) {
            if(button.itemID.equals(itemID))
                return button;
        }
        return null;
    }

    private void onTransmittItemsToBank() {

        /*for(BankElement element : bankElements)
        {
            element.saveAmount();
            info("Sending item: "+element.itemID + " amount: "+element.getTargetAmount());
        }*/

        HashMap<ItemID, Long> itemTransferToBankAmounts = new HashMap<>();
        UpdateBankTerminalBlockEntityPacket.sendPacketToServer(this.menu.getBlockPos(), itemTransferToBankAmounts, true);
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
        UpdateBankTerminalBlockEntityPacket.sendPacketToServer(this.menu.getBlockPos(), itemTransferToMarketAmounts, false);
    }
}