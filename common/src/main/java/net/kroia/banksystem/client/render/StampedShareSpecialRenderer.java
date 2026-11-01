package net.kroia.banksystem.client.render;

import net.kroia.banksystem.BankSystemModBackend;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import net.kroia.banksystem.BankSystemMod;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * MC 1.21.4+ only (this file exists on the mc_1.21.4+ branches). Item-model "special" renderer
 * for the stamped share, replacing the removed BlockEntityWithoutLevelRenderer / Fabric
 * BuiltinItemRendererRegistry path. Referenced by {@code assets/banksystem/items/stamped_share.json}
 * as {@code "type": "banksystem:stamped_share"}; drawing is delegated to {@link StampedShareRenderer}.
 */
public final class StampedShareSpecialRenderer implements SpecialModelRenderer<ItemStack> {

    public static final ResourceLocation ID = BankSystemMod.id("stamped_share");

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public record Unbaked() implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public SpecialModelRenderer<?> bake(EntityModelSet modelSet) {
            return new StampedShareSpecialRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked> type() {
            return MAP_CODEC;
        }
    }

    @Override
    public void render(@Nullable ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                       MultiBufferSource buffers, int light, int overlay, boolean hasFoil) {
        if (stack != null)
            StampedShareRenderer.render(stack, context, poseStack, buffers, light, overlay);
    }

    @Override
    public ItemStack extractArgument(ItemStack stack) {
        return stack;
    }

    /**
     * Fabric has no API for special item renderer types in 1.21.4, so the type is added to the
     * vanilla id mapper directly. The field is located by type, so it works with any mappings.
     * Must run before the first resource reload (client init). NeoForge uses
     * RegisterSpecialModelRendererEvent instead.
     */
    @SuppressWarnings("unchecked")
    public static void registerViaVanillaMapper() {
        try {
            for (Field field : SpecialModelRenderers.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == ExtraCodecs.LateBoundIdMapper.class) {
                    field.setAccessible(true);
                    ((ExtraCodecs.LateBoundIdMapper<ResourceLocation, MapCodec<? extends SpecialModelRenderer.Unbaked>>) field.get(null))
                            .put(ID, Unbaked.MAP_CODEC);
                    return;
                }
            }
            BACKEND_INSTANCES.LOGGER.error("Stamped share renderer: special renderer id mapper not found");
        } catch (ReflectiveOperationException | RuntimeException e) {
            BACKEND_INSTANCES.LOGGER.error("Stamped share renderer registration failed", e);
        }
    }
}
