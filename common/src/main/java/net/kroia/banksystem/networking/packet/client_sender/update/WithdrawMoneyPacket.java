package net.kroia.banksystem.networking.packet.client_sender.update;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.packet.server_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class WithdrawMoneyPacket extends BankSystemNetworkPacket {

    public static final Type<WithdrawMoneyPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "withdraw_money_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WithdrawMoneyPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap::new), p -> p.requestedBankNoteIDs,
            ByteBufCodecs.INT, p -> p.currentSelectedAccountNumber,
            WithdrawMoneyPacket::new
    );

    // Contains the item ID and the requested amount of the bank notes
    HashMap<ItemID, Long> requestedBankNoteIDs;// = new HashMap<>();
    int currentSelectedAccountNumber;

    /*public WithdrawMoneyPacket(FriendlyByteBuf buf) {
        super(buf);
    }
    */
    public WithdrawMoneyPacket(HashMap<ItemID, Long> requestedBankNoteIDs, int currentSelectedAccountNumber) {
        super();
        this.requestedBankNoteIDs = requestedBankNoteIDs;
        this.currentSelectedAccountNumber = currentSelectedAccountNumber;
    }

    public static void sendPacket(HashMap<ItemID, Long> requestedBankNoteIDs, int currentSelectedAccountNumber) {
        WithdrawMoneyPacket packet = new WithdrawMoneyPacket(requestedBankNoteIDs, currentSelectedAccountNumber);
        packet.sendToServer();
    }

    /*@Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(currentSelectedAccountNumber);
        buf.writeVarInt(requestedBankNoteIDs.size());
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            buf.writeItem(itemID.getStack());
            buf.writeVarLong(requestedBankNoteIDs.get(itemID));
        }
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        currentSelectedAccountNumber = buf.readVarInt();
        int size = buf.readVarInt();
        requestedBankNoteIDs = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ItemID itemID = new ItemID(buf.readItem());
            long amount = buf.readVarInt();
            requestedBankNoteIDs.put(itemID, amount);
        }
    }*/
    @Override
    protected void handleOnServer(ServerPlayer sender) {
        ISyncServerBankManager bankManager = getSyncBankManager();
        ISyncServerBankAccount account = bankManager.getBankAccount(currentSelectedAccountNumber);
        if(account == null)
            return;

        if(!account.hasPermission(sender.getUUID(), BankPermission.WITHDRAW.getValue()))
        {
            ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getNoBankPermissionMessage(account.getAccountName(), BankPermission.WITHDRAW));
            return;
        }

        ISyncServerBank moneyBank = account.getBank(MoneyItem.getItemID());
        if(moneyBank == null) {
            return; // No money bank found for the player
        }

        // Get a list of all needed bank notes
        HashMap<ItemID, MoneyItem> availableBankNotes = new HashMap<>();
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            ItemStack moneyItemStack = itemID.getStack().copy();
            try {
                if (moneyItemStack.getItem() instanceof MoneyItem moneyItem) {
                    availableBankNotes.put(itemID, moneyItem);
                }
            } catch (Exception e) {
                error("WithdrawMoneyPacket: Error setting stack size for item ID: " + itemID + "\n" + e.getMessage(), e);
            }
        }


        // Ckeck if the player has enough balance to withdraw the requested amounts
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            long requestedAmount = requestedBankNoteIDs.get(itemID);
            MoneyItem moneyItem = availableBankNotes.get(itemID);
            if(moneyItem == null)
            {
                error("WithdrawMoneyPacket: Invalid money item ID: " + itemID);
                continue;
            }
            long itemValue = moneyItem.worth();
            long totalValue = requestedAmount * itemValue;
            if (totalValue <= 0) {
                continue; // Skip invalid requests
            }


            if (!moneyBank.hasSufficientFunds(totalValue)) {
                ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getNotEnoughInAccountMessage(MoneyItem.getName(), sender.getName().getString()));
                continue;
            }

            // Withdraw the requested amount of banknotes
            if(moneyBank.withdraw(totalValue) == BankStatus.SUCCESS){
                int intAmount = (int) requestedAmount;
                if(requestedAmount > Integer.MAX_VALUE)
                {
                    while(requestedAmount > 0)
                    {
                        intAmount = (int) Math.min(requestedAmount, Integer.MAX_VALUE);
                        requestedAmount -= intAmount;
                        ItemStack moneyStack = new ItemStack(moneyItem, intAmount);
                        if(ServerPlayerUtilities.addToPlayerInventory(sender, moneyStack) > 0)
                        {
                            // Drop remaining
                            ItemUtilities.dropItemAtPlayer(sender, moneyStack);
                        }
                    }
                }
                else {
                    ItemStack moneyStack = new ItemStack(moneyItem, intAmount);
                    if(ServerPlayerUtilities.addToPlayerInventory(sender, moneyStack) > 0)
                    {
                        // Drop remaining
                        ItemUtilities.dropItemAtPlayer(sender, moneyStack);
                    }
                }
            }
        }

    }

    @Override
    protected void handleOnMaster(ForwardPacketContext context) {
        UUID sender = context.senderPlayerUUID;
        ISyncServerBankManager bankManager = getSyncBankManager();
        final int finalCurrentSelectedAccountNumber = currentSelectedAccountNumber;
        ISyncServerBankAccount account = bankManager.getBankAccount(finalCurrentSelectedAccountNumber);
        if(account == null)
            return;

        if(!account.hasPermission(sender, BankPermission.WITHDRAW.getValue()))
        {
            ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getNoBankPermissionMessage(account.getAccountName(), BankPermission.WITHDRAW));
            return;
        }

        ISyncServerBank moneyBank = account.getBank(MoneyItem.getItemID());
        if(moneyBank == null) {
            return; // No money bank found for the player
        }

        // Get a list of all needed bank notes
        HashMap<ItemID, MoneyItem> availableBankNotes = new HashMap<>();
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            ItemStack moneyItemStack = itemID.getStack().copy();
            try {
                if (moneyItemStack.getItem() instanceof MoneyItem moneyItem) {
                    availableBankNotes.put(itemID, moneyItem);
                }
            } catch (Exception e) {
                error("WithdrawMoneyPacket: Error setting stack size for item ID: " + itemID + "\n" + e.getMessage(), e);
            }
        }


        Map<ItemID, Long> requestedBankNotes = new HashMap<>();
        // Ckeck if the player has enough balance to withdraw the requested amounts
        for (ItemID itemID : requestedBankNoteIDs.keySet()) {
            long requestedAmount = requestedBankNoteIDs.get(itemID);
            MoneyItem moneyItem = availableBankNotes.get(itemID);
            if(moneyItem == null)
            {
                error("WithdrawMoneyPacket: Invalid money item ID: " + itemID);
                continue;
            }
            long itemValue = moneyItem.worth();
            long totalValue = requestedAmount * itemValue;
            if (totalValue <= 0) {
                continue; // Skip invalid requests
            }


            if (!moneyBank.hasSufficientFunds(totalValue)) {
                ServerPlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getNotEnoughInAccountMessage(MoneyItem.getName(), sender.toString()));
                continue;
            }

            // Withdraw the requested amount of banknotes
            if(moneyBank.withdraw(totalValue) == BankStatus.SUCCESS)
            {
                requestedBankNotes.put(itemID, requestedAmount);
            }
        }




        CompletableFuture<Map<ItemID, Long>> notDroppedItemsFuture = DropItemsInPlayerInventoryRequest.sendToSlave(context.senderServerID, context.senderPlayerUUID, requestedBankNotes);
        notDroppedItemsFuture.thenAccept(notDropedItems -> {
            ISyncServerBankAccount redepositAccount = getSyncBankManager().getBankAccount(finalCurrentSelectedAccountNumber);
            if(redepositAccount == null) {
                error("WithdrawMoneyPacket: Invalid bank account ID: " + finalCurrentSelectedAccountNumber + " Items are lost now: "+notDropedItems);
                return;
            }
            ISyncServerBank redepositMoneyBank = redepositAccount.getBank(MoneyItem.getItemID());
            if(redepositMoneyBank == null) {
                error("WithdrawMoneyPacket: no money bank account with ID: " + finalCurrentSelectedAccountNumber+" Items are lost now: "+notDropedItems);
                return; // No money bank found for the player
            }

            long toDepositMoney = 0;
            for(Map.Entry<ItemID, Long> entry : notDropedItems.entrySet()) {
                ItemID  itemID = entry.getKey();
                ItemStack itemStack = itemID.getStack();
                if(itemStack != null) {
                    if (itemStack.getItem() instanceof MoneyItem moneyItem) {
                        toDepositMoney += moneyItem.worth() * entry.getValue();
                    }
                }
            }
            redepositMoneyBank.deposit(toDepositMoney);
        });

    }


    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


}
