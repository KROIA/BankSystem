package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.modutilities.persistence.ServerSaveableChunked;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Master-only registry of {@link Company} objects. Owns:
 * <ul>
 *   <li>Three in-memory indices — by id, by bank account nr, by lower-cased name.</li>
 *   <li>Monotonic {@code nextCompanyId}.</li>
 *   <li>Chunked NBT save/load matching {@code ServerBankManager}'s pattern
 *       (see {@code BankSystemDataHandler#save_bank/load_bank}).</li>
 *   <li>The founder-check callback consulted by
 *       {@link net.kroia.banksystem.banking.bankaccount.ServerBankAccount}'s
 *       four-arg {@code enforceManageInvariant} — a founder can never lose MANAGE.</li>
 * </ul>
 *
 * <p>Slaves never touch Company NBT on disk — that's enforced at the {@code
 * BankSystemDataHandler} call site, which only invokes {@link #save(Map)} /
 * {@link #load(Map)} on master. All slave-side reads and writes forward through the
 * ARRS {@code AsyncCompanyManager}.
 */
public final class CompanyManager implements ServerSaveableChunked {

    private static CompanyManager INSTANCE;

    /** {@link BankSystemModBackend.Instances} for logger access — pattern copied from {@code ServerBankManager}. */
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    /**
     * Installs the singleton and (once installed) wires the founder-invariant callback
     * into {@link net.kroia.banksystem.banking.bankaccount.ServerBankAccount}. Called from
     * {@code BankSystemModBackend} initialization alongside the other managers.
     */
    public static void install() {
        if (INSTANCE == null) {
            INSTANCE = new CompanyManager();
        }
        net.kroia.banksystem.banking.bankaccount.ServerBankAccount.setFounderChecker(
                (accountNr, uuid) -> {
                    CompanyManager mgr = get();
                    if (mgr == null) return false;
                    return mgr.isFounderOf(accountNr, uuid);
                });
    }

    /** {@code null} on slave servers or before install. */
    @Nullable
    public static CompanyManager get() {
        return INSTANCE;
    }

    /**
     * Test hook — resets the singleton to a fresh empty instance so persistence tests
     * are hermetic. Also re-wires the founder callback. Not for production use.
     */
    public static CompanyManager resetForTest() {
        INSTANCE = new CompanyManager();
        install();
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Indices
    // ------------------------------------------------------------------
    private final Map<Integer, Company> byId = new HashMap<>();
    private final Map<Integer, Company> byBankAccount = new HashMap<>();
    private final Map<String, Company> byNameLower = new HashMap<>();

    private int nextCompanyId = 1;

    /** Public for test injection. */
    public CompanyManager() {}

    // ------------------------------------------------------------------
    // Public API (spec §1)
    // ------------------------------------------------------------------

    /** Result of {@link #createCompany}. */
    public enum CreateResult {
        OK,
        NAME_TAKEN,
        INVALID_NAME,
        INVALID_MAX_SUPPLY,
        BANK_ACCOUNT_MISSING,
        BANK_ACCOUNT_ALREADY_HAS_COMPANY
    }

    public static final class CreateOutcome {
        public final CreateResult result;
        @Nullable public final Company company;

        public CreateOutcome(CreateResult result, @Nullable Company company) {
            this.result = result;
            this.company = company;
        }
    }

    /**
     * Create a Company. Founders start with the single caller UUID. Validates:
     * <ul>
     *   <li>{@code name} non-empty and not currently taken (case-insensitive).</li>
     *   <li>{@code maxSupply > 0 && maxSupply <= 1_000_000_000}.</li>
     *   <li>{@code bankAccountNr} corresponds to a live bank account.</li>
     *   <li>The bank account is not already bound to another Company.</li>
     * </ul>
     */
    public CreateOutcome createCompany(String name, int bankAccountNr, UUID callerUUID, long maxSupply) {
        if (name == null || name.isBlank()) return new CreateOutcome(CreateResult.INVALID_NAME, null);
        if (maxSupply <= 0 || maxSupply > 1_000_000_000L) {
            return new CreateOutcome(CreateResult.INVALID_MAX_SUPPLY, null);
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (byNameLower.containsKey(key)) {
            return new CreateOutcome(CreateResult.NAME_TAKEN, null);
        }
        if (byBankAccount.containsKey(bankAccountNr)) {
            return new CreateOutcome(CreateResult.BANK_ACCOUNT_ALREADY_HAS_COMPANY, null);
        }
        // Bank-account existence check — the manager may be null in unit tests.
        IServerBankManager bankManager = getBankManager();
        if (bankManager != null && bankManager.getBankAccount(bankAccountNr) == null) {
            return new CreateOutcome(CreateResult.BANK_ACCOUNT_MISSING, null);
        }

        Set<UUID> founders = new HashSet<>();
        if (callerUUID != null) founders.add(callerUUID);

        int id = nextCompanyId++;
        Company company = new Company(id, name, bankAccountNr, maxSupply,
                System.currentTimeMillis(), "", founders, 0L, ShareVisuals.EMPTY, new ArrayList<>());
        byId.put(id, company);
        byBankAccount.put(bankAccountNr, company);
        byNameLower.put(key, company);
        info("Created company #" + id + " '" + name + "' bound to bank account " + bankAccountNr
                + " (founder=" + callerUUID + ", maxSupply=" + maxSupply + ")");
        return new CreateOutcome(CreateResult.OK, company);
    }

    /** Removes the Company object; underlying bank account is untouched. */
    public boolean deleteCompany(int companyId) {
        Company company = byId.remove(companyId);
        if (company == null) return false;
        byBankAccount.remove(company.getBankAccountNr());
        byNameLower.remove(company.getName().toLowerCase(Locale.ROOT));
        info("Deleted company #" + companyId + " '" + company.getName() + "'");
        return true;
    }

    /** Case-insensitive uniqueness probe. */
    public boolean isNameTaken(String name) {
        if (name == null) return false;
        return byNameLower.containsKey(name.toLowerCase(Locale.ROOT));
    }

    @Nullable public Company getById(int companyId) { return byId.get(companyId); }
    @Nullable public Company getByBankAccount(int accountNr) { return byBankAccount.get(accountNr); }
    @Nullable public Company getByName(String name) {
        if (name == null) return null;
        return byNameLower.get(name.toLowerCase(Locale.ROOT));
    }

    /** True iff {@code uuid} is a founder of the Company bound to {@code accountNr}. */
    public boolean isFounderOf(int accountNr, UUID uuid) {
        Company c = byBankAccount.get(accountNr);
        return c != null && c.isFounder(uuid);
    }

    /** Result of {@link #transferFounder}. */
    public enum TransferResult { OK, COMPANY_MISSING, NOT_A_FOUNDER, ALREADY_A_FOUNDER }

    /**
     * Removes {@code from} from the founder set, adds {@code to}, and mirrors the change
     * on the bound bank account's MANAGE bit. Bank-account update is best-effort — if the
     * bank manager is unavailable (test harness), only the founder set is mutated.
     */
    public TransferResult transferFounder(int companyId, UUID from, UUID to) {
        Company company = byId.get(companyId);
        if (company == null) return TransferResult.COMPANY_MISSING;
        if (from == null || !company.isFounder(from)) return TransferResult.NOT_A_FOUNDER;
        if (to != null && company.isFounder(to)) return TransferResult.ALREADY_A_FOUNDER;

        company.removeFounder(from);
        if (to != null) company.addFounder(to);

        IServerBankManager bankManager = getBankManager();
        if (bankManager != null) {
            IServerBankAccount account = bankManager.getBankAccount(company.getBankAccountNr());
            if (account != null) {
                // Grant MANAGE to the new founder (if any) on the bank account.
                if (to != null) {
                    User toUser = bankManager.getUserByUUID(to);
                    if (toUser != null) {
                        int existing = account.hasUser(to) ? account.getPermission(to) : 0;
                        int newMask = existing | BankPermission.MANAGE.getValue();
                        if (account.hasUser(to)) {
                            account.setPermission(to, newMask);
                        } else {
                            account.addUser(toUser, newMask);
                        }
                    }
                }
                // Strip MANAGE from the departing founder (leave other bits if any).
                if (account.hasUser(from)) {
                    int mask = account.getPermission(from);
                    int stripped = mask & ~BankPermission.MANAGE.getValue();
                    account.setPermission(from, stripped);
                }
            }
        }
        info("Transferred founder of company #" + companyId + " from " + from + " to " + to);
        return TransferResult.OK;
    }

    /**
     * Task #46 (v2.0.8) — MANAGE-gated update of a Company's {@link ShareVisuals}.
     * Permission gating happens at the ARRS entry point (see {@code AsyncCompanyManager});
     * this method is the pure mutator. Master-only.
     *
     * @return {@code true} when the Company exists and was updated; {@code false} otherwise.
     */
    /**
     * Task #47 (v2.0.8) — increment {@link Company#getTotalSharesIssued()} by one,
     * capped by {@link Company#getMaxSupply()}. Master-only; broadcasts a supply update
     * on success. Returns {@code false} if company is missing or cap reached.
     */
    public boolean stampShare(int companyId) {
        Company company = byId.get(companyId);
        if (company == null) return false;
        long cur = company.getTotalSharesIssued();
        if (cur + 1 > company.getMaxSupply()) return false;
        company.setTotalSharesIssued(cur + 1);
        broadcastSupply(companyId, cur + 1);
        return true;
    }

    /**
     * Task #47 (v2.0.8) — decrement {@link Company#getTotalSharesIssued()} by one,
     * floored at zero. Master-only; broadcasts a supply update on success. Returns
     * {@code false} if company is missing or supply already zero.
     */
    public boolean redeemShare(int companyId) {
        Company company = byId.get(companyId);
        if (company == null) return false;
        long cur = company.getTotalSharesIssued();
        if (cur <= 0L) return false;
        company.setTotalSharesIssued(cur - 1);
        broadcastSupply(companyId, cur - 1);
        return true;
    }

    private static void broadcastSupply(int companyId, long total) {
        net.minecraft.server.MinecraftServer server = dev.architectury.utils.GameInstance.getServer();
        if (server == null) return;
        net.kroia.banksystem.networking.general.S2CCompanyVisualSupplyUpdatePacket
                .broadcast(server, companyId, total);
    }

    public boolean updateShareVisuals(int companyId, ShareVisuals visuals) {
        Company company = byId.get(companyId);
        if (company == null) return false;
        company.setShareVisuals(visuals == null ? ShareVisuals.EMPTY : visuals);
        return true;
    }

    public boolean updateDescription(int companyId, String newDescription) {
        Company company = byId.get(companyId);
        if (company == null) return false;
        company.setDescription(newDescription);
        return true;
    }

    // ------------------------------------------------------------------
    // Task #45 (v2.0.8) — Payout schedule CRUD on Company NBT.
    // ------------------------------------------------------------------

    /** Hard-floor per spec Open Items — no schedule may run more frequently than once per second. */
    public static final long MIN_INTERVAL_TICKS = 20L;

    public enum PayoutMutation { OK, COMPANY_MISSING, SCHEDULE_MISSING, INVALID_INPUT }

    public static final class ScheduleCreateOutcome {
        public final PayoutMutation result;
        @Nullable public final PayoutSchedule schedule;
        public ScheduleCreateOutcome(PayoutMutation result, @Nullable PayoutSchedule schedule) {
            this.result = result;
            this.schedule = schedule;
        }
    }

    /**
     * Create a new payout schedule on {@code companyId}. Enforces the minimum-interval
     * hard-floor ({@link #MIN_INTERVAL_TICKS}). {@code amount > 0} required.
     */
    public ScheduleCreateOutcome createSchedule(int companyId, UUID target, long amount,
                                                long intervalTicks, long nowTick,
                                                UUID createdBy) {
        Company company = byId.get(companyId);
        if (company == null) return new ScheduleCreateOutcome(PayoutMutation.COMPANY_MISSING, null);
        if (target == null || amount <= 0 || intervalTicks < MIN_INTERVAL_TICKS) {
            return new ScheduleCreateOutcome(PayoutMutation.INVALID_INPUT, null);
        }
        long id = company.allocateScheduleId();
        PayoutSchedule schedule = new PayoutSchedule(id, target, amount, intervalTicks,
                nowTick + intervalTicks, false, System.currentTimeMillis(), createdBy);
        company.addSchedule(schedule);
        return new ScheduleCreateOutcome(PayoutMutation.OK, schedule);
    }

    /** Update amount + interval on an existing schedule. Interval-floor enforced. */
    public PayoutMutation updateSchedule(int companyId, long scheduleId, long newAmount,
                                          long newIntervalTicks) {
        Company company = byId.get(companyId);
        if (company == null) return PayoutMutation.COMPANY_MISSING;
        if (newAmount <= 0 || newIntervalTicks < MIN_INTERVAL_TICKS) return PayoutMutation.INVALID_INPUT;
        PayoutSchedule existing = company.findSchedule(scheduleId);
        if (existing == null) return PayoutMutation.SCHEDULE_MISSING;
        boolean ok = company.replaceSchedule(scheduleId, existing.withAmountAndInterval(newAmount, newIntervalTicks));
        return ok ? PayoutMutation.OK : PayoutMutation.SCHEDULE_MISSING;
    }

    public PayoutMutation pauseSchedule(int companyId, long scheduleId, boolean paused) {
        Company company = byId.get(companyId);
        if (company == null) return PayoutMutation.COMPANY_MISSING;
        PayoutSchedule existing = company.findSchedule(scheduleId);
        if (existing == null) return PayoutMutation.SCHEDULE_MISSING;
        boolean ok = company.replaceSchedule(scheduleId, existing.withPaused(paused));
        return ok ? PayoutMutation.OK : PayoutMutation.SCHEDULE_MISSING;
    }

    public PayoutMutation deleteSchedule(int companyId, long scheduleId) {
        Company company = byId.get(companyId);
        if (company == null) return PayoutMutation.COMPANY_MISSING;
        return company.removeSchedule(scheduleId) ? PayoutMutation.OK : PayoutMutation.SCHEDULE_MISSING;
    }

    /**
     * Executor-only hook — replace a schedule to advance {@code nextRunTick} after a
     * successful (or observed) tick. Does not validate; the executor already resolved
     * the schedule.
     */
    public void advanceSchedule(int companyId, long scheduleId, long newNextRunTick) {
        Company company = byId.get(companyId);
        if (company == null) return;
        PayoutSchedule existing = company.findSchedule(scheduleId);
        if (existing == null) return;
        company.replaceSchedule(scheduleId, existing.withNextRunTick(newNextRunTick));
    }

    public List<PayoutSchedule> listSchedulesFor(int companyId) {
        Company company = byId.get(companyId);
        if (company == null) return List.of();
        return company.getPayoutSchedules();
    }

    /**
     * Cascade-strip hook called from {@code UpdateBankAccountRequest.setUsers} when a user is
     * removed from an account. Removes every {@link PayoutSchedule} whose {@code targetUUID}
     * equals the removed user on the Company bound to that account (if any).
     *
     * @return number of schedules stripped (0 if none, or if the account has no Company).
     */
    public int cascadeStripPayoutsForRemovedUser(int accountNr, UUID removedUser) {
        Company company = byBankAccount.get(accountNr);
        if (company == null || removedUser == null) return 0;
        int removed = company.stripSchedulesForUser(removedUser);
        if (removed > 0) {
            info("Cascade-stripped " + removed + " payout schedule(s) from company #"
                    + company.getCompanyId() + " for removed user " + removedUser);
        }
        return removed;
    }

    /** Iteration hook for the future payout scheduler (Task #45). */
    public void forEach(Consumer<Company> action) {
        for (Company c : byId.values()) action.accept(c);
    }

    public int size() { return byId.size(); }

    public Collection<Company> getAll() { return java.util.Collections.unmodifiableCollection(byId.values()); }

    // ------------------------------------------------------------------
    // Task #43h — rights-scoped enumeration helpers for tab-completion.
    // ------------------------------------------------------------------

    /** All Company objects (read-only view). Used for {@code /company info} tab-completion. */
    public Set<Company> listAllCompanies() {
        return new HashSet<>(byId.values());
    }

    /** Companies where {@code uuid} appears in the founder set. */
    public Set<Company> listCompaniesFounderedBy(UUID uuid) {
        Set<Company> out = new HashSet<>();
        if (uuid == null) return out;
        for (Company c : byId.values()) {
            if (c.isFounder(uuid)) out.add(c);
        }
        return out;
    }

    /**
     * Companies whose bound bank account grants MANAGE to {@code uuid}. Falls back to the
     * founder set when the bank manager is unavailable (test harness).
     */
    public Set<Company> listCompaniesManagedBy(UUID uuid) {
        Set<Company> out = new HashSet<>();
        if (uuid == null) return out;
        IServerBankManager bankManager = getBankManager();
        for (Company c : byId.values()) {
            if (bankManager != null) {
                IServerBankAccount account = bankManager.getBankAccount(c.getBankAccountNr());
                if (account != null && account.hasPermission(uuid, BankPermission.MANAGE)) {
                    out.add(c);
                    continue;
                }
            }
            // Founders always effectively have MANAGE via the founder invariant.
            if (c.isFounder(uuid)) out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Persistence — chunked NBT (master only; wired from BankSystemDataHandler)
    // ------------------------------------------------------------------
    @Override
    public boolean save(Map<String, ListTag> listTags) {
        CompoundTag meta = new CompoundTag();
        meta.putInt("version", 1);
        meta.putInt("nextCompanyId", nextCompanyId);
        ListTag metaList = new ListTag();
        metaList.add(meta);
        listTags.put("meta", metaList);

        ListTag companiesList = new ListTag();
        for (Company company : byId.values()) {
            CompoundTag entry = new CompoundTag();
            company.save(entry);
            companiesList.add(entry);
        }
        listTags.put("companies", companiesList);
        return true;
    }

    @Override
    public boolean load(Map<String, ListTag> listTags) {
        byId.clear();
        byBankAccount.clear();
        byNameLower.clear();
        nextCompanyId = 1;

        if (listTags == null || listTags.isEmpty()) return true;

        if (listTags.containsKey("meta")) {
            ListTag metaList = listTags.get("meta");
            if (!metaList.isEmpty()) {
                CompoundTag meta = metaList.getCompound(0);
                if (meta.contains("nextCompanyId")) nextCompanyId = meta.getInt("nextCompanyId");
            }
        }

        if (listTags.containsKey("companies")) {
            ListTag companiesList = listTags.get("companies");
            IServerBankManager bankManager = getBankManager();
            // First pass: parse + drop duplicates keeping older createdAt.
            List<Company> parsed = new ArrayList<>();
            for (int i = 0; i < companiesList.size(); i++) {
                if (companiesList.getCompound(i) == null) continue;
                Company company = Company.load(companiesList.getCompound(i));
                if (company == null) {
                    warn("Skipped an unreadable Company entry at index " + i);
                    continue;
                }
                parsed.add(company);
            }
            // Sort by createdAt ascending so on duplicate-name the older wins insertion.
            parsed.sort(Comparator.comparingLong(Company::getCreatedAt));

            for (Company company : parsed) {
                // Log-and-drop if the referenced bank account is missing.
                if (bankManager != null && bankManager.getBankAccount(company.getBankAccountNr()) == null) {
                    warn("Dropping company #" + company.getCompanyId() + " '" + company.getName()
                            + "': bank account " + company.getBankAccountNr() + " does not exist.");
                    continue;
                }
                String key = company.getName().toLowerCase(Locale.ROOT);
                if (byNameLower.containsKey(key)) {
                    warn("Dropping company #" + company.getCompanyId() + " '" + company.getName()
                            + "': name collision with existing company #"
                            + byNameLower.get(key).getCompanyId() + " (older entry kept).");
                    continue;
                }
                if (byBankAccount.containsKey(company.getBankAccountNr())) {
                    warn("Dropping company #" + company.getCompanyId() + " '" + company.getName()
                            + "': bank account " + company.getBankAccountNr()
                            + " is already bound to another company.");
                    continue;
                }
                byId.put(company.getCompanyId(), company);
                byBankAccount.put(company.getBankAccountNr(), company);
                byNameLower.put(key, company);
                if (company.getCompanyId() >= nextCompanyId) {
                    nextCompanyId = company.getCompanyId() + 1;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    @Nullable
    private static IServerBankManager getBankManager() {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) return null;
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
    }

    private static void info(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.info("[CompanyManager] " + msg);
    }

    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.warn("[CompanyManager] " + msg);
    }
}
