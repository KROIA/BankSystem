package net.kroia.banksystem.minecraft.compat;

import java.util.function.Consumer;

/**
 * Loader- and JEI-neutral broker for toggling JEI's ingredient list and
 * bookmark overlays (including their overlay buttons) from screen lifecycle
 * hooks, without leaking any {@code mezz.jei.*} classes into the screens
 * themselves.
 * <p>
 * <b>Why this indirection:</b> the screens are loaded whether or not JEI is
 * on the classpath. If a screen referenced {@link BankSystemJeiPlugin}
 * directly, a JEI-less run would fail to link the screen class the first time
 * it is touched. {@code BankSystemJeiPlugin} imports {@code mezz.jei.*} and
 * is discovered lazily by JEI (Fabric/Quilt entrypoint, NeoForge annotation
 * scan) — without JEI it is never class-loaded. This broker has no JEI
 * imports, so screens can call {@link #setHidden(boolean)} unconditionally.
 * When JEI is absent the {@link #hider} field stays at its no-op default and
 * every screen call is a silent skip.
 * <p>
 * <b>Threading invariant:</b> both {@link #install(Consumer)} (called from
 * {@link BankSystemJeiPlugin#onRuntimeAvailable}) and {@link #setHidden(boolean)}
 * (called from screen {@code updateLayout} / {@code onClose} / {@code removed})
 * run on the Minecraft client render thread. The {@link #hider} field is
 * therefore intentionally not {@code volatile}. If any caller ever moves off
 * that thread, this field must be marked volatile.
 * <p>
 * <b>Failure policy:</b> {@link #setHidden(boolean)} swallows every
 * {@code Throwable} from the installed hider. Failing to toggle JEI must
 * never crash the calling screen — the worst-case fallback is that JEI stays
 * as it was, which is harmless.
 */
public final class BankSystemJeiOverlayBroker {

    // Same-thread only (see class javadoc "Threading invariant").
    private static Consumer<Boolean> hider = h -> {};

    private BankSystemJeiOverlayBroker() {}

    /**
     * Installs the real toggle implementation. Called once by
     * {@link BankSystemJeiPlugin#onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime)}
     * when JEI is present and reflection into JEI's internal
     * {@code IClientToggleState} succeeded. Passing {@code null} reverts to
     * the no-op default (used when reflection fails).
     */
    public static void install(Consumer<Boolean> impl) {
        hider = impl != null ? impl : h -> {};
    }

    /**
     * Request JEI's ingredient list + bookmark overlays hidden ({@code true})
     * or restored ({@code false}). Safe to call whether or not JEI is loaded.
     * Idempotency and "did the user hide JEI themselves?" bookkeeping live
     * inside the installed hider (see
     * {@link BankSystemJeiPlugin#onRuntimeAvailable}).
     */
    public static void setHidden(boolean hidden) {
        try {
            hider.accept(hidden);
        } catch (Throwable ignored) {
            // Fail-safe: never let overlay toggling break the calling screen.
        }
    }
}
