package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BankAccountNumbersRequest extends BankSystemGenericRequest<List<UUID>, List<Integer>> {
    @Override
    public String getRequestTypeID() {
        return BankAccountNumbersRequest.class.getSimpleName();
    }

    @Override
    public List<Integer> handleOnClient(List<UUID> input) {
        return null;
    }

    @Override
    public List<Integer> handleOnServer(List<UUID> input, ServerPlayer sender) {
        if(input.isEmpty())
            input.add(sender.getUUID());


        List<Integer> accountNumbers = new ArrayList<>();
        for(UUID uuid : input) {
            List<BankAccount> accounts = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccounts(uuid);
            for(BankAccount account : accounts) {
                int accountNumber = account.getAccountNumber();
                if(accountNumbers.contains(accountNumber)) {
                    continue; // Skip if the account number is already in the list
                }
                accountNumbers.add(accountNumber); // Add the account number to the list
            }
        }
        return accountNumbers; // Return the list of account numbers
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, List<UUID> input) {
        if (input == null || input.isEmpty()) {
            buf.writeInt(0); // Write 0 to indicate no UUIDs
            return;
        }
        buf.writeInt(input.size()); // Write the size of the list
        for (UUID uuid : input) {
            buf.writeUUID(uuid); // Write each UUID
        }
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, List<Integer> output) {
        if (output == null || output.isEmpty()) {
            buf.writeInt(0); // Write 0 to indicate no integers
            return;
        }
        buf.writeInt(output.size()); // Write the size of the list
        for (Integer number : output) {
            buf.writeInt(number); // Write each integer
        }
    }

    @Override
    public List<UUID> decodeInput(FriendlyByteBuf buf) {
        int size = buf.readInt(); // Read the size of the list
        if (size == 0) {
            return List.of(); // Return an empty list if size is 0
        }
        List<UUID> uuids = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            uuids.add(buf.readUUID()); // Read each UUID
        }
        return uuids;
    }

    @Override
    public List<Integer> decodeOutput(FriendlyByteBuf buf) {
        int size = buf.readInt(); // Read the size of the list
        if (size == 0) {
            return List.of(); // Return an empty list if size is 0
        }
        List<Integer> numbers = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            numbers.add(buf.readInt()); // Read each integer
        }
        return numbers;
    }
}
