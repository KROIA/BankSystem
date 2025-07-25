package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.UUID;

public interface BankUserAPI {
    Bank createMoneyBank(long startBalance);
    Bank createItemBank(ItemID itemID, long startBalance, boolean notifyPlayerOnFail);
    Bank getBank(ItemID itemID);
    HashMap<ItemID, Bank> getAllBanks();
    boolean removeBank(ItemID itemID);
    Bank getMoneyBank();
    long getMoneyBalance();
    long getTotalMoneyBalance();
    HashMap<ItemID, Bank> getBankMap();
    boolean isBankNotificationEbabled();
    void setBankNotificationEnabled(boolean enabled);
    UUID getPlayerUUID();
    ServerPlayer getPlayer();
    String getPlayerName();
    String toString();
}
