package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.block.custom.TerminalBlock;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.minecraft.world.item.Item;

public class Software extends Item {
    public static final String NAME = "software";
    public Software() {
        //super(new Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)); // 1.19.2 and below
        super(new Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }

    public TerminalBlock getProgrammedBlock()
    {
        return null;
    }
}
