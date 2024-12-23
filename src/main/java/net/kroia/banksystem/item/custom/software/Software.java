package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.block.custom.TerminalBlock;
import net.minecraft.world.item.Item;

public class Software extends Item {
    public static final String NAME = "software";
    public Software() {
        super(new Properties());
    }

    public TerminalBlock getProgrammedBlock()
    {
        return null;
    }
}
