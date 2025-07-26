package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

public interface IBankUser {

    MinimalBankUserData getMinimalData();
    @Nullable MinimalBankData getMinimalBankData(ItemID itemID);
    IBank createMoneyBank(long startBalance);
    IBank createItemBank(ItemID itemID, long startBalance, boolean notifyPlayerOnFail);
    @Nullable IBank getBank(ItemID itemID);
    HashMap<ItemID, IBank> getAllBanks();
    boolean removeBank(ItemID itemID);
    @Nullable IBank getMoneyBank();
    long getMoneyBalance();
    long getTotalMoneyBalance();
    HashMap<ItemID, IBank> getBankMap();
    boolean isBankNotificationEbabled();
    void setBankNotificationEnabled(boolean enabled);
    UUID getPlayerUUID();
    @Nullable ServerPlayer getPlayer();
    String getPlayerName();
    String toString();
}
