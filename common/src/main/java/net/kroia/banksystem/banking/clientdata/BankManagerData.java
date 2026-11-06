package net.kroia.banksystem.banking.clientdata;


import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BankManagerData(UserMapData userMapData, BankAccountsData bankAccountsData, List<ItemID> allowedItems,
                              List<ItemID> blacklistedItems)
{
    public record UserMapData(Map<UUID, UserData> userMap) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UserMapData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, UserData.STREAM_CODEC, HashMap::new), p -> p.userMap,
                UserMapData::new
        );

        /** Task #103: copy with user names and UUIDs only (custom data dropped) for non-admin readers. */
        public UserMapData namesOnly() {
            Map<UUID, UserData> reduced = new HashMap<>();
            userMap.forEach((uuid, u) -> reduced.put(uuid, new UserData(u.userUUID(), u.userName(), new CompoundTag())));
            return new UserMapData(reduced);
        }
    }

    /**
     * Task #103: copy safe to send to non-admins: user names and UUIDs only, no bank accounts.
     * The allow/blacklist item lists are kept; they are public server config (also readable via
     * {@code getAllowedItemsAsync}).
     */
    public BankManagerData namesOnly() {
        return new BankManagerData(userMapData.namesOnly(), new BankAccountsData(new HashMap<>()), allowedItems, blacklistedItems);
    }

    /**
     * @param bankAccountMap Map of bank account numbers to their data.
     *                       The key is the account number, and the value is the BankAccountData.
     */
    public record BankAccountsData(Map<Integer, BankAccountData> bankAccountMap) {
        public static final StreamCodec<RegistryFriendlyByteBuf, BankAccountsData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(ByteBufCodecs.INT, BankAccountData.STREAM_CODEC, HashMap::new), p -> p.bankAccountMap,
                BankAccountsData::new
        );

    }


    public static final StreamCodec<RegistryFriendlyByteBuf, BankManagerData> STREAM_CODEC = StreamCodec.composite(
            UserMapData.STREAM_CODEC, p -> p.userMapData,
            BankAccountsData.STREAM_CODEC, p -> p.bankAccountsData,
            ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC), p -> p.allowedItems,
            ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC), p -> p.blacklistedItems,
            BankManagerData::new
    );

    public List<ItemStack> getAllowedItemStacks() {
        return allowedItems.stream()
                .map(ItemID::getStack)
                .toList();
    }
}
