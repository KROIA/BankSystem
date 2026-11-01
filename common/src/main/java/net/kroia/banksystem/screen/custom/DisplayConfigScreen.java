package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.banksystem.networking.entity.UpdateDisplayBlockConfigPacket;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutVertical;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

public class DisplayConfigScreen extends BankSystemGuiScreen {

    private final BlockPos blockPos;
    private BankSystemDisplayBlockEntity.DisplayType selectedType;
    private int selectedAccount;
    private String selectedAccountName = "";

    private final Label titleLabel;
    private final Label typeLabel;
    private final Button overviewButton;
    private final Button historyButton;
    private final Label accountLabel;
    private final VerticalListView accountListView;
    private final Label selectedLabel;
    private final Button applyButton;

    private static final int ACTIVE_COLOR = ColorUtilities.getRGB(40, 120, 80);
    private static final int INACTIVE_COLOR = ColorUtilities.getRGB(60, 60, 60);
    private static final int HOVER_COLOR = ColorUtilities.getRGB(60, 140, 100);

    public DisplayConfigScreen(BlockPos blockPos, BankSystemDisplayBlockEntity.DisplayType currentType, int currentAccount) {
        super(Component.literal("Configure Display"));
        this.blockPos = blockPos;
        this.selectedType = (currentType != null && currentType != BankSystemDisplayBlockEntity.DisplayType.NONE)
                ? currentType : BankSystemDisplayBlockEntity.DisplayType.BALANCE_OVERVIEW;
        this.selectedAccount = currentAccount;

        titleLabel = new Label("Configure Display");
        titleLabel.setAlignment(GuiElement.Alignment.CENTER);
        addElement(titleLabel);

        typeLabel = new Label("Display Type:");
        addElement(typeLabel);

        overviewButton = new Button("Balance Overview");
        overviewButton.setOnFallingEdge(() -> {
            selectedType = BankSystemDisplayBlockEntity.DisplayType.BALANCE_OVERVIEW;
            updateTypeButtons();
        });
        addElement(overviewButton);

        historyButton = new Button("Balance History");
        historyButton.setOnFallingEdge(() -> {
            selectedType = BankSystemDisplayBlockEntity.DisplayType.BALANCE_HISTORY;
            updateTypeButtons();
        });
        addElement(historyButton);

        accountLabel = new Label("Select Account:");
        addElement(accountLabel);

        accountListView = new VerticalListView();
        LayoutVertical layout = new LayoutVertical();
        layout.stretchX = true;
        accountListView.setLayout(layout);
        addElement(accountListView);

        selectedLabel = new Label("");
        addElement(selectedLabel);

        applyButton = new Button("Apply");
        applyButton.setOnFallingEdge(this::onApply);
        addElement(applyButton);

        updateTypeButtons();
        loadAccounts();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int w = getWidth();
        int h = getHeight();
        int p = 8;
        int centerW = Math.min(300, w - p * 2);
        int x = (w - centerW) / 2;
        int y = p;

        titleLabel.setBounds(x, y, centerW, 18);
        y += 24;

        typeLabel.setBounds(x, y, centerW, 14);
        y += 18;

        int halfW = (centerW - p) / 2;
        overviewButton.setBounds(x, y, halfW, 20);
        historyButton.setBounds(x + halfW + p, y, halfW, 20);
        y += 28;

        accountLabel.setBounds(x, y, centerW, 14);
        y += 18;

        int listH = h - y - 60;
        accountListView.setBounds(x, y, centerW, Math.max(40, listH));
        y += Math.max(40, listH) + p;

        selectedLabel.setBounds(x, y, centerW, 14);
        y += 20;

        applyButton.setBounds(x, y, centerW, 22);
    }

    private void updateTypeButtons() {
        boolean isOverview = selectedType == BankSystemDisplayBlockEntity.DisplayType.BALANCE_OVERVIEW;
        overviewButton.setBackgroundColor(isOverview ? ACTIVE_COLOR : INACTIVE_COLOR);
        overviewButton.setHoverColor(isOverview ? ACTIVE_COLOR : HOVER_COLOR);
        historyButton.setBackgroundColor(!isOverview ? ACTIVE_COLOR : INACTIVE_COLOR);
        historyButton.setHoverColor(!isOverview ? ACTIVE_COLOR : HOVER_COLOR);
    }

    private void updateSelectedLabel() {
        if (selectedAccount > 0) {
            String name = selectedAccountName.isEmpty() ? "#" + selectedAccount : selectedAccountName;
            selectedLabel.setText("Selected: " + name + " (#" + selectedAccount + ")");
        } else {
            selectedLabel.setText("No account selected");
        }
    }

    private void loadAccounts() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        getBankManager().getBankAccountsDataAsync(player.getUUID()).thenAccept(this::onAccountsReceived);
    }

    private void onAccountsReceived(List<BankAccountData> accounts) {
        Minecraft.getInstance().execute(() -> {
            accountListView.removeChilds();
            if (accounts == null || accounts.isEmpty()) {
                Label noAccounts = new Label("No accounts found");
                noAccounts.setAlignment(GuiElement.Alignment.CENTER);
                accountListView.addChild(noAccounts);
                updateSelectedLabel();
                return;
            }

            for (BankAccountData account : accounts) {
                if (!account.hasAnyPermission(Minecraft.getInstance().player.getUUID(), BankPermission.getAllPermissions()))
                    continue;

                GuiElement row = new GuiElement(0, 0, 100, 22) {
                    @Override protected void render() {}
                    @Override protected void layoutChanged() {
                        for (var child : getChilds()) {
                            if (child instanceof ItemView iv) {
                                iv.setBounds(2, 2, 18, 18);
                            } else if (child instanceof Button btn) {
                                btn.setBounds(22, 0, getWidth() - 22, 22);
                            }
                        }
                    }
                };
                row.setEnableBackground(false);
                row.setEnableOutline(false);

                ItemView icon = new ItemView();
                icon.setItemStack(account.accountIcon != null ? account.accountIcon.getStack() : null);
                icon.setShowTooltip(true);
                row.addChild(icon);

                boolean isSelected = account.accountNumber == selectedAccount;
                Button btn = new Button(account.accountName + " (#" + account.accountNumber + ")");
                btn.setBackgroundColor(isSelected ? ACTIVE_COLOR : INACTIVE_COLOR);
                btn.setHoverColor(HOVER_COLOR);
                btn.setOnFallingEdge(() -> {
                    selectedAccount = account.accountNumber;
                    selectedAccountName = account.accountName;
                    refreshAccountHighlights();
                    updateSelectedLabel();
                });
                row.addChild(btn);

                accountListView.addChild(row);

                if (isSelected) {
                    selectedAccountName = account.accountName;
                }
            }
            updateSelectedLabel();
        });
    }

    private void refreshAccountHighlights() {
        for (var child : accountListView.getChilds()) {
            for (var sub : child.getChilds()) {
                if (sub instanceof Button btn) {
                    String text = btn.getText();
                    boolean isThis = text.endsWith("#" + selectedAccount + ")");
                    btn.setBackgroundColor(isThis ? ACTIVE_COLOR : INACTIVE_COLOR);
                }
            }
        }
    }

    private void onApply() {
        if (selectedAccount <= 0 || selectedType == null || selectedType == BankSystemDisplayBlockEntity.DisplayType.NONE) {
            return;
        }
        UpdateDisplayBlockConfigPacket.sendToServer(blockPos, selectedType.getId(), selectedAccount);
        onClose();
    }
}
