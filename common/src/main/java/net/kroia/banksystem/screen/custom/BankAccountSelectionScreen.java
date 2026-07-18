package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class BankAccountSelectionScreen extends BankSystemGuiScreen {
    public static class TEXT {
        public static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".bank_account_selection_screen.";
        public static final Component TITLE = Component.translatable(PREFIX + "title");
        public static final Component TITLE_LABEL = Component.translatable(PREFIX + "title_label");
        public static final Component SELECT_ACCOUNT_BUTTON = Component.translatable(PREFIX+"select_account_button");
    }
    public static class AccountButton extends Button
    {
        private BankAccountData accountData;
        private final ItemView iconView;
        private final Label accountNameLabel;
        public AccountButton() {
            this(null);
        }
        public AccountButton(BankAccountData accountData) {
            super("");
            this.accountData = accountData;

            iconView = new ItemView();
            accountNameLabel = new Label();
            accountNameLabel.setAlignment(GuiElement.Alignment.CENTER);

            if(this.accountData != null)
            {
                iconView.setItemStack(accountData.accountIcon != null ? accountData.accountIcon.getStack() : null);
                accountNameLabel.setText(accountData.accountName);
            }

            removeChilds();
            addChild(iconView);
            addChild(accountNameLabel);
            setHeight(20);
        }

        @Override
        protected void layoutChanged()
        {
            int width = getWidth();
            int height = getHeight();
            iconView.setBounds(0, 0, height, height);
            accountNameLabel.setBounds(iconView.getRight(), 0, width - iconView.getRight(), height);
        }

        public void setAccountData(BankAccountData accountData) {
            this.accountData = accountData;
            if(accountData != null)
            {
                iconView.setItemStack(accountData.accountIcon != null ? accountData.accountIcon.getStack() : null);
                accountNameLabel.setText(accountData.accountName);
            }
            else {
                iconView.setItemStack(null);
                accountNameLabel.setText("");
            }
        }
        public BankAccountData getAccountData() {
            return accountData;
        }
    }


    private final Label titleLabel;
    private final ListView accountsListView;


    private final UUID playerUUID;
    private final Consumer<Integer> onAccountSelected;
    private final int permissionFilter;
    private final boolean requireAllPermissions;
    public BankAccountSelectionScreen(Screen parent, UUID playerUUID, Consumer<Integer> onAccountSelected)
    {
        this(parent, playerUUID, onAccountSelected, BankPermission.getAllPermissions());
    }
    public BankAccountSelectionScreen(Screen parent, UUID playerUUID, Consumer<Integer> onAccountSelected, int permissionFilter)
    {
        this(parent, playerUUID, onAccountSelected, permissionFilter, false);
    }

    /**
     * @param permissionFilter      permission bit mask the listed accounts must satisfy
     * @param requireAllPermissions FR-001: when {@code true}, an account is listed only if the
     *                              player holds <b>every</b> bit in {@code permissionFilter}
     *                              (AND); when {@code false} (default, unchanged behavior) an
     *                              account is listed if the player holds <b>any</b> bit (OR).
     */
    public BankAccountSelectionScreen(Screen parent, UUID playerUUID, Consumer<Integer> onAccountSelected, int permissionFilter, boolean requireAllPermissions)
    {
        super(TEXT.TITLE, parent);
        this.playerUUID = playerUUID;
        this.onAccountSelected = onAccountSelected;
        this.permissionFilter = permissionFilter;
        this.requireAllPermissions = requireAllPermissions;


        titleLabel = new Label(TEXT.TITLE_LABEL.getString());
        titleLabel.setAlignment(GuiElement.Alignment.CENTER);
        addElement(titleLabel);

        accountsListView = new VerticalListView();
        LayoutVertical layout = new LayoutVertical();
        layout.stretchX = true;
        accountsListView.setLayout(layout);
        addElement(accountsListView);


        getBankManager().getBankAccountsDataAsync(this.playerUUID).thenAccept(this::onBankAccountsReceived);

    }


    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();
        int padding = 5;
        int spacing = 5;

        titleLabel.setBounds(width/4, padding, width/2, 20);
        accountsListView.setBounds(width/4, titleLabel.getBottom()+spacing, titleLabel.getWidth(), height-titleLabel.getBottom()-padding-spacing);
    }

    private void onBankAccountsReceived(List<BankAccountData> bankAccounts) {
        accountsListView.removeChilds();
        for(BankAccountData accountData : bankAccounts) {

            boolean matchesFilter = requireAllPermissions
                    ? accountData.hasAllPermissions(playerUUID, permissionFilter)   // FR-001: AND
                    : accountData.hasAnyPermission(playerUUID, permissionFilter);   // default: OR
            if(!matchesFilter) {
                continue; // Skip accounts that do not match the permission filter
            }

            AccountButton element = new AccountButton(accountData);
            element.setOnRisingEdge(() -> {
                onAccountSelected.accept(accountData.accountNumber);
                this.onClose();
            });
            accountsListView.addChild(element);
        }
    }
}
