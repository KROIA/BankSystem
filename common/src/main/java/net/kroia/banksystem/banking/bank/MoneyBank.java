package net.kroia.banksystem.banking.bank;

import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class MoneyBank extends Bank {

    public static final ItemID ITEM_ID = new ItemID(new ItemStack(BankSystemItems.MONEY.get()));

    public static ItemID compatibilityMoneyItemIDConvert(String itemID)
    {
        if(itemID.equals("$"))
            return ITEM_ID;
        if(itemID.equals("money"))
            return ITEM_ID;
        ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
        if(itemStack == ItemStack.EMPTY || itemStack == null || itemStack.is(Items.AIR))
            return null;
        return new ItemID(itemStack);
    }

    public static int getCentScaleFactorStatic()
    {
        return 100;
    }
    @Override
    public int getCentScaleFactor()
    {
        return getCentScaleFactorStatic();
    }

    public MoneyBank(BankUser owner, long balance) {
        super(owner, ITEM_ID, balance);
    }
    public MoneyBank(BankUser owner, CompoundTag tag) {
        super(owner, tag);
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putString("BankType", BankType.MONEY.name());
        tag.putBoolean("useCents", true);
        return super.save(tag);
    }

    @Override
    public boolean load(CompoundTag tag) {
        boolean result = super.load(tag);
        if(!tag.contains("useCents")) {
            balance *= getCentScaleFactor(); // Convert to cents if not already in cents
            lockedBalance *= getCentScaleFactor(); // Convert locked balance to cents as well
        }
        return result;
    }


}
