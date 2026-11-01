package net.kroia.banksystem.minecraft.block.custom;

import net.kroia.banksystem.BankSystemMod;
import net.minecraft.resources.ResourceLocation; // mc<=1.21.10
//import net.minecraft.resources.Identifier; // mc>=1.21.11
import net.kroia.modutilities.RegistryCompat;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.item.custom.software.Software;
import net.kroia.banksystem.util.MultiServerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//import net.minecraft.world.ItemInteractionResult; // mc<=1.21.1
import net.minecraft.world.InteractionResult; // mc>=1.21.2
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

public class TerminalBlock extends Block {
    public static final String NAME = "terminal_block";
    public TerminalBlock() {
        this(BankSystemMod.id(NAME));
    }
    /** Subclasses (also in other mods) pass the id they are registered under. */
    public TerminalBlock(ResourceLocation id) { // mc<=1.21.10
    //public TerminalBlock(Identifier id) { // mc>=1.21.11
        super(RegistryCompat.blockProperties(Properties.ofFullCopy(Blocks.IRON_BLOCK), id));
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH)); // Default facing
    }
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;


    @Override
    //public final @NotNull ItemInteractionResult useItemOn(ItemStack pStack, BlockState pState, Level pLevel, BlockPos pPos, Player pPlayer, InteractionHand pHand, BlockHitResult pHitResult) { // mc<=1.21.1
    public final @NotNull InteractionResult useItemOn(ItemStack pStack, BlockState pState, Level pLevel, BlockPos pPos, Player pPlayer, InteractionHand pHand, BlockHitResult pHitResult) { // mc>=1.21.2
        // Get the item in the player's hand
        Item item = pStack.getItem();

        BlockState state = pLevel.getBlockState(pPos);
        Software softwareItem = null;
        if(item instanceof Software) {
            softwareItem = (Software)item;
        }
        if (!pLevel.isClientSide() && softwareItem != null)
        {
            TerminalBlock programmedBlock = softwareItem.getProgrammedBlock();
            if(programmedBlock == null)
            {
                // Replace the block with the programmed block
                pLevel.setBlockAndUpdate(pPos, BankSystemBlocks.TERMINAL_BLOCK.get().defaultBlockState().setValue(FACING, state.getValue(FACING)));
            }
            else
            {
                // Replace the block with the programmed block
                pLevel.setBlockAndUpdate(pPos, programmedBlock.defaultBlockState().setValue(FACING, state.getValue(FACING)));
            }
            //return ItemInteractionResult.SUCCESS; // mc<=1.21.1
            return InteractionResult.SUCCESS; // mc>=1.21.2
        }
        if(softwareItem == null)
        {
            if(!pLevel.isClientSide())
            {
                if (!MultiServerUtils.canInteractWithBankSystem(pPlayer.getUUID()))
                    //return ItemInteractionResult.FAIL; // mc<=1.21.1
                    return InteractionResult.FAIL; // mc>=1.21.2
            }

            // Open the GUI
            openGui(pState, pLevel, pPos, pPlayer, pHand, pHitResult);
        }
        //return ItemInteractionResult.SUCCESS; // mc<=1.21.1
        return InteractionResult.SUCCESS; // mc>=1.21.2
    }
    @Override
    protected InteractionResult useWithoutItem(BlockState pState, Level pLevel, BlockPos pPos, Player pPlayer, BlockHitResult pHitResult) {
        if(!pLevel.isClientSide())
        {
            if (!MultiServerUtils.canInteractWithBankSystem(pPlayer.getUUID()))
                return InteractionResult.FAIL;
        }
        openGui(pState, pLevel, pPos, pPlayer, pPlayer.getUsedItemHand(), pHitResult);
        return InteractionResult.PASS;
    }
    protected void openGui(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // Open the GUI
    }


    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
}
