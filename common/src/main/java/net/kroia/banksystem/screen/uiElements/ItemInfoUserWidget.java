package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.screen.custom.BankAccountManagementScreen;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class ItemInfoUserWidget extends GuiElement {

    private static final String PREFIX = "gui."+ BankSystemMod.MOD_ID+".iteminfo_user_widget.";
    private static final Component MANAGE_BUTTON = Component.translatable(PREFIX+"manage_button");

    public static final int nameRatio = 20;
    public static final int balanceRatio = 10;
    public static final int lockedBalanceRatio = 10;
    public static final int totalBalanceRatio = 10;
    public static final int manageButtonRatio = 10;
    public static final int sumRatio = nameRatio+balanceRatio+lockedBalanceRatio+totalBalanceRatio+manageButtonRatio;

    final Label nameTextLabel;
    final Label balanceTextLabel;
    final Label lockedBalanceTextLabel;
    final Label totalBalanceTextLabel;
    final Button manageButton;

    UUID playerUUID;

    public ItemInfoUserWidget() {
        super();
        setHeight(20);

        nameTextLabel = new Label();
        balanceTextLabel = new Label();
        lockedBalanceTextLabel = new Label();
        totalBalanceTextLabel = new Label();
        balanceTextLabel.setAlignment(Alignment.CENTER);
        lockedBalanceTextLabel.setAlignment(Alignment.CENTER);
        totalBalanceTextLabel.setAlignment(Alignment.CENTER);
        manageButton = new Button(MANAGE_BUTTON.getString(), this::onManageButtonClicked);


        addChild(nameTextLabel);
        addChild(balanceTextLabel);
        addChild(lockedBalanceTextLabel);
        addChild(totalBalanceTextLabel);
        addChild(manageButton);
    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {
        int padding = 2;
        if(getParent() != null)
        {
            setWidth(getParent().getWidth());
        }
        int width = getWidth()-padding*2;
        int height = getHeight()-padding*2;

        int _nameRatio = nameRatio * width / sumRatio;
        int _balanceRatio = balanceRatio * width / sumRatio;
        int _lockedBalanceRatio = lockedBalanceRatio * width / sumRatio;
        int _totalBalanceRatio = totalBalanceRatio * width / sumRatio;
        int _manageButtonRatio = manageButtonRatio * width / sumRatio;


        nameTextLabel.setBounds(padding, padding, _nameRatio, height);
        balanceTextLabel.setBounds(nameTextLabel.getRight(), padding, _balanceRatio, height);
        lockedBalanceTextLabel.setBounds(balanceTextLabel.getRight(), padding, _lockedBalanceRatio, height);
        totalBalanceTextLabel.setBounds(lockedBalanceTextLabel.getRight(), padding, _totalBalanceRatio, height);
        manageButton.setBounds(totalBalanceTextLabel.getRight(), padding, _manageButtonRatio, height);
    }

    public void setPlayerName(String name)
    {
        nameTextLabel.setText(name);
    }
    public String getPlayerName()
    {
        return nameTextLabel.getText();
    }
    public void setBalance(long balance)
    {
        balanceTextLabel.setText(Bank.getNormalizedAmount(balance));
    }
    public void setLockedBalance(long lockedBalance)
    {
        lockedBalanceTextLabel.setText(Bank.getNormalizedAmount(lockedBalance));
    }
    public void setTotalBalance(long totalBalance)
    {
        totalBalanceTextLabel.setText(Bank.getNormalizedAmount(totalBalance));
    }
    public void setPlayerUUID(UUID playerUUID)
    {
        this.playerUUID = playerUUID;
    }

    private void onManageButtonClicked()
    {
        BankAccountManagementScreen.openScreen(playerUUID, (GuiScreen)getRoot().getScreen());
    }
}
