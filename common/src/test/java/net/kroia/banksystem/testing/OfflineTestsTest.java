package net.kroia.banksystem.testing;

import net.kroia.modutilities.testing.OfflineTests;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** `gradlew :common:test`: the TestSuites with needsGame() == false, without starting the game. */
class OfflineTestsTest {
    @TestFactory
    Stream<DynamicTest> offline() {
        return OfflineTests.run(BankSystemTestRegistration::register);
    }
}
