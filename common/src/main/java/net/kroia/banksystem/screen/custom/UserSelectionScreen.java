package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public class UserSelectionScreen extends BankSystemGuiScreen {
    private static final String prefix = "gui." + BankSystemMod.MOD_ID + ".user_selection_screen.";
    private static final Component TITLE = Component.translatable(prefix+"title");




    private final ListView userListView;
    private final Consumer<UserData> onUserSelected;
    private final GuiScreen parent;
    public UserSelectionScreen(GuiScreen parent, Consumer<UserData> onUserSelected)
    {
        super(TITLE);
        this.parent = parent;
        this.onUserSelected = onUserSelected;

        userListView = new VerticalListView();
        LayoutVertical layout = new LayoutVertical();
        layout.stretchX = true;
        userListView.setLayout(layout);


        addElement(userListView);
    }

    @Override
    public void onClose() {
        super.onClose();
        if (parent != null) {
            minecraft.setScreen(parent);
        }
    }
    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();

        userListView.setBounds(width/4, 10, width/2, height - 20);
    }

    public void setUsers(List<UserData> users) {
        userListView.removeChilds();
        for (UserData user : users) {
            Button button = new Button(user.userName(), () -> {
                onUserSelected.accept(user);
                onClose();
            });
            button.setHeight(20);
            userListView.addChild(button);
        }
    }
}
