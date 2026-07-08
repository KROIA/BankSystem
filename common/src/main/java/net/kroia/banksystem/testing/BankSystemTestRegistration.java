package net.kroia.banksystem.testing;

import net.kroia.modutilities.testing.TestRegistry;
import net.kroia.banksystem.testing.tests.ArithmeticTests;
import net.kroia.banksystem.testing.tests.AsyncForwardingTests;
import net.kroia.banksystem.testing.tests.AsyncMethodAuditTests;
import net.kroia.banksystem.testing.tests.BankAccountTests;
import net.kroia.banksystem.testing.tests.BankManagerTests;
import net.kroia.banksystem.testing.tests.BankPermissionTests;
import net.kroia.banksystem.testing.tests.DatabaseTests;
import net.kroia.banksystem.testing.tests.DepositGateTests;
import net.kroia.banksystem.testing.tests.ExampleTests;
import net.kroia.banksystem.testing.tests.ItemIDIdentityTests;
import net.kroia.banksystem.testing.tests.ItemIDMergeGuardTests;
import net.kroia.banksystem.testing.tests.LifecycleTests;
import net.kroia.banksystem.testing.tests.MultiServerSecurityTests;
import net.kroia.banksystem.testing.tests.NetworkingValidationTests;
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
        TestRegistry.register(new ItemIDIdentityTests());
        TestRegistry.register(new ItemIDMergeGuardTests());
        TestRegistry.register(new DepositGateTests());
        TestRegistry.register(new WithdrawMergeTests());
    }
}
