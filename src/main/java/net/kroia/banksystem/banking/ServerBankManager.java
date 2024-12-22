package net.kroia.banksystem.banking;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.modutilities.ClientInteraction;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ServerBankManager implements ServerSaveable {

    private static Map<UUID, BankUser> userMap = new HashMap<>();
    public static BankUser createUser(UUID userUUID, String userName, ArrayList<String> itemIDs, boolean createMoneyBank, long startMoney)
    {
        BankUser user = userMap.get(userUUID);
        if(user != null)
            return user;
        user = new BankUser(userUUID, userName);
        for(String itemID : itemIDs)
            user.createItemBank(itemID, 0);
        if(createMoneyBank)
            user.createMoneyBank(startMoney);
        ClientInteraction.printToClientConsole(userUUID, "A bank account has been created for you.\n" +
                "You can access your account using the Bank Terminal block\nor the /bank command.");
        userMap.put(userUUID, user);
        return user;
    }

    public static BankUser getUser(UUID userUUID)
    {
        return userMap.get(userUUID);
    }
    public static void clear()
    {
        userMap.clear();
    }

    public static Bank getMoneyBank(UUID userUUID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getMoneyBank();
    }

    public static long getMoneyCirculation()
    {
        long total = 0;
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            total += entry.getValue().getTotalMoneyBalance();
        }
        return total;
    }


    public static boolean saveToTag(CompoundTag tag)
    {
        ServerBankManager tmp = new ServerBankManager();
        return tmp.save(tag);
    }
    @Override
    public boolean save(CompoundTag tag) {
        ListTag bankElements = new ListTag();
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            CompoundTag bankTag = new CompoundTag();
            entry.getValue().save(bankTag);
            bankElements.add(bankTag);
        }
        tag.put("users", bankElements);
        return true;
    }

    public static boolean loadFromTag(CompoundTag tag)
    {
        ServerBankManager tmp = new ServerBankManager();
        return tmp.load(tag);
    }
    @Override
    public boolean load(CompoundTag tag) {
        boolean success = true;

        ListTag bankElements = tag.getList("users", 10);
        userMap.clear();
        for (int i = 0; i < bankElements.size(); i++) {
            CompoundTag bankTag = bankElements.getCompound(i);
            BankUser user = BankUser.loadFromTag(bankTag);
            if(user == null)
            {
                success = false;
                continue;
            }
            userMap.put(user.getOwnerUUID(), user);
        }
        return success;
    }

    /*public static void handlePacket(ServerPlayer sender, RequestBankDataPacket packet)
    {
        SyncBankDataPacket.sendPacket(sender);
    }*/
}
