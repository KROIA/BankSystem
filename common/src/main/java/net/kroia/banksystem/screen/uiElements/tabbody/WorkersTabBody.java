package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.networking.general.UpdateBankAccountRequest;
import net.kroia.banksystem.screen.custom.BankAccountManagementScreen;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.custom.UserSelectionScreen;
import net.kroia.banksystem.screen.uiElements.BankUserWidget;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * Task #51 (v2.0.8, spec §2) — Workers tab: every user with permissions on the
 * company's bound bank account.  MANAGE/founder users get full edit controls
 * (permission edit + remove via {@link BankUserWidget}); read-only viewers see
 * name-only rows.
 */
public class WorkersTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private final Label titleLabel;
    private final VerticalListView userList;
    private final Button addUserButton;
    private final Button openAccountButton;
    private final boolean canManage;

    /** Live widget map — keyed by UUID for dedup and targeted removal. */
    private final Map<UUID, BankUserWidget> userWidgets = new LinkedHashMap<>();

    public WorkersTabBody(CompanyManagementScreen screen) {
        super(screen);
        this.canManage = screen.canManageNow() || screen.isFounderNow();

        titleLabel = new Label(Component.translatable(PREFIX + "workers_list_title").getString());
        titleLabel.setAlignment(Label.Alignment.LEFT);

        userList = new VerticalListView();
        LayoutGrid l = new LayoutGrid();
        l.columns = 1; l.rows = 0; l.spacing = 2; l.padding = 2;
        l.stretchX = true; l.stretchY = false;
        l.alignment = GuiElement.Alignment.TOP;
        userList.setLayout(l);

        addUserButton = new Button(
                Component.translatable(PREFIX + "add_worker").getString(), this::onAddUser);
        openAccountButton = new Button(
                Component.translatable(PREFIX + "open_bank_account").getString(), this::onOpenAccount);

        addUserButton.setEnabled(canManage);

        addChild(titleLabel);
        addChild(userList);
        addChild(addUserButton);
        addChild(openAccountButton);

        fetchUsers();
    }

    // ------------------------------------------------------------------
    // Data fetch + population
    // ------------------------------------------------------------------

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
        userWidgets.clear();
        layoutChangedInternal();
    }

    private void populate(BankAccountData data) {
        if (data == null) {
            showPlaceholder();
            return;
        }
        var info = screen.info();
        List<String> founders = (info != null && info.founderNames() != null)
                ? info.founderNames() : List.of();

        userList.removeChilds();
        userWidgets.clear();

        for (BankUserData u : data.users.values()) {
            BankUserWidget widget = new BankUserWidget(u, this::scheduleRemove, canManage, screen);
            widget.setOnPermissionChanged(this::save);
            // Founders' remove buttons are locked — founder management is the Danger tab's domain.
            if (founders.contains(u.userName)) {
                widget.setRemoveButtonEnabled(false);
            }
            userWidgets.put(u.userUUID, widget);
            userList.addChild(widget);
        }

        if (userWidgets.isEmpty()) {
            showPlaceholder();
            return;
        }
        refreshRemoveButtonStates(founders);
        layoutChangedInternal();
    }

    /**
     * Locks the remove button on the sole remaining non-founder user (removing
     * the last user would orphan the account on the server side).
     */
    private void refreshRemoveButtonStates(List<String> founders) {
        long nonFounderCount = userWidgets.values().stream()
                .filter(w -> !founders.contains(w.getUserData().userName))
                .count();
        for (BankUserWidget w : userWidgets.values()) {
            boolean isFounder = founders.contains(w.getUserData().userName);
            if (isFounder) {
                w.setRemoveButtonEnabled(false);
            } else {
                // Last non-founder must not be removed — would orphan the account.
                w.setRemoveButtonEnabled(nonFounderCount > 1);
            }
        }
    }

    /**
     * Defers removal of a widget to the main thread to avoid ConcurrentModificationException
     * while the list is being rendered.
     */
    private void scheduleRemove(BankUserWidget widget) {
        Minecraft.getInstance().tell(() -> {
            userList.removeChild(widget);
            userWidgets.remove(widget.getUserData().userUUID);
            var info = screen.info();
            List<String> founders = (info != null && info.founderNames() != null)
                    ? info.founderNames() : List.of();
            refreshRemoveButtonStates(founders);
            layoutChangedInternal();
            save();
        });
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onAddUser() {
        if (!canManage) return;
        screen.clientBankManager().getBankManagerDataAsync().thenAccept(bankManagerData -> {
            if (bankManagerData == null) return;
            onClientThread(() -> {
                UserSelectionScreen sel = new UserSelectionScreen(screen, (userData) -> {
                    if (userWidgets.containsKey(userData.userUUID())) return;
                    BankUserData bud = new BankUserData(
                            userData.userUUID(), userData.userName(), false,
                            BankPermission.DEPOSIT.getValue());
                    BankUserWidget widget = new BankUserWidget(bud, this::scheduleRemove, canManage, screen);
                    widget.setOnPermissionChanged(this::save);
                    userWidgets.put(userData.userUUID(), widget);
                    userList.addChild(widget);
                    var info = screen.info();
                    List<String> founders = (info != null && info.founderNames() != null)
                            ? info.founderNames() : List.of();
                    refreshRemoveButtonStates(founders);
                    layoutChangedInternal();
                    save();
                });
                List<UserData> all = new ArrayList<>(
                        bankManagerData.userMapData().userMap().values());
                all.removeIf(u -> userWidgets.containsKey(u.userUUID()));
                sel.setUsers(all);
                Minecraft.getInstance().setScreen(sel);
            });
        });
    }

    /** Pushes the current worker roster + permissions to the server. Fired by every edit. */
    private void save() {
        if (!canManage) return;
        var info = screen.info();
        if (info == null || !info.present()) return;

        Map<UUID, Integer> setUsers = new HashMap<>();
        for (BankUserWidget w : userWidgets.values()) {
            BankUserData ud = w.getUserData();
            setUsers.put(ud.userUUID, ud.permissions);
        }

        UpdateBankAccountRequest.InputData input = new UpdateBankAccountRequest.InputData(
                info.bankAccountNr(),
                "",          // no account-name change
                null,        // no icon change
                List.of(),   // no bank-slot changes
                setUsers);

        screen.clientBankManager().requestUpdateBankAccount(input)
                .thenAccept(data -> onClientThread(() -> populate(data)));
    }

    private void onOpenAccount() {
        var info = screen.info();
        if (info == null || !info.present()) return;
        BankAccountManagementScreen.openScreen(info.bankAccountNr(), screen, false);
    }

    // ------------------------------------------------------------------
    // TabBody contract
    // ------------------------------------------------------------------

    @Override
    public void onInfoUpdated() {
        fetchUsers();
    }

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int h = getHeight();

        // Title row
        titleLabel.setBounds(PADDING, PADDING, w - 2 * PADDING, ROW_HEIGHT);
        int listTop = PADDING + ROW_HEIGHT + ROW_SPACING;

        // Bottom row: all action buttons side-by-side
        int btnRowY = h - PADDING - ROW_HEIGHT;
        int openBtnW = Math.min(220, w - 2 * PADDING);

        if (canManage) {
            int manageBtnW = Math.min(120, w - 2 * PADDING - ROW_SPACING - openBtnW);
            addUserButton.setBounds(PADDING, btnRowY, manageBtnW, ROW_HEIGHT);
        } else {
            addUserButton.setBounds(0, 0, 0, 0);
        }
        openAccountButton.setBounds(w - PADDING - openBtnW, btnRowY, openBtnW, ROW_HEIGHT);

        // User list fills all space between title and the button row
        int listHeight = Math.max(ROW_HEIGHT, btnRowY - ROW_SPACING - listTop);
        userList.setBounds(PADDING, listTop, w - 2 * PADDING, listHeight);
    }
}
