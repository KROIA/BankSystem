package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.entity.CloseStamperBindScreenPacket;
import net.kroia.banksystem.networking.entity.OpenStamperBindScreenPacket;
import net.kroia.banksystem.networking.entity.SetStamperBindingRequest;
import net.kroia.banksystem.screen.widgets.CompanySelectionScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Task #47 (v2.0.8) — Share-Stamper company picker. Now a thin adapter over the
 * reusable {@link CompanySelectionScreen} widget: forwards row-click to
 * {@link SetStamperBindingRequest} and releases the server-side viewer lock on
 * close via {@link CloseStamperBindScreenPacket}.
 * <p>
 * Follow-up (v2.0.8) — after a successful bind the server auto-opens the main
 * {@link ShareStamperScreen}, which naturally replaces this screen and fires
 * {@link #removed()}. To keep the viewer lock across that transition (the
 * container-menu path re-acquires the same lock via {@code stopOpen}), we set
 * {@link #suppressCloseRelease} before dispatching the bind request; the
 * override in {@code removed()} then skips the C2S release packet.
 */
public class StamperBindScreen extends CompanySelectionScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".stamper_bind.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");

    /**
     * Guards the {@link CloseStamperBindScreenPacket} dispatch in {@link #removed()}
     * so an auto-open handoff from the server (post-bind → open container menu)
     * does not clobber the viewer lock that the incoming container menu is about
     * to re-acquire. Also mirrored on {@link CompanySelectionScreen} in spirit —
     * only StamperBindScreen owns the release logic. Reset in a finally block.
     */
    public static volatile boolean suppressCloseRelease = false;

    private final BlockPos pos;

    public StamperBindScreen(BlockPos pos, List<OpenStamperBindScreenPacket.Entry> entries) {
        super(TITLE, toOptions(entries),
                companyId -> onCompanyChosen(pos, companyId),
                () -> {});
        this.pos = pos;
        // Fresh session — clear any stale suppression left over from a prior flow.
        suppressCloseRelease = false;
    }

    private static List<CompanyOption> toOptions(List<OpenStamperBindScreenPacket.Entry> entries) {
        List<CompanyOption> out = new ArrayList<>(entries.size());
        // Server only ships MANAGE-owned companies today, so all rows are enabled.
        // Description slot is left empty — no per-row tooltip needed for this flow.
        for (OpenStamperBindScreenPacket.Entry e : entries) {
            out.add(new CompanyOption(e.companyId(), e.name(), "", true));
        }
        return out;
    }

    private static void onCompanyChosen(BlockPos pos, int companyId) {
        // Suppress the CloseStamperBindScreenPacket that removed() would otherwise
        // fire when this screen tears down after onClose(). The server keeps the
        // same viewer lock through the auto-open handoff (ShareStamperContainerMenu
        // releases via stopOpen when the main GUI eventually closes). The flag is
        // reset when ShareStamperScreen or a fresh StamperBindScreen opens.
        suppressCloseRelease = true;
        SetStamperBindingRequest.send(pos, companyId);
    }

    @Override
    public void removed() {
        // Skip the release when the removal is caused by the post-bind auto-open
        // handoff — the incoming container menu already owns the same viewer slot.
        if (!suppressCloseRelease) {
            CloseStamperBindScreenPacket.send(pos);
        }
        super.removed();
    }
}
