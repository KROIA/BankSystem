package net.kroia.banksystem.minecraft.component;

import com.mojang.serialization.Codec;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Task #46 (v2.0.8) — Registration of BankSystem-owned data component types.
 * <p>
 * Currently declares {@code banksystem:company_id} — the identity-relevant integer
 * component stamped onto {@code stamped_share} items to tie a physical share stack
 * to its Company. Deliberately NOT included in the volatile-component tag: two
 * stamped stacks that differ only in {@code company_id} must resolve to distinct
 * {@link net.kroia.banksystem.util.ItemID ItemID}s (see {@code VolatileItemComponents}).
 */
public final class BankSystemDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(BankSystemMod.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    public static final RegistrySupplier<DataComponentType<Integer>> COMPANY_ID =
            DATA_COMPONENT_TYPES.register("company_id",
                    () -> DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        DATA_COMPONENT_TYPES.register();
    }

    private BankSystemDataComponents() {}
}
