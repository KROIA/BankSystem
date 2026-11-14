package net.kroia.banksystem.mixin;

import net.kroia.banksystem.data.CreativeNoteFlow;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BS-227 — feeds money notes created or deleted through the creative inventory into {@link CreativeNoteFlow}.
 * The old slot content is captured after {@code ensureRunningOnSameThread} (the handler first runs on the netty
 * thread and re-queues itself; only the server-thread pass gets past that call), the new content is read at
 * every RETURN (the handler has early returns). A creative drop (slot -1) spawns the stack: counted as created.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Unique
    private ItemStack banksystem$creativeOld;

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER))
    private void banksystem$captureCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        int slot = packet.slotNum();
        banksystem$creativeOld = player.gameMode.isCreative() && slot >= 1 && slot <= 45
                ? player.inventoryMenu.getSlot(slot).getItem().copy() : null;
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
    private void banksystem$logCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        ItemStack old = banksystem$creativeOld;
        if (old == null) return; // netty-thread pass, not creative, or not an inventory slot
        banksystem$creativeOld = null;
        ItemStack now = player.inventoryMenu.getSlot(packet.slotNum()).getItem();
        CreativeNoteFlow.add(player.getUUID(), old, -old.getCount());
        CreativeNoteFlow.add(player.getUUID(), now, now.getCount());
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private void banksystem$logCreativeDrop(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        ItemStack stack = packet.itemStack();
        CreativeNoteFlow.add(player.getUUID(), stack, stack.getCount());
    }
}
