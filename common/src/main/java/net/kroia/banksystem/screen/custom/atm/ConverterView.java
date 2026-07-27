package net.kroia.banksystem.screen.custom.atm;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.ConverterCommitToBankPacket;
import net.kroia.banksystem.networking.entity.ConverterDepositPacket;
import net.kroia.banksystem.networking.entity.ConverterDropAllPacket;
import net.kroia.banksystem.networking.entity.ConverterWithdrawPacket;
import net.kroia.banksystem.networking.entity.GetConverterCachePacket;
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

/**
 * Task #39 — Money Converter tab. Sits beside {@link WithdrawView} in the tabbed
 * ATM screen and holds a purely client-facing view of the player's server-side
 * conversion cache.
 *
 * <h3>Flow</h3>
 * <ol>
 *     <li>Player clicks <b>Deposit money</b> → all {@link MoneyItem} stacks in the
 *         inventory are enumerated and sent as
 *         {@link ConverterDepositPacket#sendPacket(HashMap)}. The server removes
 *         them and sums them into the per-player cache.</li>
 *     <li>Player picks a combination of denominations (rows use the same
 *         {@link MoneyElement} as the withdraw tab), then clicks <b>Withdraw</b> →
 *         {@link ConverterWithdrawPacket#sendPacket(HashMap)} dispenses the items and
 *         decrements the cache. Bank untouched.</li>
 *     <li>Alternatively, <b>Drop remainder</b> auto-splits the whole cache and drops
 *         it at the player, and <b>Deposit to bank</b> commits to the account
 *         currently chosen in the shared top selector via
 *         {@link ConverterCommitToBankPacket#sendPacket(int)}. Server enforces the
 *         DEPOSIT permission; a chat nudge fires when no account is selected.</li>
 * </ol>
 *
 * <h3>Cache balance sync</h3>
 * The client polls {@link GetConverterCachePacket#sendRequest()} on the ATM
 * screen's 1 Hz tick. The returned {@code CompletableFuture} resolves with
 * the server-authoritative cache balance and writes it into
 * {@link ClientCache#set(long)}. The view reads {@link ClientCache#get()} on
 * each tick and diffs against {@link #lastKnownCache} to skip redundant
 * relayout.
 */
public class ConverterView extends BankSystemGuiElement {

    public static final String COMPONENT_STR_START = "gui." + BankSystemMod.MOD_ID + ".atm_screen.";
    public static final String INSUFFICIENT_BALANCE_KEY = COMPONENT_STR_START + "insufficient_balance";
    public static final String DEPOSIT_BUTTON_KEY = COMPONENT_STR_START + "converter_deposit_button";
    public static final String WITHDRAW_BUTTON_KEY = COMPONENT_STR_START + "receive_button";
    public static final String DROP_REMAINDER_BUTTON_KEY = COMPONENT_STR_START + "converter_drop_remainder_button";
    public static final String DEPOSIT_TO_BANK_BUTTON_KEY = COMPONENT_STR_START + "converter_deposit_to_bank_button";
    public static final String NO_ACCOUNT_SELECTED_KEY = COMPONENT_STR_START + "no_account_selected";

    // Tooltip keys (backend agent adds the strings; see task instructions).
    public static final String TOOLTIP_DEPOSIT_KEY           = COMPONENT_STR_START + "tooltip.converter_deposit";
    public static final String TOOLTIP_WITHDRAW_KEY          = COMPONENT_STR_START + "tooltip.converter_withdraw";
    public static final String TOOLTIP_DROP_REMAINDER_KEY    = COMPONENT_STR_START + "tooltip.converter_drop_remainder";
    public static final String TOOLTIP_DEPOSIT_TO_BANK_KEY   = COMPONENT_STR_START + "tooltip.converter_deposit_to_bank";

    /**
     * Client-side holder for the server-authoritative cache balance. The backend's
     * response to {@link GetConverterCachePacket} writes here; the view reads on the
     * 1 Hz tick refresh driven by the enclosing {@code ATMScreen}. Simple static
     * holder keeps the packet handler decoupled from the screen instance and safe
     * to fire even when the screen is closed (writes then just wait for the next
     * screen open — the cache is server-authoritative anyway).
     */
    public static final class ClientCache {
        private static volatile long currentCache = 0;

        public static void set(long value) {
            currentCache = Math.max(0, value);
        }

        public static long get() {
            return currentCache;
        }

        public static void clear() {
            currentCache = 0;
        }
    }

