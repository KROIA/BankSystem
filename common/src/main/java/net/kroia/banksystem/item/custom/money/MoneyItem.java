package net.kroia.banksystem.item.custom.money;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.entity.custom.MoneyStockpileBlockEntity;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;

public class MoneyItem extends Item{
    public static final String NAME = "money";
    //public static int ITEM_FRACTION_SCALE_FACTOR = 100; // Scale factor for item fractions, e.g., 100 means 1.00 currency units
    private static final Component ITEM_NAME = Component.translatable("item."+ BankSystemMod.MOD_ID+".money_name");
    private static final Component CURRENCY_NAME = Component.translatable("item."+ BankSystemMod.MOD_ID+".currency");
    private static ItemID itemID = null;

    public static ItemID getItemID() {
        if(itemID != null) {
            return itemID;
        }
        itemID = ItemID.of(BankSystemItems.MONEY.get().getDefaultInstance());
        return itemID;
    }
    public static String getCurrencyName() {
        return CURRENCY_NAME.getString();
    }
    public static String getName() {
        return ITEM_NAME.getString();
    }

    public MoneyItem() {
        //super(new Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)); // 1.19.2 and below
        super(new Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MoneyItem)) return false;
        MoneyItem other = (MoneyItem) obj;

        // Compare the class and worth of the items
        return this.getClass().equals(other.getClass()) &&
                this.worth() == other.worth();
    }

    @Override
    public int hashCode() {
        long worth = (int) worth();
        return (int) (worth ^ (worth >>> 32)) ^ getClass().hashCode(); // Use worth as hash code
    }

    public boolean isBankNote()
    {
        return false; // Override this method in subclasses if needed
    }


    public long worth() {
        return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR; // 100 represents 1.00 currency units
    }

    public static boolean isMoney(ItemID itemID)
    {
        return isMoney(itemID.getStack());
    }
    public static boolean isMoney(ItemStack itemStack)
    {
        ArrayList<ItemStack> moneyItems = BankSystemItems.getMoneyItems();
        for (ItemStack moneyStack : moneyItems) {
            if (    itemStack.getItem() instanceof MoneyItem &&
                    ItemStack.isSameItemSameComponents(moneyStack, itemStack))
            {
                return true;
            }
        }
        return false;
    }


    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        Player player = context.getPlayer();

        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof MoneyStockpileBlockEntity stockpile) {
                ItemStack itemStack = context.getItemInHand();
                if (MoneyItem.isMoney(itemStack)) {
                    int added = stockpile.addItems(itemStack);
                    itemStack.shrink(added);
                    if (itemStack.isEmpty()) {
                        player.setItemInHand(context.getHand(), ItemStack.EMPTY);
                    } else {
                        player.setItemInHand(context.getHand(), itemStack);
                    }
                    return InteractionResult.SUCCESS;
                }
            }

            BlockState blockState = BankSystemBlocks.MONEY_STOCKPILE_BLOCK.get().defaultBlockState();
            if (level.getBlockState(pos).canBeReplaced(new BlockPlaceContext(context))) {
                level.setBlock(pos, blockState, 3);

                // Play placement sound
                level.playSound(null, pos, blockState.getSoundType().getPlaceSound(),
                        SoundSource.BLOCKS, 1.0F, 1.0F);

                // Get the block entity and set the item
                blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof MoneyStockpileBlockEntity stockpile) {
                    ItemStack itemStack = context.getItemInHand();
                    if (MoneyItem.isMoney(itemStack)) {
                        int added = stockpile.addItems(itemStack);
                        itemStack.shrink(added);
                        if (itemStack.isEmpty()) {
                            player.setItemInHand(context.getHand(), ItemStack.EMPTY);
                        } else {
                            player.setItemInHand(context.getHand(), itemStack);
                        }
                    }
                }
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
