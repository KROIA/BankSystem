package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MoneyDenominationOptimizer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Client-to-server packet for the ATM Money Converter tab (Task #39, v2.0.7):
 * drops the remaining converter cache at the player's feet as the item-minimum
 * banknote split (greedy largest-first via {@link MoneyDenominationOptimizer}),
 * then zeros the cache.
 *
 * <p>Also invoked internally by the auto-drop-on-disconnect hook in
 * {@code BankSystemModBackend.onPlayerLeave} via
 * {@link #dropAllForPlayer(ServerPlayer)}.
 *
 * <p><b>Trust gating.</b> Player-inventory-scoped write only. No untrusted-slave
 * gate required. Runs locally on whichever server the player is connected to.
 */
public class ConverterDropAllPacket extends BankSystemNetworkPacket {

    public static final Type<ConverterDropAllPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "converter_drop_all_packet"));

    // Empty payload — a dummy boolean satisfies the composite-codec requirement.
    public static final StreamCodec<RegistryFriendlyByteBuf, ConverterDropAllPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, p -> p.dummy,
            ConverterDropAllPacket::new
    );

    private final boolean dummy;

    public ConverterDropAllPacket(boolean dummy) {
        super();
        this.dummy = dummy;
    }

    public static void sendPacket() {
        new ConverterDropAllPacket(false).sendToServer();
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        if (sender == null) return;
        dropAllForPlayer(sender);
    }

    /**
     * Server-side helper used by both the packet and the disconnect hook.
     * Zeros the player's converter cache and dispenses the item-minimum split
     * to the player.
     *
     * <p><b>Uses the {@link ServerPlayer} reference directly</b> — does NOT
     * re-resolve via UUID against {@code PlayerList}. The disconnect hook fires
     * on some platforms after the player has already been removed from
     * {@code PlayerList}, and a UUID-based lookup would return {@code null}
     * from that point onward. Dropping the stacks directly via
     * {@link ServerPlayer#drop(ItemStack, boolean)} works even mid-disconnect
     * because the player entity is still valid for another server tick after
     * being kicked from the list.
     *
     * <p>Items land at the player's current position. The canonical
     * denomination set guarantees {@link MoneyDenominationOptimizer#split}
     * returns zero leftover for any cent-multiple amount; any leftover that
     * does occur is refunded to the cache rather than dropped.
     */
    public static void dropAllForPlayer(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        long amount = ConverterCacheManager.get().clear(uuid);
        if (amount <= 0) return;

        MoneyDenominationOptimizer.SplitResult split = MoneyDenominationOptimizer.split(amount);
        if (split.counts().isEmpty()) {
            // Canonical denomination set should never hit this; refund to be safe.
            ConverterCacheManager.get().deposit(uuid, amount);
            return;
        }

        for (Map.Entry<ItemID, Long> entry : split.counts().entrySet()) {
            ItemStack template = entry.getKey().getStack();
            if (template.isEmpty()) continue;
            long count = entry.getValue() == null ? 0L : entry.getValue();
            int maxStack = template.getMaxStackSize();
            if (maxStack <= 0) maxStack = 64;

            while (count > 0) {
                int drop = (int) Math.min(count, maxStack);
                ItemStack stack = template.copy();
                stack.setCount(drop);
                // drop(stack, false) spawns an item entity at the player's position.
                // Works whether or not the player is still in PlayerList (disconnect race).
                player.drop(stack, false);
                count -= drop;
            }
        }

        if (split.leftover() > 0) {
            ConverterCacheManager.get().deposit(uuid, split.leftover());
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
