package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.client_server.arrs.RequestNotSentException;
import net.kroia.modutilities.networking.client_server.arrs.RequestOutcomeUnknownException;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class WithdrawItemsFromBankRequest extends BankSystemGenericRequest<WithdrawItemsFromBankRequest.InputData, WithdrawItemsFromBankRequest.OutputData> {



    /**
     * @param ledgerTypeCode Task #109: {@link LedgerType} code written for each withdrawn item;
     *                       WITHDRAW by default. The master checks it against
     *                       {@link TransactionLedger#SLAVE_WITHDRAW_CODES}.
     */
    public record InputData(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items, int ledgerTypeCode)
    {
        public InputData(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items) {
            this(bankAccount, executor, items, LedgerType.WITHDRAW.code());
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, p->p.bankAccount,
                ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC), p->p.executor,
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                ByteBufCodecs.VAR_INT, p -> p.ledgerTypeCode,
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
     * Withdraws the requested items from the given bank account
     * If the executor is set, it checks if the executor has withdraw permission on the bank account
     * @param bankAccount target bank account
     * @param executor the player who has created this request
     * @param items list of items to deposit
     * @return a future containing the items that have been withdrawn. Task #160: fails with
     *         {@link RequestNotSentException} when the request was never sent (nothing withdrawn), and with
     *         {@link RequestOutcomeUnknownException} on any other failure: the master may have withdrawn the items.
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items)
    {
        return sendToMaster(bankAccount, executor, items, LedgerType.WITHDRAW);
    }

    /** Task #109: same as {@link #sendToMaster(int, UUID, Map)} with an explicit ledger row type. */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items, LedgerType ledgerType)
    {
        WithdrawItemsFromBankRequest.InputData inputData = new WithdrawItemsFromBankRequest.InputData(bankAccount, executor, items, ledgerType.code());
        CompletableFuture<OutputData> sent;
        try {
            sent = BankSystemNetworking.WITHDRAW_ITEMS_FROM_BANK_REQUEST.sendRequestToMaster(inputData);
        } catch (RuntimeException e) {
            RequestNotSentException notSent = new RequestNotSentException("withdraw request not sent: " + e);
            notSent.initCause(e);
            sent = CompletableFuture.failedFuture(notSent);
        }
        return withdrawn(sent);
    }

    /**
     * Task #160: maps the master's reply to the withdrawn items. A {@link RequestNotSentException} is passed on;
     * any other failure or a null reply fails with {@link RequestOutcomeUnknownException}, never "nothing withdrawn".
     */
    public static CompletableFuture<Map<ItemID, Long>> withdrawn(CompletableFuture<OutputData> sent)
    {
        CompletableFuture<Map<ItemID, Long>> future = new CompletableFuture<>();
        sent.whenComplete((response, ex) -> {
            if (ex == null && response != null) {
                future.complete(response.items);
                return;
            }
            Throwable cause = ex == null ? null : TransferStatusRequest.cause(ex);
            if (cause instanceof RequestNotSentException || cause instanceof RequestOutcomeUnknownException) {
                future.completeExceptionally(cause);
                return;
            }
            RequestOutcomeUnknownException unknown = new RequestOutcomeUnknownException("withdraw outcome unknown: " + (cause == null ? "null reply" : cause));
            if (cause != null) unknown.initCause(cause);
            future.completeExceptionally(unknown);
        });
        return future;
    }
    /**
     * Withdraws the requested items from the given bank account
     * No permission check is done here
     * @param bankAccount target bank account
     * @param items list of items to deposit
     * @return a future containing the items that have been withdrawn
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, Map<ItemID, Long> items)
    {
        return sendToMaster(bankAccount, null, items);
    }

    @Override
    public String getRequestTypeID() {
        return WithdrawItemsFromBankRequest.class.getName();
    }
    @Override
    public boolean needsRoutingToMaster() { return false; }

    public CompletableFuture<OutputData> handleOnMasterServer(WithdrawItemsFromBankRequest.InputData input, String slaveID, @Nullable UUID playerSender) {
        // Task #26 (security): untrusted-slave gate — see DepositItemsInBankRequest. No-op
        // return: empty withdrawn-items map, master balances untouched.
        if (isBlockedForUntrustedSlave(slaveID)) {
            return CompletableFuture.completedFuture(new OutputData(new HashMap<>()));
        }
        if(playerSender != null)
        {
            warn("This request is not allowed to be sent from a client");
            return CompletableFuture.completedFuture(new OutputData(new HashMap<>()));
        }

        IServerBankAccount account = getServerBankManager().getBankAccount(input.bankAccount);
        if(account == null)
        {
            error("The bank account: "+input.bankAccount+" does not exist");
            return CompletableFuture.completedFuture(new OutputData(new HashMap<>()));
        }

        // Check permission
        if(input.executor != null)
        {
            if(!account.hasPermission(input.executor, BankPermission.WITHDRAW))
            {
                User user = getServerBankManager().getUserByUUID(input.executor);
                String playerName;
                if(user != null)
                    playerName = user.getName();
                else
                    playerName = input.executor.toString();
                warn("Player: "+playerName + " has not the right to withdraw items from the bank account: "+input.bankAccount);
                return CompletableFuture.completedFuture(new OutputData(new HashMap<>()));
            }
        }

        // Task #109: validate the slave's type code once; a disallowed code is logged as 0 + note.
        TransactionLedger.Resolved ledger = TransactionLedger.resolveSlaveCode(input.ledgerTypeCode,
                TransactionLedger.SLAVE_WITHDRAW_CODES, false, null, slaveID, "WithdrawItemsFromBankRequest");
        if (net.kroia.banksystem.banking.ledger.AccountFreeze.blocks(input.bankAccount, ledger.type(), input.executor)) // Task #117
            return CompletableFuture.completedFuture(new OutputData(new HashMap<>()));
        Map<ItemID, Long> withdrawnItems = new HashMap<>();
        for(Map.Entry<ItemID, Long> entry : input.items.entrySet())
        {
            long toWithdraw = Math.max(0, entry.getValue());
            IServerBank itemBank = account.getBank(entry.getKey());
            if(itemBank != null)
            {
                toWithdraw = Math.min(toWithdraw, itemBank.getBalance());
                if(itemBank.withdraw(toWithdraw) == BankStatus.SUCCESS)
                {
                    withdrawnItems.put(entry.getKey(), toWithdraw);
                    logWithdrawal(input, ledger, itemBank, toWithdraw);
                }
            }
        }
        return CompletableFuture.completedFuture(new OutputData(withdrawnItems));
    }

    /** Task #44 / #48 / #109 — one WITHDRAW (or SHARE_TRADE for stamped shares) row per item. */
    private static void logWithdrawal(InputData input, TransactionLedger.Resolved ledger, IServerBank bank, long amount) {
        if (amount <= 0) return;
        try {
            ItemID itemID = bank.getItemID();
            Integer companyId = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                    .getCompanyIdForItemID(itemID);
            LedgerType type = companyId != null ? LedgerType.SHARE_TRADE : ledger.type();
            LedgerActor actor = TransactionLedger.slaveActor(input.executor);
            TransactionLedger.write(TransactionLogRecord.of(input.bankAccount, type, itemID.getShort(), amount,
                    actor, null, companyId, null, ledger.note(), bank.getTotalBalance()));
        } catch (RuntimeException ignored) {
            // best-effort
        }
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
