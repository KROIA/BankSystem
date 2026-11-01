package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * Represents data about a bank item
 * This class is used to transfer user bank data from the server to the client.
 */
public record ItemInfoData(ItemID itemID, double totalSupply, double totalLocked, List<BankAccountData> bankAccounts) {

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemInfoData> STREAM_CODEC = StreamCodec.composite(
            ItemID.STREAM_CODEC, p -> p.itemID,
            ByteBufCodecs.DOUBLE, p -> p.totalSupply,
            ByteBufCodecs.DOUBLE, p -> p.totalLocked,
            ExtraCodecUtils.listStreamCodec(BankAccountData.STREAM_CODEC), p -> p.bankAccounts,
            ItemInfoData::new
    );

}
