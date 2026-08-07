package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.screen.custom.BankAccountManagementScreen;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Task #51 (v2.0.8, spec §2) — Workers tab: every user with permissions on the
 * company's bound bank account, plus a link-out to the full bank account screen.
 */
public class WorkersTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private final Label titleLabel;
    private final VerticalListView userList;
    private final Button openAccountButton;

    public WorkersTabBody(CompanyManagementScreen screen) {
        super(screen);
        titleLabel = new Label(Component.translatable(PREFIX + "workers_list_title").getString());
        titleLabel.setAlignment(Label.Alignment.LEFT);

        // Spec §0.5 — VerticalListView is allowed for the SCROLLABLE region only.
        userList = new VerticalListView();
        LayoutGrid l = new LayoutGrid();
        l.columns = 1; l.rows = 0; l.spacing = 2; l.padding = 2;
        l.stretchX = true; l.stretchY = false;
        l.alignment = GuiElement.Alignment.TOP;
        userList.setLayout(l);

        openAccountButton = new Button(
                Component.translatable(PREFIX + "open_bank_account").getString(), this::onOpenAccount);

        addChild(titleLabel);
        addChild(userList);
        addChild(openAccountButton);

        fetchUsers();
    }

    private void fetchUsers() {
        var info = screen.info();
        if (info == null || !info.present()) {
            showPlaceholder();
            return;
        }
        screen.clientBankManager().getBankAccountDataAsync(info.bankAccountNr())
                .thenAccept(data -> onClientThread(() -> populate(data)));
    }

    private void showPlaceholder() {
        userList.removeChilds();
        userList.addChild(new Label(Component.translatable(PREFIX + "workers_no_users").getString()));
        layoutChangedInternal();
    }

    private void populate(BankAccountData data) {
        if (data == null) {
            showPlaceholder();
            return;
        }
        var info = screen.info();
        List<String> founders = info != null ? info.founderNames() : List.of();

        List<BankUserData> users = new ArrayList<>();
        if (data.personalBankOwnerData != null) {
            // Owner row synthesized with full MANAGE authority.
            users.add(new BankUserData(data.personalBankOwnerData.userUUID(),
                    data.personalBankOwnerData.userName(), false,
                    BankPermission.MANAGE.getValue()));
        }
        for (BankUserData u : data.users.values()) {
            boolean dup = false;
            for (BankUserData e : users) if (e.userUUID.equals(u.userUUID)) { dup = true; break; }
            if (!dup) users.add(u);
        }
        if (users.isEmpty()) {
            showPlaceholder();
            return;
        }
        userList.removeChilds();
        for (BankUserData u : users) {
            String star = founders.contains(u.userName) ? "★ " : "   ";
            Label row = new Label(star + u.userName + "  —  " + permissionSummary(u.permissions));
            row.setAlignment(Label.Alignment.LEFT);
            userList.addChild(row);
        }
        layoutChangedInternal();
    }

    private static String permissionSummary(int permissions) {
        if (BankPermission.hasPermission(permissions, BankPermission.MANAGE)) return "MANAGE";
        StringBuilder sb = new StringBuilder();
        if (BankPermission.hasPermission(permissions, BankPermission.DEPOSIT)) sb.append("DEPOSIT");
        if (BankPermission.hasPermission(permissions, BankPermission.WITHDRAW)) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append("WITHDRAW");
        }
        return sb.length() > 0 ? sb.toString() : "-";
    }

    private void onOpenAccount() {
        var info = screen.info();
        if (info == null || !info.present()) return;
        BankAccountManagementScreen.openScreen(info.bankAccountNr(), screen, false);
    }

    @Override
    public void onInfoUpdated() {
        fetchUsers();
    }

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int h = getHeight();
        titleLabel.setBounds(PADDING, PADDING, w - 2 * PADDING, ROW_HEIGHT);
        int listTop = PADDING + ROW_HEIGHT + ROW_SPACING;
        int buttonRow = h - PADDING - ROW_HEIGHT;
        userList.setBounds(PADDING, listTop, w - 2 * PADDING,
                Math.max(ROW_HEIGHT, buttonRow - listTop - SECTION_SPACING));
        int btnW = Math.min(220, w - 2 * PADDING);
        openAccountButton.setBounds(w - PADDING - btnW, buttonRow, btnW, ROW_HEIGHT);
    }
}
