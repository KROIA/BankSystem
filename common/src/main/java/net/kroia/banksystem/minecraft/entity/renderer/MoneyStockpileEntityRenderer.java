package net.kroia.banksystem.minecraft.entity.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.minecraft.entity.custom.MoneyStockpileBlockEntity;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

import java.util.Map;

//public class MoneyStockpileEntityRenderer implements BlockEntityRenderer<MoneyStockpileBlockEntity> { // mc<=1.21.8
public class MoneyStockpileEntityRenderer implements BlockEntityRenderer<MoneyStockpileBlockEntity, MoneyStockpileEntityRenderer.StockpileRenderState> { // mc>=1.21.9

    private static float scale = 0.25f;
    private static float itemTowerSpacing = 0.015625f; // Spacing between items in the tower
    private static float placementNoise = 0.02f; // Small noise to prevent items from overlapping
    private static final Quaternionf rotation = new Quaternionf().rotationX((float)(Math.PI/2.0));
    private static final float[] staticOffsetNoise = new float[30];

    static{
        for(int i = 0; i < staticOffsetNoise.length; ++i)
        {
            staticOffsetNoise[i] = (float) (Math.random() * placementNoise - placementNoise / 2.0f);
        }
    }

    /** Receives one rendered item at its block-local position (before the facing rotation). */
    private interface Placement {
        void accept(ItemStack stack, float x, float y, float z);
    }

    // mc>=1.21.9: block entity rendering is split into extract (game state -> render state) and submit.
    public static class StockpileRenderState extends net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState { // mc>=1.21.9
        Direction facing = Direction.NORTH; // mc>=1.21.9
        final java.util.List<net.minecraft.client.renderer.item.ItemStackRenderState> items = new java.util.ArrayList<>(); // mc>=1.21.9
        final java.util.List<float[]> positions = new java.util.ArrayList<>(); // mc>=1.21.9
    } // mc>=1.21.9

    private final net.minecraft.client.renderer.item.ItemModelResolver itemModelResolver; // mc>=1.21.9

    public MoneyStockpileEntityRenderer(BlockEntityRendererProvider.Context context)
    {
        super();
        this.itemModelResolver = context.itemModelResolver(); // mc>=1.21.9
    }

    //@Override // mc<=1.21.8
    //public void render(MoneyStockpileBlockEntity be, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) { // mc<=1.21.4
    //public void render(MoneyStockpileBlockEntity be, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay, net.minecraft.world.phys.Vec3 cameraPos) { // mc>=1.21.5 mc<=1.21.8
        //Minecraft mc = Minecraft.getInstance(); // mc<=1.21.8
        //poseStack.pushPose(); // mc<=1.21.8
        //applyFacing(poseStack, be.getFacing()); // mc<=1.21.8
        //layout(be, (stack, x, y, z) -> { // mc<=1.21.8
            //poseStack.pushPose(); // mc<=1.21.8
            //applyItemTransform(poseStack, x, y, z); // mc<=1.21.8
            //mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, overlay, poseStack, buffer, be.getLevel(), 0); // mc<=1.21.8
            //poseStack.popPose(); // mc<=1.21.8
        //}); // mc<=1.21.8
        //poseStack.popPose(); // mc<=1.21.8
    //} // mc<=1.21.8

    @Override // mc>=1.21.9
    public StockpileRenderState createRenderState() { // mc>=1.21.9
        return new StockpileRenderState(); // mc>=1.21.9
    } // mc>=1.21.9

    @Override // mc>=1.21.9
    public void extractRenderState(MoneyStockpileBlockEntity be, StockpileRenderState state, float partialTick, // mc>=1.21.9
                                   net.minecraft.world.phys.Vec3 cameraPos, // mc>=1.21.9
                                   net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling) { // mc>=1.21.9
        BlockEntityRenderer.super.extractRenderState(be, state, partialTick, cameraPos, crumbling); // mc>=1.21.9
        state.facing = be.getFacing(); // mc>=1.21.9
        state.items.clear(); // mc>=1.21.9
        state.positions.clear(); // mc>=1.21.9
        // One resolved model per item type; every placement of that type shares it.
        Map<ItemStack, net.minecraft.client.renderer.item.ItemStackRenderState> resolved = new java.util.IdentityHashMap<>(); // mc>=1.21.9
        layout(be, (stack, x, y, z) -> { // mc>=1.21.9
            state.items.add(resolved.computeIfAbsent(stack, s -> { // mc>=1.21.9
                net.minecraft.client.renderer.item.ItemStackRenderState r = new net.minecraft.client.renderer.item.ItemStackRenderState(); // mc>=1.21.9
                itemModelResolver.updateForTopItem(r, s, ItemDisplayContext.FIXED, be.getLevel(), null, 0); // mc>=1.21.9
                return r; // mc>=1.21.9
            })); // mc>=1.21.9
            state.positions.add(new float[]{x, y, z}); // mc>=1.21.9
        }); // mc>=1.21.9
    } // mc>=1.21.9

