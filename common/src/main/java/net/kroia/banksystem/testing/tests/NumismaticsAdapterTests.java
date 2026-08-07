package net.kroia.banksystem.testing.tests;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-game smoke tests for the Numismatics adapter (Task #34, v2.0.5).
 * <p>
 * <b>Server type:</b> MASTER_ONLY — bindings are master-authoritative.
 * <p>
 * <b>Numismatics presence:</b> every test checks {@code Platform.isModLoaded("numismatics")}
 * at the top and returns PASS with an INFO log if the mod is absent. No failures
 * when Numismatics isn't installed.
 *
 * @since 2.0.5
 */
public class NumismaticsAdapterTests extends TestSuite {

    private static final UUID TEST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000034");
    private static final String TEST_OWNER_NAME = "NumismaticsTestOwner";

    private ServerBankManager manager;
    private int testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    private ItemID slotItem;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("numismatics_player_round_trip", this::testPlayerRoundTrip);
        addTest("numismatics_overflow_guard", this::testOverflowGuard);
        addTest("numismatics_blaze_banker_enumeration_and_bind", this::testBlazeBankerEnumerationAndBind);
        // Task #38 — coin-variant routing through the deposit path
        addTest("numismatics_variant_deposit_credits_bound_slot", this::testVariantDepositCreditsBoundSlot);
        addTest("numismatics_no_binding_falls_back", this::testNoBindingFallsBack);
    }

    @Override
    public void setup() {
        IBankManager bankManager = BankSystemMod.getAPI().getServerBankManager();
        if (bankManager == null) return;
        if (!(bankManager.getSync() instanceof ServerBankManager serverManager)) return;
        manager = serverManager;

        if (!manager.userExists(TEST_OWNER)) {
            manager.addUser(new User(TEST_OWNER, TEST_OWNER_NAME, false));
        }

        slotItem = ItemIDManager.registerItemStackServerSide_direct(Items.DIAMOND.getDefaultInstance());
        manager.allowItemID(slotItem);

        IServerBankAccount existing = manager.getBankAccountByName("NumismaticsTestAccount");
        if (existing != null) {
            manager.deleteBankAccount(existing.getAccountNumber());
        }
        IServerBankAccount account = manager.createBankAccount("NumismaticsTestAccount");
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
        // Numismatics external state persists across sessions (backed by the world save).
        // Zero the PLAYER account before each test so absolute-value assertions are stable.
        // Failing to do this makes deposit(100) return 2147483700 when the account has
        // accumulated balance from prior test runs.
        zeroPlayerNumismaticsAccount();
    }

    /**
     * Reflection helper: zeros the TEST_OWNER's Numismatics PLAYER account balance so
     * every test starts from a clean external state. Silently no-ops if Numismatics is
     * absent or its API isn't reachable — the test bodies still skip themselves in that
     * case via {@code Platform.isModLoaded} checks.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void zeroPlayerNumismaticsAccount() {
        if (!dev.architectury.platform.Platform.isModLoaded("numismatics")) return;
        try {
            Class<?> numismaticsClass = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Object bankManager = numismaticsClass.getField("BANK").get(null);
            Class<?> managerClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.GlobalBankManager");
            Class<?> typeClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount$Type");
            Object playerType = Enum.valueOf((Class<Enum>) typeClass, "PLAYER");
            Object account = managerClass.getMethod("getOrCreateAccount", UUID.class, typeClass)
                    .invoke(bankManager, TEST_OWNER, playerType);
            if (account == null) return;
            Class<?> accountClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount");
            // BankAccount exposes setBalance(int); use it to force the balance to 0.
            try {
                accountClass.getMethod("setBalance", int.class).invoke(account, 0);
            } catch (NoSuchMethodException nsme) {
                // Alternative: withdraw everything via subtractFromBalance(int)
                java.lang.reflect.Method getBalance = accountClass.getMethod("getBalance");
                int cur = (int) getBalance.invoke(account);
                if (cur > 0) {
                    accountClass.getMethod("subtractFromBalance", int.class).invoke(account, cur);
                }
            }
        } catch (Exception e) {
            // Best-effort — if the API shape changed, tests will still catch the regression
            // via their absolute-value assertions and report a clear failure.
        }
    }

    /**
     * Smoke test: bind a PLAYER Numismatics account, deposit via BankSystem,
     * verify the balance routes through, withdraw, verify again.
     */
    private TestResult testPlayerRoundTrip() {
        if (!Platform.isModLoaded("numismatics")) {
            return pass("Numismatics not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "numismatics".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);

            if (provider == null || !provider.isAvailable()) {
                return pass("Numismatics provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            if (refs.isEmpty()) {
                return fail("No bindable Numismatics accounts for test owner");
            }

            ExternalAccountRef personalRef = refs.stream()
                    .filter(r -> !r.shared())
                    .findFirst()
                    .orElse(null);

            if (personalRef == null) {
                return fail("No personal Numismatics account found");
            }

            // Bind the PLAYER account to our test BankSystem account's slot
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

            // Withdraw 30
            BankStatus withdrawStatus = bank.withdraw(30);
            if (withdrawStatus != BankStatus.SUCCESS) {
                return fail("Withdraw failed: " + withdrawStatus);
            }

            long balanceAfterWithdraw = bank.getBalance();
            if (balanceAfterWithdraw != 70) {
                return fail("Expected balance 70 after withdraw, got " + balanceAfterWithdraw);
            }

            return pass("Round-trip: bind + deposit + withdraw all consistent");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Verify that deposits that would exceed Integer.MAX_VALUE are refused.
     */
    private TestResult testOverflowGuard() {
        if (!Platform.isModLoaded("numismatics")) {
            return pass("Numismatics not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "numismatics".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);

            if (provider == null || !provider.isAvailable()) {
                return pass("Numismatics provider not available — skipped");
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            if (refs.isEmpty()) {
                return fail("No bindable Numismatics accounts");
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

            // Numismatics stores spurs as a signed int (cap = Integer.MAX_VALUE spurs).
            // BankSystem exposes the balance in BS units where 100 BS units = 1 spur
            // (SCALE_FACTOR = 100 in NumismaticsAccount). To push the account to
            // (MAX_SPURS - 10) we need (MAX_SPURS - 10) * 100 BS units, and to trigger
            // overflow the deposit must be at least 11 spurs = 1100 BS units.
            long nearMaxBSUnits = ((long) (Integer.MAX_VALUE - 10)) * (long) net.kroia.banksystem.BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            bank.setBalance(nearMaxBSUnits);
            long balance = bank.getBalance();
            if (balance != nearMaxBSUnits) {
                return fail("Failed to set balance near max: expected " + nearMaxBSUnits
                        + ", got " + balance);
            }

            // Try to deposit 20 — should fail (would overflow)
            // Deposit 1100 BS units = 11 spurs. External is (MAX-10) spurs, so this would
            // require MAX+1 spurs — Numismatics's 32-bit cap must refuse.
            BankStatus depositStatus = bank.deposit(1100L);
            if (depositStatus == BankStatus.SUCCESS) {
                return fail("Deposit should have failed due to overflow");
            }

            // Balance should be unchanged
            long balanceAfter = bank.getBalance();
            if (balanceAfter != nearMaxBSUnits) {
                return fail("Balance changed after failed overflow deposit: expected "
                        + nearMaxBSUnits + ", got " + balanceAfter);
            }

            return pass("Overflow guard prevented deposit above Numismatics's 32-bit spur cap");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * BLAZE_BANKER shared-account test: create a real BLAZE_BANKER account,
     * populate its trust list, verify enumeration includes it, bind it,
     * perform round-trip deposit/withdraw, sync membership, verify trust list update.
     */
    private TestResult testBlazeBankerEnumerationAndBind() {
        if (!Platform.isModLoaded("numismatics")) {
            return pass("Numismatics not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "numismatics".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);

            if (provider == null || !provider.isAvailable()) {
                return pass("Numismatics provider not available — skipped");
            }

            // Create a real BLAZE_BANKER account via reflection
            Class<?> numismaticsClass = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Object bankManager = numismaticsClass.getField("BANK").get(null);
            Class<?> managerClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.GlobalBankManager");
            Class<?> typeClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount$Type");
            Object blazeBankerType = Enum.valueOf((Class<Enum>) typeClass, "BLAZE_BANKER");

            UUID blazeAccountUuid = UUID.randomUUID();
            Object blazeBankerAccount = managerClass.getMethod("getOrCreateAccount", UUID.class, typeClass)
                    .invoke(bankManager, blazeAccountUuid, blazeBankerType);

            if (blazeBankerAccount == null) {
                return fail("Failed to create BLAZE_BANKER account");
            }

            // Pre-populate trust list with 2 dummy UUIDs
            UUID trustUuidA = UUID.randomUUID();
            UUID trustUuidB = UUID.randomUUID();
            Class<?> accountClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount");
            java.util.function.Consumer<java.util.List<UUID>> populateUpdater = (list) -> {
                list.clear();
                list.add(TEST_OWNER); // Make TEST_OWNER trusted so we can bind it
                list.add(trustUuidA);
                list.add(trustUuidB);
            };
            accountClass.getMethod("updateTrustList", java.util.function.Consumer.class)
                    .invoke(blazeBankerAccount, populateUpdater);

            // List bindable accounts for TEST_OWNER — should include the BLAZE_BANKER
            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef blazeRef = refs.stream()
                    .filter(r -> r.shared() && r.accountKey().equals(blazeAccountUuid.toString()))
                    .findFirst()
                    .orElse(null);

            if (blazeRef == null) {
                return fail("BLAZE_BANKER account not found in listBindableAccounts for TEST_OWNER (trusted player)");
            }
            if (!blazeRef.shared()) {
                return fail("BLAZE_BANKER ref should have shared=true");
            }

            // Bind it to our test account slot
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

            bindings.bind(testAccountNr, slotItem, blazeRef);

            // Round-trip: deposit 100
            BankStatus depositStatus = bank.deposit(100);
            if (depositStatus != BankStatus.SUCCESS) {
                return fail("Deposit to BLAZE_BANKER failed: " + depositStatus);
            }
            if (bank.getBalance() != 100) {
                return fail("Expected balance 100 after deposit, got " + bank.getBalance());
            }

            // Withdraw 50
            BankStatus withdrawStatus = bank.withdraw(50);
            if (withdrawStatus != BankStatus.SUCCESS) {
                return fail("Withdraw from BLAZE_BANKER failed: " + withdrawStatus);
            }
            if (bank.getBalance() != 50) {
                return fail("Expected balance 50 after withdraw, got " + bank.getBalance());
            }

            // Sync membership: keep trustUuidA, add a new trustUuidC, drop trustUuidB
            UUID trustUuidC = UUID.randomUUID();
            java.util.Set<UUID> newMembers = new java.util.HashSet<>();
            newMembers.add(trustUuidA);
            newMembers.add(trustUuidC);

            // Access the ExternalAccount to call syncMembership
            net.kroia.banksystem.api.currency.ExternalAccount extAccount = provider.open(blazeRef);
            if (extAccount == null) {
                return fail("Failed to open BLAZE_BANKER account");
            }
            extAccount.syncMembership(newMembers);

            // Verify trust list now contains exactly {trustUuidA, trustUuidC}
            java.lang.reflect.Field trustListField = accountClass.getDeclaredField("trustList");
            trustListField.setAccessible(true);
            Object trustListObj = trustListField.get(blazeBankerAccount);
            if (!(trustListObj instanceof java.util.List)) {
                return fail("Trust list is not a List");
            }
            @SuppressWarnings("unchecked")
            java.util.List<UUID> finalTrustList = (java.util.List<UUID>) trustListObj;
            if (finalTrustList.size() != 2) {
                return fail("Expected 2 members in trust list after sync, got " + finalTrustList.size());
            }
            if (!finalTrustList.contains(trustUuidA)) {
                return fail("Trust list should contain trustUuidA after sync");
            }
            if (!finalTrustList.contains(trustUuidC)) {
                return fail("Trust list should contain trustUuidC after sync");
            }
            if (finalTrustList.contains(trustUuidB)) {
                return fail("Trust list should NOT contain trustUuidB after sync (it was removed)");
            }
            if (finalTrustList.contains(TEST_OWNER)) {
                return fail("Trust list should NOT contain TEST_OWNER after sync (it was removed)");
            }

            // Unbind + cleanup
            bindings.unbind(testAccountNr, slotItem);
            if (bindings.getBinding(testAccountNr, slotItem) != null) {
                return fail("Unbind left binding row behind");
            }

            return pass("BLAZE_BANKER: enumeration, bind, round-trip, membership sync all validated");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Coin-variant routing (Task #38): with a Numismatics binding on the test slot,
     * depositing 3× numismatics:bevel through {@link DepositItemsInBankRequest}
     * must credit the bound slot by 3 × (BEVEL.value=8) × SCALE_FACTOR=100 = 2400
     * raw units, not create a per-variant bevel bank.
     */
    private TestResult testVariantDepositCreditsBoundSlot() {
        if (!Platform.isModLoaded("numismatics")) {
            return pass("Numismatics not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "numismatics".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Numismatics provider not available — skipped");
            }

            Item bevelItem = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("numismatics", "bevel"));
            if (bevelItem == null || bevelItem == Items.AIR) {
                return pass("numismatics:bevel not in item registry — skipped");
            }

            ItemStack bevelStack = new ItemStack(bevelItem);
            long expectedPerItem = provider.baseUnitsPerItem(bevelStack);
            if (expectedPerItem != 800L) {
                return fail("Expected baseUnitsPerItem(bevel) = 800 (8 spurs × 100 scale), got "
                        + expectedPerItem);
            }

            List<ExternalAccountRef> refs = provider.listBindableAccounts(TEST_OWNER);
            ExternalAccountRef personalRef = refs.stream().filter(r -> !r.shared()).findFirst().orElse(null);
            if (personalRef == null) return fail("No personal Numismatics account found");

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");

            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank == null) return fail("Test bank slot not found");

            bindings.bind(testAccountNr, slotItem, personalRef);

            BindingRow row = bindings.findBindingAcceptingItem(testAccountNr, bevelStack);
            if (row == null || row.itemIdShort() != slotItem.getShort()) {
                return fail("findBindingAcceptingItem did not return the bound row for bevel");
            }

            ItemID bevelID = ItemID.getOrRegisterFromItemStackServerSide_direct(bevelStack);
            long balanceBefore = bank.getBalance();

            Map<ItemID, Long> deposit = new HashMap<>();
            deposit.put(bevelID, 3L);
            DepositItemsInBankRequest req = new DepositItemsInBankRequest();
            DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                    testAccountNr, null, deposit);
            DepositItemsInBankRequest.OutputData output = req.handleOnMasterServer(input, "", null).get();
            if (!output.items().isEmpty()) {
                return fail("Variant deposit had leftovers: " + output.items());
            }

            long delta = bank.getBalance() - balanceBefore;
            long expected = 3L * expectedPerItem;
            if (delta != expected) {
                return fail("Bound-slot balance delta = " + delta + ", expected " + expected);
            }
            // Regression guard: no separate bevel bank should have been created.
            ISyncServerBank strayVariantBank = account.getBank(bevelID);
            if (strayVariantBank != null && strayVariantBank.getBalance() > 0) {
                return fail("A stray per-variant bank was created for bevel with balance="
                        + strayVariantBank.getBalance());
            }

            return pass("3× bevel deposit credited bound slot by " + expected + " raw units");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Regression guard (Task #38): with NO binding on the account, a bevel deposit
     * must NOT route into any slot as a variant — findBindingAcceptingItem returns
     * null and the bound slot's balance is unchanged.
     */
    private TestResult testNoBindingFallsBack() {
        if (!Platform.isModLoaded("numismatics")) {
            return pass("Numismatics not loaded — skipped");
        }
        perTestReset();

        try {
            ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProviders().stream()
                    .filter(p -> "numismatics".equals(p.providerId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !provider.isAvailable()) {
                return pass("Numismatics provider not available — skipped");
            }

            Item bevelItem = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("numismatics", "bevel"));
            if (bevelItem == null || bevelItem == Items.AIR) {
                return pass("numismatics:bevel not in item registry — skipped");
            }
            ItemStack bevelStack = new ItemStack(bevelItem);

            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings == null) return fail("BankAccountBindings unavailable");

            BindingRow row = bindings.findBindingAcceptingItem(testAccountNr, bevelStack);
            if (row != null) {
                return fail("findBindingAcceptingItem returned a row when no binding exists");
            }

            IServerBankAccount account = manager.getBankAccount(testAccountNr);
            if (account == null) return fail("Test account not found");
            ISyncServerBank slotBank = account.getBank(slotItem);
            if (slotBank == null) return fail("Test bank slot not found");
            long balanceBefore = slotBank.getBalance();

            ItemID bevelID = ItemID.getOrRegisterFromItemStackServerSide_direct(bevelStack);
            Map<ItemID, Long> deposit = new HashMap<>();
            deposit.put(bevelID, 3L);
            DepositItemsInBankRequest req = new DepositItemsInBankRequest();
            DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                    testAccountNr, null, deposit);
            // Result may be empty (bevel bank rejected by allowlist → not deposited) or contain
            // the deposit in a bevel bank — either is acceptable; the guard is that the SLOT
            // balance did not move as if the deposit had been routed through the binding.
            req.handleOnMasterServer(input, "", null).get();

            if (slotBank.getBalance() != balanceBefore) {
                return fail("Slot balance changed without binding — variant routing leaked: "
                        + slotBank.getBalance() + " (expected " + balanceBefore + ")");
            }
            return pass("No binding → variant deposit did not touch slot balance (as designed)");
        } catch (Exception e) {
            return fail("Exception: " + e.getMessage());
        }
    }
}
