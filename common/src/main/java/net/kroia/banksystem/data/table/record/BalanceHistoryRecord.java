package net.kroia.banksystem.data.table.record;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record BalanceHistoryRecord(
    int accountNumber,
    short itemId,
    long balance,
    long lockedBalance,
    long time
) {
    public static final StreamCodec<RegistryFriendlyByteBuf, BalanceHistoryRecord> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, BalanceHistoryRecord::accountNumber,
                    ByteBufCodecs.SHORT, BalanceHistoryRecord::itemId,
                    ByteBufCodecs.VAR_LONG, BalanceHistoryRecord::balance,
                    ByteBufCodecs.VAR_LONG, BalanceHistoryRecord::lockedBalance,
                    ByteBufCodecs.VAR_LONG, BalanceHistoryRecord::time,
                    BalanceHistoryRecord::new
            );
}
