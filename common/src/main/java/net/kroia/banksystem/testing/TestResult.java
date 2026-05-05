package net.kroia.banksystem.testing;

import org.jetbrains.annotations.Nullable;

public class TestResult {

    public enum Status {
        PASS,
        FAIL,
        ERROR
    }

    private final String testName;
    private final Status status;
    private final @Nullable String message;
    private final @Nullable String expected;
    private final @Nullable String actual;

    private TestResult(String testName, Status status, @Nullable String message, @Nullable String expected, @Nullable String actual) {
        this.testName = testName;
        this.status = status;
        this.message = message;
        this.expected = expected;
        this.actual = actual;
    }

    public static TestResult pass(String testName, @Nullable String message) {
        return new TestResult(testName, Status.PASS, message, null, null);
    }

    public static TestResult fail(String testName, @Nullable String message) {
        return new TestResult(testName, Status.FAIL, message, null, null);
    }

    public static TestResult fail(String testName, @Nullable String message, String expected, String actual) {
        return new TestResult(testName, Status.FAIL, message, expected, actual);
    }

    public static TestResult error(String testName, String message) {
        return new TestResult(testName, Status.ERROR, message, null, null);
    }

    public String getTestName() {
        return testName;
    }

    public Status getStatus() {
        return status;
    }

    public boolean passed() {
        return status == Status.PASS;
    }

    public @Nullable String getMessage() {
        return message;
    }

    public @Nullable String getExpected() {
        return expected;
    }

    public @Nullable String getActual() {
        return actual;
    }
}
