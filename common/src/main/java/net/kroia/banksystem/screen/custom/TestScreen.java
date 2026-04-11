package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.banking.bankmanager.AsyncBankManager;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class TestScreen extends BankSystemGuiScreen {
    private static final String PREFIX = "gui.";
    private static final String NAME = ".test_screen.";
    public static final String COMPONENT_STR_START = PREFIX + BankSystemMod.MOD_ID + NAME;
    public static final Component TITLE = Component.translatable(COMPONENT_STR_START + "title");

    private final Button testButton;
    private final IAsyncBankManager  secretAsyncBankManager;

    protected TestScreen() {
        super(TITLE);

        testButton = new Button("Test", this::onTestButtonClicked);

        secretAsyncBankManager = AsyncBankManager.createClientManager();

        addElement(testButton);
    }
    public static void openScreen()
    {
        TestScreen screen = new TestScreen();
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();

        testButton.setBounds(0,0, 30,20);


    }

    private void onTestButtonClicked()
    {
        secretAsyncBankManager.getPersonalBankAccountAsync(getThisPlayerUUID()).thenAccept(account -> {
            account.getBankAsync(MoneyItem.getItemID()).thenAccept(bank -> {
                bank.depositAsync(1000).thenAccept(deposited -> {
                    info("Test deposit status: "+deposited);
                });
            });
        });
    }
}
