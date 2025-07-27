package net.kroia.banksystem.networking.packet.client_sender.update;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.UUID;

public class WithdrawMoneyPacket extends BankSystemNetworkPacket {


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
        packet.sendToServer();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(requestedBankNoteIDs.size());
        for (String itemID : requestedBankNoteIDs.keySet()) {
            buf.writeUtf(itemID);
            buf.writeVarLong(requestedBankNoteIDs.get(itemID));
        }
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
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
        IBank moneyBank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getMoneyBank(playerUUID);
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
                    if(moneyItemStack.getItem() instanceof MoneyItem moneyItem)
                    {
                        availableBankNotes.put(itemID, moneyItem);
                    }
                } catch (Exception e) {
                    BACKEND_INSTANCES.LOGGER.error("WithdrawMoneyPacket: Error setting stack size for item ID: " + itemID + "\n" + e.getMessage());
                }
            }
        }


        // Ckeck if the player has enough balance to withdraw the requested amounts
        for (String itemID : requestedBankNoteIDs.keySet()) {
            long requestedAmount = requestedBankNoteIDs.get(itemID);
            MoneyItem moneyItem = availableBankNotes.get(itemID);
            if(moneyItem == null)
            {
                BACKEND_INSTANCES.LOGGER.error("WithdrawMoneyPacket: Invalid money item ID: " + itemID);
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

            // Withdraw the requested amount of banknotes
            if(moneyBank.withdraw(totalValue) == Bank.Status.SUCCESS){
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
