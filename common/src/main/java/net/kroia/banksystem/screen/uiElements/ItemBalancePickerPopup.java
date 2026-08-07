package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Spec B.3 (v2.0.8) — payout currency picker. Lists "Money (default)" plus every
 * item the company's bank account holds a non-zero balance in (with quantity).
 * Emits the chosen currency's ItemID short value ({@link PayoutSchedule#MONEY_CURRENCY}
 * for money) back to the caller.
 */
public class ItemBalancePickerPopup extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".item_balance_picker.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final Component MONEY_OPTION = Component.translatable(PREFIX + "money_option");
    private static final Component EMPTY = Component.translatable(PREFIX + "empty");
    private static final Component LOADING = Component.translatable(PREFIX + "loading");
    private static final Component CANCEL = Component.translatable(PREFIX + "cancel");

    /** One selectable currency row — item icon + name + held quantity. */
    private static class CurrencyRow extends Button {
        private final ItemView icon;

        CurrencyRow(ItemStack stack, String text, Runnable onClick) {
            super(text, onClick);
            icon = new ItemView(stack);
            icon.setShowCount(false);
            addChild(icon);
            setHeight(20);
        }

        @Override
        protected void layoutChanged() {
            super.layoutChanged();
            icon.setBounds(2, (getHeight() - 16) / 2, 16, 16);
        }
    }

    private final GuiScreen parent;
    private final Frame frame = new Frame();
    private final Label titleLabel;
    private final VerticalListView list;
    private final Button cancelButton;

    private int frameWidth = 300;
    private int frameHeight = 240;

    public ItemBalancePickerPopup(GuiScreen parent, int companyId, UUID caller,
                                  Consumer<Short> onPick) {
        super(TITLE);
        this.parent = parent;
        titleLabel = new Label(TITLE.getString());
        list = new VerticalListView();
        LayoutGrid l = new LayoutGrid();
        l.columns = 1; l.rows = 0; l.spacing = 2; l.padding = 2;
        l.stretchX = true; l.stretchY = false;
        l.alignment = GuiElement.Alignment.TOP;
        list.setLayout(l);
        // BUG 1 fix — deferred swaps; direct setScreen inside a click callback CMEs.
        cancelButton = new Button(CANCEL.getString(), () -> switchScreen(parent));

        addElement(frame);
        frame.addChild(titleLabel);
        frame.addChild(list);
        frame.addChild(cancelButton);

        list.addChild(new Label(LOADING.getString()));
        AsyncCompanyManager.listAccountItemBalancesAsync(companyId, caller).thenAccept(out ->
                Minecraft.getInstance().execute(() -> {
                    list.removeChilds();
                    // Bug A fix (v2.0.8) — Money row is always first, rendered as a proper
                    // CurrencyRow (fixed height) with the money icon and formatted balance.
                    long moneyBalance = out != null ? out.moneyBalance() : 0L;
                    ItemStack moneyStack = net.kroia.banksystem.minecraft.item.BankSystemItems
                            .MONEY.get().getDefaultInstance();
                    String moneyText = "     " + MONEY_OPTION.getString()
                            + "  x" + net.kroia.banksystem.util.MoneyFormat.format(moneyBalance);
                    list.addChild(new CurrencyRow(moneyStack, moneyText, () -> {
                        switchScreen(parent);
                        onPick.accept(PayoutSchedule.MONEY_CURRENCY);
                    }));
                    if (out == null || out.resultCode() != AsyncCompanyManager.CODE_OK
                            || out.items().isEmpty()) {
                        list.addChild(new Label(EMPTY.getString()));
                    } else {
                        for (AsyncCompanyManager.ItemBalanceEntry entry : out.items()) {
                            short itemShort = entry.itemShort();
                            ItemStack stack = ItemIDManager.getItemStack(new ItemID(itemShort));
                            String name = stack.isEmpty() ? ("#" + itemShort)
                                    : stack.getHoverName().getString();
                            // Bug A fix — scaled amount (fixpoint /100) instead of raw.
                            String text = "     " + name + "  x"
                                    + net.kroia.banksystem.util.MoneyFormat.format(entry.balance());
                            list.addChild(new CurrencyRow(stack, text, () -> {
                                switchScreen(parent);
                                onPick.accept(itemShort);
                            }));
                        }
                    }
                    updateLayout(getGui());
                }));
    }

    @Override
    public void onClose() {
        // BUG 1 fix — deferred swap; direct setScreen inside a click callback CMEs.
        switchScreen(parent);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();
        frame.setBounds((width - frameWidth) / 2, (height - frameHeight) / 2, frameWidth, frameHeight);
        int p = 5;
        titleLabel.setBounds(p, p, frame.getWidth() - 2 * p, 15);
        cancelButton.setBounds(frame.getWidth() - p - 80, frame.getHeight() - p - 20, 80, 20);
        list.setBounds(p, titleLabel.getBottom() + 2, frame.getWidth() - 2 * p,
                frame.getHeight() - titleLabel.getBottom() - 32);
    }
}
