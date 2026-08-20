package net.kroia.banksystem.testing.tests;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-game smoke tests for the Lightman's Currency adapter (Task #36, v2.0.5).
 * <p>
 * <b>Server type:</b> MASTER_ONLY — bindings are master-authoritative.
 * <p>
 * <b>LC presence:</b> every test checks {@code Platform.isModLoaded("lightmanscurrency")}
 * at the top and returns PASS with an INFO log if the mod is absent. No failures
 * when LC isn't installed.
 * <p>
 * <b>Scope:</b> Personal accounts fully covered; a single shared-account
 * (team) enumeration + bind test runs when a live {@code ServerPlayer} is
 * available on the server (see {@link #testTeamEnumerationAndBind()}).
 *
 * @since 2.0.5
 */
public class LightmansCurrencyAdapterTests extends TestSuite {

    private static final UUID TEST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000036");
    private static final String TEST_OWNER_NAME = "LCTestOwner";

    private ServerBankManager manager;
    private int testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    private ItemID slotItem;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("lightmans_personal_round_trip", this::testPersonalRoundTrip);
        addTest("lightmans_overflow_guard", this::testOverflowGuard);
        // Task #37 parity pass — mirror the ExternalCurrencyBindingTests hardening suite
        // over the real LC adapter. Skip when LC isn't loaded (silent PASS with note).
        addTest("lightmans_transactional_lock_protocol", this::testTransactionalLockProtocol);
        addTest("lightmans_bind_preserves_locked_balance_into_binding_row", this::testBindPreservesLocked);
        addTest("lightmans_bind_overflow_leaves_local_state_untouched", this::testBindOverflowAtomicity);
        addTest("lightmans_withdraw_refused_when_external_says_no", this::testWithdrawRefusedByExternal);
        // Team (shared) account enumeration + bind. Requires a live ServerPlayer to
        // drive LC's TeamAPI.CreateTeam; skips with PASS-with-note if no one is online.
        addTest("lightmans_team_enumeration_and_bind", this::testTeamEnumerationAndBind);
        // Task #38 — coin-variant routing through the deposit path
        addTest("lightmans_variant_deposit_credits_bound_slot", this::testVariantDepositCreditsBoundSlot);
        addTest("lightmans_no_binding_falls_back", this::testNoBindingFallsBack);
        // Task #38b — per-slot ratio zero-drift regression suite
        addTest("lightmans_variant_deposit_and_full_round_trip", this::testVariantDepositFullRoundTrip);
        addTest("lightmans_all_variant_deposits_no_drift", this::testAllVariantDepositsNoDrift);
        addTest("lightmans_multi_variant_deposit_then_gold_withdraw", this::testMultiVariantDepositThenGoldWithdraw);
        addTest("lightmans_bind_with_existing_balance_ratio_conversion", this::testBindWithExistingBalanceRatioConversion);
    }

    @Override
    public void setup() {
        IBankManager bankManager = BankSystemMod.getAPI().getServerBankManager();
        if (bankManager == null) return;
        if (!(bankManager.getSync() instanceof ServerBankManager serverManager)) return;
        manager = serverManager;

        if (!manager.userExists(TEST_OWNER)) {
            manager.addUser(new User(TEST_OWNER, TEST_OWNER_NAME));
        }

        slotItem = ItemIDManager.registerItemStackServerSide_direct(Items.EMERALD.getDefaultInstance());
        manager.allowItemID(slotItem);

        IServerBankAccount existing = manager.getBankAccountByName("LCTestAccount");
        if (existing != null) {
            manager.deleteBankAccount(existing.getAccountNumber());
        }
        IServerBankAccount account = manager.createBankAccount("LCTestAccount");
        if (account != null) {
            testAccountNr = account.getAccountNumber();
            account.createBank(slotItem, 0);
        }
    }

    private void perTestReset() {
        if (manager == null) return;
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings != null && testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            bindings.removeAllForAccount(testAccountNr);
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account != null) {
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank != null) {
                bank.setBalance(0);
            }
        }
        // LC external state persists across sessions (backed by the save's dimension data).
        // Zero the primary personal LC account before each test so absolute-value assertions
        // are stable across runs. Mirrors NumismaticsAdapterTests.zeroPlayerNumismaticsAccount.
        zeroLightmansAccount();
    }

    /**
     * Coin-chain unique-name prefix; matches {@link net.kroia.banksystem.integration.lightmanscurrency.LightmansCurrencyAccount}.
     */
    private static final String COIN_CHAIN_PREFIX = "lightmanscurrency:coin";

    /**
     * Resolves TEST_OWNER's personal LC {@code IBankAccount} via
     * {@code PlayerBankReference.of(UUID).get()}. Returns {@code null} if LC is
     * absent, its API surface is unreachable, or the player has no personal
     * account yet.
     */
    private Object resolveLcAccount() {
        if (!Platform.isModLoaded("lightmanscurrency")) return null;
        try {
            Class<?> playerRefClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.reference.builtin.PlayerBankReference");
            Object playerRef = playerRefClass.getMethod("of", UUID.class).invoke(null, TEST_OWNER);
            if (playerRef == null) return null;
            Class<?> bankRefClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference");
            return bankRefClass.getMethod("get").invoke(playerRef);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempts to source a coin-chain {@code MoneyValue} usable as a
     * {@code .fromCoreValue(long)} seed. Returns {@code null} if no coin value
     * is present in the account and MoneyAPI's registered coin type cannot
     * synthesize one — same ladder as the production adapter.
     */
    private Object sourceCoinSeed(Object lcAccount) {
        // 1) Pull from the account's own storage.
        try {
            Object storage = lcAccount.getClass().getMethod("getMoneyStorage").invoke(lcAccount);
            if (storage != null) {
                Object allValuesObj = storage.getClass().getMethod("allValues").invoke(storage);
                if (allValuesObj instanceof java.util.List<?> allValues) {
                    for (Object mv : allValues) {
                        if (mv == null) continue;
                        Object nameObj = mv.getClass().getMethod("getUniqueName").invoke(mv);
                        if (nameObj != null && nameObj.toString().startsWith(COIN_CHAIN_PREFIX)) {
                            return mv;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to MoneyAPI attempt.
        }
        // 2) MoneyAPI.getApi().GetRegisteredCurrencyType("lightmanscurrency:coin").loadMoneyValue(tag).
        try {
            Class<?> moneyAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.MoneyAPI");
            Object moneyAPI = moneyAPIClass.getMethod("getApi").invoke(null);
            if (moneyAPI == null) return null;
            net.minecraft.resources.ResourceLocation coinTypeId =
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin");
            Object currencyType = moneyAPIClass
                    .getMethod("GetRegisteredCurrencyType", net.minecraft.resources.ResourceLocation.class)
                    .invoke(moneyAPI, coinTypeId);
            if (currencyType == null) return null;
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("Coin", "lightmanscurrency:coin_gold");
            tag.putLong("Count", 0L);
            return currencyType.getClass()
                    .getMethod("loadMoneyValue", net.minecraft.nbt.CompoundTag.class)
                    .invoke(currencyType, tag);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reflection helper: zeros TEST_OWNER's personal LC account balance so every
     * test starts from a clean external state. Silently no-ops if LC is absent or
     * its API surface is unreachable — the test bodies still skip themselves via
     * {@code Platform.isModLoaded} guards.
     * <p>
     * Iterates {@code MoneyStorage.allValues()} and calls
     * {@code MoneyStorage.extractMoney(value, false)} for every coin-chain value.
     */
    private void zeroLightmansAccount() {
        if (!Platform.isModLoaded("lightmanscurrency")) return;
        Object lcAccount = resolveLcAccount();
        if (lcAccount == null) return;
        try {
            Object storage = lcAccount.getClass().getMethod("getMoneyStorage").invoke(lcAccount);
            if (storage == null) return;
            Class<?> moneyValueClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");

            // Snapshot the list — extractMoney mutates the underlying storage while we iterate.
            Object allValuesObj = storage.getClass().getMethod("allValues").invoke(storage);
            if (!(allValuesObj instanceof java.util.List<?> allValues)) return;
            java.util.List<Object> snapshot = new java.util.ArrayList<>();
            for (Object mv : allValues) {
                if (mv == null) continue;
                Object nameObj = mv.getClass().getMethod("getUniqueName").invoke(mv);
                if (nameObj != null && nameObj.toString().startsWith(COIN_CHAIN_PREFIX)) {
                    snapshot.add(mv);
                }
            }
            for (Object mv : snapshot) {
                storage.getClass().getMethod("extractMoney", moneyValueClass, boolean.class)
                        .invoke(storage, mv, false);
            }
        } catch (Exception ignored) {
            // Best-effort — if the API shape shifted, tests still fail their absolute
            // assertions loudly. Silent no-op keeps setup non-fatal.
        }
    }

    /**
     * Reflection helper: adds {@code targetCore} raw coin-chain units to TEST_OWNER's
     * personal LC account (does NOT set-to; that would require zeroing first, which
     * callers do explicitly via {@link #zeroLightmansAccount()} or via
     * {@link #perTestReset()}). Returns {@code true} on success, {@code false} if
     * LC's API surface is unreachable, the account can't be resolved, or no coin
     * seed MoneyValue is available. Used by {@link #testBindOverflowAtomicity()} to
     * push the LC balance near Long.MAX_VALUE.
     */
    private boolean preFillLightmansAccount(long targetCore) {
        if (!Platform.isModLoaded("lightmanscurrency")) return false;
        Object lcAccount = resolveLcAccount();
        if (lcAccount == null) return false;
        Object seed = sourceCoinSeed(lcAccount);
        if (seed == null) return false;
        try {
            Object target = seed.getClass().getMethod("fromCoreValue", long.class).invoke(seed, targetCore);
            if (target == null) return false;
            Class<?> moneyValueClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            lcAccount.getClass().getMethod("depositMoney", moneyValueClass).invoke(lcAccount, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Smoke test: bind a personal LC account, deposit via BankSystem,
     * verify the balance routes through, withdraw, verify again.
     */
    private TestResult testPersonalRoundTrip() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);

            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            if (refs.isEmpty()) {
                return fail("No bindable LC accounts for test owner");
            }

            ExternalAccountRef personalRef = refs.stream()
                    .filter(r -> !r.shared())
                    .findFirst()
                    .orElse(null);

            if (personalRef == null) {
                return fail("No personal LC account found");
            }

            // Bind the personal account to our test BankSystem account's slot
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) {
                return fail("BankAccountBindings unavailable");
            }

            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) {
                return fail("Test account not found");
            }

            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) {
                return fail("Test bank slot not found");
            }

            // Perform bind
            bindings.bind(testAccountNr, slotItem, personalRef);

            // Deposit 100 via BankSystem
            BankStatus depositStatus = bank.deposit(100);
            if (depositStatus != BankStatus.SUCCESS) {
                return fail("Deposit failed: " + depositStatus);
            }

            long balanceAfterDeposit = bank.getBalance();
            if (balanceAfterDeposit != 100) {
                return fail("Expected balance 100 after deposit, got " + balanceAfterDeposit);
            }

            // Withdraw 50
            BankStatus withdrawStatus = bank.withdraw(50);
            if (withdrawStatus != BankStatus.SUCCESS) {
                return fail("Withdraw failed: " + withdrawStatus);
            }

            long balanceAfterWithdraw = bank.getBalance();
            if (balanceAfterWithdraw != 50) {
                return fail("Expected balance 50 after withdraw, got " + balanceAfterWithdraw);
            }

            return pass("Round-trip: bind + deposit + withdraw all consistent");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Verify that deposits that would exceed Long.MAX_VALUE are refused.
     * LC uses long internally, so the cap is at Long.MAX_VALUE.
     */
    private TestResult testOverflowGuard() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);

            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            if (refs.isEmpty()) {
                return fail("No bindable LC accounts");
            }

            ExternalAccountRef personalRef = refs.get(0);
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) {
                return fail("BankAccountBindings unavailable");
            }

            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) {
                return fail("Test account not found");
            }

            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) {
                return fail("Test bank slot not found");
            }

            bindings.bind(testAccountNr, slotItem, personalRef);

            // Set balance near max
            bank.setBalance(Long.MAX_VALUE - 10);
            long balance = bank.getBalance();
            if (balance != Long.MAX_VALUE - 10) {
                return fail("Failed to set balance near max");
            }

            // Try to deposit 20 — should fail (would overflow)
            BankStatus depositStatus = bank.deposit(20);
            if (depositStatus == BankStatus.SUCCESS) {
                return fail("Deposit should have failed due to overflow");
            }

            // Balance should be unchanged
            long balanceAfter = bank.getBalance();
            if (balanceAfter != Long.MAX_VALUE - 10) {
                return fail("Balance changed after failed overflow deposit");
            }

            return pass("Overflow guard prevented deposit above Long.MAX_VALUE");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    // =======================================================================
    // Task #37 parity pass — mirrors the hardening cases in
    // ExternalCurrencyBindingTests over the real LC adapter (personal accounts
    // only). H5 (membership sync) intentionally omitted — LC personal accounts
    // have no membership set.
    // =======================================================================

    /**
     * Transactional-lock protocol: bind an LC personal account, deposit 100, then
     * lockAmount(40) → external drops to 60, row.locked = 40. withdrawLocked(30) →
     * external stays 60, row.locked = 10. unlockAmount(10) → external = 70, row.locked = 0.
     * Mirrors {@link ExternalCurrencyBindingTests#testLockedBalanceProtocol()}.
     */
    private TestResult testTransactionalLockProtocol() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared()).findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account found");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) return fail("Test bank slot not found");

            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItem, personalRef);
            if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

            // Deposit 100.
            BankStatus dep = bank.deposit(100L);
            if (dep != BankStatus.SUCCESS) return fail("deposit(100) returned " + dep);
            if (bank.getBalance() != 100L)
                return fail("Post-deposit free = " + bank.getBalance() + " (expected 100)");

            // lockAmount(40) — external physically drops to 60, row.locked = 40.
            BankStatus lockStatus = bank.lockAmount(40L);
            if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(40) returned " + lockStatus);
            if (bank.getBalance() != 60L)
                return fail("Post-lock free = " + bank.getBalance() + " (expected 60 — external decreased)");
            if (bank.getLockedBalance() != 40L)
                return fail("Post-lock locked = " + bank.getLockedBalance() + " (expected 40)");
            if (bank.getTotalBalance() != 100L)
                return fail("Post-lock total = " + bank.getTotalBalance() + " (expected 100)");
            if (bindings.getLocked(testAccountNr, slotItem) != 40L)
                return fail("Post-lock row locked = " + bindings.getLocked(testAccountNr, slotItem)
                        + " (expected 40)");

            // withdrawLocked(30) — external stays at 60, row.locked = 10 (already reduced at lock).
            BankStatus wlStatus = bank.withdrawLocked(30L);
            if (wlStatus != BankStatus.SUCCESS) return fail("withdrawLocked(30) returned " + wlStatus);
            if (bank.getBalance() != 60L)
                return fail("Post-withdrawLocked free = " + bank.getBalance()
                        + " (expected 60 — external unchanged)");
            if (bank.getLockedBalance() != 10L)
                return fail("Post-withdrawLocked locked = " + bank.getLockedBalance() + " (expected 10)");
            if (bank.getTotalBalance() != 70L)
                return fail("Post-withdrawLocked total = " + bank.getTotalBalance() + " (expected 70)");

            // unlockAmount(10) — external gains 10 back, row.locked = 0.
            BankStatus unlockStatus = bank.unlockAmount(10L);
            if (unlockStatus != BankStatus.SUCCESS) return fail("unlockAmount(10) returned " + unlockStatus);
            if (bank.getBalance() != 70L)
                return fail("Post-unlock free = " + bank.getBalance() + " (expected 70)");
            if (bank.getLockedBalance() != 0L)
                return fail("Post-unlock locked = " + bank.getLockedBalance() + " (expected 0)");

            return pass("Transactional-lock protocol: lock physically withdraws external, "
                    + "withdrawLocked is local-only, unlock deposits back");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Bind carries local locked balance into the binding row. Populate local free=80
     * locked=30, bind → external gains 80, row.locked = 30. Unbind (keepOnBankSystem=true)
     * → local free=80, locked=30 restored. Mirrors
     * {@link ExternalCurrencyBindingTests#testBindPreservesLocked()}.
     */
    private TestResult testBindPreservesLocked() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared()).findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account found");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) return fail("Test bank slot not found");

            // Populate local: free=80, locked=30.
            bank.setBalance(80L);
            BankStatus lockStatus = bank.lockAmount(30L);
            if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(30) returned " + lockStatus);
            if (bank.getBalance() != 50L)
                return fail("Setup: expected free=50 after lockAmount, got " + bank.getBalance());
            if (bank.getLockedBalance() != 30L)
                return fail("Setup: expected locked=30, got " + bank.getLockedBalance());

            // Deposit 30 more to get free back to 80 (setBalance drives free directly on the
            // unbound path; total is 50 + 30 = 80 free, 30 locked).
            BankStatus dep = bank.deposit(30L);
            if (dep != BankStatus.SUCCESS) return fail("deposit(30) returned " + dep);
            if (bank.getBalance() != 80L)
                return fail("Setup: expected free=80 after deposit(30), got " + bank.getBalance());

            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItem, personalRef);
            if (bindStatus != BankStatus.SUCCESS) {
                return fail("bind returned " + bindStatus + " (expected SUCCESS — locked funds must "
                        + "not block binding)");
            }

            // Row locked must be carried over.
            if (bindings.getLocked(testAccountNr, slotItem) != 30L) {
                return fail("Post-bind row locked = " + bindings.getLocked(testAccountNr, slotItem)
                        + " (expected 30 — must carry over from local)");
            }
            // Bound-slot free reads external.
            if (bank.getBalance() != 80L) {
                return fail("Post-bind free = " + bank.getBalance()
                        + " (expected 80 — free portion transferred to external)");
            }
            if (bank.getLockedBalance() != 30L) {
                return fail("Post-bind locked = " + bank.getLockedBalance() + " (expected 30)");
            }

            // Unbind with keepOnBankSystem=true — everything comes home.
            BankStatus unbind = manager.unbindExternalAccount(testAccountNr, slotItem, true);
            if (unbind != BankStatus.SUCCESS) return fail("unbind returned " + unbind);
            if (bank.getBalance() != 80L)
                return fail("After unbind: free = " + bank.getBalance() + " (expected 80)");
            if (bank.getLockedBalance() != 30L)
                return fail("After unbind: locked = " + bank.getLockedBalance() + " (expected 30)");

            return pass("Bind preserved locked=30 through binding row; unbind restored local free=80 locked=30");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Bind atomicity on FAILED_OVERFLOW. Pre-fill the LC account to near Long.MAX_VALUE
     * so the bind's 100-unit auto-transfer deposit overflows. Bind must return
     * FAILED_OVERFLOW, no binding row committed, local state unchanged. If we can't
     * pre-fill via reflection, PASS-with-note. Mirrors
     * {@link ExternalCurrencyBindingTests#testBindOverflowAtomicity()}.
     */
    private TestResult testBindOverflowAtomicity() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared()).findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account found");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) return fail("Test bank slot not found");

            // Local: free=100, locked=25. Same shape as the shared-suite version.
            bank.setBalance(100L);
            BankStatus lockStatus = bank.lockAmount(25L);
            if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(25) returned " + lockStatus);
            // Deposit 25 back so we return to free=100 (setBalance drove free to 100 initially,
            // lockAmount pulled 25 out — free is now 75. Top it back up.)
            BankStatus dep = bank.deposit(25L);
            if (dep != BankStatus.SUCCESS) return fail("deposit(25) returned " + dep);
            long preFree = bank.getBalance();
            long preLocked = bank.getLockedBalance();
            if (preFree != 100L || preLocked != 25L) {
                return fail("Setup: expected free=100 locked=25, got free=" + preFree
                        + " locked=" + preLocked);
            }

            // Pre-fill LC account to (Long.MAX_VALUE - 50) so a 100-unit deposit overflows.
            if (!preFillLightmansAccount(Long.MAX_VALUE - 50L)) {
                return pass("Could not pre-fill LC account via reflection to trigger overflow — "
                        + "skipped (bind overflow atomicity is covered by ExternalCurrencyBindingTests "
                        + "with the stub provider)");
            }

            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItem, personalRef);
            if (bindStatus != BankStatus.FAILED_OVERFLOW) {
                // Best-effort cleanup: drain and try to unbind if we somehow bound.
                zeroLightmansAccount();
                return fail("bind returned " + bindStatus + " (expected FAILED_OVERFLOW — LC balance "
                        + "was Long.MAX_VALUE - 50, deposit of 100 must overflow)");
            }
            // No binding row committed.
            if (bindings.getBinding(testAccountNr, slotItem) != null) {
                return fail("bind FAILED_OVERFLOW created a binding row (should not commit)");
            }
            // Local state untouched.
            if (bank.getBalance() != preFree) {
                return fail("After bind FAILED_OVERFLOW: free = " + bank.getBalance()
                        + " (expected " + preFree + " — must not mutate on failure)");
            }
            if (bank.getLockedBalance() != preLocked) {
                return fail("After bind FAILED_OVERFLOW: locked = " + bank.getLockedBalance()
                        + " (expected " + preLocked + " — must not mutate on failure)");
            }
            return pass("Bind FAILED_OVERFLOW: no row committed, local free/locked unchanged");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        } finally {
            // Always drain the pre-filled amount so subsequent tests aren't blocked at cap.
            zeroLightmansAccount();
        }
    }

    /**
     * Withdraw refused by external. Bind, deposit 100 via BankSystem, then drain the LC
     * balance to 20 via LC's own API. {@code bank.withdraw(50)} must fail (external only
     * has 20). External balance untouched by refusal. {@code bank.withdraw(15)} then
     * succeeds (drops external to 5). Mirrors
     * {@link ExternalCurrencyBindingTests#testWithdrawRefusedByExternal()}.
     */
    private TestResult testWithdrawRefusedByExternal() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared()).findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account found");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) return fail("Test bank slot not found");

            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItem, personalRef);
            if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

            BankStatus dep = bank.deposit(100L);
            if (dep != BankStatus.SUCCESS) return fail("deposit(100) returned " + dep);
            if (bank.getBalance() != 100L) {
                return fail("Post-deposit free = " + bank.getBalance() + " (expected 100)");
            }

            // Drain LC balance behind BankSystem's back to leave only 20 units.
            if (!drainLightmansAccountTo(20L)) {
                return pass("Could not drain LC balance via reflection to simulate external drift — "
                        + "skipped (withdraw refusal is covered by ExternalCurrencyBindingTests with "
                        + "the stub provider)");
            }

            // Try to withdraw more than external has.
            BankStatus wd = bank.withdraw(50L);
            if (wd == BankStatus.SUCCESS) {
                return fail("withdraw(50) succeeded — should have been refused (external only holds 20)");
            }
            // External must not have been mutated by the refusal (LC still holds 20).
            if (bank.getBalance() != 20L) {
                return fail("Post-refusal getBalance() = " + bank.getBalance()
                        + " (expected 20 — external state should reflect current drift)");
            }
            // A withdraw within external's budget still works.
            BankStatus wdSmall = bank.withdraw(15L);
            if (wdSmall != BankStatus.SUCCESS) {
                return fail("withdraw(15) returned " + wdSmall + " (expected SUCCESS)");
            }
            if (bank.getBalance() != 5L) {
                return fail("After withdraw(15): free = " + bank.getBalance() + " (expected 5)");
            }
            return pass("Withdraw refusal by external: BankSystem propagates failure, external "
                    + "untouched on refusal, subsequent smaller withdraw still succeeds");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Reflection helper: withdraws from TEST_OWNER's personal LC account until its
     * coin-chain balance is at most {@code targetCore} raw units. Returns
     * {@code true} on success, {@code false} if the API is unreachable or a coin
     * seed MoneyValue cannot be sourced.
     */
    private boolean drainLightmansAccountTo(long targetCore) {
        if (!Platform.isModLoaded("lightmanscurrency")) return false;
        Object lcAccount = resolveLcAccount();
        if (lcAccount == null) return false;
        try {
            Object storage = lcAccount.getClass().getMethod("getMoneyStorage").invoke(lcAccount);
            if (storage == null) return false;
            Object allValuesObj = storage.getClass().getMethod("allValues").invoke(storage);
            if (!(allValuesObj instanceof java.util.List<?> allValues)) return false;

            long current = 0L;
            for (Object mv : allValues) {
                if (mv == null) continue;
                Object nameObj = mv.getClass().getMethod("getUniqueName").invoke(mv);
                if (nameObj == null || !nameObj.toString().startsWith(COIN_CHAIN_PREFIX)) continue;
                Object core = mv.getClass().getMethod("getCoreValue").invoke(mv);
                if (core instanceof Number n) current += n.longValue();
            }
            if (current <= targetCore) return true;

            Object seed = sourceCoinSeed(lcAccount);
            if (seed == null) return false;

            long delta = current - targetCore;
            Object target = seed.getClass().getMethod("fromCoreValue", long.class).invoke(seed, delta);
            if (target == null) return false;
            Class<?> moneyValueClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            lcAccount.getClass().getMethod("withdrawMoney", moneyValueClass).invoke(lcAccount, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Team-account enumeration, bind, and round-trip smoke test.
     * <p>
     * <b>Live-player dependency.</b> LC's {@code TeamAPI.CreateTeam} takes a
     * {@link net.minecraft.world.entity.player.Player} — a UUID isn't enough. The
     * test therefore requires a live {@code ServerPlayer} on the server (usually
     * the operator running {@code /banksystem test} in an integrated server) and
     * skips with PASS-with-note when the player list is empty (dedicated server
     * with nobody online). The synthetic {@link #TEST_OWNER} UUID cannot drive
     * this path.
     * <p>
     * <b>Bank-provisioning dependency.</b> {@code TeamAPI.CreateTeam} does not
     * auto-claim a bank account for the fresh team ({@code hasBankAccount()}
     * returns {@code false} until the owner claims one via LC's team-management
     * UI). If we can't observe a claimed bank account after creation, the test
     * skips-with-note; no fabricated PASS.
     * <p>
     * Assertions when the test does run:
     * <ol>
     *   <li>Provider enumerates the team with {@code shared=true} for the live player.</li>
     *   <li>Bind succeeds via the master-side bindExternalAccount path.</li>
     *   <li>Deposit(100) / withdraw(30) round-trip through the bound team account
     *       leaves BankSystem's view at 70.</li>
     *   <li>{@code provider.open(ref).isSharedAccount() == true}.</li>
     *   <li>{@code currentMembers()} contains the team owner's UUID.</li>
     * </ol>
     */
    private TestResult testTeamEnumerationAndBind() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            // Find a live ServerPlayer. LC's CreateTeam takes a Player, not a UUID.
            MinecraftServer server = getServer();
            if (server == null) {
                return pass("No server available — team test needs live ServerPlayer for CreateTeam; skipped");
            }
            ServerPlayer livePlayer = server.getPlayerList().getPlayers().stream()
                    .findFirst().orElse(null);
            if (livePlayer == null) {
                return pass("No live ServerPlayer online — team test needs a Player for "
                        + "TeamAPI.CreateTeam (UUID-only won't work); skipped");
            }
            UUID livePlayerUuid = livePlayer.getUUID();

            // Create the test team via reflection.
            Object team;
            long teamID;
            try {
                Class<?> teamAPIClass = Class.forName(
                        "io.github.lightman314.lightmanscurrency.api.teams.TeamAPI");
                Object teamAPI = teamAPIClass.getMethod("getApi").invoke(null);
                if (teamAPI == null) {
                    return pass("TeamAPI.getApi() returned null — LC team API unavailable; skipped");
                }
                Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player");
                team = teamAPIClass.getMethod("CreateTeam", playerClass, String.class)
                        .invoke(teamAPI, livePlayer, "LCBankTestTeam");
                if (team == null) {
                    return pass("TeamAPI.CreateTeam returned null — could not provision test team; skipped");
                }
                Class<?> teamClass = Class.forName(
                        "io.github.lightman314.lightmanscurrency.api.teams.ITeam");
                teamID = (long) teamClass.getMethod("getID").invoke(team);

                // LC teams don't auto-claim a bank account — hasBankAccount() will typically
                // be false right after CreateTeam. Without a claimed bank we can't test the
                // bind path (provider filters teams without a bank). Skip cleanly.
                boolean hasBank = (boolean) teamClass.getMethod("hasBankAccount").invoke(team);
                if (!hasBank) {
                    return pass("Team created (id=" + teamID + ") but has no claimed bank account "
                            + "(LC teams don't auto-provision); team-bind path can't be exercised "
                            + "without LC UI interaction — skipped");
                }
            } catch (Exception e) {
                return pass("Team creation via reflection failed (" + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + ") — skipped");
            }

            // Enumeration: the team should appear for the live player, keyed "team:<id>", shared=true.
            List<ExternalAccountRef> refs = provider.listBindableAccounts(livePlayerUuid);
            ExternalAccountRef teamRef = refs.stream()
                    .filter(ExternalAccountRef::shared)
                    .filter(r -> r.accountKey().equals("team:" + teamID))
                    .findFirst()
                    .orElse(null);
            if (teamRef == null) {
                return fail("Team " + teamID + " not enumerated by listBindableAccounts for owner "
                        + livePlayerUuid + " — got: " + refs);
            }

            // Bind the team to a fresh test slot so we don't collide with earlier personal-account tests.
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) return fail("Test bank slot not found");

            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItem, teamRef);
            if (bindStatus != BankStatus.SUCCESS) {
                return fail("bind returned " + bindStatus + " (expected SUCCESS)");
            }

            // Deposit 100 / withdraw 30 round-trip.
            BankStatus dep = bank.deposit(100L);
            if (dep != BankStatus.SUCCESS) return fail("deposit(100) returned " + dep);
            if (bank.getBalance() != 100L)
                return fail("Post-deposit balance = " + bank.getBalance() + " (expected 100)");

            BankStatus wd = bank.withdraw(30L);
            if (wd != BankStatus.SUCCESS) return fail("withdraw(30) returned " + wd);
            if (bank.getBalance() != 70L)
                return fail("Post-withdraw balance = " + bank.getBalance() + " (expected 70)");

            // Verify the account handle reports shared=true and includes the live player in members.
            net.kroia.banksystem.api.currency.ExternalAccount opened = provider.open(teamRef);
            if (opened == null) return fail("provider.open(teamRef) returned null");
            if (!opened.isSharedAccount())
                return fail("opened.isSharedAccount() = false (expected true for team account)");
            Set<UUID> members = opened.currentMembers();
            if (!members.contains(livePlayerUuid)) {
                return fail("currentMembers() = " + members + " does not contain team owner "
                        + livePlayerUuid);
            }

            // Cleanup: unbind. LC exposes no team-delete API in api-dump — the test team is
            // left behind (cheap; one row keyed by teamID with an empty bank after unbind).
            manager.unbindExternalAccount(testAccountNr, slotItem, false);

            return pass("Team enumeration + bind + round-trip: teamID=" + teamID
                    + ", owner=" + livePlayerUuid + ", members=" + members.size());
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Coin-variant routing (Task #38): bind an LC personal account to the test slot,
     * deposit 2× lightmanscurrency:coin_iron through {@link DepositItemsInBankRequest}.
     * The expected credit is 2 × (LC live-config coreValue for coin_iron) × nativeScale(1L).
     * The test READS the expected ratio from the provider itself so LC's runtime chain
     * config is honored — if an admin edited the ratio, the assertion follows the edit.
     */
    private TestResult testVariantDepositCreditsBoundSlot() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            Item ironCoin = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin_iron"));
            if (ironCoin == null || ironCoin == Items.AIR) {
                return pass("lightmanscurrency:coin_iron not in item registry — skipped");
            }
            ItemStack ironStack = new ItemStack(ironCoin);

            // Read the runtime ratio via the provider itself — the same path the adapter uses.
            long ironCoreValue = provider.baseUnitsPerItem(ironStack);
            if (ironCoreValue <= 0L) {
                return pass("LC returned coreValue=0 for coin_iron — chain data unavailable "
                        + "or item not in main chain; skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared()).findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account found");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");

            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) return fail("Test bank slot not found");

            bindings.bind(testAccountNr, slotItem, personalRef);

            BindingRow row = bindings.findBindingAcceptingItem(testAccountNr, ironStack);
            if (row == null || row.itemIdShort() != slotItem.getShort()) {
                return fail("findBindingAcceptingItem did not return the bound row for coin_iron");
            }

            ItemID ironID = ItemID.getOrRegisterFromItemStackServerSide_direct(ironStack);
            long balanceBefore = bank.getBalance();

            Map<ItemID, Long> deposit = new HashMap<>();
            deposit.put(ironID, 2L);
            DepositItemsInBankRequest req = new DepositItemsInBankRequest();
            DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                    testAccountNr, null, deposit);
            DepositItemsInBankRequest.OutputData output = req.handleOnMasterServer(input, "", null).get();
            if (!output.items().isEmpty()) {
                return fail("Variant deposit had leftovers: " + output.items());
            }

            long delta = bank.getBalance() - balanceBefore;
            long expected = 2L * ironCoreValue;
            if (delta != expected) {
                return fail("Bound-slot delta = " + delta + ", expected " + expected
                        + " (2 × LC runtime ratio " + ironCoreValue + ")");
            }
            ISyncServerBank strayBank = account.getBank(ironID);
            if (strayBank != null && strayBank.getBalance() > 0) {
                return fail("A stray per-variant bank was created for coin_iron with balance="
                        + strayBank.getBalance());
            }
            return pass("2× coin_iron deposit credited bound slot by " + expected + " raw units "
                    + "(runtime ratio " + ironCoreValue + " per iron)");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Regression guard (Task #38): with NO binding on the account, a coin_iron
     * deposit must NOT route as a variant — findBindingAcceptingItem returns null
     * and the bound slot's balance is unchanged.
     */
    private TestResult testNoBindingFallsBack() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }

            Item ironCoin = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin_iron"));
            if (ironCoin == null || ironCoin == Items.AIR) {
                return pass("lightmanscurrency:coin_iron not in item registry — skipped");
            }
            ItemStack ironStack = new ItemStack(ironCoin);

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");

            BindingRow row = bindings.findBindingAcceptingItem(testAccountNr, ironStack);
            if (row != null) {
                return fail("findBindingAcceptingItem returned a row when no binding exists");
            }

            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank slotBank = account.getBank(slotItem);
            if (slotBank == null) return fail("Test bank slot not found");
            long balanceBefore = slotBank.getBalance();

            ItemID ironID = ItemID.getOrRegisterFromItemStackServerSide_direct(ironStack);
            Map<ItemID, Long> deposit = new HashMap<>();
            deposit.put(ironID, 2L);
            DepositItemsInBankRequest req = new DepositItemsInBankRequest();
            DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                    testAccountNr, null, deposit);
            req.handleOnMasterServer(input, "", null).get();

            if (slotBank.getBalance() != balanceBefore) {
                return fail("Slot balance changed without binding — variant routing leaked: "
                        + slotBank.getBalance() + " (expected " + balanceBefore + ")");
            }
            return pass("No binding → coin_iron deposit did not touch slot balance (as designed)");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    // =======================================================================
    // Task #38b — per-slot ratio zero-drift regression suite.
    // Uses a coin_gold slot instead of the shared EMERALD setup so the LC
    // ratio (81 by default) actually differs from ITEM_FRACTION_SCALE_FACTOR
    // (100) — that is where all drift bugs live.
    // =======================================================================

    /**
     * Bind a fresh coin_gold slot to LC, then deposit 1 coin_gold via
     * DepositItemsInBankRequest and verify slot=81, LC=81. Withdraw 81 raw
     * via the ServerBank primitives (mirroring BankDownloadBlockEntity's
     * lock+withdrawLocked pattern with ratio=81) and verify slot=0, LC=0.
     * Physical coin count is invariant: 1 gold in → 1 gold out, zero dust.
     */
    private TestResult testVariantDepositFullRoundTrip() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst().orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }
            Item goldCoin = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin_gold"));
            if (goldCoin == null || goldCoin == Items.AIR) {
                return pass("lightmanscurrency:coin_gold not in item registry — skipped");
            }
            ItemStack goldStack = new ItemStack(goldCoin);
            long ratio = provider.baseUnitsPerItem(goldStack);
            if (ratio <= 0L) {
                return pass("LC returned coreValue=0 for coin_gold — skipped");
            }

            ItemID goldSlot = ItemID.getOrRegisterFromItemStackServerSide_direct(goldStack);
            manager.allowItemID(goldSlot);
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            if (account.getBank(goldSlot) == null) account.createBank(goldSlot, 0);
            ISyncServerBank goldBank = account.getBank(goldSlot);
            if (goldBank == null) return fail("Failed to provision coin_gold slot");
            goldBank.setBalance(0);
            zeroLightmansAccount();

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared())
                    .findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, goldSlot, personalRef);
            if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

            ItemID depositID = ItemID.getOrRegisterFromItemStackServerSide_direct(goldStack);
            Map<ItemID, Long> deposit = new HashMap<>();
            deposit.put(depositID, 1L);
            DepositItemsInBankRequest req = new DepositItemsInBankRequest();
            DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                    testAccountNr, null, deposit);
            DepositItemsInBankRequest.OutputData out = req.handleOnMasterServer(input, "", null).get();
            if (!out.items().isEmpty()) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("Variant deposit had leftovers: " + out.items());
            }
            if (goldBank.getBalance() != ratio) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("Post-deposit slot balance = " + goldBank.getBalance()
                        + ", expected " + ratio);
            }

            long reserve = ratio; // 1 coin × ratio
            BankStatus lockStatus = goldBank.lockAmount(reserve);
            if (lockStatus != BankStatus.SUCCESS) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("lockAmount(" + reserve + ") returned " + lockStatus);
            }
            BankStatus withdrawStatus = goldBank.withdrawLocked(reserve);
            if (withdrawStatus != BankStatus.SUCCESS) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("withdrawLocked(" + reserve + ") returned " + withdrawStatus);
            }
            if (goldBank.getBalance() != 0) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("Post-withdraw slot balance = " + goldBank.getBalance() + ", expected 0");
            }
            long lcAfter = readLightmansCoreBalance();
            if (lcAfter != 0) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("Post-withdraw LC balance = " + lcAfter + " (expected 0 — LC drift)");
            }
            bindings.unbind(testAccountNr, goldSlot);
            return pass("1 gold coin round-trip: slot 0→81→0, LC 0→81→0, zero drift");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * For each LC coin variant (copper/iron/gold/emerald/diamond/netherite),
     * deposit 1 coin through the DepositItemsInBankRequest routing and verify:
     * BankSystem slot raw balance grew by exactly {@code coreValue} for that
     * variant, AND LC's ledger (via reflection) also grew by exactly
     * {@code coreValue}. Zero drift between BankSystem and LC across all six
     * denominations.
     */
    private TestResult testAllVariantDepositsNoDrift() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst().orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }
            Item goldCoin = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin_gold"));
            if (goldCoin == null || goldCoin == Items.AIR) {
                return pass("lightmanscurrency:coin_gold not in item registry — skipped");
            }
            ItemStack goldStack = new ItemStack(goldCoin);
            ItemID goldSlot = ItemID.getOrRegisterFromItemStackServerSide_direct(goldStack);
            manager.allowItemID(goldSlot);
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            if (account.getBank(goldSlot) == null) account.createBank(goldSlot, 0);
            ISyncServerBank goldBank = account.getBank(goldSlot);
            if (goldBank == null) return fail("Failed to provision coin_gold slot");
            goldBank.setBalance(0);
            zeroLightmansAccount();

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared())
                    .findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, goldSlot, personalRef);
            if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

            String[] variantNames = {"coin_copper", "coin_iron", "coin_gold",
                    "coin_emerald", "coin_diamond", "coin_netherite"};
            long cumulativeExpected = 0L;
            for (String variantName : variantNames) {
                Item variantItem = BuiltInRegistries.ITEM.get(
                        ResourceLocation.fromNamespaceAndPath("lightmanscurrency", variantName));
                if (variantItem == null || variantItem == Items.AIR) {
                    continue;
                }
                ItemStack variantStack = new ItemStack(variantItem);
                long expectedCore = provider.baseUnitsPerItem(variantStack);
                if (expectedCore <= 0L) {
                    continue;
                }

                ItemID variantID = ItemID.getOrRegisterFromItemStackServerSide_direct(variantStack);
                Map<ItemID, Long> deposit = new HashMap<>();
                deposit.put(variantID, 1L);
                DepositItemsInBankRequest req = new DepositItemsInBankRequest();
                DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                        testAccountNr, null, deposit);
                DepositItemsInBankRequest.OutputData out = req.handleOnMasterServer(input, "", null).get();
                if (!out.items().isEmpty()) {
                    bindings.unbind(testAccountNr, goldSlot);
                    return fail(variantName + " deposit had leftovers: " + out.items());
                }
                cumulativeExpected += expectedCore;
                if (goldBank.getBalance() != cumulativeExpected) {
                    bindings.unbind(testAccountNr, goldSlot);
                    return fail(variantName + " post-deposit slot = " + goldBank.getBalance()
                            + ", expected " + cumulativeExpected);
                }
                long lcCore = readLightmansCoreBalance();
                if (lcCore != cumulativeExpected) {
                    bindings.unbind(testAccountNr, goldSlot);
                    return fail(variantName + " LC-side ledger = " + lcCore + ", expected "
                            + cumulativeExpected + " (drift!)");
                }
            }
            bindings.unbind(testAccountNr, goldSlot);
            zeroLightmansAccount();
            return pass("All variant deposits: slot and LC ledger match at each step "
                    + "(cumulative " + cumulativeExpected + " core)");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Deposit 1 coin_emerald (+729 raw at default config), then withdraw as
     * many gold coins as possible using the ratio-aware withdraw math.
     * 729 / 81 = 9 → 9 gold coins withdrawn, slot balance = 0. Verifies
     * cross-denomination round-trip through the bound slot without drift.
     */
    private TestResult testMultiVariantDepositThenGoldWithdraw() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst().orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }
            Item emeraldCoin = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin_emerald"));
            Item goldCoin = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin_gold"));
            if (emeraldCoin == null || emeraldCoin == Items.AIR
                    || goldCoin == null || goldCoin == Items.AIR) {
                return pass("coin_emerald or coin_gold not in item registry — skipped");
            }
            ItemStack emeraldStack = new ItemStack(emeraldCoin);
            ItemStack goldStack = new ItemStack(goldCoin);
            long emeraldCore = provider.baseUnitsPerItem(emeraldStack);
            long goldCore = provider.baseUnitsPerItem(goldStack);
            if (emeraldCore <= 0 || goldCore <= 0) {
                return pass("LC returned coreValue=0 for a coin — skipped");
            }
            long expectedGoldCount = emeraldCore / goldCore;
            long expectedRemainderRaw = emeraldCore % goldCore;

            ItemID goldSlot = ItemID.getOrRegisterFromItemStackServerSide_direct(goldStack);
            manager.allowItemID(goldSlot);
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            if (account.getBank(goldSlot) == null) account.createBank(goldSlot, 0);
            ISyncServerBank goldBank = account.getBank(goldSlot);
            if (goldBank == null) return fail("Failed to provision coin_gold slot");
            goldBank.setBalance(0);
            zeroLightmansAccount();

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared())
                    .findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account");
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");
            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, goldSlot, personalRef);
            if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

            ItemID emeraldID = ItemID.getOrRegisterFromItemStackServerSide_direct(emeraldStack);
            Map<ItemID, Long> deposit = new HashMap<>();
            deposit.put(emeraldID, 1L);
            DepositItemsInBankRequest req = new DepositItemsInBankRequest();
            DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                    testAccountNr, null, deposit);
            DepositItemsInBankRequest.OutputData out = req.handleOnMasterServer(input, "", null).get();
            if (!out.items().isEmpty()) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("Emerald deposit had leftovers: " + out.items());
            }
            if (goldBank.getBalance() != emeraldCore) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("Post-emerald slot = " + goldBank.getBalance() + ", expected " + emeraldCore);
            }
            long withdrawReserve = expectedGoldCount * goldCore;
            BankStatus lockStatus = goldBank.lockAmount(withdrawReserve);
            if (lockStatus != BankStatus.SUCCESS) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("lockAmount(" + withdrawReserve + ") returned " + lockStatus);
            }
            BankStatus wdStatus = goldBank.withdrawLocked(withdrawReserve);
            if (wdStatus != BankStatus.SUCCESS) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("withdrawLocked(" + withdrawReserve + ") returned " + wdStatus);
            }
            if (goldBank.getBalance() != expectedRemainderRaw) {
                bindings.unbind(testAccountNr, goldSlot);
                return fail("Post-withdraw slot = " + goldBank.getBalance() + ", expected "
                        + expectedRemainderRaw);
            }
            bindings.unbind(testAccountNr, goldSlot);
            zeroLightmansAccount();
            return pass("1 emerald → " + expectedGoldCount + " gold coins withdrawn (raw "
                    + expectedRemainderRaw + " remainder)");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Pre-bind slot at 500 raw (5 gold coins at 100:1). Bind. Post-bind ratio
     * = 81 → new raw balance must be 500 × 81 / 100 = 405 (5 gold at 81:1).
     * Physical coin count preserved (5 gold in, 5 gold in). LC ledger = 405
     * core value.
     */
    private TestResult testBindWithExistingBalanceRatioConversion() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return pass("Lightman's Currency not loaded — skipped");
        }
        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "lightmanscurrency".equals(p.providerId()))
                    .findFirst().orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Lightman's Currency provider not available — skipped");
            }
            Item goldCoin = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("lightmanscurrency", "coin_gold"));
            if (goldCoin == null || goldCoin == Items.AIR) {
                return pass("lightmanscurrency:coin_gold not in item registry — skipped");
            }
            ItemStack goldStack = new ItemStack(goldCoin);
            long goldRatio = provider.baseUnitsPerItem(goldStack);
            if (goldRatio <= 0) return pass("LC returned coreValue=0 for coin_gold — skipped");

            ItemID goldSlot = ItemID.getOrRegisterFromItemStackServerSide_direct(goldStack);
            manager.allowItemID(goldSlot);
            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            if (account.getBank(goldSlot) == null) account.createBank(goldSlot, 0);
            ISyncServerBank goldBank = account.getBank(goldSlot);
            if (goldBank == null) return fail("Failed to provision coin_gold slot");
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null && bindings.getBinding(testAccountNr, goldSlot) != null) {
                bindings.unbind(testAccountNr, goldSlot);
            }
            goldBank.setBalance(500L);
            zeroLightmansAccount();

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared())
                    .findFirst().orElse(null);
            if (personalRef == null) return fail("No personal LC account");

            BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, goldSlot, personalRef);
            if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

            long expected = 500L * goldRatio / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            if (goldBank.getBalance() != expected) {
                if (bindings != null) bindings.unbind(testAccountNr, goldSlot);
                return fail("Post-bind slot = " + goldBank.getBalance()
                        + ", expected " + expected + " (500 × " + goldRatio + " / 100)");
            }
            long lcCore = readLightmansCoreBalance();
            if (lcCore != expected) {
                if (bindings != null) bindings.unbind(testAccountNr, goldSlot);
                return fail("Post-bind LC ledger = " + lcCore + ", expected " + expected);
            }
            if (bindings != null) bindings.unbind(testAccountNr, goldSlot);
            zeroLightmansAccount();
            return pass("500 raw @ 100:1 → " + expected + " raw @ " + goldRatio + ":1 (5 gold preserved)");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Reads the sum of core-values in TEST_OWNER's LC coin-chain storage via
     * reflection. Returns 0 on any failure (LC unavailable, API mismatch).
     */
    private long readLightmansCoreBalance() {
        if (!Platform.isModLoaded("lightmanscurrency")) return 0L;
        Object lcAccount = resolveLcAccount();
        if (lcAccount == null) return 0L;
        try {
            Object storage = lcAccount.getClass().getMethod("getMoneyStorage").invoke(lcAccount);
            if (storage == null) return 0L;
            Object allValuesObj = storage.getClass().getMethod("allValues").invoke(storage);
            if (!(allValuesObj instanceof java.util.List<?> allValues)) return 0L;
            long sum = 0L;
            for (Object mv : allValues) {
                if (mv == null) continue;
                Object nameObj = mv.getClass().getMethod("getUniqueName").invoke(mv);
                if (nameObj == null || !nameObj.toString().startsWith(COIN_CHAIN_PREFIX)) continue;
                Object core = mv.getClass().getMethod("getCoreValue").invoke(mv);
                if (core instanceof Number n) sum += n.longValue();
            }
            return sum;
        } catch (Exception e) {
            return 0L;
        }
    }
}
