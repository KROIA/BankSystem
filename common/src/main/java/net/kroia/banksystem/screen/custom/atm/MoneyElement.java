package net.kroia.banksystem.screen.custom.atm;

import net.kroia.banksystem.screen.uiElements.AmountButtonGroup;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.layout.LayoutHorizontal;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Denomination row extracted from the pre-Task-#39 {@code ATMScreen}. Shared
 * between {@link WithdrawView} (bank-balance driven) and {@link ConverterView}
 * (cache-balance driven). Behavior is identical to the original inner class,
 * plus:
 * <ul>
 *     <li>{@link #setMaxAffordable(long)} — clamps user input to the balance-derived cap;
 *         mirrors the {@code maxAffordable = availableBalance / worth} rule from the Task
 *         #39 spec (both tabs must gray out rows the balance cannot afford).</li>
 *     <li>{@link #setDisabledWithReason(String)} / {@link #setEnabledRow()} — gray-out
 *         pattern mirroring
 *         {@link net.kroia.banksystem.screen.custom.BankAccountSelectionScreen.AccountButton#setDisabledWithReason(String)}
 *         and {@link net.kroia.banksystem.screen.uiElements.BankUserWidget#setRemoveButtonEnabled(boolean)}.
 *         Row stays visible + hover-tooltipped; buttons hide (no {@code setClickable} exists
 *         on the amount widgets) and the text is dimmed to signal unavailability.</li>
 * </ul>
 */
public class MoneyElement extends BankSystemGuiElement {

    private static final int DIMMED_TEXT_COLOR = 0xFF808080;

    private final ItemStack itemStack;
    private final ItemView itemView;
    private final TextBox amountTextBox;
    private final AmountButtonGroup addAmountButtonGroup;
    private final Consumer<MoneyElement> onAmountChangedCallback;

    private final int defaultTextColor;

    private long maxAffordable = Long.MAX_VALUE;
    private boolean disabled = false;

    public MoneyElement(ItemStack moneyItem, Consumer<MoneyElement> onAmountChanged)
    {
        super();
        this.itemStack = moneyItem;
        this.onAmountChangedCallback = onAmountChanged;

        itemView = new ItemView(this.itemStack);
        amountTextBox = new TextBox();
        amountTextBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 100, 0));
        defaultTextColor = amountTextBox.getTextColor();

        addAmountButtonGroup = AmountButtonGroup.create(new long[]{1L, 10L, 32L, 64L},
                this::addAmountFromButton,
                () -> addAmountFromButton(-getAmount()),
                this::getAmount);
        addChild(addAmountButtonGroup);

        amountTextBox.setOnTextChanged((text -> {
            clampToMaxAffordable();
            addAmountButtonGroup.updateButtons();
            notifyAmountChanged();
        }));

        LayoutHorizontal layout = new LayoutHorizontal();
        this.setLayout(layout);

        addChild(itemView);
        addChild(amountTextBox);

        setAmount(0);
        setHeight(addAmountButtonGroup.getHeight());
    }

    @Override
    protected void render() { }

    @Override
    protected void layoutChanged() {
        itemView.setBounds(0, 0, this.getHeight(), this.getHeight());
        amountTextBox.setBounds(itemView.getWidth(), 0, this.getWidth()/2 - itemView.getWidth(), this.getHeight());
        addAmountButtonGroup.setBounds(amountTextBox.getRight(), amountTextBox.getTop(), this.getWidth() - amountTextBox.getRight(), this.getHeight());
    }

    public ItemStack getItemStack() { return itemStack; }

    public long getAmount() {
        String text = amountTextBox.getText();
        if(text.isEmpty())
            return 0;
        long value = 0;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            if(BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[MoneyElement] Invalid amount entered: " + text + ". Returning 0.");
        }
        if(value < 0)
            value = 0;
        return value;
    }

    public void setAmount(long amount) {
        setAmountInternal(amount);
        addAmountButtonGroup.updateButtons();
    }

    private void setAmountInternal(long amount) {
        if(amount < 0)
            amount = 0;
        if(amount > maxAffordable)
            amount = maxAffordable;
        // TextBox#setText bypasses regex and does NOT fire the setOnTextChanged callback,
        // so setAmountInternal is safe to call from inside the callback without recursion.
        amountTextBox.setText(String.valueOf(amount));
    }

    private void clampToMaxAffordable() {
        if(maxAffordable == Long.MAX_VALUE)
            return;
        long current = getAmount();
        if(current > maxAffordable) {
            amountTextBox.setText(String.valueOf(maxAffordable));
        }
    }

    private void addAmountFromButton(long delta) {
        long newAmount = Math.max(0, getAmount() + delta);
        setAmountInternal(newAmount);
        notifyAmountChanged();
    }

    private void notifyAmountChanged() {
        if(onAmountChangedCallback != null)
            onAmountChangedCallback.accept(this);
    }

    /**
     * Cap the user-facing amount at {@code max}. Clamps the current value if it
     * exceeds the new cap. Drives the disabled visual when {@code max == 0}.
     *
     * @param max the maximum amount the user may request for this denomination
     */
    public void setMaxAffordable(long max) {
        if(max < 0)
            max = 0;
        this.maxAffordable = max;
        if(getAmount() > max) {
            setAmount(max);
        } else {
            addAmountButtonGroup.updateButtons();
        }
    }

    public long getMaxAffordable() {
        return maxAffordable;
    }

    /**
     * Gray-out contract mirroring
     * {@link net.kroia.banksystem.screen.custom.BankAccountSelectionScreen.AccountButton#setDisabledWithReason(String)}.
     * The row stays visible with a hover tooltip; buttons hide and the amount label is dimmed
     * to signal unavailability. Any prior amount is zeroed.
     *
     * @param reason tooltip text explaining the disabled state (e.g. "Insufficient balance")
     */
    public void setDisabledWithReason(String reason) {
        setAmount(0);
        if(disabled) {
            // Even if already disabled, keep the tooltip up-to-date.
            setHoverTooltipSupplier(() -> reason);
            return;
        }
        disabled = true;
        amountTextBox.setTextColor(DIMMED_TEXT_COLOR);
        addAmountButtonGroup.setEnabled(false);
        setHoverTooltipSupplier(() -> reason);
    }

    /**
     * Re-enables the row after {@link #setDisabledWithReason(String)}. No-op if already enabled.
     */
    public void setEnabledRow() {
        if(!disabled)
            return;
        disabled = false;
        amountTextBox.setTextColor(defaultTextColor);
        addAmountButtonGroup.setEnabled(true);
        setHoverTooltipSupplier(() -> "");
    }

    public boolean isDisabledRow() {
        return disabled;
    }
}
