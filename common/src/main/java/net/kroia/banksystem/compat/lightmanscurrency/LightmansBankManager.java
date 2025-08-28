package net.kroia.banksystem.compat.lightmanscurrency;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LightmansBankManager {

    private final Map<UUID, ILightmansBank> banks = new HashMap<>();


    public @Nullable ILightmansBank getBank(UUID playerUUID) {
        ILightmansBank bank = banks.get(playerUUID);
        if(bank == null) {
            bank = LightmansCurrencyAPI.createBank(playerUUID);
            if(bank != null)
                banks.put(playerUUID, bank);
        }
        return bank;
    }
}
