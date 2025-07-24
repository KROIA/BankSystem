package net.kroia.banksystem.networking.packet.client_sender.update;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.UUID;

public class WithdrawMoneyPacket extends NetworkPacket {

    // Contains the item ID and the requested amount of the bank notes
    HashMap<String, Long> requestedBankNoteIDs;// = new HashMap<>();

    public WithdrawMoneyPacket(FriendlyByteBuf buf) {
        super(buf);
    }
    public WithdrawMoneyPacket(HashMap<String, Long> requestedBankNoteIDs) {
        super();
        this.requestedBankNoteIDs = requestedBankNoteIDs;
    }

    public static void sendPacket(HashMap<String, Long> requestedBankNoteIDs) {
        WithdrawMoneyPacket packet = new WithdrawMoneyPacket(requestedBankNoteIDs);
        BankSystemNetworking.sendToServer(packet);
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeVarInt(requestedBankNoteIDs.size());
        for (String itemID : requestedBankNoteIDs.keySet()) {
            buf.writeUtf(itemID);
            buf.writeVarLong(requestedBankNoteIDs.get(itemID));
        }
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        requestedBankNoteIDs = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String itemID = buf.readUtf();
            long amount = buf.readVarInt();
            requestedBankNoteIDs.put(itemID, amount);
        }
    }


    @Override
    protected void handleOnServer(ServerPlayer sender) {
        UUID playerUUID = sender.getUUID();
        Bank moneyBank = BankSystemMod.SERVER_BANK_MANAGER.getMoneyBank(playerUUID);
        if(moneyBank == null) {
            return; // No money bank found for the player
        }

        // Get a list of all needed bank notes
        HashMap<String, MoneyItem> availableBankNotes = new HashMap<>();
        for (String itemID : requestedBankNoteIDs.keySet()) {
            String normalizedID = ItemUtilities.getNormalizedItemID(itemID);
            ItemStack moneyItemStack = ItemUtilities.createItemStackFromId(normalizedID);
            if(moneyItemStack != null) {
                try {
                    MoneyItem moneyItem = (MoneyItem) moneyItemStack.getItem();
                    if (moneyItem != null) {
                        availableBankNotes.put(itemID, moneyItem);
                    }
                } catch (Exception e) {
                    BankSystemMod.logError("WithdrawMoneyPacket: Error setting stack size for item ID: " + itemID + "\n" + e.getMessage());
                    continue; // Skip this item if it cannot be set
                }
            }
        }


        // Ckeck if the player has enough balance to withdraw the requested amounts
        for (String itemID : requestedBankNoteIDs.keySet()) {
            long requestedAmount = requestedBankNoteIDs.get(itemID);
            MoneyItem moneyItem = availableBankNotes.get(itemID);
            if(moneyItem == null)
            {
                BankSystemMod.logError("WithdrawMoneyPacket: Invalid money item ID: " + itemID);
                continue;
            }
            long itemValue = moneyItem.worth();
            long totalValue = requestedAmount * itemValue;
            if (totalValue <= 0) {
                continue; // Skip invalid requests
            }


            if (!moneyBank.hasSufficientFunds(totalValue)) {
                PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getNotEnoughInAccountMessage(MoneyItem.getName(), sender.getName().getString()));
                continue;
            }

            // Withdraw the requested amount of bank notes
            if(moneyBank.withdraw(totalValue) == Bank.Status.SUCCESS){
                Inventory playerInventory = sender.getInventory();
                int intAmount = (int) requestedAmount;
                if(requestedAmount > Integer.MAX_VALUE)
                {
                    while(requestedAmount > 0)
                    {
                        intAmount = (int) Math.min(requestedAmount, Integer.MAX_VALUE);
                        requestedAmount -= intAmount;
                        ItemStack moneyStack = new ItemStack(moneyItem, intAmount);
                        if(PlayerUtilities.addToPlayerInventory(sender, moneyStack) > 0)
                        {
                            // Drop remaining
                            ItemUtilities.dropItemAtPlayer(sender, moneyStack);
                        }
                    }
                }
                else {
                    ItemStack moneyStack = new ItemStack(moneyItem, intAmount);
                    if(PlayerUtilities.addToPlayerInventory(sender, moneyStack) > 0)
                    {
                        // Drop remaining
                        ItemUtilities.dropItemAtPlayer(sender, moneyStack);
                    }
                }
            }
        }

    }
}
