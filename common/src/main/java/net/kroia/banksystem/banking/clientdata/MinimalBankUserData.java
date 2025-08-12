package net.kroia.banksystem.banking.clientdata;

/**
 * Represents minimal bank user data for a player.
 * This class is used to transfer user bank data from the server to the client.
 */

/*
public class MinimalBankUserData implements INetworkPayloadEncoder {

    public final UUID userUUID;
    public final String userName;
    public final HashMap<ItemID, BankData> bankMap;
    public final boolean enableBankNotifications;


    public MinimalBankUserData(IBankUserOld bankUserAPI) {
        this.userUUID = bankUserAPI.getPlayerUUID();
        this.userName = bankUserAPI.getPlayerName();
        this.bankMap = new HashMap<>();
        HashMap<ItemID, IBank> bankDataMap = bankUserAPI.getAllBanks();
        for (var entry : bankDataMap.entrySet()) {
            ItemID bankID = entry.getKey();
            IBank bank = entry.getValue();
            BankData bankData = bank.getMinimalData();
            this.bankMap.put(bankID, bankData);
        }
        this.enableBankNotifications = bankUserAPI.isBankNotificationEnabled();
    }
    public MinimalBankUserData(UUID userUUID,
                               String userName,
                               HashMap<ItemID, BankData> bankMap,
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
            BankData bankData = entry.getValue();
            buf.writeItem(bankID.getStack());
            bankData.encode(buf);
        }
    }
    public static MinimalBankUserData decode(net.minecraft.network.FriendlyByteBuf buf) {
        UUID userUUID = buf.readUUID();
        String userName = buf.readUtf();
        boolean enableBankNotifications = buf.readBoolean();
        int bankCount = buf.readInt();
        HashMap<ItemID, BankData> bankMap = new HashMap<>();
        for (int i = 0; i < bankCount; i++) {
            ItemID bankID = new ItemID(buf.readItem());
            BankData bankData = BankData.decode(buf);
            bankMap.put(bankID, bankData);
        }
        return new MinimalBankUserData(userUUID, userName, bankMap, enableBankNotifications);
    }

}*/
