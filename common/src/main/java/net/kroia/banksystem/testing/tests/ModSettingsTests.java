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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the "Mod Settings" admin-screen backend logic
 * ({@link ModSettingsRequest} + {@link BankSystemModSettings}):
 * <ul>
 *   <li>the SettingsStore JSON round-trip used as the request's wire format
 *       (including the List&lt;String&gt; component lists and the Integrations
 *       refresh/format settings),</li>
 *   <li>the {@link ModSettingsRequest#sanitize} validation bounds and
 *       normalization rules, and</li>
 *   <li>graceful handling of partial payloads (missing groups keep their values),</li>
 *   <li>the one-time Placeholder/Utilities to Integrations file migration (Task #119).</li>
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
        addTest("migrate_integrations_old_file", this::test_migrateIntegrationsOldFile);
        addTest("migrate_integrations_already_migrated", this::test_migrateIntegrationsAlreadyMigrated);
        addTest("migrate_integrations_no_old_keys", this::test_migrateIntegrationsNoOldKeys);
        addTest("load_clamps_hand_edited_values", this::test_loadClampsHandEditedValues);
        addTest("request_needs_admin_and_op", this::test_requestNeedsAdminAndOp);
    }

    /**
     * BS-221: a GET needs the BankSystem admin flag AND operator level 2 on this server. Uses the
     * first online player: without the flag it is refused; with the flag it passes exactly when the
     * player has op level 2 (the harness cannot change a real player's op level, so a non-op player
     * cell covers the "admin flag without op" case and an op cell the positive case).
     */
    private TestResult test_requestNeedsAdminAndOp() {
        if (getServer() == null) return skip("no server");
        net.kroia.banksystem.api.bankmanager.IBankManager bm = net.kroia.banksystem.BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null || net.kroia.banksystem.BankSystemModBackend.getInstances_forTesting().isSlaveServer)
            return skip("no master server");
        List<net.minecraft.server.level.ServerPlayer> players = getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return skip("no online player");
        net.minecraft.server.level.ServerPlayer player = players.get(0);
        java.util.UUID uuid = player.getUUID();
        ModSettingsRequest.InputData get = new ModSettingsRequest.InputData(ModSettingsRequest.Action.GET, "");
        boolean before = bm.getSync().isBanksystemAdmin(uuid);
        try {
            bm.getSync().setBanksystemAdminMode(uuid, false);
            ModSettingsRequest.OutputData out = net.kroia.banksystem.networking.BankSystemNetworking.MOD_SETTINGS_REQUEST.handleOnMasterServer(get, "", uuid).join();
            TestResult r = assertFalse("no admin flag: refused", out.success());
            if (!r.passed()) return r;
            r = assertTrue("refusal names both requirements", out.message().contains("operator level 2"));
            if (!r.passed()) return r;

            bm.getSync().setBanksystemAdminMode(uuid, true);
            boolean op = net.kroia.modutilities.PermissionCompat.hasPermission(player, ModSettingsRequest.REQUIRED_OP_LEVEL);
            out = net.kroia.banksystem.networking.BankSystemNetworking.MOD_SETTINGS_REQUEST.handleOnMasterServer(get, "", uuid).join();
            return assertEquals("admin flag: allowed iff op level 2 (op=" + op + ")", op, out.success());
        } finally {
            bm.getSync().setBanksystemAdminMode(uuid, before);
        }
    }

    /**
     * Serializing the editable groups to a JSON string (the ModSettingsRequest wire
     * format) and loading it into a second settings instance must reproduce every
     * value, including the List&lt;String&gt; component lists and the Integrations
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
        source.INTEGRATIONS.PLAYER_REFRESH_MS.set(2500);
        source.INTEGRATIONS.MONEY_FORMAT.set("%s €");

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
        r = assertEquals("PLAYER_REFRESH_MS survives round-trip", 2500, (int) target.INTEGRATIONS.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("MONEY_FORMAT survives round-trip", "%s €", target.INTEGRATIONS.MONEY_FORMAT.get());
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
        settings.INTEGRATIONS.PLAYER_REFRESH_MS.set(1);
        settings.INTEGRATIONS.SERVER_REFRESH_MS.set(-5);
        settings.INTEGRATIONS.TOP_COUNT.set(-3);

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
                ModSettingsRequest.MIN_PLACEHOLDER_REFRESH_MS, (int) settings.INTEGRATIONS.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("server placeholder refresh clamped to minimum",
                ModSettingsRequest.MIN_PLACEHOLDER_REFRESH_MS, (int) settings.INTEGRATIONS.SERVER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("placeholder top count clamped to 0",
                ModSettingsRequest.MIN_PLACEHOLDER_TOP_COUNT, (int) settings.INTEGRATIONS.TOP_COUNT.get());
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
        settings.INTEGRATIONS.PLAYER_REFRESH_MS.set(Integer.MAX_VALUE);
        settings.INTEGRATIONS.SERVER_REFRESH_MS.set(Integer.MAX_VALUE);
        settings.INTEGRATIONS.TOP_COUNT.set(1000);

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
                ModSettingsRequest.MAX_PLACEHOLDER_REFRESH_MS, (int) settings.INTEGRATIONS.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("server placeholder refresh clamped to maximum",
                ModSettingsRequest.MAX_PLACEHOLDER_REFRESH_MS, (int) settings.INTEGRATIONS.SERVER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("placeholder top count clamped to 100",
                ModSettingsRequest.MAX_PLACEHOLDER_TOP_COUNT, (int) settings.INTEGRATIONS.TOP_COUNT.get());
        if (!r.passed()) return r;
        return pass("sanitize() clamps all upper bounds");
    }

    /**
     * The component-id lists must be normalized (entries trimmed, blanks dropped,
     * duplicates removed, null → empty list) and null values of Booleans and
     * Integrations settings must fall back to the setting's default.
     */
    private TestResult test_sanitizeNormalizesListsAndPlaceholders() {
        BankSystemModSettings settings = new BankSystemModSettings();
        settings.BANK.ADDITIONAL_VOLATILE_COMPONENTS.set(new ArrayList<>(List.of(
                "  tfc:food  ", "", "tfc:food", "   ", "somemod:decay")));
        settings.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.set(null);
        settings.BANK.CONFIRM_ITEMID_MERGE.set(null);
        settings.INTEGRATIONS.PLAYER_REFRESH_MS.set(null);
        settings.INTEGRATIONS.SERVER_REFRESH_MS.set(5000);
        settings.INTEGRATIONS.TOP_COUNT.set(null);
        settings.INTEGRATIONS.MONEY_FORMAT.set(null);
        settings.INTEGRATIONS.DISCORD_ENABLED.set(null);

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
                settings.INTEGRATIONS.PLAYER_REFRESH_MS.getDefaultValue(), settings.INTEGRATIONS.PLAYER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("valid SERVER_REFRESH_MS untouched",
                5000, (int) settings.INTEGRATIONS.SERVER_REFRESH_MS.get());
        if (!r.passed()) return r;
        r = assertEquals("null TOP_COUNT falls back to its default",
                settings.INTEGRATIONS.TOP_COUNT.getDefaultValue(), settings.INTEGRATIONS.TOP_COUNT.get());
        if (!r.passed()) return r;
        r = assertEquals("null MONEY_FORMAT falls back to its default",
                settings.INTEGRATIONS.MONEY_FORMAT.getDefaultValue(), settings.INTEGRATIONS.MONEY_FORMAT.get());
        if (!r.passed()) return r;
        r = assertEquals("null DISCORD_ENABLED falls back to its default (false)",
                Boolean.FALSE, settings.INTEGRATIONS.DISCORD_ENABLED.get());
        if (!r.passed()) return r;
        return pass("sanitize() normalizes component lists and placeholder settings");
    }

    /**
     * MONEY_FORMAT must contain "%s": blank values or values without it are reset
     * to the default, valid values are kept exactly as entered (not trimmed).
     */
    private TestResult test_sanitizeMoneyFormat() {
        BankSystemModSettings settings = new BankSystemModSettings();
        String def = settings.INTEGRATIONS.MONEY_FORMAT.getDefaultValue();

        settings.INTEGRATIONS.MONEY_FORMAT.set("EUR");
        ModSettingsRequest.sanitize(settings);
        TestResult r = assertEquals("format without %s reset to default", def, settings.INTEGRATIONS.MONEY_FORMAT.get());
        if (!r.passed()) return r;

        settings.INTEGRATIONS.MONEY_FORMAT.set("   ");
        ModSettingsRequest.sanitize(settings);
        r = assertEquals("blank format reset to default", def, settings.INTEGRATIONS.MONEY_FORMAT.get());
        if (!r.passed()) return r;

        settings.INTEGRATIONS.MONEY_FORMAT.set(" %s € ");
        ModSettingsRequest.sanitize(settings);
        r = assertEquals("valid format kept as-is (not trimmed)", " %s € ", settings.INTEGRATIONS.MONEY_FORMAT.get());
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

    // ---- Task #119: one-time migration into the Integrations group ----

    /** Writes {@code json} to a temp file, loads it like load_globalSettings does, runs {@code body}, deletes the file. */
    private TestResult withSettingsFile(String json, MigrationCase body) {
        Path file = null;
        try {
            file = Files.createTempFile("banksystem_settings_migration", ".json");
            Files.writeString(file, json);
            BankSystemModSettings settings = new BankSystemModSettings();
            new SettingsStore().loadFromFile(settings.getEditableGroups(), file.toFile());
            return body.run(settings, file);
        } catch (Exception e) {
            return fail("Migration test threw " + e);
        } finally {
            try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) {}
        }
    }

    private interface MigrationCase {
        TestResult run(BankSystemModSettings settings, Path file) throws Exception;
    }

    /** A pre-2.2.0 file: Placeholder.* and Utilities.SYNC_* are copied, and a re-save drops the old keys. */
    private TestResult test_migrateIntegrationsOldFile() {
        String json = "{\"Utilities\":{\"SAVE_INTERVAL_SECONDS\":9,\"SYNC_ADMIN_FLAG_WITH_PERMISSION\":true},"
                + "\"Placeholder\":{\"PLAYER_REFRESH_MS\":2500,\"SERVER_REFRESH_MS\":40000,\"TOP_COUNT\":7,\"MONEY_FORMAT\":\"%s EUR\"}}";
        return withSettingsFile(json, (settings, file) -> {
            TestResult r = assertTrue("migration reports copied values", settings.migrateIntegrations(file));
            if (!r.passed()) return r;
            BankSystemModSettings.Integrations in = settings.INTEGRATIONS;
            r = assertEquals("PLAYER_REFRESH_MS copied", 2500, (int) in.PLAYER_REFRESH_MS.get());
            if (!r.passed()) return r;
            r = assertEquals("SERVER_REFRESH_MS copied", 40000, (int) in.SERVER_REFRESH_MS.get());
            if (!r.passed()) return r;
            r = assertEquals("TOP_COUNT copied", 7, (int) in.TOP_COUNT.get());
            if (!r.passed()) return r;
            r = assertEquals("MONEY_FORMAT copied", "%s EUR", in.MONEY_FORMAT.get());
            if (!r.passed()) return r;
            r = assertTrue("SYNC_ADMIN_FLAG_WITH_PERMISSION copied", in.SYNC_ADMIN_FLAG_WITH_PERMISSION.get());
            if (!r.passed()) return r;
            r = assertTrue("new PLACEHOLDERS_ENABLED keeps its default (true)", in.PLACEHOLDERS_ENABLED.get());
            if (!r.passed()) return r;
            r = assertFalse("new DISCORD_ENABLED keeps its default (false)", in.DISCORD_ENABLED.get());
            if (!r.passed()) return r;
            r = assertEquals("unrelated Utilities value still loaded", 9L, (long) settings.UTILITIES.SAVE_INTERVAL_SECONDS.get());
            if (!r.passed()) return r;

            // Re-save like load_globalSettings does: registered groups only.
            new SettingsStore().saveToFile(settings.getEditableGroups(), file.toFile());
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            r = assertTrue("rewritten file has Integrations", root.has("Integrations"));
            if (!r.passed()) return r;
            r = assertFalse("rewritten file has no Placeholder group", root.has("Placeholder"));
            if (!r.passed()) return r;
            r = assertFalse("rewritten file has no Utilities.SYNC_ADMIN_FLAG_WITH_PERMISSION",
                    root.getAsJsonObject("Utilities").has("SYNC_ADMIN_FLAG_WITH_PERMISSION"));
            if (!r.passed()) return r;
            r = assertEquals("rewritten Integrations.TOP_COUNT", 7, root.getAsJsonObject("Integrations").get("TOP_COUNT").getAsInt());
            if (!r.passed()) return r;
            r = assertFalse("second migration is a no-op", settings.migrateIntegrations(file));
            if (!r.passed()) return r;
            return pass("Old Placeholder/Utilities keys migrated into Integrations; re-save drops them");
        });
    }

    /** A file that already has Integrations is left alone, even if stale old keys are still present. */
    private TestResult test_migrateIntegrationsAlreadyMigrated() {
        String json = "{\"Integrations\":{\"TOP_COUNT\":3},\"Placeholder\":{\"TOP_COUNT\":9}}";
        return withSettingsFile(json, (settings, file) -> {
            TestResult r = assertFalse("migration skipped", settings.migrateIntegrations(file));
            if (!r.passed()) return r;
            r = assertEquals("Integrations value wins", 3, (int) settings.INTEGRATIONS.TOP_COUNT.get());
            if (!r.passed()) return r;
            r = assertEquals("file untouched", json, Files.readString(file));
            if (!r.passed()) return r;
            return pass("Already-migrated file left untouched; Integrations wins");
        });
    }

    /** No old keys: nothing to migrate, defaults stay, no save requested. */
    private TestResult test_migrateIntegrationsNoOldKeys() {
        String json = "{\"Utilities\":{\"SAVE_INTERVAL_SECONDS\":9}}";
        return withSettingsFile(json, (settings, file) -> {
            TestResult r = assertFalse("migration reports nothing to do", settings.migrateIntegrations(file));
            if (!r.passed()) return r;
            BankSystemModSettings.Integrations in = settings.INTEGRATIONS;
            r = assertEquals("TOP_COUNT default", in.TOP_COUNT.getDefaultValue(), in.TOP_COUNT.get());
            if (!r.passed()) return r;
            r = assertFalse("SYNC_ADMIN_FLAG_WITH_PERMISSION default (false)", in.SYNC_ADMIN_FLAG_WITH_PERMISSION.get());
            if (!r.passed()) return r;
            r = assertTrue("NUMISMATICS_ENABLED default (true)", in.NUMISMATICS_ENABLED.get());
            if (!r.passed()) return r;
            r = assertTrue("LIGHTMANS_CURRENCY_ENABLED default (true)", in.LIGHTMANS_CURRENCY_ENABLED.get());
            if (!r.passed()) return r;
            r = assertEquals("file untouched", json, Files.readString(file));
            if (!r.passed()) return r;
            return pass("No old keys: defaults kept, nothing to save");
        });
    }

    /** Task #149: a hand-edited settings.json is clamped on load, and each clamped key is reported. */
    private TestResult test_loadClampsHandEditedValues() {
        String json = "{\"Utilities\":{\"SAVE_INTERVAL_SECONDS\":-5},"
                + "\"Integrations\":{\"TOP_COUNT\":500,\"PLAYER_REFRESH_MS\":2500}}";
        return withSettingsFile(json, (settings, file) -> {
            List<String> changes = ModSettingsRequest.sanitizeAndListChanges(settings);
            TestResult r = assertEquals("TOP_COUNT clamped to max",
                    ModSettingsRequest.MAX_PLACEHOLDER_TOP_COUNT, (int) settings.INTEGRATIONS.TOP_COUNT.get());
            if (!r.passed()) return r;
            r = assertEquals("negative save interval clamped to min",
                    ModSettingsRequest.MIN_SAVE_INTERVAL_SECONDS, (long) settings.UTILITIES.SAVE_INTERVAL_SECONDS.get());
            if (!r.passed()) return r;
            r = assertEquals("in-range value kept", 2500, (int) settings.INTEGRATIONS.PLAYER_REFRESH_MS.get());
            if (!r.passed()) return r;
            r = assertEquals("exactly the two clamped keys reported", 2, changes.size());
            if (!r.passed()) return r;
            r = assertTrue("TOP_COUNT change reported", changes.contains("Integrations.TOP_COUNT: 500 -> 100"));
            if (!r.passed()) return r;
            r = assertTrue("save interval change reported", changes.contains("Utilities.SAVE_INTERVAL_SECONDS: -5 -> 1"));
            if (!r.passed()) return r;
            return pass("Hand-edited out-of-range values are clamped on load and reported per key");
        });
    }
}
