package net.kroia.banksystem.screen.widgets;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Task #47 follow-up (v2.1.0) — Reusable company picker widget. Given a list
 * of {@link CompanyOption}s, renders a vertical scrollable list of
 * {@link CompanySelectionButton}s and fires {@code onSelect} with the chosen
 * company id. Disabled options remain visible (grayed out) with the
 * description surfaced as a below-cursor tooltip.
 * <p>
 * Callers that need bespoke lifecycle behaviour (e.g. releasing a server-side
 * viewer lock on close) subclass this and override {@link #removed()}.
 */
public class CompanySelectionScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_selection.";
    private static final Component DEFAULT_EMPTY = Component.translatable(PREFIX + "empty");

    /**
     * A single row in the picker.
     *
     * @param companyId   the id passed back through {@code onSelect}
     * @param displayName label shown on the row
     * @param description hover-tooltip body (may be empty)
     * @param enabled     when {@code false} the row is grayed out and non-clickable
     */
    public record CompanyOption(int companyId, String displayName, String description, boolean enabled) {}

    protected final List<CompanyOption> options;
    protected final IntConsumer onSelect;
    protected final Runnable onCancel;

    private Label titleLabel;
    private CloseButton closeButton;
    private ListView listView;
    private Label emptyLabel;

    public CompanySelectionScreen(Component title, List<CompanyOption> options,
                                  IntConsumer onSelect, Runnable onCancel) {
        this(title, null, options, onSelect, onCancel);
    }

    public CompanySelectionScreen(Component title, Screen parent, List<CompanyOption> options,
                                  IntConsumer onSelect, Runnable onCancel) {
        super(title, parent);
        this.options = options;
        this.onSelect = onSelect != null ? onSelect : id -> {};
        this.onCancel = onCancel != null ? onCancel : () -> {};
        setupUi(title);
    }

    private void setupUi(Component title) {
        titleLabel = new Label(title.getString());
        titleLabel.setAlignment(GuiElement.Alignment.CENTER);

        closeButton = new CloseButton(this::triggerCancel);

        listView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 2;
        layout.padding = 2;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        listView.setLayout(layout);

        for (CompanyOption option : options) {
            final int id = option.companyId();
            listView.addChild(new CompanySelectionButton(option, () -> onRowClicked(id)));
        }

        emptyLabel = new Label(DEFAULT_EMPTY.getString());
        emptyLabel.setAlignment(GuiElement.Alignment.CENTER);
        emptyLabel.setEnabled(options.isEmpty());

        addElement(titleLabel);
        addElement(closeButton);
        addElement(listView);
        addElement(emptyLabel);
    }

    /** Called when a row is clicked. Subclasses may override to inject pre/post logic. */
    protected void onRowClicked(int companyId) {
        onSelect.accept(companyId);
        onClose();
    }

    private void triggerCancel() {
        onCancel.run();
        onClose();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int padding = 5;
        int width = getWidth() - 2 * padding;
        if (titleLabel == null) return;

        titleLabel.setBounds(padding, padding, width - 25, 20);
        closeButton.setBounds(getWidth() - 20 - padding, padding, 20, 20);

        int listTop = padding + 30;
        int listWidth = Math.min(width, 260);
        int listX = (getWidth() - listWidth) / 2;
        int listHeight = Math.max(0, getHeight() - listTop - padding);
        listView.setBounds(listX, listTop, listWidth, listHeight);

        emptyLabel.setBounds(padding, listTop + 20, width, 20);
    }

    @Override public boolean isPauseScreen() { return false; }
}
