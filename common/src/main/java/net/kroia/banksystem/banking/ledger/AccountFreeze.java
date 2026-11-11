package net.kroia.banksystem.banking.ledger;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.networking.multi_server.ClientConsoleMessagePacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Task #117 (v2.2.0) — master-side gate for frozen accounts.
 * <p>
 * Every money/item movement is described by a {@link LedgerType} (Task #109). Player-initiated
 * types are refused on a frozen account; admin types (ADMIN_*), payout bookkeeping and raw API
 * calls (no type / {@link TransactionLedger#NO_LOG}) pass. A refused call changes nothing and
 * the player gets a chat message (also on slaves).
 */
public final class AccountFreeze {

    /** Movement types refused on a frozen account. UNKNOWN_LEGACY = a slave code that failed validation. */
    public static final Set<LedgerType> BLOCKED = EnumSet.of(LedgerType.DEPOSIT, LedgerType.WITHDRAW,
            LedgerType.TRANSFER, LedgerType.CONVERTER_DEPOSIT, LedgerType.CONVERTER_WITHDRAW,
            LedgerType.UPLOAD_DEPOSIT, LedgerType.DOWNLOAD_WITHDRAW, LedgerType.CRAFT_CONSUME,
            LedgerType.CRAFT_OUTPUT, LedgerType.SHARE_TRADE, LedgerType.UNKNOWN_LEGACY);

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private AccountFreeze() {}

    /** @return true on the master if the account exists and is frozen. */
    public static boolean isFrozen(int accountNumber) {
        try {
            if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null)
                return false;
            ISyncServerBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            return manager != null && manager.isAccountFrozen(accountNumber);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @return true if a movement of {@code type} on {@code accountNumber} must be refused; the
     *         player (if any) is told why. Never throws.
     */
    public static boolean blocks(int accountNumber, @Nullable LedgerType type, @Nullable UUID player) {
        if (type == null || !BLOCKED.contains(type) || !isFrozen(accountNumber))
            return false;
        notifyPlayer(player, accountNumber);
        return true;
    }

    /** {@link #blocks} for the movement type tagged on this thread with {@link TransactionLedger#tagged}. */
    public static boolean blocksTagged(int accountNumber) {
        TransactionLedger.SlaveTag tag = TransactionLedger.currentTag();
        if (tag.code() == TransactionLedger.NO_LOG)
            return false;
        return blocks(accountNumber, LedgerType.fromCode(tag.code()), tag.actor());
    }

    private static final java.util.Map<UUID, Long> LAST_NOTIFY = new java.util.concurrent.ConcurrentHashMap<>();

    /** Chat message "account is frozen" to the player, wherever they are online. At most one per second. */
    public static void notifyPlayer(@Nullable UUID player, int accountNumber) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_NOTIFY.put(player, now);
        if (last != null && now - last < 1000) return; // one terminal sweep = many bank calls
        try {
            ClientConsoleMessagePacket.sendMessageFromMaster(player, BankSystemTextMessages.getAccountFrozenMessage(accountNumber));
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }
}
