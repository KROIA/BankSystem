package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.menu.custom.BankUploadContainerMenu;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankUploadDataPacket;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiContainerScreen;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.ContainerView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class BankUploadScreen extends GuiContainerScreen<BankUploadContainerMenu> {

    private static final Component INVENTORY_NAME_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_upload_screen.inventory_name");
    private static final Component CONNECT_BUTTON = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_upload_screen.connect_button");
    private static final Component DISCONNECT_BUTTON = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_upload_screen.disconnect_button");
    private static final Component DO_DROP_IF_NOT_BANKABLE = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_upload_screen.do_drop_if_not_bankable");

    private  class SettingsMenu extends GuiElement {

        public final Button connectDisconnectButton;
        public final CheckBox doDropIfNotBankableCheckBox;

        public static final int elementHeight = 16;

        public SettingsMenu(BankUploadScreen parent)
        {
            connectDisconnectButton = new Button((isOwned?DISCONNECT_BUTTON.getString():CONNECT_BUTTON.getString()), parent::onConnectDisconnectButtonClicked);
            doDropIfNotBankableCheckBox = new CheckBox(DO_DROP_IF_NOT_BANKABLE.getString(), parent::onDoDropIfNotBankableCheckBoxClicked);
            doDropIfNotBankableCheckBox.setChecked(dropIfNotBankable);

            addChild(connectDisconnectButton);
            addChild(doDropIfNotBankableCheckBox);
        }

        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {
            int padding = 2;
            int width = getWidth()-2*padding;

            connectDisconnectButton.setBounds(padding, padding, width, elementHeight);
            doDropIfNotBankableCheckBox.setBounds(connectDisconnectButton.getLeft(), connectDisconnectButton.getBottom()+padding, connectDisconnectButton.getWidth(), elementHeight);
            setHeight(doDropIfNotBankableCheckBox.getBottom()+padding);
        }
    }

    private final BlockPos pos;

    private final ContainerView<BankUploadContainerMenu> inventoryView;
    private final SettingsMenu settingsMenu;

    public static boolean isOwned = false;
    public static boolean dropIfNotBankable = false;



    private static BankUploadScreen instance;

    public BankUploadScreen(BankUploadContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        instance = this;
        this.pos = pMenu.getBlockPos();


        settingsMenu = new SettingsMenu(this);
        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 176, 166));
        settingsMenu.setWidth(inventoryView.getWidth());

        addElement(settingsMenu);
        addElement(inventoryView);

    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.width;
        int height = this.height;
        int spacing = 5;

        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();

        settingsMenu.setBounds((width-inventoryWidth)/2, (height-inventoryHeight-spacing-settingsMenu.getHeight())/2, inventoryWidth, 0);
        inventoryView.setPosition(settingsMenu.getLeft(), settingsMenu.getBottom()+spacing);
    }

    public static void handlePacket(SyncBankUploadDataPacket packet) {
        isOwned = packet.isOwned();
        dropIfNotBankable = packet.doesDropIfNotBankable();
        if(instance != null) {
            if (isOwned) {
                instance.settingsMenu.connectDisconnectButton.setLabel(DISCONNECT_BUTTON.getString());
            } else {
                instance.settingsMenu.connectDisconnectButton.setLabel(CONNECT_BUTTON.getString());
            }
            instance.settingsMenu.doDropIfNotBankableCheckBox.setChecked(dropIfNotBankable);
        }
    }

    public void onClose() {
        instance = null;
        isOwned = false;
        dropIfNotBankable = false;
        super.onClose();
    }

    private void onConnectDisconnectButtonClicked()
    {
        isOwned = !isOwned;
        sendUpdatePacket();
    }
    private void onDoDropIfNotBankableCheckBoxClicked()
    {
        sendUpdatePacket();
    }

    private void sendUpdatePacket()
    {
        UpdateBankUploadBlockEntityPacket.sendPacket(pos, isOwned, settingsMenu.doDropIfNotBankableCheckBox.isChecked());
    }
}
