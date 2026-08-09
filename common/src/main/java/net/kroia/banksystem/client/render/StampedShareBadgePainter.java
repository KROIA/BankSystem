package net.kroia.banksystem.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.client.company.SharePresetRegistry;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * Task #53 (v2.0.8) — shared body of the stamped-share item renderer.
 * The stamped_share model uses {@code builtin/entity} so the vanilla item-model
 * quad path is bypassed; this painter is the complete visual for every display
 * context. Tinted 16x16 quad everywhere, plus monogram initials in GUI / FIXED
 * / GROUND when the company has no preset icon set.
 */
public final class StampedShareBadgePainter implements IShareItemBadgePainter {

    public static final StampedShareBadgePainter INSTANCE = new StampedShareBadgePainter();

    private static final ResourceLocation BASE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("banksystem", "textures/item/stamped_share.png");

    private StampedShareBadgePainter() {}

    @Override
    public boolean paint(ItemStack stack,
                         ItemDisplayContext context,
                         PoseStack pose,
                         MultiBufferSource buffers,
                         int packedLight,
                         int packedOverlay) {
        if (stack == null || stack.isEmpty()) return false;

        Integer companyId = StampedShareItem.getCompanyId(stack);
        int tint;
        ShareVisuals visuals = null;
        boolean hasVisuals = false;
        if (companyId != null) {
            if (ShareVisualCache.has(companyId)) {
                visuals = ShareVisualCache.getVisualsOrPlaceholder(companyId);
                tint = visuals.getBgLayer().tint();
                hasVisuals = true;
            } else {
                ShareVisualCache.tryLookup(companyId);
                tint = 0xFF808080;
            }
        } else {
            tint = 0xFFFFFFFF;
        }
        if ((tint & 0xFF000000) == 0) tint |= 0xFF000000;

        // ItemRenderer.renderGuiItem pre-applies scale(16, -16, 16) before calling BEWLR.
        // Correct for this so the painter's 0-16 pixel coordinates land in the 16x16 slot.
        // For GUI: translate to top-left corner and cancel the (16,-16,16) scale.
        // For other contexts: center and scale to ~1-unit footprint.
        pose.pushPose();
        if (context == ItemDisplayContext.GUI) {
            pose.translate(0f, 1f, 0f);
            pose.scale(1f / 16f, -1f / 16f, 1f / 16f);
        } else {
            // World contexts (frame, ground, hands): vanilla has already applied
            // the display transform plus translate(-0.5,-0.5,-0.5), so map the
            // painter's 0-16 pixels onto the 0-1 item cube. The +0.5 z pushes the
            // quad back onto the item plane (frame face) instead of floating half
            // a unit in front. Z stays unscaled so the 0.01 painter-space layer
            // offsets remain real depth separation (avoids z-fighting on ground).
            pose.translate(0f, 1f, 0.5f);
            pose.scale(1f / 16f, -1f / 16f, 1f);
        }

        drawFilledQuad(pose, buffers, tint, packedLight);
        if (context != ItemDisplayContext.GUI) {
            drawCardEdges(pose, buffers, tint, packedLight);
        }

        if (hasVisuals && isGuiLikeContext(context)) {
            String preset = visuals.getFgLayer().symbolId();
            int fgTint = visuals.getFgLayer().tint();
            if ((fgTint & 0xFF000000) == 0) fgTint |= 0xFF000000;
            if (preset != null && !preset.isBlank()) {
                ResourceLocation texture = SharePresetRegistry.getTexture(preset);
                drawTexturedQuad(pose, buffers, texture, fgTint, packedLight);
            } else {
                String initials = BankSystemColorHandlers.resolveMonogramInitials(companyId, visuals);
                drawInitials(pose, buffers, initials, fgTint, packedLight);
            }
        }

        pose.popPose();
        return true;
    }

    private static boolean isGuiLikeContext(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.GUI
                || ctx == ItemDisplayContext.FIXED
                || ctx == ItemDisplayContext.GROUND;
    }

