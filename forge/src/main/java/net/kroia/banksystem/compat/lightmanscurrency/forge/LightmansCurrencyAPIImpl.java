package net.kroia.banksystem.compat.lightmanscurrency.forge;

import net.kroia.banksystem.compat.lightmanscurrency.ILightmansBank;

import java.util.UUID;

public class LightmansCurrencyAPIImpl {


    public static ILightmansBank createBank(UUID playerUUID)
    {
        return new LightmansBankImpl(playerUUID);
    }
}
