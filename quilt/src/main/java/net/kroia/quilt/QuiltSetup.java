package net.kroia.quilt;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.kroia.banksystem.BankSystemMod;

public class QuiltSetup implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[QuiltSetup] Common setup for server.");
        BankSystemMod.onServerSetup();
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void onInitializeClient() {
        System.out.println("[QuiltSetup] Client setup.");
        BankSystemMod.onClientSetup();
    }
}