package net.kroia.banksystem.testing.tests;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.networking.general.ModSettingsRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.setting.SettingsStore;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the "Mod Settings" admin-screen backend logic
 * ({@link ModSettingsRequest} + {@link BankSystemModSettings}):
 * <ul>
 *   <li>the SettingsStore JSON round-trip used as the request's wire format
 *       (including the List&lt;String&gt; component lists and the Placeholder
 *       refresh/format settings),</li>
 *   <li>the {@link ModSettingsRequest#sanitize} validation bounds and
 *       normalization rules, and</li>
 *   <li>graceful handling of partial payloads (missing groups keep their values).</li>
 * </ul>
 * Pure logic tests on fresh settings instances — no running bank manager required.
 */
public class ModSettingsTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.MOD_SETTINGS;
    }

    @Override
    public void registerTests() {
        addTest("json_round_trip", this::test_jsonRoundTrip);
        addTest("sanitize_clamps_lower_bounds", this::test_sanitizeClampsLowerBounds);
        addTest("sanitize_clamps_upper_bounds", this::test_sanitizeClampsUpperBounds);
        addTest("sanitize_normalizes_lists_and_placeholders", this::test_sanitizeNormalizesListsAndPlaceholders);
        addTest("sanitize_money_format", this::test_sanitizeMoneyFormat);
        addTest("partial_payload_keeps_other_values", this::test_partialPayloadKeepsOtherValues);
    }

    /**
     * Serializing the editable groups to a JSON string (the ModSettingsRequest wire
     * format) and loading it into a second settings instance must reproduce every
     * value, including the List&lt;String&gt; component lists and the Placeholder
     * refresh interval / money format.
     */
    private TestResult test_jsonRoundTrip() {
        BankSystemModSettings source = new BankSystemModSettings();
        source.UTILITIES.SAVE_INTERVAL_SECONDS.set(7L);
        source.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.set(15L);
        source.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.set(500L);
        source.UTILITIES.LOGGING_ENABLE_DEBUG.set(true);
        source.UTILITIES.LOGGING_ENABLE_INFO.set(false);
        source.PLAYER.STARTING_BALANCE.set(12345L);
        source.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(40);
        source.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(60);
        source.BANK.ADDITIONAL_VOLATILE_COMPONENTS.set(new ArrayList<>(List.of("tfc:food", "somemod:decay")));
        source.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.set(new ArrayList<>(List.of("tfc:food")));
        source.BANK.CONFIRM_ITEMID_MERGE.set(true);
        source.BANK.CONFIRM_ITEMID_REPAIR.set(true);
        source.PLACEHOLDER.PLAYER_REFRESH_MS.set(2500);
        source.PLACEHOLDER.MONEY_FORMAT.set("%s €");

        SettingsStore store = new SettingsStore();
        String json = store.toJsonString(source.getEditableGroups());

        BankSystemModSettings target = new BankSystemModSettings();
        store.fromJson(target.getEditableGroups(), JsonParser.parseString(json));

        TestResult r = assertEquals("SAVE_INTERVAL_SECONDS survives round-trip", 7L, (long) target.UTILITIES.SAVE_INTERVAL_SECONDS.get());
        if (!r.passed()) return r;
        r = assertEquals("BALANCE_SNAPSHOT_INTERVAL_MINUTES survives round-trip", 15L, (long) target.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.get());
        if (!r.passed()) return r;
        r = assertEquals("BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM survives round-trip", 500L, (long) target.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.get());
        if (!r.passed()) return r;
        r = assertTrue("LOGGING_ENABLE_DEBUG survives round-trip", target.UTILITIES.LOGGING_ENABLE_DEBUG.get());
        if (!r.passed()) return r;
        r = assertFalse("LOGGING_ENABLE_INFO survives round-trip", target.UTILITIES.LOGGING_ENABLE_INFO.get());
        if (!r.passed()) return r;
        r = assertEquals("STARTING_BALANCE survives round-trip", 12345L, (long) target.PLAYER.STARTING_BALANCE.get());
        if (!r.passed()) return r;
        r = assertEquals("BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL survives round-trip", 40, (int) target.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get());
        if (!r.passed()) return r;
        r = assertEquals("BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL survives round-trip", 60, (int) target.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get());
        if (!r.passed()) return r;
        r = assertEquals("ADDITIONAL_VOLATILE_COMPONENTS survives round-trip",
                List.of("tfc:food", "somemod:decay"), target.BANK.ADDITIONAL_VOLATILE_COMPONENTS.get());
        if (!r.passed()) return r;
        r = assertEquals("ADDITIONAL_DEPOSIT_GATED_COMPONENTS survives round-trip",
                List.of("tfc:food"), target.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.get());
        if (!r.passed()) return r;
        r = assertTrue("CONFIRM_ITEMID_MERGE survives round-trip", target.BANK.CONFIRM_ITEMID_MERGE.get());
        if (!r.passed()) return r;
        r = assertTrue("CONFIRM_ITEMID_REPAIR survives round-trip", target.BANK.CONFIRM_ITEMID_REPAIR.get());
        if (!r.passed()) return r;
        r = assertEquals("PLAYER_REFRESH_MS survives round-trip", 2500, (int) target.PLACEHOLDER.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("MONEY_FORMAT survives round-trip", "%s €", target.PLACEHOLDER.MONEY_FORMAT.get());
        if (!r.passed()) return r;
        return pass("SettingsStore JSON round-trip preserves all editable settings");
    }

    /**
     * Values below the documented minimums must be clamped up.
     */
    private TestResult test_sanitizeClampsLowerBounds() {
        BankSystemModSettings settings = new BankSystemModSettings();
        settings.UTILITIES.SAVE_INTERVAL_SECONDS.set(0L);
        settings.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.set(-5L);
        settings.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.set(-1L);
        settings.PLAYER.STARTING_BALANCE.set(-100L);
        settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(-20);
        settings.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(0);
        settings.PLACEHOLDER.PLAYER_REFRESH_MS.set(1);
        settings.PLACEHOLDER.SERVER_REFRESH_MS.set(-5);
        settings.PLACEHOLDER.TOP_COUNT.set(-3);

        ModSettingsRequest.sanitize(settings);

        TestResult r = assertEquals("save interval clamped to minimum",
                ModSettingsRequest.MIN_SAVE_INTERVAL_SECONDS, (long) settings.UTILITIES.SAVE_INTERVAL_SECONDS.get());
        if (!r.passed()) return r;
        r = assertEquals("snapshot interval clamped to minimum (0 = disabled)",
                ModSettingsRequest.MIN_SNAPSHOT_INTERVAL_MINUTES, (long) settings.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.get());
        if (!r.passed()) return r;
        r = assertEquals("snapshot record cap clamped to minimum (0 = unlimited)",
                ModSettingsRequest.MIN_SNAPSHOT_MAX_RECORDS, (long) settings.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.get());
        if (!r.passed()) return r;
        r = assertEquals("negative starting balance clamped to 0",
                ModSettingsRequest.MIN_STARTING_BALANCE, (long) settings.PLAYER.STARTING_BALANCE.get());
        if (!r.passed()) return r;
        r = assertEquals("download block interval clamped to minimum",
                ModSettingsRequest.MIN_TICK_INTERVAL, (int) settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get());
        if (!r.passed()) return r;
        r = assertEquals("upload block interval clamped to minimum",
                ModSettingsRequest.MIN_TICK_INTERVAL, (int) settings.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get());
        if (!r.passed()) return r;
        r = assertEquals("player placeholder refresh clamped to minimum",
                ModSettingsRequest.MIN_PLACEHOLDER_REFRESH_MS, (int) settings.PLACEHOLDER.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("server placeholder refresh clamped to minimum",
                ModSettingsRequest.MIN_PLACEHOLDER_REFRESH_MS, (int) settings.PLACEHOLDER.SERVER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("placeholder top count clamped to 0",
                ModSettingsRequest.MIN_PLACEHOLDER_TOP_COUNT, (int) settings.PLACEHOLDER.TOP_COUNT.get());
        if (!r.passed()) return r;
        return pass("sanitize() clamps all lower bounds");
    }

    /**
     * Values above the documented maximums must be clamped down.
     */
    private TestResult test_sanitizeClampsUpperBounds() {
        BankSystemModSettings settings = new BankSystemModSettings();
        settings.UTILITIES.SAVE_INTERVAL_SECONDS.set(Long.MAX_VALUE);
        settings.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.set(Long.MAX_VALUE);
        settings.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.set(Long.MAX_VALUE);
        settings.PLAYER.STARTING_BALANCE.set(Long.MAX_VALUE);
        settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(Integer.MAX_VALUE);
        settings.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(Integer.MAX_VALUE);
        settings.PLACEHOLDER.PLAYER_REFRESH_MS.set(Integer.MAX_VALUE);
        settings.PLACEHOLDER.SERVER_REFRESH_MS.set(Integer.MAX_VALUE);
        settings.PLACEHOLDER.TOP_COUNT.set(1000);

        ModSettingsRequest.sanitize(settings);

        TestResult r = assertEquals("save interval clamped to maximum",
                ModSettingsRequest.MAX_SAVE_INTERVAL_SECONDS, (long) settings.UTILITIES.SAVE_INTERVAL_SECONDS.get());
        if (!r.passed()) return r;
        r = assertEquals("snapshot interval clamped to maximum",
                ModSettingsRequest.MAX_SNAPSHOT_INTERVAL_MINUTES, (long) settings.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.get());
        if (!r.passed()) return r;
        r = assertEquals("snapshot record cap clamped to maximum",
                ModSettingsRequest.MAX_SNAPSHOT_MAX_RECORDS, (long) settings.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.get());
        if (!r.passed()) return r;
        r = assertEquals("starting balance clamped to maximum",
                ModSettingsRequest.MAX_STARTING_BALANCE, (long) settings.PLAYER.STARTING_BALANCE.get());
        if (!r.passed()) return r;
        r = assertEquals("download block interval clamped to maximum",
                ModSettingsRequest.MAX_TICK_INTERVAL, (int) settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get());
        if (!r.passed()) return r;
        r = assertEquals("upload block interval clamped to maximum",
                ModSettingsRequest.MAX_TICK_INTERVAL, (int) settings.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get());
        if (!r.passed()) return r;
        r = assertEquals("player placeholder refresh clamped to maximum",
                ModSettingsRequest.MAX_PLACEHOLDER_REFRESH_MS, (int) settings.PLACEHOLDER.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("server placeholder refresh clamped to maximum",
                ModSettingsRequest.MAX_PLACEHOLDER_REFRESH_MS, (int) settings.PLACEHOLDER.SERVER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("placeholder top count clamped to 100",
                ModSettingsRequest.MAX_PLACEHOLDER_TOP_COUNT, (int) settings.PLACEHOLDER.TOP_COUNT.get());
        if (!r.passed()) return r;
        return pass("sanitize() clamps all upper bounds");
    }

    /**
     * The component-id lists must be normalized (entries trimmed, blanks dropped,
     * duplicates removed, null → empty list) and null values of Booleans and
     * Placeholder settings must fall back to the setting's default.
     */
    private TestResult test_sanitizeNormalizesListsAndPlaceholders() {
        BankSystemModSettings settings = new BankSystemModSettings();
        settings.BANK.ADDITIONAL_VOLATILE_COMPONENTS.set(new ArrayList<>(List.of(
                "  tfc:food  ", "", "tfc:food", "   ", "somemod:decay")));
        settings.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.set(null);
        settings.BANK.CONFIRM_ITEMID_MERGE.set(null);
        settings.PLACEHOLDER.PLAYER_REFRESH_MS.set(null);
        settings.PLACEHOLDER.SERVER_REFRESH_MS.set(5000);
        settings.PLACEHOLDER.TOP_COUNT.set(null);
        settings.PLACEHOLDER.MONEY_FORMAT.set(null);

        ModSettingsRequest.sanitize(settings);

        TestResult r = assertEquals("volatile list trimmed, blanks dropped, deduped (order preserved)",
                List.of("tfc:food", "somemod:decay"), settings.BANK.ADDITIONAL_VOLATILE_COMPONENTS.get());
        if (!r.passed()) return r;
        r = assertEquals("null gated list becomes empty list",
                List.of(), settings.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.get());
        if (!r.passed()) return r;
        r = assertEquals("null CONFIRM_ITEMID_MERGE falls back to its default (false)",
                Boolean.FALSE, settings.BANK.CONFIRM_ITEMID_MERGE.get());
        if (!r.passed()) return r;
        r = assertEquals("null PLAYER_REFRESH_MS falls back to its default",
                settings.PLACEHOLDER.PLAYER_REFRESH_MS.getDefaultValue(), settings.PLACEHOLDER.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("valid SERVER_REFRESH_MS untouched",
                5000, (int) settings.PLACEHOLDER.SERVER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("null TOP_COUNT falls back to its default",
                settings.PLACEHOLDER.TOP_COUNT.getDefaultValue(), settings.PLACEHOLDER.TOP_COUNT.get());
        if (!r.passed()) return r;
        r = assertEquals("null MONEY_FORMAT falls back to its default",
                settings.PLACEHOLDER.MONEY_FORMAT.getDefaultValue(), settings.PLACEHOLDER.MONEY_FORMAT.get());
        if (!r.passed()) return r;
        return pass("sanitize() normalizes component lists and placeholder settings");
    }

    /**
     * MONEY_FORMAT must contain "%s": blank values or values without it are reset
     * to the default, valid values are kept exactly as entered (not trimmed).
     */
    private TestResult test_sanitizeMoneyFormat() {
        BankSystemModSettings settings = new BankSystemModSettings();
        String def = settings.PLACEHOLDER.MONEY_FORMAT.getDefaultValue();

        settings.PLACEHOLDER.MONEY_FORMAT.set("EUR");
        ModSettingsRequest.sanitize(settings);
        TestResult r = assertEquals("format without %s reset to default", def, settings.PLACEHOLDER.MONEY_FORMAT.get());
        if (!r.passed()) return r;

        settings.PLACEHOLDER.MONEY_FORMAT.set("   ");
        ModSettingsRequest.sanitize(settings);
        r = assertEquals("blank format reset to default", def, settings.PLACEHOLDER.MONEY_FORMAT.get());
        if (!r.passed()) return r;

        settings.PLACEHOLDER.MONEY_FORMAT.set(" %s € ");
        ModSettingsRequest.sanitize(settings);
        r = assertEquals("valid format kept as-is (not trimmed)", " %s € ", settings.PLACEHOLDER.MONEY_FORMAT.get());
        if (!r.passed()) return r;
        return pass("sanitize() validates MONEY_FORMAT");
    }

    /**
     * A payload that only contains one group (as SettingsStore.fromJson allows)
     * must not reset the settings of the other groups.
     */
    private TestResult test_partialPayloadKeepsOtherValues() {
        BankSystemModSettings settings = new BankSystemModSettings();
        settings.UTILITIES.SAVE_INTERVAL_SECONDS.set(99L);
        settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(33);

        // Payload containing ONLY the ServerBank group with ONLY one setting
        JsonObject bankGroup = new JsonObject();
        bankGroup.addProperty("CONFIRM_ITEMID_MERGE", true);
        JsonObject root = new JsonObject();
        root.add("ServerBank", bankGroup);

        new SettingsStore().fromJson(settings.getEditableGroups(), root);

        TestResult r = assertEquals("untouched Utilities value preserved", 99L, (long) settings.UTILITIES.SAVE_INTERVAL_SECONDS.get());
        if (!r.passed()) return r;
        r = assertTrue("ServerBank.CONFIRM_ITEMID_MERGE applied from partial payload", settings.BANK.CONFIRM_ITEMID_MERGE.get());
        if (!r.passed()) return r;
        r = assertEquals("ServerBank setting missing from payload preserved", 33, (int) settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get());
        if (!r.passed()) return r;
        return pass("Partial payloads only change the settings they contain");
    }
}
