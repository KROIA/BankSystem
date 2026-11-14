package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation; // mc<=1.21.10
//import net.minecraft.resources.Identifier; // mc>=1.21.11
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Client-to-server packet for the ATM Money Converter tab (Task #39, v2.0.7):
 * commits the converter cache balance to the player's chosen bank account.
 * This is the ONE branch where the cache crosses the bank boundary — a real
 * bank transaction is created (balance history, DEPOSIT permission check,
 * change stream notifications all apply).
 *
 * <p><b>Multi-server flow.</b> The converter cache lives per-server (master
 * and slave each maintain their own map). The bank always lives on the
 * master. Therefore:
 * <ul>
 *   <li>When the connected server is the master: read the local cache,
 *       validate DEPOSIT permission, deposit directly via
 *       {@code moneyBank.deposit(cachedAmount)}, zero the cache on success.
 *   </li>
 *   <li>When the connected server is a slave: reserve the local cache
 *       (atomic withdraw) and forward it as {@code {money: cents}} via
 *       {@link DepositItemsInBankRequest#sendToMaster} — which already
 *       carries the Task #26 untrusted-slave gate. Value the master reports
 *       as not deposited (e.g. untrusted slave, account gone) or a request
 *       that was never sent is refunded back into the cache. When the
 *       outcome is unknown (the future fails, e.g. timeout or lost reply),
 *       nothing is refunded and an error is logged: the master may have
 *       applied the deposit, so a refund could duplicate value.
 *   </li>
 * </ul>
 *
 * <p><b>Trust gating.</b> The slave-forward path routes through
 * {@code DepositItemsInBankRequest} whose {@code handleOnMasterServer} calls
 * {@code isBlockedForUntrustedSlave(slaveID)} (Task #26). An untrusted slave
 * therefore cannot commit its local cache to any bank account on master. The
 * master-direct path is client-originated so no slave gate applies (the
 * client's per-account permission check remains the authoritative gate).
 */
public class ConverterCommitToBankPacket extends BankSystemNetworkPacket {

    public static final Type<ConverterCommitToBankPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "converter_commit_to_bank_packet")); // mc<=1.21.10
    //public static final Type<ConverterCommitToBankPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BankSystemMod.MOD_ID, "converter_commit_to_bank_packet")); // mc>=1.21.11

    public static final StreamCodec<RegistryFriendlyByteBuf, ConverterCommitToBankPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, p -> p.selectedAccountNumber,
            ConverterCommitToBankPacket::new
    );

    private final int selectedAccountNumber;

    public ConverterCommitToBankPacket(int selectedAccountNumber) {
        super();
        this.selectedAccountNumber = selectedAccountNumber;
    }

    public static void sendPacket(int selectedAccountNumber) {
        new ConverterCommitToBankPacket(selectedAccountNumber).sendToServer();
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        if (sender == null) return;
        UUID playerUUID = sender.getUUID();
        long cached = ConverterCacheManager.get().getCache(playerUUID);
        if (cached <= 0) return;

        // BS-258: the ATM block as the row's position; on a slave it goes in the deposit request
        net.kroia.banksystem.banking.ledger.TransactionLedger.withPos(net.kroia.banksystem.minecraft.block.custom.ATMBlock.openedAt(playerUUID), () -> {
            if (BACKEND_INSTANCES.isSlaveServer) {
                commitFromSlave(playerUUID, cached);
            } else {
                commitOnMaster(playerUUID, cached);
            }
            return null;
        });
    }

    /**
     * Master-direct path. Reads local cache, validates DEPOSIT permission,
     * calls {@code moneyBank.deposit}, and zeroes the cache on success. On
     * any refusal the cache is left intact and the player is notified via
     * chat.
     */
    private void commitOnMaster(UUID playerUUID, long amount) {
        ISyncServerBankManager bankManager = getSyncBankManager();
        if (bankManager == null) return;
        ISyncServerBankAccount account = bankManager.getBankAccount(selectedAccountNumber);
        if (account == null) {
            warn("ConverterCommitToBankPacket: account " + selectedAccountNumber + " does not exist");
            return;
        }
        if (!account.hasPermission(playerUUID, BankPermission.DEPOSIT)) {
            ServerPlayerUtilities.printToClientConsole(playerUUID,
                    BankSystemTextMessages.getNoBankPermissionMessage(account.getAccountName(), BankPermission.DEPOSIT));
            return;
        }
        ISyncServerBank moneyBank = account.getBank(MoneyItem.getItemID());
        if (moneyBank == null) {
            warn("ConverterCommitToBankPacket: no money bank on account " + selectedAccountNumber);
            return;
        }

        if (net.kroia.banksystem.banking.ledger.AccountFreeze.blocks(selectedAccountNumber, net.kroia.banksystem.api.ledger.LedgerType.CONVERTER_DEPOSIT, playerUUID))
            return; // Task #117: cache stays intact
        // Reserve the cache first so the pending deposit is never double-spent.
        if (!ConverterCacheManager.get().withdraw(playerUUID, amount)) {
            debug("ConverterCommitToBankPacket: race — cache changed under commit for " + playerUUID);
            return;
        }
        BankStatus status = net.kroia.banksystem.banking.ledger.TransactionLedger.withoutRawLog(() -> moneyBank.deposit(amount)); // BS-254: row below
        if (status == BankStatus.SUCCESS) {
            net.kroia.banksystem.banking.ledger.TransactionLedger.logBank(moneyBank, net.kroia.banksystem.api.ledger.LedgerType.CONVERTER_DEPOSIT, amount,
                    net.kroia.banksystem.banking.ledger.TransactionLedger.player(playerUUID), null);
        } else {
            warn("ConverterCommitToBankPacket: bank deposit refused (" + status + ") — refunding cache");
            ConverterCacheManager.get().deposit(playerUUID, amount);
        }
    }

    /**
     * Slave-forward path. Reserves the local cache and forwards it as
     * {@code {money: cents}} through {@code DepositItemsInBankRequest} (which is
     * trust-gated on master), so the master credits the money bank with the
     * cents, the same as {@link #commitOnMaster}. Value reported as not
     * deposited is refunded to the cache; an unknown outcome is logged, not
     * refunded.
     *
     * @return the master's "not deposited" reply (public for the in-game test),
     *         or null when the cache could not be reserved
     */
    public CompletableFuture<Map<ItemID, Long>> commitFromSlave(UUID playerUUID, long amount) {
        // Reserve the cache atomically so a concurrent packet can't double-spend it.
        if (!ConverterCacheManager.get().withdraw(playerUUID, amount)) {
            debug("ConverterCommitToBankPacket: race — cache changed under commit for " + playerUUID);
            return null;
        }
        // Task #128: the request deposits raw units into the bank of each key, so the cache goes
        // in as cents on the money item. A banknote split here credited note counts instead.
        ItemID money = MoneyItem.getItemID();
        Map<ItemID, Long> items = Map.of(money, amount);

        // Task #124 (B2): slave TransferJournal rows for this commit, amounts in cents.
        // Task #125: the same id goes on the wire and becomes the master's ledger ref.
        TransferJournal.Ref journal = TransferJournal.ref("converter");
        CompletableFuture<Map<ItemID, Long>> sent = DepositItemsInBankRequest.sendToMaster(selectedAccountNumber, playerUUID, items,
                net.kroia.banksystem.api.ledger.LedgerType.CONVERTER_DEPOSIT, journal.transferId());
        TransferJournal.requested(journal, TransferJournal.TO_MASTER, TransferJournal.CONVERTER_DEPOSIT, selectedAccountNumber,
                playerUUID, items, net.kroia.banksystem.api.ledger.LedgerType.CONVERTER_DEPOSIT.code());
        return sent.whenComplete((notDeposited, ex) -> {
                    // ARRS response lands on the netty IO thread. Hop back to the server thread
                    // before touching the cache map and before dispatching a chat message —
                    // ServerPlayerUtilities.printToClientConsole ultimately calls
                    // player.sendSystemMessage which is not documented as thread-safe.
                    if (ex != null) {
                        // Task #121 / #126: still unknown after the master's status check (the master may
                        // have applied it). No refund, a late success would duplicate the value. Never-sent
                        // and NOT_APPLIED arrive below as the full map and are refunded.
                        error("ConverterCommitToBankPacket: deposit outcome unknown, no refund: "
                                + amount + " cents to account " + selectedAccountNumber
                                + " for player " + playerUUID, ex);
                        TransferJournal.step(journal.transferId(), TransferJournal.TIMED_OUT, null,
                                String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
                        return;
                    }
                    // Not-deposited value comes back in cents (includes "never sent" and NOT_APPLIED: the full map).
                    // Clamped so a bad reply can never refund more than was reserved.
                    final long refund = Math.min(amount, Math.max(0L, notDeposited.getOrDefault(money, 0L)));
                    if (refund < amount)
                        TransferJournal.step(journal.transferId(), TransferJournal.CONFIRMED,
                                refund == 0 ? null : Map.of(money, amount - refund), null);
                    if (refund <= 0) return;

                    Runnable applyRefund = () -> {
                        ConverterCacheManager.get().deposit(playerUUID, refund);
                        TransferJournal.step(journal.transferId(), TransferJournal.RETURNED,
                                refund == amount ? null : Map.of(money, refund), null);
                        notifyRefused(playerUUID, refund);
                    };
                    net.minecraft.server.MinecraftServer server = UtilitiesPlatform.getServer();
                    if (server != null) {
                        server.execute(applyRefund);
                    } else {
                        // No server (shutdown race): fall back to direct call — cache map is thread-
                        // safe, and no player is around to be notified anyway.
                        applyRefund.run();
                    }
                });
    }

    /**
     * BS-273: the refusal line after a refund. An untrusted slave gets the red untrusted line (once, instead of the
     * refund line); otherwise the refund line. Public for the in-game test.
     *
     * @return true if the untrusted line was sent
     */
    public static CompletableFuture<Boolean> notifyRefused(UUID playerUUID, long refund) {
        return MultiServerUtils.notifyIfUntrustedSlave(playerUUID).thenApply(untrusted -> {
            if (!untrusted)
                ServerPlayerUtilities.printToClientConsole(playerUUID,
                        "Converter deposit to bank refused by master; " + refund + " cents returned to converter cache.");
            return untrusted;
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
