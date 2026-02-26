package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.Label;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class BankUserWidget extends BankSystemGuiElement {

    public static class TEXT
    {
        public static final String PREFIX = "gui."+ BankSystemMod.MOD_ID+".bank_user_widget.";
        public static final Component PERMISSION_EDIT_BUTTON = Component.translatable(PREFIX+"permission_edit_button");
        public static final Component REMOVE_BUTTON = Component.translatable(PREFIX+"remove_button");

        public static final Component PERMISSION_SCREEN = Component.translatable(PREFIX+"permission_screen");
        public static final Component PERMISSION_DEPOSIT = Component.translatable(PREFIX+"permission_deposit");
        public static final Component PERMISSION_WITHDRAW = Component.translatable(PREFIX+"permission_withdraw");
        public static final Component PERMISSION_MANAGE = Component.translatable(PREFIX+"permission_manage");
        public static final Component PERMISSION_SAVE = Component.translatable(PREFIX+"permission_save");
        public static final Component PERMISSION_CANCEL = Component.translatable(PREFIX+"permission_cancel");
    }

    private static class PermissionEditScreen extends BankSystemGuiScreen {
        private final GuiScreen parentScreen;
        private final BankUserData userData;

        private final CheckBox permissionDepositCheckBox;
        private final CheckBox permissionWithdrawCheckBox;
        private final CheckBox permissionManageCheckBox;
        private final Button saveButton;
        private final Button cancelButton;

        private final Consumer<Integer> onPermissionSet;

        protected PermissionEditScreen(BankUserData userDatam,
                                       Consumer<Integer> onPermissionSet,
                                       GuiScreen parentScreen) {
            super(TEXT.PERMISSION_SCREEN);
            this.userData = userDatam;
            this.onPermissionSet = onPermissionSet;
            this.parentScreen = parentScreen;

            permissionDepositCheckBox = new CheckBox(TEXT.PERMISSION_DEPOSIT.getString());
            permissionWithdrawCheckBox = new CheckBox(TEXT.PERMISSION_WITHDRAW.getString());
            permissionManageCheckBox = new CheckBox(TEXT.PERMISSION_MANAGE.getString());
            saveButton = new Button(TEXT.PERMISSION_SAVE.getString(), this::onSaveButtonClicked);
            cancelButton = new Button(TEXT.PERMISSION_CANCEL.getString(), this::onCancelButtonClicked);

            // Set the checkboxes based on the user data permissions
            permissionDepositCheckBox.setChecked(BankPermission.hasPermission(userData.permissions, BankPermission.DEPOSIT));
            permissionWithdrawCheckBox.setChecked(BankPermission.hasPermission(userData.permissions, BankPermission.WITHDRAW));
            permissionManageCheckBox.setChecked(BankPermission.hasPermission(userData.permissions, BankPermission.MANAGE));


            addElement(permissionDepositCheckBox);
            addElement(permissionWithdrawCheckBox);
            addElement(permissionManageCheckBox);
            addElement(saveButton);
            addElement(cancelButton);
        }

        @Override
        protected void updateLayout(Gui gui) {
            int width = getWidth();
            int height = getHeight();
            int spacing = 5;
            int elementHeight = 20;
            int xCenter = width / 2;
            int yCenter = height / 2;

            int xStart = xCenter - width/4; // Center the elements horizontally
            int yStart = yCenter - (4 * elementHeight + 3 * spacing) / 2; // Center the elements vertically

            permissionDepositCheckBox.setBounds(xStart, yStart, width / 2, elementHeight);
            permissionWithdrawCheckBox.setBounds(permissionDepositCheckBox.getLeft(), permissionDepositCheckBox.getBottom() + spacing, permissionDepositCheckBox.getWidth(), elementHeight);
            permissionManageCheckBox.setBounds(permissionWithdrawCheckBox.getLeft(), permissionWithdrawCheckBox.getBottom() + spacing, permissionWithdrawCheckBox.getWidth(), elementHeight);

            saveButton.setBounds(xStart, permissionManageCheckBox.getBottom() + spacing, permissionDepositCheckBox.getWidth()/2 - spacing, elementHeight);
            cancelButton.setBounds(saveButton.getRight() + spacing*2, saveButton.getTop(), permissionDepositCheckBox.getWidth()/2 - spacing, elementHeight);
        }

        @Override
        public void onClose() {
            super.onClose();
            if (parentScreen != null) {
                minecraft.setScreen(parentScreen);
            }
        }

        private void onSaveButtonClicked()
        {
            int permission = 0;
            if(permissionDepositCheckBox.isChecked())
                permission = BankPermission.addPermission(permission, BankPermission.DEPOSIT);
            if(permissionWithdrawCheckBox.isChecked())
                permission = BankPermission.addPermission(permission, BankPermission.WITHDRAW);
            if(permissionManageCheckBox.isChecked())
                permission = BankPermission.addPermission(permission, BankPermission.MANAGE);

            onPermissionSet.accept(permission);
            this.onClose();
        }
        private void onCancelButtonClicked()
        {
            this.onClose();
        }
    }

    private final Label nameLabel;
    private final Button permissionEditButton;
    private final Button removeButton;

    private final BankUserData userData;
    private final boolean canManage;
    private final GuiScreen parentScreen;
    private final Consumer<BankUserWidget> onRemoveUser;


    public BankUserWidget(BankUserData userData, Consumer<BankUserWidget> onRemoveUser, boolean canManage, GuiScreen parentScreen)
    {
        super();
        setHeight(20);
        this.userData = userData;
        this.canManage = canManage;
        this.parentScreen = parentScreen;
        this.onRemoveUser = onRemoveUser;

        nameLabel = new Label();
        nameLabel.setAlignment(Alignment.CENTER);
        nameLabel.setText(userData.userName);
        addChild(nameLabel);

        if(canManage)
        {
            permissionEditButton = new Button(TEXT.PERMISSION_EDIT_BUTTON.getString(), this::onPermissionEditButtonClicked);
            removeButton = new Button(TEXT.REMOVE_BUTTON.getString(), this::onRemoveButtonClicked);

            addChild(permissionEditButton);
            addChild(removeButton);
        }
        else
        {
            permissionEditButton = null;
            removeButton = null;
        }

        // Initialize UI elements here if needed
    }

    public BankUserData getUserData() {
        return userData;
    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {
        int width = getWidth();
        int height = getHeight();

        if(canManage)
        {
            int editButtonWidth = getTextWidth(TEXT.PERMISSION_EDIT_BUTTON.getString()) + spacing * 2;
            int removeButtonWidth = getTextWidth(TEXT.REMOVE_BUTTON.getString()) + spacing * 2;

            nameLabel.setBounds(0, 0, width - editButtonWidth - removeButtonWidth, height);
            permissionEditButton.setBounds(nameLabel.getRight(), 0, editButtonWidth, height);
            removeButton.setBounds(permissionEditButton.getRight(), 0, removeButtonWidth, height);
        }
        else
        {
            nameLabel.setBounds(0, 0, width, height);
        }
    }
    private void onPermissionEditButtonClicked() {
        if (canManage) {
            PermissionEditScreen permissionEditScreen = new PermissionEditScreen(userData, (permission)->
            {
                userData.permissions = permission;

            }, parentScreen);
            getMinecraft().setScreen(permissionEditScreen);
        }
    }
    private void onRemoveButtonClicked() {
        if (canManage) {
            onRemoveUser.accept(this);
        }
    }
}
