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
        jsonObject.addProperty("permissionValue", permission);
        return jsonObject;
    }

    /**
     * Deserializes a BankUser from a JsonObject previously produced by {@link #toJson()}.
     *
     * @param jsonObject the JSON representation of a BankUser
     * @return the deserialized BankUser, or {@code null} if required fields are missing
     */
    public static BankUser fromJson(JsonObject jsonObject)
    {
        if(!jsonObject.has("user") || !jsonObject.has("permissionValue"))
            return null;

        JsonObject userJson = jsonObject.getAsJsonObject("user");
        if(!userJson.has("userUUID") || !userJson.has("userName"))
            return null;

        UUID uuid = UUID.fromString(userJson.get("userUUID").getAsString());
        String name = userJson.get("userName").getAsString();
        boolean enableNotifications = userJson.has("enableBankNotifications") && userJson.get("enableBankNotifications").getAsBoolean();

        User user = new User(uuid, name, enableNotifications);
        int permissionValue = jsonObject.get("permissionValue").getAsInt();
        return new BankUser(user, permissionValue);
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
