package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation; // mc<=1.21.10
//import net.minecraft.resources.Identifier; // mc>=1.21.11
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class WithdrawMoneyPacket extends BankSystemNetworkPacket {

    public static final Type<WithdrawMoneyPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "withdraw_money_packet")); // mc<=1.21.10
    //public static final Type<WithdrawMoneyPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BankSystemMod.MOD_ID, "withdraw_money_packet")); // mc>=1.21.11

    public static final StreamCodec<RegistryFriendlyByteBuf, WithdrawMoneyPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap::new), p -> p.requestedBankNoteIDs,
            ByteBufCodecs.INT, p -> p.currentSelectedAccountNumber,
            WithdrawMoneyPacket::new
    );

    // Contains the item ID and the requested amount of the bank notes
    HashMap<ItemID, Long> requestedBankNoteIDs;// = new HashMap<>();
    int currentSelectedAccountNumber;

    public WithdrawMoneyPacket(HashMap<ItemID, Long> requestedBankNoteIDs, int currentSelectedAccountNumber) {
        super();
        this.requestedBankNoteIDs = requestedBankNoteIDs;
        this.currentSelectedAccountNumber = currentSelectedAccountNumber;
    }

    public static void sendPacket(HashMap<ItemID, Long> requestedBankNoteIDs, int currentSelectedAccountNumber) {
        WithdrawMoneyPacket packet = new WithdrawMoneyPacket(requestedBankNoteIDs, currentSelectedAccountNumber);
        packet.sendToServer();
    }


    @Override
    protected void handleOnServer(ServerPlayer sender) {
        handle(sender.getUUID(), null);
        /*ISyncServerBankManager bankManager = getSyncBankManager();
        ISyncServerBankAccount account = bankManager.getBankAccount(currentSelectedAccountNumber);
        if(account == null)
            return;

        if(!account.hasPermission(sender.getUUID(), BankPermission.WITHDRAW))
        {
            ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getNoBankPermissionMessage(account.getAccountName(), BankPermission.WITHDRAW));
            return;
        }

        ISyncServerBank moneyBank = account.getBank(MoneyItem.getItemID());
        if(moneyBank == null) {
            return; // No money bank found for the player
        }

        // Get a list of all needed bank notes
        HashMap<ItemID, MoneyItem> availableBankNotes = new HashMap<>();
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            ItemStack moneyItemStack = itemID.getStack().copy();
            try {
                if (moneyItemStack.getItem() instanceof MoneyItem moneyItem) {
                    availableBankNotes.put(itemID, moneyItem);
                }
            } catch (Exception e) {
                error("WithdrawMoneyPacket: Error setting stack size for item ID: " + itemID + "\n" + e.getMessage(), e);
            }
        }


        // Ckeck if the player has enough balance to withdraw the requested amounts
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            long requestedAmount = requestedBankNoteIDs.get(itemID);
            MoneyItem moneyItem = availableBankNotes.get(itemID);
            if(moneyItem == null)
            {
                error("WithdrawMoneyPacket: Invalid money item ID: " + itemID);
                continue;
            }
            long itemValue = moneyItem.worth();
            long totalValue = requestedAmount * itemValue;
            if (totalValue <= 0) {
                continue; // Skip invalid requests
            }


            if (!moneyBank.hasSufficientFunds(totalValue)) {
                ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getNotEnoughInAccountMessage(MoneyItem.getMoneyItemName(), sender.getName().getString()));
                continue;
            }

            // Withdraw the requested amount of banknotes
            if(moneyBank.withdraw(totalValue) == BankStatus.SUCCESS){
                int intAmount = (int) requestedAmount;
                if(requestedAmount > Integer.MAX_VALUE)
                {
                    while(requestedAmount > 0)
                    {
                        intAmount = (int) Math.min(requestedAmount, Integer.MAX_VALUE);
                        requestedAmount -= intAmount;
                        ItemStack moneyStack = new ItemStack(moneyItem, intAmount);
                        if(ServerPlayerUtilities.addToPlayerInventory(sender, moneyStack) > 0)
                        {
                            // Drop remaining
                            ItemUtilities.dropItemAtPlayer(sender, moneyStack);
                        }
                    }
                }
                else {
                    ItemStack moneyStack = new ItemStack(moneyItem, intAmount);
                    if(ServerPlayerUtilities.addToPlayerInventory(sender, moneyStack) > 0)
                    {
                        // Drop remaining
                        ItemUtilities.dropItemAtPlayer(sender, moneyStack);
                    }
                }
            }
        }*/

    }

    @Override
    protected void handleOnMaster(ForwardPacketContext context) {
        TransactionLedger.withOrigin(context.senderServerID, () -> { // BS-256
            handle(context.senderPlayerUUID, context.senderServerID);
            return null;
        });
    }
    public void handle(UUID player, @Nullable String slaveID)
    {
        ISyncServerBankManager bankManager = getSyncBankManager();
        if(bankManager == null)
            return;
        // The player UUID is asserted by the slave: an untrusted slave could withdraw from any account.
        if(slaveID != null && !slaveID.isEmpty() && !bankManager.isSlaveServerTrusted(slaveID))
        {
            warn("The slave server: '" + slaveID + "' tried to withdraw money, which is not allowed for an untrusted slave server!");
            ServerPlayerUtilities.printToClientConsole(player, "[BankSystem] Your server '" + slaveID + "' is not trusted by the bank master. "
                    + "Contact a bank admin to grant trust for cross-server bank operations.");
            return;
        }
        final int finalCurrentSelectedAccountNumber = currentSelectedAccountNumber;
        ISyncServerBankAccount account = bankManager.getBankAccount(finalCurrentSelectedAccountNumber);
        if(account == null)
            return;

        if(!account.hasPermission(player, BankPermission.WITHDRAW))
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNoBankPermissionMessage(account.getAccountName(), BankPermission.WITHDRAW));
            return;
        }
        if (net.kroia.banksystem.banking.ledger.AccountFreeze.blocks(finalCurrentSelectedAccountNumber, LedgerType.WITHDRAW, player)) // Task #117
            return;

        ISyncServerBank moneyBank = account.getBank(MoneyItem.getItemID());
        if(moneyBank == null) {
            return; // No money bank found for the player
        }

        // Get a list of all needed bank notes
        HashMap<ItemID, MoneyItem> availableBankNotes = new HashMap<>();
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            ItemStack moneyItemStack = itemID.getStack().copy();
            try {
                if (moneyItemStack.getItem() instanceof MoneyItem moneyItem) {
                    availableBankNotes.put(itemID, moneyItem);
                }
            } catch (Exception e) {
                error("WithdrawMoneyPacket: Error setting stack size for item ID: " + itemID + "\n" + e.getMessage(), e);
            }
        }


        Map<ItemID, Long> requestedBankNotes = new HashMap<>();
        // Task #125: a DROP to a slave is one transfer; its debit and refund rows carry the id as ref.
        final String transferId = slaveID == null ? null : net.kroia.banksystem.data.TransferJournal.newTransferId();
        // Ckeck if the player has enough balance to withdraw the requested amounts
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            long requestedAmount = requestedBankNoteIDs.get(itemID);
            MoneyItem moneyItem = availableBankNotes.get(itemID);
            if(moneyItem == null)
            {
                error("WithdrawMoneyPacket: Invalid money item ID: " + itemID);
                continue;
            }
            long itemValue = moneyItem.worth();
            if (requestedAmount <= 0 || itemValue <= 0)
                continue;
            if (requestedAmount > Long.MAX_VALUE / itemValue)
                continue;
            long totalValue = requestedAmount * itemValue;


            if (!moneyBank.hasSufficientFunds(totalValue)) {
                User user = bankManager.getUserByUUID(player);
                if(user == null) continue;
                String userName = user.getName();
                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNotEnoughInAccountMessage(MoneyItem.getMoneyItemName(), userName));
                continue;
            }

            // Withdraw the requested amount of banknotes
            if(TransactionLedger.withoutRawLog(() -> moneyBank.withdraw(totalValue)) == BankStatus.SUCCESS) // BS-254: row below
            {
                requestedBankNotes.put(itemID, requestedAmount);
                // Task #44 (v2.1.0) — Transaction Ledger. Money withdrawals are logged
                // against the base money ItemID (not the banknote denomination) so a
                // downstream reader sees the same currency identity regardless of which
                // denomination was chosen at the terminal. Amount is base-money units.
                // Task #109: note names the denomination so banknote flows can be traced.
                TransactionLedger.logBank(moneyBank, LedgerType.WITHDRAW, totalValue,
                        TransactionLedger.player(player), "banknote " + itemID.getName() + " x" + requestedAmount, transferId);
            }
        }



        if(slaveID == null)
        {
            Map<ItemID, Long> notDropped = DropItemsInPlayerInventoryRequest.dropItems(UtilitiesPlatform.getServer(), player, requestedBankNotes);
            putRemainingItemsBack(finalCurrentSelectedAccountNumber, player, notDropped, null);
        }
        else
        {
            dropRequestToSlave(slaveID, player, finalCurrentSelectedAccountNumber, requestedBankNotes, transferId);
        }
    }

    private void dropRequestToSlave(String slaveID, UUID player, int bankaccountNr, Map<ItemID, Long> items, String transferId)
    {
        DropItemsInPlayerInventoryRequest.sendToSlave(slaveID, player, items, false, transferId)
                .whenComplete((notDropped, ex) -> onDropResult(slaveID, player, bankaccountNr, items, transferId, notDropped, ex));
    }

    /**
     * Task #126 (B6): a known result refunds what was not dropped. An outcome that is still unknown after the
     * slave's status check is PARKED: no refund (the notes may be in the player's inventory), a TRANSFER_PARKED
     * suspicion flag for the admin instead. Public for the in-game test.
     */
    public void onDropResult(String slaveID, UUID player, int bankaccountNr, Map<ItemID, Long> items, String transferId,
                             @Nullable Map<ItemID, Long> notDropped, @Nullable Throwable ex)
    {
        if(ex == null) {
            TransactionLedger.withOrigin(slaveID, () -> { // BS-256: the refund row belongs to the slave's request
                putRemainingItemsBack(bankaccountNr, player, notDropped, transferId);
                return null;
            });
            return;
        }
        String details = "DROP to slave " + slaveID + " outcome unknown, not refunded: " + items;
        error("WithdrawMoneyPacket: " + details + " (transfer " + transferId + ")", ex);
        AdminAudit.raise(bankaccountNr, SuspicionChecks.TRANSFER_PARKED, 2, moneyValue(items), transferId, details);
    }

    private void putRemainingItemsBack(int bankaccountNr, UUID player, Map<ItemID, Long> items, @Nullable String transferId)
    {
        if(items.isEmpty())
            return;
        ISyncServerBankAccount redepositAccount = getSyncBankManager().getBankAccount(bankaccountNr);
        if(redepositAccount == null) {
            error("WithdrawMoneyPacket: Invalid bank account ID: " + bankaccountNr + " Items are lost now: "+items);
            return;
        }
        ISyncServerBank redepositMoneyBank = redepositAccount.getBank(MoneyItem.getItemID());
        if(redepositMoneyBank == null) {
            error("WithdrawMoneyPacket: no money bank account with ID: " + bankaccountNr+" Items are lost now: "+items);
            return; // No money bank found for the player
        }

        long toDepositMoney = moneyValue(items);
        if (toDepositMoney > 0 && TransactionLedger.withoutRawLog(() -> redepositMoneyBank.deposit(toDepositMoney)) == BankStatus.SUCCESS) {
            // Task #109: separate refund row; the WITHDRAW row above stays as written.
            TransactionLedger.logBank(redepositMoneyBank, LedgerType.WITHDRAW_REFUND, toDepositMoney,
                    TransactionLedger.player(player), "banknotes not handed out: " + items, transferId);
        }
    }

    /** Money value (raw units) of a banknote map; entries that would overflow are skipped. */
    private static long moneyValue(Map<ItemID, Long> items)
    {
        long total = 0;
        for(Map.Entry<ItemID, Long> entry : items.entrySet()) {
            if (entry.getKey().getStack().getItem() instanceof MoneyItem moneyItem) {
                long worth = moneyItem.worth();
                long count = entry.getValue();
                if (worth <= 0 || count <= 0)
                    continue;
                if (count > Long.MAX_VALUE / worth)
                    continue; // skip: multiplication would overflow
                long product = worth * count;
                if (total > Long.MAX_VALUE - product)
                    continue; // skip: running sum would overflow
                total += product;
            }
        }
        return total;
    }


    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


}
