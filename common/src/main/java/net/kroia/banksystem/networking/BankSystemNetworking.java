package net.kroia.banksystem.networking;

import dev.architectury.impl.NetworkAggregator;
import dev.architectury.networking.simple.*;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packet.client_sender.request.*;
import net.kroia.banksystem.networking.packet.client_sender.update.UpdateBankAccountPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.*;

import java.util.List;


public class BankSystemNetworking {

    public static final SimpleNetworkManager CHANNEL = SimpleNetworkManager.create(BankSystemMod.MOD_ID);


    public static MessageType SYNC_BANK_DATA = registerS2C(getClassName(SyncBankDataPacket.class.getName()), SyncBankDataPacket::new);
    public static MessageType SYNC_POTENTIAL_BANK_ITEM_IDS = registerS2C(getClassName(SyncPotentialBankItemIDsPacket.class.getName()), SyncPotentialBankItemIDsPacket::new);
    public static MessageType SYNC_OPEN_GUI = registerS2C(getClassName(SyncOpenGUIPacket.class.getName()), SyncOpenGUIPacket::new);
    public static MessageType SYNC_ITEM_INFO = registerS2C(getClassName(SyncItemInfoPacket.class.getName()), SyncItemInfoPacket::new);
    public static MessageType SYNC_BANK_UPLOAD_DATA = registerS2C(getClassName(SyncBankUploadDataPacket.class.getName()), SyncBankUploadDataPacket::new);
    public static MessageType SYNC_BANK_DOWNLOAD_DATA = registerS2C(getClassName(SyncBankDownloadDataPacket.class.getName()), SyncBankDownloadDataPacket::new);



    public static MessageType REQUEST_ALLOW_NEW_BANK_ITEM_ID = registerC2S(getClassName(RequestAllowNewBankItemIDPacket.class.getName()), RequestAllowNewBankItemIDPacket::new);
    public static MessageType REQUEST_BANK_DATA = registerC2S(getClassName(RequestBankDataPacket.class.getName()), RequestBankDataPacket::new);
    public static MessageType REQUEST_DISALLOW_BANKING_ITEM_ID = registerC2S(getClassName(RequestDisallowBankingItemIDPacket.class.getName()), RequestDisallowBankingItemIDPacket::new);
    public static MessageType REQUEST_POTENTIAL_BANK_ITEM_IDS = registerC2S(getClassName(RequestPotentialBankItemIDsPacket.class.getName()), RequestPotentialBankItemIDsPacket::new);
    public static MessageType UPDATE_BANK_ACCOUNT = registerC2S(getClassName(UpdateBankAccountPacket.class.getName()), UpdateBankAccountPacket::new);
    public static MessageType UPDATE_BANK_TERMINAL_BLOCK_ENTITY = registerC2S(getClassName(UpdateBankTerminalBlockEntityPacket.class.getName()), UpdateBankTerminalBlockEntityPacket::new);
    public static MessageType REQUEST_ITEM_INFO = registerC2S(getClassName(RequestItemInfoPacket.class.getName()), RequestItemInfoPacket::new);
    public static MessageType UPDATE_BANK_UPLOAD_BLOCK_ENTITY = registerC2S(getClassName(UpdateBankUploadBlockEntityPacket.class.getName()), UpdateBankUploadBlockEntityPacket::new);
    public static MessageType UPDATE_BANK_DOWNLOAD_BLOCK_ENTITY = registerC2S(getClassName(UpdateBankDownloadBlockEntityPacket.class.getName()), UpdateBankDownloadBlockEntityPacket::new);



    public static void init() {

    }
    private static String getClassName(String name) {
        String sub = name.substring(name.lastIndexOf(".")+1).toLowerCase();
        return sub;
    }

    public static MessageType registerS2C(String name, MessageDecoder<BaseS2CMessage> decoder)
    {
        MessageType registeredMsg = CHANNEL.registerS2C(name, decoder);
        if (Platform.getEnvironment() == Env.SERVER)
        {
            NetworkAggregator.registerS2CType(registeredMsg.getId(), List.of());
        }
        return registeredMsg;
    }
    public static MessageType registerC2S(String name, MessageDecoder<BaseC2SMessage> decoder)
    {
        MessageType registeredMsg = CHANNEL.registerC2S(name, decoder);
        return registeredMsg;
    }
}
