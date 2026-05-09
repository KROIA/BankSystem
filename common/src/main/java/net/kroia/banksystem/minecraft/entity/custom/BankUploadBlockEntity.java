package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.minecraft.block.custom.BankUploadBlock;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.menu.custom.BankUploadContainerMenu;
import net.kroia.banksystem.networking.entity.SyncBankUploadDataPacket;
import net.kroia.banksystem.networking.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BankUploadBlockEntity extends BaseContainerBlockEntity implements MenuProvider {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
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

    private static final Component TITLE =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".bank_upload_block");

    private final SimpleContainer inventory = new ControlledContainer(27); // 27 slots like a chest
    private boolean sendingEnabled = false;
    private boolean dropIfNotBankable = false;
    private int bankAccountNumber = 0; // Not used, but kept for compatibility

    private UUID playerOwner = null;

    private int tickCounter = 0;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankUploadBlockEntity.BACKEND_INSTANCES = backend;
    }


    public BankUploadBlockEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.BANK_UPLOAD_BLOCK_ENTITY.get(), pos, state);
    }

    public Container getInventory() {
        return inventory;
    }
    public int getBankAccountNumber() {
        return bankAccountNumber;
    }

    public void update()
    {
        sendInventoryToBank();
    }

    // The tick method
    public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
        if(t instanceof BankUploadBlockEntity blockEntity)
        {
            if (!level.isClientSide) { // Ensure this only runs on the server
                blockEntity.tickCounter++;

                int targetTickCount = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get();
                if (blockEntity.tickCounter >= targetTickCount) {
                    blockEntity.tickCounter = 0;
                    blockEntity.update();
                }
            }
        }
    }
    @Override
    public void setRemoved() {
        super.setRemoved();
        this.inventory.stopOpen(null);
    }

    @Override
    public void loadAdditional(@NotNull CompoundTag tag, HolderLookup.Provider pRegistries) {
        super.loadAdditional(tag, pRegistries);
        inventory.fromTag(tag.getList("Items", 10), pRegistries);
        sendingEnabled = tag.getBoolean("SendingEnabled");
        dropIfNotBankable = tag.getBoolean("DropIfNotBankable");
        if(tag.contains("BankAccountNumber"))
        {
            bankAccountNumber = tag.getInt("BankAccountNumber");
        }
        else
            bankAccountNumber = 0;
        playerOwner = null;
        if(tag.contains("PlayerOwner"))
        {
            playerOwner = tag.getUUID("PlayerOwner");
        }
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.Provider pRegistries) {
        super.saveAdditional(tag, pRegistries);
        tag.put("Items", inventory.createTag(pRegistries));
        tag.putBoolean("SendingEnabled", sendingEnabled);
        tag.putBoolean("DropIfNotBankable", dropIfNotBankable);
        tag.putInt("BankAccountNumber", bankAccountNumber);
        if(playerOwner != null)
        {
            tag.putUUID("PlayerOwner", playerOwner);
        }
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.banksystem.bank_upload");
    }



    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return null;
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
    public ItemStack getItem(int slot) {
        return inventory.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return inventory.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
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
        /*if(this.sendingEnabled)
        {
            sendInventoryToBank();
        }*/
    }

    public void dropContents() {
        Containers.dropContents(this.getLevel(), this.getBlockPos(), this.inventory);
    }
    public void dropItem(ItemStack stack) {
        Containers.dropItemStack(Objects.requireNonNull(this.getLevel()), this.getBlockPos().getX(), this.getBlockPos().getY(), this.getBlockPos().getZ(), stack);
    }

    public void redstoneSignalChanged(boolean isPowered) {
        this.sendingEnabled = isPowered;
        if (level == null) return;
        BlockState blockState = level.getBlockState(worldPosition);
        if (blockState.getBlock() instanceof BankUploadBlock) {
            level.setBlock(worldPosition, blockState.setValue(BankUploadBlock.SENDING_STATE, (this.sendingEnabled?BankUploadBlock.SendingState.SENDING:BankUploadBlock.SendingState.NOT_SENDING)), 3);
        }
        if(this.sendingEnabled)
        {
            this.tickCounter = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get();
        }
        /*if(this.sendingEnabled) {
            sendInventoryToBank();
        }*/
    }

    private void setPlayerOwner(UUID playerOwner) {
        this.playerOwner = playerOwner;
        if(this.playerOwner != null)
        {
            if(level != null)
            {
                BlockState blockState = level.getBlockState(worldPosition);
                if (blockState.getBlock() instanceof BankUploadBlock) {
                    level.setBlock(worldPosition, blockState.setValue(BankUploadBlock.CONNECTION_STATE, BankUploadBlock.ConnectionState.CONNECTED), 3);
                }
                if(this.sendingEnabled)
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
                if (blockState.getBlock() instanceof BankUploadBlock) {
                    level.setBlock(worldPosition, blockState.setValue(BankUploadBlock.CONNECTION_STATE, BankUploadBlock.ConnectionState.NOT_CONNECTED), 3);
                }
            }
        }
    }
    public UUID getPlayerOwner() {
        return playerOwner;
    }

    public boolean isSendingEnabled() {
        return sendingEnabled;
    }
    public boolean doesDropIfNotBankable() {
        return dropIfNotBankable;
    }

    private void sendInventoryToBank()
    {
        if(!this.sendingEnabled)
            return;
        if(playerOwner == null || !hasItemsInInventory())
            return;
        CompletableFuture<IAsyncBankAccount> accountFuture = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getBankAccountAsync(bankAccountNumber);
        accountFuture.thenAccept(account->{
            if(account == null)
                return;

            CompletableFuture<Boolean> hasPermissionFuture = account.hasPermissionAsync(playerOwner, BankPermission.DEPOSIT.getValue());
            hasPermissionFuture.thenAccept(hasPermission->{
                if(!hasPermission)
                {
                    return; // Player does not have permission to deposit
                }

                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    final int finalI = i;
                    ItemStack stack = inventory.getItem(i);
                    if(!stack.isEmpty())
                    {
                        // Ignore damaged or enchanted items
                        if(stack.isDamaged() || stack.isEnchanted())
                        {
                            if(dropIfNotBankable){
                                dropItem(stack);
                                inventory.setItem(i, ItemStack.EMPTY);
                            }
                            else {
                                continue;
                            }
                        }

                        // Clear the slot immediately to prevent double-read on next tick
                        inventory.setItem(i, ItemStack.EMPTY);

                        ItemID itemID = ItemID.of(stack);
                        CompletableFuture<@Nullable IAsyncBank> itemBankFuture = account.getOrCreateBankAsync(itemID);

                        itemBankFuture.thenAccept(itemBank->{
                            if(itemBank == null)
                            {
                                if(dropIfNotBankable){
                                    dropItem(stack);
                                } else {
                                    inventory.setItem(finalI, stack); // Restore item on failure
                                }
                                return;
                            }
                            long amount = stack.getCount();
                            if(MoneyItem.isMoney(itemID))
                            {
                                amount *= ((MoneyItem)stack.getItem()).worth();
                                CompletableFuture<@Nullable IAsyncBank> moneyBankFuture = account.getOrCreateBankAsync(MoneyItem.getItemID());
                                final long finalAmount = amount;
                                moneyBankFuture.thenAccept(moneyBank->{
                                    if(moneyBank != null) {
                                        CompletableFuture<BankStatus> depositStatusFuture = moneyBank.depositAsync(finalAmount);
                                        depositStatusFuture.thenAccept(depositStatus->{
                                            if(depositStatus != BankStatus.SUCCESS) {
                                                inventory.setItem(finalI, stack); // Restore item on failure
                                            }
                                        });
                                    }else if(dropIfNotBankable){
                                        dropItem(stack);
                                    } else {
                                        inventory.setItem(finalI, stack); // Restore item on failure
                                    }
                                });
                            }
                            else
                            {
                                CompletableFuture<BankStatus> depositStatusFuture = itemBank.depositRealAsync((double)amount);
                                depositStatusFuture.thenAccept(depositStatus->{
                                    if(depositStatus != BankStatus.SUCCESS) {
                                        inventory.setItem(finalI, stack); // Restore item on failure
                                    }
                                });
                            }


                        });
                    }
                }
                setChanged();
            });
        });
    }
    boolean hasItemsInInventory()
    {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if(!stack.isEmpty()) {
                return true;
            }
        }
        return false;
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
        return new BankUploadContainerMenu(pContainerId, pPlayerInventory, this);
    }

    public void handlePacket(ServerPlayer sender, UpdateBankUploadBlockEntityPacket packet)
    {
        UUID playerOwner = getPlayerOwner();
        boolean sendUpdate = false;
        if(playerOwner == null || playerOwner.equals(sender.getUUID())) {
            dropIfNotBankable = packet.isDropIfNotBankable();
            bankAccountNumber = packet.getBankAccountNumber();
            setPlayerOwner(packet.isOwned() ? sender.getUUID() : null);
            sendUpdate = true;
        }
       /* if(sendingEnabled && getPlayerOwner() != null)
        {
            sendInventoryToBank();
        }*/


        setChanged();
        if(sendUpdate)
        {
            SyncBankUploadDataPacket.sendPacket(sender, this);
        }
    }
}
