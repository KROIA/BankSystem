package net.kroia.banksystem.banking.bank;

/*
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

    public static int getItemFractionScaleFactorStatic()
    {
        return 100;
    }

    public static long convertToRawAmountStatic(float realAmount)
    {
        return (long)(realAmount * getItemFractionScaleFactorStatic());
    }


    public static float convertToRealAmountStatic(long rawAmount)
    {
        return (float)rawAmount / getItemFractionScaleFactorStatic();
    }

    public MoneyBank(BankUserOld owner, long balance) {
        super(owner, ITEM_ID, balance);
    }
    public MoneyBank(BankUserOld owner, CompoundTag tag) {
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
            int scaleFactor = getItemFractionScaleFactor();
            balance *= scaleFactor; // Convert to cents if not already in cents
            lockedBalance *= scaleFactor; // Convert locked balance to cents as well
        }
        return result;
    }


}
*/