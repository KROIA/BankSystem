package net.kroia.banksystem.screen.custom;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestBankDataPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiContainerScreen;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.geometry.Rectangle;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;

public class BankTerminalScreen extends GuiContainerScreen<BankTerminalContainerMenu>
{
    private class BankElement extends GuiElement
    {
        public static final int HEIGHT = 20;
        private long targetAmount = 0;
        private ItemStack stack;
        public long stackSize;
        private final Rectangle itemStackHitBox;
        public final String itemID;

        private final TextBox amountBox;
        private final Label balanceLabel;

        BankTerminalScreen parent;

        public BankElement(BankTerminalScreen parent, ItemStack stack, String itemID, long stackSize) {
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
                drawTooltipLater(stack, getMousePos());
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

    private static final Component SEND_ITEMS_TO_BANK_BUTTON_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.send_items_to_bank_button");
    private static final Component RECEIVE_ITEMS_FROM_BANK_BUTTON_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.receive_items_from_bank_button");
    private static final Component INVENTORY_NAME_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_terminal_screen.inventory_name");


    private int lastTickCount = 0;
    private int tickCount = 0;
    private final ArrayList<BankElement> bankElements = new ArrayList<>();

    BankTerminalContainerMenu menu;

    // Gui elements
    private final Button sendItemsToBankButton;
    private final Button receiveItemsFromBankButton;
    private final VerticalListView itemListView;
    private final ContainerView<BankTerminalContainerMenu> inventoryView;

    public static int widthPercentage = 100;

    public BankTerminalScreen(BankTerminalContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        //super(pTitle);
        menu = pMenu;
        sendItemsToBankButton = new Button(SEND_ITEMS_TO_BANK_BUTTON_TEXT.getString());
        sendItemsToBankButton.setOnFallingEdge(this::onTransmittItemsToMarket);

        receiveItemsFromBankButton = new Button(RECEIVE_ITEMS_FROM_BANK_BUTTON_TEXT.getString());
        receiveItemsFromBankButton.setOnFallingEdge(this::onReceiveItemsFromMarket);

        itemListView = new VerticalListView(0, 0, 100, 100);
        itemListView.setLayout(new LayoutVertical(0,0,true, false));
        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 176, 166));

        addElement(sendItemsToBankButton);
        addElement(receiveItemsFromBankButton);
        addElement(itemListView);
        addElement(inventoryView);

        buildItemButtons();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.width*widthPercentage/100;
        int height = this.height;

        int padding = 5;
        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();
        inventoryView.setPosition(width - inventoryWidth - padding, (height - inventoryHeight) / 2);

        sendItemsToBankButton.setBounds(inventoryView.getX(), padding, inventoryView.getWidth(), 20);

        int itemListViewWidth = inventoryView.getX()-padding*2;
        receiveItemsFromBankButton.setBounds(padding, padding, itemListViewWidth, 20);
        itemListView.setBounds(padding, receiveItemsFromBankButton.getY() + receiveItemsFromBankButton.getHeight() + padding,
                itemListViewWidth, height - padding - receiveItemsFromBankButton.getHeight() - padding*2);
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
            RequestBankDataPacket.sendRequest();
            lastTickCount = tickCount;
            buildItemButtons();
        }
    }

    private void buildItemButtons()
    {
        int x = 0;
        int y = 0;
        // Sort the bank accounts by itemID
        ArrayList<Pair<String, SyncBankDataPacket.BankData>> sortedBankAccounts = ClientBankManager.getSortedBankData();

        boolean needsResize = sortedBankAccounts.size() != bankElements.size();
        HashMap<String,String> availableItems = new HashMap<>();
        for (int i=0; i<sortedBankAccounts.size(); i++) {
            Pair<String,SyncBankDataPacket.BankData> pair = sortedBankAccounts.get(i);
            long amount = pair.getSecond().getBalance();
            BankElement element = getBankElement(pair.getFirst());
            if(element == null)
            {
                ItemStack stack = ItemUtilities.createItemStackFromId(pair.getFirst(), 1);
                element = new BankElement(this, stack, pair.getFirst(), amount);
                bankElements.add(element);
                itemListView.addChild(element);
            }
            else
            {
                element.stackSize = amount;
            }
            if(needsResize)
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

    private BankElement getBankElement(String itemID)
    {
        for (BankElement button : bankElements) {
            if(button.itemID.equals(itemID))
                return button;
        }
        return null;
    }

    private void onTransmittItemsToMarket() {

        for(BankElement element : bankElements)
        {
            element.saveAmount();
            BankSystemMod.LOGGER.info("Sending item: "+element.itemID + " amount: "+element.getTargetAmount());
        }

        HashMap<String, Long> itemTransferToMarketAmounts = new HashMap<>();
        UpdateBankTerminalBlockEntityPacket.sendPacketToServer(this.menu.getBlockPos(), itemTransferToMarketAmounts, true);
    }
    private void onReceiveItemsFromMarket() {
        for(BankElement element : bankElements)
        {
            element.saveAmount();
            BankSystemMod.LOGGER.info("Sending item: "+element.itemID + " amount: "+element.getTargetAmount());
        }
        HashMap<String, Long> itemTransferToMarketAmounts = new HashMap<>();
        for(BankElement button : bankElements)
        {
            long amount = button.getTargetAmount();
            if(amount > 0)
            {
                itemTransferToMarketAmounts.put(button.itemID, amount);
            }
        }
        UpdateBankTerminalBlockEntityPacket.sendPacketToServer(this.menu.getBlockPos(), itemTransferToMarketAmounts, false);
    }
}