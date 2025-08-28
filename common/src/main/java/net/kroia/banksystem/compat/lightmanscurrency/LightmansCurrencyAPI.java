package net.kroia.banksystem.compat.lightmanscurrency;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.kroia.modutilities.ModChecker;

import java.util.UUID;

public class LightmansCurrencyAPI {

    public LightmansBankManager MANAGER = null;
    private final boolean isAvailable;
    public boolean isAvailable() { return isAvailable; }

    public LightmansCurrencyAPI()
    {
        if(ModChecker.isModLoaded("lightmanscurrency"))
        {
            MANAGER = new LightmansBankManager();
            isAvailable = true;
        }
        else
        {
            isAvailable = false;
        }
    }

    /**
     * See the platform-specific implementation for details.
     * forge.src.main.java.net.kroia.banksystem.compat.lightmanscurrency.forge
     * @param playerUUID
     * @return An ILightmansBank instance for the given player UUID,
     * or null if the player does not have a bank account or the mod is not supported on this platform.
     */
    @ExpectPlatform
    public static ILightmansBank createBank(UUID playerUUID)
    {
        throw new AssertionError();
    }
}
