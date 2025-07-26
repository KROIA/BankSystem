package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankUser;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;

import java.util.HashMap;
import java.util.UUID;

/**
 * Represents minimal bank user data for a player.
 * This class is used to transfer user bank data from the server to the client.
 */
public class MinimalBankUserData implements INetworkPayloadEncoder {

    public final UUID userUUID;
    public final String userName;
    public final HashMap<ItemID, MinimalBankData> bankMap;
    public final boolean enableBankNotifications;


    public MinimalBankUserData(IBankUser bankUserAPI) {
        this.userUUID = bankUserAPI.getPlayerUUID();
        this.userName = bankUserAPI.getPlayerName();
        this.bankMap = new HashMap<>();
        HashMap<ItemID, IBank> bankDataMap = bankUserAPI.getBankMap();
        for (var entry : bankDataMap.entrySet()) {
            ItemID bankID = entry.getKey();
            IBank bank = entry.getValue();
            MinimalBankData minimalBankData = bank.getMinimalData();
            this.bankMap.put(bankID, minimalBankData);
        }
        this.enableBankNotifications = bankUserAPI.isBankNotificationEbabled();
    }
    public MinimalBankUserData(UUID userUUID,
                               String userName,
                               HashMap<ItemID, MinimalBankData> bankMap,
                               boolean enableBankNotifications) {
        this.userUUID = userUUID;
        this.userName = userName;
        this.bankMap = bankMap;
        this.enableBankNotifications = enableBankNotifications;
    }

    @Override
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeUUID(userUUID);
        buf.writeUtf(userName);
        buf.writeBoolean(enableBankNotifications);
        buf.writeInt(bankMap.size());
        for (var entry : bankMap.entrySet()) {
            ItemID bankID = entry.getKey();
            MinimalBankData minimalBankData = entry.getValue();
            buf.writeItem(bankID.getStack());
            minimalBankData.encode(buf);
        }
    }
    public static MinimalBankUserData decode(net.minecraft.network.FriendlyByteBuf buf) {
        UUID userUUID = buf.readUUID();
        String userName = buf.readUtf();
        boolean enableBankNotifications = buf.readBoolean();
        int bankCount = buf.readInt();
        HashMap<ItemID, MinimalBankData> bankMap = new HashMap<>();
        for (int i = 0; i < bankCount; i++) {
            ItemID bankID = new ItemID(buf.readItem());
            MinimalBankData minimalBankData = MinimalBankData.decode(buf);
            bankMap.put(bankID, minimalBankData);
        }
        return new MinimalBankUserData(userUUID, userName, bankMap, enableBankNotifications);
    }

}
