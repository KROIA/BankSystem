package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import java.util.ArrayList;
import net.kroia.banksystem.util.ItemStackNbt;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UpdateBankAccountRequest extends BankSystemGenericRequest<UpdateBankAccountRequest.InputData, @Nullable BankAccountData> {

    /** BS-234: what happens to shares removed from a share-item bank (balance lowered or bank closed). */
    public static final int SHARE_NONE = 0;
    public static final int SHARE_RETURN_TO_COMPANY = 1;
    public static final int SHARE_GIVE_TO_ME = 2;
    public static final int SHARE_DESTROY = 3;

    public record InputData(int accountNumber,
                            String accountName,
                            @Nullable ItemStack accountIcon,
                            List<BankData> bankData,
                            Map<UUID, Integer> setUsers,
                            int setFrozen) // Task #117: -1 = unchanged, 0 = unfreeze, 1 = freeze (admins only)
    {

        public record BankData(ItemID itemID,
                               long balance,
                               boolean setBalance,
                               boolean resetLockedBalance,
                               boolean removeBank,
                               boolean createBank,
                               int shareRemoval) // BS-234: SHARE_* choice
        {
            public BankData(ItemID itemID, long balance, boolean setBalance, boolean resetLockedBalance,
                            boolean removeBank, boolean createBank)
            {
                this(itemID, balance, setBalance, resetLockedBalance, removeBank, createBank, SHARE_NONE);
            }

            public static final StreamCodec<RegistryFriendlyByteBuf, BankData> STREAM_CODEC = StreamCodec.of(
                    (buf, d) -> {
                        ItemID.STREAM_CODEC.encode(buf, d.itemID);
                        ByteBufCodecs.VAR_LONG.encode(buf, d.balance);
                        buf.writeBoolean(d.setBalance);
                        buf.writeBoolean(d.resetLockedBalance);
                        buf.writeBoolean(d.removeBank);
                        buf.writeBoolean(d.createBank);
                        ByteBufCodecs.VAR_INT.encode(buf, d.shareRemoval);
                    },
                    buf -> new BankData(ItemID.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                            ByteBufCodecs.VAR_INT.decode(buf))
            );
        }

        /**
         * NBT-based codec for {@link #accountIcon} — see the class-level Javadoc on
         * {@link net.kroia.banksystem.networking.general.SyncItemIDsPacket#STREAM_CODEC}
         * for the underlying rationale (Task #25): vanilla's
         * {@link ItemStack#STREAM_CODEC} encodes by runtime numeric registry ID, which
         * is stable client↔server but NOT stable when this request is forwarded
         * slave→master (each server has an independent numeric ID table). Encoding by
         * resource key via {@link ItemStack#save(net.minecraft.core.HolderLookup.Provider, Tag)}
         * is stable across processes; unresolvable icons (mod missing on the receiving
         * side) decode to {@link ItemStack#EMPTY} and the {@code InputData} canonical
         * constructor coerces that to {@code null} → account keeps its previous icon
         * (or none) instead of silently binding to a wrong item.
         */
        private static final StreamCodec<RegistryFriendlyByteBuf, ItemStack> ITEM_STACK_NBT_CODEC = StreamCodec.of(
                (buf, stack) -> {
                    try
                    {
                        Tag stackTag = ItemStackNbt.save(stack, buf.registryAccess(), new CompoundTag());
                        if(stackTag instanceof CompoundTag compoundTag)
                            ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, compoundTag);
                        else
                            ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, new CompoundTag());
                    }
                    catch(Exception ignored)
                    {
                        // Unserializable in the current registry context: send an empty
                        // tag so the receiver's parseOptional degrades to EMPTY (→ null
                        // icon via the canonical InputData ctor).
                        ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, new CompoundTag());
                    }
                },
                buf -> {
                    CompoundTag stackTag = ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf);
                    if(stackTag.isEmpty())
                        return ItemStack.EMPTY;
                    Optional<ItemStack> parsed = ItemStackNbt.parse(buf.registryAccess(), stackTag);
                    return parsed.orElse(ItemStack.EMPTY);
                }
        );

        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, InputData::accountNumber,
                ByteBufCodecs.STRING_UTF8, InputData::accountName,
                ExtraCodecUtils.nullable(ITEM_STACK_NBT_CODEC), InputData::accountIcon,
                ExtraCodecUtils.listStreamCodec(BankData.STREAM_CODEC), InputData::bankData,
                ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, ByteBufCodecs.INT, HashMap<UUID, Integer>::new), InputData::setUsers,
                ByteBufCodecs.INT, InputData::setFrozen,
                InputData::new
        );


        public InputData(int accountNumber,
                         String accountName,
                         @Nullable ItemStack accountIcon,
                         List<BankData> bankData,
                         Map<UUID, Integer> setUsers)
        {
            this(accountNumber, accountName, accountIcon, bankData, setUsers, -1);
        }

        public InputData(int accountNumber,
                         String accountName,
                         @Nullable ItemStack accountIcon,
                         List<BankData> bankData,
                         Map<UUID, Integer> setUsers,
                         int setFrozen)
        {
            this.setFrozen = setFrozen;
            this.accountNumber = accountNumber;
            this.accountName = accountName;
            if(accountIcon != null)
            {
                if(accountIcon.isEmpty())
                {
                    this.accountIcon = null;
                }
                else
                {
                    this.accountIcon = accountIcon;
                }
            }
            else
                this.accountIcon = null;
            this.bankData = bankData;
            this.setUsers = setUsers;
        }


    }

    @Override
    public String getRequestTypeID() {
        return UpdateBankAccountRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<BankAccountData> handleOnServer(InputData input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> handleOnMasterServer(InputData input, String slaveID, UUID sender) {
        CompletableFuture<@Nullable BankAccountData>  future = new CompletableFuture<>();
        // Task #26: an untrusted slave must not perform bank-account writes. The per-player
        // admin/permission checks below trust the forwarded sender UUID, which an untrusted
        // slave can forge — so gate on the authenticated slaveID first.
        if (isBlockedForUntrustedSlave(slaveID)) {
            future.complete(null);
            return future;
        }
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        // Check if the player is a admin
        boolean isAdmin = playerIsAdmin(sender);

        ISyncServerBankAccount account = bankManager.getBankAccount(input.accountNumber);
        if(account == null) {
            // If the account does not exist, we cannot update it
            future.complete(null);
            return future;
        }
        boolean canManage = account.hasPermission(sender, BankPermission.MANAGE);
        if (!isAdmin && !canManage) {
            future.complete(null);
            return future;
        }

        // Task #109: admins acting on an account they could not manage are ADMIN, else PLAYER.
        final LedgerActor actor = isAdmin && !canManage ? TransactionLedger.admin(sender) : TransactionLedger.player(sender);
        final LedgerActor adminActor = TransactionLedger.admin(sender);
        List<String> edits = new ArrayList<>();
        if(input.accountName != null && !input.accountName.isEmpty()) {
            String oldName = account.getAccountName();
            account.setAccountName(input.accountName); // cut to ServerBankAccount.MAX_ACCOUNT_NAME_LENGTH
            String newName = account.getAccountName();
            if (!newName.equals(oldName))
                edits.add("name '" + oldName + "'->'" + newName + "'");
        }

        if (isAdmin && input.setFrozen >= 0) // Task #117: ledger row + event inside setAccountFrozen
            bankManager.setAccountFrozen(input.accountNumber, input.setFrozen == 1, adminActor);

        if(input.bankData != null) {
            for (InputData.BankData data : input.bankData) {
                // Closing a bank destroys its balance but cannot create value, so an account
                // manager (owner / MANAGE permission) may close their own banks — not just
                // admins. Reaching this handler already guarantees isAdmin || canManage (checked
                // above), so no further permission check is needed for the removal.
                if (data.removeBank) {
                    if (!isAdmin && bankManager.isAccountFrozen(input.accountNumber))
                        continue; // Task #117: a frozen account's banks can only be closed by an admin
                    boolean suspended = BankAccountBindings.isSuspended(input.accountNumber, data.itemID);
                    if (!isAdmin && suspended)
                        continue; // Task #146: keep a suspended binding for the admins to investigate
                    ISyncServerBank closing = account.getBank(data.itemID);
                    Integer shareCompany = StampedShareItem.getCompanyIdForItemID(data.itemID);
                    if (closing != null && shareCompany != null && closing.getTotalBalance() != 0) {
                        // BS-234: shares never vanish silently; locked ones stay untouched
                        if (closing.getLockedBalance() != 0) {
                            notifySender(sender, SHARE_KEY + "locked_refused");
                            continue;
                        }
                        if (!removeShares(bankManager, account, closing, shareCompany, closing.getBalance(),
                                data.shareRemoval, sender, isAdmin, isAdmin ? adminActor : actor))
                            continue;
                    }
                    long destroyed = closing == null ? 0L : closing.getTotalBalance();
                    TransactionLedger.withActor(actor, () -> TransactionLedger.withoutRawLog(() -> { account.removeBank(data.itemID); return null; })); // BS-254: row below
                    if (closing != null && !suspended) // Task #148: removeBank logs a suspended binding's closure + flag
                        TransactionLedger.logRow(input.accountNumber, LedgerType.BANK_CLOSED, data.itemID, destroyed,
                                actor, null, "bank closed");
                    continue;
                }
                // Balance manipulation (set balance, create a bank with a balance, or unlock
                // locked funds) can create or launder value — restricted to BankSystem admins.
                if (!isAdmin)
                    continue;
                ISyncServerBank bank = account.getBank(data.itemID);
                Integer shareCompany = StampedShareItem.getCompanyIdForItemID(data.itemID);
                if (bank != null) {
                    if (data.resetLockedBalance) {
                        long locked = bank.getLockedBalance();
                        bank.unlockAll();
                        if (locked != 0)
                            TransactionLedger.logBank(bank, LedgerType.ADMIN_UNLOCK, locked, adminActor, "unlocked " + locked);
                    }
                    if (data.setBalance && shareCompany != null) {
                        // BS-234: a share balance can only go down (to no less than the locked part)
                        long oldTotal = bank.getTotalBalance();
                        if (data.balance > oldTotal)
                            notifySender(sender, SHARE_KEY + "increase_refused");
                        else if (data.balance < bank.getLockedBalance())
                            notifySender(sender, SHARE_KEY + "locked_refused");
                        else
                            removeShares(bankManager, account, bank, shareCompany, oldTotal - data.balance,
                                    data.shareRemoval, sender, true, adminActor);
                    } else if (data.setBalance) {
                        long oldBalance = bank.getBalance();
                        TransactionLedger.withoutRawLog(() -> bank.setBalance(data.balance)); // BS-254: row below
                        long newBalance = bank.getBalance();
                        if (newBalance != oldBalance)
                            TransactionLedger.logBank(bank, LedgerType.ADMIN_SET, Math.abs(newBalance - oldBalance),
                                    adminActor, oldBalance + "->" + newBalance);
                    }
                } else {
                    if (data.createBank) {
                        long startBalance = data.balance;
                        if (shareCompany != null && startBalance != 0) { // BS-234: no shares out of thin air
                            notifySender(sender, SHARE_KEY + "increase_refused");
                            startBalance = 0;
                        }
                        long finalStart = startBalance;
                        ISyncServerBank created = TransactionLedger.withoutRawLog(() -> account.createBank(data.itemID, finalStart)); // BS-254: row below
                        if (created != null && startBalance != 0)
                            TransactionLedger.logBank(created, LedgerType.ADMIN_SET, Math.abs(created.getBalance()),
                                    adminActor, "bank created: 0->" + created.getBalance());
                    }
                }
            }
        }

        if (input.setUsers != null) {
            int validMask = BankPermission.getAllPermissions();
            User owner = account.getPersonalBankOwner();
            Map<User, Integer> userList = new HashMap<>(input.setUsers.size());
            for (Map.Entry<UUID, Integer> entry : input.setUsers.entrySet()) {
                UUID userUUID = entry.getKey();
                int permissions = entry.getValue() & validMask;
                if (owner != null && owner.getUUID().equals(userUUID))
                    continue;
                User userToSet = bankManager.getUserByUUID(userUUID);
                if (userToSet != null) {
                    userList.put(userToSet, permissions);
                }
            }
            // Invariant: a non-personal account must always keep at least one MANAGE holder.
            // Personal accounts are already safe (owner implicitly holds MANAGE), so the helper
            // short-circuits to OK when owner != null. Task #43 (v2.1.0): the four-arg overload
            // additionally refuses removals of MANAGE from Company founders.
            Map<User, Integer> previous = new HashMap<>();
            for (net.kroia.banksystem.banking.clientdata.BankUserData bud : account.getUserData()) {
                User u = bankManager.getUserByUUID(bud.userUUID);
                if (u != null) previous.put(u, bud.permissions);
            }
            ServerBankAccount.ManageInvariantOutcome outcome =
                    ServerBankAccount.enforceManageInvariant(userList, previous, owner != null, input.accountNumber);
            switch (outcome) {
                case REFUSED_FOUNDER -> {
                    warn("Refused user-set change on account " + input.accountNumber
                            + ": it would strip MANAGE from a Company founder. User-set change skipped.");
                    if (slaveID != null && slaveID.isEmpty()) {
                        notifySender(sender,
                                "gui.banksystem.bank_account_management_screen.must_keep_founder");
                    }
                }
                case REFUSED_ORPHAN -> {
                    // Removing the last user would orphan the account. Skip ONLY the user-set
                    // mutation; the name/icon/bankData changes above/below still apply. The
                    // pre-existing users therefore remain and hasAnyUser() below stays true,
                    // so the account is not deleted.
                    warn("Refused user-set change on account " + input.accountNumber
                            + ": it would remove the last user and orphan the account "
                            + "(no managing user would remain). User-set change skipped.");
                    // Best-effort: tell the initiating player (local request only) why nothing changed.
                    if (slaveID != null && slaveID.isEmpty()) {
                        notifySender(sender,
                                "gui.banksystem.bank_account_management_screen.must_keep_manager");
                    }
                }
                case PROMOTED -> {
                    String promotedName = userList.entrySet().stream()
                            .filter(e -> BankPermission.hasPermission(e.getValue(), BankPermission.MANAGE.getValue()))
                            .map(e -> e.getKey().getName())
                            .findFirst().orElse("<unknown>");
                    warn("Auto-granted MANAGE to '" + promotedName + "' on account "
                            + input.accountNumber + " to keep the account manageable "
                            + "(no managing user remained after the requested change).");
                    account.setUsers(userList);
                }
                case OK -> account.setUsers(userList);
            }
            if (outcome == ServerBankAccount.ManageInvariantOutcome.OK
                    || outcome == ServerBankAccount.ManageInvariantOutcome.PROMOTED) {
                Map<UUID, Integer> before = new HashMap<>();
                for (Map.Entry<User, Integer> e : previous.entrySet()) before.put(e.getKey().getUUID(), e.getValue());
                Map<UUID, Integer> after = new HashMap<>();
                for (Map.Entry<User, Integer> e : userList.entrySet()) after.put(e.getKey().getUUID(), e.getValue());
                if (owner != null) {
                    before.remove(owner.getUUID());
                    after.remove(owner.getUUID());
                }
                if (!before.equals(after))
                    edits.add("users/permissions " + before + "->" + after);
            }
            // Task #45 (v2.1.0) — cascade-strip payout schedules whose target was just removed.
            // Only fire when the user-set change actually landed (OK / PROMOTED); on refuse
            // paths the previous users are unchanged so nothing to strip.
            if (outcome == ServerBankAccount.ManageInvariantOutcome.OK
                    || outcome == ServerBankAccount.ManageInvariantOutcome.PROMOTED) {
                net.kroia.banksystem.banking.company.CompanyManager cm =
                        net.kroia.banksystem.banking.company.CompanyManager.get();
                if (cm != null) {
                    java.util.Set<UUID> newUuids = new java.util.HashSet<>();
                    for (User u : userList.keySet()) newUuids.add(u.getUUID());
                    for (User prev : previous.keySet()) {
                        if (!newUuids.contains(prev.getUUID())) {
                            cm.cascadeStripPayoutsForRemovedUser(input.accountNumber, prev.getUUID());
                        }
                    }
                }
            }
        }
        if(input.accountIcon != null)
        {
            ItemID iconID = ItemID.getOrRegisterFromItemStackServerSide_direct(input.accountIcon);
            ItemID oldIcon = account.getAccountIcon();
            account.setAccountIcon(iconID);
            if (oldIcon == null || !oldIcon.equals(iconID))
                edits.add("icon " + iconID.getName());
        }
        if (!edits.isEmpty())
            TransactionLedger.logRow(input.accountNumber, LedgerType.ADMIN_ACCOUNT_EDIT, ItemID.INVALID_ID, 0L,
                    actor, null, String.join("; ", edits));
        if(!account.hasAnyUser())
        {
            // If the account has no users, we remove it
            TransactionLedger.withActor(actor, () -> bankManager.deleteBankAccount(input.accountNumber));
            future.complete(null);
            return future; // The account was deleted
        }

        future.complete(account.getAccountData());
        return future;
    }

    private static final String SHARE_KEY = "gui.banksystem.bank_account_management_screen.share_removal.";

    /**
     * BS-234: takes {@code amount} raw units of company shares out of {@code bank}'s free balance:
     * RETURN_TO_COMPANY moves them to the company's own account, GIVE_TO_ME puts the stamped share
     * items into the sender's inventory (sender must be online here), DESTROY (admin, company account
     * only) also lowers the company's issued share count. Anything else is refused.
     *
     * @return {@code true} if the shares were removed
     */
    private boolean removeShares(ISyncServerBankManager bankManager, ISyncServerBankAccount account, ISyncServerBank bank,
                                 int companyId, long amount, int choice, UUID sender, boolean isAdmin, LedgerActor actor) {
        if (amount <= 0)
            return true;
        int scale = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        CompanyManager cm = CompanyManager.get();
        Company company = cm == null ? null : cm.getById(companyId);
        int accountNr = account.getAccountNumber();
        boolean companyAccount = company != null && company.getBankAccountNr() == accountNr;
        boolean valid = company != null && amount % scale == 0 && amount <= bank.getBalance() && switch (choice) {
            case SHARE_RETURN_TO_COMPANY -> !companyAccount;
            case SHARE_GIVE_TO_ME -> isAdmin || account.hasPermission(sender, BankPermission.WITHDRAW);
            case SHARE_DESTROY -> isAdmin && companyAccount;
            default -> false;
        };
        if (!valid) {
            notifySender(sender, SHARE_KEY + "choice_refused");
            return false;
        }
        ItemID itemID = bank.getItemID();
        if (choice == SHARE_RETURN_TO_COMPANY) {
            ISyncServerBankAccount target = bankManager.getBankAccount(company.getBankAccountNr());
            ISyncServerBank targetBank = target == null ? null : target.getOrCreateBank(itemID);
            if (targetBank == null || TransactionLedger.withoutTransferLog(() -> bank.transfer(amount, targetBank)) != BankStatus.SUCCESS) {
                notifySender(sender, SHARE_KEY + "choice_refused");
                return false;
            }
            TransactionLedger.write(TransactionLogRecord.of(accountNr, LedgerType.TRANSFER, itemID.getShort(), -amount, actor,
                    company.getBankAccountNr(), companyId, null, "shares returned to company", bank.getTotalBalance()));
            TransactionLedger.write(TransactionLogRecord.of(company.getBankAccountNr(), LedgerType.TRANSFER, itemID.getShort(), amount, actor,
                    accountNr, companyId, null, "shares returned to company", targetBank.getTotalBalance()));
            return true;
        }
        if (choice == SHARE_GIVE_TO_ME) {
            // ponytail: local delivery only; a slave-side admin would need the DropItems transfer-id chain
            if (ServerPlayerUtilities.getOnlinePlayer(sender) == null) {
                notifySender(sender, SHARE_KEY + "give_offline");
                return false;
            }
            if (TransactionLedger.withoutRawLog(() -> bank.withdraw(amount)) != BankStatus.SUCCESS) { // BS-254: row below
                notifySender(sender, SHARE_KEY + "choice_refused");
                return false;
            }
            Map<ItemID, Long> left = DropItemsInPlayerInventoryRequest.dropItems(UtilitiesPlatform.getServer(), sender,
                    Map.of(itemID, amount / scale), true);
            long notGiven = left.getOrDefault(itemID, 0L) * scale;
            if (notGiven > 0)
                TransactionLedger.withoutRawLog(() -> bank.deposit(notGiven)); // the row below holds the net amount
            if (amount > notGiven)
                TransactionLedger.write(TransactionLogRecord.of(accountNr, isAdmin ? LedgerType.ADMIN_REMOVE : LedgerType.WITHDRAW,
                        itemID.getShort(), amount - notGiven, actor, null, companyId, null, "shares given to editor", bank.getTotalBalance()));
            return notGiven == 0;
        }
        // SHARE_DESTROY
        if (TransactionLedger.withoutRawLog(() -> bank.withdraw(amount)) != BankStatus.SUCCESS) { // BS-254: row below
            notifySender(sender, SHARE_KEY + "choice_refused");
            return false;
        }
        long issuedBefore = company.getTotalSharesIssued();
        cm.destroyShares(companyId, amount / scale);
        TransactionLedger.write(TransactionLogRecord.of(accountNr, LedgerType.ADMIN_REMOVE, itemID.getShort(), amount, actor,
                null, companyId, null, "shares destroyed; issued " + issuedBefore + "->" + company.getTotalSharesIssued(),
                bank.getTotalBalance()));
        return true;
    }

    /**
     * Best-effort chat notice to the initiating player, if they are online on this (master)
     * server. Used to explain a master-side rejection (e.g. the manage-invariant refusal).
     * No-op if the player cannot be resolved — a WARN log has already been emitted.
     *
     * @param senderUUID       the initiating player's UUID
     * @param translationKey   the lang key of the message to send
     */
    private void notifySender(UUID senderUUID, String translationKey) {
        if (senderUUID == null) {
            return;
        }
        for (ServerPlayer player : ServerPlayerUtilities.getOnlinePlayers()) {
            if (player.getUUID().equals(senderUUID)) {
                player.sendSystemMessage(Component.translatable(translationKey));
                return;
            }
        }
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, BankAccountData output) {
        ExtraCodecUtils.nullable(BankAccountData.STREAM_CODEC).encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public BankAccountData decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.nullable(BankAccountData.STREAM_CODEC).decode(buf);
    }


}
