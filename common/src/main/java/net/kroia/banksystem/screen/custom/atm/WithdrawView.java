package net.kroia.banksystem.screen.custom.atm;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.WithdrawMoneyPacket;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.IntSupplier;

/**
 * The pre-Task-#39 ATM withdraw UI, extracted into its own panel so
 * {@link ConverterView} can live beside it in the tabbed screen.
 *
 * <p>Owns the balance view, the denomination grid, the receive button, and the
 * per-row {@code maxAffordable = balance / worth} gray-out enforcement introduced
 * by Task #39 acceptance criterion C.
 *
 * <p>The screen-level {@code selectAccountButton} is NOT owned here (Task #39 moved
 * it to the {@link net.kroia.banksystem.screen.custom.ATMScreen} level so the
 * converter's deposit-to-bank flow can share it).
 */
public class WithdrawView extends BankSystemGuiElement {

    public static final String COMPONENT_STR_START = "gui." + BankSystemMod.MOD_ID + ".atm_screen.";
    public static final Component RECEIVE_BUTTON_TEXT = Component.translatable(COMPONENT_STR_START + "receive_button");
    public static final String INSUFFICIENT_BALANCE_KEY = COMPONENT_STR_START + "insufficient_balance";
    public static final String TOOLTIP_RECEIVE_KEY = COMPONENT_STR_START + "tooltip.receive";

    private final Frame rootElement;
    private final BalanceView balanceView;
    private final ListView moneyListView;
    private final ArrayList<MoneyElement> moneyElements = new ArrayList<>();
    private final Button receiveButton;

    private final IntSupplier selectedAccountNumberSupplier;

    private long currentBalance = 0;

    public WithdrawView(IntSupplier selectedAccountNumberSupplier) {
        super();
        this.selectedAccountNumberSupplier = selectedAccountNumberSupplier;

        rootElement = new Frame();
        addChild(rootElement);

        LayoutGrid layout = new LayoutGrid();
        layout.stretchX = true;
        layout.columns = 2;
        layout.padding = 2;
        layout.spacing = 2;
        moneyListView = new VerticalListView();
        moneyListView.setLayout(layout);

        balanceView = new BalanceView();
        balanceView.setHeight(20);

        ArrayList<ItemStack> moneyItems = BankSystemItems.getMoneyItems();
        for (ItemStack moneyItem : moneyItems) {
            MoneyElement moneyElement = new MoneyElement(moneyItem, this::onRequestedAmountChanged);
            moneyElements.add(moneyElement);
            moneyListView.addChild(moneyElement);
        }

        receiveButton = new Button(RECEIVE_BUTTON_TEXT.getString(), this::onReceiveButtonPressed);
        receiveButton.setHeight(20);
        // Bottom-of-screen button — tooltip renders above the cursor.
        final String receiveTooltip = Component.translatable(TOOLTIP_RECEIVE_KEY).getString();
        receiveButton.setHoverTooltipSupplier(() -> receiveTooltip);
        receiveButton.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.BOTTOM);

        rootElement.addChild(balanceView);
        rootElement.addChild(moneyListView);
        rootElement.addChild(receiveButton);
    }

    @Override
    protected void render() { }

    @Override
    protected void layoutChanged() {
        int padding = 5;
        int width = getWidth();
        int height = getHeight();

        rootElement.setBounds(0, 0, width, height);

        int innerPadding = 5;
        int innerWidth = rootElement.getWidth() - 2 * innerPadding;
        int innerHeight = rootElement.getHeight() - 2 * innerPadding;

        balanceView.setBounds(innerPadding, innerPadding, innerWidth, 20);

        int receiveButtonWidth = rootElement.getWidth() / 2;
        receiveButton.setBounds((rootElement.getWidth() - receiveButtonWidth) / 2,
                innerHeight + innerPadding - 20, receiveButtonWidth, 20);

        moneyListView.setBounds(innerPadding, balanceView.getBottom() + innerPadding,
                innerWidth, receiveButton.getTop() - balanceView.getBottom() - innerPadding * 2);
    }

    /**
     * Push the current bank balance for the selected account. Also recomputes the
     * per-denomination {@code maxAffordable} cap and applies the gray-out contract
     * from Task #39 acceptance criterion C.
     */
    public void setBalance(long balance) {
        this.currentBalance = balance;
        balanceView.updateBalance(balance);
        recomputeMaxAffordablePerRow(balance);
        calculateSum();
    }

    public long getBalance() {
        return currentBalance;
    }

    private void recomputeMaxAffordablePerRow(long availableBalance) {
        String insufficientBalanceMsg = Component.translatable(INSUFFICIENT_BALANCE_KEY).getString();
        for (MoneyElement moneyElement : moneyElements) {
            ItemStack itemStack = moneyElement.getItemStack();
            if (!(itemStack.getItem() instanceof MoneyItem moneyItem))
                continue;
            long worth = moneyItem.worth();
            long maxAffordable = (worth <= 0) ? 0 : (availableBalance / worth);
            if (maxAffordable <= 0) {
                moneyElement.setMaxAffordable(0);
                moneyElement.setDisabledWithReason(insufficientBalanceMsg);
            } else {
                moneyElement.setEnabledRow();
                moneyElement.setMaxAffordable(maxAffordable);
            }
        }
    }

    private void onRequestedAmountChanged(MoneyElement moneyElement) {
        calculateSum();
    }

    private long calculateSum() {
        long sum = 0;
        for (MoneyElement moneyElement : moneyElements) {
            long amount = moneyElement.getAmount();
            ItemStack itemStack = moneyElement.getItemStack();
            if (!(itemStack.getItem() instanceof MoneyItem moneyItem))
                continue;
            try {
                amount = Math.multiplyExact(amount, moneyItem.worth());
                sum = Math.addExact(sum, amount);
            } catch (ArithmeticException e) {
                sum = Long.MAX_VALUE;
                break;
            }
        }
        balanceView.updateSum(sum);
        balanceView.enableWarning(currentBalance < sum);
        return sum;
    }

    private void onReceiveButtonPressed() {
        long sum = calculateSum();
        if (sum <= 0 || sum > currentBalance) {
            Player player = Minecraft.getInstance().player;
            if (player != null && sum > currentBalance) {
                String text = BankSystemTextMessages.getATMNotEnoughBalance(sum);
                player.sendSystemMessage(Component.translatable(text));
            }
            return;
        }

        HashMap<ItemID, Long> requestedBankNoteIDs = new HashMap<>();
        for (MoneyElement moneyElement : moneyElements) {
            long amount = moneyElement.getAmount();
            if (amount > 0) {
                ItemID itemID = ItemID.of(moneyElement.getItemStack());
                requestedBankNoteIDs.put(itemID, amount);
            }
        }
        WithdrawMoneyPacket.sendPacket(requestedBankNoteIDs, selectedAccountNumberSupplier.getAsInt());
    }
}