    private final Frame rootElement;
    private final BalanceView balanceView;
    private final ListView moneyListView;
    private final ArrayList<MoneyElement> moneyElements = new ArrayList<>();

    private final Button depositMoneyButton;
    private final Button withdrawButton;
    private final Button dropRemainderButton;
    private final Button depositToBankButton;

    private final java.util.function.IntSupplier selectedAccountNumberSupplier;

    /** Cached last known cache balance to detect changes across ticks. */
    private long lastKnownCache = -1;

    public ConverterView(java.util.function.IntSupplier selectedAccountNumberSupplier) {
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

        for (ItemStack moneyItem : BankSystemItems.getMoneyItems()) {
            MoneyElement moneyElement = new MoneyElement(moneyItem, this::onRequestedAmountChanged);
            moneyElements.add(moneyElement);
            moneyListView.addChild(moneyElement);
        }

        depositMoneyButton = new Button(Component.translatable(DEPOSIT_BUTTON_KEY).getString(), this::onDepositMoneyPressed);
        depositMoneyButton.setHeight(20);
        withdrawButton = new Button(Component.translatable(WITHDRAW_BUTTON_KEY).getString(), this::onWithdrawPressed);
        withdrawButton.setHeight(20);
        dropRemainderButton = new Button(Component.translatable(DROP_REMAINDER_BUTTON_KEY).getString(), this::onDropRemainderPressed);
        dropRemainderButton.setHeight(20);
        depositToBankButton = new Button(Component.translatable(DEPOSIT_TO_BANK_BUTTON_KEY).getString(), this::onDepositToBankPressed);
        depositToBankButton.setHeight(20);

        // Bottom-of-screen buttons — tooltip renders above the cursor.
        applyBottomTooltip(depositMoneyButton, TOOLTIP_DEPOSIT_KEY);
        applyBottomTooltip(withdrawButton, TOOLTIP_WITHDRAW_KEY);
        applyBottomTooltip(dropRemainderButton, TOOLTIP_DROP_REMAINDER_KEY);
        applyBottomTooltip(depositToBankButton, TOOLTIP_DEPOSIT_TO_BANK_KEY);

        rootElement.addChild(balanceView);
        rootElement.addChild(moneyListView);
        rootElement.addChild(depositMoneyButton);
        rootElement.addChild(withdrawButton);
        rootElement.addChild(dropRemainderButton);
        rootElement.addChild(depositToBankButton);

        refreshFromCache();
    }

    @Override
    protected void render() { }

    @Override
    protected void layoutChanged() {
        int width = getWidth();
        int height = getHeight();

        rootElement.setBounds(0, 0, width, height);

        int innerPadding = 5;
        int innerWidth = rootElement.getWidth() - 2 * innerPadding;

        balanceView.setBounds(innerPadding, innerPadding, innerWidth, 20);

        // Bottom: two rows of two buttons each (4 buttons total).
        int buttonRowHeight = 20;
        int buttonSpacing = 4;
        int bottomBlockHeight = buttonRowHeight * 2 + buttonSpacing;
        int bottomBlockTop = rootElement.getHeight() - innerPadding - bottomBlockHeight;

        int buttonWidth = (innerWidth - buttonSpacing) / 2;
        depositMoneyButton.setBounds(innerPadding, bottomBlockTop, buttonWidth, buttonRowHeight);
        withdrawButton.setBounds(depositMoneyButton.getRight() + buttonSpacing, bottomBlockTop, innerWidth - buttonWidth - buttonSpacing, buttonRowHeight);
        int secondRowTop = depositMoneyButton.getBottom() + buttonSpacing;
        dropRemainderButton.setBounds(innerPadding, secondRowTop, buttonWidth, buttonRowHeight);
        depositToBankButton.setBounds(dropRemainderButton.getRight() + buttonSpacing, secondRowTop, innerWidth - buttonWidth - buttonSpacing, buttonRowHeight);

        moneyListView.setBounds(innerPadding, balanceView.getBottom() + innerPadding,
                innerWidth, bottomBlockTop - balanceView.getBottom() - innerPadding * 2);
    }

    /**
     * Called on the ATM screen's 1 Hz tick. Refreshes the balance view from the
     * client-side cache mirror and re-applies the per-row {@code maxAffordable}
     * gray-out contract.
     */
    public void tick() {
        refreshCacheFromServer();
        refreshFromCache();
    }