    private static void drawTexturedQuad(PoseStack pose, MultiBufferSource buffers,
                                          ResourceLocation texture, int argb, int packedLight) {
        var consumer = buffers.getBuffer(RenderType.text(texture));
        Matrix4f m = pose.last().pose();
        int a = FastColor.ARGB32.alpha(argb);
        int r = FastColor.ARGB32.red(argb);
        int g = FastColor.ARGB32.green(argb);
        int b = FastColor.ARGB32.blue(argb);
        int lu = packedLight & 0xFFFF, lv = (packedLight >> 16) & 0xFFFF;
        // front face
        consumer.addVertex(m,  0f,  0f, 0.01f).setColor(r, g, b, a).setUv(0f, 0f).setUv2(lu, lv);
        consumer.addVertex(m,  0f, 16f, 0.01f).setColor(r, g, b, a).setUv(0f, 1f).setUv2(lu, lv);
        consumer.addVertex(m, 16f, 16f, 0.01f).setColor(r, g, b, a).setUv(1f, 1f).setUv2(lu, lv);
        consumer.addVertex(m, 16f,  0f, 0.01f).setColor(r, g, b, a).setUv(1f, 0f).setUv2(lu, lv);
        // back face (reversed winding, mirrored U)
        consumer.addVertex(m,  0f,  0f, -0.0625f).setColor(r, g, b, a).setUv(1f, 0f).setUv2(lu, lv);
        consumer.addVertex(m, 16f,  0f, -0.0625f).setColor(r, g, b, a).setUv(0f, 0f).setUv2(lu, lv);
        consumer.addVertex(m, 16f, 16f, -0.0625f).setColor(r, g, b, a).setUv(0f, 1f).setUv2(lu, lv);
        consumer.addVertex(m,  0f, 16f, -0.0625f).setColor(r, g, b, a).setUv(1f, 1f).setUv2(lu, lv);
    }

    private static void drawFilledQuad(PoseStack pose, MultiBufferSource buffers, int argb, int packedLight) {
        var consumer = buffers.getBuffer(RenderType.text(BASE_TEXTURE));
        Matrix4f m = pose.last().pose();
        int a = FastColor.ARGB32.alpha(argb);
        int r = FastColor.ARGB32.red(argb);
        int g = FastColor.ARGB32.green(argb);
        int b = FastColor.ARGB32.blue(argb);
        int lu = packedLight & 0xFFFF, lv = (packedLight >> 16) & 0xFFFF;
        // front face
        consumer.addVertex(m,  0f,  0f, 0f).setColor(r, g, b, a).setUv(0f, 0f).setUv2(lu, lv);
        consumer.addVertex(m,  0f, 16f, 0f).setColor(r, g, b, a).setUv(0f, 1f).setUv2(lu, lv);
        consumer.addVertex(m, 16f, 16f, 0f).setColor(r, g, b, a).setUv(1f, 1f).setUv2(lu, lv);
        consumer.addVertex(m, 16f,  0f, 0f).setColor(r, g, b, a).setUv(1f, 0f).setUv2(lu, lv);
        // back face (reversed winding, mirrored U)
        consumer.addVertex(m,  0f,  0f, -0.0625f).setColor(r, g, b, a).setUv(1f, 0f).setUv2(lu, lv);
        consumer.addVertex(m, 16f,  0f, -0.0625f).setColor(r, g, b, a).setUv(0f, 0f).setUv2(lu, lv);
        consumer.addVertex(m, 16f, 16f, -0.0625f).setColor(r, g, b, a).setUv(0f, 1f).setUv2(lu, lv);
        consumer.addVertex(m,  0f, 16f, -0.0625f).setColor(r, g, b, a).setUv(1f, 1f).setUv2(lu, lv);
    }

