package net.kroia.banksystem.entity.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.block.custom.BankDownloadBlock;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.menu.custom.BankDownloadContainerMenu;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDownloadDataPacket;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.core.BlockPos;
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

import java.util.Objects;
import java.util.UUID;

public class BankDownloadBlockEntity extends BaseContainerBlockEntity implements MenuProvider {
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
            Component.translatable("container." + BankSystemMod.MOD_ID + ".bank_download_block");

    private final SimpleContainer inventory = new ControlledContainer(27); // 27 slots like a chest
    private boolean receivingEnabled = false;
    private boolean currentlyReceiving = false;
    //private boolean dropIfNotBankable = false;

    private UUID playerOwner = null;
    private String itemID;
    private int targetAmount;
    public static int tickCounter = 0;

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
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        inventory.fromTag(tag.getList("Items", 10));
        receivingEnabled = tag.getBoolean("RecievingEnabled");
        itemID = null;
        if(tag.contains("ItemID"))
            itemID = tag.getString("ItemID");
        targetAmount = tag.getInt("TargetAmount");
        playerOwner = null;
        if(tag.contains("PlayerOwner"))
        {
            playerOwner = tag.getUUID("PlayerOwner");
        }
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Items", inventory.createTag());
        tag.putBoolean("RecievingEnabled", receivingEnabled);
        if(itemID != null)
            tag.putString("ItemID", itemID);
        tag.putInt("TargetAmount", targetAmount);
        if(playerOwner != null)
        {
            tag.putUUID("PlayerOwner", playerOwner);
        }
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.banksystem.bank_download");
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
    public boolean stillValid(Player player) {
        return inventory.stillValid(player);
    }

    @Override
    public void clearContent() {
        inventory.clearContent();
    }

    private void inventoryContentChanged()
    {
        receiveItemsFromBank();
    }

    public void dropContents() {
        Containers.dropContents(this.getLevel(), this.getBlockPos(), this.inventory);
    }
    public void dropItem(ItemStack stack) {
        Containers.dropItemStack(Objects.requireNonNull(this.getLevel()), this.getBlockPos().getX(), this.getBlockPos().getY(), this.getBlockPos().getZ(), stack);
    }

    public void redstoneSignalChanged(boolean isPowered) {
        this.receivingEnabled = isPowered;
        BlockState blockState = level.getBlockState(worldPosition);
        if (blockState.getBlock() instanceof BankDownloadBlock) {
            level.setBlock(worldPosition, blockState.setValue(BankDownloadBlock.RECEIVING_STATE, (this.receivingEnabled? BankDownloadBlock.ReceivingState.RECEIVING:BankDownloadBlock.ReceivingState.NOT_RECEIVING)), 3);
        }
        if(this.receivingEnabled) {
            receiveItemsFromBank();
        }
    }

    private void setPlayerOwner(UUID playerOwner) {
        this.playerOwner = playerOwner;
        if(this.playerOwner != null)
        {
            if(level != null)
            {
                BlockState blockState = level.getBlockState(worldPosition);
                if (blockState.getBlock() instanceof BankDownloadBlock) {
                    level.setBlock(worldPosition, blockState.setValue(BankDownloadBlock.CONNECTION_STATE, BankDownloadBlock.ConnectionState.CONNECTED), 3);
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
    }
    public UUID getPlayerOwner() {
        return playerOwner;
    }

    public boolean isReceivingEnabled() {
        return receivingEnabled;
    }

    public String getItemID() {
        return itemID;
    }

    public int getTargetAmount() {
        return targetAmount;
    }
    public int getMaxTargetAmount() {
        int stackSize = 64;
        if(itemID != null)
        {
            stackSize = ItemUtilities.createItemStackFromId(itemID).getMaxStackSize();
        }
        return inventory.getContainerSize() * stackSize;
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
                tickCounter++;
                if (tickCounter >= 20) {
                    tickCounter = 0;
                    blockEntity.update();
                }
            }
        }
    }


    private void receiveItemsFromBank()
    {
        if(!receivingEnabled || itemID == null || playerOwner == null || currentlyReceiving)
            return;



        BankUser bankUser = ServerBankManager.getUser(playerOwner);
        if(bankUser == null)
            return;
        Bank itemBank = bankUser.getBank(itemID);
        if(itemBank == null)
            return;
        ItemStack exampleStack = ItemUtilities.createItemStackFromId(itemID);
        if(exampleStack == null)
            return;
        int stackSize = exampleStack.getMaxStackSize();
        int currentItemCount = countItems();
        int targetItemCount = targetAmount;
        if(currentItemCount >= targetItemCount)
            return;
        currentlyReceiving = true;
        int amountToReceive = targetItemCount - currentItemCount;

        for(int i = 0; i < inventory.getContainerSize(); i++)
        {
            ItemStack stack = inventory.getItem(i);
            if(ItemUtilities.getItemID(stack.getItem()).equals(itemID))
            {
                int fillInStackAmount = stackSize - stack.getCount();
                int amountToReceiveFromStack = Math.min(fillInStackAmount, amountToReceive);
                if(amountToReceiveFromStack > 0)
                {
                    amountToReceiveFromStack = Math.min(amountToReceiveFromStack, (int)itemBank.getBalance());
                    if(itemBank.withdraw(amountToReceiveFromStack))
                    {
                        stack.grow(amountToReceiveFromStack);
                        amountToReceive -= amountToReceiveFromStack;
                    }
                }
            }
            else if(stack.isEmpty())
            {
                int amountToReceiveFromStack = Math.min(stackSize, amountToReceive);
                if(amountToReceiveFromStack > 0)
                {
                    amountToReceiveFromStack = Math.min(amountToReceiveFromStack, (int)itemBank.getBalance());
                    if(itemBank.withdraw(amountToReceiveFromStack)) {
                        inventory.setItem(i, new ItemStack(exampleStack.getItem(), amountToReceiveFromStack));
                        amountToReceive -= amountToReceiveFromStack;
                    }
                }
            }
        }
        setChanged();
        currentlyReceiving = false;
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
        UUID playerOwner = getPlayerOwner();
        boolean sendUpdate = false;
        itemID = packet.getItemID();

        ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
        if(itemStack == null)
        {
            itemID = null;
            targetAmount = 0;
            setChanged();
        }
        else {
            targetAmount = Math.max(0,Math.min(packet.getTargetAmount(), inventory.getContainerSize() * itemStack.getMaxStackSize()));
        }

        if(playerOwner == null || playerOwner.equals(sender.getUUID())) {
            setPlayerOwner(packet.isOwned() ? sender.getUUID() : null);
            sendUpdate = true;
        }
        if(receivingEnabled && getPlayerOwner() != null)
        {
            receiveItemsFromBank();
        }
        receiveItemsFromBank();

        if(sendUpdate)
        {
            SyncBankDownloadDataPacket.sendPacket(sender, this);
        }
    }

    private int countItems()
    {
        if(itemID == null)
            return 0;
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if(!stack.isEmpty() && itemID.equals(ItemUtilities.getItemID(stack.getItem())))
            {
                count += stack.getCount();
            }
        }
        return count;
    }
}
