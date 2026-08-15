package net.kroia.banksystem.minecraft.block.custom;

// TODO_ART Task #47 (v2.1.0) — placeholder model reuses iron_block texture; dedicated art pending.

import net.kroia.banksystem.minecraft.component.BankSystemDataComponents;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static dev.architectury.registry.menu.MenuRegistry.openExtendedMenu;

public class ShareStamperBlock extends Block implements EntityBlock {

    public static final String NAME = "share_stamper";
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public ShareStamperBlock() {
        super(Properties.ofFullCopy(Blocks.IRON_BLOCK)
                .requiresCorrectToolForDrops()
                .strength(3.5f, 6.0f)
                .isRedstoneConductor((s, l, p) -> false));
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShareStamperBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ShareStamperBlockEntity s) s.dropContents();
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    protected final @NotNull InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                              Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ShareStamperBlockEntity stamper)) return InteractionResult.SUCCESS;

        if (stamper.getBoundCompanyId() < 0) {
            if (player instanceof ServerPlayer sPlayer) {
                if (!stamper.tryAcquireViewer(sPlayer.getUUID())) {
                    sPlayer.sendSystemMessage(Component.translatable("gui.banksystem.share_stamper.in_use"));
                    return InteractionResult.SUCCESS;
                }
                openBindScreenFor(sPlayer, pos);
            }
            return InteractionResult.SUCCESS;
        }
        if (!stamper.hasManagePermission(player.getUUID())) {
            String companyName = stamper.getBoundCompanyName();
            player.sendSystemMessage(Component.translatable(
                    "gui.banksystem.share_stamper.no_permission", companyName));
            return InteractionResult.FAIL;
        }
        if (player instanceof ServerPlayer sPlayer) {
            if (!stamper.tryAcquireViewer(sPlayer.getUUID())) {
                sPlayer.sendSystemMessage(Component.translatable("gui.banksystem.share_stamper.in_use"));
                return InteractionResult.SUCCESS;
            }
            openExtendedMenu(sPlayer, stamper, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Task #47 (v2.1.0) — Compute the set of companies the caller may bind {@code pos}
     * to (founder OR MANAGE OR banksystem admin) and dispatch the bind-screen open S2C.
     * Master-only meaningful: on a slave the {@link net.kroia.banksystem.banking.company.CompanyManager}
     * is null and we fall back to a chat hint.
     */
    private static void openBindScreenFor(ServerPlayer player, BlockPos pos) {
        BlockEntity beRef = player.level().getBlockEntity(pos);
        ShareStamperBlockEntity stamperRef = beRef instanceof ShareStamperBlockEntity ssbe ? ssbe : null;
        net.kroia.banksystem.banking.company.CompanyManager cm =
                net.kroia.banksystem.banking.company.CompanyManager.get();
        if (cm == null) {
            player.sendSystemMessage(Component.translatable("gui.banksystem.stamper_bind.master_only"));
            if (stamperRef != null) stamperRef.releaseViewer(player.getUUID());
            return;
        }
        java.util.UUID uuid = player.getUUID();
        java.util.Set<net.kroia.banksystem.banking.company.Company> managed =
                cm.listCompaniesManagedBy(uuid);
        // Union with founder set (founder implies manage but be defensive).
        java.util.Set<net.kroia.banksystem.banking.company.Company> founder =
                cm.listCompaniesFounderedBy(uuid);
        java.util.LinkedHashSet<net.kroia.banksystem.banking.company.Company> all = new java.util.LinkedHashSet<>();
        all.addAll(managed);
        all.addAll(founder);

        List<net.kroia.banksystem.networking.entity.OpenStamperBindScreenPacket.Entry> entries =
                new ArrayList<>(all.size());
        for (net.kroia.banksystem.banking.company.Company c : all) {
            entries.add(new net.kroia.banksystem.networking.entity.OpenStamperBindScreenPacket.Entry(
                    c.getCompanyId(), c.getName()));
        }
        if (entries.isEmpty()) {
            player.sendSystemMessage(Component.translatable("gui.banksystem.stamper_bind.no_companies"));
            if (stamperRef != null) stamperRef.releaseViewer(player.getUUID());
            return;
        }
        net.kroia.banksystem.networking.entity.OpenStamperBindScreenPacket.send(player, pos, entries);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        Integer bound = stack.get(BankSystemDataComponents.STAMPER_BINDING.get());
        if (bound == null || bound <= 0) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ShareStamperBlockEntity s)) return;
        java.util.UUID placerUuid = placer instanceof Player p ? p.getUUID() : null;
        s.bind(bound, placerUuid);
    }

    @Override
    public @NotNull List<ItemStack> getDrops(BlockState state, LootParams.@NotNull Builder builder) {
        List<ItemStack> drops = super.getDrops(state, builder);
        BlockEntity be = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof ShareStamperBlockEntity s && s.getBoundCompanyId() > 0) {
            for (ItemStack drop : drops) {
                if (drop.is(this.asItem())) {
                    drop.set(BankSystemDataComponents.STAMPER_BINDING.get(), s.getBoundCompanyId());
                    break;
                }
            }
        }
        return drops;
    }

    /**
     * Task #47 (v2.1.0) — Bound stampers break {@code /20} for non-MANAGE callers.
     * Client-side path: {@link ShareStamperBlockEntity#hasManagePermission} returns
     * false because {@code CompanyManager.get()} is server-only, so all breakers see
     * the slowed rate on their client. Server-authoritative breaking is unchanged.
     */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter world, BlockPos pos) {
        float base = super.getDestroyProgress(state, player, world, pos);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ShareStamperBlockEntity s
                && s.getBoundCompanyId() >= 0
                && !s.hasManagePermission(player.getUUID())) {
            return base / 20f;
        }
        return base;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return type == BankSystemEntities.SHARE_STAMPER_BLOCK_ENTITY.get()
                ? ShareStamperBlockEntity::tick : null;
    }
}
