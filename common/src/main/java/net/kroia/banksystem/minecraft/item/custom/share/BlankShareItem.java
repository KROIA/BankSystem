package net.kroia.banksystem.minecraft.item.custom.share;

import net.kroia.banksystem.minecraft.item.BankSystemCreativeModeTab;
import net.minecraft.world.item.Item;

/**
 * Task #46 (v2.1.0) — inert blank share. Crafted from paper + leather. Fed into the
 * (future, Task #47) share stamper to produce stamped shares bound to a company.
 * Right-click is deliberately inert — the item is a raw material with no in-hand action.
 */
public class BlankShareItem extends Item {

    public static final String NAME = "blank_share";

    public BlankShareItem() {
        super(new Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }
}
