package net.kroia.banksystem.compat.lightmanscurrency;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

public class NoBankImpl implements ILightmansBank {
    //@Override
    //public long getBalance(UUID player) { return 0; }
//
    //@Override
    //public boolean deposit(UUID player, long amount) { return false; }
//
    //@Override
    //public boolean withdraw(UUID player, long amount) { return false; }

    private static final ItemID DEFAULT_ITEM = ItemID.of(Items.AIR.getDefaultInstance());

    @Override
    public int getItemFractionScaleFactor() {
        return 1;
    }

    @Override
    public BankData getMinimalData() {
        return new BankData(DEFAULT_ITEM, 1);
    }

    @Override
    public long getBalance() {
        return 0;
    }

    @Override
    public long getLockedBalance() {
        return 0;
    }

    @Override
    public long getTotalBalance() {
        return 0;
    }

    @Override
    public float getRealBalance() {
        return 0;
    }

    @Override
    public float getRealLockedBalance() {
        return 0;
    }

    @Override
    public float getRealTotalBalance() {
        return 0;
    }

    @Override
    public ItemID getItemID() {
        return DEFAULT_ITEM;
    }

    @Override
    public String getItemName() {
        return DEFAULT_ITEM.getName();
    }

    @Override
    public boolean setBalance(long balance) {
        return false;
    }

    @Override
    public boolean setRealBalance(float balance) {
        return false;
    }

    @Override
    public Status deposit(long amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status depositReal(float amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public boolean hasSufficientFunds(long amount) {
        return false;
    }

    @Override
    public Status withdraw(long amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status withdrawReal(float amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status withdrawLocked(long amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status withdrawLockedReal(float amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status withdrawLockedPrefered(long amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status withdrawLockedPreferedReal(float amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status transfer(long amount, IBank other) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status transferReal(float amount, @NotNull IBank other) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status transferFromLocked(long amount, @NotNull IBank other) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status transferFromLockedReal(float amount, @NotNull IBank other) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status transferFromLockedPrefered(long amount, @NotNull IBank other) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status transferFromLockedPreferedReal(float amount, @NotNull IBank other) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status lockAmount(long amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status lockAmountReal(float amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status unlockAmount(long amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public Status unlockAmountReal(float amount) {
        return Status.FAILED_NO_BANK;
    }

    @Override
    public void unlockAll() {

    }

    @Override
    public long convertToRawAmount(float realAmount) {
        return 0;
    }

    @Override
    public float convertToRealAmount(long rawAmount) {
        return 0;
    }

    @Override
    public String getNormalizedBalance() {
        return "0";
    }

    @Override
    public String getNormalizedLockedBalance() {
        return "0";
    }

    @Override
    public String getNormalizedTotalBalance() {
        return "0";
    }

    @Override
    public String getFormattedBalance() {
        return "0";
    }

    @Override
    public String getFormattedLockedBalance() {
        return "0";
    }

    @Override
    public String getFormattedTotalBalance() {
        return "0";
    }

    @Override
    public String getNormalizedAmount(float realAmount) {
        return "0";
    }

    @Override
    public String getNormalizedAmount(double realAmount) {
        return "0";
    }

    @Override
    public String getNormalizedAmount(long rawAmount) {
        return "0";
    }

    @Override
    public String getFormattedAmount(float realAmount) {
        return "0";
    }

    @Override
    public String getFormattedAmount(double realAmount) {
        return "0";
    }

    @Override
    public String getFormattedAmount(long rawAmount) {
        return "0";
    }

    @Override
    public String toStringNoOwner() {
        return "0";
    }

    @Override
    public JsonElement toJson() {
        return new JsonObject();
    }

    @Override
    public String toJsonString() {
        return "{}";
    }
}