package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.block.custom.TerminalBlock;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class Software extends Item {
    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public static final String NAME = "software";
    public Software() {
        //super(new Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)); // 1.19.2 and below
        super(new Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }

    public TerminalBlock getProgrammedBlock()
    {
        return null;
    }

    protected void onRightClickedClientSide()
    {

    }
    protected void onRightClickedServerSide(ServerPlayer player)
    {

    }



    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        // Called when the item is used on a block
        Player player = context.getPlayer();
        Level level = context.getLevel();

        if (player != null && !level.isClientSide()) {
            onRightClickedServerSide((ServerPlayer) player);
        } else if (player != null && level.isClientSide()) {
            onRightClickedClientSide();
        }

        return InteractionResult.SUCCESS;
    }
    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            onRightClickedServerSide((ServerPlayer) player);
        } else if (level.isClientSide()) {
            onRightClickedClientSide();
        }
        return InteractionResultHolder.success(itemStack);
    }
}
