package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DepositItemsInBankRequest extends BankSystemGenericRequest<DepositItemsInBankRequest.InputData, DepositItemsInBankRequest.OutputData> {



    /**
     * @param ledgerTypeCode Task #109: {@link LedgerType} code written to the ledger for each
     *                       deposited item (e.g. CONVERTER_DEPOSIT); DEPOSIT by default. The master
     *                       checks it against {@link TransactionLedger#SLAVE_DEPOSIT_CODES}.
     * @param transferId     Task #125: the slave's transfer_id, written as the ledger rows' {@code ref};
     *                       last field on the wire, null from an older slave
     */
    public record InputData(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items, int ledgerTypeCode,
                            @Nullable String transferId)
    {
        public InputData(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items) {
            this(bankAccount, executor, items, LedgerType.DEPOSIT.code());
        }
        public InputData(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items, int ledgerTypeCode) {
            this(bankAccount, executor, items, ledgerTypeCode, null);
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, p->p.bankAccount,
                ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC), p->p.executor,
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                ByteBufCodecs.VAR_INT, p -> p.ledgerTypeCode,
                net.kroia.banksystem.data.TransferJournal.ID_CODEC, p -> p.transferId, // Task #125: keep last
                InputData::new
        );
    }
    public record OutputData(Map<ItemID, Long> items)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                OutputData::new
        );
    }

    /**
     * Deposits the requested amount of items onto the bank account
     * If the executor is set, permission checks are made to check if the player has access to the bank account
     * @param bankAccount target bank account
     * @param executor the player who has created this request
     * @param items list of items to deposit
     * @return a future containing the items that have not been deposited;
     *         completes exceptionally when the outcome is unknown
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items)
    {
        return sendToMaster(bankAccount, executor, items, LedgerType.DEPOSIT);
    }

    /** Task #109: same as {@link #sendToMaster(int, UUID, Map)} with an explicit ledger row type. */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items, LedgerType ledgerType)
    {
        return sendToMaster(bankAccount, executor, items, ledgerType, null);
    }

    /** Task #125: same, plus the transfer_id of the caller's TransferJournal rows (the master's ledger ref). */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items,
                                                                    LedgerType ledgerType, @Nullable String transferId)
    {
        DepositItemsInBankRequest.InputData inputData = new DepositItemsInBankRequest.InputData(bankAccount, executor, items, ledgerType.code(), transferId);
        final long sentMs = System.currentTimeMillis();
        CompletableFuture<OutputData> sent;
        try {
            sent = BankSystemNetworking.DEPOSIT_ITEMS_IN_BANK_REQUEST.sendRequestToMaster(inputData);
        } catch (RuntimeException e) {
            BACKEND_INSTANCES.LOGGER.error("[DepositItemsInBankRequest] request not sent", e);
            sent = CompletableFuture.completedFuture(null); // not sent = definitely not applied
        }
        return notDeposited(items, sent, transferId == null ? null : () -> TransferStatusRequest.ask(null, transferId, sentMs));
    }

    /**
     * Task #121 / #126: maps the master's reply to the "not deposited" items. A null reply or a
     * RequestNotSentException means the request was never sent, so everything comes back. Any other
     * failure asks the master ({@code askStatus}): APPLIED → its not-deposited remainder, NOT_APPLIED →
     * everything. If it stays unknown (or there is no id) the future fails: the master may have
     * applied the deposit, so the caller must not refund.
     */
    public static CompletableFuture<Map<ItemID, Long>> notDeposited(Map<ItemID, Long> requested, CompletableFuture<OutputData> sent,
                                                                    @Nullable java.util.function.Supplier<CompletableFuture<TransferStatusRequest.OutputData>> askStatus)
    {
        return TransferStatusRequest.resolve(sent.thenApply(r -> r == null ? null : r.items),
                () -> new HashMap<>(requested), remainder -> new HashMap<>(remainder), askStatus);
    }
    /**
     * Deposits the requested amount of items onto the bank account
     * This does not check for bank account access permissions
     * @param bankAccount target bank account
     * @param items list of items to deposit
     * @return a future containing the items that have not been deposited;
     *         completes exceptionally when the outcome is unknown
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, Map<ItemID, Long> items)
    {
        return sendToMaster(bankAccount, null, items);
    }

    @Override
    public String getRequestTypeID() {
        return DepositItemsInBankRequest.class.getName();
    }
    @Override
    public boolean needsRoutingToMaster() { return false; }

    public CompletableFuture<OutputData> handleOnMasterServer(DepositItemsInBankRequest.InputData input, String slaveID, @Nullable UUID playerSender) {
        // Task #26 (security): untrusted-slave gate. WRITE-side requests that extend
        // BankSystemGenericRequest bypass the central AsyncForwardingRequest gate, so the
        // slave-trust check is enforced here. An untrusted slave could otherwise deposit
        // freely by forging playerSender (see isBlockedForUntrustedSlave). No-op return:
        // the full requested map comes back as "not deposited", master state untouched.
        if (isBlockedForUntrustedSlave(slaveID)) {
            return CompletableFuture.completedFuture(new OutputData(input.items));
        }
        if(playerSender != null)
        {
            warn("This request is not allowed to be sent from a client");
            return CompletableFuture.completedFuture(new OutputData(input.items));
        }
        // Task #126 (B6): a repeated transfer gets the first reply; nothing is deposited twice.
        final String transferId = slaveID == null || slaveID.isEmpty() ? null : input.transferId();
        CompletableFuture<OutputData> seen = TransferStatusRequest.replay(slaveID, transferId);
        if(seen != null)
            return seen;

        IServerBankAccount account = getServerBankManager().getBankAccount(input.bankAccount);
        if(account == null)
        {
            error("The bank account: "+input.bankAccount+" does not exist");
            return CompletableFuture.completedFuture(new OutputData(input.items));
        }

        // Check permission
        if(input.executor != null)
        {
            if(!account.hasPermission(input.executor, BankPermission.DEPOSIT))
            {
                User user = getServerBankManager().getUserByUUID(input.executor);
                String playerName;
                if(user != null)
                    playerName = user.getName();
                else
                    playerName = input.executor.toString();
                warn("Player: "+playerName + " has not the right to deposit items into the bank account: "+input.bankAccount);
                return CompletableFuture.completedFuture(new OutputData(input.items));
            }
        }

        // Task #109: validate the slave's type code once; a disallowed code is logged as 0 + note.
        TransactionLedger.Resolved ledger = TransactionLedger.resolveSlaveCode(input.ledgerTypeCode,
                TransactionLedger.SLAVE_DEPOSIT_CODES, false, null, slaveID, "DepositItemsInBankRequest");
        if (net.kroia.banksystem.banking.ledger.AccountFreeze.blocks(input.bankAccount, ledger.type(), input.executor)) // Task #117
            return CompletableFuture.completedFuture(new OutputData(input.items));
        TransferStatusRequest.applying(slaveID, transferId);
        Map<ItemID, Long> notDepositedItems = new HashMap<>();
        BankAccountBindings bindings = BankAccountBindings.get();
        for(Map.Entry<ItemID, Long> entry : input.items.entrySet())
        {
            long toDeposit = Math.max(0, entry.getValue());
            ItemID itemID = entry.getKey();

            // Task #38: variant routing — redirect coin variants to the bound base-coin
            // slot at the provider's per-item value. Falls through to per-item routing
            // when the stack can't be reconstructed from ItemID (master registry miss).
            if (bindings != null && toDeposit > 0) {
                ItemStack template = ItemIDManager.getItemStackTemplate(itemID);
                if (template.isEmpty()) {
                    debug("Variant routing skipped: no ItemStack template for " + itemID
                            + " (master registry miss); falling through to per-item routing");
                } else {
                    BindingRow row = bindings.findBindingAcceptingItem(input.bankAccount, template);
                    if (row != null) {
                        ExternalCurrencyProvider provider = BankSystemMod.getAPI()
                                .getCurrencyProvider(row.ref().providerId());
                        long baseUnits = provider == null ? 0L : provider.baseUnitsPerItem(template);
                        if (baseUnits > 0L) {
                            long amount;
                            try {
                                amount = Math.multiplyExact(toDeposit, baseUnits);
                            } catch (ArithmeticException overflow) {
                                notDepositedItems.put(itemID, toDeposit);
                                continue;
                            }
                            IServerBank baseBank = account.getOrCreateBank(new ItemID(row.itemIdShort()));
                            if (baseBank != null && TransactionLedger.withoutRawLog(() -> baseBank.deposit(amount)) == BankStatus.SUCCESS) { // BS-254: row below
                                String coin = "coin " + itemID.getName() + " x" + toDeposit;
                                TransactionLedger.logBank(baseBank, ledger.type(), amount, ledgerActor(input),
                                        ledger.note() == null ? coin : ledger.note() + "; " + coin, input.transferId());
                                continue;
                            }
                            notDepositedItems.put(itemID, toDeposit);
                            continue;
                        }
                    }
                }
            }

            IServerBank itemBank = account.getOrCreateBank(itemID);
            if(itemBank != null)
            {
                final long depositNow = toDeposit;
                if(TransactionLedger.withoutRawLog(() -> itemBank.deposit(depositNow)) == BankStatus.SUCCESS) // BS-254: row below
                {
                    logDeposit(input, ledger, itemBank, toDeposit);
                    toDeposit = 0;
                }
            }
            if(toDeposit != 0)
            {
                notDepositedItems.put(itemID, toDeposit);
            }
        }
        OutputData out = new OutputData(notDepositedItems);
        TransferStatusRequest.remember(slaveID, transferId, !notDepositedItems.equals(input.items), notDepositedItems, out);
        return CompletableFuture.completedFuture(out);
    }

    /**
     * Task #44 / #48 / #109 — one ledger row per deposited item. A stamped share is logged
     * as SHARE_TRADE with its company id, anything else with the request's type code.
     */
    private static void logDeposit(InputData input, TransactionLedger.Resolved ledger, IServerBank bank, long amount) {
        try {
            ItemID itemID = bank.getItemID();
            Integer companyId = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                    .getCompanyIdForItemID(itemID);
            LedgerType type = companyId != null ? LedgerType.SHARE_TRADE : ledger.type();
            TransactionLedger.write(TransactionLogRecord.of(input.bankAccount, type, itemID.getShort(), amount,
                    ledgerActor(input), null, companyId, input.transferId(), ledger.note(), bank.getTotalBalance()));
        } catch (RuntimeException ignored) {
            // best-effort: the balance is already updated
        }
    }

    private static LedgerActor ledgerActor(InputData input) {
        return input.executor == null ? LedgerActor.system() : TransactionLedger.player(input.executor);
    }


    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        OutputData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        return OutputData.STREAM_CODEC.decode(buf);
    }


}
