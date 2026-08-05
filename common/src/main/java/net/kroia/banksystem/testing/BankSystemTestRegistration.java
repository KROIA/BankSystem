package net.kroia.banksystem.testing;

import net.kroia.modutilities.testing.TestRegistry;
import net.kroia.banksystem.testing.tests.ArithmeticTests;
import net.kroia.banksystem.testing.tests.AsyncForwardingTests;
import net.kroia.banksystem.testing.tests.AsyncMethodAuditTests;
import net.kroia.banksystem.testing.tests.BackupCommandTests;
import net.kroia.banksystem.testing.tests.BankAccountTests;
import net.kroia.banksystem.testing.tests.BalanceHistoryTests;
import net.kroia.banksystem.testing.tests.BankChangeStreamPublishTests;
import net.kroia.banksystem.testing.tests.CompanyFounderInvariantTests;
import net.kroia.banksystem.testing.tests.CompanyManagerTests;
import net.kroia.banksystem.testing.tests.BankCraftingMatcherTests;
import net.kroia.banksystem.testing.tests.BankManagerTests;
import net.kroia.banksystem.testing.tests.BankPermissionTests;
import net.kroia.banksystem.testing.tests.ConverterCacheTests;
import net.kroia.banksystem.testing.tests.DatabaseTests;
import net.kroia.banksystem.testing.tests.DepositGateTests;
import net.kroia.banksystem.testing.tests.MoneyDenominationOptimizerTests;
import net.kroia.banksystem.testing.tests.ExampleTests;
import net.kroia.banksystem.testing.tests.ExternalCurrencyBindingTests;
import net.kroia.banksystem.testing.tests.ItemIDCounterTests;
import net.kroia.banksystem.testing.tests.ItemIDFormatAndRepairTests;
import net.kroia.banksystem.testing.tests.ItemIDIdentityTests;
import net.kroia.banksystem.testing.tests.ItemIDMergeGuardTests;
import net.kroia.banksystem.testing.tests.ItemIDSlaveDelegationTests;
import net.kroia.banksystem.testing.tests.LifecycleTests;
import net.kroia.banksystem.testing.tests.ModSettingsTests;
import net.kroia.banksystem.testing.tests.MultiServerSecurityTests;
import net.kroia.banksystem.testing.tests.NetworkingValidationTests;
import net.kroia.banksystem.testing.tests.NumismaticsAdapterTests;
import net.kroia.banksystem.testing.tests.LightmansCurrencyAdapterTests;
import net.kroia.banksystem.testing.tests.SerializationTests;
import net.kroia.banksystem.testing.tests.ServerBankTests;
import net.kroia.banksystem.testing.tests.WithdrawMergeTests;

public class BankSystemTestRegistration {

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        TestRegistry.register(new ExampleTests());
        TestRegistry.register(new BankPermissionTests());
        TestRegistry.register(new ArithmeticTests());
        TestRegistry.register(new ServerBankTests());
        TestRegistry.register(new AsyncMethodAuditTests());
        TestRegistry.register(new BankAccountTests());
        TestRegistry.register(new BankManagerTests());
        TestRegistry.register(new AsyncForwardingTests());
        TestRegistry.register(new NetworkingValidationTests());
        TestRegistry.register(new MultiServerSecurityTests());
        TestRegistry.register(new SerializationTests());
        TestRegistry.register(new LifecycleTests());
        TestRegistry.register(new DatabaseTests());
        // Task #41 (v2.0.7) — sample-on-change dedup + tiered retention
        TestRegistry.register(new BalanceHistoryTests());
        // Task #42 (v2.0.7) — /banksystem backup pause|resume|status|snapshot
        TestRegistry.register(new BackupCommandTests());
        TestRegistry.register(new ItemIDIdentityTests());
        TestRegistry.register(new ItemIDMergeGuardTests());
        TestRegistry.register(new ItemIDSlaveDelegationTests());
        TestRegistry.register(new ItemIDCounterTests());
        TestRegistry.register(new ItemIDFormatAndRepairTests());
        TestRegistry.register(new DepositGateTests());
        TestRegistry.register(new WithdrawMergeTests());
        TestRegistry.register(new BankCraftingMatcherTests());
        TestRegistry.register(new ModSettingsTests());
        TestRegistry.register(new ExternalCurrencyBindingTests());
        TestRegistry.register(new NumismaticsAdapterTests());
        TestRegistry.register(new LightmansCurrencyAdapterTests());
        TestRegistry.register(new BankChangeStreamPublishTests());
        // Task #39 (v2.0.7) — ATM Money Converter tab
        TestRegistry.register(new MoneyDenominationOptimizerTests());
        TestRegistry.register(new ConverterCacheTests());
        // Task #43 (v2.0.8) — Company feature Phase 1 (POJO + manager + founder invariant).
        TestRegistry.register(new CompanyManagerTests());
        TestRegistry.register(new CompanyFounderInvariantTests());
        // Task #43g (v2.0.8) — slave-side ARRS forwarding for /company subcommands.
        TestRegistry.register(new net.kroia.banksystem.testing.tests.CompanyArrsRoundTripTests());
        // Task #44 (v2.0.8) — SQLite Transaction Ledger round-trip + read API.
        TestRegistry.register(new net.kroia.banksystem.testing.tests.TransactionLogManagerTests());
    }
}
