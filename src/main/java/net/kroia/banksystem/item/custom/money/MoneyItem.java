package net.kroia.banksystem.item.custom.money;

import it.unimi.dsi.fastutil.Hash;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.world.item.Item;

import java.util.HashMap;

public class MoneyItem extends Item{
    public static final String NAME = "money";
    public static final String DISPLAY_NAME = "$";

    public MoneyItem() {
        super(new Properties());
    }

    public int worth() {
        return 1;
    }
}
