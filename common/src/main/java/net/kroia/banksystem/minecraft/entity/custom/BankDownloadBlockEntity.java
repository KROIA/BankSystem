package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.util.ItemStackNbt;
import net.kroia.modutilities.NbtCompat;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.block.custom.BankDownloadBlock;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.menu.custom.BankDownloadContainerMenu;
import net.kroia.banksystem.networking.entity.SyncBankDownloadDataPacket;
import net.kroia.banksystem.networking.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.util.ContainerItemInsertion;
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
        public static final String OWNER = "U";
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
            ItemID itemID = ItemID.createFromTag(NbtCompat.getCompound(tag, TAGS.ITEM_ID));
            int targetAmount = NbtCompat.getInt(tag, TAGS.TARGET_AMOUNT);
            WithdrawCondition condition;
            try {
                condition = WithdrawCondition.valueOf(NbtCompat.getString(tag, TAGS.CONDITION));
            } catch (IllegalArgumentException e) {
                return null;
            }
            long conditionValue = NbtCompat.getLong(tag, TAGS.CONDITION_VALUE);
            return new WithdrawOrder(itemID, targetAmount, condition, conditionValue);
        }
    }

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private static final Component TITLE =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".bank_download_block");

    private final SimpleContainer inventory = new ControlledContainer(27); // 27 slots like a chest
    private boolean blockIsPowered = false;
    private boolean currentlyReceiving = false;

    /**
     * Task #141: the player who connected the block; each withdraw needs their WITHDRAW permission.
     * Blocks saved before 2.2.0 have none and stay idle until someone with WITHDRAW saves the settings again.
     */
    private @Nullable UUID playerOwner = null;
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

    @Override // mc<=1.21.5
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) { super.loadAdditional(tag, provider); loadState(tag, provider); } // mc<=1.21.5
    //@Override // mc>=1.21.6
    //public void loadAdditional(net.minecraft.world.level.storage.ValueInput input) { super.loadAdditional(input); loadState(net.kroia.modutilities.NbtCompat.readAll(input), input.lookup()); } // mc>=1.21.6

    /** Version-neutral load of this block entity's data (called from loadAdditional). */
    private void loadState(CompoundTag tag, HolderLookup.Provider provider) {
        bankAccountNumber = 0;
        withdrawOrders.clear();
        playerOwner = null;

        if(tag.contains("PlayerOwner") && tag.contains("TargetAmount"))
        {
            // Compatibility, convert to new Block data
            UUID playerOwner = NbtCompat.getUUID(tag, "PlayerOwner");
            this.playerOwner = playerOwner; // Task #141: the old format drew from this player's own personal account
            int targetAmount = NbtCompat.getInt(tag, "TargetAmount");
            ItemStackNbt.fromTag(inventory, NbtCompat.getList(tag, "Items", Tag.TAG_COMPOUND), provider);
            blockIsPowered = NbtCompat.getBoolean(tag, "RecievingEnabled");

            CompletableFuture<IAsyncBankAccount> account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getPersonalBankAccountAsync(playerOwner);
            account.thenAccept(bankAccount -> {
                if(bankAccount == null)
                    return;
                bankAccountNumber = bankAccount.getAccountNumberAsync();
                if(tag.contains("ItemID")) {
                    ItemID itemID = ItemID.createFromTag(NbtCompat.getCompound(tag, "ItemID"));
                    if(itemID != null) {
                        WithdrawOrder order = new WithdrawOrder(itemID, targetAmount, WithdrawCondition.NONE, 0);
                        withdrawOrders.add(order);
                    }
                }
            });
            return;
        }

        if(tag.contains(TAGS.INVENTORY_ITEMS))
            ItemStackNbt.fromTag(inventory, NbtCompat.getList(tag, TAGS.INVENTORY_ITEMS, Tag.TAG_COMPOUND), provider);

        if(tag.contains(TAGS.BLOCK_IS_POWERED))
            blockIsPowered = NbtCompat.getBoolean(tag, TAGS.BLOCK_IS_POWERED);

        if(tag.contains(TAGS.BANK_ACCOUNT_NUMBER))
            bankAccountNumber = NbtCompat.getInt(tag, TAGS.BANK_ACCOUNT_NUMBER);
        else
            bankAccountNumber = 0; // Reset account number if not present

        if(tag.contains(TAGS.OWNER))
            playerOwner = NbtCompat.getUUID(tag, TAGS.OWNER);

        if(tag.contains(TAGS.WITHDRAW_ORDERS)) {
            ListTag ordersList = NbtCompat.getList(tag, TAGS.WITHDRAW_ORDERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < ordersList.size(); i++) {
                CompoundTag orderTag = NbtCompat.getCompound(ordersList, i);
                WithdrawOrder order = WithdrawOrder.fromTag(orderTag);
                if(order != null) {
                    withdrawOrders.add(order);
                }
            }
        }
    }

    @Override // mc<=1.21.5
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) { super.saveAdditional(tag, provider); saveState(tag, provider); } // mc<=1.21.5
    //@Override // mc>=1.21.6
    //protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) { super.saveAdditional(output); CompoundTag tag = new CompoundTag(); saveState(tag, level != null ? level.registryAccess() : net.kroia.modutilities.UtilitiesPlatform.getRegistryAccess()); net.kroia.modutilities.NbtCompat.writeAll(output, tag); } // mc>=1.21.6

    /** Version-neutral save of this block entity's data (called from saveAdditional). */
    private void saveState(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put(TAGS.INVENTORY_ITEMS, ItemStackNbt.createTag(inventory, provider));
        tag.putBoolean(TAGS.BLOCK_IS_POWERED, blockIsPowered);
        tag.putInt(TAGS.BANK_ACCOUNT_NUMBER, bankAccountNumber);
        if(playerOwner != null)
            NbtCompat.putUUID(tag, TAGS.OWNER, playerOwner);
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

    //@Override // mc>=1.21.5
    //public void preRemoveSideEffects(BlockPos pos, BlockState state) { dropContents(); } // mc>=1.21.5

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
            this.tickCounter = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get();
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
                    this.tickCounter = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get();
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
        return hasPermissionToOpenBlock(player.getUUID());
    }
    /** WITHDRAW on the connected account; true while the block is not connected. Also gates every settings change (Task #141). */
    public CompletableFuture<Boolean> hasPermissionToOpenBlock(UUID player)
    {
        CompletableFuture<Boolean> future =  new CompletableFuture<>();
        if(bankAccountNumber <= 0) {
            future.complete(true);
            return future;
        }
        // Task #169: master link down, the account cannot be checked. Deny, and keep the binding
        // (a null answer below would otherwise unbind the block).
        if(!net.kroia.banksystem.util.MultiServerUtils.canInteractWithBankSystem()) {
            future.complete(false);
            return future;
        }
        IAsyncBankManager asyncBankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        return asyncBankManager.getBankAccountAsync(bankAccountNumber).thenCompose(bankAccount ->
        {
            if(bankAccount == null) {
                bankAccountNumber = 0;
                return CompletableFuture.completedFuture(true);
            }
            return bankAccount.hasPermissionAsync(player, BankPermission.WITHDRAW.getValue());
        }).exceptionally(ex -> false); // failed request: deny, keep the binding
    }

    public void update()
    {
        receiveItemsFromBank();
    }

    // The tick method
    public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
        if(t instanceof BankDownloadBlockEntity blockEntity)
        {
            if (!level.isClientSide()) { // Ensure this only runs on the server
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
        if(!blockIsPowered || currentlyReceiving || playerOwner == null)
            return;
        // Task #169: master link down -> getBankAccountAsync answers null, which must not count
        // as "account missing" (that unbinds the block). Skip the cycle, keep the binding.
        if(!net.kroia.banksystem.util.MultiServerUtils.canInteractWithBankSystem())
            return;

        // Held across the account lookup and the owner check so a slow (slave) round trip cannot start a second cycle.
        currentlyReceiving = true;
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankAccountAsync(bankAccountNumber).thenCompose(accountResult -> {
            if(accountResult == null) {
                bankAccountNumber = 0; // Reset account number if it does not exist
                setBockstate_connected(false);
                return CompletableFuture.completedFuture((IAsyncBankAccount) null);
            }
            return ownerCanWithdraw(accountResult).thenApply(allowed -> allowed ? accountResult : null);
        }).whenComplete((accountResult, ex) -> {
            if(accountResult != null)
                processWithdrawOrders(accountResult);
            else
                currentlyReceiving = false;
        });
    }

    /** Task #141: the player who connected the block still holds WITHDRAW on the account. False without an owner. */
    public CompletableFuture<Boolean> ownerCanWithdraw(IAsyncBankAccount account)
    {
        UUID owner = playerOwner;
        return owner == null ? CompletableFuture.completedFuture(false)
                : account.hasPermissionAsync(owner, BankPermission.WITHDRAW.getValue());
    }

    private void processWithdrawOrders(IAsyncBankAccount accountResult)
    {
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
        // Issue #79: a failed request completes the order as "nothing withdrawn"; a pending result would leave
        // currentlyReceiving stuck and the block dead until reload. No unlock on any failure: the outcome is unknown.
        itemBankDataFuture.exceptionally(ex -> { result.complete(false); return null; });
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
            itemBankFuture.exceptionally(ex -> { result.complete(false); return null; });
            itemBankFuture.thenAccept((itemBank) -> {
                if (itemBank == null) { result.complete(false); return; }
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
        // Task #38b: per-slot raw-units-per-item ratio. For unbound slots this is
        // ITEM_FRACTION_SCALE_FACTOR (100) — unchanged behavior. For bound LC gold
        // slots this is 81 (from CoinAPI's runtime chain data), so 81 raw = 1 gold
        // and the withdraw math produces exactly 1 physical coin with zero dust.
        final long ratio = BankAccountBindings.getRawUnitsPerItem(bankAccountNumber, item);
        CompletableFuture<Long>  currentBalanceFuture = itemBank.getBalanceAsync();
        currentBalanceFuture.exceptionally(ex -> { result.complete(0); return null; }); // Issue #79: never leave result pending
        currentBalanceFuture.thenAccept((currentBalance) -> {
            int amountToFill = amountToFillIn;
            long amountToReserve = (long) amountToFill * ratio;
            if(amountToReserve > currentBalance) {
                amountToFill = (int) (currentBalance / ratio);
                amountToReserve = (long) amountToFill * ratio;
            }
            if(amountToReserve <= 0)
            {
                result.complete(0);
                return;
            }

            CompletableFuture<BankStatus> lockStatusFuture = itemBank.lockAmountAsync(amountToReserve);
            final int amountToFillFinal = amountToFill;
            final long amountToReserveFinal = amountToReserve;
            // Issue #87: whenComplete, so a failed lock still completes the block's result.
            lockStatusFuture.whenComplete((lockStatus, lockError) -> {
                if (lockError != null) {
                    // Nothing inserted. No unlock: the master may have applied the lock, and the lock is one
                    // counter (a blind unlock could free other holders' locks). Left for the admin.
                    BACKEND_INSTANCES.LOGGER.error("[BankDownloadBlockEntity] Lock of " + amountToReserveFinal + " (raw) " + item
                            + " at " + worldPosition.toShortString() + " has an unknown outcome; nothing withdrawn,"
                            + " any applied lock is left for the admin", lockError);
                    result.complete(0);
                    return;
                }
                if(lockStatus != BankStatus.SUCCESS) // Lock the amount to prevent other transactions from interfering
                {
                    result.complete(0);
                    return; // Failed to lock the amount, return 0
                }

                // Try to fill the inventory with the item. Keep the exact stack we insert so a
                // failed debit can take back the same (component-equal) items.
                ItemStack withdrawalStack = item.getStack();
                int filledAmount = (int) ContainerItemInsertion.insertWithdrawnItems(inventory, withdrawalStack, amountToFillFinal);
                if(filledAmount > 0) {
                    long amountToWithdraw = (long) filledAmount * ratio;
                    final net.kroia.banksystem.data.TransferJournal.Ref journal =
                            net.kroia.banksystem.data.TransferJournal.ref("download_block " + net.kroia.banksystem.data.DiagnosticsDatabase.at(level, worldPosition));
                    CompletableFuture<BankStatus> withdrawStatusFuture = net.kroia.banksystem.banking.ledger.TransactionLedger.tagged(net.kroia.banksystem.api.ledger.LedgerType.DOWNLOAD_WITHDRAW, playerOwner,
                            "download block " + worldPosition.toShortString(), journal,
                            () -> itemBank.withdrawLockedPreferedAsync(amountToWithdraw));
                    // whenComplete, not thenAccept: an exceptional withdraw must also roll back.
                    withdrawStatusFuture.whenComplete((withdrawStatus, withdrawError) -> {
                        if(withdrawStatus == BankStatus.SUCCESS) {
                            // Task #109 / BS-235: actor = the block's owner (SYSTEM without one), position in the note.
                            net.kroia.banksystem.banking.ledger.TransactionLedger.logRow(bankAccountNumber, net.kroia.banksystem.api.ledger.LedgerType.DOWNLOAD_WITHDRAW, item,
                                    amountToWithdraw, playerOwner != null ? net.kroia.banksystem.banking.ledger.TransactionLedger.player(playerOwner)
                                            : net.kroia.banksystem.api.ledger.LedgerActor.system(), null,
                                    "download block " + worldPosition.toShortString());
                            if(filledAmount != amountToFillFinal)
                            {
                                // If we filled less than requested, unlock the remaining amount
                                long remainingAmount = (long) (amountToFillFinal - filledAmount) * ratio;
                                itemBank.unlockAmountAsync(remainingAmount);
                            }
                            setChanged();
                            result.complete(filledAmount);
                            return; // Successfully withdrew and filled the inventory
                        } else {
                            // Issue #74: the debit failed, so take the inserted items back out —
                            // otherwise the block keeps items the account never paid for.
                            long removed = ContainerItemInsertion.removeItems(inventory, withdrawalStack, filledAmount);
                            // Items that already left the block (hopper) were not paid for: their part stays locked.
                            long escapedRaw = Math.max(0L, filledAmount - removed) * ratio;
                            if (removed < filledAmount)
                                BACKEND_INSTANCES.LOGGER.error("[BankDownloadBlockEntity] Withdraw of " + filledAmount + " " + item
                                        + " at " + worldPosition.toShortString() + " failed (" + (withdrawError != null ? withdrawError : withdrawStatus)
                                        + ") and only " + removed + " item(s) could be taken back out of the block; transfer "
                                        + journal.transferId() + ", " + escapedRaw + " (raw) left locked for the admin");
                            if (withdrawError == null) {
                                long unlock = Math.max(0L, amountToReserveFinal - escapedRaw);
                                if (unlock > 0)
                                    itemBank.unlockAmountAsync(unlock); // not applied: release our lock, minus the escaped items
                            } else {
                                // Task #126: still unknown after the master's status check. The master may have
                                // consumed the lock, and the lock is one counter: a blind unlock could free other
                                // holders' locks. Leave it for the admin (slave journal: TIMED_OUT).
                                BACKEND_INSTANCES.LOGGER.error("[BankDownloadBlockEntity] Withdraw of " + amountToWithdraw + " (raw) " + item
                                        + " at " + worldPosition.toShortString() + " has an unknown outcome (transfer " + journal.transferId()
                                        + "); items taken back, lock of " + amountToReserveFinal + " left for the admin", withdrawError);
                            }
                            setChanged();
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


    /**
     * Counts how many items of the given ItemID the block inventory holds, to decide how
     * far a withdraw order is from its target amount.
     * <p>
     * ItemID equality is intentional here: this is a ledger-style quantity check, not
     * physical stack placement. Component-distinct variants that share the ItemID (e.g. a
     * spoiled stack next to fresh ones) count toward the target — consistent with the
     * bank's identity semantics, and it prevents the block from endlessly topping up a
     * container that already holds the target quantity in "other-state" stacks.
     */
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
        applySettings(sender.getUUID(), packet.getAccountNr(), packet.getWithdrawOrders()).thenAccept(applied -> {
            if(applied)
                SyncBankDownloadDataPacket.sendPacket(sender, this);
        });
    }

    /**
     * Applies new settings from {@code sender}. Task #141: any change needs WITHDRAW on the currently connected
     * account (the same check that opens the menu); connecting also needs WITHDRAW on the new account and makes
     * the sender the owner whose permission every later withdraw is checked against.
     * @return true if the settings were applied
     */
    public CompletableFuture<Boolean> applySettings(UUID sender, int accountNr, List<WithdrawOrder> orders)
    {
        return hasPermissionToOpenBlock(sender).thenCompose(allowed -> {
            if(!allowed)
                return CompletableFuture.completedFuture(false);
            if(accountNr <= 0) {
                setSettings(0, null, orders);
                setBockstate_connected(false);
                return CompletableFuture.completedFuture(true);
            }
            return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankAccountAsync(accountNr).thenCompose(bankAccount -> {
                if(bankAccount == null) {
                    setBockstate_connected(false);
                    return CompletableFuture.completedFuture(false);
                }
                return bankAccount.hasPermissionAsync(sender, BankPermission.WITHDRAW.getValue()).thenApply(hasPermission -> {
                    if(!hasPermission) {
                        setBockstate_connected(false);
                        return false; // Player does not have permission to withdraw from this bank account
                    }
                    setSettings(accountNr, sender, orders);
                    setBockstate_connected(true);
                    return true;
                });
            });
        });
    }

    private void setSettings(int accountNr, @Nullable UUID owner, List<WithdrawOrder> orders)
    {
        this.bankAccountNumber = accountNr;
        this.playerOwner = owner;
        this.withdrawOrders.clear();
        for (WithdrawOrder order : orders) {
            if(order != null) {
                this.withdrawOrders.add(order);
            }
        }
        setChanged();
    }
}
