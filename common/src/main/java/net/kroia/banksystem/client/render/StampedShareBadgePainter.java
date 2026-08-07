package net.kroia.banksystem.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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

        drawFilledQuad(pose, buffers, tint);

        if (hasVisuals && isGuiLikeContext(context)) {
            String preset = visuals.getFgLayer().symbolId();
            if (preset == null || preset.isBlank()) {
                String initials = BankSystemColorHandlers.resolveMonogramInitials(companyId, visuals);
                int fgTint = visuals.getFgLayer().tint();
                if ((fgTint & 0xFF000000) == 0) fgTint |= 0xFF000000;
                drawInitials(pose, buffers, initials, fgTint, packedLight);
            }
            // TODO(v2.0.9): preset sprite draw tinted with fg tint once atlas ships.
        }
        return true;
    }

    private static boolean isGuiLikeContext(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.GUI
                || ctx == ItemDisplayContext.FIXED
                || ctx == ItemDisplayContext.GROUND;
    }

    private static void drawFilledQuad(PoseStack pose, MultiBufferSource buffers, int argb) {
        var consumer = buffers.getBuffer(RenderType.gui());
        Matrix4f m = pose.last().pose();
        int a = FastColor.ARGB32.alpha(argb);
        int r = FastColor.ARGB32.red(argb);
        int g = FastColor.ARGB32.green(argb);
        int b = FastColor.ARGB32.blue(argb);
        consumer.addVertex(m, 0f,  0f,  0f).setColor(r, g, b, a);
        consumer.addVertex(m, 0f,  16f, 0f).setColor(r, g, b, a);
        consumer.addVertex(m, 16f, 16f, 0f).setColor(r, g, b, a);
        consumer.addVertex(m, 16f, 0f,  0f).setColor(r, g, b, a);
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
