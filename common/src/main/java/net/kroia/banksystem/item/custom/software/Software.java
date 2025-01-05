package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.block.custom.TerminalBlock;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class Software extends Item {
    public static final String NAME = "software";
    public Software() {
        //super(new Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)); // 1.19.2 and below
        super(new Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }

    public TerminalBlock getProgrammedBlock()
    {
        return null;
    }

    protected void onRightClickedClientSide()
    {

    }
    protected void onRightClickedServerSide()
    {

    }



    @Override
    public InteractionResult useOn(UseOnContext context) {
        // Called when the item is used on a block
        Player player = context.getPlayer();
        Level level = context.getLevel();

        if (player != null && !level.isClientSide()) {
            onRightClickedServerSide();
        } else if (player != null && level.isClientSide()) {
            onRightClickedClientSide();
        }

        return InteractionResult.SUCCESS;
    }
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            onRightClickedServerSide();
        } else if (level.isClientSide()) {
            onRightClickedClientSide();
        }
        return InteractionResultHolder.success(itemStack);
    }
}
