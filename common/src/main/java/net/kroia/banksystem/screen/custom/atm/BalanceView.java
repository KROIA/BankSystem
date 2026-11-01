package net.kroia.banksystem.screen.custom.atm;

import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.layout.LayoutHorizontal;

/**
 * Balance + selection-sum row extracted from the pre-Task-#39 {@code ATMScreen}. The
 * left cell renders the balance (bank balance in {@link WithdrawView}, cache balance in
 * {@link ConverterView}); the right cell renders the currently-selected withdraw sum.
 * {@link #enableWarning(boolean)} tints the sum label red when the user has selected
 * more than the balance permits.
 */
class BalanceView extends BankSystemGuiElement {

    private final ItemView coinItemView;
    private final Label balanceLabel;
    private final Label sumLabel;

    private final int defaultTextColor;

    BalanceView() {
        super();
        coinItemView = new ItemView(BankSystemItems.MONEY.get().getDefaultInstance());
        balanceLabel = new Label();
        balanceLabel.setAlignment(Alignment.CENTER);
        sumLabel = new Label();
        sumLabel.setAlignment(Alignment.CENTER);

        setLayout(new LayoutHorizontal());

        defaultTextColor = sumLabel.getTextColor();

        addChild(coinItemView);
        addChild(balanceLabel);
        addChild(sumLabel);
    }

    @Override
    protected void render() { }

    @Override
    protected void layoutChanged() {
        coinItemView.setBounds(0, 0, this.getHeight(), this.getHeight());
        balanceLabel.setBounds(coinItemView.getWidth(), 0, (this.getWidth() - coinItemView.getWidth()) / 2, this.getHeight());
        sumLabel.setBounds(balanceLabel.getRight(), 0, balanceLabel.getWidth(), this.getHeight());
    }

    void updateBalance(long amount) {
        balanceLabel.setText(BankSystemTextMessages.getATMAvailableTextMessage(amount));
    }

    void updateSum(long amount) {
        sumLabel.setText(BankSystemTextMessages.getATMSumTextMessage(amount));
    }

    void enableWarning(boolean enabled) {
        sumLabel.setTextColor(enabled ? 0xFF0000 : defaultTextColor);
    }
}
