package net.kroia.banksystem.item.custom.money;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;

public class MoneyItem extends Item{
    public static final String NAME = "money";
    private static final Component ITEM_NAME = Component.translatable("item."+ BankSystemMod.MOD_ID+".money_name");
    private static final Component CURRENCY_NAME = Component.translatable("item."+ BankSystemMod.MOD_ID+".currency");

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


    public long worth() {
        return 100; // 100 represents 1.00 currency units
    }

    public static boolean isMoney(ItemID itemID)
    {
        ArrayList<ItemStack> moneyItems = BankSystemItems.getMoneyItems();
        for (ItemStack itemStack : moneyItems) {
            if (    itemStack.getItem() instanceof MoneyItem &&
                    ItemStack.isSameItemSameTags(itemStack, itemID.getStack()))
            {
                return true;
            }
        }
        return false;
    }


}
