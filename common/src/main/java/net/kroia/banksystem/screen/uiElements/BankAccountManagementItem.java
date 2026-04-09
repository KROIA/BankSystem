package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.*;
import net.minecraft.network.chat.Component;

public class BankAccountManagementItem extends BankSystemGuiElement {

    private static final String PREFIX = "gui."+ BankSystemMod.MOD_ID+".bank_account_management_item.";
    private static final Component CLOSE_BANK_BUTTON = Component.translatable(PREFIX+"close_bank_button");
    private static final Component FREE_LOCKED_BALANCE_CHECKBOX = Component.translatable(PREFIX+"free_locked_balance_checkbox");
    private static final Component BALANCE = Component.translatable(PREFIX+"balance");
    private static final Component LOCKED_BALANCE = Component.translatable(PREFIX+"locked_balance");
    private static final Component TOTAL_BALANCE = Component.translatable(PREFIX+"total_balance");

    private static final Component CLOSE_BANK_BUTTON_TOOLTIP = Component.translatable(PREFIX+"close_bank_button.tooltip");
    private static final Component FREE_LOCKED_BALANCE_CHECKBOX_TOOLTIP = Component.translatable(PREFIX+"free_locked_balance_checkbox.tooltip");
    private static final Component BALANCE_TEXTBOX_TOOLTIP = Component.translatable(PREFIX+"balance_textbox.tooltip");
    private static final Component BALANCE_LABEL_TOOLTIP = Component.translatable(PREFIX+"balance_label.tooltip");
    private static final Component LOCKED_LABEL_TOOLTIP = Component.translatable(PREFIX+"locked_label.tooltip");
    private static final Component TOTAL_LABEL_TOOLTIP = Component.translatable(PREFIX+"total_label.tooltip");


    private final int accountNumber;
    private final ItemID itemID;
    private final ItemView itemView;
    private final Button closeBankButton;
    private final CheckBox freeLockedBalanceCheckBox;


    private final Label balanceLabel;
    private final Label balanceValueLabel;
    private final TextBox balanceValueTextBox;

    private final Label lockedBalanceLabel;
    private final Label lockedBalanceValueLabel;

    private final Label totalBalanceLabel;
    private final Label totalBalanceValueLabel;

    private boolean balanceChanged = false;
    private boolean deleteAccount = false;
    private boolean freeLockedBalance = false;
    private final boolean isAdminMode;
    private final boolean canManage;


