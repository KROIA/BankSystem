package net.kroia.banksystem.banking;

import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.bank.ItemBank;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BankUser implements ServerSaveable {
    private UUID userUUID;
    private String userName;
    private final HashMap<ItemID, Bank> bankMap = new HashMap<>();
    private boolean enableBankNotifications = true;

    public BankUser(ServerPlayer player)
    {
        this(player.getUUID(), player.getName().getString());
    }
    public BankUser(UUID userUUID, String userName) {
        this.userUUID = userUUID;
        this.userName = userName;
    }

    private BankUser()
    {

    }
    public static BankUser loadFromTag(CompoundTag tag)
    {
        BankUser user = new BankUser();
        if(user.load(tag))
            return user;
        return null;
    }

    public Bank createMoneyBank(long startBalance)
    {
        Bank bank = getBank(MoneyBank.ITEM_ID);
        if(bank != null)
            return bank;
        bank = new MoneyBank(this, startBalance);
        bankMap.put(MoneyBank.ITEM_ID, bank);
        return bank;
    }
    public Bank createItemBank(ItemID itemID, long startBalance)
    {
        Bank bank = getBank(itemID);
        if(bank != null)
            return bank;
        if(!ServerBankManager.isItemIDAllowed(itemID))
        {
            PlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getItemNotAllowedMessage(itemID.getName()));
            return null;
        }
        bank = new ItemBank(this, itemID,  startBalance);
        bankMap.put(itemID, bank);
        return bank;
    }
    public Bank createItemBank_noMSG_Feedback(ItemID itemID, long startBalance)
    {
        Bank bank = getBank(itemID);
        if(bank != null)
            return bank;
        if(!ServerBankManager.isItemIDAllowed(itemID))
        {
            return null;
        }
        bank = new ItemBank(this, itemID,  startBalance);
        bankMap.put(itemID, bank);
        return bank;
    }

    public Bank getBank(ItemID itemID)
    {
        return bankMap.get(itemID);
    }
    public boolean removeBank(ItemID itemID)
    {
        Bank bank = bankMap.get(itemID);
        if(bank == null)
            return bankMap.remove(itemID) != null;

        PlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getBankDeletedMessage(getPlayerName(), bank.getItemName())+"\n"+
                BankSystemTextMessages.getBankBalanceLostMessage(bank.getTotalBalance(), bank.getItemName()));
        return bankMap.remove(itemID) != null;
    }
    public Bank getMoneyBank()
    {
        return bankMap.get(MoneyBank.ITEM_ID);
    }
    public long getMoneyBalance()
    {
        Bank bank = getMoneyBank();
        if(bank != null)
            return bank.getBalance();
        return 0;
    }

    public long getTotalMoneyBalance()
    {
        Bank bank = getMoneyBank();
        if(bank != null)
            return bank.getTotalBalance();
        return 0;
    }

    public HashMap<ItemID, Bank> getBankMap()
    {
        return bankMap;
    }
    public boolean isBankNotificationEbabled()
    {
        return enableBankNotifications;
    }
    public void setBankNotificationEnabled(boolean enabled)
    {
        enableBankNotifications = enabled;
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putUUID("userUUID", userUUID);
        tag.putString("userName", userName);
        tag.putBoolean("enableBankNotifications", enableBankNotifications);


        ListTag bankElements = new ListTag();
        for (Map.Entry<ItemID, Bank> entry : bankMap.entrySet()) {
            CompoundTag bankTag = new CompoundTag();
            entry.getValue().save(bankTag);
            bankElements.add(bankTag);
        }
        tag.put("bankMap", bankElements);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        boolean loadSuccess = true;
        userUUID = tag.getUUID("userUUID");
        userName = tag.getString("userName");
        if(tag.contains("enableBankNotifications"))
            enableBankNotifications = tag.getBoolean("enableBankNotifications");
        else
            enableBankNotifications = true;


        ListTag bankElements = tag.getList("bankMap", 10);
        bankMap.clear();
        for (int i = 0; i < bankElements.size(); i++) {
            CompoundTag bankTag = bankElements.getCompound(i);
            Bank bank = Bank.loadFromTag(this, bankTag);
            if(bank != null)
                bankMap.put(bank.getItemID(), bank);
            else
                loadSuccess = false;
        }
        return loadSuccess;
    }

    public UUID getPlayerUUID() {
        return userUUID;
    }
    public ServerPlayer getPlayer()
    {
        return PlayerUtilities.getOnlinePlayer(userUUID);
    }

    public String getPlayerName()
    {
        ServerPlayer player = getPlayer();
        if(player != null) {
            userName = player.getName().getString();
        }
        if(userName == null)
            return "UnknownUserName";
        return userName;
    }

    public String toString()
    {
        String owner = getPlayerName();
        StringBuilder content = new StringBuilder(BankSystemTextMessages.getBankOfMessage(owner) + "\n");
        ArrayList<String> itemNames = new ArrayList<>();
        ArrayList<String> itemBalances = new ArrayList<>();

        if(bankMap.containsKey(MoneyBank.ITEM_ID)) {
            itemNames.add(bankMap.get(MoneyBank.ITEM_ID).getItemName());
            itemBalances.add(String.valueOf(bankMap.get(MoneyBank.ITEM_ID).getBalance()));
        }

        for(Bank bank : bankMap.values())
        {
            if(bank.getItemID().equals(MoneyBank.ITEM_ID))
                continue;
            itemNames.add(bank.getItemName());
            itemBalances.add(String.valueOf(bank.getTotalBalance()));
        }
        int maxAmountLength = 0;
        for(String itemName : itemBalances)
        {
            if(itemName.length() > maxAmountLength)
                maxAmountLength = itemName.length();
        }
        for(int i=0; i<itemNames.size(); i++)
        {

            content.append(" | ");
            for(int j=0; j<=maxAmountLength-itemBalances.get(i).length(); j++)
                content.append("_");
            content.append(itemBalances.get(i)).append(" ");

            content.append(" ").append(itemNames.get(i)).append("\n");


        }
        return content.toString();
    }
}
