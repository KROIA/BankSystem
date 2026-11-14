package net.kroia.banksystem.api.ledger;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Task #109 — who caused a ledger row. Persisted as {@code actor_uuid},
 * {@code actor_name} and {@code source_kind}.
 *
 * @since 2.2.0
 * @param uuid   the acting player or admin, or {@code null} for console/system/StockMarket rows
 * @param name   display name at the time of the row (may be {@code null} if unknown)
 * @param source what kind of actor this is
 */
public record LedgerActor(@Nullable UUID uuid, @Nullable String name, Source source) {

    /** Persisted in {@code source_kind} by name. Append-only. */
    public enum Source { PLAYER, ADMIN, CONSOLE, SYSTEM, STOCKMARKET,
        /** BS-254: a balance change whose caller gave no actor (another mod, a raw slave request). */
        UNKNOWN }

    public static LedgerActor player(@Nullable UUID uuid, @Nullable String name) {
        return new LedgerActor(uuid, name, Source.PLAYER);
    }

    public static LedgerActor admin(@Nullable UUID uuid, @Nullable String name) {
        return new LedgerActor(uuid, name, Source.ADMIN);
    }

    public static LedgerActor console() {
        return new LedgerActor(null, "Console", Source.CONSOLE);
    }

    public static LedgerActor system() {
        return new LedgerActor(null, null, Source.SYSTEM);
    }

    /** BS-254: actor of a {@link LedgerType#RAW_CHANGE} row whose caller is not known. */
    public static LedgerActor unknown() {
        return new LedgerActor(null, null, Source.UNKNOWN);
    }

    public static LedgerActor stockmarket() {
        return new LedgerActor(null, "StockMarket", Source.STOCKMARKET);
    }
}
