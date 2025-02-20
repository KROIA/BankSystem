package net.kroia.banksystem.banking.bank;

import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public class MoneyBank extends Bank {

    public static final ItemID ITEM_ID = new ItemID(new ItemStack(BankSystemItems.MONEY.get()));

    public static ItemID compatibilityMoneyItemIDConvert(String itemID)
    {
        if(itemID.equals("$"))
            return ITEM_ID;
        if(itemID.equals("money"))
            return ITEM_ID;
        ItemStack itemStack = ItemUtilities.createItemStackFromId(itemID);
        if(itemStack == ItemStack.EMPTY || itemStack == null)
            return null;
        return new ItemID(itemStack);
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
        return super.save(tag);
    }

    @Override
    public boolean load(CompoundTag tag) {
        return super.load(tag);
    }


}
