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
        GET_FAILURE_COUNT_24H
    }

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
                                    List<String> founderNames) {
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
                    return new CompanyInfoOutput(present, id, name, accNr, ms, tsi, desc, founders);
                }
        );
        public static final CompanyInfoOutput ABSENT =
                new CompanyInfoOutput(false, 0, "", 0, 0L, 0L, "", List.of());
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
    public record CreatePayoutInput(int companyId, UUID target, long amount, long intervalTicks,
                                    long nowTick, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CreatePayoutInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                UUIDUtil.STREAM_CODEC, p -> p.target,
                ByteBufCodecs.VAR_LONG, p -> p.amount,
                ByteBufCodecs.VAR_LONG, p -> p.intervalTicks,
                ByteBufCodecs.VAR_LONG, p -> p.nowTick,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                CreatePayoutInput::new);
    }
    public record CreatePayoutOutput(int resultCode, long scheduleId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CreatePayoutOutput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.resultCode,
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                CreatePayoutOutput::new);
    }

    public record UpdatePayoutInput(int companyId, long scheduleId, long newAmount,
                                    long newIntervalTicks, UUID callerUUID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePayoutInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.companyId,
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                ByteBufCodecs.VAR_LONG, p -> p.newAmount,
                ByteBufCodecs.VAR_LONG, p -> p.newIntervalTicks,
                UUIDUtil.STREAM_CODEC, p -> p.callerUUID,
                UpdatePayoutInput::new);
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
    /** Wire form of a {@link PayoutSchedule}. */
    public record ScheduleWire(long scheduleId, @Nullable UUID targetUUID, long amount, long intervalTicks,
                               long nextRunTick, boolean paused, long createdAt, @Nullable UUID createdBy) {
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
            return new ScheduleWire(id, target, amount, interval, next, paused, createdAt, createdBy);
        }
        public static ScheduleWire of(PayoutSchedule s) {
            return new ScheduleWire(s.getScheduleId(), s.getTargetUUID(), s.getAmount(),
                    s.getIntervalTicks(), s.getNextRunTick(), s.isPaused(), s.getCreatedAt(), s.getCreatedBy());
        }
        public PayoutSchedule toSchedule() {
            return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks, nextRunTick,
                    paused, createdAt, createdBy);
        }
    }
    public record ListSchedulesOutput(List<ScheduleWire> schedules) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListSchedulesOutput> STREAM_CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.schedules.size());
                    for (ScheduleWire s : v.schedules) ScheduleWire.write(buf, s);
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<ScheduleWire> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(ScheduleWire.read(buf));
                    return new ListSchedulesOutput(out);
                });
        public static final ListSchedulesOutput EMPTY = new ListSchedulesOutput(List.of());
    }

    public record GetHistoryInput(long scheduleId, int limit) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetHistoryInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, p -> p.scheduleId,
                ByteBufCodecs.VAR_INT, p -> p.limit,
                GetHistoryInput::new);
    }
    /** Wire form of a {@link PayoutHistoryRecord}. */
    public record HistoryRowWire(long id, int companyId, long scheduleId, int sourceAccount,
                                 @Nullable UUID targetUuid, long amount, long time, int statusOrdinal) {
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
            return new HistoryRowWire(id, cid, sid, src, target, amount, time, status);
        }
        public static HistoryRowWire of(PayoutHistoryRecord r) {
            return new HistoryRowWire(r.id(), r.companyId(), r.scheduleId(), r.sourceAccount(),
                    r.targetUuid(), r.amount(), r.time(), r.status().ordinal());
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

    public record GetCompanyInfoByAccountInput(int accountNr) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GetCompanyInfoByAccountInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, p -> p.accountNr,
                GetCompanyInfoByAccountInput::new);
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
            });
        }

        @Override
        protected boolean isAllowedToCallByClient(InputData input) {
            // Never allow raw client calls — all /company commands go through the server-side
            // command handler which then forwards on slave. Reads still hop via that server.
            return false;
        }

        @Override
        protected boolean isAllowedToCallByUntrustedSlaveServer(InputData input) {
            return switch (input.function) {
                case GET_COMPANY_INFO, IS_NAME_TAKEN, LIST_COMPANIES_FOR_CALLER,
                     LIST_SCHEDULES, GET_HISTORY, GET_COMPANY_INFO_BY_ACCOUNT,
                     GET_FAILURE_COUNT_24H -> true;
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
        if (!hasManage && !isAdmin)
            return OutputData.of(FunctionType.UPDATE_DESCRIPTION,
                    new DescriptionOutput(CODE_NO_PERMISSION, company.getCompanyId()));
        cm.updateDescription(company.getCompanyId(), in.text == null ? "" : in.text);
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
                        founderNames));
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
        };
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
    private static int gateManage(int companyId, UUID callerUUID, IServerBankManager bm, CompanyManager cm) {
        Company company = cm.getById(companyId);
        if (company == null) return CODE_NOT_FOUND;
        IServerBankAccount account = bm.getBankAccount(company.getBankAccountNr());
        boolean hasManage = account != null && account.hasPermission(callerUUID, BankPermission.MANAGE);
        boolean isAdmin = bm.isBanksystemAdmin(callerUUID);
        return (hasManage || isAdmin) ? CODE_OK : CODE_NO_PERMISSION;
    }

    private static OutputData handleCreatePayout(CreatePayoutInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.CREATE_PAYOUT, new CreatePayoutOutput(gate, 0L));
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.CREATE_PAYOUT, new CreatePayoutOutput(CODE_INTERNAL, 0L));
        // Server tick isn't easily reachable from ARRS thread — fall through to client-supplied nowTick.
        long nowTick = in.nowTick;
        IPayoutManager.CreateOutcome outcome = pm.createSchedule(in.companyId, in.target, in.amount,
                in.intervalTicks, nowTick, in.callerUUID);
        return OutputData.of(FunctionType.CREATE_PAYOUT,
                new CreatePayoutOutput(mapPayoutOp(outcome.result()), outcome.scheduleId()));
    }

    private static OutputData handleUpdatePayout(UpdatePayoutInput in, IServerBankManager bm, CompanyManager cm) {
        int gate = gateManage(in.companyId, in.callerUUID, bm, cm);
        if (gate != CODE_OK) return OutputData.of(FunctionType.UPDATE_PAYOUT, new UpdatePayoutOutput(gate));
        IPayoutManager pm = BACKEND_INSTANCES != null ? BACKEND_INSTANCES.PAYOUT_MANAGER : null;
        if (pm == null) return OutputData.of(FunctionType.UPDATE_PAYOUT, new UpdatePayoutOutput(CODE_INTERNAL));
        return OutputData.of(FunctionType.UPDATE_PAYOUT,
                new UpdatePayoutOutput(mapPayoutOp(pm.updateSchedule(in.companyId, in.scheduleId, in.newAmount, in.newIntervalTicks))));
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
        return OutputData.of(FunctionType.LIST_SCHEDULES, new ListSchedulesOutput(wire));
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
                        founderNames));
    }

    // ------------------------------------------------------------------
    // Slave-side convenience helpers
    // ------------------------------------------------------------------
    public static CompletableFuture<CreateOutput> createCompanyAsync(String name, long maxSupply, UUID caller, String callerName) {
        InputData input = InputData.of(FunctionType.CREATE_COMPANY, new CreateInput(name, maxSupply, caller, callerName));
        CompletableFuture<CreateOutput> future = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(out -> future.complete(out.decodeResult()));
        return future;
    }

    public static CompletableFuture<TransferOutput> transferFounderAsync(String companyName, UUID caller, String targetName) {
        InputData input = InputData.of(FunctionType.TRANSFER_FOUNDER, new TransferInput(companyName, caller, targetName));
        CompletableFuture<TransferOutput> future = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(out -> future.complete(out.decodeResult()));
        return future;
    }

    public static CompletableFuture<DissolveOutput> dissolveCompanyAsync(String companyName, UUID caller) {
        InputData input = InputData.of(FunctionType.DISSOLVE_COMPANY, new DissolveInput(companyName, caller));
        CompletableFuture<DissolveOutput> future = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(out -> future.complete(out.decodeResult()));
        return future;
    }

    public static CompletableFuture<DescriptionOutput> updateDescriptionAsync(String companyName, UUID caller, String text) {
        InputData input = InputData.of(FunctionType.UPDATE_DESCRIPTION, new DescriptionInput(companyName, caller, text));
        CompletableFuture<DescriptionOutput> future = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(out -> future.complete(out.decodeResult()));
        return future;
    }

    public static CompletableFuture<CompanyInfoOutput> getCompanyInfoAsync(String companyName) {
        InputData input = InputData.of(FunctionType.GET_COMPANY_INFO, companyName);
        CompletableFuture<CompanyInfoOutput> future = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(out -> future.complete(out.decodeResult()));
        return future;
    }

    public static CompletableFuture<Boolean> isNameTakenAsync(String name) {
        InputData input = InputData.of(FunctionType.IS_NAME_TAKEN, name);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(out -> future.complete(out.decodeResult()));
        return future;
    }

    // ------------------------------------------------------------------
    // Task #45a — payout slave-side helpers
    // ------------------------------------------------------------------
    public static CompletableFuture<CreatePayoutOutput> createPayoutAsync(int companyId, UUID target, long amount,
                                                                         long intervalTicks, long nowTick, UUID caller) {
        InputData input = InputData.of(FunctionType.CREATE_PAYOUT,
                new CreatePayoutInput(companyId, target, amount, intervalTicks, nowTick, caller));
        CompletableFuture<CreatePayoutOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    public static CompletableFuture<UpdatePayoutOutput> updatePayoutAsync(int companyId, long scheduleId,
                                                                         long newAmount, long newIntervalTicks, UUID caller) {
        InputData input = InputData.of(FunctionType.UPDATE_PAYOUT,
                new UpdatePayoutInput(companyId, scheduleId, newAmount, newIntervalTicks, caller));
        CompletableFuture<UpdatePayoutOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    public static CompletableFuture<PausePayoutOutput> pausePayoutAsync(int companyId, long scheduleId,
                                                                       boolean paused, UUID caller) {
        InputData input = InputData.of(FunctionType.PAUSE_PAYOUT,
                new PausePayoutInput(companyId, scheduleId, paused, caller));
        CompletableFuture<PausePayoutOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    public static CompletableFuture<DeletePayoutOutput> deletePayoutAsync(int companyId, long scheduleId, UUID caller) {
        InputData input = InputData.of(FunctionType.DELETE_PAYOUT,
                new DeletePayoutInput(companyId, scheduleId, caller));
        CompletableFuture<DeletePayoutOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    public static CompletableFuture<ListSchedulesOutput> listSchedulesAsync(int companyId) {
        InputData input = InputData.of(FunctionType.LIST_SCHEDULES, new ListSchedulesInput(companyId));
        CompletableFuture<ListSchedulesOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    public static CompletableFuture<GetHistoryOutput> getHistoryAsync(long scheduleId, int limit) {
        InputData input = InputData.of(FunctionType.GET_HISTORY, new GetHistoryInput(scheduleId, limit));
        CompletableFuture<GetHistoryOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    public static CompletableFuture<GetFailureCount24hOutput> getFailureCount24hAsync(int companyId) {
        InputData input = InputData.of(FunctionType.GET_FAILURE_COUNT_24H, new GetFailureCount24hInput(companyId));
        CompletableFuture<GetFailureCount24hOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    public static CompletableFuture<CompanyInfoOutput> getCompanyInfoByAccountAsync(int accountNr) {
        InputData input = InputData.of(FunctionType.GET_COMPANY_INFO_BY_ACCOUNT, new GetCompanyInfoByAccountInput(accountNr));
        CompletableFuture<CompanyInfoOutput> f = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(o -> f.complete(o.decodeResult()));
        return f;
    }

    /** Task #43h — fetch company names visible to the caller under a rights filter. */
    public static CompletableFuture<List<String>> listCompanyNamesForCallerAsync(UUID caller, byte filterKind) {
        InputData input = InputData.of(FunctionType.LIST_COMPANIES_FOR_CALLER, new ListInput(caller, filterKind));
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        Request.instance.sendRequestToMaster(input).thenAccept(out -> {
            ListOutput result = out.decodeResult();
            future.complete(result == null ? List.of() : result.companyNames());
        });
        return future;
    }
}
