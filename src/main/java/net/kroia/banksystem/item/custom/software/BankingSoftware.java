package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.block.custom.TerminalBlock;

public class BankingSoftware extends Software {
    public static final String NAME = "banking_software";
    public BankingSoftware() {
        super();
    }

    @Override
    public TerminalBlock getProgrammedBlock()
    {
        return BankSystemBlocks.BANK_TERMINAL_BLOCK.get();
    }

}
