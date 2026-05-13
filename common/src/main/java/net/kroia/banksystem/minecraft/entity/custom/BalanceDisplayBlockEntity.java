package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.display.AbstractDisplayBlockEntity;
import net.kroia.modutilities.gui.display.ContentBuilder;
import net.kroia.modutilities.gui.display.DisplayConfig;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class BalanceDisplayBlockEntity extends AbstractDisplayBlockEntity {

    private static final int COLS_PER_DISPLAY = 2;
    private static final int DISPLAY_UNIT = 256;
    private static final int ROW_HEIGHT = 20;
    private static final int MARGIN = 8;
    private static final int FRAME_PAD = 6;
    private static final int HEADER_HEIGHT = 36;

    private static final int FRAME_BG = ColorUtilities.getRGB(20, 20, 30, 180);
    private static final int FRAME_OUTLINE = ColorUtilities.getRGB(80, 100, 140);
    private static final int LABEL_COLOR = ColorUtilities.getRGB(220, 220, 220);

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            256, 256, 2, 4096,
            -14.0f / 16.0f + 0.001f,
            facing -> switch (facing) {
                case NORTH -> Block.box(0, 0, 14, 16, 16, 16);
                case SOUTH -> Block.box(0, 0, 0, 16, 16, 2);
                case EAST  -> Block.box(0, 0, 0, 2, 16, 16);
                case WEST  -> Block.box(14, 0, 0, 16, 16, 16);
                default -> Block.box(0, 0, 14, 16, 16, 16);
            },
            1, 0
    );

    private static BankSystemModBackend.Instances BACKEND;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND = backend;
    }

    private int tickCounter = 0;
    private int slotCount = 0;
    private boolean pendingRebuild = false;
    private Label accountLabel;
    private final List<ItemView> itemIcons = new ArrayList<>();
    private final List<Label> balanceLabels = new ArrayList<>();

    private String pendingAccountText = null;
    private ListTag pendingSlotData = null;

    public BalanceDisplayBlockEntity(BlockPos pos, BlockState blockState) {
        super(BankSystemEntities.BALANCE_DISPLAY_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Override
    public DisplayConfig getDisplayConfig() {
        return DISPLAY_CONFIG;
    }

    @Override
    public ContentBuilder getContentBuilder() {
        int liveCount = queryItemCount();
        if (liveCount > 0) {
            slotCount = liveCount;
        }
        final int count = slotCount;
        return (gui, w, h) -> buildContent(gui, w, h, count);
    }

    @Override
    public String getChannelId() {
        return "12";
    }

    @Override
    protected void wireCallbacks(Gui gui) {
        accountLabel = null;
        itemIcons.clear();
        balanceLabels.clear();
        for (var el : gui.getElements()) {
            if (el instanceof Label l) {
                String id = l.getId();
                if ("account_label".equals(id)) {
                    accountLabel = l;
                } else if (id != null && id.startsWith("bal_")) {
                    balanceLabels.add(l);
                }
            } else if (el instanceof ItemView iv) {
                String id = iv.getId();
                if (id != null && id.startsWith("icon_")) {
                    itemIcons.add(iv);
                }
            }
        }
        itemIcons.sort(Comparator.comparingInt(a -> parseSlotIndex(a.getId())));
        balanceLabels.sort(Comparator.comparingInt(a -> parseSlotIndex(a.getId())));

        applyPendingState();
        updateBalances();
    }

    private static int parseSlotIndex(String id) {
        int u = id.lastIndexOf('_');
        if (u < 0) return 0;
        try { return Integer.parseInt(id.substring(u + 1)); }
        catch (NumberFormatException e) { return 0; }
    }

    @Override
    protected void onControllerTick() {
        if (pendingRebuild) {
            pendingRebuild = false;
            tickCounter = 0;
            rebuildGui();
            return;
        }
        tickCounter++;
        if (tickCounter % 20 == 0 || tickCounter == 1) {
            updateBalances();
        }
    }

    private void rebuildGui() {
        if (gui == null) return;
        gui.removeAllElements();
        DisplayConfig config = getDisplayConfig();
        int w = getGroupWidth() * config.virtualWidth();
        int h = getGroupHeight() * config.virtualHeight();
        getContentBuilder().build(gui, w, h);
        wireCallbacks(gui);
        syncToClientPublic();
    }

    @Override
    protected void saveCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("slotCount", slotCount);
        if (accountLabel != null) {
            tag.putString("accountText", accountLabel.getText());
        }
        ListTag slots = new ListTag();
        int count = Math.min(itemIcons.size(), balanceLabels.size());
        for (int i = 0; i < count; i++) {
            CompoundTag entry = new CompoundTag();
            ItemStack stack = itemIcons.get(i).getItemStack();
            if (stack != null && !stack.isEmpty()) {
                entry.putString("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            entry.putString("text", balanceLabels.get(i).getText());
            slots.add(entry);
        }
        tag.put("slots", slots);
    }

    @Override
    protected void loadCustomData(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("slotCount")) {
            slotCount = tag.getInt("slotCount");
        }
        pendingAccountText = tag.contains("accountText") ? tag.getString("accountText") : null;
        pendingSlotData = tag.contains("slots") ? tag.getList("slots", 10) : null;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    }

    @Override
    public void onInputSynced() {}

    private void applyPendingState() {
        if (pendingAccountText != null && accountLabel != null) {
            accountLabel.setText(pendingAccountText);
            pendingAccountText = null;
        }
        if (pendingSlotData != null) {
            int count = Math.min(pendingSlotData.size(), Math.min(itemIcons.size(), balanceLabels.size()));
            for (int i = 0; i < count; i++) {
                CompoundTag entry = pendingSlotData.getCompound(i);
                if (entry.contains("item")) {
                    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getString("item")));
                    if (item != Items.AIR) {
                        itemIcons.get(i).setItemStack(new ItemStack(item));
                    }
                }
                if (entry.contains("text")) {
                    balanceLabels.get(i).setText(entry.getString("text"));
                }
            }
            pendingSlotData = null;
        }
    }

    private int queryItemCount() {
        if (BACKEND == null || BACKEND.SERVER_BANK_MANAGER == null) return 0;
        ISyncServerBankManager mgr = BACKEND.SERVER_BANK_MANAGER.getSync();
        if (mgr == null) return 0;
        var account = mgr.getBankAccount(1);
        if (account == null) return 0;
        int maxSlots = calculateMaxSlots();
        return Math.min(account.getAccountData().bankData.size(), maxSlots);
    }

    private int calculateMaxSlots() {
        int w = Math.max(DISPLAY_UNIT, getGroupWidth() * getDisplayConfig().virtualWidth());
        int h = Math.max(DISPLAY_UNIT, getGroupHeight() * getDisplayConfig().virtualHeight());
        int totalCols = COLS_PER_DISPLAY * Math.max(1, w / DISPLAY_UNIT);
        int availableH = h - MARGIN - HEADER_HEIGHT - MARGIN;
        int maxRows = Math.max(0, (availableH - FRAME_PAD * 2) / ROW_HEIGHT);
        return totalCols * maxRows;
    }

    private void updateBalances() {
        if (BACKEND == null || BACKEND.SERVER_BANK_MANAGER == null) return;
        ISyncServerBankManager syncManager = BACKEND.SERVER_BANK_MANAGER.getSync();
        if (syncManager == null) return;

        var account = syncManager.getBankAccount(1);
        if (account == null) {
            if (slotCount != 0) {
                slotCount = 0;
                pendingRebuild = true;
                return;
            }
            if (accountLabel != null) {
                accountLabel.setText("Account #1 (not found)");
            }
            syncToClientPublic();
            return;
        }

        BankAccountData data = account.getAccountData();

        List<Map.Entry<ItemID, BankData>> sorted = new ArrayList<>(data.bankData.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().balance(), a.getValue().balance()));

        int maxSlots = calculateMaxSlots();
        int needed = Math.min(sorted.size(), maxSlots);
        if (needed != slotCount) {
            slotCount = needed;
            pendingRebuild = true;
            return;
        }

        if (accountLabel != null) {
            accountLabel.setText(data.accountName);
        }

        for (int i = 0; i < needed; i++) {
            Map.Entry<ItemID, BankData> entry = sorted.get(i);
            ItemID itemId = entry.getKey();
            BankData bankData = entry.getValue();

            if (i < itemIcons.size()) {
                itemIcons.get(i).setItemStack(itemId.getStack());
            }
            if (i < balanceLabels.size()) {
                balanceLabels.get(i).setText(formatCompact(bankData.balance()));
            }
        }
        syncToClientPublic();
    }

    static String formatCompact(long rawAmount) {
        double value = rawAmount / (double) BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        if (value <= 0) return "0";

        String[] suffixes = {"", "k", "M", "G", "T", "P"};
        int idx = 0;
        double d = value;
        while (d >= 1000 && idx < suffixes.length - 1) {
            d /= 1000;
            idx++;
        }

        String result;
        if (idx == 0) {
            if (d == Math.floor(d)) {
                result = String.valueOf((long) d);
            } else {
                result = String.format("%.2f", d);
                result = result.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
        } else {
            if (d >= 100) result = String.format("%.0f", d);
            else if (d >= 10) result = String.format("%.1f", d);
            else result = String.format("%.2f", d);
            if (result.contains(".")) {
                result = result.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            result += suffixes[idx];
        }
        return result;
    }

    private static void buildContent(Gui gui, int w, int h, int itemCount) {
        int iconSize = 16;
        int iconLabelGap = 3;
        int y = MARGIN;

        Label title = new Label("Balance Overview");
        title.setBounds(0, y, w, 14);
        title.setAlignment(GuiElement.Alignment.CENTER);
        title.setTextColor(ColorUtilities.getRGB(200, 220, 255));
        gui.addElement(title);
        y += 18;

        Label accountLabel = new Label("loading...");
        accountLabel.setId("account_label");
        accountLabel.setBounds(0, y, w, 12);
        accountLabel.setAlignment(GuiElement.Alignment.CENTER);
        accountLabel.setTextColor(ColorUtilities.getRGB(170, 170, 190));
        gui.addElement(accountLabel);
        y += 18;

        if (itemCount <= 0) return;

        int displaysWide = Math.max(1, w / DISPLAY_UNIT);
        int totalCols = COLS_PER_DISPLAY * displaysWide;
        int rows = (int) Math.ceil((double) itemCount / totalCols);
        int frameH = rows * ROW_HEIGHT + FRAME_PAD * 2;

        Frame frame = new Frame();
        frame.setBounds(MARGIN, y, w - MARGIN * 2, frameH);
        frame.setEnableBackground(true);
        frame.setBackgroundColor(FRAME_BG);
        frame.setEnableOutline(true);
        frame.setOutlineColor(FRAME_OUTLINE);
        gui.addElement(frame);

        int cellContentW = iconSize + iconLabelGap + 50;
        int slotW = DISPLAY_UNIT / COLS_PER_DISPLAY;
        int slotOffset = (slotW - cellContentW) / 2;

        for (int i = 0; i < itemCount; i++) {
            int col = i % totalCols;
            int row = i / totalCols;

            int displayIdx = col / COLS_PER_DISPLAY;
            int colInDisplay = col % COLS_PER_DISPLAY;

            int cellX = displayIdx * DISPLAY_UNIT + colInDisplay * slotW + slotOffset;
            int cellY = y + FRAME_PAD + row * ROW_HEIGHT;

            ItemView icon = new ItemView(cellX, cellY + 2, iconSize, iconSize);
            icon.setId("icon_" + i);
            icon.setShowTooltip(true);
            gui.addElement(icon);

            Label label = new Label("");
            label.setId("bal_" + i);
            label.setBounds(cellX + iconSize + iconLabelGap, cellY, slotW - iconSize - iconLabelGap - slotOffset, ROW_HEIGHT);
            label.setAlignment(GuiElement.Alignment.LEFT);
            label.setTextColor(LABEL_COLOR);
            gui.addElement(label);
        }
    }
}
