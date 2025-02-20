package net.kroia.banksystem.entity.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class BankTerminalBlockEntity  extends BlockEntity implements MenuProvider {
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
            BankUser bank = ServerBankManager.getUser(playerID);
            if(bank == null) {
                // Create bank account for this item if it can be used for banking
                ArrayList<ItemID> keys = new ArrayList<>();
                String userName = PlayerUtilities.getOnlinePlayer(playerID).getName().getString();
                keys.add(itemID);
                bank = ServerBankManager.createUser(playerID, userName, keys, true,0 );
            }

            ItemID bankITemID = itemID;
            if(MoneyItem.isMoney(itemID))
            {
                bankITemID = MoneyBank.ITEM_ID;
            }
            Bank bankAccount = bank.getBank(bankITemID);
            if(bankAccount == null) {
                // Create item bank account
                bankAccount = bank.createItemBank(bankITemID, 0);
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
            BankUser bank = ServerBankManager.getUser(playerID);
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

                Bank bankAccount = bank.getBank(bankITemID);
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
                        if(bankAccount.deposit(depositAmount) == Bank.Status.SUCCESS) {
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
                    amount = Math.min(amount, amountToProcess);
                    amount = Math.min(amount, bankAccount.getBalance());
                    if(amount <= 0) {
                        cancelTask(itemID);
                        continue;
                    }
                    long addedAmount = inventory.addItem(itemID, amount);
                    if(addedAmount > 0)
                    {
                        if(bankAccount.withdraw(addedAmount) != Bank.Status.SUCCESS)
                        {
                            // error
                            BankSystemMod.LOGGER.error("Failed to withdraw " + addedAmount + " " + itemID + " from bank account of user " + playerID);
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
    public static class TerminalInventory implements ServerSaveable, Container {
        private final BankTerminalBlockEntity blockEntity;
        private final ArrayList<ItemStack> inventory;
        public TerminalInventory(BankTerminalBlockEntity blockEntity, int size) {
            this.blockEntity = blockEntity;
            inventory = new ArrayList<>(size);
            for(int i = 0; i < size; i++)
            {
                inventory.add(ItemStack.EMPTY);
            }
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
                    freeSpace+=64;
                    continue;
                }
                if(stack.isDamaged() || stack.isEnchanted())
                    continue;

                String stackItemID = ItemUtilities.getItemID(stack.getItem());

                // Compare the ResourceLocation to the provided string
                if (stackItemID != null && stackItemID.equals(itemID)) {
                    // Check if the stack can fit the amount
                    freeSpace += stack.getMaxStackSize() - stack.getCount();
                }
            }

            return freeSpace;
        }

        // Only counts items that are not damaged or enchanted
        public HashMap<ItemID, Long> getItemCount()
        {
            HashMap<ItemID, Long> items = new HashMap<>();
            for (int i = 0; i < this.getContainerSize(); i++) {
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted()) {
                    continue;
                }

                // Get the item's ResourceLocation
                ItemID itemID = new ItemID(stack);

                // Compare the ResourceLocation to the provided string
                // Check if the stack can fit the amount
                if(!items.containsKey(itemID))
                    items.put(itemID, (long)stack.getCount());
                else
                    items.put(itemID, items.get(itemID) + stack.getCount());

            }
            return items;
        }
        // Only counts items that are not damaged or enchanted
        public long getItemCount(ItemID itemID)
        {
            long count = 0;
            for (int i = 0; i < this.getContainerSize(); i++) {
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted()) {
                    continue;
                }

                // Get the item's ResourceLocation
                ItemID _itemID = new ItemID(stack);

                // Compare the ResourceLocation to the provided string
                if (_itemID.equals(itemID)) {
                    // Check if the stack can fit the amount)
                    count += stack.getCount();
                }
            }
            return count;
        }

        // Only adds items that are not damaged or enchanted
        public long addItem(ItemID ItemID, long amount)
        {
            if(amount <=0)
                return 0;
            long orgAmount = amount;
            for (int i = 0; i < this.getContainerSize(); i++) {
                if(amount <= 0)
                    return orgAmount;
                ItemStack stack = this.getItem(i);

                // If the slot is empty, it has space
                if (stack.isEmpty()) {
                    int stackSize = (int)Math.min(amount, 64);
                    amount -= stackSize;
                    ItemStack itemStack = ItemID.getStack().copy();
                    itemStack.setCount(stackSize);
                    this.setStackInSlot(i, itemStack);
                    continue;
                }else if(stack.isDamaged() || stack.isEnchanted())
                    continue;

                // Get the item's ResourceLocation
                ItemID itemID = new ItemID(stack);

                // Compare the ResourceLocation to the provided string
                if (itemID != null && itemID.equals(ItemID)) {
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
                ItemID _itemID = new ItemID(stack);

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
                CompoundTag stackTag = new CompoundTag();
                stack.save(stackTag);
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
                ItemStack stack = ItemStack.of(stackTag);
                if(stack.isEmpty())
                    return false;
                inventory.set(slot, stack);
            }
            return false;
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
        public ItemStack getItem(int slot) {
            if(slot < 0 || slot >= inventory.size())
                return null;
            return inventory.get(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            if(slot < 0 || slot >= inventory.size())
                return null;
            ItemStack stack = inventory.get(slot);
            if(stack.isEmpty())
                return null;
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
        public ItemStack removeItemNoUpdate(int slot) {
            if(slot < 0 || slot >= inventory.size())
                return null;
            ItemStack stack = inventory.get(slot);
            if(stack.isEmpty())
                return null;
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
        private TransferTask transferTask;

        private final BankTerminalBlockEntity blockEntity;
        public PlayerData(UUID playerID, BankTerminalBlockEntity blockEntity)
        {
            this.blockEntity = blockEntity;
            this.playerID = playerID;
            this.inventory = new TerminalInventory(blockEntity, 27);
            this.transferTask = new TransferTask(blockEntity, playerID, new HashMap<>());
        }
        private PlayerData(BankTerminalBlockEntity blockEntity)
        {
            this.blockEntity = blockEntity;
            playerID = new UUID(0, 0);
            inventory = new TerminalInventory(blockEntity, 27);
            transferTask = new TransferTask(blockEntity);
        }
        public UUID getPlayerID() {
            return playerID;
        }
        public TerminalInventory getInventory() {
            return inventory;
        }
        public TransferTask getTransferTask() {
            return transferTask;
        }

        public static PlayerData createFromTag(BankTerminalBlockEntity blockEntity, CompoundTag tag)
        {
            PlayerData playerData = new PlayerData(blockEntity);
            if(playerData.load(tag))
                return playerData;
            return null;
        }

        @Override
        public boolean save(CompoundTag tag) {
            tag.putUUID("PlayerID", playerID);
            CompoundTag inventoryTag = new CompoundTag();
            if(!inventory.save(inventoryTag))
                return false;
            tag.put("Inventory",inventoryTag);
            CompoundTag transferTaskTag = new CompoundTag();
            if(!transferTask.save(transferTaskTag))
                return false;
            tag.put("TransferTask", transferTaskTag);
            return true;
        }

        @Override
        public boolean load(CompoundTag tag) {
            if(tag == null)
                return false;
            if(!tag.contains("PlayerID") || !tag.contains("Inventory") || !tag.contains("TransferTask"))
                return false;
            playerID = tag.getUUID("PlayerID");
            CompoundTag inventoryTag = tag.getCompound("Inventory");
            inventory.load(inventoryTag);
            CompoundTag transferTaskTag = tag.getCompound("TransferTask");
            transferTask = TransferTask.createFromTag(blockEntity,transferTaskTag);
            return transferTask != null;
        }
    }

    private static final Component TITLE =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".bank_terminal_block_entity");

    private final HashMap<UUID, PlayerData> playerDataTable = new HashMap<>();

    ;
    private int lastTickCounter = 0;
    private int tickCounter = 0;

    public BankTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.BANK_TERMINAL_BLOCK_ENTITY.get(), pos, state);
    }


    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        CompoundTag data = nbt.getCompound(BankSystemMod.MOD_ID);

       // this.inventory.deserializeNBT(tutorialmodData.getCompound("Inventory"));
        //transferTickAmount = data.getInt("TransferTickAmount");

        ListTag playerDataTag = data.getList("PlayerData", ListTag.TAG_COMPOUND);
        for(int i = 0; i < playerDataTag.size(); i++)
        {
            CompoundTag playerDataCompound = playerDataTag.getCompound(i);
            PlayerData playerData = PlayerData.createFromTag(this, playerDataCompound);
            if(playerData != null)
            {
                playerDataTable.put(playerData.getPlayerID(), playerData);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        CompoundTag data = new CompoundTag();
        //tutorialmodData.put("Inventory", this.inventory.serializeNBT());
        //data.putInt("TransferTickAmount", transferTickAmount);
        ListTag playerInventoriesTag = new ListTag();
        for(UUID playerID : playerDataTable.keySet())
        {
            CompoundTag dataTag = new CompoundTag();
            if(playerDataTable.get(playerID).save(dataTag))
            {
                playerInventoriesTag.add(dataTag);
            }
        }
        data.put("PlayerData", playerInventoriesTag);
        nbt.put(BankSystemMod.MOD_ID, data);
    }
/*
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap) {
        return super.getCapability(cap);


        //return cap == ForgeCapabilities.ITEM_HANDLER ? this.optional.cast() : super.getCapability(cap);
    }*/

    /*@Override
    public void invalidateCaps() {
        super.invalidateCaps();
        //this.optional.invalidate();
    }*/


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

    @Override
    public Component getDisplayName() {
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
        String userNameStr  = player.getName().getString();
        BankUser user = ServerBankManager.getUser(player.getUUID());
        if (user == null) {
            BankSystemMod.LOGGER.error("BankUser is null for user: " + userNameStr);
            return;
        }

        PlayerData playerData = getPlayerData(player.getUUID());
        TerminalInventory inventory = playerData.getInventory();

        HashMap<ItemID, Long> items;
        int sendToMarketSign = 1;
        if(packet.isSendItemsToMarket())
        {
            items = inventory.getItemCount();
            sendToMarketSign = -1;
        }
        else
        {
            // Send to inventory
            items = packet.getItemTransferFromMarket();
        }
        for(ItemID itemID : items.keySet()) {
            long amount = items.get(itemID);
            playerData.transferTask.createTask(itemID, (int)amount*sendToMarketSign);
        }

        // mark the block entity for saving
        setChanged();
        SyncBankDataPacket.sendPacket(player);
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        // Your block entity logic here
        //System.out.println("Block entity is ticking!");
        tickCounter++;
        if(tickCounter - lastTickCounter >= BankSystemModSettings.Bank.ITEM_TRANSFER_TICK_INTERVAL) {
            lastTickCounter = tickCounter;
            for(UUID playerID : playerDataTable.keySet())
            {
                PlayerData playerData = playerDataTable.get(playerID);
                TransferTask task = playerData.getTransferTask();
                if(task.taskCount() > 0)
                {
                    boolean transferTheWholeItemStack = BankSystemModSettings.Bank.ITEM_TRANSFER_TICK_INTERVAL == 0;
                    task.processTaskStep(1, transferTheWholeItemStack);
                }
            }
        }
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
        if(level.isClientSide)
            return;
        if (t instanceof BankTerminalBlockEntity blockEntity) {
            blockEntity.tick(level, blockPos, blockState);
        }
    }


}