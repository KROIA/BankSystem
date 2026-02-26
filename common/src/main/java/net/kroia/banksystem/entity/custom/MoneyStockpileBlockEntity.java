package net.kroia.banksystem.entity.custom;

import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class MoneyStockpileBlockEntity extends BlockEntity {

    public static class ItemData implements ServerSaveable
    {
        private final ItemID itemID;
        private int amount;
        private int gridSpacesPerItemType = 1;
        private int usesGridSpaces = 1;

        public ItemData()
        {
            itemID = new ItemID(ItemStack.EMPTY);
        }
        public ItemData(ItemID itemID, int amount) {
            this.itemID = itemID;
            Item item = itemID.getStack().getItem();
            if(item instanceof MoneyItem moneyItem) {
                this.gridSpacesPerItemType = moneyItem.isBankNote() ? 2 : 1;
            }
            setAmount(amount);
        }
        public ItemID getItemID() {
            return itemID;
        }
        public int getAmount() {
            return amount;
        }
        public void setAmount(int amount) {
            this.amount = amount;
            this.usesGridSpaces = (int) Math.ceil((double) amount / 64) * gridSpacesPerItemType;
        }
        public void addAmount(int amount) {
            this.amount += amount;
            if(this.amount < 0) this.amount = 0; // Prevent negative amounts
            this.usesGridSpaces = (int) Math.ceil((double) this.amount / 64) * gridSpacesPerItemType;
        }
        public int getGridSpacesPerItemType() {
            return gridSpacesPerItemType;
        }
        public int getUsedGridSpaces() {
            return usesGridSpaces;
        }
        public int getCapacityUntilNextGridElement()
        {
            return 64 - amount % 64;
        }
        public int getWillUseGridSpaceIfAdded(int amount)
        {
            int newAmount = this.amount + amount;
            if(newAmount <= 0) return 0; // No space needed if the amount is zero or less
            int spacesNeeded = (int) Math.ceil((double) newAmount / 64);
            return spacesNeeded * gridSpacesPerItemType;
        }

        @Override
        public int hashCode() {
            return itemID.hashCode();
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ItemData other)) return false;
            return this.itemID.equals(other.itemID);
        }

        @Override
        public boolean save(CompoundTag tag) {
            if (tag == null) {
                return false; // Cannot save to a null tag
            }
            itemID.save(tag); // Assuming ItemID has a save method
            tag.putInt("amount", amount);
            tag.putInt("gridSpacesPerItemType", gridSpacesPerItemType);
            tag.putInt("usesGridSpaces", usesGridSpaces);
            return true;
        }

        @Override
        public boolean load(CompoundTag tag) {
            if (tag == null) {
                return false; // Cannot load from a null tag
            }
            itemID.load(tag); // Assuming ItemID has a load method
            amount = tag.getInt("amount");
            gridSpacesPerItemType = tag.getInt("gridSpacesPerItemType");
            usesGridSpaces = tag.getInt("usesGridSpaces");
            return true;
        }
    }
    private static final int maxGridSpaces = 36; // Maximum grid spaces for items in the stockpile
    private int sum = 0;
    private final Map<ItemID, ItemData> items = new HashMap<>();



    public MoneyStockpileBlockEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.MONEY_STOCKPILE_BLOCK_ENTITY.get(), pos, state);
    }

    public int addItems(ItemStack stack) {
        if(!MoneyItem.isMoney(stack))
            return 0;

        int amount = stack.getCount();

       /* int canAdd = Math.min(maxCapacity - sum, amount);

        if (canAdd <= 0) {
            return 0; // No space to add items
        }
*/
        ItemID id = ItemID.of(stack);
        ItemData data = items.get(id);
        int added = 0;

        int usedGridSpaces = getUsedGridSpaces();
        if(data == null)
        {

            data = new ItemData(id, amount);

            if(usedGridSpaces + data.getUsedGridSpaces() < maxGridSpaces)
            {
                added = amount;
                items.put(id, data);
            }
            else
            {
                data.setAmount(0);
                int willNeedGricCounts = (amount / 64 + (amount % 64 == 0 ? 0 : 1)) * data.getGridSpacesPerItemType();
                int freeSpaces = maxGridSpaces - usedGridSpaces;
                if(freeSpaces < willNeedGricCounts)
                    return 0; // Not enough space in the grid to add this item type
                int canAddStackCount = Math.min(freeSpaces, willNeedGricCounts / data.getGridSpacesPerItemType());
                added = Math.min(canAddStackCount * 64, amount);
                data.setAmount(added);
                items.put(id, data);
            }
        }
        else {
            int wouldUseGridSpaces = usedGridSpaces -data.getUsedGridSpaces() + data.getWillUseGridSpaceIfAdded(amount);
            if(usedGridSpaces <= maxGridSpaces)
            {
                int willNeedGricCounts = (amount / 64 + (amount % 64 == 0 ? 0 : 1)) * data.getGridSpacesPerItemType();
                int freeSpaces = maxGridSpaces - usedGridSpaces;
                if(freeSpaces < willNeedGricCounts)
                {
                    // Not enough space in the grid to add this item type
                    return 0;
                }
                int canAddStackCount = Math.min(freeSpaces, willNeedGricCounts / data.getGridSpacesPerItemType());
                added = Math.min(canAddStackCount * 64, amount);
                data.addAmount(added);
            }
            else if(wouldUseGridSpaces <= maxGridSpaces)
            {
                added = Math.min(data.getCapacityUntilNextGridElement(), amount);
                data.addAmount(added);
            }
            else
            {
                // Not enough space in the grid to add this item type
                return 0;
            }

        }

        sum += added;

        notifyChanges();
        return added;
    }

    public int getUsedGridSpaces() {
        int usedSpaces = 0;
        for (ItemData data : items.values()) {
            usedSpaces += data.getUsedGridSpaces();
        }
        return usedSpaces;
    }
    public float getHighestColumnHeight()
    {
        int maxCount = 0;
        for (ItemData data : items.values()) {
            if(data.getAmount() > maxCount) {
                maxCount = data.getAmount();
                if(maxCount >= 64)
                {
                    maxCount = 64;
                    break;
                }
            }
        }
        return maxCount*16 / 64f; // Return the height as a fraction of the maximum stack size
    }

    public void setCount(int count) {
        //this.count = count;
        notifyChanges();
    }

    public Map<ItemID, ItemData> getItems() {
        return items;
    }
    public int getCount() {
        return sum;
    }

    public net.minecraft.world.item.ItemStack removeItems() {
        // Gets a random stack
        if (items.isEmpty()) {
            return net.minecraft.world.item.ItemStack.EMPTY; // No items to remove
        }
        ItemID randomID = items.keySet().stream().findAny().orElse(null);
        if (randomID == null) {
            return net.minecraft.world.item.ItemStack.EMPTY; // No items to remove
        }
        ItemData data = items.get(randomID);
        if (data == null || data.getAmount() <= 0) {
            return net.minecraft.world.item.ItemStack.EMPTY; // No items to remove
        }
        int canRemove = Math.min(data.getAmount(), data.getItemID().getStack().getMaxStackSize());
        data.addAmount(-canRemove);
        if (data.getAmount() <= 0) {
            items.remove(randomID); // Remove the item data if no more items left
        }
        sum -= canRemove;
        net.minecraft.world.item.ItemStack resultStack = data.getItemID().getStack().copy();
        resultStack.setCount(canRemove);

        if(sum <= 0)
        {
            // Remove the block
            if (level != null) {
                level.removeBlock(worldPosition, false);
            }
        }

        notifyChanges();
        return resultStack;
    }
    public net.minecraft.world.item.ItemStack removeItems(ItemStack stack) {
        ItemID id = ItemID.of(stack);
        ItemData data = items.get(id);
        if(data == null || data.getAmount() <= 0) {
            return net.minecraft.world.item.ItemStack.EMPTY; // No items to remove
        }
        int amount = stack.getCount();
        int canRemove = Math.min(data.getAmount(), amount);
        data.addAmount(-canRemove);

        ItemStack resultStack = stack.copy();
        resultStack.setCount(canRemove);
        if (data.getAmount() <= 0) {
            items.remove(id); // Remove the item data if no more items left
        }
        sum -= canRemove;
        notifyChanges();
        return resultStack;
    }

    public void dropItems()
    {
        if (level == null || level.isClientSide()) {
            return; // No level or client-side, nothing to drop
        }

        for (Map.Entry<ItemID, ItemData> entry : items.entrySet()) {
            ItemData data = entry.getValue();
            if (data.getAmount() <= 0) continue;

            ItemStack stack = data.getItemID().getStack().copy();
            stack.setCount(data.getAmount());
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D, stack));
        }
        items.clear();
        sum = 0;
        notifyChanges();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("sum", sum);
        ListTag itemsTag = new ListTag();
        for (Map.Entry<ItemID, ItemData> entry : items.entrySet()) {
            CompoundTag itemTag = new CompoundTag();
            entry.getValue().save(itemTag); // Save ItemData to tag
            itemsTag.add(itemTag);
        }
        tag.put("items", itemsTag);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        sum = tag.getInt("sum");
        items.clear();
        ListTag itemsTag = tag.getList("items", 10); // 10 is the type ID for CompoundTag
        for (int i = 0; i < itemsTag.size(); i++) {
            CompoundTag itemTag = itemsTag.getCompound(i);
            ItemData itemData = new ItemData();
            if (itemData.load(itemTag)) {
                items.put(itemData.getItemID(), itemData); // Load ItemData from tag
            }
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public @NotNull CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    private void notifyChanges()
    {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}