package net.kroia.banksystem.networking.general;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.setting.SettingsStore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * ARRS request used by the master-only "Mod Settings" admin screen
 * ({@code net.kroia.banksystem.screen.custom.ModSettingsScreen}) to read and edit
 * the master server's {@code settings.json} in-game.
 * <p>
 * <b>Actions:</b>
 * <ul>
 *   <li>{@link Action#GET} — returns the current values of all editable settings groups.</li>
 *   <li>{@link Action#SET} — applies the values from the payload, sanitizes them
 *       (see {@link #sanitize}), persists them via the targeted settings-only save
 *       ({@code BankSystemDataHandler.save_globalSettings()} — no bank/ItemID/world
 *       data is written) and returns the confirmed (post-sanitize) state.</li>
 * </ul>
 * <p>
 * <b>Wire format:</b> the payload is the {@link SettingsStore} JSON string of
 * {@link BankSystemModSettings#getEditableGroups()} — the exact same generic
 * serialization (including the custom parsers, e.g. for the Placeholder settings)
 * that is used for the settings.json file itself. This avoids duplicating a
 * structured codec for every setting and automatically covers settings added later.
 * <p>
 * <b>Security:</b> the handler enforces BOTH conditions server-side, regardless of
 * any client-side UI gating:
 * <ul>
 *   <li>the sending player must be a BankSystem admin
 *       ({@code User.isBanksystemAdmin()} — the same gating used by the other
 *       admin requests and the {@code /banksystem manage} flow), and</li>
 *   <li>the HANDLING server must be the MASTER server — only the master's
 *       settings.json is editable in-game. Requests from clients connected to a
 *       slave are auto-routed to the master by ARRS ({@code needsRoutingToMaster()}),
 *       so a slave-side admin edits the MASTER's settings — which is the intended
 *       behavior; this check is defense in depth against misrouted requests.</li>
 * </ul>
 */
public class ModSettingsRequest extends BankSystemGenericRequest<ModSettingsRequest.InputData, ModSettingsRequest.OutputData> {

    // ------------------------------------------------------------------
    // Validation bounds applied by sanitize(). Documented here so UI and
    // server agree on a single source of truth.
    // ------------------------------------------------------------------

    /** Minimum autosave interval in minutes (avoid save-spam every tick). */
    public static final long MIN_SAVE_INTERVAL_MINUTES = 1L;
    /** Maximum autosave interval in minutes (1 day). */
    public static final long MAX_SAVE_INTERVAL_MINUTES = 1440L;

    /** Minimum balance-snapshot interval in minutes; 0 disables snapshots entirely. */
    public static final long MIN_SNAPSHOT_INTERVAL_MINUTES = 0L;
    /** Maximum balance-snapshot interval in minutes (1 week). */
    public static final long MAX_SNAPSHOT_INTERVAL_MINUTES = 10_080L;

    /** Minimum snapshot record cap per item; 0 = unlimited (DB can grow very large). */
    public static final long MIN_SNAPSHOT_MAX_RECORDS = 0L;
    /** Maximum snapshot record cap per item (sanity guard). */
    public static final long MAX_SNAPSHOT_MAX_RECORDS = 10_000_000L;

    /** Minimum starting balance for new players (negative balances are not allowed). */
    public static final long MIN_STARTING_BALANCE = 0L;
    /** Maximum starting balance for new players (sanity guard, in money cents). */
    public static final long MAX_STARTING_BALANCE = 1_000_000_000_000L;

    /** Minimum block-update tick interval (1 tick — every tick). */
    public static final int MIN_TICK_INTERVAL = 1;
    /** Maximum block-update tick interval (1200 ticks = 1 minute). */
    public static final int MAX_TICK_INTERVAL = 1200;

    /** Minimum TAB placeholder refresh rate in milliseconds. */
    public static final int MIN_PLACEHOLDER_REFRESH_MS = 100;
    /** Maximum TAB placeholder refresh rate in milliseconds (1 day). */
    public static final int MAX_PLACEHOLDER_REFRESH_MS = 86_400_000;

    /**
     * The two request actions.
     */
    public enum Action {
        /** Fetch the current settings state (input JSON is ignored/empty). */
        GET,
        /** Apply + persist the settings contained in the input JSON. */
        SET;

        /** Wire codec: encoded as a single byte (the ordinal). */
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> STREAM_CODEC =
                ByteBufCodecs.BYTE.map(b -> Action.values()[b], a -> (byte) a.ordinal())
                        .cast();
    }

    /**
     * Input payload sent from the client to the server.
     *
     * @param action       GET to fetch, SET to apply + persist
     * @param settingsJson SettingsStore JSON of the editable groups (empty string for GET)
     */
    public record InputData(Action action, String settingsJson) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                Action.STREAM_CODEC, InputData::action,
                ByteBufCodecs.STRING_UTF8, InputData::settingsJson,
                InputData::new
        );
    }

    /**
     * Output payload returned from the server to the client.
     *
     * @param success      true if the action succeeded (for SET: values applied AND persisted)
     * @param message      short human-readable failure reason, empty on success
     * @param settingsJson the CONFIRMED current server state (post-sanitize) as
     *                     SettingsStore JSON; empty string when the request was rejected
     *                     before touching the settings (no permission / not master)
     */
    public record OutputData(boolean success, String message, String settingsJson) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, OutputData::success,
                ByteBufCodecs.STRING_UTF8, OutputData::message,
                ByteBufCodecs.STRING_UTF8, OutputData::settingsJson,
                OutputData::new
        );
    }

    @Override
    public String getRequestTypeID() {
        return ModSettingsRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<OutputData> handleOnServer(InputData input, ServerPlayer sender) {
        // Direct client request on a master/single server (on a slave, ARRS routes
        // to the master via needsRoutingToMaster() before this is ever called).
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
        // --- Permission check: BankSystem admin (User.isBanksystemAdmin()), the same
        // gating used by the other admin requests. Enforced here regardless of the
        // client-side button gating.
        if (playerSender == null || !playerIsAdmin(playerSender)) {
            warn("Rejected mod settings " + input.action() + " from " + getPlayerName(playerSender) + ": no permission");
            return CompletableFuture.completedFuture(new OutputData(false, "No permission (BankSystem admin required)", ""));
        }

        // --- Master check: only the master server's settings.json is editable
        // in-game. Requests are normally auto-routed to the master, but enforce it
        // anyway (defense in depth against misconfigured multi-server setups).
        if (BACKEND_INSTANCES.isSlaveServer) {
            warn("Rejected mod settings " + input.action() + " from " + getPlayerName(playerSender) + ": this server is not the master");
            return CompletableFuture.completedFuture(new OutputData(false, "Settings can only be edited on the master server", ""));
        }

        BankSystemModSettings settings = BACKEND_INSTANCES.SERVER_SETTINGS;
        if (settings == null || BACKEND_INSTANCES.SERVER_DATA_HANDLER == null) {
            return CompletableFuture.completedFuture(new OutputData(false, "Server settings not available", ""));
        }

        SettingsStore store = new SettingsStore();

        if (input.action() == Action.GET) {
            return CompletableFuture.completedFuture(
                    new OutputData(true, "", store.toJsonString(settings.getEditableGroups())));
        }

        // ------------------------------ SET ------------------------------
        JsonObject root;
        try {
            root = JsonParser.parseString(input.settingsJson()).getAsJsonObject();
        } catch (Exception e) {
            error("Malformed mod settings payload from " + getPlayerName(playerSender), e);
            return CompletableFuture.completedFuture(new OutputData(false, "Malformed settings payload",
                    store.toJsonString(settings.getEditableGroups())));
        }

        String applyError = null;
        try {
            // Generic apply: settings/groups missing from the payload keep their
            // current values (SettingsStore skips them).
            store.fromJson(settings.getEditableGroups(), root);
        } catch (Exception e) {
            // Partial application is possible (groups are applied in order) —
            // sanitize below and return the ACTUAL server state so the client re-syncs.
            error("Failed to apply mod settings from " + getPlayerName(playerSender), e);
            applyError = "Failed to apply some values: " + e.getMessage();
        }

        // Clamp all numeric values into their documented bounds and normalize the
        // component-id lists / placeholder data.
        sanitize(settings);

        // Persist ONLY settings.json (targeted save via save_globalSettings() —
        // no bank/ItemID/metadata write, and the data handler's load-state save
        // gate still applies: an unreadable settings.json is never overwritten).
        boolean saved = BACKEND_INSTANCES.SERVER_DATA_HANDLER.save_globalSettings();
        if (!saved && applyError == null) {
            applyError = "Applied in memory, but failed to write settings.json";
        }

        // --- Per-group slave propagation decision ---
        // * Utilities (autosave interval, snapshot interval/cap, logging flags):
        //   the autosave/snapshot timers only run on the master (the tick handler is
        //   registered on the master only). The logging flags are also read on slaves,
        //   but from the SLAVE's OWN local settings.json — the master's file never
        //   governs a slave's logger, so there is nothing to propagate.
        // * Player (STARTING_BALANCE): consumed exclusively on the master
        //   (ServerBankManager user creation; slaves forward joins to the master).
        //   No propagation needed.
        // * ServerBank tick intervals (ITEM_TRANSFER / BANK_DOWNLOAD / BANK_UPLOAD
        //   _UPDATE_TICK_INTERVAL): consumed live by the block entities on the server
        //   HOSTING the block — including slaves, which read their own local
        //   settings.json. There is deliberately NO settings broadcast to slaves:
        //   slave-hosted automation blocks keep the slave's local values (edit the
        //   slave's settings.json file directly if they must differ from defaults).
        // * ServerBank component lists (ADDITIONAL_VOLATILE_COMPONENTS /
        //   ADDITIONAL_DEPOSIT_GATED_COMPONENTS): restart-required — they are applied
        //   to ItemID identity only during world load (startup merge guard). Pushing
        //   them to slaves NOW would change ItemID identity mid-session, which the mod
        //   explicitly forbids (same rule as the datapack-reload rejection). After the
        //   master restarts and applies them, they reach slaves and clients
        //   automatically through the existing SyncItemIDsPacket path.
        // * ServerBank confirm flags (CONFIRM_ITEMID_MERGE / CONFIRM_ITEMID_REPAIR):
        //   one-shot startup guards evaluated on the master only. No propagation.
        // * Placeholder: consumed once at TAB-integration registration during startup
        //   of the server where the TAB mod runs, from that server's local file.
        //   No propagation.
        // => No active propagation is required for any group.

        // Notify other online admins (on this server) about the change.
        broadcastToAdmins(playerSender, getPlayerName(playerSender) + " updated the BankSystem mod settings");
        info("Mod settings updated by " + getPlayerName(playerSender) + (saved ? " (persisted)" : " (PERSIST FAILED)"));

        boolean success = saved && applyError == null;
        return CompletableFuture.completedFuture(new OutputData(success,
                applyError == null ? "" : applyError,
                store.toJsonString(settings.getEditableGroups())));
    }

    /**
     * Clamps all numeric settings into their documented bounds and normalizes the
     * non-numeric values.
     * <p>
     * Bounds (see the constants above):
     * <ul>
     *   <li>{@code Utilities.SAVE_INTERVAL_MINUTES} ∈ [1, 1440]</li>
     *   <li>{@code Utilities.BALANCE_SNAPSHOT_INTERVAL_MINUTES} ∈ [0, 10,080] (0 = disabled)</li>
     *   <li>{@code Utilities.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM} ∈ [0, 10,000,000] (0 = unlimited)</li>
     *   <li>{@code Player.STARTING_BALANCE} ∈ [0, 10^12]</li>
     *   <li>{@code ServerBank.*_TICK_INTERVAL} ∈ [1, 1200]</li>
     *   <li>{@code ServerBank.ADDITIONAL_VOLATILE_COMPONENTS} /
     *       {@code ADDITIONAL_DEPOSIT_GATED_COMPONENTS}: entries trimmed, blanks
     *       dropped, duplicates removed (order preserved); null list → empty list</li>
     *   <li>{@code Placeholder.*}: null data → the setting's default; identifier
     *       trimmed; refreshRate ∈ [100 ms, 86,400,000 ms]</li>
     * </ul>
     * Static and side-effect free apart from the settings object itself, so the
     * in-game test suite can verify the bounds without a running request.
     *
     * @param settings the live (or test) settings object to sanitize
     */
    public static void sanitize(BankSystemModSettings settings) {
        // Utilities
        settings.UTILITIES.SAVE_INTERVAL_MINUTES.set(
                clamp(settings.UTILITIES.SAVE_INTERVAL_MINUTES.get(), MIN_SAVE_INTERVAL_MINUTES, MAX_SAVE_INTERVAL_MINUTES));
        settings.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.set(
                clamp(settings.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.get(), MIN_SNAPSHOT_INTERVAL_MINUTES, MAX_SNAPSHOT_INTERVAL_MINUTES));
        settings.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.set(
                clamp(settings.UTILITIES.BALANCE_SNAPSHOT_MAX_RECORDS_PER_ITEM.get(), MIN_SNAPSHOT_MAX_RECORDS, MAX_SNAPSHOT_MAX_RECORDS));
        sanitizeBoolean(settings.UTILITIES.LOGGING_ENABLE_INFO);
        sanitizeBoolean(settings.UTILITIES.LOGGING_ENABLE_WARNING);
        sanitizeBoolean(settings.UTILITIES.LOGGING_ENABLE_ERROR);
        sanitizeBoolean(settings.UTILITIES.LOGGING_ENABLE_DEBUG);

        // Player
        settings.PLAYER.STARTING_BALANCE.set(
                clamp(settings.PLAYER.STARTING_BALANCE.get(), MIN_STARTING_BALANCE, MAX_STARTING_BALANCE));

        // ServerBank
        settings.BANK.ITEM_TRANSFER_TICK_INTERVAL.set(
                clamp(settings.BANK.ITEM_TRANSFER_TICK_INTERVAL.get(), MIN_TICK_INTERVAL, MAX_TICK_INTERVAL));
        settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(
                clamp(settings.BANK.BANK_DOWNLOAD_BLOCK_UPDATE_TICK_INTERVAL.get(), MIN_TICK_INTERVAL, MAX_TICK_INTERVAL));
        settings.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.set(
                clamp(settings.BANK.BANK_UPLOAD_BLOCK_UPDATE_TICK_INTERVAL.get(), MIN_TICK_INTERVAL, MAX_TICK_INTERVAL));
        settings.BANK.ADDITIONAL_VOLATILE_COMPONENTS.set(
                normalizeComponentList(settings.BANK.ADDITIONAL_VOLATILE_COMPONENTS.get()));
        settings.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.set(
                normalizeComponentList(settings.BANK.ADDITIONAL_DEPOSIT_GATED_COMPONENTS.get()));
        sanitizeBoolean(settings.BANK.CONFIRM_ITEMID_MERGE);
        sanitizeBoolean(settings.BANK.CONFIRM_ITEMID_REPAIR);

        // Placeholder
        sanitizePlaceholder(settings.PLACEHOLDER.PLAYER_BALANCE);
        sanitizePlaceholder(settings.PLACEHOLDER.PLAYER_LOCKED_BALANCE);
        sanitizePlaceholder(settings.PLACEHOLDER.PLAYER_TOTAL_BALANCE);
        sanitizePlaceholder(settings.PLACEHOLDER.PLAYER_BANKUSER_JSON);
        sanitizePlaceholder(settings.PLACEHOLDER.SERVER_CIRCULATION_JSON);
    }

    /** Clamps a Long setting value into [min, max]; null falls back to min. */
    private static long clamp(@Nullable Long value, long min, long max) {
        if (value == null) return min;
        return Math.max(min, Math.min(max, value));
    }

    /** Clamps an Integer setting value into [min, max]; null falls back to min. */
    private static int clamp(@Nullable Integer value, int min, int max) {
        if (value == null) return min;
        return Math.max(min, Math.min(max, value));
    }

    /** Replaces a null Boolean value with the setting's default. */
    private static void sanitizeBoolean(Setting<Boolean> setting) {
        if (setting.get() == null) setting.set(setting.getDefaultValue());
    }

    /**
     * Normalizes a component-type-id list: entries trimmed, blank entries dropped,
     * duplicates removed while preserving order. A null list becomes an empty list.
     * No format validation beyond that — unknown/mistyped ids are already tolerated
     * by {@code VolatileItemComponents} (they simply never match a component).
     */
    private static List<String> normalizeComponentList(@Nullable List<String> list) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (list != null) {
            for (String entry : list) {
                if (entry == null) continue;
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) normalized.add(trimmed);
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * Sanitizes one Placeholder setting: null data falls back to the setting's
     * default; the identifier is trimmed and the refresh rate clamped into
     * [{@link #MIN_PLACEHOLDER_REFRESH_MS}, {@link #MAX_PLACEHOLDER_REFRESH_MS}].
     * (The '%...%' identifier format is validated at TAB registration time, which
     * skips malformed identifiers with a log message — not enforced here.)
     */
    private static void sanitizePlaceholder(Setting<BankSystemModSettings.Placeholder.PlaceholderSettingData> setting) {
        BankSystemModSettings.Placeholder.PlaceholderSettingData data = setting.get();
        if (data == null) {
            setting.set(setting.getDefaultValue());
            return;
        }
        String identifier = data.getIdentifier() == null ? "" : data.getIdentifier().trim();
        int refreshRate = Math.max(MIN_PLACEHOLDER_REFRESH_MS, Math.min(MAX_PLACEHOLDER_REFRESH_MS, data.getRefreshRate()));
        if (!identifier.equals(data.getIdentifier()) || refreshRate != data.getRefreshRate()) {
            setting.set(new BankSystemModSettings.Placeholder.PlaceholderSettingData(identifier, refreshRate));
        }
    }

    /**
     * Resolves a display name for the sending player for log messages. Works for
     * players hosted on slave servers too (they are registered as bank users on the
     * master at join, but are not in the master's vanilla player list).
     *
     * @param playerUUID the sending player's UUID (may be null)
     * @return the player's name, or a fallback string
     */
    private String getPlayerName(@Nullable UUID playerUUID) {
        if (playerUUID == null) return "<unknown>";
        try {
            User user = getServerBankManager().getUserByUUID(playerUUID);
            if (user != null && user.getName() != null && !user.getName().isEmpty())
                return user.getName();
        } catch (Exception ignored) {
        }
        return playerUUID.toString();
    }

    /**
     * Sends a chat notice to all online BankSystem admins on THIS (master) server,
     * excluding the acting player. Admins on slave servers are not notified (no
     * dedicated admin-broadcast channel exists — acceptable for an informational
     * message; the settings change itself is fully effective regardless).
     *
     * @param actingPlayer the player who performed the change (excluded)
     * @param message      the notice text
     */
    private void broadcastToAdmins(@Nullable UUID actingPlayer, String message) {
        for (ServerPlayer player : ServerPlayerUtilities.getOnlinePlayers()) {
            if (player.getUUID().equals(actingPlayer)) continue;
            if (playerIsAdmin(player.getUUID())) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        OutputData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        return OutputData.STREAM_CODEC.decode(buf);
    }
}
