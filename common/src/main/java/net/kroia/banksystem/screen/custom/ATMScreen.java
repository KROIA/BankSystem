package net.kroia.banksystem.screen.custom;

import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.WithdrawMoneyPacket;
import net.kroia.banksystem.screen.uiElements.AmountButtonGroup;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.kroia.modutilities.gui.layout.LayoutHorizontal;
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

        private final AmountButtonGroup addAmountButtonGroup;

        /*private final Button zeroButton;

        private final Button add1Button;
        private final Button add10Button;
        private final Button add32Button;
        private final Button add64Button;

        private final Button remove1Button;
        private final Button remove10Button;
        private final Button remove32Button;
        private final Button remove64Button;*/

        public MoneyElement(ItemStack moneyItem)
        {
            super();
            this.itemStack = moneyItem;

            itemView = new ItemView(this.itemStack);
            amountTextBox = new TextBox();
            amountTextBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 100, 0));

            addAmountButtonGroup = AmountButtonGroup.create(new long[]{1L, 10L, 32L, 64L},
                    this::addAmountFromButton,
                    ()->{addAmountFromButton(-getAmount());},
                    this::getAmount);
            addChild(addAmountButtonGroup);

            amountTextBox.setOnTextChanged((text -> {
                addAmountButtonGroup.updateButtons();
                onRequestedAmountChanged(this);
            }));



            /*float addRemoveButtonFontScale = 0.8f;
            int colorGreen = ColorUtilities.getRGB(50,204,111);
            int colorRed = ColorUtilities.getRGB(204,90,86);
            zeroButton = new Button("=0", ()->addAmountFromButton(-getAmount()));
            add1Button = new Button("+1", ()->addAmountFromButton(1));
            add10Button = new Button("+10", ()->addAmountFromButton(10));
            add32Button = new Button("+32", ()->addAmountFromButton(32));
            add64Button = new Button("+64", ()->addAmountFromButton(64));
            remove1Button = new Button("-1", ()->addAmountFromButton(-1));
            remove10Button = new Button("-10", ()->addAmountFromButton(-10));
            remove32Button = new Button("-32", ()->addAmountFromButton(-32));
            remove64Button = new Button("-64", ()->addAmountFromButton(-64));
            formatButton(zeroButton, addRemoveButtonFontScale, colorRed);
            zeroButton.setHeight(zeroButton.getHeight()*2);

            formatButton(add1Button, addRemoveButtonFontScale, colorGreen);
            formatButton(add10Button, addRemoveButtonFontScale, colorGreen);
            formatButton(add32Button, addRemoveButtonFontScale, colorGreen);
            formatButton(add64Button, addRemoveButtonFontScale, colorGreen);

            formatButton(remove1Button, addRemoveButtonFontScale, colorRed);
            formatButton(remove10Button, addRemoveButtonFontScale, colorRed);
            formatButton(remove32Button, addRemoveButtonFontScale, colorRed);
            formatButton(remove64Button, addRemoveButtonFontScale, colorRed);*/

            LayoutHorizontal layout = new LayoutHorizontal();
            this.setLayout(layout);


            addChild(itemView);
            addChild(amountTextBox);

            /*addChild(zeroButton);
            addChild(add1Button);
            addChild(add10Button);
            addChild(add32Button);
            addChild(add64Button);
            addChild(remove1Button);
            addChild(remove10Button);
            addChild(remove32Button);
            addChild(remove64Button);*/


            setAmount(0); // Initialize with 0 amount
            setHeight(addAmountButtonGroup.getHeight());
            //setHeight(add10Button.getHeight()*2+2);
        }
        /*private void formatButton(Button button, float fontscale, int color)
        {
            button.setTextFontScale(fontscale);
            button.setBackgroundColor(color);
            button.setHoverColor(ColorUtilities.setBrightness(color, 0.7f));
            button.setPressedColor(ColorUtilities.setBrightness(color, 0.6f));
            //button.setOutlineColor(ColorUtilities.setBrightness(color, 0.5f));
            button.setEnableOutline(false);
            button.setWidth(button.getTextWidth(button.getText()) + padding-2);
            button.setHeight(button.getTextHeight() + padding-4);
        }*/

        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {
            itemView.setBounds(0, 0, this.getHeight(), this.getHeight());

            //int buttonWidthSum = zeroButton.getWidth() + add1Button.getWidth() + add10Button.getWidth() + add32Button.getWidth() + add64Button.getWidth()+1;
            amountTextBox.setBounds(itemView.getWidth(), 0, this.getWidth()/2 - itemView.getWidth(), this.getHeight());

            addAmountButtonGroup.setBounds(amountTextBox.getRight(), amountTextBox.getTop(), this.getWidth() - amountTextBox.getRight(), this.getHeight());
            //addAmountButtonGroup.setPosition(amountTextBox.getRight(), amountTextBox.getTop());
            //addAmountButtonGroup.setHeight(getHeight());
            /*zeroButton.setPosition(amountTextBox.getRight(), amountTextBox.getTop()+1);

            add1Button.setPosition(zeroButton.getRight(), zeroButton.getTop());
            add10Button.setPosition(add1Button.getRight(), add1Button.getTop());
            add32Button.setPosition(add10Button.getRight(), add10Button.getTop());
            add64Button.setPosition(add32Button.getRight(), add32Button.getTop());

            remove1Button.setPosition(add1Button.getLeft(), add1Button.getBottom());
            remove10Button.setPosition(remove1Button.getRight(), remove1Button.getTop());
            remove32Button.setPosition(remove10Button.getRight(), remove10Button.getTop());
            remove64Button.setPosition(remove32Button.getRight(), remove32Button.getTop());*/
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
            setAmountInternal(amount);
            addAmountButtonGroup.updateButtons();
            //updateButtons(amount);
        }
        public void setAmountInternal(long amount)
        {
            if(amount < 0)
                amount = 0;
            amountTextBox.setText(String.valueOf(amount));
        }
        private void addAmountFromButton(long amount)
        {
            long newAmount = Math.max(0, getAmount() + amount);
            setAmountInternal(newAmount);
            onRequestedAmountChanged(this);
        }
        /*private void updateButtons(long amount)
        {
            zeroButton.setEnabled(amount > 0);
            remove1Button.setEnabled(amount >= 1);
            remove10Button.setEnabled(amount >= 10);
            remove32Button.setEnabled(amount >= 32);
            remove64Button.setEnabled(amount >= 64);

        }*/
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
            balanceLabel.setAlignment(Alignment.CENTER);
            sumLabel = new Label();
            sumLabel.setAlignment(Alignment.CENTER);

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
            balanceLabel.setBounds(coinItemView.getWidth(), 0, (this.getWidth()-coinItemView.getWidth())/2, this.getHeight());
            sumLabel.setBounds(balanceLabel.getRight(), 0, balanceLabel.getWidth(), this.getHeight());
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


    private final BankAccountSelectionScreen.AccountButton selectAccountButton;
    private final BalanceView balanceView;

    private final ArrayList<MoneyElement> moneyElements = new ArrayList<>();
    private final Button receiveButton;

    private final Frame rootElement;
    private final ListView moneyListView;

    private static ATMScreen instance = null;
    private static long lastTickCount = 0;
    private long currentBalanceWeekVar = 0;
    private int currentSelectedAccountNumber = 0; // This is not used in the ATM screen, but kept for consistency with other screens


    public ATMScreen()
    {
        super(TITLE);
        //super(pMenu, pPlayerInventory, pTitle);
        rootElement = new Frame();
        addElement(rootElement);

        selectAccountButton = new BankAccountSelectionScreen.AccountButton();
        selectAccountButton.setOnFallingEdge(() -> {
            BankAccountSelectionScreen selectionScreen = new BankAccountSelectionScreen(this, minecraft.player.getUUID(), (accountNumber) -> {
                this.currentSelectedAccountNumber = accountNumber;
            });
            minecraft.setScreen(selectionScreen);
        });
        rootElement.addChild(selectAccountButton);

        LayoutGrid layout = new LayoutGrid();
        layout.stretchX = true;
        layout.columns = 2;
        layout.padding = 5;
        layout.spacing = 5;
        moneyListView = new VerticalListView();
        moneyListView.setLayout(layout);



        balanceView = new BalanceView();
        balanceView.setHeight(20);


        ArrayList<ItemStack> moneyItems = BankSystemItems.getMoneyItems();
        for(ItemStack moneyItem : moneyItems)
        {
            MoneyElement moneyElement = new MoneyElement(moneyItem);
            moneyElements.add(moneyElement);
            moneyListView.addChild(moneyElement);
        }

        receiveButton = new Button(RECEIVE_BUTTON_TEXT.getString(), this::onReceiveButtonPressed);
        receiveButton.setHeight(20);

        rootElement.addChild(balanceView);
        rootElement.addChild(moneyListView);
        rootElement.addChild(receiveButton);

        lastTickCount = System.currentTimeMillis();
        TickEvent.PLAYER_POST.register(ATMScreen::onClientTick);
        instance = this;
        updateBalanceView();
        calculateSum();

        getBankManager().getPersonalBankAccountDataAsync(Minecraft.getInstance().player.getUUID()).thenAccept((accountData) -> {
            if(accountData != null && !accountData.bankData.isEmpty())
            {
                currentSelectedAccountNumber = accountData.accountNumber;
                selectAccountButton.setAccountData(accountData);
                updateBalanceView();
            }
        });
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
        int width = (getWidth()*3)/4;
        int height = getHeight();


        int padding = 10;
        int spacing = 5;

        int leftEdget = (getWidth() - width)/2+padding;


        rootElement.setBounds(leftEdget, padding, width-2*padding, height-2*padding);
        padding = 5;
        width = rootElement.getWidth() - 2*padding;
        height = rootElement.getHeight()-2*padding;

        selectAccountButton.setBounds(padding, padding, width/2, 20);
        balanceView.setBounds(padding, selectAccountButton.getBottom()+spacing, width, 20);
        int receiveButtonWidth = rootElement.getWidth()/2;
        receiveButton.setBounds((rootElement.getWidth()-receiveButtonWidth)/2, height+padding-20, receiveButtonWidth, 20);
        moneyListView.setBounds(padding, balanceView.getBottom() + padding, width, receiveButton.getTop() - balanceView.getBottom() - padding*2);

        //receiveButton.setBounds(rootElement.getLeft(), rootElement.getBottom()+5,rootElement.getWidth(), 20);
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
        //BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestPersonalBankAccountData(Minecraft.getInstance().player.getUUID(), (accountData) ->
        getBankManager().getBankAccountDataAsync(currentSelectedAccountNumber).thenAccept((accountData) ->
        {
            if(instance == null || accountData == null)
                return;

            selectAccountButton.setAccountData(accountData);
            if(accountData.bankData.containsKey(MoneyItem.getItemID()))
            {
                BankData minimalBankData = accountData.bankData.get(MoneyItem.getItemID());
                currentBalanceWeekVar = minimalBankData.balance();
                balanceView.updateBalance(currentBalanceWeekVar);
            }
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

        HashMap<ItemID, Long> requestedBankNoteIDs = new HashMap<>();

        for (MoneyElement moneyElement : moneyElements) {
            long amount = moneyElement.getAmount();
            if(amount > 0)
            {
                ItemID itemID = ItemID.of(moneyElement.getItemStack());
                requestedBankNoteIDs.put(itemID, amount);
            }
        }
        WithdrawMoneyPacket.sendPacket(requestedBankNoteIDs, currentSelectedAccountNumber);
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
            amount *= moneyItem.worth(); // Assuming getValue() returns the value of the money item
            sum += amount;
        }
        balanceView.updateSum(sum);
        balanceView.enableWarning(currentBalanceWeekVar < sum);
        return sum;
    }
}
