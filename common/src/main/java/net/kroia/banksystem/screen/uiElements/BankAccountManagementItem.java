package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.network.chat.Component;

public class BankAccountManagementItem extends GuiElement {

    private static final String PREFIX = "gui."+ BankSystemMod.MOD_ID+".bank_account_management_item.";
    private static final Component CLOSE_ACCOUNT_BUTTON = Component.translatable(PREFIX+"close_account_button");
    private static final Component FREE_LOCKED_BALANCE_CHECKBOX = Component.translatable(PREFIX+"free_locked_balance_checkbox");
    private static final Component BALANCE = Component.translatable(PREFIX+"balance");
    private static final Component LOCKED_BALANCE = Component.translatable(PREFIX+"locked_balance");
    private static final Component TOTAL_BALANCE = Component.translatable(PREFIX+"total_balance");
    private static final Component SAVE_CHANGES = Component.translatable(PREFIX+"save_changes");

    private final String playerName;
    private final String itemID;
    private final ItemView itemView;
    private final Button closeAccountButton;
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


    public BankAccountManagementItem(String itemID, String playerName)
    {
        super();
        this.itemID = itemID;
        this.playerName = playerName;
        itemView = new ItemView(ItemUtilities.createItemStackFromId(itemID));
        //closeAccountButton = new Button(CLOSE_ACCOUNT_BUTTON.getString(), this::onCloseAccountButtonClicked);
        balanceLabel = new Label(BALANCE.getString());
        balanceValueLabel = new Label();
        balanceValueTextBox = new TextBox();
        balanceValueTextBox.setAllowLetters(false);
        balanceValueTextBox.setOnTextChanged(this::onBalanceTextBoxChanged);
        balanceValueTextBox.setMaxChars(6*3+1); // Max size of a long
        lockedBalanceLabel = new Label(LOCKED_BALANCE.getString());
        lockedBalanceValueLabel = new Label();
        totalBalanceLabel = new Label(TOTAL_BALANCE.getString());
        totalBalanceValueLabel = new Label();

        closeAccountButton = new Button(CLOSE_ACCOUNT_BUTTON.getString(), () -> {
            String askTitle = BankSystemTextMessages.getBankAccountManagementItemAskRemoveTitleMessage(itemID);
            String askMessage = BankSystemTextMessages.getBankAccountManagementItemAskRemoveMessage(itemID, playerName);
            AskPopupScreen popup = new AskPopupScreen((GuiScreen)getRoot().getScreen(), this::onCloseAccountButtonClicked, () -> {}, askTitle, askMessage);
            popup.setSize(400,100);
            popup.setColors(0xFFe8711c, 0xFFe04c12, 0xFFf22718, 0xFF70e815);
            getMinecraft().setScreen(popup);
        });
        freeLockedBalanceCheckBox = new CheckBox(FREE_LOCKED_BALANCE_CHECKBOX.getString(), this::onFreeLockedBalanceCheckBoxClicked);



        addChild(itemView);
        addChild(closeAccountButton);
        addChild(freeLockedBalanceCheckBox);

        addChild(balanceLabel);
        addChild(balanceValueLabel);
        addChild(balanceValueTextBox);
        addChild(lockedBalanceLabel);
        addChild(lockedBalanceValueLabel);
        addChild(totalBalanceLabel);
        addChild(totalBalanceValueLabel);
    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {
        int padding = 5;
        int spacing = 2;
        int width = getWidth()-padding*2;
        int elementHeight = 20;

        itemView.setBounds(padding, padding, 20, elementHeight);
        int textWidth = getFont().width(CLOSE_ACCOUNT_BUTTON.getString())+10;
        closeAccountButton.setBounds(width-textWidth, padding, textWidth, elementHeight);
        textWidth = getFont().width(FREE_LOCKED_BALANCE_CHECKBOX.getString())+10;
        freeLockedBalanceCheckBox.setBounds(closeAccountButton.getLeft()-spacing-textWidth-elementHeight, padding, textWidth+elementHeight, elementHeight);

        balanceLabel.setBounds(padding, itemView.getBottom()+spacing, width/4, elementHeight);
        balanceValueLabel.setBounds(balanceLabel.getRight(), balanceLabel.getTop(), (width-balanceLabel.getWidth()-padding)/2, elementHeight);
        balanceValueTextBox.setBounds(balanceValueLabel.getRight(), balanceLabel.getTop(), balanceValueLabel.getWidth(), elementHeight);

        lockedBalanceLabel.setBounds(padding, balanceLabel.getBottom()+spacing, balanceLabel.getWidth(), elementHeight);
        lockedBalanceValueLabel.setBounds(lockedBalanceLabel.getRight(), lockedBalanceLabel.getTop(), width-lockedBalanceLabel.getWidth()-padding, elementHeight);

        totalBalanceLabel.setBounds(padding, lockedBalanceLabel.getBottom()+spacing, balanceLabel.getWidth(), elementHeight);
        totalBalanceValueLabel.setBounds(totalBalanceLabel.getRight(), totalBalanceLabel.getTop(), width-totalBalanceLabel.getWidth()-padding, elementHeight);

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

    public String getItemID()
    {
        return itemID;
    }
    public String getPlayerName()
    {
        return playerName;
    }

    public long getBalance()
    {
        return Math.max(0,balanceValueTextBox.getLong());
    }
    public void setBalanceLabel(long balance)
    {
        balanceValueLabel.setText(getFormattedAmount(balance));
    }
    public void setBalance(long balance)
    {
        setBalanceLabel(balance);
    }
    public void setLockedBalance(long lockedBalance)
    {
        lockedBalanceValueLabel.setText(getFormattedAmount(lockedBalance));
    }
    public void setTotalBalance(long totalBalance)
    {
        totalBalanceValueLabel.setText(getFormattedAmount(totalBalance));
        balanceValueTextBox.setText(String.valueOf(totalBalance));
    }
    private void onFreeLockedBalanceCheckBoxClicked()
    {
        freeLockedBalance = freeLockedBalanceCheckBox.isChecked();
    }
    private String getFormattedAmount(long amount)
    {
        String nr = String.valueOf(amount);
        // add ' for every 3 digits
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for(int j = nr.length()-1; j >= 0; j--)
        {
            sb.append(nr.charAt(j));
            i++;
            if(i % 3 == 0 && j > 0)
                sb.append('\'');
        }
        return sb.reverse().toString();
    }
}
