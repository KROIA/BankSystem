package net.kroia.banksystem.api;

import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public interface BankAPI {
    enum BankType
    {
        MONEY,
        ITEM
    }
    enum Status
    {
        SUCCESS,
        FAILED_NOT_ENOUGH_FUNDS,
        FAILED_OVERFLOW,
        FAILED_NEGATIVE_VALUE,
        FAILED_WRONG_INSTANCE_TYPE
    }



    long getBalance();
    long getLockedBalance();

    long getTotalBalance();
    ItemID getItemID();
    String getItemName();

    boolean setBalance(long balance);

    UUID getPlayerUUID();
    ServerPlayer getUser();

    String getPlayerName();


    Status deposit(long amount);


    boolean hasSufficientFunds(long amount);

    Status withdraw(long amount);
    Status withdrawLocked(long amount);
    Status withdrawLockedPrefered(long amount);
    Status transfer(long amount, BankAPI other);
    Status transferFromLocked(long amount, BankAPI other);
    Status transferFromLockedPrefered(long amount, BankAPI other);

    Status lockAmount(long amount);
    Status unlockAmount(long amount) ;
    void unlockAll();
    String toString();
    String toStringNoOwner();
}
