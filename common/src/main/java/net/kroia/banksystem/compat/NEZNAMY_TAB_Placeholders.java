package net.kroia.banksystem.compat;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.placeholder.PlaceholderManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.IBank;

import java.util.UUID;

public class NEZNAMY_TAB_Placeholders {

    public static void register() {
        // Placeholder for NEZNAMY_TAB integration
        // This method can be used to register placeholders or other integration features

        TabAPI tab = TabAPI.getInstance();
        if(tab==null)
            return;

        PlaceholderManager pm = tab.getPlaceholderManager();
        pm.registerPlayerPlaceholder("%banksystem_balance%", 1000, (playerTab) -> {
            UUID playerUUID = playerTab.getUniqueId();
            IBank bank = BankSystemMod.getAPI().getServerBankManager().getMoneyBank(playerUUID);
            if(bank != null)
            {
                return bank.getNormalizedAmount(bank.getBalance());
            }
            return "0";
        });
    }
}
