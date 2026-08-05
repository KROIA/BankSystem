package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
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
        LIST_COMPANIES_FOR_CALLER
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
                case GET_COMPANY_INFO, IS_NAME_TAKEN, LIST_COMPANIES_FOR_CALLER -> true;
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
        };
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
