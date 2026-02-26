package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents data about a bank item
 * This class is used to transfer user bank data from the server to the client.
 */
public class ItemInfoData  {

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemInfoData> STREAM_CODEC = StreamCodec.composite(
            ItemID.STREAM_CODEC, p -> p.itemID,
            ByteBufCodecs.DOUBLE, p -> p.totalSupply,
            ByteBufCodecs.DOUBLE, p -> p.totalLocked,
            ExtraCodecUtils.listStreamCodec(BankAccountData.STREAM_CODEC), p -> p.bankAccounts,
            ByteBufCodecs.INT, p -> p.itemFractionScaleFactor,
            ItemInfoData::new
    );

    public final ItemID itemID;
    public final double totalSupply;
    public final double totalLocked;
    public final List<BankAccountData> bankAccounts;
    public final int itemFractionScaleFactor;


    public ItemInfoData(ItemID itemID,
                        double totalSupply,
                        double totalLocked,
                        List<BankAccountData> bankAccounts,
                        int itemFractionScaleFactor) {
        this.itemID = itemID;
        this.totalSupply = totalSupply;
        this.totalLocked = totalLocked;
        this.bankAccounts = bankAccounts;
        this.itemFractionScaleFactor = itemFractionScaleFactor;
    }

    /*@Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeItem(itemID.getStack());
        buf.writeDouble(totalSupply);
        buf.writeDouble(totalLocked);
        buf.writeInt(itemFractionScaleFactor);
        buf.writeInt(bankAccounts.size());
        for(BankAccountData bankAccount : bankAccounts)
        {
            bankAccount.encode(buf);
        }
    }

    public static ItemInfoData decode(FriendlyByteBuf buf) {
        ItemID itemID = new ItemID(buf.readItem());
        double totalSupply = buf.readDouble();
        double totalLocked = buf.readDouble();
        int itemFractionScaleFactor = buf.readInt();
        int size = buf.readInt();
        List<BankAccountData> bankAccounts = new ArrayList<>(size);
        for(int i = 0; i < size; i++)
        {
            bankAccounts.add(BankAccountData.decode(buf));
        }
        return new ItemInfoData(itemID, totalSupply, totalLocked, bankAccounts, itemFractionScaleFactor);
    }*/
}
