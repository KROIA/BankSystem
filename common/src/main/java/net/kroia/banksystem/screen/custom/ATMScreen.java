package net.kroia.banksystem.screen.custom;

import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.packet.client_sender.update.WithdrawMoneyPacket;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.layout.LayoutHorizontal;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;

public class ATMScreen extends BankSystemGuiScreen {
    private static final String PREFIX = "gui.";
    private static final String NAME = ".atm_screen.";
    public static final String COMPONENT_STR_START = PREFIX + BankSystemMod.MOD_ID + NAME;
    public static final Component TITLE = Component.translatable(COMPONENT_STR_START + "title");
    public static final Component RECEIVE_BUTTON_TEXT = Component.translatable(COMPONENT_STR_START + "receive_button");

    private class MoneyElement extends BankSystemGuiElement{

        private final ItemStack itemStack;
        private final ItemView itemView;
        private final TextBox amountTextBox;

        public MoneyElement(ItemStack moneyItem)
        {
            super();
            this.itemStack = moneyItem;

            itemView = new ItemView(this.itemStack);
            amountTextBox = new TextBox();
            amountTextBox.setAllowLetters(false);

            amountTextBox.setOnTextChanged((text -> {
                // Send signal to root GuiElement
                onRequestedAmountChanged(this);
            }));

            LayoutHorizontal layout = new LayoutHorizontal();
            this.setLayout(layout);


            addChild(itemView);
            addChild(amountTextBox);

            setAmount(0); // Initialize with 0 amount
        }

        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {
            itemView.setBounds(0, 0, this.getHeight(), this.getHeight());
            amountTextBox.setBounds(itemView.getWidth(), 0, this.getWidth() - itemView.getWidth(), this.getHeight());
        }

