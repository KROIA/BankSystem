package net.kroia.banksystem.banking;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class User implements ServerSaveable {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        User.BACKEND_INSTANCES = backend;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, User> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, p -> p.userUUID,
            ByteBufCodecs.STRING_UTF8, p -> p.userName,
            ByteBufCodecs.BOOL, p -> p.enableBankNotifications,
            ByteBufCodecs.BOOL, p -> p.isBankModAdmin,
            User::new
    );

    private UUID userUUID;
    private String userName;
    private boolean enableBankNotifications = true;
    private boolean isBankModAdmin = false;

    private User()
    {

    }
    private User(UUID userUUID, String userName, boolean enableBankNotifications, boolean isBankModAdmin)
    {
        this.userUUID = userUUID;
        this.userName = userName;
        this.enableBankNotifications = enableBankNotifications;
        this.isBankModAdmin = isBankModAdmin;
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
    public boolean isBankModAdmin() {
        return isBankModAdmin;
    }
    public void setBankModAdmin(boolean isBankModAdmin) {
        this.isBankModAdmin = isBankModAdmin;
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putUUID("userUUID", userUUID);
        tag.putString("userName", userName);
        tag.putBoolean("enableBankNotifications", enableBankNotifications);
        tag.putBoolean("isBankModAdmin", isBankModAdmin);
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
        if(tag.contains("isBankModAdmin"))
            this.isBankModAdmin = tag.getBoolean("isBankModAdmin");
        else
            this.isBankModAdmin = false;
        return true;
    }

    public JsonElement toJson()
    {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("userUUID", userUUID.toString());
        jsonObject.addProperty("userName", userName);
        jsonObject.addProperty("enableBankNotifications", enableBankNotifications);
        jsonObject.addProperty("isBankModAdmin", isBankModAdmin);
        return jsonObject;
    }
    @Override
    public String toString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }
}
