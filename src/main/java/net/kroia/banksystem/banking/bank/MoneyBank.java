package net.kroia.banksystem.banking.bank;

import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.nbt.CompoundTag;

public class MoneyBank extends Bank {

    public static final String ITEM_ID = ItemUtilities.getItemID(BankSystemItems.MONEY.get());

    static String compatibilityMoneyItemIDConvert(String itemID)
    {
        if(itemID.equals("$"))
            return ITEM_ID;
        return itemID;
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
