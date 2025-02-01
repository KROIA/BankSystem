package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

public class ItemInfoWidget extends GuiElement {

    private static final String PREFIX = "gui."+BankSystemMod.MOD_ID+".iteminfo_widget.";

    private static final Component USER_NAME = Component.translatable(PREFIX+"player_name");
    private static final Component BALANCE = Component.translatable(PREFIX+"balance");
    private static final Component LOCKED_BALANCE = Component.translatable(PREFIX+"locked_balance");
    private static final Component TOTAL_BALANCE = Component.translatable(PREFIX+"total_balance");
    private static final Component SEARCH_PLAYER = Component.translatable(PREFIX+"search_player");


    String itemID;


    final Label totalSuplyTextLabel;

    final Label totalLockedTextLabel;


    final Label playerNameLabel;
    final Label balanceLabel;
    final Label lockedBalanceLabel;
    final Label totalBalanceLabel;
    final Label searchLabel;
    final TextBox searchTextBox;
    final ListView playerDataView;
    final HashMap<String, ItemInfoUserWidget> playerDataWidgets = new HashMap<>();

    public ItemInfoWidget()
    {
        super();
        this.itemID = null;

        totalSuplyTextLabel = new Label();
        totalLockedTextLabel = new Label();

        playerNameLabel = new Label(USER_NAME.getString());
        balanceLabel = new Label(BALANCE.getString());
        lockedBalanceLabel = new Label(LOCKED_BALANCE.getString());
        totalBalanceLabel = new Label(TOTAL_BALANCE.getString());
        playerNameLabel.setAlignment(Alignment.CENTER);
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

        addChild(playerNameLabel);
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
        playerNameLabel.setBounds(padding, totalLockedTextLabel.getBottom(), _nameRatio, elementHeight);
        balanceLabel.setBounds(playerNameLabel.getRight(), totalLockedTextLabel.getBottom(), _balanceRatio, elementHeight);
        lockedBalanceLabel.setBounds(balanceLabel.getRight(), totalLockedTextLabel.getBottom(), _lockedBalanceRatio, elementHeight);
        totalBalanceLabel.setBounds(lockedBalanceLabel.getRight(), totalLockedTextLabel.getBottom(), _totalBalanceRatio, elementHeight);
        playerDataView.setBounds(padding, playerNameLabel.getBottom(), width, height-playerNameLabel.getBottom()+padding);
    }


    public void setItemID(String itemID) {
        this.itemID = itemID;
        if(this.itemID == null)
            return;

        long totalSupply = ClientBankManager.getTotalSupply(itemID);
        long totalLocked = ClientBankManager.getTotalLocked(itemID);
        totalSuplyTextLabel.setText(BankSystemTextMessages.getItemInfoWidgetTotalSuplyMessage(Bank.getNormalizedAmount(totalSupply)));
        totalLockedTextLabel.setText(BankSystemTextMessages.getItemInfoWidgetTotalLockedMessage(Bank.getNormalizedAmount(totalLocked)));


        Map<String, SyncItemInfoPacket.BankData> bankData = ClientBankManager.getItemInfoBankData(itemID);
        if(bankData == null)
            return;
        // sort by key
        bankData.entrySet().stream().sorted(Map.Entry.comparingByKey());
        playerDataView.getLayout().enabled = false;
        HashMap<String, ItemInfoUserWidget> toRemoveItems = new HashMap<>(playerDataWidgets);

        for(String playerName : bankData.keySet())
        {
            ItemInfoUserWidget userWidget = toRemoveItems.get(playerName);
            if(userWidget == null)
            {
                userWidget = new ItemInfoUserWidget();
                playerDataWidgets.put(playerName, userWidget);
                playerDataView.addChild(userWidget);
            }
            else
                toRemoveItems.remove(playerName);


            SyncItemInfoPacket.BankData data = bankData.get(playerName);

            userWidget.setBalance(data.balance);
            userWidget.setLockedBalance(data.lockedBalance);
            userWidget.setTotalBalance(data.lockedBalance+data.balance);
            userWidget.setPlayerName(playerName);
            userWidget.setPlayerUUID(data.player);


        }
        for(ItemInfoUserWidget item : toRemoveItems.values())
        {
            playerDataView.removeChild(item);
            playerDataWidgets.remove(item.getPlayerName());
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
            if(widget.getPlayerName().toLowerCase().contains(name))
                playerDataView.addChild(widget);
        }
    }
}
