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
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Items;

import java.util.List;
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
 * <b>Scope:</b> Personal accounts only — LC team accounts are not exposed via
 * public API, so no shared-account tests.
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
}
