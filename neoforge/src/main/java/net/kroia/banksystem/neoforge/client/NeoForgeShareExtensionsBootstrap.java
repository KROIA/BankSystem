package net.kroia.banksystem.neoforge.client;

/**
 * Task #53 (v2.0.8) — NeoForge stamped-share renderer scaffold. The
 * {@code @EventBusSubscriber} registration is intentionally REMOVED — with
 * {@code stamped_share.json} reverted to {@code item/generated} the
 * {@code IClientItemExtensions.getCustomRenderer} path is never invoked, and
 * leaving the subscriber in would attach a dead handler. The scaffold class
 * ({@link NeoForgeShareRenderer}) is retained so the follow-up that ships a
 * proper cross-context item renderer is a one-line re-add of the subscription
 * plus the model-JSON flip back to {@code builtin/entity}.
 */
public final class NeoForgeShareExtensionsBootstrap {
    private NeoForgeShareExtensionsBootstrap() {}
}
