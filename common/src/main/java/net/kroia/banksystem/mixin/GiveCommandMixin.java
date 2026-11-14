package net.kroia.banksystem.mixin;

import net.kroia.banksystem.banking.company.ShareIntegrity;
import net.kroia.banksystem.data.NoteFlowLog;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * BS-227 — logs money notes handed out by {@code /give}: one {@link NoteFlowLog#GIVE} row per target.
 * BS-235 — stamped shares handed out by {@code /give} raise a SHARE_CREATED alert per target.
 */
@Mixin(GiveCommand.class)
public abstract class GiveCommandMixin {
    @Inject(method = "giveItem", at = @At("RETURN"))
    private static void banksystem$logGive(CommandSourceStack source, ItemInput item, Collection<ServerPlayer> targets, int count,
                                           CallbackInfoReturnable<Integer> cir) {
        try {
            if (cir.getReturnValue() <= 0 || count <= 0) return;
            ItemStack stack = item.createItemStack(1, false);
            Integer companyId = StampedShareItem.getCompanyId(stack);
            if (companyId != null) { // BS-235: shares that did not come from a stamper
                for (ServerPlayer target : targets)
                    ShareIntegrity.reportCreated(source.getTextName(), companyId, count,
                            "/give to " + target.getName().getString());
                return;
            }
            if (!MoneyItem.isMoney(stack)) return;
            ItemID id = ItemIDManager.getItemID(stack);
            if (id.getShort() == ItemID.INVALID_ID.getShort()) return;
            String context = source.getTextName();
            for (ServerPlayer target : targets)
                NoteFlowLog.INSTANCE.record(NoteFlowLog.GIVE, target.getUUID(), id, count, context);
        } catch (Exception ignored) {
            // diagnostics never affect gameplay
        }
    }
}
