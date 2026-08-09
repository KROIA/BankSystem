package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.payout.IPayoutManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;
import net.kroia.banksystem.util.async_function_forwarding.AsyncForwardingRequest;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionDataCodecs;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionInputData;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionOutputData;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Task #43g (v2.0.8) — slave-side ARRS dispatcher for the {@code /company} subcommands.
 * <p>
 * Mirrors {@link net.kroia.banksystem.banking.bankmanager.AsyncBankManager} in shape.
 * Slave initiates via the static {@code *Async} helpers below; master receives on
 * {@link Request#handleOnMasterServer}. All company mutations run on master only —
 * the slave never touches Company NBT on disk.
 */
public final class AsyncCompanyManager {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private AsyncCompanyManager() {}

    // ------------------------------------------------------------------
    // Standard result codes shared across all functions
    // ------------------------------------------------------------------
    public static final int CODE_OK                    = 0;
    public static final int CODE_NOT_FOUND             = 1;
    public static final int CODE_NAME_TAKEN            = 2;
    public static final int CODE_INVALID_INPUT         = 3;
    public static final int CODE_NOT_FOUNDER           = 4;
    public static final int CODE_ALREADY_FOUNDER       = 5;
    public static final int CODE_MISSING_TARGET        = 6;
    public static final int CODE_NO_PERMISSION         = 7;
    public static final int CODE_BANK_ACCOUNT_ERROR    = 8;
    public static final int CODE_INTERNAL              = 9;
    public static final int CODE_SCHEDULE_MISSING      = 10;
    /** Task #49 (v2.0.8) — company money bank cannot cover the dividend outflow. */
    public static final int CODE_INSUFFICIENT_FUNDS    = 11;
    /** Task #49 (v2.0.8) — no account holds this company's shares. */
    public static final int CODE_NO_SHARES             = 12;
    /** Spec B.3 (v2.0.8) — company account lacks enough of the chosen currency item. */
    public static final int CODE_CURRENCY_ITEM_MISSING = 13;
    /** Spec B.1 (v2.0.8) — target lacks DEPOSIT right on the chosen account. */
    public static final int CODE_TARGET_NO_DEPOSIT     = 14;

    // ------------------------------------------------------------------
    // Function enum
    // ------------------------------------------------------------------
    public enum FunctionType {
        CREATE_COMPANY,
        TRANSFER_FOUNDER,
        DISSOLVE_COMPANY,
        UPDATE_DESCRIPTION,
        GET_COMPANY_INFO,
        IS_NAME_TAKEN,
        LIST_COMPANIES_FOR_CALLER,
        // Task #45a (v2.0.8) — payout scheduling.
        CREATE_PAYOUT,
        UPDATE_PAYOUT,
        PAUSE_PAYOUT,
        DELETE_PAYOUT,
        LIST_SCHEDULES,
        GET_HISTORY,
        GET_COMPANY_INFO_BY_ACCOUNT,
        GET_FAILURE_COUNT_24H,
        // Task #46 (v2.0.8) — share visuals editor writeback (MANAGE-gated on master).
        UPDATE_SHARE_VISUALS,
        // Task #46 (v2.0.8) — by-id share visuals lookup for tooltip self-heal.
        GET_SHARE_VISUALS,
        // Task #49 (v2.0.8) — one-shot dividend distribution.
        PAY_DIVIDEND,
        // Task #51 (v2.0.8) — list Share Stamper block-entity positions bound to a company.
        LIST_STAMPER_BINDINGS,
        // Task #51 fix (v2.0.8) — by-id company info lookup (CompanyManagementScreen needs
        // the full CompanyInfoOutput given only the companyId from a stamped-share stack).
        GET_COMPANY_INFO_BY_ID,
        // Task #52 (v2.0.8) — read-only holder count for a company's stamped shares.
        COUNT_HOLDERS_FOR_COMPANY,
        // Task #54 (v2.0.8) — slave→master bulk request for all companies' visuals+info.
        // Used at slave-master handshake to populate SlaveCompanyMirror.
        LIST_ALL_COMPANY_VISUALS,
        // Task #1 (v2.0.8) — StockMarket integration: create/close/query share market.
        OPEN_SHARE_MARKET,
        CLOSE_SHARE_MARKET,
        MARKET_EXISTS_FOR_COMPANY,
        // Task #1 (v2.0.8) — StockMarket pause/resume trading (distinct from delete).
        SET_MARKET_OPEN,
        IS_MARKET_OPEN,
        // Spec §4.3 (v2.0.8) — MANAGE-gated unbind of a Share Stamper from a company.
        UNBIND_STAMPER,
        // Spec B.1 (v2.0.8) — list a player's bank accounts filtered by permission mask.
        // MANAGE-gated when caller != subject.
        LIST_PLAYER_ACCOUNTS_WITH_FILTER,
        // Spec B.3 (v2.0.8) — list all non-zero item balances on the company account
        // for the payout currency picker. MANAGE-gated.
        LIST_ACCOUNT_ITEM_BALANCES,
        // Spec B.4 (v2.0.8) — manual missed-payout catch-up. MANAGE-gated.
        PAY_MISSED,
        // Task #52 (v2.0.8) — read-only dividend history for a company.
        LIST_DIVIDEND_HISTORY,
        // Statistics tab (v2.0.9) — company cashflow, shareholder, solvency data.
        GET_COMPANY_STATS,
        // v2.0.9 — MANAGE-gated mutation: set the company's default payout currency.
        SET_COMPANY_CURRENCY,
        // Task #54 (v2.0.9) — slave→master: fetch current share symbol manifest.
        GET_SYMBOL_MANIFEST,
        // Task #54 (v2.0.9) — slave→master: request byte push for specific symbol hashes.
        PULL_SYMBOL_BYTES
    }

    /** Task #1 (v2.0.8) — SM bridge result codes for OPEN_SHARE_MARKET output. */
    public static final int SM_STATUS_SUCCESS          = 0;
    public static final int SM_STATUS_ALREADY_EXISTS   = 1;
    public static final int SM_STATUS_ITEM_BLACKLISTED = 2;
    public static final int SM_STATUS_FAILED           = 3;
    public static final int SM_STATUS_UNAVAILABLE      = 4;
    public static final int SM_STATUS_NO_PERMISSION    = 5;
    public static final int SM_STATUS_NOT_FOUND        = 6;
    /** MARKET_EXISTS_FOR_COMPANY result codes. */
    public static final int MARKET_EXISTS_NO   = 0;
    public static final int MARKET_EXISTS_YES  = 1;
    public static final int MARKET_EXISTS_UNAV = 2;
    /** IS_MARKET_OPEN result codes (mirrors {@link StockMarketBridge.MarketOpen}). */
    public static final int MARKET_OPEN_NO   = 0;
    public static final int MARKET_OPEN_YES  = 1;
    public static final int MARKET_OPEN_UNAV = 2;

    /** Task #43h — rights filter kinds for {@link #LIST_COMPANIES_FOR_CALLER}. */
    public static final byte FILTER_ALL      = 0;
    public static final byte FILTER_FOUNDER  = 1;
    public static final byte FILTER_MANAGE   = 2;

    // ------------------------------------------------------------------
    // Param records + codecs
    // ------------------------------------------------------------------
    public record CreateInput(String name, long maxSupply, UUID callerUUID, String callerName) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CreateInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p -> p.name,
                ByteBufCodecs.VAR_LONG, p -> p.maxSupply,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                ByteBufCodecs.STRING_UTF8, p -> p.callerName,
                CreateInput::new);
    }
    public record CreateOutput(int resultCode, int companyId, int bankAccountNr, String name, long maxSupply) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CreateOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.VAR_INT, p -> p.bankAccountNr,
                ByteBufCodecs.STRING_UTF8, p -> p.name,
                ByteBufCodecs.VAR_LONG, p -> p.maxSupply,
                CreateOutput::new);
    }

    public record TransferInput(String companyName, UUID callerUUID, String targetName) {
        public static final StreamCodec<RegistryFriendlyByteBuf, TransferInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p -> p.companyName,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                ByteBufCodecs.STRING_UTF8, p -> p.targetName,
                TransferInput::new);
    }
    public record TransferOutput(int resultCode, int companyId, String fromName, String toName) {
        public static final StreamCodec<RegistryFriendlyByteBuf, TransferOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.STRING_UTF8, p -> p.fromName,
                ByteBufCodecs.STRING_UTF8, p -> p.toName,
                TransferOutput::new);
    }

    public record DissolveInput(String companyName, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, DissolveInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p -> p.companyName,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                DissolveInput::new);
    }
    public record DissolveOutput(int resultCode, int companyId, String companyName, int bankAccountNr) {
        public static final StreamCodec<RegistryFriendlyByteBuf, DissolveOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.STRING_UTF8, p -> p.companyName,
                ByteBufCodecs.VAR_INT, p -> p.bankAccountNr,
                DissolveOutput::new);
    }

    public record DescriptionInput(String companyName, UUID callerUUID, String text) {
        public static final StreamCodec<RegistryFriendlyByteBuf, DescriptionInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p -> p.companyName,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                ByteBufCodecs.STRING_UTF8, p -> p.text,
                DescriptionInput::new);
    }
    public record DescriptionOutput(int resultCode, int companyId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, DescriptionOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                DescriptionOutput::new);
    }

    public record CompanyInfoOutput(boolean present, int companyId, String name, int bankAccountNr,
                                    long maxSupply, long totalSharesIssued, String description,
                                    List<String> founderNames, short companyCurrency) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CompanyInfoOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBoolean(v.present);
                    buf.writeVarInt(v.companyId);
                    buf.writeUtf(v.name);
                    buf.writeVarInt(v.bankAccountNr);
                    buf.writeVarLong(v.maxSupply);
                    buf.writeVarLong(v.totalSharesIssued);
                    buf.writeUtf(v.description);
                    buf.writeVarInt(v.founderNames.size());
                    for (String s : v.founderNames) buf.writeUtf(s);
                    buf.writeShort(v.companyCurrency);
                },
                buf -> {
                    boolean present = buf.readBoolean();
                    int id = buf.readVarInt();
                    String name = buf.readUtf();
                    int accNr = buf.readVarInt();
                    long ms = buf.readVarLong();
                    long tsi = buf.readVarLong();
                    String desc = buf.readUtf();
                    int n = buf.readVarInt();
                    List<String> founders = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) founders.add(buf.readUtf());
                    short currency = buf.readShort();
                    return new CompanyInfoOutput(present, id, name, accNr, ms, tsi, desc, founders, currency);
                }
        );
        public static final CompanyInfoOutput ABSENT =
                new CompanyInfoOutput(false, 0, "", 0, 0L, 0L, "", List.of(), (short) 0);
    }

    /** Task #43h — list company names visible to the caller under a rights filter. */
    public record ListInput(UUID callerUUID, byte filterKind) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListInput> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                ByteBufCodecs.BYTE, p -> p.filterKind,
                ListInput::new);
    }
    public record ListOutput(List<String> companyNames) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.companyNames.size());
                    for (String s : v.companyNames) buf.writeUtf(s);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<String> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(buf.readUtf());
                    return new ListOutput(out);
                });
        public static final ListOutput EMPTY = new ListOutput(List.of());
    }

    // ------------------------------------------------------------------
    // Task #45a (v2.0.8) — payout ARRS records
    // ------------------------------------------------------------------
    /**
     * Spec B.1–B.3 (v2.0.8) — extended payout create payload. {@code target} is nullable
     * for DIVIDEND-mode schedules. Name snapshots are resolved on the master (spec A.9),
     * never trusted from the client.
     */
    public record CreatePayoutInput(int companyId, @Nullable UUID target, long amount, long intervalTicks,
                                    long nowTick, UUID callerUUID, int targetAccountNr,
                                    byte mode, short currencyItem) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CreatePayoutInput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.companyId);
                    buf.writeBoolean(v.target != null);
                    if (v.target != null) buf.writeUUID(v.target);
                    buf.writeVarLong(v.amount);
                    buf.writeVarLong(v.intervalTicks);
                    buf.writeVarLong(v.nowTick);
                    buf.writeUUID(v.callerUUID);
                    buf.writeVarInt(v.targetAccountNr);
                    buf.writeByte(v.mode);
                    buf.writeShort(v.currencyItem);
                },
                buf -> {
                    int cid = buf.readVarInt();
                    UUID target = buf.readBoolean() ? buf.readUUID() : null;
                    long amount = buf.readVarLong();
                    long interval = buf.readVarLong();
                    long nowTick = buf.readVarLong();
                    UUID caller = buf.readUUID();
                    int accNr = buf.readVarInt();
                    byte mode = buf.readByte();
                    short currency = buf.readShort();
                    return new CreatePayoutInput(cid, target, amount, interval, nowTick, caller,
                            accNr, mode, currency);
                });
    }
    public record CreatePayoutOutput(int resultCode, long scheduleId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CreatePayoutOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                CreatePayoutOutput::new);
    }

    /** Spec B.1–B.3 (v2.0.8) — extended payout update payload (target/mode/currency editable). */
    public record UpdatePayoutInput(int companyId, long scheduleId, long newAmount,
                                    long newIntervalTicks, UUID callerUUID, @Nullable UUID newTarget,
                                    int newTargetAccountNr, byte newMode, short newCurrencyItem) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePayoutInput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.companyId);
                    buf.writeVarLong(v.scheduleId);
                    buf.writeVarLong(v.newAmount);
                    buf.writeVarLong(v.newIntervalTicks);
                    buf.writeUUID(v.callerUUID);
                    buf.writeBoolean(v.newTarget != null);
                    if (v.newTarget != null) buf.writeUUID(v.newTarget);
                    buf.writeVarInt(v.newTargetAccountNr);
                    buf.writeByte(v.newMode);
                    buf.writeShort(v.newCurrencyItem);
                },
                buf -> {
                    int cid = buf.readVarInt();
                    long sid = buf.readVarLong();
                    long amount = buf.readVarLong();
                    long interval = buf.readVarLong();
                    UUID caller = buf.readUUID();
                    UUID target = buf.readBoolean() ? buf.readUUID() : null;
                    int accNr = buf.readVarInt();
                    byte mode = buf.readByte();
                    short currency = buf.readShort();
                    return new UpdatePayoutInput(cid, sid, amount, interval, caller, target,
                            accNr, mode, currency);
                });
    }
    public record UpdatePayoutOutput(int resultCode) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePayoutOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                UpdatePayoutOutput::new);
    }

    public record PausePayoutInput(int companyId, long scheduleId, boolean paused, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PausePayoutInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                ByteBufCodecs.BOOL, p -> p.paused,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                PausePayoutInput::new);
    }
    public record PausePayoutOutput(int resultCode) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PausePayoutOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                PausePayoutOutput::new);
    }

    public record DeletePayoutInput(int companyId, long scheduleId, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, DeletePayoutInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                DeletePayoutInput::new);
    }
    public record DeletePayoutOutput(int resultCode) {
        public static final StreamCodec<RegistryFriendlyByteBuf, DeletePayoutOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                DeletePayoutOutput::new);
    }

    public record ListSchedulesInput(int companyId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListSchedulesInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ListSchedulesInput::new);
    }
    /** Wire form of a {@link PayoutSchedule} (spec B fields included). */
    public record ScheduleWire(long scheduleId, @Nullable UUID targetUUID, long amount, long intervalTicks,
                               long nextRunTick, boolean paused, long createdAt, @Nullable UUID createdBy,
                               int targetAccountNr, String targetPlayerName, String targetAccountName,
                               byte mode, short currencyItem, long missedAmount, int missedCount) {
        public static void write(RegistryFriendlyByteBuf buf, ScheduleWire v) {
            buf.writeVarLong(v.scheduleId);
            buf.writeBoolean(v.targetUUID != null);
            if (v.targetUUID != null) buf.writeUUID(v.targetUUID);
            buf.writeVarLong(v.amount);
            buf.writeVarLong(v.intervalTicks);
            buf.writeVarLong(v.nextRunTick);
            buf.writeBoolean(v.paused);
            buf.writeVarLong(v.createdAt);
            buf.writeBoolean(v.createdBy != null);
            if (v.createdBy != null) buf.writeUUID(v.createdBy);
            buf.writeVarInt(v.targetAccountNr);
            buf.writeUtf(v.targetPlayerName == null ? "" : v.targetPlayerName);
            buf.writeUtf(v.targetAccountName == null ? "" : v.targetAccountName);
            buf.writeByte(v.mode);
            buf.writeShort(v.currencyItem);
            buf.writeVarLong(v.missedAmount);
            buf.writeVarInt(v.missedCount);
        }
        public static ScheduleWire read(RegistryFriendlyByteBuf buf) {
            long id = buf.readVarLong();
            UUID target = buf.readBoolean() ? buf.readUUID() : null;
            long amount = buf.readVarLong();
            long interval = buf.readVarLong();
            long next = buf.readVarLong();
            boolean paused = buf.readBoolean();
            long createdAt = buf.readVarLong();
            UUID createdBy = buf.readBoolean() ? buf.readUUID() : null;
            int accNr = buf.readVarInt();
            String playerName = buf.readUtf();
            String accountName = buf.readUtf();
            byte mode = buf.readByte();
            short currency = buf.readShort();
            long missedAmount = buf.readVarLong();
            int missedCount = buf.readVarInt();
            return new ScheduleWire(id, target, amount, interval, next, paused, createdAt, createdBy,
                    accNr, playerName, accountName, mode, currency, missedAmount, missedCount);
        }
        public static ScheduleWire of(PayoutSchedule s) {
            return new ScheduleWire(s.getScheduleId(), s.getTargetUUID(), s.getAmount(),
                    s.getIntervalTicks(), s.getNextRunTick(), s.isPaused(), s.getCreatedAt(), s.getCreatedBy(),
                    s.getTargetAccountNr(), s.getTargetPlayerName(), s.getTargetAccountName(),
                    (byte) s.getMode().ordinal(), s.getCurrencyItem(), s.getMissedAmount(), s.getMissedCount());
        }
        public PayoutSchedule toSchedule() {
            PayoutSchedule.Mode m = mode >= 0 && mode < PayoutSchedule.Mode.values().length
                    ? PayoutSchedule.Mode.values()[mode] : PayoutSchedule.Mode.FIXED_PAYOUT;
            return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks, nextRunTick,
                    paused, createdAt, createdBy, targetAccountNr, targetPlayerName, targetAccountName,
                    m, currencyItem, missedAmount, missedCount);
        }
    }
    /** {@code nowTick} — master's current tick, for client-side countdown rendering (spec A.4). */
    public record ListSchedulesOutput(List<ScheduleWire> schedules, long nowTick) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListSchedulesOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.schedules.size());
                    for (ScheduleWire s : v.schedules) ScheduleWire.write(buf, s);
                    buf.writeVarLong(v.nowTick);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<ScheduleWire> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(ScheduleWire.read(buf));
                    long nowTick = buf.readVarLong();
                    return new ListSchedulesOutput(out, nowTick);
                });
        public static final ListSchedulesOutput EMPTY = new ListSchedulesOutput(List.of(), 0L);
    }

    public record GetHistoryInput(long scheduleId, int limit) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetHistoryInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                ByteBufCodecs.VAR_INT, p -> p.limit,
                GetHistoryInput::new);
    }
    /** Wire form of a {@link PayoutHistoryRecord} (spec A.8/A.9/B.3/B.4 fields included). */
    public record HistoryRowWire(long id, int companyId, long scheduleId, int sourceAccount,
                                 @Nullable UUID targetUuid, long amount, long time, int statusOrdinal,
                                 String targetPlayerName, String targetAccountName,
                                 short currencyItem, int typeOrdinal) {
        public static void write(RegistryFriendlyByteBuf buf, HistoryRowWire v) {
            buf.writeVarLong(v.id);
            buf.writeVarInt(v.companyId);
            buf.writeVarLong(v.scheduleId);
            buf.writeVarInt(v.sourceAccount);
            buf.writeBoolean(v.targetUuid != null);
            if (v.targetUuid != null) buf.writeUUID(v.targetUuid);
            buf.writeVarLong(v.amount);
            buf.writeVarLong(v.time);
            buf.writeVarInt(v.statusOrdinal);
            buf.writeUtf(v.targetPlayerName == null ? "" : v.targetPlayerName);
            buf.writeUtf(v.targetAccountName == null ? "" : v.targetAccountName);
            buf.writeShort(v.currencyItem);
            buf.writeVarInt(v.typeOrdinal);
        }
        public static HistoryRowWire read(RegistryFriendlyByteBuf buf) {
            long id = buf.readVarLong();
            int cid = buf.readVarInt();
            long sid = buf.readVarLong();
            int src = buf.readVarInt();
            UUID target = buf.readBoolean() ? buf.readUUID() : null;
            long amount = buf.readVarLong();
            long time = buf.readVarLong();
            int status = buf.readVarInt();
            String playerName = buf.readUtf();
            String accountName = buf.readUtf();
            short currency = buf.readShort();
            int type = buf.readVarInt();
            return new HistoryRowWire(id, cid, sid, src, target, amount, time, status,
                    playerName, accountName, currency, type);
        }
        public static HistoryRowWire of(PayoutHistoryRecord r) {
            return new HistoryRowWire(r.id(), r.companyId(), r.scheduleId(), r.sourceAccount(),
                    r.targetUuid(), r.amount(), r.time(), r.status().ordinal(),
                    r.targetPlayerName(), r.targetAccountName(), r.currencyItem(),
                    r.type().ordinal());
        }
    }
    public record GetHistoryOutput(List<HistoryRowWire> rows, long totalPaid) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetHistoryOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.rows.size());
                    for (HistoryRowWire r : v.rows) HistoryRowWire.write(buf, r);
                    buf.writeVarLong(v.totalPaid);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<HistoryRowWire> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(HistoryRowWire.read(buf));
                    long total = buf.readVarLong();
                    return new GetHistoryOutput(out, total);
                });
        public static final GetHistoryOutput EMPTY = new GetHistoryOutput(List.of(), 0L);
    }

    public record GetFailureCount24hInput(int companyId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetFailureCount24hInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                GetFailureCount24hInput::new);
    }
    public record GetFailureCount24hOutput(long failedCount) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetFailureCount24hOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, p -> p.failedCount,
                GetFailureCount24hOutput::new);
    }

    // Task #46 (v2.0.8) / v2.0.9 two-layer — share visuals editor.
    public record UpdateShareVisualsInput(int companyId,
                                          String bgSymbolId, int bgTint,
                                          String fgSymbolId, int fgTint,
                                          int baseTint,
                                          String displayName, String description, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateShareVisualsInput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.companyId);
                    buf.writeUtf(v.bgSymbolId == null ? "" : v.bgSymbolId);
                    buf.writeInt(v.bgTint);
                    buf.writeUtf(v.fgSymbolId == null ? "" : v.fgSymbolId);
                    buf.writeInt(v.fgTint);
                    buf.writeInt(v.baseTint);
                    buf.writeUtf(v.displayName == null ? "" : v.displayName);
                    buf.writeUtf(v.description == null ? "" : v.description);
                    buf.writeUUID(v.callerUUID);
                },
                buf -> new UpdateShareVisualsInput(
                        buf.readVarInt(),
                        buf.readUtf(), buf.readInt(),
                        buf.readUtf(), buf.readInt(),
                        buf.readInt(),
                        buf.readUtf(), buf.readUtf(), buf.readUUID()));
    }
    public record UpdateShareVisualsOutput(int resultCode) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateShareVisualsOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                UpdateShareVisualsOutput::new);
    }

    // Task #46 (v2.0.8) — by-id share visuals lookup for tooltip self-heal.
    public record GetShareVisualsInput(int companyId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetShareVisualsInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                GetShareVisualsInput::new);
    }
    // v2.0.9 two-layer: bgSymbolId/bgTint/fgSymbolId/fgTint replace iconPresetId/tint.
    public record GetShareVisualsOutput(boolean present,
                                        String bgSymbolId, int bgTint,
                                        String fgSymbolId, int fgTint,
                                        int baseTint,
                                        String displayName, String description,
                                        long totalIssued, long maxSupply) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetShareVisualsOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBoolean(v.present);
                    buf.writeUtf(v.bgSymbolId == null ? "" : v.bgSymbolId);
                    buf.writeInt(v.bgTint);
                    buf.writeUtf(v.fgSymbolId == null ? "" : v.fgSymbolId);
                    buf.writeInt(v.fgTint);
                    buf.writeInt(v.baseTint);
                    buf.writeUtf(v.displayName == null ? "" : v.displayName);
                    buf.writeUtf(v.description == null ? "" : v.description);
                    buf.writeVarLong(v.totalIssued);
                    buf.writeVarLong(v.maxSupply);
                },
                buf -> new GetShareVisualsOutput(
                        buf.readBoolean(),
                        buf.readUtf(), buf.readInt(),
                        buf.readUtf(), buf.readInt(),
                        buf.readInt(),
                        buf.readUtf(), buf.readUtf(),
                        buf.readVarLong(), buf.readVarLong()));
        public static final GetShareVisualsOutput ABSENT =
                new GetShareVisualsOutput(false, "", 0xFFFFFFFF, "", 0xFFFFFFFF, 0xFFFFFFFF, "", "", 0L, 0L);
    }

    // Task #49 (v2.0.8) — dividend distribution.
    public record PayDividendInput(int companyId, long amountPerShare, boolean includeCompanyAccount, UUID callerUUID,
                                   short currencyItem) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PayDividendInput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.companyId);
                    buf.writeVarLong(v.amountPerShare);
                    buf.writeBoolean(v.includeCompanyAccount);
                    buf.writeUUID(v.callerUUID);
                    buf.writeShort(v.currencyItem);
                },
                buf -> new PayDividendInput(
                        buf.readVarInt(),
                        buf.readVarLong(),
                        buf.readBoolean(),
                        buf.readUUID(),
                        buf.readShort()));
    }
    public record PayDividendOutput(int resultCode, long totalPaid, int holderCount) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PayDividendOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,  p -> p.resultCode,
                ByteBufCodecs.VAR_LONG, p -> p.totalPaid,
                ByteBufCodecs.VAR_INT,  p -> p.holderCount,
                PayDividendOutput::new);
    }

    // Task #51 (v2.0.8) — list Share Stamper positions bound to a company.
    public record ListStamperBindingsInput(int companyId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListStamperBindingsInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ListStamperBindingsInput::new);
    }
    public record ListStamperBindingsOutput(List<BlockPos> positions) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListStamperBindingsOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.positions.size());
                    for (BlockPos p : v.positions) BlockPos.STREAM_CODEC.encode(buf, p);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<BlockPos> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(BlockPos.STREAM_CODEC.decode(buf));
                    return new ListStamperBindingsOutput(out);
                });
        public static final ListStamperBindingsOutput EMPTY = new ListStamperBindingsOutput(List.of());
    }

    // Spec §4.3 (v2.0.8) — MANAGE-gated Share Stamper unbind.
    public record UnbindStamperInput(int companyId, BlockPos pos, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UnbindStamperInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                BlockPos.STREAM_CODEC, p -> p.pos,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                UnbindStamperInput::new);
    }
    public record UnbindStamperOutput(int resultCode) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UnbindStamperOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                UnbindStamperOutput::new);
    }

    // Task #54 (v2.0.8) — bulk visuals+info list.
    public record EmptyInput() {
        public static final StreamCodec<RegistryFriendlyByteBuf, EmptyInput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {},
                buf -> new EmptyInput());
    }
    public record ListAllVisualsOutput(List<net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry> entries) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListAllVisualsOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.entries.size());
                    for (var e : v.entries) {
                        buf.writeVarInt(e.companyId());
                        buf.writeUtf(e.bgSymbolId() == null ? "" : e.bgSymbolId());
                        buf.writeInt(e.bgTint());
                        buf.writeUtf(e.fgSymbolId() == null ? "" : e.fgSymbolId());
                        buf.writeInt(e.fgTint());
                        buf.writeInt(e.baseTint());
                        buf.writeUtf(e.displayName() == null ? "" : e.displayName());
                        buf.writeUtf(e.description() == null ? "" : e.description());
                        buf.writeVarLong(e.totalSharesIssued());
                        buf.writeVarLong(e.maxSupply());
                        buf.writeUtf(e.internalName() == null ? "" : e.internalName());
                        buf.writeUtf(e.companyDescription() == null ? "" : e.companyDescription());
                        buf.writeVarInt(e.bankAccountNr());
                        buf.writeVarInt(e.founderNames().size());
                        for (String fn : e.founderNames()) buf.writeUtf(fn);
                        buf.writeVarInt(e.holderCount());
                    }
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        int cid = buf.readVarInt();
                        String bgSym = buf.readUtf();
                        int bgTint = buf.readInt();
                        String fgSym = buf.readUtf();
                        int fgTint = buf.readInt();
                        int baseTint = buf.readInt();
                        String dn = buf.readUtf();
                        String desc = buf.readUtf();
                        long issued = buf.readVarLong();
                        long max = buf.readVarLong();
                        String iname = buf.readUtf();
                        String cdesc = buf.readUtf();
                        int accNr = buf.readVarInt();
                        int fn = buf.readVarInt();
                        List<String> founders = new ArrayList<>(fn);
                        for (int j = 0; j < fn; j++) founders.add(buf.readUtf());
                        int hc = buf.readVarInt();
                        out.add(new net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry(
                                cid, bgSym, bgTint, fgSym, fgTint, baseTint, dn, desc, issued, max, iname, cdesc, accNr, founders, hc));
                    }
                    return new ListAllVisualsOutput(out);
                });
        public static final ListAllVisualsOutput EMPTY = new ListAllVisualsOutput(List.of());
    }

    // Task #54 (v2.0.9) — symbol store ARRS params.
    public record SymbolManifestOutput(int revision,
                                       List<ShareSymbolStore.SymbolEntry> entries) {
        public static final SymbolManifestOutput EMPTY = new SymbolManifestOutput(0, List.of());
        public static final StreamCodec<RegistryFriendlyByteBuf, SymbolManifestOutput> STREAM_CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeVarInt(v.revision);
                            buf.writeVarInt(v.entries.size());
                            for (ShareSymbolStore.SymbolEntry e : v.entries) {
                                buf.writeUtf(e.id());
                                buf.writeVarInt(e.ordinal());
                                buf.writeBytes(e.sha256()); // 32 bytes
                                buf.writeVarInt(e.size());
                            }
                        },
                        buf -> {
                            int rev = buf.readVarInt();
                            int count = buf.readVarInt();
                            List<ShareSymbolStore.SymbolEntry> list = new ArrayList<>(count);
                            for (int i = 0; i < count; i++) {
                                String id = buf.readUtf();
                                int ord = buf.readVarInt();
                                byte[] sha256 = new byte[32];
                                buf.readBytes(sha256);
                                int size = buf.readVarInt();
                                list.add(new ShareSymbolStore.SymbolEntry(id, ord, sha256, size));
                            }
                            return new SymbolManifestOutput(rev, list);
                        });
    }

    public record PullSymbolBytesInput(List<byte[]> hashes) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PullSymbolBytesInput> STREAM_CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeVarInt(v.hashes.size());
                            for (byte[] h : v.hashes) buf.writeBytes(h); // 32 bytes each
                        },
                        buf -> {
                            int count = Math.min(buf.readVarInt(), 8);
                            List<byte[]> list = new ArrayList<>(count);
                            for (int i = 0; i < count; i++) {
                                byte[] h = new byte[32];
                                buf.readBytes(h);
                                list.add(h);
                            }
                            return new PullSymbolBytesInput(list);
                        });
    }

    // Task #1 (v2.0.8) — StockMarket bridge params.
    public record OpenShareMarketInput(int companyId, float initialPrice, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenShareMarketInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.FLOAT,   p -> p.initialPrice,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                OpenShareMarketInput::new);
    }
    public record OpenShareMarketOutput(int status, String reason) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenShareMarketOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,      p -> p.status,
                ByteBufCodecs.STRING_UTF8,  p -> p.reason == null ? "" : p.reason,
                OpenShareMarketOutput::new);
    }
    public record CloseShareMarketInput(int companyId, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseShareMarketInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                CloseShareMarketInput::new);
    }
    public record CloseShareMarketOutput(int status) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseShareMarketOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.status,
                CloseShareMarketOutput::new);
    }
    /** Task #1 (v2.0.8) — SET_MARKET_OPEN payload: companyId + desired open flag + caller for MANAGE gate. */
    public record SetMarketOpenInput(int companyId, boolean open, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SetMarketOpenInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.BOOL,    p -> p.open,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                SetMarketOpenInput::new);
    }
    public record SetMarketOpenOutput(int status) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SetMarketOpenOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.status,
                SetMarketOpenOutput::new);
    }

    public record GetCompanyInfoByAccountInput(int accountNr) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetCompanyInfoByAccountInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.accountNr,
                GetCompanyInfoByAccountInput::new);
    }

    // ------------------------------------------------------------------
    // Spec B.1 (v2.0.8) — filtered account listing for the split target picker.
    // ------------------------------------------------------------------
    public record ListPlayerAccountsInput(int companyId, UUID subject, byte filterMask, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListPlayerAccountsInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                UUIDUtil.STREAM_CODEC, p -> p.subject,
                ByteBufCodecs.BYTE, p -> p.filterMask,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                ListPlayerAccountsInput::new);
    }
    public record AccountEntry(int accountId, String accountName, int filterBits) {}
    public record ListPlayerAccountsOutput(int resultCode, List<AccountEntry> accounts) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListPlayerAccountsOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.resultCode);
                    buf.writeVarInt(v.accounts.size());
                    for (AccountEntry e : v.accounts) {
                        buf.writeVarInt(e.accountId());
                        buf.writeUtf(e.accountName() == null ? "" : e.accountName());
                        buf.writeVarInt(e.filterBits());
                    }
                },
                buf -> {
                    int code = buf.readVarInt();
                    int n = buf.readVarInt();
                    List<AccountEntry> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        out.add(new AccountEntry(buf.readVarInt(), buf.readUtf(), buf.readVarInt()));
                    }
                    return new ListPlayerAccountsOutput(code, out);
                });
        public static final ListPlayerAccountsOutput EMPTY =
                new ListPlayerAccountsOutput(CODE_INTERNAL, List.of());
    }

    // ------------------------------------------------------------------
    // Spec B.3 (v2.0.8) — non-zero item balances on the company account.
    // ------------------------------------------------------------------
    public record ListItemBalancesInput(int companyId, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListItemBalancesInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                ListItemBalancesInput::new);
    }
    public record ItemBalanceEntry(short itemShort, long balance) {}
    /** Bug A fix (v2.0.8) — {@code moneyBalance} carries the money-bank balance so
     *  the picker can render "Money (default)" with its balance without a second RPC. */
    public record ListItemBalancesOutput(int resultCode, List<ItemBalanceEntry> items, long moneyBalance) {
        public ListItemBalancesOutput(int resultCode, List<ItemBalanceEntry> items) {
            this(resultCode, items, 0L);
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, ListItemBalancesOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.resultCode);
                    buf.writeVarInt(v.items.size());
                    for (ItemBalanceEntry e : v.items) {
                        buf.writeShort(e.itemShort());
                        buf.writeVarLong(e.balance());
                    }
                    buf.writeVarLong(v.moneyBalance);
                },
                buf -> {
                    int code = buf.readVarInt();
                    int n = buf.readVarInt();
                    List<ItemBalanceEntry> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(new ItemBalanceEntry(buf.readShort(), buf.readVarLong()));
                    long money = buf.readVarLong();
                    return new ListItemBalancesOutput(code, out, money);
                });
        public static final ListItemBalancesOutput EMPTY =
                new ListItemBalancesOutput(CODE_INTERNAL, List.of(), 0L);
    }

    // ------------------------------------------------------------------
    // Spec B.4 (v2.0.8) — manual missed-payout catch-up.
    // ------------------------------------------------------------------
    public record PayMissedInput(int companyId, long scheduleId, long amount, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PayMissedInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                ByteBufCodecs.VAR_LONG, p -> p.amount,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                PayMissedInput::new);
    }
    public record PayMissedOutput(int resultCode, long remainingMissedAmount, int remainingMissedCount) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PayMissedOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                ByteBufCodecs.VAR_LONG, p -> p.remainingMissedAmount,
                ByteBufCodecs.VAR_INT, p -> p.remainingMissedCount,
                PayMissedOutput::new);
    }

    // ------------------------------------------------------------------
    // Task #52 (v2.0.8) — dividend history records.
    // ------------------------------------------------------------------
    public record ListDividendHistoryInput(int companyId, int limit) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListDividendHistoryInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.VAR_INT, p -> p.limit,
                ListDividendHistoryInput::new);
    }
    /** Wire form of a {@link DividendEvent}. */
    public record DividendEventWire(int companyId, int scheduleId, boolean hasScheduleId,
                                    long timestampMs, short currencyShort,
                                    long perShareRaw, long totalRaw, int holderCount, String sourceKind) {
        public static void write(RegistryFriendlyByteBuf buf, DividendEventWire v) {
            buf.writeVarInt(v.companyId);
            buf.writeBoolean(v.hasScheduleId);
            if (v.hasScheduleId) buf.writeVarInt(v.scheduleId);
            buf.writeVarLong(v.timestampMs);
            buf.writeShort(v.currencyShort);
            buf.writeVarLong(v.perShareRaw);
            buf.writeVarLong(v.totalRaw);
            buf.writeVarInt(v.holderCount);
            buf.writeUtf(v.sourceKind);
        }
        public static DividendEventWire read(RegistryFriendlyByteBuf buf) {
            int cid = buf.readVarInt();
            boolean has = buf.readBoolean();
            int sid = has ? buf.readVarInt() : 0;
            long ts = buf.readVarLong();
            short cs = buf.readShort();
            long psr = buf.readVarLong();
            long tr = buf.readVarLong();
            int hc = buf.readVarInt();
            String sk = buf.readUtf();
            return new DividendEventWire(cid, sid, has, ts, cs, psr, tr, hc, sk);
        }
        public static DividendEventWire of(DividendEvent e) {
            return new DividendEventWire(e.companyId(), e.scheduleId() != null ? e.scheduleId() : 0,
                    e.scheduleId() != null, e.timestampMs(), e.currencyShort(),
                    e.perShareRaw(), e.totalRaw(), e.holderCount(), e.sourceKind());
        }
        public DividendEvent toEvent() {
            return new DividendEvent(companyId, hasScheduleId ? scheduleId : null, timestampMs,
                    currencyShort, perShareRaw, totalRaw, holderCount, sourceKind);
        }
    }
    public record ListDividendHistoryOutput(List<DividendEventWire> events) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListDividendHistoryOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.events.size());
                    for (DividendEventWire e : v.events) DividendEventWire.write(buf, e);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<DividendEventWire> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(DividendEventWire.read(buf));
                    return new ListDividendHistoryOutput(out);
                });
        public static final ListDividendHistoryOutput EMPTY = new ListDividendHistoryOutput(List.of());
    }

    // ------------------------------------------------------------------
    // Statistics tab (v2.0.9) — GET_COMPANY_STATS param/result records
    // ------------------------------------------------------------------
    public record GetCompanyStatsInput(int companyId, int timeframeIndex) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetCompanyStatsInput> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, p -> p.companyId,
                        ByteBufCodecs.VAR_INT, p -> p.timeframeIndex,
                        GetCompanyStatsInput::new);
    }

    public record ShareholderWire(int accountNr, String accountName, long shares, float pct) {
        public static void write(RegistryFriendlyByteBuf buf, ShareholderWire v) {
            buf.writeVarInt(v.accountNr);
            buf.writeUtf(v.accountName == null ? "" : v.accountName);
            buf.writeVarLong(v.shares);
            buf.writeFloat(v.pct);
        }
        public static ShareholderWire read(RegistryFriendlyByteBuf buf) {
            return new ShareholderWire(buf.readVarInt(), buf.readUtf(), buf.readVarLong(), buf.readFloat());
        }
    }

    public record CashflowBucketWire(long bucketStart, long earnings, long spendings) {
        public static void write(RegistryFriendlyByteBuf buf, CashflowBucketWire v) {
            buf.writeVarLong(v.bucketStart);
            buf.writeVarLong(v.earnings);
            buf.writeVarLong(v.spendings);
        }
        public static CashflowBucketWire read(RegistryFriendlyByteBuf buf) {
            return new CashflowBucketWire(buf.readVarLong(), buf.readVarLong(), buf.readVarLong());
        }
    }

    public record CompanyStatsPayload(
            long totalEarnings, long totalSpendings, long netCashflow,
            int missedPayoutCount, long missedPayoutAmount,
            long currentBalance, long daysToInsolvency,
            List<CashflowBucketWire> cashflowSeries,
            List<ShareholderWire> topShareholders) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CompanyStatsPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeVarLong(v.totalEarnings);
                            buf.writeVarLong(v.totalSpendings);
                            buf.writeVarLong(v.netCashflow);
                            buf.writeVarInt(v.missedPayoutCount);
                            buf.writeVarLong(v.missedPayoutAmount);
                            buf.writeVarLong(v.currentBalance);
                            buf.writeVarLong(v.daysToInsolvency);
                            buf.writeVarInt(v.cashflowSeries.size());
                            for (CashflowBucketWire b : v.cashflowSeries) CashflowBucketWire.write(buf, b);
                            buf.writeVarInt(v.topShareholders.size());
                            for (ShareholderWire s : v.topShareholders) ShareholderWire.write(buf, s);
                        },
                        buf -> {
                            long earn = buf.readVarLong(), spend = buf.readVarLong(), net = buf.readVarLong();
                            int mpc = buf.readVarInt(); long mpa = buf.readVarLong();
                            long bal = buf.readVarLong(), days = buf.readVarLong();
                            int nb = buf.readVarInt();
                            List<CashflowBucketWire> buckets = new ArrayList<>(nb);
                            for (int i = 0; i < nb; i++) buckets.add(CashflowBucketWire.read(buf));
                            int ns = buf.readVarInt();
                            List<ShareholderWire> holders = new ArrayList<>(ns);
                            for (int i = 0; i < ns; i++) holders.add(ShareholderWire.read(buf));
                            return new CompanyStatsPayload(earn, spend, net, mpc, mpa, bal, days, buckets, holders);
                        });
        public static final CompanyStatsPayload EMPTY = new CompanyStatsPayload(
                0L, 0L, 0L, 0, 0L, 0L, -1L, List.of(), List.of());
    }

    // ------------------------------------------------------------------
    // v2.0.9 — SET_COMPANY_CURRENCY param/result records
    // ------------------------------------------------------------------
    public record SetCompanyCurrencyInput(int companyId, short currency, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SetCompanyCurrencyInput> STREAM_CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeVarInt(v.companyId);
                            buf.writeShort(v.currency);
                            buf.writeUUID(v.callerUUID);
                        },
                        buf -> new SetCompanyCurrencyInput(buf.readVarInt(), buf.readShort(), buf.readUUID())
                );
    }
    public record SetCompanyCurrencyOutput(int resultCode) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SetCompanyCurrencyOutput> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, SetCompanyCurrencyOutput::resultCode, SetCompanyCurrencyOutput::new);
    }

    // ------------------------------------------------------------------
    // Codec map
    // ------------------------------------------------------------------
    public static final Map<FunctionType, AsyncFunctionDataCodecs> codecs = new HashMap<>() {{
        put(FunctionType.CREATE_COMPANY,     new AsyncFunctionDataCodecs(CreateInput.STREAM_CODEC,      CreateOutput.STREAM_CODEC));
        put(FunctionType.TRANSFER_FOUNDER,   new AsyncFunctionDataCodecs(TransferInput.STREAM_CODEC,    TransferOutput.STREAM_CODEC));
        put(FunctionType.DISSOLVE_COMPANY,   new AsyncFunctionDataCodecs(DissolveInput.STREAM_CODEC,    DissolveOutput.STREAM_CODEC));
        put(FunctionType.UPDATE_DESCRIPTION, new AsyncFunctionDataCodecs(DescriptionInput.STREAM_CODEC, DescriptionOutput.STREAM_CODEC));
        put(FunctionType.GET_COMPANY_INFO,   new AsyncFunctionDataCodecs(ByteBufCodecs.STRING_UTF8.cast(), CompanyInfoOutput.STREAM_CODEC));
        put(FunctionType.IS_NAME_TAKEN,      new AsyncFunctionDataCodecs(ByteBufCodecs.STRING_UTF8.cast(), ByteBufCodecs.BOOL.cast()));
        put(FunctionType.LIST_COMPANIES_FOR_CALLER, new AsyncFunctionDataCodecs(ListInput.STREAM_CODEC, ListOutput.STREAM_CODEC));
        put(FunctionType.CREATE_PAYOUT,      new AsyncFunctionDataCodecs(CreatePayoutInput.STREAM_CODEC, CreatePayoutOutput.STREAM_CODEC));
        put(FunctionType.UPDATE_PAYOUT,      new AsyncFunctionDataCodecs(UpdatePayoutInput.STREAM_CODEC, UpdatePayoutOutput.STREAM_CODEC));
        put(FunctionType.PAUSE_PAYOUT,       new AsyncFunctionDataCodecs(PausePayoutInput.STREAM_CODEC,  PausePayoutOutput.STREAM_CODEC));
        put(FunctionType.DELETE_PAYOUT,      new AsyncFunctionDataCodecs(DeletePayoutInput.STREAM_CODEC, DeletePayoutOutput.STREAM_CODEC));
        put(FunctionType.LIST_SCHEDULES,     new AsyncFunctionDataCodecs(ListSchedulesInput.STREAM_CODEC, ListSchedulesOutput.STREAM_CODEC));
        put(FunctionType.GET_HISTORY,        new AsyncFunctionDataCodecs(GetHistoryInput.STREAM_CODEC,   GetHistoryOutput.STREAM_CODEC));
        put(FunctionType.GET_COMPANY_INFO_BY_ACCOUNT, new AsyncFunctionDataCodecs(GetCompanyInfoByAccountInput.STREAM_CODEC, CompanyInfoOutput.STREAM_CODEC));
        put(FunctionType.GET_FAILURE_COUNT_24H, new AsyncFunctionDataCodecs(GetFailureCount24hInput.STREAM_CODEC, GetFailureCount24hOutput.STREAM_CODEC));
        put(FunctionType.UPDATE_SHARE_VISUALS, new AsyncFunctionDataCodecs(UpdateShareVisualsInput.STREAM_CODEC, UpdateShareVisualsOutput.STREAM_CODEC));
        put(FunctionType.GET_SHARE_VISUALS,    new AsyncFunctionDataCodecs(GetShareVisualsInput.STREAM_CODEC,    GetShareVisualsOutput.STREAM_CODEC));
        put(FunctionType.PAY_DIVIDEND,         new AsyncFunctionDataCodecs(PayDividendInput.STREAM_CODEC,       PayDividendOutput.STREAM_CODEC));
        put(FunctionType.LIST_STAMPER_BINDINGS, new AsyncFunctionDataCodecs(ListStamperBindingsInput.STREAM_CODEC, ListStamperBindingsOutput.STREAM_CODEC));
        put(FunctionType.GET_COMPANY_INFO_BY_ID, new AsyncFunctionDataCodecs(GetShareVisualsInput.STREAM_CODEC, CompanyInfoOutput.STREAM_CODEC));
        put(FunctionType.COUNT_HOLDERS_FOR_COMPANY, new AsyncFunctionDataCodecs(GetShareVisualsInput.STREAM_CODEC, ByteBufCodecs.VAR_INT.cast()));
        put(FunctionType.LIST_ALL_COMPANY_VISUALS, new AsyncFunctionDataCodecs(EmptyInput.STREAM_CODEC, ListAllVisualsOutput.STREAM_CODEC));
        put(FunctionType.OPEN_SHARE_MARKET,         new AsyncFunctionDataCodecs(OpenShareMarketInput.STREAM_CODEC,  OpenShareMarketOutput.STREAM_CODEC));
        put(FunctionType.CLOSE_SHARE_MARKET,        new AsyncFunctionDataCodecs(CloseShareMarketInput.STREAM_CODEC, CloseShareMarketOutput.STREAM_CODEC));
        put(FunctionType.MARKET_EXISTS_FOR_COMPANY, new AsyncFunctionDataCodecs(GetShareVisualsInput.STREAM_CODEC,   ByteBufCodecs.VAR_INT.cast()));
        put(FunctionType.SET_MARKET_OPEN,           new AsyncFunctionDataCodecs(SetMarketOpenInput.STREAM_CODEC,     SetMarketOpenOutput.STREAM_CODEC));
        put(FunctionType.IS_MARKET_OPEN,            new AsyncFunctionDataCodecs(GetShareVisualsInput.STREAM_CODEC,   ByteBufCodecs.VAR_INT.cast()));
        put(FunctionType.UNBIND_STAMPER,            new AsyncFunctionDataCodecs(UnbindStamperInput.STREAM_CODEC,     UnbindStamperOutput.STREAM_CODEC));
        put(FunctionType.LIST_PLAYER_ACCOUNTS_WITH_FILTER, new AsyncFunctionDataCodecs(ListPlayerAccountsInput.STREAM_CODEC, ListPlayerAccountsOutput.STREAM_CODEC));
        put(FunctionType.LIST_ACCOUNT_ITEM_BALANCES, new AsyncFunctionDataCodecs(ListItemBalancesInput.STREAM_CODEC, ListItemBalancesOutput.STREAM_CODEC));
        put(FunctionType.PAY_MISSED,                new AsyncFunctionDataCodecs(PayMissedInput.STREAM_CODEC,        PayMissedOutput.STREAM_CODEC));
        put(FunctionType.LIST_DIVIDEND_HISTORY,     new AsyncFunctionDataCodecs(ListDividendHistoryInput.STREAM_CODEC, ListDividendHistoryOutput.STREAM_CODEC));
        put(FunctionType.GET_COMPANY_STATS,         new AsyncFunctionDataCodecs(GetCompanyStatsInput.STREAM_CODEC,  CompanyStatsPayload.STREAM_CODEC));
        put(FunctionType.SET_COMPANY_CURRENCY,      new AsyncFunctionDataCodecs(SetCompanyCurrencyInput.STREAM_CODEC, SetCompanyCurrencyOutput.STREAM_CODEC));
        // Task #54 (v2.0.9) — symbol store ARRS.
        put(FunctionType.GET_SYMBOL_MANIFEST,       new AsyncFunctionDataCodecs(EmptyInput.STREAM_CODEC,            SymbolManifestOutput.STREAM_CODEC));
        put(FunctionType.PULL_SYMBOL_BYTES,         new AsyncFunctionDataCodecs(PullSymbolBytesInput.STREAM_CODEC,  EmptyInput.STREAM_CODEC));
    }};

    // ------------------------------------------------------------------
    // Input / Output containers
    // ------------------------------------------------------------------
    public static class InputData extends AsyncFunctionInputData<FunctionType> {
        public InputData(FunctionType function, byte[] encodedParams) {
            super(function, codecs.get(function).inputParamsCodec, encodedParams);
        }
        public static <T> InputData of(FunctionType type, T params) {
            return (InputData) AsyncFunctionInputData.of(codecs.get(type).inputParamsCodec, type, params, InputData::new);
        }
    }
    public static class OutputData extends AsyncFunctionOutputData<FunctionType> {
        public OutputData(FunctionType function, byte[] encodedResult) {
            super(function, codecs.get(function).outputParamsCodec, encodedResult);
        }
        public static <T> OutputData of(FunctionType type, T result) {
            return (OutputData) AsyncFunctionOutputData.of(codecs.get(type).outputParamsCodec, type, result, OutputData::new);
        }
    }

    // ------------------------------------------------------------------
    // ARRS request
    // ------------------------------------------------------------------
    public static class Request extends AsyncForwardingRequest<FunctionType, InputData, OutputData> {
        public static final Request instance = (Request) AsynchronousRequestResponseSystem.register(new Request());

        public Request() {
            super(InputData::new, OutputData::new, FunctionType.class);
        }

        @Override
        public String getRequestTypeID() {
            return Request.class.getName();
        }

        @Override
        public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
            String playerName = "";
            if (playerSender != null) playerName = tryGetPlayerName(playerSender);
            if (!isRequestAllowed(input, slaveID, playerSender, playerName))
                return CompletableFuture.completedFuture(fallbackOutput(input.function));

            IServerBankManager bm = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            CompanyManager cm = CompanyManager.get();
            if (bm == null || cm == null) {
                return CompletableFuture.completedFuture(fallbackOutput(input.function));
            }

            return CompletableFuture.completedFuture(switch (input.function) {
                case CREATE_COMPANY     -> handleCreate(input.decodeParams(), bm, cm);
                case TRANSFER_FOUNDER   -> handleTransfer(input.decodeParams(), bm, cm);
                case DISSOLVE_COMPANY   -> handleDissolve(input.decodeParams(), cm);
                case UPDATE_DESCRIPTION -> handleDescription(input.decodeParams(), bm, cm);
                case GET_COMPANY_INFO   -> handleInfo((String) input.decodeParams(), bm, cm);
                case IS_NAME_TAKEN      -> OutputData.of(FunctionType.IS_NAME_TAKEN,
                                             cm.isNameTaken((String) input.decodeParams()));
                case LIST_COMPANIES_FOR_CALLER -> handleListForCaller(input.decodeParams(), cm);
                case CREATE_PAYOUT      -> handleCreatePayout(input.decodeParams(), bm, cm);
                case UPDATE_PAYOUT      -> handleUpdatePayout(input.decodeParams(), bm, cm);
                case PAUSE_PAYOUT       -> handlePausePayout(input.decodeParams(), bm, cm);
                case DELETE_PAYOUT      -> handleDeletePayout(input.decodeParams(), bm, cm);
                case LIST_SCHEDULES     -> handleListSchedules(input.decodeParams());
                case GET_HISTORY        -> handleGetHistory(input.decodeParams());
                case GET_COMPANY_INFO_BY_ACCOUNT -> handleInfoByAccount(input.decodeParams(), bm, cm);
                case GET_FAILURE_COUNT_24H -> handleFailureCount24h(input.decodeParams());
                case UPDATE_SHARE_VISUALS -> handleUpdateShareVisuals(input.decodeParams(), bm, cm);
                case GET_SHARE_VISUALS    -> handleGetShareVisuals(input.decodeParams(), cm);
                case PAY_DIVIDEND         -> handlePayDividend(input.decodeParams(), bm, cm);
                case LIST_STAMPER_BINDINGS -> handleListStamperBindings(input.decodeParams(), cm);
                case GET_COMPANY_INFO_BY_ID -> handleInfoById(input.decodeParams(), bm, cm);
                case COUNT_HOLDERS_FOR_COMPANY -> handleCountHoldersForCompany(input.decodeParams(), bm, cm);
                case LIST_ALL_COMPANY_VISUALS -> handleListAllVisuals(bm, cm);
                case OPEN_SHARE_MARKET         -> handleOpenShareMarket(input.decodeParams(), bm, cm);
                case CLOSE_SHARE_MARKET        -> handleCloseShareMarket(input.decodeParams(), cm);
                case MARKET_EXISTS_FOR_COMPANY -> handleMarketExistsForCompany(input.decodeParams(), cm);
                case SET_MARKET_OPEN           -> handleSetMarketOpen(input.decodeParams(), bm, cm);
                case IS_MARKET_OPEN            -> handleIsMarketOpen(input.decodeParams(), cm);
                case UNBIND_STAMPER            -> handleUnbindStamper(input.decodeParams(), bm, cm);
                case LIST_PLAYER_ACCOUNTS_WITH_FILTER -> handleListPlayerAccounts(input.decodeParams(), bm, cm);
                case LIST_ACCOUNT_ITEM_BALANCES -> handleListItemBalances(input.decodeParams(), bm, cm);
                case PAY_MISSED                -> handlePayMissed(input.decodeParams(), bm, cm);
                case LIST_DIVIDEND_HISTORY     -> handleListDividendHistory(input.decodeParams());
                case GET_COMPANY_STATS         -> handleGetCompanyStats(input.decodeParams(), bm, cm);
                case SET_COMPANY_CURRENCY      -> handleSetCompanyCurrency(input.decodeParams(), bm, cm);
                // Task #54 (v2.0.9) — symbol store (don't need bm/cm).
                case GET_SYMBOL_MANIFEST -> handleGetSymbolManifest();
                case PULL_SYMBOL_BYTES   -> handlePullSymbolBytes(input.decodeParams(), slaveID);
            });
        }

        @Override
        protected boolean isAllowedToCallByClient(InputData input) {
            return switch (input.function) {
                // ── Read-only queries used by client screens ──────────────────────────
                case GET_COMPANY_INFO, GET_COMPANY_INFO_BY_ID, GET_COMPANY_INFO_BY_ACCOUNT,
                     LIST_COMPANIES_FOR_CALLER, GET_COMPANY_STATS, COUNT_HOLDERS_FOR_COMPANY,
                     LIST_ALL_COMPANY_VISUALS, GET_SHARE_VISUALS, MARKET_EXISTS_FOR_COMPANY,
                     IS_MARKET_OPEN, LIST_STAMPER_BINDINGS, LIST_SCHEDULES, GET_HISTORY,
                     GET_FAILURE_COUNT_24H, LIST_DIVIDEND_HISTORY, LIST_ACCOUNT_ITEM_BALANCES,
                     LIST_PLAYER_ACCOUNTS_WITH_FILTER, IS_NAME_TAKEN,
                     GET_SYMBOL_MANIFEST, PULL_SYMBOL_BYTES -> true;
                // ── MANAGE-gated mutations from client screens ────────────────────────
                // The server-side handler re-checks rights via gateManage / gateFounder,
                // so allowing these from the client is safe.
                case UPDATE_SHARE_VISUALS,
                     CREATE_PAYOUT, UPDATE_PAYOUT, PAUSE_PAYOUT, DELETE_PAYOUT,
                     PAY_DIVIDEND, PAY_MISSED, UNBIND_STAMPER,
                     OPEN_SHARE_MARKET, CLOSE_SHARE_MARKET, SET_MARKET_OPEN,
                     SET_COMPANY_CURRENCY -> true;
                default -> false;
            };
        }

        @Override
        protected boolean isAllowedToCallByUntrustedSlaveServer(InputData input) {
            return switch (input.function) {
                case GET_COMPANY_INFO, IS_NAME_TAKEN, LIST_COMPANIES_FOR_CALLER,
                     LIST_SCHEDULES, GET_HISTORY, GET_COMPANY_INFO_BY_ACCOUNT,
                     GET_FAILURE_COUNT_24H, GET_SHARE_VISUALS,
                     LIST_STAMPER_BINDINGS, GET_COMPANY_INFO_BY_ID,
                     COUNT_HOLDERS_FOR_COMPANY, LIST_ALL_COMPANY_VISUALS,
                     MARKET_EXISTS_FOR_COMPANY, IS_MARKET_OPEN,
                     LIST_PLAYER_ACCOUNTS_WITH_FILTER, LIST_ACCOUNT_ITEM_BALANCES,
                     LIST_DIVIDEND_HISTORY, GET_COMPANY_STATS,
                     // Task #54 (v2.0.9) — read-only symbol store queries.
                     GET_SYMBOL_MANIFEST, PULL_SYMBOL_BYTES -> true;
                default -> false;
            };
        }
    }

    public static void setupNetworkPacket() {
        Request instance = Request.instance;
    }

    // ------------------------------------------------------------------
    // Master-side handler bodies (extracted for readability)
    // ------------------------------------------------------------------
    private static OutputData handleCreate(CreateInput in, IServerBankManager bm, CompanyManager cm) {
        if (in.name == null || in.name.isBlank())
            return OutputData.of(FunctionType.CREATE_COMPANY,
                    new CreateOutput(CODE_INVALID_INPUT, 0, 0, in.name == null ? "" : in.name, in.maxSupply));
        if (cm.isNameTaken(in.name))
            return OutputData.of(FunctionType.CREATE_COMPANY,
                    new CreateOutput(CODE_NAME_TAKEN, 0, 0, in.name, in.maxSupply));

        IServerBankAccount account = bm.createBankAccount(in.name);
        if (account == null) {
            return OutputData.of(FunctionType.CREATE_COMPANY,
                    new CreateOutput(CODE_BANK_ACCOUNT_ERROR, 0, 0, in.name, in.maxSupply));
        }
        User caller = bm.getUserByUUID(in.callerUUID);
        if (caller == null) {
            String cname = in.callerName == null || in.callerName.isEmpty() ? in.callerUUID.toString() : in.callerName;
            bm.addUser(new User(in.callerUUID, cname, true));
            caller = bm.getUserByUUID(in.callerUUID);
        }
        if (caller != null) {
            account.addUser(caller, BankPermission.MANAGE.getValue());
        }
        CompanyManager.CreateOutcome outcome =
                cm.createCompany(in.name, account.getAccountNumber(), in.callerUUID, in.maxSupply);
        if (outcome.result != CompanyManager.CreateResult.OK) {
            bm.deleteBankAccount(account.getAccountNumber());
            int code = switch (outcome.result) {
                case NAME_TAKEN -> CODE_NAME_TAKEN;
                case INVALID_NAME, INVALID_MAX_SUPPLY -> CODE_INVALID_INPUT;
                case BANK_ACCOUNT_MISSING, BANK_ACCOUNT_ALREADY_HAS_COMPANY -> CODE_BANK_ACCOUNT_ERROR;
                default -> CODE_INTERNAL;
            };
            return OutputData.of(FunctionType.CREATE_COMPANY,
                    new CreateOutput(code, 0, 0, in.name, in.maxSupply));
        }
        // Task #54 (v2.0.8) — broadcast the fresh company to all clients + slaves so
        // stamped-share tooltips and slave mirrors learn about it immediately (rather
        // than waiting for the next join-time bulk sync).
        Company created = outcome.company;
        net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
        if (srv != null && created != null) {
            net.kroia.banksystem.networking.general.S2CCompanyVisualUpdatePacket
                    .broadcast(srv, created.getCompanyId(), created.getShareVisuals(),
                            created.getTotalSharesIssued(), created.getMaxSupply());
        }
        return OutputData.of(FunctionType.CREATE_COMPANY,
                new CreateOutput(CODE_OK, outcome.company.getCompanyId(),
                        account.getAccountNumber(), in.name, in.maxSupply));
    }

    private static OutputData handleTransfer(TransferInput in, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getByName(in.companyName);
        if (company == null)
            return OutputData.of(FunctionType.TRANSFER_FOUNDER,
                    new TransferOutput(CODE_NOT_FOUND, 0, "", in.targetName));
        if (!company.isFounder(in.callerUUID))
            return OutputData.of(FunctionType.TRANSFER_FOUNDER,
                    new TransferOutput(CODE_NOT_FOUNDER, company.getCompanyId(), "", in.targetName));
        String cleaned = in.targetName == null ? "" : in.targetName.replace("\"", "");
        User target = bm.getUserByName(cleaned);
        if (target == null)
            return OutputData.of(FunctionType.TRANSFER_FOUNDER,
                    new TransferOutput(CODE_MISSING_TARGET, company.getCompanyId(), "", cleaned));
        CompanyManager.TransferResult r = cm.transferFounder(company.getCompanyId(), in.callerUUID, target.getUUID());
        int code = switch (r) {
            case OK -> CODE_OK;
            case COMPANY_MISSING -> CODE_NOT_FOUND;
            case NOT_A_FOUNDER -> CODE_NOT_FOUNDER;
            case ALREADY_A_FOUNDER -> CODE_ALREADY_FOUNDER;
        };
        User from = bm.getUserByUUID(in.callerUUID);
        String fromName = from != null ? from.getName() : in.callerUUID.toString();
        // Task #54 (v2.0.8) — republish so slave mirrors and clients see the founder list change.
        if (r == CompanyManager.TransferResult.OK) {
            net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
            if (srv != null) {
                net.kroia.banksystem.networking.general.S2CCompanyVisualUpdatePacket
                        .broadcast(srv, company.getCompanyId(), company.getShareVisuals(),
                                company.getTotalSharesIssued(), company.getMaxSupply());
            }
        }
        return OutputData.of(FunctionType.TRANSFER_FOUNDER,
                new TransferOutput(code, company.getCompanyId(), fromName, target.getName()));
    }

    private static OutputData handleDissolve(DissolveInput in, CompanyManager cm) {
        Company company = cm.getByName(in.companyName);
        if (company == null)
            return OutputData.of(FunctionType.DISSOLVE_COMPANY,
                    new DissolveOutput(CODE_NOT_FOUND, 0, in.companyName == null ? "" : in.companyName, 0));
        if (!company.isFounder(in.callerUUID))
            return OutputData.of(FunctionType.DISSOLVE_COMPANY,
                    new DissolveOutput(CODE_NOT_FOUNDER, company.getCompanyId(), company.getName(), company.getBankAccountNr()));
        String name = company.getName();
        int accNr = company.getBankAccountNr();
        int id = company.getCompanyId();
        boolean ok = cm.deleteCompany(id);
        // Task #54 (v2.0.8) — broadcast a REMOVE to slaves so their mirror drops the row.
        if (ok && net.kroia.modutilities.networking.multi_server.MultiServerManager.isRunning()
                && net.kroia.modutilities.networking.multi_server.MultiServerManager.isMaster()) {
            net.kroia.modutilities.networking.multi_server.MultiServerManager.broadcastToSlaves(
                    net.kroia.banksystem.networking.multi_server.S2SCompanyMirrorPacket.remove(id));
        }
        return OutputData.of(FunctionType.DISSOLVE_COMPANY,
                new DissolveOutput(ok ? CODE_OK : CODE_INTERNAL, id, name, accNr));
    }

    private static OutputData handleDescription(DescriptionInput in, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getByName(in.companyName);
        if (company == null)
            return OutputData.of(FunctionType.UPDATE_DESCRIPTION,
                    new DescriptionOutput(CODE_NOT_FOUND, 0));
        IServerBankAccount account = bm.getBankAccount(company.getBankAccountNr());
        boolean hasManage = account != null && account.hasPermission(in.callerUUID, BankPermission.MANAGE);
        boolean isAdmin = bm.isBanksystemAdmin(in.callerUUID);
        // v2.0.8 Bug1 root cause — description save silently rejected for founders that
        // do not carry explicit MANAGE on the linked bank account. Founder status is a
        // company-level authority; treat it as sufficient for description edits.
        boolean isFounder = company.isFounder(in.callerUUID);
        if (!hasManage && !isAdmin && !isFounder)
            return OutputData.of(FunctionType.UPDATE_DESCRIPTION,
                    new DescriptionOutput(CODE_NO_PERMISSION, company.getCompanyId()));
        cm.updateDescription(company.getCompanyId(), in.text == null ? "" : in.text);
        // Task #54 (v2.0.8) — republish so slave mirrors + all clients pick up the new
        // description via the standard update broadcast (also carries current visuals).
        net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
        if (srv != null) {
            net.kroia.banksystem.networking.general.S2CCompanyVisualUpdatePacket
                    .broadcast(srv, company.getCompanyId(), company.getShareVisuals(),
                            company.getTotalSharesIssued(), company.getMaxSupply());
            // Task #51 (v2.0.8, spec §1.4) — the visual packet does NOT carry
            // Company.description; push it explicitly so other clients' Overview
            // tabs refresh. Slaves receive it via the S2S mirror upsert above and
            // re-forward to their own clients.
            net.kroia.banksystem.networking.general.S2CCompanyDescriptionUpdatePacket
                    .broadcast(srv, company.getCompanyId(), company.getDescription());
        }
        return OutputData.of(FunctionType.UPDATE_DESCRIPTION,
                new DescriptionOutput(CODE_OK, company.getCompanyId()));
    }

    private static OutputData handleInfo(String companyName, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getByName(companyName);
        if (company == null)
            return OutputData.of(FunctionType.GET_COMPANY_INFO, CompanyInfoOutput.ABSENT);
        Set<UUID> founders = company.getFounders();
        List<String> founderNames = new ArrayList<>(founders.size());
        for (UUID uuid : founders) {
            User u = bm.getUserByUUID(uuid);
            founderNames.add(u != null ? u.getName() : uuid.toString());
        }
        return OutputData.of(FunctionType.GET_COMPANY_INFO,
                new CompanyInfoOutput(true, company.getCompanyId(), company.getName(),
                        company.getBankAccountNr(), company.getMaxSupply(),
                        company.getTotalSharesIssued(),
                        company.getDescription() == null ? "" : company.getDescription(),
                        founderNames, company.getCompanyCurrency()));
    }

    private static OutputData handleListForCaller(ListInput in, CompanyManager cm) {
        Set<Company> set = switch (in.filterKind) {
            case FILTER_FOUNDER -> cm.listCompaniesFounderedBy(in.callerUUID);
            case FILTER_MANAGE  -> cm.listCompaniesManagedBy(in.callerUUID);
            default             -> cm.listAllCompanies();
        };
        List<String> names = new ArrayList<>(set.size());
        for (Company c : set) names.add(c.getName());
        return OutputData.of(FunctionType.LIST_COMPANIES_FOR_CALLER, new ListOutput(names));
    }

    private static OutputData fallbackOutput(FunctionType function) {
        return switch (function) {
            case CREATE_COMPANY     -> OutputData.of(function, new CreateOutput(CODE_INTERNAL, 0, 0, "", 0L));
            case TRANSFER_FOUNDER   -> OutputData.of(function, new TransferOutput(CODE_INTERNAL, 0, "", ""));
            case DISSOLVE_COMPANY   -> OutputData.of(function, new DissolveOutput(CODE_INTERNAL, 0, "", 0));
            case UPDATE_DESCRIPTION -> OutputData.of(function, new DescriptionOutput(CODE_INTERNAL, 0));
            case GET_COMPANY_INFO   -> OutputData.of(function, CompanyInfoOutput.ABSENT);
            case IS_NAME_TAKEN      -> OutputData.of(function, Boolean.FALSE);
            case LIST_COMPANIES_FOR_CALLER -> OutputData.of(function, ListOutput.EMPTY);
            case CREATE_PAYOUT      -> OutputData.of(function, new CreatePayoutOutput(CODE_INTERNAL, 0L));
            case UPDATE_PAYOUT      -> OutputData.of(function, new UpdatePayoutOutput(CODE_INTERNAL));
            case PAUSE_PAYOUT       -> OutputData.of(function, new PausePayoutOutput(CODE_INTERNAL));
            case DELETE_PAYOUT      -> OutputData.of(function, new DeletePayoutOutput(CODE_INTERNAL));
            case LIST_SCHEDULES     -> OutputData.of(function, ListSchedulesOutput.EMPTY);
            case GET_HISTORY        -> OutputData.of(function, GetHistoryOutput.EMPTY);
            case GET_COMPANY_INFO_BY_ACCOUNT -> OutputData.of(function, CompanyInfoOutput.ABSENT);
            case GET_FAILURE_COUNT_24H -> OutputData.of(function, new GetFailureCount24hOutput(0L));
            case UPDATE_SHARE_VISUALS -> OutputData.of(function, new UpdateShareVisualsOutput(CODE_INTERNAL));
            case GET_SHARE_VISUALS    -> OutputData.of(function, GetShareVisualsOutput.ABSENT);
            case PAY_DIVIDEND         -> OutputData.of(function, new PayDividendOutput(CODE_INTERNAL, 0L, 0));
            case LIST_STAMPER_BINDINGS -> OutputData.of(function, ListStamperBindingsOutput.EMPTY);
            case GET_COMPANY_INFO_BY_ID -> OutputData.of(function, CompanyInfoOutput.ABSENT);
            case COUNT_HOLDERS_FOR_COMPANY -> OutputData.of(function, Integer.valueOf(0));
            case LIST_ALL_COMPANY_VISUALS -> OutputData.of(function, ListAllVisualsOutput.EMPTY);
            case OPEN_SHARE_MARKET         -> OutputData.of(function, new OpenShareMarketOutput(SM_STATUS_UNAVAILABLE, ""));
            case CLOSE_SHARE_MARKET        -> OutputData.of(function, new CloseShareMarketOutput(SM_STATUS_UNAVAILABLE));
            case MARKET_EXISTS_FOR_COMPANY -> OutputData.of(function, Integer.valueOf(MARKET_EXISTS_UNAV));
            case SET_MARKET_OPEN           -> OutputData.of(function, new SetMarketOpenOutput(SM_STATUS_UNAVAILABLE));
            case IS_MARKET_OPEN            -> OutputData.of(function, Integer.valueOf(MARKET_OPEN_UNAV));
            case UNBIND_STAMPER            -> OutputData.of(function, new UnbindStamperOutput(CODE_INTERNAL));
            case LIST_PLAYER_ACCOUNTS_WITH_FILTER -> OutputData.of(function, ListPlayerAccountsOutput.EMPTY);
            case LIST_ACCOUNT_ITEM_BALANCES -> OutputData.of(function, ListItemBalancesOutput.EMPTY);
            case PAY_MISSED                -> OutputData.of(function, new PayMissedOutput(CODE_INTERNAL, 0L, 0));
            case LIST_DIVIDEND_HISTORY     -> OutputData.of(function, ListDividendHistoryOutput.EMPTY);
            case GET_COMPANY_STATS         -> OutputData.of(function, CompanyStatsPayload.EMPTY);
            case SET_COMPANY_CURRENCY      -> OutputData.of(function, new SetCompanyCurrencyOutput(CODE_INTERNAL));
            // Task #54 (v2.0.9)
            case GET_SYMBOL_MANIFEST -> OutputData.of(function, SymbolManifestOutput.EMPTY);
            case PULL_SYMBOL_BYTES   -> OutputData.of(function, new EmptyInput());
        };
    }

    // ------------------------------------------------------------------
    // Task #1 (v2.0.8) — StockMarket bridge handlers (master-side).
    // Server-thread contract: SM API requires server thread. ARRS may not run
    // handlers on the server thread; marshal via MinecraftServer.execute if
    // needed. Existing sibling handlers touch live BankSystem state directly,
    // so we mirror that pattern and rely on SM's own thread-safety fencing.
    // ------------------------------------------------------------------

    /** Reverse-iterate the ItemID registry to find the ONE share ItemID that belongs to companyId. */
    private static net.kroia.banksystem.util.ItemID resolveShareItemID(int companyId) {
        for (Map.Entry<net.kroia.banksystem.util.ItemID, net.minecraft.world.item.ItemStack> e
                : net.kroia.banksystem.util.ItemIDManager.getItemIDMap().entrySet()) {
            Integer cid = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                    .getCompanyIdForItemID(e.getKey());
            if (cid != null && cid == companyId) return e.getKey();
        }
        return null;
    }

    private static OutputData handleOpenShareMarket(OpenShareMarketInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) {
            int mapped = gate == CODE_NO_PERMISSION ? SM_STATUS_NO_PERMISSION
                       : gate == CODE_NOT_FOUND     ? SM_STATUS_NOT_FOUND
                       : SM_STATUS_FAILED;
            return OutputData.of(FunctionType.OPEN_SHARE_MARKET, new OpenShareMarketOutput(mapped, ""));
        }
        net.kroia.banksystem.util.ItemID share = resolveShareItemID(in.companyId);
        if (share == null) {
            return OutputData.of(FunctionType.OPEN_SHARE_MARKET,
                    new OpenShareMarketOutput(SM_STATUS_NOT_FOUND, "no share ItemID for company"));
        }
        net.kroia.banksystem.integration.stockmarket.StockMarketBridge.OpenResult r =
                runOnServerThreadSync(() -> net.kroia.banksystem.integration.stockmarket.StockMarketBridge
                        .openMarket(share, in.initialPrice));
        int code = switch (r == null ? net.kroia.banksystem.integration.stockmarket.StockMarketBridge.Status.UNAVAILABLE : r.status()) {
            case SUCCESS          -> SM_STATUS_SUCCESS;
            case ALREADY_EXISTS   -> SM_STATUS_ALREADY_EXISTS;
            case ITEM_BLACKLISTED -> SM_STATUS_ITEM_BLACKLISTED;
            case FAILED           -> SM_STATUS_FAILED;
            case UNAVAILABLE      -> SM_STATUS_UNAVAILABLE;
        };
        String reason = r == null || r.reason() == null ? "" : r.reason();
        return OutputData.of(FunctionType.OPEN_SHARE_MARKET, new OpenShareMarketOutput(code, reason));
    }

    private static OutputData handleCloseShareMarket(CloseShareMarketInput in, CompanyManager cm) {
        // Founder-only (mirror handleDissolve semantics).
        Company company = cm.getById(in.companyId);
        if (company == null)
            return OutputData.of(FunctionType.CLOSE_SHARE_MARKET, new CloseShareMarketOutput(SM_STATUS_NOT_FOUND));
        if (!company.isFounder(in.callerUUID))
            return OutputData.of(FunctionType.CLOSE_SHARE_MARKET, new CloseShareMarketOutput(SM_STATUS_NO_PERMISSION));
        net.kroia.banksystem.util.ItemID share = resolveShareItemID(in.companyId);
        if (share == null)
            return OutputData.of(FunctionType.CLOSE_SHARE_MARKET, new CloseShareMarketOutput(SM_STATUS_NOT_FOUND));
        Boolean ok = runOnServerThreadSync(() ->
                net.kroia.banksystem.integration.stockmarket.StockMarketBridge.closeMarket(share));
        return OutputData.of(FunctionType.CLOSE_SHARE_MARKET,
                new CloseShareMarketOutput(Boolean.TRUE.equals(ok) ? SM_STATUS_SUCCESS : SM_STATUS_UNAVAILABLE));
    }

    /**
     * Task #1 (v2.0.8) — pause/resume the SM market for this company's share.
     * MANAGE-gated (mirrors {@link #gateManage}). Server-thread marshalling is
     * handled by the bridge call itself (SM API is server-thread only).
     */
    private static OutputData handleSetMarketOpen(SetMarketOpenInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) {
            int mapped = gate == CODE_NO_PERMISSION ? SM_STATUS_NO_PERMISSION
                       : gate == CODE_NOT_FOUND     ? SM_STATUS_NOT_FOUND
                       : SM_STATUS_FAILED;
            return OutputData.of(FunctionType.SET_MARKET_OPEN, new SetMarketOpenOutput(mapped));
        }
        net.kroia.banksystem.util.ItemID share = resolveShareItemID(in.companyId);
        if (share == null)
            return OutputData.of(FunctionType.SET_MARKET_OPEN, new SetMarketOpenOutput(SM_STATUS_NOT_FOUND));
        Boolean ok = runOnServerThreadSync(() ->
                net.kroia.banksystem.integration.stockmarket.StockMarketBridge.setMarketOpen(share, in.open));
        return OutputData.of(FunctionType.SET_MARKET_OPEN,
                new SetMarketOpenOutput(Boolean.TRUE.equals(ok) ? SM_STATUS_SUCCESS : SM_STATUS_UNAVAILABLE));
    }

    /** Task #1 (v2.0.8) — is the SM market open for trading? Read-only; no gate. */
    private static OutputData handleIsMarketOpen(GetShareVisualsInput in, CompanyManager cm) {
        net.kroia.banksystem.util.ItemID share = resolveShareItemID(in.companyId());
        if (share == null)
            return OutputData.of(FunctionType.IS_MARKET_OPEN, Integer.valueOf(MARKET_OPEN_UNAV));
        net.kroia.banksystem.integration.stockmarket.StockMarketBridge.MarketOpen open =
                runOnServerThreadSync(() ->
                        net.kroia.banksystem.integration.stockmarket.StockMarketBridge.isMarketOpen(share));
        int code = switch (open == null ? net.kroia.banksystem.integration.stockmarket.StockMarketBridge.MarketOpen.UNAVAILABLE : open) {
            case YES -> MARKET_OPEN_YES;
            case NO  -> MARKET_OPEN_NO;
            case UNAVAILABLE -> MARKET_OPEN_UNAV;
        };
        return OutputData.of(FunctionType.IS_MARKET_OPEN, Integer.valueOf(code));
    }

    private static OutputData handleMarketExistsForCompany(GetShareVisualsInput in, CompanyManager cm) {
        net.kroia.banksystem.util.ItemID share = resolveShareItemID(in.companyId());
        if (share == null)
            return OutputData.of(FunctionType.MARKET_EXISTS_FOR_COMPANY, Integer.valueOf(MARKET_EXISTS_UNAV));
        net.kroia.banksystem.integration.stockmarket.StockMarketBridge.MarketExists e =
                runOnServerThreadSync(() ->
                        net.kroia.banksystem.integration.stockmarket.StockMarketBridge.marketExistsFor(share));
        int code = switch (e == null ? net.kroia.banksystem.integration.stockmarket.StockMarketBridge.MarketExists.UNAVAILABLE : e) {
            case YES -> MARKET_EXISTS_YES;
            case NO  -> MARKET_EXISTS_NO;
            case UNAVAILABLE -> MARKET_EXISTS_UNAV;
        };
        return OutputData.of(FunctionType.MARKET_EXISTS_FOR_COMPANY, Integer.valueOf(code));
    }

    /**
     * Marshal a StockMarket API call onto the server thread. If we are already on it (or no
     * server is available) run inline. Blocks the ARRS worker briefly — SM ops are cheap.
     */
    private static <T> T runOnServerThreadSync(java.util.function.Supplier<T> s) {
        net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
        if (srv == null || Thread.currentThread() == srv.getRunningThread()) {
            try { return s.get(); } catch (Throwable t) { return null; }
        }
        try {
            return srv.submit(s::get).get();
        } catch (Throwable t) {
            return null;
        }
    }

    // Task #54 (v2.0.8) — build full Entry list from CompanyManager. Master-only.
    private static OutputData handleListAllVisuals(IServerBankManager bm, CompanyManager cm) {
        List<net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry> entries = new ArrayList<>();
        for (Company c : cm.getAll()) {
            List<String> founderNames = new ArrayList<>();
            for (UUID uuid : c.getFounders()) {
                User u = bm != null ? bm.getUserByUUID(uuid) : null;
                founderNames.add(u != null ? u.getName() : uuid.toString());
            }
            int holderCount = 0;
            if (bm != null) {
                Set<Integer> holders = new java.util.HashSet<>();
                for (Map.Entry<net.kroia.banksystem.util.ItemID, net.minecraft.world.item.ItemStack> e
                        : net.kroia.banksystem.util.ItemIDManager.getItemIDMap().entrySet()) {
                    Integer cid = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                            .getCompanyIdForItemID(e.getKey());
                    if (cid == null || cid != c.getCompanyId()) continue;
                    holders.addAll(bm.listAccountsHolding(e.getKey()));
                }
                holderCount = holders.size();
            }
            entries.add(net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry.of(
                    c.getCompanyId(), c.getShareVisuals(),
                    c.getTotalSharesIssued(), c.getMaxSupply(),
                    c.getName(),
                    c.getDescription() == null ? "" : c.getDescription(),
                    c.getBankAccountNr(),
                    founderNames,
                    holderCount));
        }
        return OutputData.of(FunctionType.LIST_ALL_COMPANY_VISUALS, new ListAllVisualsOutput(entries));
    }

    // Task #51 (v2.0.8) — read-only lookup of Share Stamper positions bound to a company.
    private static OutputData handleListStamperBindings(ListStamperBindingsInput in, CompanyManager cm) {
        List<BlockPos> positions = cm.listStampers(in.companyId);
        return OutputData.of(FunctionType.LIST_STAMPER_BINDINGS,
                new ListStamperBindingsOutput(positions));
    }

    /**
     * Spec §4.3 (v2.0.8) — MANAGE-gated unbind of the Share Stamper at {@code pos}
     * from {@code companyId}. Master-only side effects: clears the BE's bound
     * company id (marks dirty via {@code unbind()}, which also drops the reverse
     * index entry) and force-syncs the BE to nearby clients. World access is
     * marshalled onto the server thread (mirrors the SM bridge handlers).
     */
    private static OutputData handleUnbindStamper(UnbindStamperInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.UNBIND_STAMPER, new UnbindStamperOutput(gate));
        Boolean ok = runOnServerThreadSync(() -> {
            net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
            if (srv == null) return Boolean.FALSE;
            for (net.minecraft.server.level.ServerLevel level : srv.getAllLevels()) {
                if (!level.isLoaded(in.pos)) continue;
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(in.pos);
                if (be instanceof net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity stamper
                        && stamper.getBoundCompanyId() == in.companyId) {
                    stamper.unbind();
                    net.minecraft.world.level.block.state.BlockState st = level.getBlockState(in.pos);
                    level.sendBlockUpdated(in.pos, st, st, 3);
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        });
        if (!Boolean.TRUE.equals(ok)) {
            // Stamper missing / unloaded / already rebound — treat as stale list entry.
            // Defensive: drop any stale reverse-index entry so the next list refresh is clean.
            cm.unregisterStamper(in.companyId, in.pos);
            return OutputData.of(FunctionType.UNBIND_STAMPER, new UnbindStamperOutput(CODE_NOT_FOUND));
        }
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[ShareStamper] unbound stamper at " + in.pos
                    + " from company #" + in.companyId + " by " + in.callerUUID);
        }
        return OutputData.of(FunctionType.UNBIND_STAMPER, new UnbindStamperOutput(CODE_OK));
    }

    /** Spec §4.3 (v2.0.8) — slave helper: unbind the Share Stamper at pos from a company (MANAGE-gated). */
    public static CompletableFuture<UnbindStamperOutput> unbindStamperAsync(int companyId, BlockPos pos, UUID caller) {
        InputData input = InputData.of(FunctionType.UNBIND_STAMPER, new UnbindStamperInput(companyId, pos, caller));
        CompletableFuture<UnbindStamperOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    // Task #46 (v2.0.8) — MANAGE-gated visuals writeback + broadcast.
    private static OutputData handleUpdateShareVisuals(UpdateShareVisualsInput in,
                                                       IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.UPDATE_SHARE_VISUALS, new UpdateShareVisualsOutput(gate));
        // Preset id validation — reject arbitrary client-supplied ids. Empty allowed (no preset).
        String bgSym = in.bgSymbolId == null ? "" : in.bgSymbolId;
        if (!bgSym.isEmpty() && !isValidSymbolId(bgSym)) {
            return OutputData.of(FunctionType.UPDATE_SHARE_VISUALS, new UpdateShareVisualsOutput(CODE_INVALID_INPUT));
        }
        String fgSym = in.fgSymbolId == null ? "" : in.fgSymbolId;
        if (!fgSym.isEmpty() && !isValidSymbolId(fgSym)) {
            return OutputData.of(FunctionType.UPDATE_SHARE_VISUALS, new UpdateShareVisualsOutput(CODE_INVALID_INPUT));
        }
        // Length caps mirror the editor's client-side caps — defense in depth.
        String displayName = in.displayName == null ? "" : in.displayName;
        if (displayName.length() > 24) displayName = displayName.substring(0, 24);
        String description = in.description == null ? "" : in.description;
        if (description.length() > 120) description = description.substring(0, 120);

        net.kroia.banksystem.banking.company.ShareVisuals visuals =
                new net.kroia.banksystem.banking.company.ShareVisuals(
                        new net.kroia.banksystem.banking.company.ShareVisuals.ShareLayer(bgSym, in.bgTint),
                        new net.kroia.banksystem.banking.company.ShareVisuals.ShareLayer(fgSym, in.fgTint),
                        in.baseTint,
                        displayName, description);
        if (!cm.updateShareVisuals(in.companyId, visuals)) {
            return OutputData.of(FunctionType.UPDATE_SHARE_VISUALS, new UpdateShareVisualsOutput(CODE_NOT_FOUND));
        }
        Company company = cm.getById(in.companyId);
        long issued = company != null ? company.getTotalSharesIssued() : 0L;
        long max = company != null ? company.getMaxSupply() : 0L;
        net.minecraft.server.MinecraftServer server = dev.architectury.utils.GameInstance.getServer();
        if (server != null) {
            net.kroia.banksystem.networking.general.S2CCompanyVisualUpdatePacket
                    .broadcast(server, in.companyId, visuals, issued, max);
        }
        return OutputData.of(FunctionType.UPDATE_SHARE_VISUALS, new UpdateShareVisualsOutput(CODE_OK));
    }

    // ------------------------------------------------------------------
    // Task #45a — payout master handlers
    // ------------------------------------------------------------------
    private static int mapPayoutOp(IPayoutManager.OpResult r) {
        return switch (r) {
            case OK -> CODE_OK;
            case COMPANY_MISSING -> CODE_NOT_FOUND;
            case SCHEDULE_MISSING -> CODE_SCHEDULE_MISSING;
            case INVALID_INPUT -> CODE_INVALID_INPUT;
            case NOT_MASTER -> CODE_INTERNAL;
        };
    }

    /**
     * MANAGE gate for a mutation. Returns {@link #CODE_OK} on pass, or a specific error code
     * to short-circuit with. Non-mutating queries do not use this.
     */
    /** Symbol-id validation: use ShareSymbolStore when available, fall back to client registry. */
    private static boolean isValidSymbolId(String id) {
        ShareSymbolStore store = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.SHARE_SYMBOL_STORE : null;
        if (store != null) return store.isValidSymbolId(id);
        // Fallback while store is not yet initialized (test env / singleplayer first tick)
        return net.kroia.banksystem.client.company.SharePresetRegistry.isValidPresetId(id);
    }

    private static int gateManage(int companyId, UUID callerUUID, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getById(companyId);
        if (company == null) return CODE_NOT_FOUND;
        IServerBankAccount account = bm.getBankAccount(company.getBankAccountNr());
        boolean hasManage = account != null && account.hasPermission(callerUUID, BankPermission.MANAGE);
        boolean isAdmin = bm.isBanksystemAdmin(callerUUID);
        // v2.0.8 Bug2 root cause — visuals/tint save silently rejected for founders
        // lacking explicit MANAGE bit on the linked bank account. Founder rights imply
        // company-level authority; extend the gate to accept founder membership.
        boolean isFounder = company.isFounder(callerUUID);
        return (hasManage || isAdmin || isFounder) ? CODE_OK : CODE_NO_PERMISSION;
    }

    /** Spec A.9 — server-side snapshot resolution: player name from the user map, account name from the account. */
    private static String[] resolveTargetNames(@Nullable UUID target, int targetAccountNr, IServerBankManager bm) {
        String playerName = "";
        String accountName = "";
        if (target != null) {
            User u = bm.getUserByUUID(target);
            playerName = u != null ? u.getName() : target.toString();
            IServerBankAccount account = targetAccountNr != PayoutSchedule.NO_TARGET_ACCOUNT
                    ? bm.getBankAccount(targetAccountNr)
                    : bm.getPersonalBankAccount(target);
            if (account != null) accountName = account.getAccountName();
        }
        return new String[]{playerName, accountName};
    }

    private static PayoutSchedule.Mode modeFromByte(byte b) {
        return b >= 0 && b < PayoutSchedule.Mode.values().length
                ? PayoutSchedule.Mode.values()[b] : PayoutSchedule.Mode.FIXED_PAYOUT;
    }

    private static OutputData handleCreatePayout(CreatePayoutInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.CREATE_PAYOUT, new CreatePayoutOutput(gate, 0L));
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.CREATE_PAYOUT, new CreatePayoutOutput(CODE_INTERNAL, 0L));
        long nowTick = PayoutExecutor.getLastObservedTick() > 0L
                ? PayoutExecutor.getLastObservedTick() : in.nowTick;
        String[] names = resolveTargetNames(in.target, in.targetAccountNr, bm);
        IPayoutManager.CreateOutcome outcome = pm.createSchedule(in.companyId, in.target, in.amount,
                in.intervalTicks, nowTick, in.callerUUID, in.targetAccountNr, names[0], names[1],
                modeFromByte(in.mode), in.currencyItem);
        return OutputData.of(FunctionType.CREATE_PAYOUT,
                new CreatePayoutOutput(mapPayoutOp(outcome.result()), outcome.scheduleId()));
    }

    private static OutputData handleUpdatePayout(UpdatePayoutInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.UPDATE_PAYOUT, new UpdatePayoutOutput(gate));
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.UPDATE_PAYOUT, new UpdatePayoutOutput(CODE_INTERNAL));
        long nowTick = PayoutExecutor.getLastObservedTick() > 0L ? PayoutExecutor.getLastObservedTick() : 0L;
        String[] names = resolveTargetNames(in.newTarget, in.newTargetAccountNr, bm);
        return OutputData.of(FunctionType.UPDATE_PAYOUT,
                new UpdatePayoutOutput(mapPayoutOp(pm.updateScheduleEx(in.companyId, in.scheduleId,
                        in.newAmount, in.newIntervalTicks, nowTick, in.newTarget, in.newTargetAccountNr,
                        names[0], names[1], modeFromByte(in.newMode), in.newCurrencyItem))));
    }

    /** Spec B.1 — filtered account listing. MANAGE-gated when caller != subject. */
    private static OutputData handleListPlayerAccounts(ListPlayerAccountsInput in,
                                                       IServerBankManager bm, CompanyManager cm) {
        if (!in.callerUUID.equals(in.subject)) {
            int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
            if (gate != CODE_OK) {
                return OutputData.of(FunctionType.LIST_PLAYER_ACCOUNTS_WITH_FILTER,
                        new ListPlayerAccountsOutput(gate, List.of()));
            }
        }
        // Exclude the paying company's own bank account — a self-transfer
        // (Account1 -> Account1) is meaningless as a payout target.
        Company payer = cm.getById(in.companyId);
        int excludeAccountNr = payer != null ? payer.getBankAccountNr() : Integer.MIN_VALUE;
        List<AccountEntry> entries = new ArrayList<>();
        for (IServerBankAccount account : bm.getBankAccounts(in.subject)) {
            if (account.getAccountNumber() == excludeAccountNr) continue;
            int bits = account.getPermission(in.subject);
            if (in.filterMask != 0 && (bits & in.filterMask) == 0) continue;
            entries.add(new AccountEntry(account.getAccountNumber(), account.getAccountName(), bits));
        }
        // The subject's personal account is always a valid deposit target even without
        // an explicit user row — include it if the account walk missed it (and it is
        // not the payer's own account).
        IServerBankAccount personal = bm.getPersonalBankAccount(in.subject);
        if (personal != null && personal.getAccountNumber() != excludeAccountNr
                && entries.stream().noneMatch(e -> e.accountId() == personal.getAccountNumber())) {
            entries.add(new AccountEntry(personal.getAccountNumber(), personal.getAccountName(),
                    BankPermission.DEPOSIT.getValue() | BankPermission.WITHDRAW.getValue()
                            | BankPermission.MANAGE.getValue()));
        }
        return OutputData.of(FunctionType.LIST_PLAYER_ACCOUNTS_WITH_FILTER,
                new ListPlayerAccountsOutput(CODE_OK, entries));
    }

    /** Spec B.3 — non-zero item balances on the company's bank account (money excluded). */
    private static OutputData handleListItemBalances(ListItemBalancesInput in,
                                                     IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) {
            return OutputData.of(FunctionType.LIST_ACCOUNT_ITEM_BALANCES,
                    new ListItemBalancesOutput(gate, List.of()));
        }
        Company company = cm.getById(in.companyId);
        if (company == null) {
            return OutputData.of(FunctionType.LIST_ACCOUNT_ITEM_BALANCES,
                    new ListItemBalancesOutput(CODE_NOT_FOUND, List.of()));
        }
        IServerBankAccount account = bm.getBankAccount(company.getBankAccountNr());
        if (account == null) {
            return OutputData.of(FunctionType.LIST_ACCOUNT_ITEM_BALANCES,
                    new ListItemBalancesOutput(CODE_BANK_ACCOUNT_ERROR, List.of()));
        }
        net.kroia.banksystem.util.ItemID moneyId =
                net.kroia.banksystem.minecraft.item.custom.money.MoneyItem.getItemID();
        short moneyShort = moneyId != null ? moneyId.getShort() : 0;
        List<ItemBalanceEntry> items = new ArrayList<>();
        long moneyBalance = 0L;
        for (var e : account.getAllBanks().entrySet()) {
            short s = e.getKey().getShort();
            long balance = e.getValue().getBalance();
            if (s == moneyShort) { moneyBalance = balance; continue; }
            if (s == 0) continue;
            if (balance <= 0L) continue;
            items.add(new ItemBalanceEntry(s, balance));
        }
        return OutputData.of(FunctionType.LIST_ACCOUNT_ITEM_BALANCES,
                new ListItemBalancesOutput(CODE_OK, items, moneyBalance));
    }

    /** Spec B.4 — manual missed-payout catch-up. MANAGE-gated; runs the shared transfer path. */
    private static OutputData handlePayMissed(PayMissedInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.PAY_MISSED, new PayMissedOutput(gate, 0L, 0));
        Company company = cm.getById(in.companyId);
        if (company == null) return OutputData.of(FunctionType.PAY_MISSED, new PayMissedOutput(CODE_NOT_FOUND, 0L, 0));
        PayoutSchedule schedule = company.findSchedule(in.scheduleId);
        if (schedule == null) return OutputData.of(FunctionType.PAY_MISSED, new PayMissedOutput(CODE_SCHEDULE_MISSING, 0L, 0));
        if (in.amount <= 0L || in.amount > schedule.getMissedAmount()) {
            return OutputData.of(FunctionType.PAY_MISSED,
                    new PayMissedOutput(CODE_INVALID_INPUT, schedule.getMissedAmount(), schedule.getMissedCount()));
        }
        long nowMs = System.currentTimeMillis();
        List<net.kroia.banksystem.data.table.record.TransactionLogRecord> ledger = new ArrayList<>();
        PayoutExecutor.Outcome outcome = PayoutExecutor.executeOne(company, schedule, in.amount,
                bm, nowMs, ledger);
        PayoutHistoryRecord.Status status = outcome.reason == null
                ? PayoutHistoryRecord.Status.OK : outcome.reason.toStatus();
        // Catch-up attempts are audit-worthy either way — write a CATCH_UP history row.
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.PAYOUT_HISTORY_MANAGER != null) {
            BACKEND_INSTANCES.PAYOUT_HISTORY_MANAGER.save(PayoutHistoryRecord.of(
                    company.getCompanyId(), schedule.getScheduleId(), company.getBankAccountNr(),
                    schedule.getTargetUUID(), in.amount, nowMs, status,
                    outcome.targetPlayerName, outcome.targetAccountName,
                    schedule.getCurrencyItem(), PayoutHistoryRecord.Type.CATCH_UP));
        }
        if (outcome.reason != null) {
            int code = switch (outcome.reason) {
                case INSUFFICIENT_FUNDS -> CODE_INSUFFICIENT_FUNDS;
                case TARGET_NOT_FOUND -> CODE_MISSING_TARGET;
                case TARGET_NO_DEPOSIT_RIGHT -> CODE_TARGET_NO_DEPOSIT;
                case CURRENCY_ITEM_MISSING -> CODE_CURRENCY_ITEM_MISSING;
                default -> CODE_INTERNAL;
            };
            return OutputData.of(FunctionType.PAY_MISSED,
                    new PayMissedOutput(code, schedule.getMissedAmount(), schedule.getMissedCount()));
        }
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.TRANSACTION_LOG_MANAGER != null && !ledger.isEmpty()) {
            BACKEND_INSTANCES.TRANSACTION_LOG_MANAGER.save(ledger);
        }
        cm.applyCatchUpPayment(in.companyId, in.scheduleId, in.amount);
        PayoutSchedule updated = company.findSchedule(in.scheduleId);
        return OutputData.of(FunctionType.PAY_MISSED, new PayMissedOutput(CODE_OK,
                updated != null ? updated.getMissedAmount() : 0L,
                updated != null ? updated.getMissedCount() : 0));
    }

    private static OutputData handlePausePayout(PausePayoutInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.PAUSE_PAYOUT, new PausePayoutOutput(gate));
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.PAUSE_PAYOUT, new PausePayoutOutput(CODE_INTERNAL));
        return OutputData.of(FunctionType.PAUSE_PAYOUT,
                new PausePayoutOutput(mapPayoutOp(pm.pauseSchedule(in.companyId, in.scheduleId, in.paused))));
    }

    private static OutputData handleDeletePayout(DeletePayoutInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.DELETE_PAYOUT, new DeletePayoutOutput(gate));
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.DELETE_PAYOUT, new DeletePayoutOutput(CODE_INTERNAL));
        return OutputData.of(FunctionType.DELETE_PAYOUT,
                new DeletePayoutOutput(mapPayoutOp(pm.deleteSchedule(in.companyId, in.scheduleId))));
    }

    private static OutputData handleListSchedules(ListSchedulesInput in) {
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.LIST_SCHEDULES, ListSchedulesOutput.EMPTY);
        List<PayoutSchedule> list = pm.listSchedulesFor(in.companyId);
        List<ScheduleWire> wire = new ArrayList<>(list.size());
        for (PayoutSchedule s : list) wire.add(ScheduleWire.of(s));
        return OutputData.of(FunctionType.LIST_SCHEDULES,
                new ListSchedulesOutput(wire, PayoutExecutor.getLastObservedTick()));
    }

    private static OutputData handleGetHistory(GetHistoryInput in) {
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.GET_HISTORY, GetHistoryOutput.EMPTY);
        // Best-effort synchronous unwrap — history futures resolve on the DB worker.
        List<PayoutHistoryRecord> rows;
        long total;
        try {
            rows = pm.getHistory(in.scheduleId, in.limit).get();
            total = pm.getTotalPaidForSchedule(in.scheduleId).get();
        } catch (Exception e) {
            return OutputData.of(FunctionType.GET_HISTORY, GetHistoryOutput.EMPTY);
        }
        List<HistoryRowWire> wire = new ArrayList<>(rows.size());
        for (PayoutHistoryRecord r : rows) wire.add(HistoryRowWire.of(r));
        return OutputData.of(FunctionType.GET_HISTORY, new GetHistoryOutput(wire, total));
    }

    private static OutputData handleFailureCount24h(GetFailureCount24hInput in) {
        net.kroia.banksystem.data.table.PayoutHistoryManager hm =
                BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_HISTORY_MANAGER : null;
        if (hm == null) return OutputData.of(FunctionType.GET_FAILURE_COUNT_24H, new GetFailureCount24hOutput(0L));
        long since = System.currentTimeMillis() - 86_400_000L;
        long count;
        try {
            count = hm.countFailuresSinceForCompany(in.companyId, since).get();
        } catch (Exception e) {
            count = 0L;
        }
        return OutputData.of(FunctionType.GET_FAILURE_COUNT_24H, new GetFailureCount24hOutput(count));
    }

    // Task #46 (v2.0.8) — by-id share visuals lookup (read-only; no permission gate).
    private static OutputData handleGetShareVisuals(GetShareVisualsInput in, CompanyManager cm) {
        Company company = cm.getById(in.companyId);
        if (company == null)
            return OutputData.of(FunctionType.GET_SHARE_VISUALS, GetShareVisualsOutput.ABSENT);
        ShareVisuals v = company.getShareVisuals();
        if (v == null) v = ShareVisuals.EMPTY;
        return OutputData.of(FunctionType.GET_SHARE_VISUALS,
                new GetShareVisualsOutput(true,
                        v.getBgLayer().symbolId(),
                        v.getBgLayer().tint(),
                        v.getFgLayer().symbolId(),
                        v.getFgLayer().tint(),
                        v.getBaseTint(),
                        v.getDisplayName() == null ? "" : v.getDisplayName(),
                        v.getDescription() == null ? "" : v.getDescription(),
                        company.getTotalSharesIssued(),
                        company.getMaxSupply()));
    }

    private static OutputData handleInfoById(GetShareVisualsInput in, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getById(in.companyId());
        if (company == null) return OutputData.of(FunctionType.GET_COMPANY_INFO_BY_ID, CompanyInfoOutput.ABSENT);
        Set<UUID> founders = company.getFounders();
        List<String> founderNames = new ArrayList<>(founders.size());
        for (UUID uuid : founders) {
            User u = bm.getUserByUUID(uuid);
            founderNames.add(u != null ? u.getName() : uuid.toString());
        }
        return OutputData.of(FunctionType.GET_COMPANY_INFO_BY_ID,
                new CompanyInfoOutput(true, company.getCompanyId(), company.getName(),
                        company.getBankAccountNr(), company.getMaxSupply(),
                        company.getTotalSharesIssued(),
                        company.getDescription() == null ? "" : company.getDescription(),
                        founderNames, company.getCompanyCurrency()));
    }

    /** Task #52 (v2.0.8) — count of accounts holding a strictly-positive balance
     *  of the company's stamped-share ItemID. Read-only, no permission gate.
     *  Iterates the ItemID registry via {@code StampedShareItem.getCompanyIdForItemID}
     *  (reverse lookup) to find every share ItemID that belongs to this company,
     *  then unions {@code listAccountsHolding} results across all of them. Avoids
     *  the fragile forward "build a template stack, resolve ItemID by component
     *  equality" path — that path silently returns INVALID for stacks whose
     *  registered form carries extra default components. */
    private static OutputData handleCountHoldersForCompany(GetShareVisualsInput in, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getById(in.companyId());
        if (company == null) return OutputData.of(FunctionType.COUNT_HOLDERS_FOR_COMPANY, Integer.valueOf(0));

        java.util.Set<Integer> holders = new java.util.HashSet<>();
        for (java.util.Map.Entry<net.kroia.banksystem.util.ItemID, net.minecraft.world.item.ItemStack> e
                : net.kroia.banksystem.util.ItemIDManager.getItemIDMap().entrySet()) {
            Integer cid = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                    .getCompanyIdForItemID(e.getKey());
            if (cid == null || cid != in.companyId()) continue;
            holders.addAll(bm.listAccountsHolding(e.getKey()));
        }
        return OutputData.of(FunctionType.COUNT_HOLDERS_FOR_COMPANY, Integer.valueOf(holders.size()));
    }

    private static OutputData handleInfoByAccount(GetCompanyInfoByAccountInput in, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getByBankAccount(in.accountNr);
        if (company == null) return OutputData.of(FunctionType.GET_COMPANY_INFO_BY_ACCOUNT, CompanyInfoOutput.ABSENT);
        Set<UUID> founders = company.getFounders();
        List<String> founderNames = new ArrayList<>(founders.size());
        for (UUID uuid : founders) {
            User u = bm.getUserByUUID(uuid);
            founderNames.add(u != null ? u.getName() : uuid.toString());
        }
        return OutputData.of(FunctionType.GET_COMPANY_INFO_BY_ACCOUNT,
                new CompanyInfoOutput(true, company.getCompanyId(), company.getName(),
                        company.getBankAccountNr(), company.getMaxSupply(),
                        company.getTotalSharesIssued(),
                        company.getDescription() == null ? "" : company.getDescription(),
                        founderNames, company.getCompanyCurrency()));
    }

    // ------------------------------------------------------------------
    // Slave-side convenience helpers
    // ------------------------------------------------------------------
    public static CompletableFuture<CreateOutput> createCompanyAsync(String name, long maxSupply, UUID caller, String callerName) {
        InputData input = InputData.of(FunctionType.CREATE_COMPANY, new CreateInput(name, maxSupply, caller, callerName));
        CompletableFuture<CreateOutput> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> future.complete(out == null ? null : out.decodeResult()));
        return future;
    }

    public static CompletableFuture<TransferOutput> transferFounderAsync(String companyName, UUID caller, String targetName) {
        InputData input = InputData.of(FunctionType.TRANSFER_FOUNDER, new TransferInput(companyName, caller, targetName));
        CompletableFuture<TransferOutput> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> future.complete(out == null ? null : out.decodeResult()));
        return future;
    }

    public static CompletableFuture<DissolveOutput> dissolveCompanyAsync(String companyName, UUID caller) {
        InputData input = InputData.of(FunctionType.DISSOLVE_COMPANY, new DissolveInput(companyName, caller));
        CompletableFuture<DissolveOutput> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> future.complete(out == null ? null : out.decodeResult()));
        return future;
    }

    public static CompletableFuture<DescriptionOutput> updateDescriptionAsync(String companyName, UUID caller, String text) {
        InputData input = InputData.of(FunctionType.UPDATE_DESCRIPTION, new DescriptionInput(companyName, caller, text));
        CompletableFuture<DescriptionOutput> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> future.complete(out == null ? null : out.decodeResult()));
        return future;
    }

    public static CompletableFuture<CompanyInfoOutput> getCompanyInfoAsync(String companyName) {
        InputData input = InputData.of(FunctionType.GET_COMPANY_INFO, companyName);
        CompletableFuture<CompanyInfoOutput> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> future.complete(out == null ? null : out.decodeResult()));
        return future;
    }

    /** Task #51 fix (v2.0.8) — full CompanyInfoOutput lookup by companyId. */
    public static CompletableFuture<CompanyInfoOutput> getCompanyInfoByIdAsync(int companyId) {
        InputData input = InputData.of(FunctionType.GET_COMPANY_INFO_BY_ID, new GetShareVisualsInput(companyId));
        CompletableFuture<CompanyInfoOutput> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> future.complete(out == null ? null : out.decodeResult()));
        return future;
    }

    public static CompletableFuture<Boolean> isNameTakenAsync(String name) {
        InputData input = InputData.of(FunctionType.IS_NAME_TAKEN, name);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> future.complete(out == null ? null : out.decodeResult()));
        return future;
    }

    // ------------------------------------------------------------------
    // Task #45a — payout slave-side helpers
    // ------------------------------------------------------------------
    public static CompletableFuture<CreatePayoutOutput> createPayoutAsync(int companyId, @Nullable UUID target, long amount,
                                                                         long intervalTicks, long nowTick, UUID caller,
                                                                         int targetAccountNr, byte mode, short currencyItem) {
        InputData input = InputData.of(FunctionType.CREATE_PAYOUT,
                new CreatePayoutInput(companyId, target, amount, intervalTicks, nowTick, caller,
                        targetAccountNr, mode, currencyItem));
        CompletableFuture<CreatePayoutOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Legacy-shape helper — personal-account money payout, FIXED mode. */
    public static CompletableFuture<CreatePayoutOutput> createPayoutAsync(int companyId, UUID target, long amount,
                                                                         long intervalTicks, long nowTick, UUID caller) {
        return createPayoutAsync(companyId, target, amount, intervalTicks, nowTick, caller,
                PayoutSchedule.NO_TARGET_ACCOUNT, (byte) PayoutSchedule.Mode.FIXED_PAYOUT.ordinal(),
                PayoutSchedule.MONEY_CURRENCY);
    }

    public static CompletableFuture<UpdatePayoutOutput> updatePayoutAsync(int companyId, long scheduleId,
                                                                         long newAmount, long newIntervalTicks, UUID caller,
                                                                         @Nullable UUID newTarget, int newTargetAccountNr,
                                                                         byte newMode, short newCurrencyItem) {
        InputData input = InputData.of(FunctionType.UPDATE_PAYOUT,
                new UpdatePayoutInput(companyId, scheduleId, newAmount, newIntervalTicks, caller,
                        newTarget, newTargetAccountNr, newMode, newCurrencyItem));
        CompletableFuture<UpdatePayoutOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Spec B.1 — slave helper: list a player's accounts filtered by permission mask. */
    public static CompletableFuture<ListPlayerAccountsOutput> listPlayerAccountsWithFilterAsync(
            int companyId, UUID subject, byte filterMask, UUID caller) {
        InputData input = InputData.of(FunctionType.LIST_PLAYER_ACCOUNTS_WITH_FILTER,
                new ListPlayerAccountsInput(companyId, subject, filterMask, caller));
        CompletableFuture<ListPlayerAccountsOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Spec B.3 — slave helper: list non-zero item balances on the company account. */
    public static CompletableFuture<ListItemBalancesOutput> listAccountItemBalancesAsync(
            int companyId, UUID caller) {
        InputData input = InputData.of(FunctionType.LIST_ACCOUNT_ITEM_BALANCES,
                new ListItemBalancesInput(companyId, caller));
        CompletableFuture<ListItemBalancesOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Spec B.4 — slave helper: pay (part of) a schedule's missed amount now. */
    public static CompletableFuture<PayMissedOutput> payMissedAsync(int companyId, long scheduleId,
                                                                    long amount, UUID caller) {
        InputData input = InputData.of(FunctionType.PAY_MISSED,
                new PayMissedInput(companyId, scheduleId, amount, caller));
        CompletableFuture<PayMissedOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    public static CompletableFuture<PausePayoutOutput> pausePayoutAsync(int companyId, long scheduleId,
                                                                       boolean paused, UUID caller) {
        InputData input = InputData.of(FunctionType.PAUSE_PAYOUT,
                new PausePayoutInput(companyId, scheduleId, paused, caller));
        CompletableFuture<PausePayoutOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    public static CompletableFuture<DeletePayoutOutput> deletePayoutAsync(int companyId, long scheduleId, UUID caller) {
        InputData input = InputData.of(FunctionType.DELETE_PAYOUT,
                new DeletePayoutInput(companyId, scheduleId, caller));
        CompletableFuture<DeletePayoutOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    public static CompletableFuture<ListSchedulesOutput> listSchedulesAsync(int companyId) {
        InputData input = InputData.of(FunctionType.LIST_SCHEDULES, new ListSchedulesInput(companyId));
        CompletableFuture<ListSchedulesOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    public static CompletableFuture<GetHistoryOutput> getHistoryAsync(long scheduleId, int limit) {
        InputData input = InputData.of(FunctionType.GET_HISTORY, new GetHistoryInput(scheduleId, limit));
        CompletableFuture<GetHistoryOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    public static CompletableFuture<GetFailureCount24hOutput> getFailureCount24hAsync(int companyId) {
        InputData input = InputData.of(FunctionType.GET_FAILURE_COUNT_24H, new GetFailureCount24hInput(companyId));
        CompletableFuture<GetFailureCount24hOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    public static CompletableFuture<CompanyInfoOutput> getCompanyInfoByAccountAsync(int accountNr) {
        InputData input = InputData.of(FunctionType.GET_COMPANY_INFO_BY_ACCOUNT, new GetCompanyInfoByAccountInput(accountNr));
        CompletableFuture<CompanyInfoOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** v2.0.9 — slave helper: forward two-layer share-visuals writeback to master. */
    public static CompletableFuture<UpdateShareVisualsOutput> updateShareVisualsAsync(int companyId,
                                                                                     String bgSymbolId, int bgTint,
                                                                                     String fgSymbolId, int fgTint,
                                                                                     int baseTint,
                                                                                     String displayName, String description,
                                                                                     UUID caller) {
        InputData input = InputData.of(FunctionType.UPDATE_SHARE_VISUALS,
                new UpdateShareVisualsInput(companyId, bgSymbolId, bgTint, fgSymbolId, fgTint, baseTint, displayName, description, caller));
        CompletableFuture<UpdateShareVisualsOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Task #46 (v2.0.8) — slave helper: fetch share visuals + supply for a companyId. */
    public static CompletableFuture<GetShareVisualsOutput> getShareVisualsAsync(int companyId) {
        InputData input = InputData.of(FunctionType.GET_SHARE_VISUALS, new GetShareVisualsInput(companyId));
        CompletableFuture<GetShareVisualsOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Task #49 (v2.0.8) — MANAGE-gated one-shot dividend distribution. Master-only side effects. */
    private static OutputData handlePayDividend(PayDividendInput in, IServerBankManager bm, CompanyManager cm) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[AsyncCompanyManager] PAY_DIVIDEND received: company="
                    + in.companyId + " amountPerShareRaw=" + in.amountPerShare
                    + " caller=" + in.callerUUID);
        }
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.PAY_DIVIDEND, new PayDividendOutput(gate, 0L, 0));
        net.kroia.banksystem.api.dividend.IDividendPayer payer =
                BACKEND_INSTANCES != null ? BACKEND_INSTANCES.DIVIDEND_PAYER : null;
        if (payer == null) return OutputData.of(FunctionType.PAY_DIVIDEND, new PayDividendOutput(CODE_INTERNAL, 0L, 0));
        net.kroia.banksystem.api.PayDividendResult result =
                payer.payDividend(in.companyId, in.amountPerShare, in.includeCompanyAccount, in.callerUUID,
                        in.currencyItem);
        int code = switch (result.reason()) {
            case OK -> CODE_OK;
            case NOT_MASTER, INTERNAL -> CODE_INTERNAL;
            case COMPANY_MISSING -> CODE_NOT_FOUND;
            case INVALID_INPUT -> CODE_INVALID_INPUT;
            case NO_SHARES -> CODE_NO_SHARES;
            case INSUFFICIENT_FUNDS, CURRENCY_ITEM_MISSING -> CODE_INSUFFICIENT_FUNDS;
            case NO_PERMISSION -> CODE_NO_PERMISSION;
        };
        return OutputData.of(FunctionType.PAY_DIVIDEND,
                new PayDividendOutput(code, result.totalPaid(), result.holderCount()));
    }

    /** Task #49 (v2.0.8) — slave helper: forward a dividend distribution request to master. */
    public static CompletableFuture<PayDividendOutput> payDividendAsync(int companyId, long amountPerShare,
                                                                       boolean includeCompanyAccount, UUID caller,
                                                                       short currencyItem) {
        InputData input = InputData.of(FunctionType.PAY_DIVIDEND,
                new PayDividendInput(companyId, amountPerShare, includeCompanyAccount, caller, currencyItem));
        CompletableFuture<PayDividendOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Task #49 (v2.0.8) — slave helper: pay dividend with money (default currency). */
    public static CompletableFuture<PayDividendOutput> payDividendAsync(int companyId, long amountPerShare,
                                                                       boolean includeCompanyAccount, UUID caller) {
        return payDividendAsync(companyId, amountPerShare, includeCompanyAccount, caller,
                net.kroia.banksystem.banking.company.PayoutSchedule.MONEY_CURRENCY);
    }

    /** Task #51 (v2.0.8) — slave helper: fetch Share Stamper positions bound to a company. */
    public static CompletableFuture<ListStamperBindingsOutput> listStamperBindingsAsync(int companyId) {
        InputData input = InputData.of(FunctionType.LIST_STAMPER_BINDINGS, new ListStamperBindingsInput(companyId));
        CompletableFuture<ListStamperBindingsOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Task #52 (v2.0.8) — slave helper: fetch the count of accounts holding a company's shares. */
    public static CompletableFuture<Integer> countHoldersForCompanyAsync(int companyId) {
        InputData input = InputData.of(FunctionType.COUNT_HOLDERS_FOR_COMPANY, new GetShareVisualsInput(companyId));
        CompletableFuture<Integer> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> {
            Integer v = o == null ? null : o.decodeResult();
            f.complete(v == null ? 0 : v);
        });
        return f;
    }

    /** Task #54 (v2.0.8) — slave helper: fetch every company's visuals+info in one shot for the mirror. */
    public static CompletableFuture<ListAllVisualsOutput> listAllCompanyVisualsAsync() {
        InputData input = InputData.of(FunctionType.LIST_ALL_COMPANY_VISUALS, new EmptyInput());
        CompletableFuture<ListAllVisualsOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> {
            ListAllVisualsOutput v = out == null ? null : out.decodeResult();
            f.complete(v == null ? ListAllVisualsOutput.EMPTY : v);
        });
        return f;
    }

    /**
     * Task #1 (v2.0.8) — dispatch an ARRS input from wherever the caller sits.
     * <p>
     * {@code sendRequestToMaster} is a <b>slave-server → master</b> hop only: it
     * allocates a server-side byte buf (null on a dedicated-server client JVM →
     * "Can't create byte buf … Is the server running?") and sends via
     * {@code MultiServerManager.sendToMaster}, which silently drops the packet
     * unless this JVM is a running MSM <i>slave</i>. So route per topology:
     * <ul>
     *   <li><b>Physical client, no local server</b> (dedicated server connection) —
     *       standard C2S ARRS hop via {@code sendRequestToServer}; the game server
     *       handles it as master, or ARRS forwards it to the bank master when that
     *       server is an MSM slave ({@code needsRoutingToMaster()}).</li>
     *   <li><b>MSM slave server</b> — S2S hop via {@code sendRequestToMaster}.</li>
     *   <li><b>This JVM is the master</b> (dedicated master server code, or
     *       singleplayer integrated server) — invoke the handler locally,
     *       marshalled onto the server thread.</li>
     * </ul>
     */
    private static CompletableFuture<OutputData> dispatchInput(InputData input) {
        net.minecraft.server.MinecraftServer server = dev.architectury.utils.GameInstance.getServer();
        if (server == null) {
            // Physical client connected to a dedicated server — C2S over the game
            // connection (same path AsyncBankManager uses from the client side).
            return Request.instance.sendRequestToServer(input);
        }
        if (net.kroia.modutilities.networking.multi_server.MultiServerManager.isRunning()
                && !net.kroia.modutilities.networking.multi_server.MultiServerManager.isMaster()) {
            // True slave server — standard S2S request hop to the bank master.
            return Request.instance.sendRequestToMaster(input);
        }
        // Local master (dedicated master or singleplayer integrated server) — the MSM
        // sendToMaster path would drop the packet, so short-circuit to the handler on
        // the server thread.
        CompletableFuture<OutputData> out = new CompletableFuture<>();
        server.execute(() -> {
            try {
                Request.instance.handleOnMasterServer(input, "", null)
                        .whenComplete((res, err) -> out.complete(err != null ? null : res));
            } catch (Throwable t) {
                out.complete(null);
            }
        });
        return out;
    }

    /** Task #1 (v2.0.8) — slave helper: request market creation. */
    public static CompletableFuture<OpenShareMarketOutput> openShareMarketAsync(int companyId, float initialPrice, UUID caller) {
        InputData input = InputData.of(FunctionType.OPEN_SHARE_MARKET,
                new OpenShareMarketInput(companyId, initialPrice, caller));
        CompletableFuture<OpenShareMarketOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Task #1 (v2.0.8) — slave helper: request market close (founder-gated). */
    public static CompletableFuture<CloseShareMarketOutput> closeShareMarketAsync(int companyId, UUID caller) {
        InputData input = InputData.of(FunctionType.CLOSE_SHARE_MARKET,
                new CloseShareMarketInput(companyId, caller));
        CompletableFuture<CloseShareMarketOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Task #1 (v2.0.8) — slave helper: probe whether SM has a market for this company's share. */
    public static CompletableFuture<Integer> marketExistsForCompanyAsync(int companyId) {
        InputData input = InputData.of(FunctionType.MARKET_EXISTS_FOR_COMPANY, new GetShareVisualsInput(companyId));
        CompletableFuture<Integer> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> {
            Integer v = o == null ? null : o.decodeResult();
            f.complete(v == null ? MARKET_EXISTS_UNAV : v);
        });
        return f;
    }

    /** Task #1 (v2.0.8) — slave helper: pause / resume trading on the company's share market (MANAGE-gated). */
    public static CompletableFuture<SetMarketOpenOutput> setMarketOpenAsync(int companyId, boolean open, UUID caller) {
        InputData input = InputData.of(FunctionType.SET_MARKET_OPEN, new SetMarketOpenInput(companyId, open, caller));
        CompletableFuture<SetMarketOpenOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> f.complete(o == null ? null : o.decodeResult()));
        return f;
    }

    /** Task #1 (v2.0.8) — slave helper: query whether the company's share market is currently open for trading. */
    public static CompletableFuture<Integer> isMarketOpenAsync(int companyId) {
        InputData input = InputData.of(FunctionType.IS_MARKET_OPEN, new GetShareVisualsInput(companyId));
        CompletableFuture<Integer> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> {
            Integer v = o == null ? null : o.decodeResult();
            f.complete(v == null ? MARKET_OPEN_UNAV : v);
        });
        return f;
    }

    /** Task #43h — fetch company names visible to the caller under a rights filter. */
    public static CompletableFuture<List<String>> listCompanyNamesForCallerAsync(UUID caller, byte filterKind) {
        InputData input = InputData.of(FunctionType.LIST_COMPANIES_FOR_CALLER, new ListInput(caller, filterKind));
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        dispatchInput(input).thenAccept(out -> {
            ListOutput result = out == null ? null : out.decodeResult();
            future.complete(result == null ? List.of() : result.companyNames());
        });
        return future;
    }

    // ------------------------------------------------------------------
    // Statistics tab (v2.0.9)
    // ------------------------------------------------------------------

    /** Task #52 (v2.0.8) — master handler: read dividend history from the SQLite store. */
    private static OutputData handleListDividendHistory(ListDividendHistoryInput in) {
        net.kroia.banksystem.banking.company.DividendHistoryStore store =
                BACKEND_INSTANCES != null ? BACKEND_INSTANCES.DIVIDEND_HISTORY_STORE : null;
        if (store == null) return OutputData.of(FunctionType.LIST_DIVIDEND_HISTORY, ListDividendHistoryOutput.EMPTY);
        List<net.kroia.banksystem.banking.company.DividendEvent> events =
                store.listByCompany(in.companyId, Math.min(in.limit, 100));
        List<DividendEventWire> wires = new ArrayList<>(events.size());
        for (var e : events) wires.add(DividendEventWire.of(e));
        return OutputData.of(FunctionType.LIST_DIVIDEND_HISTORY, new ListDividendHistoryOutput(wires));
    }

    private static OutputData handleSetCompanyCurrency(SetCompanyCurrencyInput in, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getById(in.companyId);
        if (company == null)
            return OutputData.of(FunctionType.SET_COMPANY_CURRENCY, new SetCompanyCurrencyOutput(CODE_NOT_FOUND));
        IServerBankAccount account = bm.getBankAccount(company.getBankAccountNr());
        boolean hasManage = account != null && account.hasPermission(in.callerUUID, BankPermission.MANAGE);
        boolean isAdmin = bm.isBanksystemAdmin(in.callerUUID);
        if (!hasManage && !isAdmin)
            return OutputData.of(FunctionType.SET_COMPANY_CURRENCY, new SetCompanyCurrencyOutput(CODE_NO_PERMISSION));
        boolean ok = cm.updateCompanyCurrency(in.companyId, in.currency);
        return OutputData.of(FunctionType.SET_COMPANY_CURRENCY, new SetCompanyCurrencyOutput(ok ? CODE_OK : CODE_INTERNAL));
    }

    private static OutputData handleGetCompanyStats(GetCompanyStatsInput in, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getById(in.companyId);
        if (company == null) return OutputData.of(FunctionType.GET_COMPANY_STATS, CompanyStatsPayload.EMPTY);

        long nowMs = System.currentTimeMillis();
        long fromMs = switch (in.timeframeIndex) {
            case 0  -> nowMs - 86_400_000L;
            case 1  -> nowMs - 7L * 86_400_000L;
            case 2  -> nowMs - 30L * 86_400_000L;
            case 3  -> nowMs - 90L * 86_400_000L;
            default -> 0L;
        };
        long bucketMs = in.timeframeIndex == 0 ? 3_600_000L
                : in.timeframeIndex <= 2      ? 86_400_000L
                : 7L * 86_400_000L;

        // Current balance — use company currency (default: money).
        long currentBalance = 0L;
        short filterItemIdShort = 0;
        IServerBankAccount companyAccount = bm.getBankAccount(company.getBankAccountNr());
        if (companyAccount != null) {
            net.kroia.banksystem.util.ItemID currencyId;
            short companyCurrencyShort = company.getCompanyCurrency();
            if (companyCurrencyShort == PayoutSchedule.MONEY_CURRENCY) {
                currencyId = net.kroia.banksystem.minecraft.item.custom.money.MoneyItem.getItemID();
            } else {
                currencyId = new net.kroia.banksystem.util.ItemID(companyCurrencyShort);
            }
            if (currencyId != null) {
                filterItemIdShort = currencyId.getShort();
                net.kroia.banksystem.api.bank.IServerBank bank = companyAccount.getBank(currencyId);
                if (bank != null) currentBalance = bank.getBalance();
            }
        }

        // Missed payout totals from Company's schedule list
        int missedPayoutCount = 0;
        long missedPayoutAmount = 0L;
        for (PayoutSchedule s : company.getPayoutSchedules()) {
            if (s.getMissedCount() > 0) {
                missedPayoutCount += s.getMissedCount();
                missedPayoutAmount += s.getMissedAmount();
            }
        }

        // DB-backed metrics
        net.kroia.banksystem.data.DatabaseManager dbm =
                BACKEND_INSTANCES != null ? BACKEND_INSTANCES.DATABASE_MANAGER : null;
        List<CashflowBucketWire> buckets = new ArrayList<>();
        long totalEarnings = 0L, totalSpendings = 0L;
        long daysToInsolvency = -1L;
        if (dbm != null) {
            try {
                java.sql.Connection conn = dbm.getConnection();
                int acctNr = company.getBankAccountNr();
                List<CompanyStatsQuery.CashflowBucket> raw =
                        CompanyStatsQuery.getCashflowSeries(conn, acctNr, filterItemIdShort, fromMs, nowMs, bucketMs);
                for (CompanyStatsQuery.CashflowBucket b : raw)
                    buckets.add(new CashflowBucketWire(b.bucketStart(), b.earnings(), b.spendings()));
                CompanyStatsQuery.CompanyHeadlineMetrics metrics =
                        CompanyStatsQuery.getHeadlineMetrics(conn, acctNr, filterItemIdShort, fromMs);
                totalEarnings = metrics.totalEarnings();
                totalSpendings = metrics.totalSpendings();
                daysToInsolvency = CompanyStatsQuery.getDaysToInsolvency(conn, acctNr, company, currentBalance);
            } catch (Exception e) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.warn("[CompanyStats] DB query failed: " + e);
            }
        } else {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.warn("[CompanyStats] dbm is null — no DB-backed metrics");
        }

        long netCashflow = totalEarnings - totalSpendings;

        // Top shareholders
        List<ShareholderWire> holders = new ArrayList<>();
        try {
            List<CompanyStatsQuery.ShareholderEntry> sh =
                    CompanyStatsQuery.getTopShareholders(bm, in.companyId, company.getTotalSharesIssued(), 10);
            for (CompanyStatsQuery.ShareholderEntry e : sh)
                holders.add(new ShareholderWire(e.accountNr(), e.accountName(), e.shares(), e.pct()));
        } catch (Exception e) { /* fail-open */ }

        return OutputData.of(FunctionType.GET_COMPANY_STATS,
                new CompanyStatsPayload(totalEarnings, totalSpendings, netCashflow,
                        missedPayoutCount, missedPayoutAmount,
                        currentBalance, daysToInsolvency, buckets, holders));
    }

    /** v2.0.9 — slave helper: set the company's default payout currency (MANAGE-gated on master). */
    public static CompletableFuture<SetCompanyCurrencyOutput> setCompanyCurrencyAsync(int companyId, short currency, UUID caller) {
        InputData input = InputData.of(FunctionType.SET_COMPANY_CURRENCY,
                new SetCompanyCurrencyInput(companyId, currency, caller));
        CompletableFuture<SetCompanyCurrencyOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> {
            SetCompanyCurrencyOutput v = o == null ? null : o.decodeResult();
            f.complete(v == null ? new SetCompanyCurrencyOutput(CODE_INTERNAL) : v);
        });
        return f;
    }

    /** v2.0.9 — slave helper: fetch company statistics for the Statistics tab. */
    public static CompletableFuture<CompanyStatsPayload> getCompanyStatsAsync(int companyId, int timeframeIndex) {
        InputData input = InputData.of(FunctionType.GET_COMPANY_STATS,
                new GetCompanyStatsInput(companyId, timeframeIndex));
        CompletableFuture<CompanyStatsPayload> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> {
            CompanyStatsPayload v = o == null ? null : o.decodeResult();
            f.complete(v == null ? CompanyStatsPayload.EMPTY : v);
        });
        return f;
    }

    /** Task #52 (v2.0.8) — slave helper: fetch dividend history for a company. */
    public static CompletableFuture<List<DividendEvent>> listDividendHistoryAsync(int companyId, int limit) {
        InputData input = InputData.of(FunctionType.LIST_DIVIDEND_HISTORY,
                new ListDividendHistoryInput(companyId, limit));
        CompletableFuture<List<DividendEvent>> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> {
            ListDividendHistoryOutput out = o == null ? null : o.decodeResult();
            if (out == null || out.events().isEmpty()) { f.complete(List.of()); return; }
            List<DividendEvent> events = new ArrayList<>(out.events().size());
            for (DividendEventWire w : out.events()) events.add(w.toEvent());
            f.complete(events);
        });
        return f;
    }

    // ------------------------------------------------------------------
    // Task #54 (v2.0.9) — symbol store ARRS handlers + helpers
    // ------------------------------------------------------------------

    private static OutputData handleGetSymbolManifest() {
        ShareSymbolStore store = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.SHARE_SYMBOL_STORE : null;
        if (store == null) return OutputData.of(FunctionType.GET_SYMBOL_MANIFEST, SymbolManifestOutput.EMPTY);
        return OutputData.of(FunctionType.GET_SYMBOL_MANIFEST,
                new SymbolManifestOutput(store.getRevision(), store.getEntries()));
    }

    private static OutputData handlePullSymbolBytes(PullSymbolBytesInput in, String slaveID) {
        if (in == null || slaveID == null || slaveID.isEmpty())
            return OutputData.of(FunctionType.PULL_SYMBOL_BYTES, new EmptyInput());
        ShareSymbolStore store = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.SHARE_SYMBOL_STORE : null;
        if (store == null) return OutputData.of(FunctionType.PULL_SYMBOL_BYTES, new EmptyInput());
        for (byte[] requestedHash : in.hashes()) {
            for (ShareSymbolStore.SymbolEntry e : store.getEntries()) {
                if (java.util.Arrays.equals(e.sha256(), requestedHash)) {
                    byte[] bytes = store.getSymbolBytes(e.id());
                    if (bytes != null) {
                        net.kroia.banksystem.networking.multi_server.S2SShareSymbolDataPacket
                                .sendChunksToSlave(slaveID, e.sha256(), bytes);
                    }
                    break;
                }
            }
        }
        return OutputData.of(FunctionType.PULL_SYMBOL_BYTES, new EmptyInput());
    }

    /**
     * Task #54 (v2.0.9) — slave helper: fetch the current share symbol manifest from master on boot.
     * On success, caller should call {@link ShareSymbolStore#mirrorApplyManifest} and then
     * {@link #pullSymbolBytesAsync} for any missing entries.
     */
    public static CompletableFuture<SymbolManifestOutput> getSymbolManifestAsync() {
        InputData input = InputData.of(FunctionType.GET_SYMBOL_MANIFEST, new EmptyInput());
        CompletableFuture<SymbolManifestOutput> f = new CompletableFuture<>();
        dispatchInput(input).thenAccept(o -> {
            SymbolManifestOutput v = o == null ? null : o.decodeResult();
            f.complete(v == null ? SymbolManifestOutput.EMPTY : v);
        });
        return f;
    }

    /**
     * Task #54 (v2.0.9) — slave helper: request master to push PNG bytes for the given SHA-256 hashes.
     * The bytes arrive as {@link net.kroia.banksystem.networking.multi_server.S2SShareSymbolDataPacket}
     * chunks pushed directly to this slave. Fire-and-forget; no result needed.
     */
    public static void pullSymbolBytesAsync(List<byte[]> hashes) {
        if (hashes == null || hashes.isEmpty()) return;
        InputData input = InputData.of(FunctionType.PULL_SYMBOL_BYTES, new PullSymbolBytesInput(hashes));
        dispatchInput(input); // fire and forget
    }
}
