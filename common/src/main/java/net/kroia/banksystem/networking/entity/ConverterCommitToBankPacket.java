package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MoneyDenominationOptimizer;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 *       (atomic withdraw), split it into money items via
 *       {@link MoneyDenominationOptimizer}, and forward via
 *       {@link DepositItemsInBankRequest#sendToMaster} — which already
 *       carries the Task #26 untrusted-slave gate. Undispensed value (e.g.
 *       master refuses because the slave is untrusted, or the account is
 *       gone) is refunded back into the cache so the player never loses
 *       anything.
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

    public static final Type<ConverterCommitToBankPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "converter_commit_to_bank_packet"));

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

        if (BACKEND_INSTANCES.isSlaveServer) {
            commitFromSlave(playerUUID, cached);
        } else {
            commitOnMaster(playerUUID, cached);
        }
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

        // Reserve the cache first so the pending deposit is never double-spent.
        if (!ConverterCacheManager.get().withdraw(playerUUID, amount)) {
            debug("ConverterCommitToBankPacket: race — cache changed under commit for " + playerUUID);
            return;
        }
        BankStatus status = moneyBank.deposit(amount);
        if (status != BankStatus.SUCCESS) {
            warn("ConverterCommitToBankPacket: bank deposit refused (" + status + ") — refunding cache");
            ConverterCacheManager.get().deposit(playerUUID, amount);
        }
    }

    /**
     * Slave-forward path. Reserves the local cache, splits into money items
     * via the optimizer, and forwards through {@code DepositItemsInBankRequest}
     * (which is trust-gated on master). Any value that fails to deposit is
     * refunded to the cache.
     */
    private void commitFromSlave(UUID playerUUID, long amount) {
        // Reserve the cache atomically so a concurrent packet can't double-spend it.
        if (!ConverterCacheManager.get().withdraw(playerUUID, amount)) {
            debug("ConverterCommitToBankPacket: race — cache changed under commit for " + playerUUID);
            return;
        }
        MoneyDenominationOptimizer.SplitResult split = MoneyDenominationOptimizer.split(amount);
        Map<ItemID, Long> items = new HashMap<>(split.counts());
        long leftover = split.leftover();
        // Refund any non-representable leftover immediately (canonical set → always 0).
        if (leftover > 0) {
            ConverterCacheManager.get().deposit(playerUUID, leftover);
        }
        if (items.isEmpty()) return;

        DepositItemsInBankRequest.sendToMaster(selectedAccountNumber, playerUUID, items)
                .whenComplete((notDeposited, ex) -> {
                    // ARRS response lands on the netty IO thread. Hop back to the server thread
                    // before touching the cache map and before dispatching a chat message —
                    // ServerPlayerUtilities.printToClientConsole ultimately calls
                    // player.sendSystemMessage which is not documented as thread-safe.
                    final long refund;
                    if (ex != null || notDeposited == null) {
                        refund = valueOf(items); // transport failure → refund the full reserved amount
                    } else if (!notDeposited.isEmpty()) {
                        refund = valueOf(notDeposited);
                    } else {
                        refund = 0L;
                    }
                    if (refund <= 0) return;

                    Runnable applyRefund = () -> {
                        ConverterCacheManager.get().deposit(playerUUID, refund);
                        ServerPlayerUtilities.printToClientConsole(playerUUID,
                                "Converter deposit to bank refused by master; " + refund
                                        + " cents returned to converter cache.");
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
     * Sums the cent value of a money-item map. Overflow-safe; any overflowing
     * entry is skipped with a WARN — this only happens on pathological data
     * (a cache amount of nearly {@link Long#MAX_VALUE}), and losing the extra
     * entry on refund is better than throwing here.
     */
    private long valueOf(Map<ItemID, Long> items) {
        long total = 0L;
        for (Map.Entry<ItemID, Long> e : items.entrySet()) {
            ItemStack s = e.getKey().getStack();
            if (!(s.getItem() instanceof MoneyItem money)) continue;
            long worth = money.worth();
            long cnt = e.getValue() == null ? 0L : e.getValue();
            if (worth <= 0 || cnt <= 0) continue;
            try {
                total = Math.addExact(total, Math.multiplyExact(cnt, worth));
            } catch (ArithmeticException overflow) {
                warn("ConverterCommitToBankPacket: overflow computing refund value for " + e.getKey());
            }
        }
        return total;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
