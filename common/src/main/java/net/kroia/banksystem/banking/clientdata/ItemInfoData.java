package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankUser;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents data about a bank item
 * This class is used to transfer user bank data from the server to the client.
 */
public class ItemInfoData implements INetworkPayloadEncoder {

    public final ItemID itemID;
    public final long totalSupply;
    public final long totalLocked;
    public final List<MinimalBankData> playerBanks;
    public final int itemFractionScaleFactor;


    public ItemInfoData(ServerBankManager manager, ItemID itemID)
    {
        Map<UUID, IBankUser> users = manager.getUser();
        long totalLocked = 0;
        long totalSupply = 0;
        this.playerBanks = new ArrayList<>();
        for(UUID player : users.keySet())
        {
            IBankUser user = users.get(player);
            if(user == null)
                continue;
            IBank bankAccount = user.getBank(itemID);
            if(bankAccount == null)
                continue;
            long balance = bankAccount.getBalance();
            long lockedBalance = bankAccount.getLockedBalance();
            totalLocked += lockedBalance;
            totalSupply += balance + lockedBalance;
            this.playerBanks.add(bankAccount.getMinimalData());
        }

        this.itemID = itemID;
        this.totalSupply = totalSupply;
        this.totalLocked = totalLocked;
        this.itemFractionScaleFactor = manager.getItemFractionScaleFactor(itemID);
    }
    public ItemInfoData(ItemID itemID,
                        long totalSupply,
                        long totalLocked,
                        List<MinimalBankData> playerBanks,
                        int itemFractionScaleFactor) {
        this.itemID = itemID;
        this.totalSupply = totalSupply;
        this.totalLocked = totalLocked;
        this.playerBanks = playerBanks;
        this.itemFractionScaleFactor = itemFractionScaleFactor;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeItem(itemID.getStack());
        buf.writeLong(totalSupply);
        buf.writeLong(totalLocked);
        buf.writeInt(itemFractionScaleFactor);
        buf.writeInt(playerBanks.size());
        for(MinimalBankData bank : playerBanks)
        {
            bank.encode(buf);
        }
    }

    public static ItemInfoData decode(FriendlyByteBuf buf) {
        ItemID itemID = new ItemID(buf.readItem());
        long totalSupply = buf.readLong();
        long totalLocked = buf.readLong();
        int itemFractionScaleFactor = buf.readInt();
        int size = buf.readInt();
        List<MinimalBankData> playerBanks = new ArrayList<>(size);
        for(int i = 0; i < size; i++)
        {
            playerBanks.add(MinimalBankData.decode(buf));
        }
        return new ItemInfoData(itemID, totalSupply, totalLocked, playerBanks, itemFractionScaleFactor);
    }
}
