package net.kroia.banksystem.banking.clientdata;


import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BankManagerData(UserMapData userMapData, BankAccountsData bankAccountsData, List<ItemID> allowedItems,
                              List<ItemID> blacklistedItems, List<ItemID> notRemovableItems)
{
    public record UserMapData(Map<UUID, UserData> userMap) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UserMapData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, UserData.STREAM_CODEC, HashMap::new), p -> p.userMap,
                UserMapData::new
        );
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
            ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC), p -> p.notRemovableItems,
            BankManagerData::new
    );

    public List<ItemStack> getAllowedItemStacks() {
        return allowedItems.stream()
                .map(ItemID::getStack)
                .toList();
    }
}
