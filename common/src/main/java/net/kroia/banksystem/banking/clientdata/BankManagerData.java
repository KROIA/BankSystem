package net.kroia.banksystem.banking.clientdata;


import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BankManagerData {




    public static class UserMapData
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, UserMapData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, UserData.STREAM_CODEC, HashMap::new), p -> p.userMap,
                UserMapData::new
        );


        public final Map<UUID, UserData> userMap;

        public UserMapData(Map<UUID, UserData> userMap)
        {
            this.userMap = userMap;
        }

        /*public static UserMapData decode(FriendlyByteBuf buf) {
            int size = buf.readInt();
            Map<UUID, UserData> userMap = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                UserData userData = UserData.decode(buf);
                userMap.put(userData.userUUID, userData);
            }
            return new UserMapData(userMap);
        }

        @Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeInt(userMap.size());
            for (Map.Entry<UUID, UserData> entry : userMap.entrySet()) {
                entry.getValue().encode(buf);
            }
        }*/
    }
    public static class ItemFractionScaleFactorData
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemFractionScaleFactorData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.INT, HashMap::new), p -> p.itemFractionScaleFactorMap,
                ItemFractionScaleFactorData::new
        );

        public final Map<ItemID, Integer> itemFractionScaleFactorMap;

        public ItemFractionScaleFactorData(Map<ItemID, Integer> itemFractionScaleFactorMap)
        {
            this.itemFractionScaleFactorMap = itemFractionScaleFactorMap;
        }

      /*  public static ItemFractionScaleFactorData decode(FriendlyByteBuf buf) {
            int size = buf.readInt();
            Map<ItemID, Integer> itemFractionScaleFactorMap = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                ItemID itemID = ItemID.createFomBytes(buf);
                int scaleFactor = buf.readInt();
                itemFractionScaleFactorMap.put(itemID, scaleFactor);
            }
            return new ItemFractionScaleFactorData(itemFractionScaleFactorMap);
        }

        @Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeInt(itemFractionScaleFactorMap.size());
            for (Map.Entry<ItemID, Integer> entry : itemFractionScaleFactorMap.entrySet()) {
                entry.getKey().encode(buf);
                buf.writeInt(entry.getValue());
            }
        }*/
    }
    public static class BankAccountsData
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, BankAccountsData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(ByteBufCodecs.INT, BankAccountData.STREAM_CODEC, HashMap::new), p -> p.bankAccountMap,
                BankAccountsData::new
        );

        /**
         * Map of bank account numbers to their data.
         * The key is the account number, and the value is the BankAccountData.
         */
        public final Map<Integer, BankAccountData> bankAccountMap;
        public BankAccountsData(Map<Integer, BankAccountData> bankAccountMap)
        {
            this.bankAccountMap = bankAccountMap;
        }
       /* public static BankAccountsData decode(FriendlyByteBuf buf) {
            int size = buf.readInt();
            Map<Integer, BankAccountData> bankAccountMap = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                BankAccountData bankAccountData = BankAccountData.decode(buf);
                bankAccountMap.put(bankAccountData.accountNumber, bankAccountData);
            }
            return new BankAccountsData(bankAccountMap);
        }
        @Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeInt(bankAccountMap.size());
            for (Map.Entry<Integer, BankAccountData> entry : bankAccountMap.entrySet()) {
                entry.getValue().encode(buf);
            }
        }*/
    }


    public static final StreamCodec<RegistryFriendlyByteBuf, BankManagerData> STREAM_CODEC = StreamCodec.composite(
            UserMapData.STREAM_CODEC, p -> p.userMapData,
           // ItemFractionScaleFactorData.STREAM_CODEC, p -> p.itemFractionScaleFactorData,
            BankAccountsData.STREAM_CODEC, p -> p.bankAccountsData,
            ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC), p -> p.allowedItems,
            ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC), p -> p.blacklistedItems,
            ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC), p -> p.notRemovableItems,
            BankManagerData::new
    );

    public final UserMapData userMapData;
    //public final ItemFractionScaleFactorData itemFractionScaleFactorData;
    public final BankAccountsData bankAccountsData;
    public final List<ItemID> allowedItems;
    public final List<ItemID> blacklistedItems;
    public final List<ItemID> notRemovableItems;

    public BankManagerData(UserMapData userMapData,
                           //ItemFractionScaleFactorData itemFractionScaleFactorData,
                           BankAccountsData bankAccountsData,
                           List<ItemID> allowedItems,
                           List<ItemID> blacklistedItems,
                           List<ItemID> notRemovableItems) {
        this.userMapData = userMapData;
       // this.itemFractionScaleFactorData = itemFractionScaleFactorData;
        this.bankAccountsData = bankAccountsData;
        this.allowedItems = allowedItems;
        this.blacklistedItems = blacklistedItems;
        this.notRemovableItems = notRemovableItems;
    }

    public List<ItemStack> getAllowedItemStacks() {
        return allowedItems.stream()
                .map(ItemID::getStack)
                .toList();
    }


    /*@Override
    public void encode(FriendlyByteBuf buf) {
        userMapData.encode(buf);
        itemFractionScaleFactorData.encode(buf);
        bankAccountsData.encode(buf);
        buf.writeInt(allowedItems.size());
        for (ItemID itemID : allowedItems) {
            itemID.encode(buf);
        }
        buf.writeInt(blacklistedItems.size());
        for (ItemID itemID : blacklistedItems) {
            itemID.encode(buf);
        }
        buf.writeInt(notRemovableItems.size());
        for (ItemID itemID : notRemovableItems) {
            itemID.encode(buf);
        }
    }

    public static BankManagerData decode(FriendlyByteBuf buf) {
        UserMapData userMapData = UserMapData.decode(buf);
        ItemFractionScaleFactorData itemFractionScaleFactorData = ItemFractionScaleFactorData.decode(buf);
        BankAccountsData bankAccountsData = BankAccountsData.decode(buf);
        int allowedItemsSize = buf.readInt();
        List<ItemID> allowedItems = new java.util.ArrayList<>(allowedItemsSize);
        for (int i = 0; i < allowedItemsSize; i++) {
            allowedItems.add(ItemID.createFomBytes(buf));
        }
        int blacklistedItemsSize = buf.readInt();
        List<ItemID> blacklistedItems = new java.util.ArrayList<>(blacklistedItemsSize);
        for (int i = 0; i < blacklistedItemsSize; i++) {
            blacklistedItems.add(ItemID.createFomBytes(buf));
        }
        int notRemovableItemsSize = buf.readInt();
        List<ItemID> notRemovableItems = new java.util.ArrayList<>(notRemovableItemsSize);
        for (int i = 0; i < notRemovableItemsSize; i++) {
            notRemovableItems.add(ItemID.createFomBytes(buf));
        }
        return new BankManagerData(
                userMapData,
                itemFractionScaleFactorData,
                bankAccountsData,
                allowedItems,
                blacklistedItems,
                notRemovableItems
        );
    }*/
}
