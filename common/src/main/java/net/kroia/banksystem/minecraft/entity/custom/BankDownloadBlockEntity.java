package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.block.custom.BankDownloadBlock;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.menu.custom.BankDownloadContainerMenu;
import net.kroia.banksystem.networking.entity.SyncBankDownloadDataPacket;
import net.kroia.banksystem.networking.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BankDownloadBlockEntity extends BaseContainerBlockEntity implements MenuProvider {

    private final static class TAGS
    {
        public static final String WITHDRAW_ORDERS = "O";
        public static final String BANK_ACCOUNT_NUMBER = "A";
        public static final String INVENTORY_ITEMS = "I";
        public static final String BLOCK_IS_POWERED = "P";
    }
    private class ControlledContainer extends SimpleContainer {
        public ControlledContainer(int size) {
            super(size);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            inventoryContentChanged();
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            super.setItem(slot, stack);
            setChanged();
        }
    }

    public enum WithdrawCondition
    {
        NONE,
        HAS_MORE_THEN,
        HAS_LESS_THEN,
    }
    public static class WithdrawOrder
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, WithdrawOrder> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.nullable(ItemID.STREAM_CODEC), p -> p.itemID,
                ByteBufCodecs.INT, p -> p.targetAmount,
                ExtraCodecUtils.enumStreamCodec(WithdrawCondition.class), p -> p.condition,
                ByteBufCodecs.VAR_LONG, p -> p.conditionValue,
                WithdrawOrder::new
        );

        private static final class TAGS
        {
            public static final String ITEM_ID = "I";
            public static final String TARGET_AMOUNT = "T";
            public static final String CONDITION = "C";
            public static final String CONDITION_VALUE = "V";
        }


        public final ItemID itemID;
        public final int targetAmount;

        public final WithdrawCondition condition;
        public final long conditionValue;

        public WithdrawOrder(ItemID itemID, int targetAmount, WithdrawCondition condition, long conditionValue) {
            this.itemID = itemID;
            this.targetAmount = targetAmount;
            this.condition = condition;
            this.conditionValue = conditionValue;
        }
        public boolean meetsCondition(long currentRealBankBalance)
        {
            return switch (condition) {
                case NONE -> true; // No condition, always true
                case HAS_MORE_THEN -> currentRealBankBalance > conditionValue;
                case HAS_LESS_THEN -> currentRealBankBalance < conditionValue;
                default -> false; // Unknown condition, treat as false
            };
        }


        /*@Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(itemID != null);
            if(itemID != null) {
                buf.writeItem(itemID.getStack());
            }
            buf.writeInt(targetAmount);
            buf.writeInt(condition.ordinal());
            buf.writeLong(conditionValue);
        }
        public static @Nullable WithdrawOrder decode(FriendlyByteBuf buf) {
            ItemID itemID = null;
            if(buf.readBoolean()) {
                itemID = new ItemID(buf.readItem());
            }
            else
            {
                return null;
            }
            int targetAmount = buf.readInt();
            WithdrawCondition condition = WithdrawCondition.values()[buf.readInt()];
            long conditionValue = buf.readLong();
            return new WithdrawOrder(itemID, targetAmount, condition, conditionValue);
        }*/

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            if(itemID != null) {
                CompoundTag itemTag = new CompoundTag();
                itemID.save(itemTag);
                tag.put(TAGS.ITEM_ID, itemTag);
            }
            tag.putInt(TAGS.TARGET_AMOUNT, targetAmount);
            tag.putString(TAGS.CONDITION, condition.name());
            tag.putLong(TAGS.CONDITION_VALUE, conditionValue);
            return tag;
        }

        public static @Nullable WithdrawOrder fromTag(CompoundTag tag) {
            if(     !tag.contains(TAGS.ITEM_ID) ||
                    !tag.contains(TAGS.TARGET_AMOUNT) ||
                    !tag.contains(TAGS.CONDITION) ||
                    !tag.contains(TAGS.CONDITION_VALUE))
                return null;
            ItemID itemID = ItemID.createFromTag(tag.getCompound(TAGS.ITEM_ID));
            int targetAmount = tag.getInt(TAGS.TARGET_AMOUNT);
            WithdrawCondition condition;
            try {
                condition = WithdrawCondition.valueOf(tag.getString(TAGS.CONDITION));
            } catch (IllegalArgumentException e) {
                return null;
            }
            long conditionValue = tag.getLong(TAGS.CONDITION_VALUE);
            return new WithdrawOrder(itemID, targetAmount, condition, conditionValue);
        }
    }

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private static final Component TITLE =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".bank_download_block");

    private final SimpleContainer inventory = new ControlledContainer(27); // 27 slots like a chest
    private boolean blockIsPowered = false;
    private boolean currentlyReceiving = false;

    //private UUID playerOwner = null;
    //private ItemID itemID;
    //private int targetAmount;
    private int tickCounter = 0;
    private int bankAccountNumber = 0;
    private final List<WithdrawOrder> withdrawOrders = new ArrayList<>();

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankDownloadBlockEntity.BACKEND_INSTANCES = backend;
    }

    public BankDownloadBlockEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.BANK_DOWNLOAD_BLOCK_ENTITY.get(), pos, state);
    }

    public Container getInventory() {
        return inventory;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        this.inventory.stopOpen(null);
    }

    @Override
    public void loadAdditional(@NotNull CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        bankAccountNumber = 0;
        withdrawOrders.clear();

        if(tag.contains("PlayerOwner") && tag.contains("TargetAmount"))
        {
            // Compatibility, convert to new Block data
            UUID playerOwner = tag.getUUID("PlayerOwner");
            int targetAmount = tag.getInt("TargetAmount");
            inventory.fromTag(tag.getList("Items", Tag.TAG_COMPOUND), provider);
            blockIsPowered = tag.getBoolean("RecievingEnabled");

            CompletableFuture<IAsyncBankAccount> account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getPersonalBankAccountAsync(playerOwner);
            account.thenAccept(bankAccount -> {
                if(bankAccount == null)
                    return;
                bankAccountNumber = bankAccount.getAccountNumberAsync();
                if(tag.contains("ItemID")) {
                    ItemID itemID = ItemID.createFromTag(tag.getCompound("ItemID"));
                    if(itemID != null) {
                        WithdrawOrder order = new WithdrawOrder(itemID, targetAmount, WithdrawCondition.NONE, 0);
                        withdrawOrders.add(order);
                    }
                }
            });
            return;
        }

        if(tag.contains(TAGS.INVENTORY_ITEMS))
            inventory.fromTag(tag.getList(TAGS.INVENTORY_ITEMS, Tag.TAG_COMPOUND), provider);

        if(tag.contains(TAGS.BLOCK_IS_POWERED))
            blockIsPowered = tag.getBoolean(TAGS.BLOCK_IS_POWERED);

        if(tag.contains(TAGS.BANK_ACCOUNT_NUMBER))
            bankAccountNumber = tag.getInt(TAGS.BANK_ACCOUNT_NUMBER);
        else
            bankAccountNumber = 0; // Reset account number if not present

        if(tag.contains(TAGS.WITHDRAW_ORDERS)) {
            ListTag ordersList = tag.getList(TAGS.WITHDRAW_ORDERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < ordersList.size(); i++) {
                CompoundTag orderTag = ordersList.getCompound(i);
                WithdrawOrder order = WithdrawOrder.fromTag(orderTag);
                if(order != null) {
                    withdrawOrders.add(order);
                }
            }
        }
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.put(TAGS.INVENTORY_ITEMS, inventory.createTag(provider));
        tag.putBoolean(TAGS.BLOCK_IS_POWERED, blockIsPowered);
        tag.putInt(TAGS.BANK_ACCOUNT_NUMBER, bankAccountNumber);
        ListTag ordersList = new ListTag();
        for (WithdrawOrder order : withdrawOrders) {
            CompoundTag orderTag = order.toTag();
            if(orderTag != null) {
                ordersList.add(orderTag);
            }
        }
        tag.put(TAGS.WITHDRAW_ORDERS, ordersList);
    }

    @Override
    protected @NotNull Component getDefaultName() {
        return TITLE;
    }



    @Override
    protected @NotNull AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return createMenu(containerId, inventory, null);
    }

    @Override
    public int getContainerSize() {
        return inventory.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return inventory.isEmpty();
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        return inventory.getItem(slot);
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        return inventory.removeItem(slot, amount);
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        return inventory.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        inventory.setItem(slot, stack);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return inventory.getItems();
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        for(int i = 0; i < inventory.getContainerSize(); i++)
        {
            if(i < items.size())
                inventory.setItem(i, items.get(i));
            else
                inventory.setItem(i, ItemStack.EMPTY);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return inventory.stillValid(player);
    }

    @Override
    public void clearContent() {
        inventory.clearContent();
    }

    private void inventoryContentChanged()
    {

    }

    public void dropContents() {
        Containers.dropContents(this.getLevel(), this.getBlockPos(), this.inventory);
    }
    public void dropItem(ItemStack stack) {
        Containers.dropItemStack(Objects.requireNonNull(this.getLevel()), this.getBlockPos().getX(), this.getBlockPos().getY(), this.getBlockPos().getZ(), stack);
    }

    public void redstoneSignalChanged(boolean isPowered) {
        this.blockIsPowered = isPowered;
        setBlockState_receiving(this.blockIsPowered);
        if(this.blockIsPowered)
        {
            this.tickCounter = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get();
        }
    }

    /*private void setPlayerOwner(UUID playerOwner) {
        this.playerOwner = playerOwner;
        if(this.playerOwner != null)
        {
            if(level != null)
            {
                BlockState blockState = level.getBlockState(worldPosition);
                if (blockState.getBlock() instanceof BankDownloadBlock) {
                    level.setBlock(worldPosition, blockState.setValue(BankDownloadBlock.CONNECTION_STATE, BankDownloadBlock.ConnectionState.CONNECTED), 3);
                }
                if(this.receivingEnabled)
                {
                    this.tickCounter = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get();
                }
            }
        }
        else
        {
            if(level != null)
            {
                BlockState blockState = level.getBlockState(worldPosition);
                if (blockState.getBlock() instanceof BankDownloadBlock) {
                    level.setBlock(worldPosition, blockState.setValue(BankDownloadBlock.CONNECTION_STATE, BankDownloadBlock.ConnectionState.NOT_CONNECTED), 3);
                }
            }
        }
    }*/

    private void setBockstate_connected(boolean isConnected)
    {
        if (level == null) return;
        BlockState blockState = level.getBlockState(worldPosition);
        if (blockState.getBlock() instanceof BankDownloadBlock) {
            level.setBlock(worldPosition, blockState.setValue(BankDownloadBlock.CONNECTION_STATE, isConnected ? BankDownloadBlock.ConnectionState.CONNECTED : BankDownloadBlock.ConnectionState.NOT_CONNECTED), 3);
        }
    }
    private void setBlockState_receiving(boolean isReceiving) {
        if (level == null) return;
        BlockState blockState = level.getBlockState(worldPosition);
        if (blockState.getBlock() instanceof BankDownloadBlock) {
            level.setBlock(worldPosition, blockState.setValue(BankDownloadBlock.RECEIVING_STATE, isReceiving ? BankDownloadBlock.ReceivingState.RECEIVING : BankDownloadBlock.ReceivingState.NOT_RECEIVING), 3);
        }
    }
    //public UUID getPlayerOwner() {
    //    return playerOwner;
    //}

    public boolean isBlockIsPowered() {
        return blockIsPowered;
    }

    public List<WithdrawOrder> getWithdrawOrders() {
        return withdrawOrders;
    }
    public int getBlockInventorySlotCount() {
        return inventory.getContainerSize();
    }
    public int getBankAccountNumber() {
        return bankAccountNumber;
    }
    public CompletableFuture<Boolean> hasPermissionToOpenBlock(ServerPlayer player)
    {
        CompletableFuture<Boolean> future =  new CompletableFuture<>();
        if(bankAccountNumber <= 0) {
            future.complete(true);
            return future;
        }
        IAsyncBankManager asyncBankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        CompletableFuture<IAsyncBankAccount> account = asyncBankManager.getBankAccountAsync(bankAccountNumber);
        account.thenAccept((bankAccount) ->
        {
            if(bankAccount == null) {
                bankAccountNumber = 0;
                future.complete(true);
            }
            else {
                CompletableFuture<Boolean> hasPermissionFuture = bankAccount.hasPermissionAsync(player.getUUID(), BankPermission.WITHDRAW.getValue());
                hasPermissionFuture.thenAccept(future::complete);
            }
        });
        return future;
    }

    public void update()
    {
        receiveItemsFromBank();
    }

    // The tick method
    public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
        if(t instanceof BankDownloadBlockEntity blockEntity)
        {
            if (!level.isClientSide) { // Ensure this only runs on the server
                if(blockEntity.bankAccountNumber <= 0)
                    return;
                blockEntity.tickCounter++;

                int targetTickCount = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get();
                if (blockEntity.tickCounter >= targetTickCount) {
                    blockEntity.tickCounter = 0;
                    blockEntity.update();
                }
            }
        }
    }


    private void receiveItemsFromBank()
    {
        if(!blockIsPowered || currentlyReceiving)
            return;

        CompletableFuture<IAsyncBankAccount> account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankAccountAsync(bankAccountNumber);
        account.thenAccept(accountResult -> {
            if(accountResult == null) {
                bankAccountNumber = 0; // Reset account number if it does not exist
                setBockstate_connected(false);
                return;
            }

            currentlyReceiving = true;
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();
            for( WithdrawOrder order : withdrawOrders)
            {
                if(order != null) {
                    futures.add(processWithdrawOrder(order, accountResult));
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
                currentlyReceiving = false;
                setChanged();
            });
        });
    }



    private CompletableFuture<Boolean> processWithdrawOrder(WithdrawOrder order, IAsyncBankAccount account)
    {
        if(order.targetAmount <= 0)
            return CompletableFuture.completedFuture(false);

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        ItemID item = order.itemID;
        int itemsInInventory = countItemsInInventory(item);
        if(itemsInInventory >= order.targetAmount)
            return CompletableFuture.completedFuture(false);

        CompletableFuture<@Nullable BankData> itemBankDataFuture = account.getBankDataAsync(item);
        itemBankDataFuture.thenAccept((itemBankData) -> {
            if (itemBankData == null) {
                result.complete(false);
                return;
            }

            long realBankBalance = (long) Math.floor(itemBankData.getRealBalance());
            if (!order.meetsCondition(realBankBalance)) {
                result.complete(false);
                return; // Condition not met, do not withdraw
            }
            int amountToFill = Math.max(0, order.targetAmount - countItemsInInventory(item));
            if (amountToFill <= 0)
            {
                result.complete(false);
                return; // No need to withdraw, inventory already has enough items
            }



            CompletableFuture<@Nullable IAsyncBank> itemBankFuture = account.getBankAsync(item);
            itemBankFuture.thenAccept((itemBank) -> {
                CompletableFuture<Integer> filledAmountFuture = withdrawAndFillInventory(item, amountToFill, itemBank);
                filledAmountFuture.thenAccept((filledAmount) -> {
                    result.complete(filledAmount > 0);
                });
            });
        });
        return result;
    }

    private CompletableFuture<Integer> withdrawAndFillInventory(ItemID item, final int amountToFillIn, IAsyncBank itemBank)
    {
        CompletableFuture<Integer>  result = new CompletableFuture<>();
        CompletableFuture<Long>  currentBalanceFuture = itemBank.getBalanceAsync();
        currentBalanceFuture.thenAccept((currentBalance) -> {
            int amountToFill = amountToFillIn;
            long amountToReserve = BankManager.convertToRawAmountStatic(amountToFill);
            if(amountToReserve > currentBalance) {
                amountToFill = (int) (currentBalance / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
                amountToReserve = BankManager.convertToRawAmountStatic(amountToFill);
            }
            if(amountToReserve <= 0)
            {
                result.complete(0);
                return;
            }

            CompletableFuture<BankStatus> lockStatusFuture = itemBank.lockAmountAsync(amountToReserve);
            final int amountToFillFinal = amountToFill;
            final long amountToReserveFinal = amountToReserve;
            lockStatusFuture.thenAccept((lockStatus) -> {
                if(lockStatus != BankStatus.SUCCESS) // Lock the amount to prevent other transactions from interfering
                {
                    result.complete(0);
                    return; // Failed to lock the amount, return 0
                }

                // Try to fill the inventory with the item
                int filledAmount = tryFillInventory(item, amountToFillFinal);
                if(filledAmount > 0) {
                    long amountToWithdraw = BankManager.convertToRawAmountStatic(filledAmount);
                    CompletableFuture<BankStatus> withdrawStatusFuture = itemBank.withdrawLockedPreferedAsync(amountToWithdraw);
                    withdrawStatusFuture.thenAccept((withdrawStatus) -> {
                        if(withdrawStatus == BankStatus.SUCCESS) {
                            if(filledAmount != amountToFillFinal)
                            {
                                // If we filled less than requested, unlock the remaining amount
                                long remainingAmount = BankManager.convertToRawAmountStatic(amountToFillFinal - filledAmount);
                                itemBank.unlockAmountAsync(remainingAmount);
                            }
                            setChanged();
                            result.complete(filledAmount);
                            return; // Successfully withdrew and filled the inventory
                        } else {
                            itemBank.unlockAmountAsync(amountToReserveFinal); // Unlock the amount if withdrawal failed
                            result.complete(0);
                        }
                    });
                }
                else
                {
                    itemBank.unlockAmountAsync(amountToReserveFinal); // Unlock the amount if nothing was filled
                    result.complete(0);
                }
            });
        });

        return result;
    }

    private int tryFillInventory(ItemID item, int amountToFill)
    {
        if(item == null || amountToFill <= 0)
            return 0;

        ItemStack exampleStack = item.getStack();
        if(exampleStack == null || exampleStack.isEmpty())
            return 0;

        int stackSize = exampleStack.getMaxStackSize();
        int filledAmount = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (ItemID.of(stack).equals(item)) {
                //if (stack.isDamaged() || stack.isEnchanted())
                //    continue;
                int fillInStackAmount = stackSize - stack.getCount();
                int amountToFillInStack = Math.min(fillInStackAmount, amountToFill);
                if (amountToFillInStack > 0) {
                    stack.grow(amountToFillInStack);
                    filledAmount += amountToFillInStack;
                    amountToFill -= amountToFillInStack;
                }
            } else if (stack.isEmpty()) {
                int amountToFillInStack = Math.min(stackSize, amountToFill);
                if (amountToFillInStack > 0) {
                    inventory.setItem(i, new ItemStack(exampleStack.getItem(), amountToFillInStack));
                    filledAmount += amountToFillInStack;
                    amountToFill -= amountToFillInStack;
                }
            }
            if(amountToFill <= 0)
                break;
        }
        return filledAmount;
    }

    private int countItemsInInventory(ItemID itemID)
    {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if(!stack.isEmpty() && itemID.equals(ItemID.of(stack)) && !stack.isDamaged() && !stack.isEnchanted())
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    public MenuProvider getMenuProvider() {
        return this;
    }

    @Override
    public Component getDisplayName() {
        return TITLE;
    }

    @Override
    public AbstractContainerMenu createMenu(int pContainerId, Inventory pPlayerInventory, Player pPlayer) {
        return new BankDownloadContainerMenu(pContainerId, pPlayerInventory, this);
    }

    public void handlePacket(ServerPlayer sender, UpdateBankDownloadBlockEntityPacket packet)
    {
        final int finalAccountNr = packet.getAccountNr();
        if(finalAccountNr <= 0) {
            this.bankAccountNumber = 0;
            this.withdrawOrders.clear();
            List<WithdrawOrder> orders = packet.getWithdrawOrders();
            for (WithdrawOrder order : orders) {
                if(order != null) {
                    this.withdrawOrders.add(order);
                }
            }
            setChanged();
            setBockstate_connected(false);
            SyncBankDownloadDataPacket.sendPacket(sender, this);
            return;
        }
        UUID senderUUID = sender.getUUID();
        CompletableFuture<@Nullable IAsyncBankAccount> account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankAccountAsync(finalAccountNr);
        account.thenAccept(bankAccount -> {
            if(bankAccount == null) {
                setBockstate_connected(false);
                return;
            }
            CompletableFuture<Boolean> hasPermissionFuture = bankAccount.hasPermissionAsync(senderUUID, BankPermission.WITHDRAW.getValue());
            hasPermissionFuture.thenAccept((hasPermission) -> {
                if(!hasPermission) {
                    setBockstate_connected(false);
                    return; // Player does not have permission to withdraw from this bank account
                }

                this.bankAccountNumber = finalAccountNr;
                this.withdrawOrders.clear();
                List<WithdrawOrder> orders = packet.getWithdrawOrders();
                for (WithdrawOrder order : orders) {
                    if(order != null) {
                        this.withdrawOrders.add(order);
                    }
                }

                setChanged();
                setBockstate_connected(true);
                SyncBankDownloadDataPacket.sendPacket(sender, this);
            });
        });
    }
}