        public ItemStack getItemStack() {
            return itemStack;
        }
        public long getAmount() {
            String text = amountTextBox.getText();
            if(text.isEmpty())
                return 0;
            long value = 0;
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException e) {
                // If the text is not a valid number, return 0
                warn("Invalid amount entered: " + text + ". Returning 0.");
            }
            if(value < 0)
                value = 0;
            return value;
        }
        public void setAmount(long amount) {
            if(amount < 0)
                amount = 0;
            amountTextBox.setText(String.valueOf(amount));
        }
    }

    private static class BalanceView extends BankSystemGuiElement {

        private final ItemView coinItemView;
        private final Label balanceLabel;
        private final Label sumLabel;
        private final LayoutHorizontal layout;

        private final int defaultTextColor;
        public BalanceView()
        {
            super();
            coinItemView = new ItemView(BankSystemItems.MONEY.get().getDefaultInstance());
            balanceLabel = new Label();
            sumLabel = new Label();

            layout = new LayoutHorizontal();
            this.setLayout(layout);

            defaultTextColor = sumLabel.getTextColor();

            addChild(coinItemView);
            addChild(balanceLabel);
            addChild(sumLabel);
        }

        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {
            coinItemView.setBounds(0, 0, this.getHeight(), this.getHeight());
            balanceLabel.setBounds(coinItemView.getWidth(), 0, this.getWidth()/2 - coinItemView.getWidth(), this.getHeight());
            sumLabel.setBounds(balanceLabel.getX() + balanceLabel.getWidth(), 0, this.getWidth()/2 - coinItemView.getWidth(), this.getHeight());
        }

        public void updateBalance(long amount)
        {
            balanceLabel.setText(BankSystemTextMessages.getATMAvailableTextMessage(amount));
        }
        public void updateSum(long amount)
        {
            sumLabel.setText(BankSystemTextMessages.getATMSumTextMessage(amount));
        }

        public void enableWarning(boolean enabled)
        {
            if(enabled)
            {
                sumLabel.setTextColor(0xFF0000); // Red color for warning
            }
            else
            {
                sumLabel.setTextColor(defaultTextColor); // Default color
            }
        }
    }


    private final BalanceView balanceView;

    private final ArrayList<MoneyElement> moneyElements = new ArrayList<>();
    private final Button receiveButton;

    private final LayoutVertical layout;
    private final Frame rootElement;

    private static ATMScreen instance = null;
    private static long lastTickCount = 0;
    private long currentBalanceWeekVar = 0;


    public ATMScreen()
    {
        super(TITLE);
        //super(pMenu, pPlayerInventory, pTitle);

        layout = new LayoutVertical();
        layout.stretchX = true;
        layout.stretchY = true;
        rootElement = new Frame();
        rootElement.setLayout(layout);
        addElement(rootElement);

        balanceView = new BalanceView();
        rootElement.addChild(balanceView);

        ArrayList<ItemStack> moneyItems = BankSystemItems.getMoneyItems();
        for(ItemStack moneyItem : moneyItems)
        {
            MoneyElement moneyElement = new MoneyElement(moneyItem);
            moneyElements.add(moneyElement);
            rootElement.addChild(moneyElement);
        }

        receiveButton = new Button(RECEIVE_BUTTON_TEXT.getString(), this::onReceiveButtonPressed);
        rootElement.addChild(receiveButton);


        lastTickCount = System.currentTimeMillis();
        TickEvent.PLAYER_POST.register(ATMScreen::onClientTick);
        instance = this;
        updateBalanceView();
        calculateSum();
    }

    public static void openScreen()
    {
        ATMScreen screen = new ATMScreen();
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    public void onClose() {
        super.onClose();
        instance = null;
        // Unregister the event listener when the screen is closed
        TickEvent.PLAYER_POST.unregister(ATMScreen::onClientTick);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.width;
        int height = this.height;

        int padding = 20;

        rootElement.setBounds(padding, padding, width-2*padding, height-2*padding);
    }





    private static void onClientTick(Player player) {
        if (Minecraft.getInstance().screen != instance || instance == null)
            return;

        long currentTickCount = System.currentTimeMillis();
        if(currentTickCount - lastTickCount > 1000)
        {
            lastTickCount = currentTickCount;
            instance.updateBalanceView();
        }
    }

    private void updateBalanceView()
    {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestMinimalBankData(Minecraft.getInstance().player.getUUID(),
                new ItemID(BankSystemItems.MONEY.get().getDefaultInstance()), (minimalBankData) ->
        {
            if(instance == null || minimalBankData == null)
                return;
            currentBalanceWeekVar = minimalBankData.balance;
            balanceView.updateBalance(currentBalanceWeekVar);
        });
    }


    private void onReceiveButtonPressed()
    {
        long sum = calculateSum();
        if(sum <= 0 || sum > currentBalanceWeekVar)
        {
            Player player = Minecraft.getInstance().player;
            if(player != null && sum > currentBalanceWeekVar)
            {
                String text = BankSystemTextMessages.getATMNotEnoughBalance(sum);
                player.sendSystemMessage(Component.translatable(text));
            }
            return;
        }

        HashMap<String, Long> requestedBankNoteIDs = new HashMap<>();

        for (MoneyElement moneyElement : moneyElements) {
            long amount = moneyElement.getAmount();
            if(amount > 0)
            {
                String itemID = ItemUtilities.getItemIDStr(moneyElement.getItemStack().getItem());
                requestedBankNoteIDs.put(itemID, amount);
            }
        }
        //requestedBankNoteIDs.put(ItemUtilities.getItemID(BankSystemItems.MONEY50.get()), 0);
        //requestedBankNoteIDs.put(ItemUtilities.getItemID(BankSystemItems.MONEY10.get()), 100);
        WithdrawMoneyPacket.sendPacket(requestedBankNoteIDs);
    }

    private void onRequestedAmountChanged(MoneyElement moneyElement) {
        long amount = moneyElement.getAmount();
        ItemStack itemStack = moneyElement.getItemStack();
        calculateSum();

        // Here you can handle the requested amount for the specific item
        // For example, you can store it in a map or send it to the server
        //BankSystemMod.LOGGER.info("Requested amount for " + itemID + ": " + amount);
    }

    private long calculateSum()
    {
        long sum = 0;
        for (MoneyElement moneyElement : moneyElements) {
            long amount = moneyElement.getAmount();
            ItemStack itemStack = moneyElement.getItemStack();
            MoneyItem moneyItem = (MoneyItem) itemStack.getItem();
            if (moneyItem != null) {
                amount *= moneyItem.worth(); // Assuming getValue() returns the value of the money item
                sum += amount;
            }
        }
        balanceView.updateSum(sum);
        balanceView.enableWarning(currentBalanceWeekVar < sum);
        return sum;
    }
}
