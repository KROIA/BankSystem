package net.kroia.banksystem.entity.custom;

import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.block.custom.BankUploadBlock;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

public class BankUploadEntity extends BaseContainerBlockEntity {

    private final SimpleContainer inventory = new SimpleContainer(27); // 27 slots like a chest
    private boolean sendingEnabled = false;

    private UUID playerOwner = null;

    public BankUploadEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.BANK_UPLOAD_BLOCK_ENTITY.get(), pos, state);
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
        sendingEnabled = tag.getBoolean("SendingEnabled");
        if(tag.contains("PlayerOwner"))
        {
            playerOwner = tag.getUUID("PlayerOwner");
        }
        // Print inventory to console
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            System.out.println("Slot " + i + ": " + inventory.getItem(i));
        }
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Items", inventory.createTag());
        tag.putBoolean("SendingEnabled", sendingEnabled);
        if(playerOwner != null)
        {
            tag.putUUID("PlayerOwner", playerOwner);
        }
        // Print inventory to console
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            System.out.println("Slot " + i + ": " + inventory.getItem(i));
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
        if(this.sendingEnabled)
        {
            sendInventoryToBank();
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

    public void dropContents() {
        Containers.dropContents(this.getLevel(), this.getBlockPos(), this.inventory);
    }
    public void dropItem(ItemStack stack) {
        Containers.dropItemStack(Objects.requireNonNull(this.getLevel()), this.getBlockPos().getX(), this.getBlockPos().getY(), this.getBlockPos().getZ(), stack);
    }

    public void setSendingEnabled(boolean sendingEnabled) {
        this.sendingEnabled = sendingEnabled;
        if(this.sendingEnabled)
        {
            sendInventoryToBank();
        }
    }

    public void setPlayerOwner(UUID playerOwner) {
        this.playerOwner = playerOwner;
        if(this.playerOwner != null)
        {
            if(level != null)
            {
                BlockState blockState = level.getBlockState(worldPosition);
                if (blockState.getBlock() instanceof BankUploadBlock) {
                    level.setBlock(worldPosition, blockState.setValue(BankUploadBlock.CONNECTION_STATE, BankUploadBlock.ConnectionState.CONNECTED), 3);
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
        if(this.sendingEnabled)
        {
            sendInventoryToBank();
        }
    }
    public UUID getPlayerOwner() {
        return playerOwner;
    }

    public boolean isSendingEnabled() {
        return sendingEnabled;
    }

    private void sendInventoryToBank()
    {
        if(playerOwner == null)
            return;
        BankUser bankUser = ServerBankManager.getUser(playerOwner);
        if(bankUser == null)
            return;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if(!stack.isEmpty())
            {
                String itemID = ItemUtilities.getItemID(stack.getItem());
                Bank itemBank = bankUser.getBank(itemID);
                if(itemBank == null)
                {
                    itemBank = bankUser.createItemBank(itemID, 0);
                }
                if(itemBank == null)
                    dropItem(stack);

                itemBank.deposit(stack.getCount());
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }
}
