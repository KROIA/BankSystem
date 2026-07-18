package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

    public record InputData(int accountNumber,
                            String accountName,
                            @Nullable ItemStack accountIcon,
                            List<BankData> bankData,
                            Map<UUID, Integer> setUsers)
    {

        public record BankData(ItemID itemID,
                               long balance,
                               boolean setBalance,
                               boolean resetLockedBalance,
                               boolean removeBank,
                               boolean createBank)
        {

            public static final StreamCodec<RegistryFriendlyByteBuf, BankData> STREAM_CODEC = StreamCodec.composite(
                    ItemID.STREAM_CODEC, BankData::itemID,
                    ByteBufCodecs.VAR_LONG, BankData::balance,
                    ByteBufCodecs.BOOL, BankData::setBalance,
                    ByteBufCodecs.BOOL, BankData::resetLockedBalance,
                    ByteBufCodecs.BOOL, BankData::removeBank,
                    ByteBufCodecs.BOOL, BankData::createBank,
                    BankData::new
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
                        Tag stackTag = stack.save(buf.registryAccess(), new CompoundTag());
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
                    Optional<ItemStack> parsed = ItemStack.parse(buf.registryAccess(), stackTag);
                    return parsed.orElse(ItemStack.EMPTY);
                }
        );

        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, InputData::accountNumber,
                ByteBufCodecs.STRING_UTF8, InputData::accountName,
                ExtraCodecUtils.nullable(ITEM_STACK_NBT_CODEC), InputData::accountIcon,
                ExtraCodecUtils.listStreamCodec(BankData.STREAM_CODEC), InputData::bankData,
                ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, ByteBufCodecs.INT, HashMap<UUID, Integer>::new), InputData::setUsers,
                InputData::new
        );


        public InputData(int accountNumber,
                         String accountName,
                         @Nullable ItemStack accountIcon,
                         List<BankData> bankData,
                         Map<UUID, Integer> setUsers)
        {
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

        if(input.accountName != null && !input.accountName.isEmpty()) {
            account.setAccountName(input.accountName);
        }

        if(input.bankData != null) {
            for (InputData.BankData data : input.bankData) {
                // Closing a bank destroys its balance but cannot create value, so an account
                // manager (owner / MANAGE permission) may close their own banks — not just
                // admins. Reaching this handler already guarantees isAdmin || canManage (checked
                // above), so no further permission check is needed for the removal.
                if (data.removeBank) {
                    account.removeBank(data.itemID);
                    continue;
                }
                // Balance manipulation (set balance, create a bank with a balance, or unlock
                // locked funds) can create or launder value — restricted to BankSystem admins.
                if (!isAdmin)
                    continue;
                ISyncServerBank bank = account.getBank(data.itemID);
                if (bank != null) {
                    if (data.resetLockedBalance)
                        bank.unlockAll();
                    if (data.setBalance)
                        bank.setBalance(data.balance);
                } else {
                    if (data.createBank) {
                        account.createBank(data.itemID, data.balance);
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
            account.setUsers(userList);
        }
        if(input.accountIcon != null)
        {
            ItemID iconID = ItemID.getOrRegisterFromItemStackServerSide_direct(input.accountIcon);
            account.setAccountIcon(iconID);
        }
        if(!account.hasAnyUser())
        {
            // If the account has no users, we remove it
            bankManager.deleteBankAccount(input.accountNumber);
            future.complete(null);
            return future; // The account was deleted
        }

        future.complete(account.getAccountData());
        return future;
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
