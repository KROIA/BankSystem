package net.kroia.banksystem.client;

import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Scrollable list of the saved items that "Remove missing items" would drop, opened from the load-refused screen. */
public class MissingItemsScreen extends BankSystemGuiScreen {

    private final Label titleLabel;
    private final VerticalListView listView = new VerticalListView();
    private final Button backButton;

    public MissingItemsScreen(Screen parent, List<String> items) {
        super(Component.translatable("gui.banksystem.load_refused.items_title", items.size()), parent);
        titleLabel = new Label(getTitle().getString());
        backButton = new Button(Component.translatable("gui.banksystem.load_refused.back").getString(), this::onClose);
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        listView.setLayout(layout);
        for (String item : items) {
            Label row = new Label(item);
            row.setHeight(12);
            listView.addChild(row);
        }
        addElement(titleLabel);
        addElement(listView);
        addElement(backButton);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int p = 5, h = 20;
        int w = getWidth() - 2 * p;
        titleLabel.setBounds(p, p, w, h);
        listView.setBounds(p, p + h + p, w, getHeight() - 2 * h - 4 * p);
        backButton.setBounds(p + (w - 100) / 2, getHeight() - p - h, 100, h);
    }
}
