package net.kroia.banksystem.util;

import net.kroia.modutilities.ColorUtilities;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class ItemColorUtil {

    private static final int[] FALLBACK_COLORS = {
            ColorUtilities.getRGB(63, 255, 139),
            ColorUtilities.getRGB(255, 113, 108),
            ColorUtilities.getRGB(100, 149, 237),
            ColorUtilities.getRGB(255, 215, 0),
            ColorUtilities.getRGB(186, 85, 211),
            ColorUtilities.getRGB(0, 206, 209),
            ColorUtilities.getRGB(255, 165, 0),
            ColorUtilities.getRGB(144, 238, 144),
            ColorUtilities.getRGB(255, 105, 180),
            ColorUtilities.getRGB(176, 196, 222),
    };

    private static final Map<Short, Integer> colorCache = new HashMap<>();

    public static int getColor(short itemId, ItemStack stack, int fallbackIndex) {
        Integer cached = colorCache.get(itemId);
        if (cached != null) return cached;

        int color = 0;
        if (stack != null && !stack.isEmpty()) {
            color = tryExtractFromTexture(stack);
        }
        if (color == 0) {
            color = FALLBACK_COLORS[fallbackIndex % FALLBACK_COLORS.length];
        }
        colorCache.put(itemId, color);
        return color;
    }

    public static int getColor(short itemId, int fallbackIndex) {
        Integer cached = colorCache.get(itemId);
        if (cached != null) return cached;

        ItemID id = new ItemID(itemId);
        ItemStack stack = id.getStack();
        return getColor(itemId, stack, fallbackIndex);
    }

    public static void clearCache() {
        colorCache.clear();
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static int tryExtractFromTexture(ItemStack stack) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return 0;
            var random = net.minecraft.util.RandomSource.create();
            var model = mc.getItemRenderer().getModel(stack, null, null, 0);
            var quads = model.getQuads(null, null, random);
            if (quads.isEmpty()) {
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    quads = model.getQuads(null, dir, random);
                    if (!quads.isEmpty()) break;
                }
            }
            if (quads.isEmpty()) return 0;

            var sprite = quads.get(0).getSprite();
            int width = sprite.contents().width();
            int height = sprite.contents().height();

            var field = sprite.contents().getClass().getDeclaredField("originalImage");
            field.setAccessible(true);
            var nativeImage = (com.mojang.blaze3d.platform.NativeImage) field.get(sprite.contents());
            if (nativeImage == null) return 0;

            long rSum = 0, gSum = 0, bSum = 0;
            int count = 0;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int pixel = nativeImage.getPixelRGBA(x, y);
                    int a = (pixel >> 24) & 0xFF;
                    if (a < 128) continue;
                    rSum += pixel & 0xFF;
                    gSum += (pixel >> 8) & 0xFF;
                    bSum += (pixel >> 16) & 0xFF;
                    count++;
                }
            }
            if (count == 0) return 0;

            int r = (int) (rSum / count);
            int g = (int) (gSum / count);
            int b = (int) (bSum / count);

            float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
            hsb[1] = Math.min(1.0f, hsb[1] * 1.5f);
            hsb[2] = Math.min(1.0f, Math.max(0.4f, hsb[2]));
            return java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) | 0xFF000000;
        } catch (Throwable t) {
            return 0;
        }
    }
}
