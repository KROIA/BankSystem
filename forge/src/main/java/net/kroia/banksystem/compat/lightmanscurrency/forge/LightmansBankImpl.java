package net.kroia.banksystem.compat.lightmanscurrency.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyStorage;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.common.bank.BankAccount;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.compat.lightmanscurrency.ILightmansBank;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;


public class LightmansBankImpl implements ILightmansBank {

    private static final ItemID DEFAULT_ITEM = ItemID.of(Items.AIR.getDefaultInstance());

    private final UUID playerUUID;
    /*@Override
    public long getBalance(UUID player) {

        BankAccount account = this.findAccount(player);
        if(account == null)
            return 0;

        MoneyStorage storage = account.getMoneyStorage();
        List<MoneyValue> values = storage.allValues();
        long coreValueSum = 0;
        for(MoneyValue value : values)
        {
            coreValueSum += value.getCoreValue();
            //System.out.println("Corevalue for: "+value.getString() + " is "+value.getCoreValue());
        }
        return coreValueSum;
    }

    @Override
    public boolean deposit(UUID player, long amount) {
        return false;
    }

    @Override
    public boolean withdraw(UUID player, long amount) {
        BankAccount account = this.findAccount(player);
        if(account == null)
            return false;
        MoneyStorage storage = account.getMoneyStorage();
        return false; // Not implemented yet
    }


    */

    public LightmansBankImpl(UUID playerUUID)
    {
        this.playerUUID = playerUUID;
    }


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
        BankAccount account = this.findAccount();
        if(account == null)
            return 0;
        MoneyStorage storage = account.getMoneyStorage();
        List<MoneyValue> values = storage.allValues();
        long coreValueSum = 0;
        for(MoneyValue value : values)
        {
            coreValueSum += value.getCoreValue();
            //System.out.println("Corevalue for: "+value.getString() + " is "+value.getCoreValue());
        }
        return coreValueSum;
    }

    @Override
    public long getLockedBalance() {
        return 0;
    }

    @Override
    public long getTotalBalance() {
        return getBalance() + getLockedBalance();
    }

    @Override
    public float getRealBalance() {
        return getBalance();
    }

    @Override
    public float getRealLockedBalance() {
        return getLockedBalance();
    }

    @Override
    public float getRealTotalBalance() {
        return getTotalBalance();
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





    private @Nullable BankAccount findAccount()
    {
        List<IBankAccount> accounts = BankAPI.API.GetAllBankAccounts(false);
        for(IBankAccount account : accounts)
        {
            String owner = "";
            BankAccount bankAccount = null;
            if(account instanceof BankAccount ba)
            {
                owner = ba.getOwnersName(); // IBankAccount does not provide the getOwnersName method.
                bankAccount = ba;
            }
            User user = BankSystemMod.getAPI().getServerBankManager().getUserByUUID(playerUUID);
            String playerName = "";
            if(user == null)
            {
                ServerPlayer sp = ServerPlayerUtilities.getOnlinePlayer(playerUUID);
                if(sp != null)
                {
                    playerName = sp.getName().getString();
                }
            }
            else
                playerName = user.getName();
            if(owner.equals(playerName)) {
                return bankAccount;
            }
        }
        return null;
    }
}