    public BankAccountManagementItem(ItemID itemID, int accountNumber, boolean isAdminMode, boolean canManage)
    {
        super();
        this.isAdminMode = isAdminMode;
        this.canManage = canManage || isAdminMode;
        this.itemID = itemID;
        this.accountNumber = accountNumber;
        itemView = new ItemView(itemID.getStack());
        balanceLabel = new Label(BALANCE.getString());
        balanceValueLabel = new Label();


        lockedBalanceLabel = new Label(LOCKED_BALANCE.getString());
        lockedBalanceValueLabel = new Label();
        totalBalanceLabel = new Label(TOTAL_BALANCE.getString());
        totalBalanceValueLabel = new Label();







        balanceValueLabel.setHoverTooltipSupplier(BALANCE_LABEL_TOOLTIP::getString);
        lockedBalanceValueLabel.setHoverTooltipSupplier(LOCKED_LABEL_TOOLTIP::getString);
        totalBalanceValueLabel.setHoverTooltipSupplier(TOTAL_LABEL_TOOLTIP::getString);




        balanceValueLabel.setHoverTooltipFontScale(0.8f);
        lockedBalanceValueLabel.setHoverTooltipFontScale(0.8f);
        totalBalanceValueLabel.setHoverTooltipFontScale(0.8f);




        balanceValueLabel.setHoverTooltipMousePositionAlignment(Alignment.LEFT);
        lockedBalanceValueLabel.setHoverTooltipMousePositionAlignment(Alignment.LEFT);
        totalBalanceValueLabel.setHoverTooltipMousePositionAlignment(Alignment.LEFT);


        addChild(itemView);

        addChild(balanceLabel);
        addChild(balanceValueLabel);
        addChild(lockedBalanceLabel);
        addChild(lockedBalanceValueLabel);
        addChild(totalBalanceLabel);
        addChild(totalBalanceValueLabel);

        if(canManage)
        {
            closeBankButton = new Button(CLOSE_BANK_BUTTON.getString(), () -> {
                String askTitle = BankSystemTextMessages.getBankAccountManagementItemAskRemoveTitleMessage(itemID.getName());
                String askMessage = BankSystemTextMessages.getBankAccountManagementItemAskRemoveMessage(itemID.getName(), accountNumber);
                AskPopupScreen popup = new AskPopupScreen((GuiScreen)getRoot().getScreen(), this::onCloseAccountButtonClicked, () -> {}, askTitle, askMessage);
                popup.setSize(400,100);
                popup.setColors(0xFFe8711c, 0xFFe04c12, 0xFFf22718, 0xFF70e815);
                getMinecraft().setScreen(popup);
            });
            closeBankButton.setHoverTooltipSupplier(CLOSE_BANK_BUTTON_TOOLTIP::getString);
            closeBankButton.setHoverTooltipFontScale(0.8f);
            closeBankButton.setHoverTooltipMousePositionAlignment(Alignment.RIGHT);
            addChild(closeBankButton);

        }
        else
        {
            closeBankButton = null;
        }
        if(isAdminMode)
        {
            balanceValueTextBox = new TextBox();
            balanceValueTextBox.setAllowLetters(false);
            balanceValueTextBox.setAllowNumbers(true, true);
            balanceValueTextBox.setAllowNegativeNumbers(false);
            int maxDecimalChar = ServerBank.getMaxDecimalDigitsCount();
            balanceValueTextBox.setMaxDecimalChar(maxDecimalChar);
            balanceValueTextBox.setOnTextChanged(this::onBalanceTextBoxChanged);
            balanceValueTextBox.setMaxChars(6*3+1); // Max size of a long
            balanceValueTextBox.setHoverTooltipSupplier(BALANCE_TEXTBOX_TOOLTIP::getString);
            balanceValueTextBox.setHoverTooltipFontScale(0.8f);
            balanceValueTextBox.setHoverTooltipMousePositionAlignment(Alignment.RIGHT);

            freeLockedBalanceCheckBox = new CheckBox(FREE_LOCKED_BALANCE_CHECKBOX.getString(), this::onFreeLockedBalanceCheckBoxClicked);
            freeLockedBalanceCheckBox.setHoverTooltipSupplier(FREE_LOCKED_BALANCE_CHECKBOX_TOOLTIP::getString);
            freeLockedBalanceCheckBox.setHoverTooltipFontScale(0.8f);
            freeLockedBalanceCheckBox.setHoverTooltipMousePositionAlignment(Alignment.RIGHT);

            addChild(balanceValueTextBox);
            addChild(freeLockedBalanceCheckBox);
        }
        else
        {
            balanceValueTextBox = null;
            freeLockedBalanceCheckBox = null;
        }
    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {
        int padding = 5;
        int vSpacing = 0;
        int width = getWidth()-padding*2;
        int elementHeight = 15;

        itemView.setBounds(padding, padding, 20, elementHeight);
        //int textWidth = Math.max(getTextWidth(CLOSE_ACCOUNT_BUTTON.getString()), getTextWidth(FREE_LOCKED_BALANCE_CHECKBOX.getString())) +10;


        balanceLabel.setBounds(padding, itemView.getBottom()+vSpacing, width/4, elementHeight);
        balanceValueLabel.setBounds(balanceLabel.getRight(), balanceLabel.getTop(), (width-balanceLabel.getWidth())/2, elementHeight);

        int balanceValueTextBoxLeft = balanceValueLabel.getRight();
        int balanceValueTextBoxWidth = width - balanceValueLabel.getRight() + padding;


        lockedBalanceLabel.setBounds(padding, balanceLabel.getBottom()+vSpacing, balanceLabel.getWidth(), elementHeight);
        lockedBalanceValueLabel.setBounds(lockedBalanceLabel.getRight(), lockedBalanceLabel.getTop(), balanceValueTextBoxLeft-lockedBalanceLabel.getRight(), elementHeight);



        totalBalanceLabel.setBounds(padding, lockedBalanceLabel.getBottom()+vSpacing, balanceLabel.getWidth(), elementHeight);
        totalBalanceValueLabel.setBounds(totalBalanceLabel.getRight(), totalBalanceLabel.getTop(), width-totalBalanceLabel.getRight()+padding, elementHeight);

        if(isAdminMode) {
            balanceValueTextBox.setBounds(balanceValueTextBoxLeft, balanceLabel.getTop(), balanceValueTextBoxWidth, elementHeight);
            freeLockedBalanceCheckBox.setBounds(lockedBalanceValueLabel.getRight(), lockedBalanceValueLabel.getTop(), width-lockedBalanceValueLabel.getRight()+padding, elementHeight);
        }

        if(canManage) {
            closeBankButton.setBounds(balanceValueTextBoxLeft, padding, balanceValueTextBoxWidth, elementHeight);
        }

        setHeight(totalBalanceValueLabel.getBottom()+padding);

    }

    private void onCloseAccountButtonClicked()
    {
        deleteAccount = true;
        int backgroundColor = getBackgroundColor();
        backgroundColor = (backgroundColor & 0xFF000000) | 0x00e34829;
        setBackgroundColor(backgroundColor);
    }

    private void onBalanceTextBoxChanged(String value)
    {
        //long value = balanceValueTextBox.getInt();
        balanceChanged = true;
    }
    public boolean balanceHasChanged()
    {
        return balanceChanged;
    }
    public boolean deleteAccount()
    {
        return deleteAccount;
    }
    public boolean freeLockedBalance()
    {
        return freeLockedBalance;
    }

    public ItemID getItemID()
    {
        return itemID;
    }
    public int getAccountNumber()
    {
        return accountNumber;
    }

    public long getBalance()
    {
        if(balanceValueTextBox.getText().isEmpty())
            return 0;
        try {
            return (long)Math.max(0.0, ServerBank.convertToRawAmountStatic(balanceValueTextBox.getText()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    public void setBalanceLabel(long balance)
    {
        balanceValueLabel.setText(ServerBank.getFormattedAmountStatic(balance));
    }
    public void setBalance(long balance)
    {
        setBalanceLabel(balance);
    }
    public void setLockedBalance(long lockedBalance)
    {
        lockedBalanceValueLabel.setText(ServerBank.getFormattedAmountStatic(lockedBalance));
    }
    public void setTotalBalance(long totalBalance)
    {
        totalBalanceValueLabel.setText(ServerBank.getFormattedAmountStatic(totalBalance));
        if(!balanceChanged && isAdminMode)
            balanceValueTextBox.setText(ServerBank.getTextFieldString(totalBalance));
    }
    private void onFreeLockedBalanceCheckBoxClicked(Boolean checked)
    {
        freeLockedBalance = checked;
    }

}
