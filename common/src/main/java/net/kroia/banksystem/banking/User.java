package net.kroia.banksystem.banking;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class User implements ServerSaveable {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        User.BACKEND_INSTANCES = backend;
    }

    private UUID userUUID;
    private String userName;
    private boolean enableBankNotifications = true;

    private User()
    {

    }
    public User(UUID userUUID, String userName, boolean enableBankNotifications) {
        this.userUUID = userUUID;
        this.userName = userName;
        this.enableBankNotifications = enableBankNotifications;
    }
    public static @Nullable User createFromTag(CompoundTag tag)
    {
        User user = new User();
        if(!user.load(tag)) {
            return null; // Invalid data
        }
        return user;
    }

    public UserData getUserData() {
        return new UserData(userUUID, userName, enableBankNotifications);
    }

    public UUID getUUID() {
        return userUUID;
    }
    public String getName() {
        return userName;
    }
    public boolean isEnableBankNotifications() {
        return enableBankNotifications;
    }
    public void setEnableBankNotifications(boolean enableBankNotifications) {
        this.enableBankNotifications = enableBankNotifications;
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putUUID("userUUID", userUUID);
        tag.putString("userName", userName);
        tag.putBoolean("enableBankNotifications", enableBankNotifications);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if(!tag.contains("userUUID") || !tag.contains("userName")) {
            return false; // Invalid data
        }
        this.userUUID = tag.getUUID("userUUID");
        this.userName = tag.getString("userName");
        this.enableBankNotifications = tag.getBoolean("enableBankNotifications");
        return true;
    }

    public JsonElement toJson()
    {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("userUUID", userUUID.toString());
        jsonObject.addProperty("userName", userName);
        jsonObject.addProperty("enableBankNotifications", enableBankNotifications);
        return jsonObject;
    }
    @Override
    public String toString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }
}
