package net.kroia.banksystem.client.render;

import net.kroia.banksystem.BankSystemModBackend;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import net.kroia.banksystem.BankSystemMod;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
//import net.minecraft.resources.ResourceLocation; // mc<=1.21.10
import net.minecraft.resources.Identifier; // mc>=1.21.11
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

    //public static final ResourceLocation ID = BankSystemMod.id("stamped_share"); // mc<=1.21.10
    public static final Identifier ID = BankSystemMod.id("stamped_share"); // mc>=1.21.11

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    //public record Unbaked() implements SpecialModelRenderer.Unbaked { // mc<=1.21.11
    public record Unbaked() implements SpecialModelRenderer.Unbaked<ItemStack> { // mc>=26.1
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        //public SpecialModelRenderer<?> bake(EntityModelSet modelSet) { // mc<=1.21.8
        //public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) { // mc>=1.21.9 mc<=1.21.11
        public SpecialModelRenderer<ItemStack> bake(SpecialModelRenderer.BakingContext context) { // mc>=26.1
            return new StampedShareSpecialRenderer();
        }

        @Override
        //public MapCodec<? extends SpecialModelRenderer.Unbaked> type() { // mc<=1.21.11
        public MapCodec<? extends SpecialModelRenderer.Unbaked<ItemStack>> type() { // mc>=26.1
            return MAP_CODEC;
        }
    }

    //@Override // mc<=1.21.8
    //public void render(@Nullable ItemStack stack, ItemDisplayContext context, PoseStack poseStack, // mc<=1.21.8
                       //MultiBufferSource buffers, int light, int overlay, boolean hasFoil) { // mc<=1.21.8
        //if (stack != null) // mc<=1.21.8
            //StampedShareRenderer.render(stack, context, poseStack, buffers, light, overlay); // mc<=1.21.8
    //} // mc<=1.21.8

    @Override // mc>=1.21.9
    //public void submit(@Nullable ItemStack stack, ItemDisplayContext context, PoseStack poseStack, // mc>=1.21.9 mc<=1.21.11
    public void submit(@Nullable ItemStack stack, PoseStack poseStack, // mc>=26.1
                       net.minecraft.client.renderer.SubmitNodeCollector collector, int light, int overlay, boolean hasFoil, int outlineColor) { // mc>=1.21.9
        if (stack != null) // mc>=1.21.9
            StampedShareRenderer.render(stack, (type, geometry) -> collector.submitCustomGeometry(poseStack, type, geometry::accept), light, overlay); // mc>=1.21.9
    } // mc>=1.21.9

    //@Override // mc>=1.21.6 mc<=1.21.10
    //public void getExtents(java.util.Set<org.joml.Vector3f> extents) { for (float x : new float[]{0f, 1f}) for (float y : new float[]{0f, 1f}) for (float z : new float[]{7.5f / 16f, 8.5f / 16f}) extents.add(new org.joml.Vector3f(x, y, z)); } // mc>=1.21.6 mc<=1.21.10 (card box)
    @Override // mc>=1.21.11
    public void getExtents(java.util.function.Consumer<org.joml.Vector3fc> extents) { for (float x : new float[]{0f, 1f}) for (float y : new float[]{0f, 1f}) for (float z : new float[]{7.5f / 16f, 8.5f / 16f}) extents.accept(new org.joml.Vector3f(x, y, z)); } // mc>=1.21.11 (card box)

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
                    //((ExtraCodecs.LateBoundIdMapper<ResourceLocation, MapCodec<? extends SpecialModelRenderer.Unbaked>>) field.get(null)) // mc<=1.21.10
                    ((ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked>>) field.get(null)) // mc>=1.21.11
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
