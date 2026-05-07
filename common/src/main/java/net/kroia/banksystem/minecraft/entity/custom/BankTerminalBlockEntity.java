package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class BankTerminalBlockEntity  extends BlockEntity implements MenuProvider {
    /*
    private static class TransferTask implements ServerSaveable
    {
        // negative values: send to market
        // positive values: send to inventory
        private final BankTerminalBlockEntity blockEntity;
        private HashMap<ItemID, Long> transferItems;
        private UUID playerID;
        public TransferTask(BankTerminalBlockEntity blockEntity, UUID playerID, HashMap<ItemID, Long>transferItems)
        {
            this.blockEntity = blockEntity;
            this.playerID = playerID;
            this.transferItems = transferItems;
        }
        private TransferTask(BankTerminalBlockEntity blockEntity)
        {
            this.blockEntity = blockEntity;
            this.transferItems = new HashMap<>();
            this.playerID = new UUID(0, 0);
        }
        public UUID getPlayerID() {
            return playerID;
        }
        private HashMap<ItemID, Long> getItemID() {
            return transferItems;
        }

        private long getAmount(ItemID itemID) {
            return transferItems.getOrDefault(itemID, 0L);
        }
        private void setAmount(ItemID itemID, long amount) {
            if(amount == 0 && transferItems.containsKey(itemID))
                transferItems.remove(itemID);
            else
                transferItems.put(itemID, amount);
            blockEntity.setChanged();
        }

        public boolean createTask(ItemID itemID, long amount)
        {
            if(amount == 0)
                return false;
            IBankUserOld bank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUser(playerID);
            if(bank == null) {
                // Create bank account for this item if it can be used for banking
                ArrayList<ItemID> keys = new ArrayList<>();
                String userName = ServerPlayerUtilities.getOnlinePlayer(playerID).getName().getString();
                keys.add(itemID);
                bank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.createUser(playerID, userName, keys, true,0 );
            }

            ItemID bankITemID = itemID;
            if(MoneyItem.isMoney(itemID))
            {
                bankITemID = MoneyBank.ITEM_ID;
            }
            ISyncServerBank bankAccount = bank.getBank(bankITemID);
            if(bankAccount == null) {
                // Create item bank account
                bankAccount = bank.createItemBank(bankITemID, 0, true);
                if(bankAccount == null)
                    return false;
            }

            setAmount(itemID, getAmount(itemID) + amount);
            return true;
        }

        public void cancelTask(ItemID itemID)
        {
            setAmount(itemID, 0);
        }
        public void cancelTasks()
        {
            transferItems.clear();
        }
        public int taskCount()
        {
            return transferItems.size();
        }

        public boolean processTaskStep(long amountToProcess, boolean processWholeItemStack)
        {
            if(amountToProcess<=0)
            {
                cancelTasks();
                return false;
            }
            IBankUserOld bank = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUser(playerID);
            if(bank == null) {
                cancelTasks();
                return false;
            }
            ArrayList<ItemID> keys = new ArrayList<>(transferItems.keySet());
            for(ItemID itemID : keys)
            {
                long amount = transferItems.get(itemID);
                if(processWholeItemStack)
                    amountToProcess = Math.abs(amount);
                if(amount == 0)
                    continue;
                ItemID bankITemID = itemID;
                boolean isMoney = MoneyItem.isMoney(itemID);
                if(isMoney)
                    bankITemID = MoneyBank.ITEM_ID;

                ISyncServerBank bankAccount = bank.getBank(bankITemID);
                if(bankAccount == null) {
                    cancelTask(itemID);
                    continue;
                }
                TerminalInventory inventory = blockEntity.playerDataTable.get(playerID).inventory;

                if(amount < 0)
                {
                    // send to bank
                    amount = Math.min(-amount, amountToProcess);
                    if(amount <= 0) {
                        cancelTask(itemID);
                        continue;
                    }
                    long availableAmount = Math.min(amount, inventory.getItemCount(itemID));
                    if(availableAmount > 0)
                    {
                        long depositAmount = availableAmount;
                        if(isMoney) {
                            MoneyItem moneyItem = (MoneyItem) itemID.getStack().getItem();
                            depositAmount *= moneyItem.worth();
                        }
                        else
                        {
                            depositAmount = bankAccount.convertToRawAmount(depositAmount);
                        }
                        if(bankAccount.deposit(depositAmount) == BankStatus.SUCCESS) {
                            inventory.removeItem(itemID, availableAmount);
                            setAmount(itemID, getAmount(itemID) + availableAmount);
                        }
                        else
                            cancelTask(itemID);
                        return true;
                    }else {
                        cancelTask(itemID);
                        continue;
                    }
                }
                else
                {
                    // send to inventory
                    if(isMoney)
                    {
                        amount = Math.min(amount, amountToProcess);
                        amount *= ((MoneyItem)BankSystemItems.MONEY.get()).worth();
                        amount = Math.min(amount, bankAccount.getBalance());
                        int moneyFractionScaleFactor = bankAccount.getItemFractionScaleFactor();
                        if(amount < moneyFractionScaleFactor)
                            amount = 0; // don't transfer less than 100 cents
                        if(amount % moneyFractionScaleFactor != 0)
                            amount = (amount / moneyFractionScaleFactor) * moneyFractionScaleFactor; // round to whole dollars
                        if(amount <= 0) {
                            cancelTask(itemID);
                            continue;
                        }

                        long addedAmount = inventory.addItem(itemID, (long)bankAccount.convertToRealAmount(amount));
                        if(addedAmount > 0)
                        {
                            if(bankAccount.withdraw(bankAccount.convertToRawAmount(addedAmount)) != BankStatus.SUCCESS)
                            {
                                // error
                                error("Failed to withdraw " + ServerBank.getNormalizedAmount(addedAmount,bankAccount.getItemFractionScaleFactor()) + " " + itemID + " from bank account of user " + playerID);
                                inventory.removeItem(itemID, addedAmount);
                                cancelTask(itemID);
                                continue;
                            }
                            setAmount(itemID, getAmount(itemID) - addedAmount);
                            return true;
                        }
                        else
                            cancelTask(itemID); // inventory full
                    }
                    else
                    {
                        amount = Math.min(amount, amountToProcess);
                        amount = Math.min(amount, (long)bankAccount.convertToRealAmount(bankAccount.getBalance()));
                        if(amount <= 0) {
                            cancelTask(itemID);
                            continue;
                        }
                        long addedAmount = inventory.addItem(itemID, amount);
                        if(addedAmount > 0)
                        {
                            if(bankAccount.withdraw(bankAccount.convertToRawAmount(addedAmount)) != BankStatus.SUCCESS)
                            {
                                // error
                                error("Failed to withdraw " + ServerBank.getNormalizedAmount(addedAmount,bankAccount.getItemFractionScaleFactor()) + " " + itemID + " from bank account of user " + playerID);
                                inventory.removeItem(itemID, addedAmount);
                                cancelTask(itemID);
                                continue;
                            }
                            setAmount(itemID, getAmount(itemID) - addedAmount);
                            return true;
                        }
                        else
                            cancelTask(itemID); // inventory full
                    }
                }
            }
            return false;
        }

        public static TransferTask createFromTag(BankTerminalBlockEntity blockEntity,CompoundTag tag)
        {
            TransferTask task = new TransferTask(blockEntity);
            if(task.load(tag))
                return task;
            return null;
        }

        @Override
        public boolean save(CompoundTag tag) {
            tag.putUUID("PlayerID", playerID);
            ListTag transferItemsTag = new ListTag();
            for(ItemID itemID : transferItems.keySet())
            {
                CompoundTag itemTag = new CompoundTag();
                long amount = transferItems.get(itemID);
                if(amount == 0)
                    continue;
                CompoundTag itemIDTag = new CompoundTag();
                itemID.save(itemIDTag);
                itemTag.put("ItemID", itemIDTag);
                itemTag.putLong("Amount", amount);
                transferItemsTag.add(itemTag);
            }
            tag.put("TransferItems", transferItemsTag);
            return true;
        }

        @Override
        public boolean load(CompoundTag tag) {
            if(tag == null)
                return false;
            if(!tag.contains("PlayerID") || !tag.contains("TransferItems"))
                return false;
            playerID = tag.getUUID("PlayerID");
            ListTag transferItemsTag = tag.getList("TransferItems", 10);
            for(int i = 0; i < transferItemsTag.size(); i++)
            {
                CompoundTag itemTag = transferItemsTag.getCompound(i);
                String itemIDStr = itemTag.getString("ItemID");
                ItemID itemID;
                if(!itemIDStr.isEmpty())
                {
                    itemID = new ItemID(itemIDStr);
                }
                else
                {
                    CompoundTag itemIDTag = itemTag.getCompound("ItemID");
                    itemID = new ItemID(ItemStack.of(itemIDTag));
                    return false;
                }
                long amount = itemTag.getLong("Amount");
                transferItems.put(itemID, amount);
            }
            return true;
        }
    }
    */
    public static class TerminalInventory implements ServerSaveable, Container {
        private final BankTerminalBlockEntity blockEntity;
        private final ArrayList<ItemStack> inventory;
        private HolderLookup.Provider pRegistries = null;
        public TerminalInventory(BankTerminalBlockEntity blockEntity, int size) {
            this.blockEntity = blockEntity;
            inventory = new ArrayList<>(size);
            for(int i = 0; i < size; i++)
            {
                inventory.add(ItemStack.EMPTY);
            }
        }

        public void setRegistryProvider(HolderLookup.Provider pRegistries) {
            this.pRegistries = pRegistries;
        }

        public void setStackInSlot(int slot, ItemStack stack)
        {
            if(slot < 0 || slot >= inventory.size())
                return;
            inventory.set(slot, stack);
            setChanged();
        }

        public long getFreeSpace(String itemID, int amount)
        {
            long freeSpace = 0;
            for (int i = 0; i < this.getContainerSize(); i++) {
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty()) {
                    freeSpace += amount > 0 ? amount : 64;
                    continue;
                }
                if(stack.isDamaged() || stack.isEnchanted())
                    continue;

                String stackItemID = ItemUtilities.getItemIDStr(stack.getItem());

                // Compare the ResourceLocation to the provided string
                if (stackItemID != null && stackItemID.equals(itemID)) {
                    // Check if the stack can fit the amount
                    freeSpace += stack.getMaxStackSize() - stack.getCount();
                }
            }

            return freeSpace;
        }

        // Only counts items that are not damaged or enchanted
        public CompletableFuture<HashMap<ItemID, Long>> getItemCount()
        {
            CompletableFuture<HashMap<ItemID, Long>> futureResult = new CompletableFuture<>();

            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < this.getContainerSize(); i++) {
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted()) {
                    continue;
                }
                stacks.add(stack);
            }
            HashMap<ItemID, Long> items = new HashMap<>();
            for (ItemStack stack : stacks) {
                ItemID itemID = ItemID.getOrRegisterFromItemStackServerSide_direct(stack);
                if(itemID == null || !itemID.isValid())
                {
                    warn("ItemStack: "+stack+" is not registered for ItemID. Skipping this item");
                    continue;
                }

                if(!items.containsKey(itemID))
                    items.put(itemID, (long)stack.getCount());
                else
                    items.put(itemID, items.get(itemID) + stack.getCount());
            }
            futureResult.complete(items);

            return futureResult;
        }
        // Only counts items that are not damaged or enchanted
        public CompletableFuture<Long> getItemCount(ItemID itemID)
        {
            CompletableFuture<Long> futureResult = new CompletableFuture<>();
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < this.getContainerSize(); i++) {
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted()) {
                    continue;
                }
                stacks.add(stack);
            }
            long count = 0;
            for (ItemStack stack : stacks) {
                ItemID _itemID = ItemID.getOrRegisterFromItemStackServerSide_direct(stack);
                if(_itemID == null || !_itemID.isValid())
                {
                    warn("ItemStack: "+stack+" is not registered for ItemID. Skipping this item");
                    continue;
                }

                if (_itemID.equals(itemID)) {
                    count += stack.getCount();
                }
            }
            futureResult.complete(count);
            return futureResult;
        }
        // Only counts items that are not damaged or enchanted
        public long getItemCount_direct(ItemID itemID)
        {
            long count = 0;
            for (int i = 0; i < this.getContainerSize(); i++) {
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted()) {
                    continue;
                }

                // Get the item's ResourceLocation
                ItemID _itemID = ItemID.getOrRegisterFromItemStackServerSide_direct(stack);

                // Compare the ResourceLocation to the provided string
                if (_itemID.equals(itemID)) {
                    // Check if the stack can fit the amount)
                    count += stack.getCount();
                }
            }
            return count;
        }

        // Only adds items that are not damaged or enchanted
        public long addItem(ItemID itemID, long amount)
        {
            if(amount <=0)
                return 0;
            ItemStack templateStack = itemID.getStack();
            if (templateStack == null || templateStack.isEmpty()) return 0;
            long orgAmount = amount;
            for (int i = 0; i < this.getContainerSize(); i++) {
                if(amount <= 0)
                    return orgAmount;
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty()) {
                    ItemStack itemStack = templateStack.copy();
                    int stackSize = (int)Math.min(amount, itemStack.getMaxStackSize());
                    amount -= stackSize;
                    itemStack.setCount(stackSize);
                    this.setStackInSlot(i, itemStack);
                    continue;
                }else if(stack.isDamaged() || stack.isEnchanted())
                    continue;

                // Get the item's ResourceLocation
                ItemID stackItemID = ItemID.of(stack);

                // Compare the ResourceLocation to the provided string
                if (stackItemID.isValid() && stackItemID.equals(itemID)) {
                    // Check if the stack can fit the amount
                    int freeSpace = stack.getMaxStackSize() - stack.getCount();
                    int stackSize = (int)Math.min(amount, freeSpace);
                    stack.setCount(stack.getCount() + stackSize);
                    amount -= stackSize;
                }
            }
            setChanged();
            return orgAmount - amount;
        }

        // Only removes items that are not damaged or enchanted
        public long removeItem(ItemID itemID, long amount)
        {
            long orgAmount = amount;
            for (int i = 0; i < this.getContainerSize(); i++) {
                if(amount <= 0)
                    return orgAmount-amount;
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted()) {
                    continue;
                }

                // Get the item's ResourceLocation
                ItemID _itemID = ItemID.of(stack);

                // Compare the ResourceLocation to the provided string
                if (_itemID.equals(itemID)) {
                    // Check if the stack can fit the amount
                    int stackSize = (int)Math.min(amount, stack.getCount());
                    stack.shrink(stackSize);
                    amount -= stackSize;
                    if(stack.isEmpty())
                        this.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
            setChanged();
            return orgAmount - amount;
        }

        @Override
        public boolean save(CompoundTag tag) {
            ListTag inventoryTag = new ListTag();
            for(int i = 0; i < inventory.size(); i++)
            {
                CompoundTag itemTag = new CompoundTag();
                ItemStack stack = inventory.get(i);
                if(stack.isEmpty())
                    continue;
                itemTag.putInt("Slot", i);
                Tag stackTag = stack.save(pRegistries);
                itemTag.put("ItemStack", stackTag);
                inventoryTag.add(itemTag);
            }
            tag.put("Slots", inventoryTag);
            return true;
        }

        @Override
        public boolean load(CompoundTag tag) {
            if(tag == null)
                return false;
            if(!tag.contains("Slots"))
                return false;
            ListTag inventoryTag = tag.getList("Slots", CompoundTag.TAG_COMPOUND);
            for(int i = 0; i < inventoryTag.size(); i++)
            {
                CompoundTag itemTag = inventoryTag.getCompound(i);
                if(!itemTag.contains("Slot") || !itemTag.contains("ItemStack"))
                    return false;
                int slot = itemTag.getInt("Slot");
                CompoundTag stackTag = itemTag.getCompound("ItemStack");
                ItemStack stack = ItemStack.parse(pRegistries,
                                stackTag)
                        .orElse(ItemStack.EMPTY);
                if(stack.isEmpty())
                    return false;
                inventory.set(slot, stack);
            }
            return true;
        }

        @Override
        public int getContainerSize() {
            return inventory.size();
        }

        @Override
        public boolean isEmpty() {
            for(ItemStack stack : inventory)
            {
                if(!stack.isEmpty())
                    return false;
            }
            return true;
        }

        @Override
        public @NotNull ItemStack getItem(int slot) {
            if(slot < 0 || slot >= inventory.size())
                return ItemStack.EMPTY;
            return inventory.get(slot);
        }

        @Override
        public @NotNull ItemStack removeItem(int slot, int amount) {
            if(slot < 0 || slot >= inventory.size())
                return ItemStack.EMPTY;
            ItemStack stack = inventory.get(slot);
            if(stack.isEmpty())
                return ItemStack.EMPTY;
            ItemStack copy = stack.copy();
            if(stack.getCount() <= amount)
            {
                inventory.set(slot, ItemStack.EMPTY);
                setChanged();
                return copy;
            }
            ItemStack newStack = stack.split(amount);
            if(stack.isEmpty())
                inventory.set(slot, ItemStack.EMPTY);
            setChanged();
            return newStack;
        }

        @Override
        public @NotNull ItemStack removeItemNoUpdate(int slot) {
            if(slot < 0 || slot >= inventory.size())
                return ItemStack.EMPTY;
            ItemStack stack = inventory.get(slot);
            if(stack.isEmpty())
                return ItemStack.EMPTY;
            inventory.set(slot, ItemStack.EMPTY);
            return stack;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if(slot < 0 || slot >= inventory.size())
                return;
            inventory.set(slot, stack);
            setChanged();
        }

        @Override
        public void setChanged() {
            blockEntity.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return false;
        }

        @Override
        public void clearContent() {
            for(int i = 0; i < inventory.size(); i++)
            {
                inventory.set(i, ItemStack.EMPTY);
            }
            setChanged();
        }
    }
    private static class PlayerData implements ServerSaveable
    {
        private UUID playerID;
        private TerminalInventory inventory;
        private int selectedBankAccount = 0;

        private HolderLookup.Provider pRegistries = null;
        //private TransferTask transferTask;

        private final BankTerminalBlockEntity blockEntity;
        public PlayerData(UUID playerID, BankTerminalBlockEntity blockEntity)
        {
            this.blockEntity = blockEntity;
            this.playerID = playerID;
            this.inventory = new TerminalInventory(blockEntity, 27);
            //this.transferTask = new TransferTask(blockEntity, playerID, new HashMap<>());
        }
        private PlayerData(BankTerminalBlockEntity blockEntity)
        {
            this.blockEntity = blockEntity;
            playerID = new UUID(0, 0);
            inventory = new TerminalInventory(blockEntity, 27);
            //transferTask = new TransferTask(blockEntity);
        }
        public UUID getPlayerID() {
            return playerID;
        }
        public TerminalInventory getInventory() {
            return inventory;
        }
       /* public TransferTask getTransferTask() {
            return transferTask;
        }*/

        public static PlayerData createFromTag(BankTerminalBlockEntity blockEntity, CompoundTag tag, HolderLookup.Provider pRegistries)
        {
            PlayerData playerData = new PlayerData(blockEntity);
            playerData.pRegistries = pRegistries;
            if(playerData.load(tag))
                return playerData;
            return null;
        }

        @Override
        public boolean save(CompoundTag tag) {
            tag.putUUID("PlayerID", playerID);
            CompoundTag inventoryTag = new CompoundTag();
            inventory.setRegistryProvider(pRegistries);
            if(!inventory.save(inventoryTag))
                return false;
            tag.put("Inventory",inventoryTag);
            //CompoundTag transferTaskTag = new CompoundTag();
            //tag.put("TransferTask", transferTaskTag);
            tag.putInt("SelectedBankAccount", selectedBankAccount);
            return true;
        }

        @Override
        public boolean load(CompoundTag tag) {
            if(tag == null)
                return false;
            if(tag.contains("PlayerID"))
                playerID = tag.getUUID("PlayerID");

            if(tag.contains("Inventory")) {
                CompoundTag inventoryTag = tag.getCompound("Inventory");
                inventory.setRegistryProvider(pRegistries);
                inventory.load(inventoryTag);
            }
            //CompoundTag transferTaskTag = tag.getCompound("TransferTask");
            if(tag.contains("SelectedBankAccount"))
                selectedBankAccount = tag.getInt("SelectedBankAccount");

           // transferTask = TransferTask.createFromTag(blockEntity,transferTaskTag);
            //return transferTask != null;
            return true;
        }
    }

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private static final Component TITLE =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".bank_terminal_block_entity");

    private final HashMap<UUID, PlayerData> playerDataTable = new HashMap<>();


    private int lastTickCounter = 0;
    private int tickCounter = 0;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankTerminalBlockEntity.BACKEND_INSTANCES = backend;
    }

    public BankTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.BANK_TERMINAL_BLOCK_ENTITY.get(), pos, state);
    }


    @Override
    public void loadAdditional(CompoundTag nbt, HolderLookup.Provider pRegistries) {
        super.loadAdditional(nbt, pRegistries);
        CompoundTag data = nbt.getCompound(BankSystemMod.MOD_ID);

        ListTag playerDataTag = data.getList("PlayerData", ListTag.TAG_COMPOUND);
        for(int i = 0; i < playerDataTag.size(); i++)
        {
            CompoundTag playerDataCompound = playerDataTag.getCompound(i);
            PlayerData playerData = PlayerData.createFromTag(this, playerDataCompound, pRegistries);
            if(playerData != null)
            {
                playerDataTable.put(playerData.getPlayerID(), playerData);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider pRegistries) {
        super.saveAdditional(nbt, pRegistries);
        CompoundTag data = new CompoundTag();
        //tutorialmodData.put("Inventory", this.inventory.serializeNBT());
        //data.putInt("TransferTickAmount", transferTickAmount);
        ListTag playerInventoriesTag = new ListTag();
        for(UUID playerID : playerDataTable.keySet())
        {
            CompoundTag dataTag = new CompoundTag();
            PlayerData playerData = playerDataTable.get(playerID);
            playerData.pRegistries = pRegistries;
            if(playerData.save(dataTag))
            {
                playerInventoriesTag.add(dataTag);
            }
        }
        data.put("PlayerData", playerInventoriesTag);
        nbt.put(BankSystemMod.MOD_ID, data);
    }


    public TerminalInventory getInventory(UUID playerID) {
        PlayerData playerData = getPlayerData(playerID);
        return playerData.getInventory();
    }

    public HashMap<UUID, TerminalInventory> getPlayerInventories() {
        HashMap<UUID, TerminalInventory> playerInventories = new HashMap<>();
        for(UUID playerID : this.playerDataTable.keySet())
        {
            playerInventories.put(playerID, this.playerDataTable.get(playerID).getInventory());
        }
        return playerInventories;
    }
    public int getSelectedBankAccount(UUID playerID) {
        PlayerData playerData = getPlayerData(playerID);
        return playerData.selectedBankAccount;
    }

    @Override
    public @NotNull Component getDisplayName() {
        return TITLE;
    }

    @Override
    public AbstractContainerMenu createMenu(int pContainerId, Inventory pPlayerInventory, Player pPlayer) {
        return new BankTerminalContainerMenu(pContainerId, pPlayerInventory, this);
    }

    public MenuProvider getMenuProvider() {
        return this;
    }

    private PlayerData getPlayerData(UUID playerID) {
        if(!playerDataTable.containsKey(playerID)) {
            playerDataTable.put(playerID, new PlayerData(playerID, this));
            this.setChanged();
        }
        return playerDataTable.get(playerID);
    }

    public void handlePacket(UpdateBankTerminalBlockEntityPacket packet, ServerPlayer player) {
        //String userNameStr  = player.getName().getString();
        //IBankUserOld user = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUser(player.getUUID());
        /*if (user == null) {
            error("BankUserOld is null for user: " + userNameStr);
            return;
        }*/

        //PlayerData playerData = getPlayerData(player.getUUID());

        //int sendToMarketSign = 1;
        if(packet.isSendItemsToBank())
        {
            sendItemsToBank(player.getUUID(), packet.getSelectedBankAccount());
            //items = inventory.getItemCount();
            //sendToMarketSign = -1;
        }
        else
        {
            HashMap<ItemID, Long> items = packet.getItemTransferFromMarket();
            if(items == null || items.isEmpty()) {
                return;
            }
            sendToBlock(player.getUUID(), packet.getSelectedBankAccount(), items);

            // Send to inventory
            //items = packet.getItemTransferFromMarket();
        }
        /*for(ItemID itemID : items.keySet()) {
            long amount = items.get(itemID);
            playerData.transferTask.createTask(itemID, (int)amount*sendToMarketSign);
        }

        // mark the block entity for saving
        setChanged();*/
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        /*tickCounter++;
        int itemTransferTickInterval = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ITEM_TRANSFER_TICK_INTERVAL.get();
        if(tickCounter - lastTickCounter >= itemTransferTickInterval) {
            lastTickCounter = tickCounter;
            for(UUID playerID : playerDataTable.keySet())
            {
                PlayerData playerData = playerDataTable.get(playerID);
                TransferTask task = playerData.getTransferTask();
                if(task.taskCount() > 0)
                {
                    boolean transferTheWholeItemStack = itemTransferTickInterval == 0;
                    task.processTaskStep(1, transferTheWholeItemStack);
                }
            }
        }*/
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
        if(level.isClientSide)
            return;
        if (t instanceof BankTerminalBlockEntity blockEntity) {
            blockEntity.tick(level, blockPos, blockState);
        }
    }


    private void sendItemsToBank(UUID playerID, int accountNr)
    {
        IAsyncBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        if(accountNr <= 0)
            return;

        PlayerData playerData = getPlayerData(playerID);
        TerminalInventory inventory = playerData.getInventory();
        CompletableFuture<HashMap<ItemID, Long>> itemsFuture = inventory.getItemCount();

        itemsFuture.thenAccept((items)->
        {
            CompletableFuture<IAsyncBankAccount> bankAccountFuture = bankManager.getBankAccountAsync(accountNr);
            bankAccountFuture.thenAccept(bankAccount -> {
                if (bankAccount == null) {
                    CompletableFuture<User> userFuture = bankManager.getUserByUUIDAsync(playerID);
                    userFuture.thenAccept(user -> {
                        String userName = user != null ? user.getName() : "Unknown User";
                        ServerPlayerUtilities.printToClientConsole(playerID, BankSystemTextMessages.getBankAccountNotFoundMessage(userName));
                    });
                    return;
                }
                CompletableFuture<Boolean> hasPermissionFuture = bankAccount.hasPermissionAsync(playerID, BankPermission.DEPOSIT.getValue());
                hasPermissionFuture.thenAccept(hasPermission -> {
                    if (!hasPermission) {
                        CompletableFuture<String> accountNameFuture = bankAccount.getAccountNameAsync();
                        accountNameFuture.thenAccept(accountName -> {
                            ServerPlayerUtilities.printToClientConsole(playerID, BankSystemTextMessages.getNoBankPermissionMessage(accountName, BankPermission.DEPOSIT));
                        });
                        return;
                    }
                    playerData.selectedBankAccount = bankAccount.getAccountNumberAsync();

                    for (ItemID itemID : items.keySet()) {
                        long amount = items.get(itemID);
                        if (amount <= 0)
                            continue;

                        CompletableFuture<@Nullable IAsyncBank> bankFuture;
                        boolean isMoney = MoneyItem.isMoney(itemID);
                        if (isMoney) {
                            bankFuture = bankAccount.getOrCreateBankAsync(MoneyItem.getItemID());
                        } else {
                            bankFuture = bankAccount.getOrCreateBankAsync(itemID);
                        }
                        bankFuture.thenAccept(bank -> {
                            if (bank == null) {
                                ServerPlayerUtilities.printToClientConsole(playerID, BankSystemTextMessages.getItemNotAllowedMessage(itemID.getName()));
                                return;
                            }
                            long amountToDeposit = amount * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
                            if (isMoney) {
                                amountToDeposit = amount * ((MoneyItem) Objects.requireNonNull(itemID.getStack()).getItem()).worth();
                            }
                            CompletableFuture<BankStatus> depositResult = bank.depositAsync(amountToDeposit);
                            depositResult.thenAccept(deposit -> {
                                if (deposit == BankStatus.SUCCESS) {
                                    inventory.removeItem(itemID, amount);
                                }
                            });
                        });
                    }
                    // mark the block entity for saving
                    setChanged();
                });
            });
        });
    }

    private void sendToBlock(UUID playerID, int accountNr, HashMap<ItemID, Long> items)
    {
        IAsyncBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();

        PlayerData playerData = getPlayerData(playerID);
        TerminalInventory inventory = playerData.getInventory();

        if(accountNr <= 0)
            return;
        CompletableFuture<IAsyncBankAccount> bankAccountFuture = bankManager.getBankAccountAsync(accountNr);
        bankAccountFuture.thenAccept(bankAccount -> {
            if (bankAccount == null) {
                CompletableFuture<User> userFuture = bankManager.getUserByUUIDAsync(playerID);
                userFuture.thenAccept(user -> {
                    String userName = user != null ? user.getName() : "Unknown User";
                    ServerPlayerUtilities.printToClientConsole(playerID, BankSystemTextMessages.getBankAccountNotFoundMessage(userName));
                });
                return;
            }
            CompletableFuture<Boolean> hasPermissionFuture = bankAccount.hasPermissionAsync(playerID, BankPermission.WITHDRAW.getValue());
            hasPermissionFuture.thenAccept(hasPermission -> {
                if (!hasPermission) {
                    CompletableFuture<String>  accountNameFuture = bankAccount.getAccountNameAsync();
                    accountNameFuture.thenAccept(accountName -> {
                        ServerPlayerUtilities.printToClientConsole(playerID, BankSystemTextMessages.getNoBankPermissionMessage(accountName, BankPermission.WITHDRAW));
                    });
                    return;
                }
                playerData.selectedBankAccount = bankAccount.getAccountNumberAsync();

                for (ItemID itemID : items.keySet()) {
                    long amount = items.get(itemID);
                    if (amount <= 0)
                        continue;

                    CompletableFuture<@Nullable IAsyncBank> bankFuture = bankAccount.getOrCreateBankAsync(itemID);
                    bankFuture.thenAccept(bank -> {
                        if (bank == null) {
                            ServerPlayerUtilities.printToClientConsole(playerID, BankSystemTextMessages.getItemNotAllowedMessage(itemID.getName()));
                            return;
                        }

                        //long withdrawAmount = amount;
                        final long withdrawAmountFinal = BankManager.convertToRawAmountStatic(amount);

                        CompletableFuture<Long> balanceFuture = bank.getBalanceAsync();

                        balanceFuture.thenAccept(balance -> {
                            long withdrawAmount = Math.min(withdrawAmountFinal, balance);
                            if (withdrawAmount > 0) {
                                long itemsToDepositInInventory = (long)Math.floor(BankManager.convertToRealAmountStatic(withdrawAmount));
                                long addedAmount = inventory.addItem(itemID, itemsToDepositInInventory);
                                if (addedAmount > 0) {

                                    CompletableFuture<BankStatus> withdrawResult = bank.withdrawAsync(BankManager.convertToRawAmountStatic(addedAmount));
                                    withdrawResult.thenAccept(withdraw -> {
                                        if (withdraw != BankStatus.SUCCESS) {
                                            // error
                                            error("Failed to withdraw " + ServerBank.getNormalizedAmountStatic(BankManager.convertToRawAmountStatic(addedAmount)) +
                                                    " " + itemID + " from bank account of user " + playerID +" : "+withdraw);
                                            inventory.removeItem(itemID, addedAmount);
                                        }
                                    });
                                }
                            }
                        });
                    });
                }
                // mark the block entity for saving
                setChanged();
            });
        });
    }



    private static void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[BankTerminalBlockEntity] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankTerminalBlockEntity] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankTerminalBlockEntity] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[BankTerminalBlockEntity] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[BankTerminalBlockEntity] " + msg);
    }
}