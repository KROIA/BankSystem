package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
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
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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

    public WithdrawMoneyPacket(HashMap<ItemID, Long> requestedBankNoteIDs, int currentSelectedAccountNumber) {
        super();
        this.requestedBankNoteIDs = requestedBankNoteIDs;
        this.currentSelectedAccountNumber = currentSelectedAccountNumber;
    }

    public static void sendPacket(HashMap<ItemID, Long> requestedBankNoteIDs, int currentSelectedAccountNumber) {
        WithdrawMoneyPacket packet = new WithdrawMoneyPacket(requestedBankNoteIDs, currentSelectedAccountNumber);
        packet.sendToServer();
    }


    @Override
    protected void handleOnServer(ServerPlayer sender) {
        handle(sender.getUUID(), null);
        /*ISyncServerBankManager bankManager = getSyncBankManager();
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
        }*/

    }

    @Override
    protected void handleOnMaster(ForwardPacketContext context) {
        handle(context.senderPlayerUUID, context.senderServerID);
    }
    public void handle(UUID player, @Nullable String slaveID)
    {
        ISyncServerBankManager bankManager = getSyncBankManager();
        if(bankManager == null)
            return;
        final int finalCurrentSelectedAccountNumber = currentSelectedAccountNumber;
        ISyncServerBankAccount account = bankManager.getBankAccount(finalCurrentSelectedAccountNumber);
        if(account == null)
            return;

        if(!account.hasPermission(player, BankPermission.WITHDRAW.getValue()))
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNoBankPermissionMessage(account.getAccountName(), BankPermission.WITHDRAW));
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
                String userName = Objects.requireNonNull(bankManager.getUserByUUID(player)).getName();
                ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNotEnoughInAccountMessage(MoneyItem.getName(), userName));
                continue;
            }

            // Withdraw the requested amount of banknotes
            if(moneyBank.withdraw(totalValue) == BankStatus.SUCCESS)
            {
                requestedBankNotes.put(itemID, requestedAmount);
            }
        }



        if(slaveID == null)
        {
            Map<ItemID, Long> notDropped = DropItemsInPlayerInventoryRequest.dropItems(UtilitiesPlatform.getServer(), player, requestedBankNotes);
            putRemainingItemsBack(finalCurrentSelectedAccountNumber, notDropped);
        }
        else
        {
            dropRequestToSlave(slaveID, player, finalCurrentSelectedAccountNumber, requestedBankNotes);
        }
    }

    private void dropRequestToSlave(String slaveID, UUID player, int bankaccountNr, Map<ItemID, Long> items)
    {
        CompletableFuture<Map<ItemID, Long>> notDroppedItemsFuture = DropItemsInPlayerInventoryRequest.sendToSlave(slaveID, player, items);
        notDroppedItemsFuture.thenAccept(notDropedItems -> {
            putRemainingItemsBack(bankaccountNr, notDropedItems);
        });
    }
    private void putRemainingItemsBack(int bankaccountNr, Map<ItemID, Long> items)
    {
        if(items.isEmpty())
            return;
        ISyncServerBankAccount redepositAccount = getSyncBankManager().getBankAccount(bankaccountNr);
        if(redepositAccount == null) {
            error("WithdrawMoneyPacket: Invalid bank account ID: " + bankaccountNr + " Items are lost now: "+items);
            return;
        }
        ISyncServerBank redepositMoneyBank = redepositAccount.getBank(MoneyItem.getItemID());
        if(redepositMoneyBank == null) {
            error("WithdrawMoneyPacket: no money bank account with ID: " + bankaccountNr+" Items are lost now: "+items);
            return; // No money bank found for the player
        }

        long toDepositMoney = 0;
        for(Map.Entry<ItemID, Long> entry : items.entrySet()) {
            ItemID  itemID = entry.getKey();
            ItemStack itemStack = itemID.getStack();
            if(itemStack != null) {
                if (itemStack.getItem() instanceof MoneyItem moneyItem) {
                    toDepositMoney += moneyItem.worth() * entry.getValue();
                }
            }
        }
        redepositMoneyBank.deposit(toDepositMoney);
    }


    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


}
