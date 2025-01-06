package net.kroia.banksystem.networking;

import dev.architectury.networking.simple.MessageType;
import dev.architectury.networking.simple.SimpleNetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packet.client_sender.request.*;
import net.kroia.banksystem.networking.packet.client_sender.update.UpdateBankAccountPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncPotentialBankItemIDsPacket;


public class BankSystemNetworking {

    public static final SimpleNetworkManager CHANNEL = SimpleNetworkManager.create(BankSystemMod.MOD_ID);


    public static MessageType SYNC_BANK_DATA = CHANNEL.registerS2C(getClassName(SyncBankDataPacket.class.getName()), SyncBankDataPacket::new);
    public static MessageType SYNC_POTENTIAL_BANK_ITEM_IDS = CHANNEL.registerS2C(getClassName(SyncPotentialBankItemIDsPacket.class.getName()), SyncPotentialBankItemIDsPacket::new);
    public static MessageType SYNC_OPEN_GUI = CHANNEL.registerS2C(getClassName(SyncOpenGUIPacket.class.getName()), SyncOpenGUIPacket::new);
    public static MessageType SYNC_ITEM_INFO = CHANNEL.registerS2C(getClassName(SyncItemInfoPacket.class.getName()), SyncItemInfoPacket::new);



    public static MessageType REQUEST_ALLOW_NEW_BANK_ITEM_ID = CHANNEL.registerC2S(getClassName(RequestAllowNewBankItemIDPacket.class.getName()), RequestAllowNewBankItemIDPacket::new);
    public static MessageType REQUEST_BANK_DATA = CHANNEL.registerC2S(getClassName(RequestBankDataPacket.class.getName()), RequestBankDataPacket::new);
    public static MessageType REQUEST_DISALLOW_BANKING_ITEM_ID = CHANNEL.registerC2S(getClassName(RequestDisallowBankingItemIDPacket.class.getName()), RequestDisallowBankingItemIDPacket::new);
    public static MessageType REQUEST_POTENTIAL_BANK_ITEM_IDS = CHANNEL.registerC2S(getClassName(RequestPotentialBankItemIDsPacket.class.getName()), RequestPotentialBankItemIDsPacket::new);
    public static MessageType UPDATE_BANK_ACCOUNT = CHANNEL.registerC2S(getClassName(UpdateBankAccountPacket.class.getName()), UpdateBankAccountPacket::new);
    public static MessageType UPDATE_BANK_TERMINAL_BLOCK_ENTITY = CHANNEL.registerC2S(getClassName(UpdateBankTerminalBlockEntityPacket.class.getName()), UpdateBankTerminalBlockEntityPacket::new);
    public static MessageType REQUEST_ITEM_INFO = CHANNEL.registerC2S(getClassName(RequestItemInfoPacket.class.getName()), RequestItemInfoPacket::new);



    public static void init() {

    }
    private static String getClassName(String name) {
        String sub = name.substring(name.lastIndexOf(".")+1).toLowerCase();
        return sub;
    }
}