    @Override // mc>=1.21.9
    public void submit(StockpileRenderState state, PoseStack poseStack, // mc>=1.21.9
                       net.minecraft.client.renderer.SubmitNodeCollector collector, // mc>=1.21.9
                       net.minecraft.client.renderer.state.CameraRenderState camera) { // mc>=1.21.9
        poseStack.pushPose(); // mc>=1.21.9
        applyFacing(poseStack, state.facing); // mc>=1.21.9
        for (int i = 0; i < state.items.size(); i++) { // mc>=1.21.9
            float[] p = state.positions.get(i); // mc>=1.21.9
            poseStack.pushPose(); // mc>=1.21.9
            applyItemTransform(poseStack, p[0], p[1], p[2]); // mc>=1.21.9
            state.items.get(i).submit(poseStack, collector, state.lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0); // mc>=1.21.9
            poseStack.popPose(); // mc>=1.21.9
        } // mc>=1.21.9
        poseStack.popPose(); // mc>=1.21.9
    } // mc>=1.21.9

    private static void applyFacing(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(new Quaternionf().rotationY(getFacingAngle(facing)));
        poseStack.translate(-0.5, 0, -0.5);
    }

    private static void applyItemTransform(PoseStack poseStack, float x, float y, float z) {
        poseStack.translate(x, y, z);
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(rotation);
    }

    /** Lays out every item of the stockpile as towers of up to 64 on a 6x6 grid. */
    private static void layout(MoneyStockpileBlockEntity be, Placement out) {
        Map<ItemID, MoneyStockpileBlockEntity.ItemData> items = be.getItems();

        int currentGridIndex = 0;
        for (Map.Entry<ItemID, MoneyStockpileBlockEntity.ItemData> entry : items.entrySet()) {
            ItemID itemID = entry.getKey();
            MoneyStockpileBlockEntity.ItemData itemData = entry.getValue();

            // Create a stack from the item ID.
            // Hot path (runs every frame): use the read-only template instead of the
            // defensive copy of getStack() — the stack is only rendered, never mutated.
            ItemStack stack = itemID.getStackTemplate();
            boolean isBankNote = false;
            int gridIncrement = 1;
            float xOffset = 0;
            if(itemData.getGridSpacesPerItemType() == 2)
            {
                // ServerBank notes are rendered with a slight offset to the right
                isBankNote = true;
                xOffset = 0.08f;
                gridIncrement = 2; // ServerBank notes take up two grid spaces
            }


            if (stack == null || stack.isEmpty()) continue;

            int amount = itemData.getAmount();
            if(amount > 64)
            {
                int loops = amount / 64;
                for(int i = 0; i < loops; i++)
                {
                    if(isBankNote && currentGridIndex % 6 == 5)
                    {
                        // If it's a bank note, we skip the next grid index to create a gap
                        currentGridIndex++;
                    }
                    layoutTower(stack, 64, currentGridIndex, xOffset, out);
                    currentGridIndex+=gridIncrement;
                    amount-= 64;
                }
            }
            if(amount > 0) {
                if (isBankNote && currentGridIndex % 6 == 5) {
                    // If it's a bank note, we skip the next grid index to create a gap
                    currentGridIndex++;
                }
                layoutTower(stack, amount, currentGridIndex, xOffset, out);
                currentGridIndex += gridIncrement;
            }
        }
    }

    private static float getFacingAngle(Direction facing) {
        return switch (facing) {
            case NORTH -> 0f;
            case EAST -> (float) (Math.PI * 1.5);
            case SOUTH -> (float) Math.PI;
            case WEST -> (float) (Math.PI * 0.5);
            default -> 0f;
        };
    }


    private static void layoutTower(ItemStack stack, int count, int gridIndex, float xOffset, Placement out) {

        float x = (gridIndex % 6) * 0.16f + 0.08f + xOffset;
        float y = 0;
        float z = ((gridIndex / 6) % 6) * 0.16f + 0.08f;


        for(int i=0; i<count; ++i)
        {
            float xPos = x + staticOffsetNoise[(i % staticOffsetNoise.length)];
            float zPos = z + staticOffsetNoise[((i+20) % staticOffsetNoise.length)];

            out.accept(stack, xPos, y + i * itemTowerSpacing, zPos);
        }
    }
}