    private static void drawCardEdges(PoseStack pose, MultiBufferSource buffers, int argb, int packedLight) {
        var consumer = buffers.getBuffer(RenderType.text(BASE_TEXTURE));
        Matrix4f m = pose.last().pose();
        int a = FastColor.ARGB32.alpha(argb);
        int r = (int)(FastColor.ARGB32.red(argb)   * 0.65f);
        int g = (int)(FastColor.ARGB32.green(argb) * 0.65f);
        int b = (int)(FastColor.ARGB32.blue(argb)  * 0.65f);
        int lu = packedLight & 0xFFFF, lv = (packedLight >> 16) & 0xFFFF;
        float f = 0.01f;   // front z — matches fg face so edges seal flush
        float t = -0.0625f; // back z
        // top edge (y=0)
        consumer.addVertex(m,  0f, 0f,  f).setColor(r,g,b,a).setUv(0f,0f).setUv2(lu,lv);
        consumer.addVertex(m, 16f, 0f,  f).setColor(r,g,b,a).setUv(1f,0f).setUv2(lu,lv);
        consumer.addVertex(m, 16f, 0f,  t).setColor(r,g,b,a).setUv(1f,1f).setUv2(lu,lv);
        consumer.addVertex(m,  0f, 0f,  t).setColor(r,g,b,a).setUv(0f,1f).setUv2(lu,lv);
        // bottom edge (y=16)
        consumer.addVertex(m,  0f, 16f, t).setColor(r,g,b,a).setUv(0f,0f).setUv2(lu,lv);
        consumer.addVertex(m, 16f, 16f, t).setColor(r,g,b,a).setUv(1f,0f).setUv2(lu,lv);
        consumer.addVertex(m, 16f, 16f, f).setColor(r,g,b,a).setUv(1f,1f).setUv2(lu,lv);
        consumer.addVertex(m,  0f, 16f, f).setColor(r,g,b,a).setUv(0f,1f).setUv2(lu,lv);
        // left edge (x=0)
        consumer.addVertex(m,  0f,  0f, t).setColor(r,g,b,a).setUv(0f,0f).setUv2(lu,lv);
        consumer.addVertex(m,  0f,  0f, f).setColor(r,g,b,a).setUv(1f,0f).setUv2(lu,lv);
        consumer.addVertex(m,  0f, 16f, f).setColor(r,g,b,a).setUv(1f,1f).setUv2(lu,lv);
        consumer.addVertex(m,  0f, 16f, t).setColor(r,g,b,a).setUv(0f,1f).setUv2(lu,lv);
        // right edge (x=16)
        consumer.addVertex(m, 16f,  0f, f).setColor(r,g,b,a).setUv(0f,0f).setUv2(lu,lv);
        consumer.addVertex(m, 16f,  0f, t).setColor(r,g,b,a).setUv(1f,0f).setUv2(lu,lv);
        consumer.addVertex(m, 16f, 16f, t).setColor(r,g,b,a).setUv(1f,1f).setUv2(lu,lv);
        consumer.addVertex(m, 16f, 16f, f).setColor(r,g,b,a).setUv(0f,1f).setUv2(lu,lv);
    }

    private static void drawInitials(PoseStack pose, MultiBufferSource buffers, String initials,
                                     int tintARGB, int packedLight) {
        Font font = Minecraft.getInstance().font;
        int textW = font.width(initials);
        int textH = font.lineHeight;
        float scale = Math.min((16f - 2f) / Math.max(textW, 1), (16f - 2f) / Math.max(textH, 1));
        if (scale <= 0f) return;

        int r = (tintARGB >> 16) & 0xFF;
        int g = (tintARGB >>  8) & 0xFF;
        int b =  tintARGB        & 0xFF;
        int luma = (299 * r + 587 * g + 114 * b) / 1000;
        int textColor = luma > 140 ? 0xFF202020 : 0xFFF0F0F0;

        pose.pushPose();
        pose.translate(8f, 8f, 0.03f);
        pose.scale(scale, scale, 1f);
        pose.translate(-textW / 2f, -textH / 2f, 0f);
        font.drawInBatch(initials, 0f, 0f, textColor, false, pose.last().pose(),
                buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();
    }
}
