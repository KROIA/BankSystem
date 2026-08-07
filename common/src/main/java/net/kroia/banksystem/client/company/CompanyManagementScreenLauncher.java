package net.kroia.banksystem.client.company;

import net.minecraft.client.Minecraft;

/**
 * Task #51 (v2.0.8) — thin client-side facade shielding packet code from the
 * (still-being-built) CompanyManagementScreen. Isolating the setScreen call here
 * lets the packet compile even if the screen class is temporarily missing during
 * feature slicing.
 */
public final class CompanyManagementScreenLauncher {

    private CompanyManagementScreenLauncher() {}

    public static void open(int companyId, String companyName) {
        try {
            Minecraft.getInstance().setScreen(
                    new net.kroia.banksystem.screen.custom.CompanyManagementScreen(companyId, companyName));
        } catch (NoClassDefFoundError e) {
            System.err.println("[BankSystem] CompanyManagementScreen not available on client: " + e);
        } catch (Throwable t) {
            System.err.println("[BankSystem] Failed to open CompanyManagementScreen for #"
                    + companyId + " '" + companyName + "': " + t);
        }
    }
}
