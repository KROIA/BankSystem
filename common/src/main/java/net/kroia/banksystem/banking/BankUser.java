package net.kroia.banksystem.banking;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.modutilities.JsonUtilities;

import java.util.UUID;

public class BankUser {

    public record BankUserSQL_Data(int bankAccountNr, UUID userUUID, int permission)
    {

    }

    private final User user;
    private int permission;

    public BankUser(User user, int permission) {
        this.user = user;
        this.permission = permission;
    }


    public BankUserData toBankUserData() {
        return new BankUserData(user.getUUID(), user.getName(), user.isEnableBankNotifications(), permission);
    }

    public User getUser() {
        return user;
    }
    public UUID getUUID() {
        return user.getUUID();
    }
    public int getPermission() {
        return permission;
    }
    public void setPermission(int permission) {
        this.permission = permission;
    }

    public JsonElement toJson()
    {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("user", user.toJson());
        jsonObject.addProperty("permission", BankPermission.toString(permission));
        return jsonObject;
    }
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }

    @Override
    public String toString() {
        return toJsonString();
    }
}
