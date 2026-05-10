package net.kroia.banksystem.minecraft.entity.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.minecraft.entity.custom.MoneyStockpileBlockEntity;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

import java.util.Map;

public class MoneyStockpileEntityRenderer implements BlockEntityRenderer<MoneyStockpileBlockEntity> {

    private static float scale = 0.25f;
    private static float itemTowerSpacing = 0.015625f; // Spacing between items in the tower
    private static float placementNoise = 0.02f; // Small noise to prevent items from overlapping
    private Quaternionf rotation = new Quaternionf().rotationX((float)(Math.PI/2.0));
    private static final float[] staticOffsetNoise = new float[30];

    static{
        for(int i = 0; i < staticOffsetNoise.length; ++i)
        {
            staticOffsetNoise[i] = (float) (Math.random() * placementNoise - placementNoise / 2.0f);
        }
    }


    private MultiBufferSource bufferSource;
    MoneyStockpileBlockEntity blockEntity;
    float partialTicks;
    PoseStack poseStack;
    int light;
    int overlay;
    Minecraft mc;

    public MoneyStockpileEntityRenderer(BlockEntityRendererProvider.Context context)
    {
        super();
    }

    @Override
    public void render(MoneyStockpileBlockEntity be, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        this.blockEntity = be;
        this.partialTicks = partialTicks;
        this.poseStack = poseStack;
        this.bufferSource = buffer;
        this.light = light;
        this.overlay = overlay;
        this.mc = Minecraft.getInstance();


        Map<ItemID, MoneyStockpileBlockEntity.ItemData> items = be.getItems();

        //ItemStack stack = new ItemStack(BankSystemItems.MONEY.get());

        Minecraft mc = Minecraft.getInstance();


        // Render each item in the stockpile
        float spacing = 0.25f; // Spacing between items in the grid
        int currentGridIndex = 0;
        for (Map.Entry<ItemID, MoneyStockpileBlockEntity.ItemData> entry : items.entrySet()) {
            ItemID itemID = entry.getKey();
            MoneyStockpileBlockEntity.ItemData itemData = entry.getValue();

            // Create a stack from the item ID
            //ItemStack stack = itemID.getStack();
            // For now, we use a placeholder stack
            ItemStack stack = itemID.getStack();
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
                    renderMoneyTower(stack, 64, currentGridIndex, xOffset);
                    currentGridIndex+=gridIncrement;
                    amount-= 64;
                }
            }
            if(amount > 0) {
                if (isBankNote && currentGridIndex % 6 == 5) {
                    // If it's a bank note, we skip the next grid index to create a gap
                    currentGridIndex++;
                }
                renderMoneyTower(stack, amount, currentGridIndex, xOffset);
                currentGridIndex += gridIncrement;
            }
        }
    }


    private void renderMoneyTower(ItemStack stack, int count, int gridIndex, float xOffset) {

        float x = (gridIndex % 6) * 0.16f + 0.08f + xOffset;
        float y = 0;
        float z = ((gridIndex / 6) % 6) * 0.16f + 0.08f;


        for(int i=0; i<count; ++i)
        {
            float xPos = x + staticOffsetNoise[(i % staticOffsetNoise.length)];
            float zPos = z + staticOffsetNoise[((i+20) % staticOffsetNoise.length)];


            renderItem(stack, xPos, y + i * itemTowerSpacing, zPos);
        }
    }

    private void renderItem(ItemStack stack, float x, float y, float z) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(rotation);
        mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
                light, overlay, poseStack, bufferSource, blockEntity.getLevel(), 0);
        poseStack.popPose();
    }
}