    /**
     * Fire a one-shot cache poll and update {@link ClientCache} when the response
     * lands. Chained after every state-changing action so the display doesn't wait
     * for the next 1 Hz tick to catch up. TCP packet ordering guarantees the server
     * processes the state-change before the poll, so the response reflects the
     * post-change balance.
     */
    private void refreshCacheFromServer() {
        try {
            GetConverterCachePacket.sendRequest().thenAccept(ClientCache::set);
        } catch (Throwable t) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[ConverterView] Failed to send GetConverterCachePacket: " + t.getMessage());
        }
    }

    /**
     * Bottom-of-screen tooltip helper — tooltip renders above the cursor. Used by
     * the four action buttons at the bottom of the Converter tab.
     */
    private void applyBottomTooltip(GuiElement el, String translationKey) {
        final String text = Component.translatable(translationKey).getString();
        el.setHoverTooltipSupplier(() -> text);
        el.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.BOTTOM);
    }

    /**
     * @return the current cache balance from the client-side mirror
     */
    public long getCacheBalance() {
        return ClientCache.get();
    }

    private void refreshFromCache() {
        long cache = ClientCache.get();
        if (cache == lastKnownCache)
            return;
        lastKnownCache = cache;
        balanceView.updateBalance(cache);
        recomputeMaxAffordablePerRow(cache);
        calculateSum();
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
        balanceView.enableWarning(lastKnownCache < sum);
        return sum;
    }

    /**
     * Client-side scan of the player's inventory: sums {@link MoneyItem} stacks per
     * {@link ItemID} and sends the batch as a single {@link ConverterDepositPacket}.
     * Per Task #39 open-question 1, v1 is single-button "deposit ALL money" — the
     * player can control what gets deposited by keeping money in a hotbar slot they
     * don't want emptied. Server does the physical removal.
     */
    private void onDepositMoneyPressed() {
        Player player = Minecraft.getInstance().player;
        if (player == null)
            return;

        HashMap<ItemID, Long> moneyItems = new HashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty())
                continue;
            if (!MoneyItem.isMoney(stack))
                continue;
            ItemID itemID = ItemID.of(stack);
            long prior = moneyItems.getOrDefault(itemID, 0L);
            long updated;
            try {
                updated = Math.addExact(prior, (long) stack.getCount());
            } catch (ArithmeticException e) {
                updated = Long.MAX_VALUE;
            }
            moneyItems.put(itemID, updated);
        }

        if (moneyItems.isEmpty()) {
            // Nothing to deposit — surface a friendly no-op instead of a silent click.
            player.sendSystemMessage(Component.literal("No money items found in inventory."));
            return;
        }
        try {
            ConverterDepositPacket.sendPacket(moneyItems);
        } catch (Throwable t) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[ConverterView] Failed to send ConverterDepositPacket: " + t.getMessage());
        }
        // Immediate poll — TCP ordering ensures the server processes the deposit
        // before this poll, so the response reflects the new cache balance.
        refreshCacheFromServer();
    }

    private void onWithdrawPressed() {
        long sum = calculateSum();
        long cache = ClientCache.get();
        if (sum <= 0 || sum > cache) {
            Player player = Minecraft.getInstance().player;
            if (player != null && sum > cache) {
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
        try {
            ConverterWithdrawPacket.sendPacket(requestedBankNoteIDs);
        } catch (Throwable t) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[ConverterView] Failed to send ConverterWithdrawPacket: " + t.getMessage());
        }
        refreshCacheFromServer();
    }

    private void onDropRemainderPressed() {
        try {
            ConverterDropAllPacket.sendPacket();
        } catch (Throwable t) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[ConverterView] Failed to send ConverterDropAllPacket: " + t.getMessage());
        }
        refreshCacheFromServer();
    }

    /**
     * Commits the cache to the currently-selected account (the shared top selector).
     * If nothing is selected (account 0), the player is nudged in chat instead of
     * bouncing them through the account picker. Task #39 polish-round item 3.
     */
    private void onDepositToBankPressed() {
        Player player = Minecraft.getInstance().player;
        if (player == null)
            return;
        int accountNumber = selectedAccountNumberSupplier.getAsInt();
        if (accountNumber <= 0) {
            player.sendSystemMessage(Component.translatable(NO_ACCOUNT_SELECTED_KEY));
            return;
        }
        try {
            ConverterCommitToBankPacket.sendPacket(accountNumber);
        } catch (Throwable t) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[ConverterView] Failed to send ConverterCommitToBankPacket: " + t.getMessage());
        }
        refreshCacheFromServer();
    }
}
