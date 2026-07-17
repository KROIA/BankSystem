package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * <b>Slave→Master ARRS batch request (Task #23):</b> master is the SOLE ItemID minter — no
 * slave-side minting.
 * <p>
 * The slave sends a list of already-normalized item stacks (see
 * {@link net.kroia.banksystem.util.VolatileItemComponents#normalize(ItemStack)}, computed
 * identically on both sides) serialized as NBT tags. The master parses each tag, calls its own
 * {@link ItemID#getOrRegisterFromItemStackServerSide_direct(ItemStack)}, and returns an
 * index-aligned list of {@link ItemID}s — a valid short if the master could parse and register
 * the stack, {@link ItemID#INVALID_ID} for any slot whose NBT the master could not parse
 * (item mod missing on master).
 * <p>
 * <b>NBT transport (not {@code ItemStack.STREAM_CODEC}):</b> vanilla's
 * {@code ItemStack.STREAM_CODEC} sends items by numeric registry id and throws at decode time
 * on unknown items. That would abort the whole batch on the master, breaking the per-item
 * result requirement (Design Q3). Serializing each stack as {@code stack.save(access, tag)}
 * and decoding with {@link ItemStack#parse(RegistryAccess, net.minecraft.nbt.Tag)} gives an
 * {@link Optional} the master can safely inspect per slot: parse-failure → {@code INVALID_ID}
 * in that slot only.
 * <p>
 * <b>Design Q2 (component identity):</b> a component surviving normalization is part of item
 * identity. If master's {@code ItemStack.parse} fails (unknown item OR unknown data-component
 * type), master rejects the slot — no silent fallback to a plain base item. Post-parse
 * validation is unnecessary in MC 1.21: the {@code DataComponentType} registry is checked
 * during parse, so a component master doesn't have makes parse fail in the first place.
 * <p>
 * Batch-only (Design Q3). Single-item registrations wrap in a 1-element list on the caller
 * side (see {@link ItemIDManager#registerItemStackServerSide_direct(ItemStack)}). The master's
 * own local {@code getOrRegisterFromItemStackServerSide_direct} is not changed — this handler
 * just calls it in a loop per slot, so master-side allocation semantics stay bit-identical.
 */
public class RegisterItemStacksBatchRequest extends BankSystemGenericRequest<RegisterItemStacksBatchRequest.InputData, RegisterItemStacksBatchRequest.OutputData> {

    /** Per-slot NBT tag of a normalized {@link ItemStack} (see {@link ItemStack#save(RegistryAccess, net.minecraft.nbt.Tag)}). */
    public record InputData(List<CompoundTag> nbtItems) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.listStreamCodec(ByteBufCodecs.TRUSTED_COMPOUND_TAG), p -> p.nbtItems,
                InputData::new
        );
    }

    /** Index-aligned per-slot response — {@link ItemID#INVALID_ID} on parse failure. */
    public record OutputData(List<ItemID> ids) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC), p -> p.ids,
                OutputData::new
        );
    }

    /**
     * Slave-side entry point: serialize the caller's already-normalized stacks to NBT and
     * dispatch the batch to master. The returned future completes on the ARRS response thread
     * (typically a netty thread) — callers must marshal any state mutation onto the server
     * thread themselves (see {@code ItemIDManager.registerItemStackServerSide_direct}).
     * <p>
     * If serialization or the ARRS call itself fails, the future completes with an
     * index-aligned list of {@link ItemID#INVALID_ID}s so the caller can populate the
     * negative cache uniformly.
     *
     * @param normalizedStacks stacks already normalized via
     *                         {@code VolatileItemComponents.normalize(...)} — the same
     *                         normalization the master uses on the received side.
     * @return future of an index-aligned list of ItemIDs (never null; INVALID_ID on failure)
     */
    public static CompletableFuture<List<ItemID>> sendToMaster(List<ItemStack> normalizedStacks) {
        if (normalizedStacks == null || normalizedStacks.isEmpty())
            return CompletableFuture.completedFuture(Collections.emptyList());

        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null) {
            // Registry not available on the slave — nothing we can encode. Fall through to
            // INVALID_ID for every slot; caller populates the negative cache like a real
            // master rejection so we don't hammer master with retries on the next lookup.
            List<ItemID> allInvalid = new ArrayList<>(normalizedStacks.size());
            for (int i = 0; i < normalizedStacks.size(); i++)
                allInvalid.add(ItemID.INVALID_ID);
            return CompletableFuture.completedFuture(allInvalid);
        }

        List<CompoundTag> encoded = new ArrayList<>(normalizedStacks.size());
        for (ItemStack stack : normalizedStacks) {
            if (stack == null || stack.isEmpty()) {
                encoded.add(new CompoundTag());
                continue;
            }
            try {
                CompoundTag itemStackTag = new CompoundTag();
                stack.save(access, itemStackTag);
                encoded.add(itemStackTag);
            } catch (Throwable t) {
                // Serialization failure on the slave side (e.g. cross-registry Holder issue)
                // — put an empty tag so the master's parse fails cleanly for this slot only.
                encoded.add(new CompoundTag());
            }
        }

        CompletableFuture<List<ItemID>> resultFuture = new CompletableFuture<>();
        BankSystemNetworking.REGISTER_ITEM_STACKS_BATCH_REQUEST
                .sendRequestToMaster(new InputData(encoded))
                .whenComplete((response, ex) -> {
                    if (ex != null || response == null || response.ids == null) {
                        List<ItemID> allInvalid = new ArrayList<>(normalizedStacks.size());
                        for (int i = 0; i < normalizedStacks.size(); i++)
                            allInvalid.add(ItemID.INVALID_ID);
                        resultFuture.complete(allInvalid);
                    } else {
                        // Pad or truncate the response to be index-aligned with the request —
                        // defensive: the master should always return the exact size, but a
                        // buggy/older master version returning a shorter list must not blow up
                        // the negative-cache-populate loop.
                        if (response.ids.size() == normalizedStacks.size()) {
                            resultFuture.complete(response.ids);
                        } else {
                            List<ItemID> padded = new ArrayList<>(normalizedStacks.size());
                            for (int i = 0; i < normalizedStacks.size(); i++)
                                padded.add(i < response.ids.size() ? response.ids.get(i) : ItemID.INVALID_ID);
                            resultFuture.complete(padded);
                        }
                    }
                });
        return resultFuture;
    }

    @Override
    public String getRequestTypeID() {
        return RegisterItemStacksBatchRequest.class.getName();
    }

    /**
     * ARRS routing: the slave sends this request explicitly via {@link #sendToMaster(List)},
     * so no automatic routing-to-master is needed. Setting this to false matches
     * {@code DepositItemsInBankRequest} / {@code WithdrawItemsFromBankRequest}, which are the
     * two slave→master ARRS requests in the codebase.
     */
    @Override
    public boolean needsRoutingToMaster() { return false; }

    /**
     * Handler on the master. Runs per-slot: parse-then-register with per-slot INVALID_ID on
     * parse failure. Master's own {@code getOrRegisterFromItemStackServerSide_direct} is
     * unchanged — this loop just calls it once per parsed stack, so the master's registration
     * latch (Task #16) still gates the mint if the master's own load hasn't completed yet.
     *
     * @param input        request slots (per-slot NBT)
     * @param slaveID      the requesting slave's id (unused; used only for the client-safety
     *                     guard on the {@code playerSender} path)
     * @param playerSender {@code null} for slave→master calls — a non-null value indicates a
     *                     client attempted to invoke this handler directly, which is refused
     *                     with an ALL-INVALID response (defensive: clients never mint IDs).
     */
    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
        int slotCount = input.nbtItems != null ? input.nbtItems.size() : 0;
        if (playerSender != null) {
            warn("RegisterItemStacksBatchRequest is a slave→master call — refusing to answer a "
                    + "client-originated invocation (playerSender=" + playerSender + "). Returning "
                    + "INVALID_ID for all " + slotCount + " slots.");
            return CompletableFuture.completedFuture(new OutputData(allInvalid(slotCount)));
        }
        if (slotCount == 0)
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));

        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if (access == null) {
            error("RegisterItemStacksBatchRequest: no server-side RegistryAccess on master — "
                    + "returning INVALID_ID for all " + slotCount + " slots.");
            return CompletableFuture.completedFuture(new OutputData(allInvalid(slotCount)));
        }

        List<ItemID> result = new ArrayList<>(slotCount);
        for (CompoundTag tag : input.nbtItems) {
            if (tag == null || tag.isEmpty()) {
                result.add(ItemID.INVALID_ID);
                continue;
            }
            Optional<ItemStack> parsed;
            try {
                parsed = ItemStack.parse(access, tag);
            } catch (Throwable t) {
                // Defensive: ItemStack.parse should return Optional.empty() on failure, but a
                // malformed NBT could still surface as an exception. Treat as a normal per-slot
                // parse failure — no INVALID_ID batch collapse.
                parsed = Optional.empty();
            }
            if (parsed.isEmpty() || parsed.get().isEmpty()) {
                result.add(ItemID.INVALID_ID);
                continue;
            }
            ItemStack stack = parsed.get();
            // Route through the ItemID API rather than ItemIDManager directly so the master-side
            // lookup-then-register semantics (getOrRegister...) match the slave's positive-cache
            // hit path: an already-known short comes back without re-mint.
            ItemID id = ItemID.getOrRegisterFromItemStackServerSide_direct(stack);
            result.add(id != null ? id : ItemID.INVALID_ID);
        }
        int invalidSlots = 0;
        for (ItemID r : result) {
            if (r == null || !r.isValid()) invalidSlots++;
        }

        // Task #23 — user-facing chat notification when master rejects at least one item.
        // Mirrors Task #27's pattern: master pushes a ClientConsoleMessagePacket back to the
        // originating slave, which broadcasts to all local players (acceptable tradeoff — the
        // master has no per-player context here). Deliberately omits the specific item name
        // because the master's translation table is not authoritative for the failing item.
        if (invalidSlots > 0 && slaveID != null && !slaveID.isEmpty()) {
            ClientConsoleMessagePacket.sendMessageFromMasterToSlave(slaveID,
                    "[BankSystem] The item you tried to bank does not exist on the master server — "
                    + "it cannot be used with this bank system.");
        }
        return CompletableFuture.completedFuture(new OutputData(result));
    }

    private static List<ItemID> allInvalid(int count) {
        List<ItemID> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            out.add(ItemID.INVALID_ID);
        return out;
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
