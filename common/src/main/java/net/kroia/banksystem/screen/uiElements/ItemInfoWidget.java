package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.SyncServerBank;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;

public class ItemInfoWidget extends BankSystemGuiElement {


    private static final String PREFIX = "gui."+BankSystemMod.MOD_ID+".iteminfo_widget.";

    private static final Component ACCOUNT_NAME = Component.translatable(PREFIX+"account_name");
    private static final Component BALANCE = Component.translatable(PREFIX+"balance");
    private static final Component LOCKED_BALANCE = Component.translatable(PREFIX+"locked_balance");
    private static final Component TOTAL_BALANCE = Component.translatable(PREFIX+"total_balance");
    private static final Component SEARCH_PLAYER = Component.translatable(PREFIX+"search_player");


    ItemID itemID;


    final Label totalSuplyTextLabel;

    final Label totalLockedTextLabel;


    final Label accountNameLabel;
    final Label balanceLabel;
    final Label lockedBalanceLabel;
    final Label totalBalanceLabel;
    final Label searchLabel;
    final TextBox searchTextBox;
    final ListView playerDataView;
    final HashMap<Integer, ItemInfoUserWidget> playerDataWidgets = new HashMap<>();



    public ItemInfoWidget()
    {
        super();
        this.itemID = null;

        totalSuplyTextLabel = new Label();
        totalLockedTextLabel = new Label();

        accountNameLabel = new Label(ACCOUNT_NAME.getString());
        balanceLabel = new Label(BALANCE.getString());
        lockedBalanceLabel = new Label(LOCKED_BALANCE.getString());
        totalBalanceLabel = new Label(TOTAL_BALANCE.getString());
        accountNameLabel.setAlignment(Alignment.CENTER);
        balanceLabel.setAlignment(Alignment.CENTER);
        lockedBalanceLabel.setAlignment(Alignment.CENTER);
        totalBalanceLabel.setAlignment(Alignment.CENTER);
        playerDataView = new VerticalListView();
        LayoutVertical layout = new LayoutVertical();
        layout.stretchX = true;
        layout.padding = 0;
        layout.spacing = 0;
        playerDataView.setLayout(layout);


        searchLabel = new Label(SEARCH_PLAYER.getString());
        searchTextBox = new TextBox();
        searchTextBox.setOnTextChanged(this::applyPlayerFiler);




        addChild(totalSuplyTextLabel);
        addChild(totalLockedTextLabel);
        addChild(searchLabel);
        addChild(searchTextBox);

        addChild(accountNameLabel);
        addChild(balanceLabel);
        addChild(lockedBalanceLabel);
        addChild(totalBalanceLabel);
        addChild(playerDataView);

    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {
        int padding = 2;
        int width = getWidth()-2*padding;
        int height = getHeight()-2*padding;
        int elementHeight = 16;

        int widthHalf = width/2;
        totalSuplyTextLabel.setBounds(padding, padding, width, elementHeight);
        totalLockedTextLabel.setBounds(padding, totalSuplyTextLabel.getBottom(), width, elementHeight);
        int textWidth = getFont().width(SEARCH_PLAYER.getString())+10;
        int xPos = Math.max(width/3, getFont().width(totalSuplyTextLabel.getText())+10);
        searchLabel.setBounds(xPos, padding, textWidth, elementHeight);
        searchTextBox.setBounds(searchLabel.getRight(), searchLabel.getTop(), width-searchLabel.getRight(), elementHeight);

        int reducedWidth = width - playerDataView.getScrollbarThickness();
        int _nameRatio = ItemInfoUserWidget.nameRatio * reducedWidth / ItemInfoUserWidget.sumRatio;
        int _balanceRatio = ItemInfoUserWidget.balanceRatio * reducedWidth / ItemInfoUserWidget.sumRatio;
        int _lockedBalanceRatio = ItemInfoUserWidget.lockedBalanceRatio * reducedWidth / ItemInfoUserWidget.sumRatio;
        int _totalBalanceRatio = ItemInfoUserWidget.totalBalanceRatio * reducedWidth / ItemInfoUserWidget.sumRatio;
        accountNameLabel.setBounds(padding, totalLockedTextLabel.getBottom(), _nameRatio, elementHeight);
        balanceLabel.setBounds(accountNameLabel.getRight(), totalLockedTextLabel.getBottom(), _balanceRatio, elementHeight);
        lockedBalanceLabel.setBounds(balanceLabel.getRight(), totalLockedTextLabel.getBottom(), _lockedBalanceRatio, elementHeight);
        totalBalanceLabel.setBounds(lockedBalanceLabel.getRight(), totalLockedTextLabel.getBottom(), _totalBalanceRatio, elementHeight);
        playerDataView.setBounds(padding, accountNameLabel.getBottom(), width, height- accountNameLabel.getBottom()+padding);
    }


    public void setItemInfo(ItemInfoData info) {
        if(info == null) {
            this.itemID = null;
            return;
        }
        this.itemID = info.itemID;
        if(this.itemID == null)
            return;

        double totalSupply = info.totalSupply; //BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getTotalSupply(itemID);
        double totalLocked = info.totalLocked; //BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getTotalLocked(itemID);


        totalSuplyTextLabel.setText(BankSystemTextMessages.getItemInfoWidgetTotalSuplyMessage(SyncServerBank.getNormalizedAmountStatic(totalSupply)));
        totalLockedTextLabel.setText(BankSystemTextMessages.getItemInfoWidgetTotalLockedMessage(SyncServerBank.getNormalizedAmountStatic(totalLocked)));


        List<BankAccountData> bankData = info.bankAccounts;
        if(bankData == null)
            return;
        // sort by total balance
        bankData = bankData.stream().sorted((a, b) -> Long.compare(
                b.bankData.get(info.itemID).balance+b.bankData.get(info.itemID).lockedBalance,
                a.bankData.get(info.itemID).balance+a.bankData.get(info.itemID).lockedBalance)).toList();

        playerDataView.getLayout().enabled = false;
        HashMap<Integer, ItemInfoUserWidget> toRemoveItems = new HashMap<>(playerDataWidgets);

        for(BankAccountData account : bankData)
        {
            BankData bankDataItem = account.bankData.get(itemID);
            ItemInfoUserWidget userWidget = toRemoveItems.get(account.accountNumber);
            if(userWidget == null)
            {
                userWidget = new ItemInfoUserWidget();
                playerDataWidgets.put(account.accountNumber, userWidget);
                playerDataView.addChild(userWidget);
            }
            else
                toRemoveItems.remove(account.accountNumber);

            userWidget.setBalance(bankDataItem.balance);
            userWidget.setLockedBalance(bankDataItem.lockedBalance);
            userWidget.setTotalBalance(bankDataItem.balance + bankDataItem.lockedBalance);
            userWidget.setAccountNumber(account.accountNumber);
            userWidget.setAccountName(account.accountName);
            userWidget.setUserNames(account.getSearchTexts());

        }
        for(ItemInfoUserWidget item : toRemoveItems.values())
        {
            playerDataView.removeChild(item);
            playerDataWidgets.remove(item.getAccountNumber());
        }
        playerDataView.getLayout().enabled = true;
        layoutChangedInternal();
    }

    private void applyPlayerFiler(String name)
    {
        name = name.toLowerCase();
        playerDataView.removeChilds();
        for(ItemInfoUserWidget widget : playerDataWidgets.values())
        {
            if(widget.hasUserName(name))
                playerDataView.addChild(widget);
        }
    }
}
