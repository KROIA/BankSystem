package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.share.BlankShareItem;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Task #47 (v2.0.8) — Share Stamper Block Entity. Two slots: input (0), output (1).
 * Master-only tick; slave BEs idle. Bound via {@code /company stamper-bind}.
 */
public class ShareStamperBlockEntity extends BaseContainerBlockEntity implements MenuProvider, WorldlyContainer {

    public enum Mode { STAMP, REDEEM }

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;
    public static final int STAMP_TICKS_PER_CYCLE = 200;

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) { BACKEND_INSTANCES = backend; }

    private static final Component TITLE =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".share_stamper");

    private final NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);

    private int boundCompanyId = -1;
    private UUID linkedByUUID = null;
    private long linkedAt = 0L;
    private int stampProgress = 0;
    private int queuedStamps = 0;
    private Mode mode = Mode.STAMP;
    private boolean allowHopperRedeem = false;

    private boolean idleLoggedOnce = false;

    // ContainerData for menu sync.
    public final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            long ti = getTotalIssued();
            long ms = getMaxSupply();
            return switch (index) {
                case 0 -> stampProgress;
                case 1 -> queuedStamps;
                case 2 -> boundCompanyId;
                case 3 -> mode.ordinal();
                case 4 -> allowHopperRedeem ? 1 : 0;
                case 5 -> (int) (ti & 0xFFFF);
                case 6 -> (int) ((ti >> 16) & 0xFFFF);
                case 7 -> (int) (ms & 0xFFFF);
                case 8 -> (int) ((ms >> 16) & 0xFFFF);
                default -> 0;
            };
        }
        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> stampProgress = value;
                case 1 -> queuedStamps = value;
                case 2 -> boundCompanyId = value;
                case 3 -> mode = value == 1 ? Mode.REDEEM : Mode.STAMP;
                case 4 -> allowHopperRedeem = value != 0;
            }
        }
        @Override
        public int getCount() { return 9; }
    };

    public ShareStamperBlockEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.SHARE_STAMPER_BLOCK_ENTITY.get(), pos, state);
    }

    // -------- accessors --------
    public int getBoundCompanyId() { return boundCompanyId; }
    public UUID getLinkedByUUID() { return linkedByUUID; }
    public long getLinkedAt() { return linkedAt; }
    public Mode getMode() { return mode; }
    public boolean isAllowHopperRedeem() { return allowHopperRedeem; }
    public int getQueuedStamps() { return queuedStamps; }
    public int getStampProgress() { return stampProgress; }

    public String getBoundCompanyName() {
        CompanyManager cm = CompanyManager.get();
        if (cm == null || boundCompanyId < 0) return String.valueOf(boundCompanyId);
        Company c = cm.getById(boundCompanyId);
        return c != null ? c.getName() : "#" + boundCompanyId;
    }

    private long getTotalIssued() {
        CompanyManager cm = CompanyManager.get();
        if (cm == null || boundCompanyId < 0) return 0L;
        Company c = cm.getById(boundCompanyId);
        return c != null ? c.getTotalSharesIssued() : 0L;
    }
    private long getMaxSupply() {
        CompanyManager cm = CompanyManager.get();
        if (cm == null || boundCompanyId < 0) return 0L;
        Company c = cm.getById(boundCompanyId);
        return c != null ? c.getMaxSupply() : 0L;
    }

    /** MANAGE gate check — founder OR MANAGE on bound account OR banksystem admin. Master-only meaningful. */
    public boolean hasManagePermission(UUID uuid) {
        if (boundCompanyId < 0) return false;
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return false;
        Company company = cm.getById(boundCompanyId);
        if (company == null) return false;
        if (company.isFounder(uuid)) return true;
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) return false;
        IServerBankManager bm = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (bm == null) return false;
        if (bm.isBanksystemAdmin(uuid)) return true;
        IServerBankAccount acc = bm.getBankAccount(company.getBankAccountNr());
        return acc != null && acc.hasPermission(uuid, BankPermission.MANAGE);
    }

    // -------- mutation from packet handler / command --------
    public void bind(int companyId, UUID linkedBy) {
        this.boundCompanyId = companyId;
        this.linkedByUUID = linkedBy;
        this.linkedAt = System.currentTimeMillis();
        this.stampProgress = 0;
        setChanged();
    }
    public void unbind() {
        this.boundCompanyId = -1;
        this.linkedByUUID = null;
        this.linkedAt = 0L;
        this.stampProgress = 0;
        this.queuedStamps = 0;
        setChanged();
    }
    public void setMode(Mode m) { this.mode = m; this.stampProgress = 0; setChanged(); }
    public void queueStamps(int add) {
        if (add <= 0) return;
        this.queuedStamps = Math.min(Integer.MAX_VALUE - 1, this.queuedStamps + add);
        setChanged();
    }
    public void toggleHopperRedeem() { this.allowHopperRedeem = !this.allowHopperRedeem; setChanged(); }

    // -------- tick --------
    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T t) {
        if (level.isClientSide) return;
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer) return;
        if (!(t instanceof ShareStamperBlockEntity be)) return;
        be.serverTick();
    }

    private void serverTick() {
        if (boundCompanyId < 0) return;
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return;
        Company company = cm.getById(boundCompanyId);
        if (company == null) {
            if (!idleLoggedOnce) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.warn("[ShareStamper] bound company #" + boundCompanyId
                            + " missing at " + worldPosition);
                }
                idleLoggedOnce = true;
            }
            return;
        }
        idleLoggedOnce = false;

        ItemStack input = items.get(SLOT_INPUT);
        ItemStack output = items.get(SLOT_OUTPUT);

        if (mode == Mode.STAMP) {
            if (input.isEmpty() || !(input.getItem() instanceof BlankShareItem)) {
                stampProgress = 0; return;
            }
            if (company.getTotalSharesIssued() + 1 > company.getMaxSupply()) {
                stampProgress = 0; return;
            }
            ItemStack expected = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), boundCompanyId);
            if (!output.isEmpty()) {
                if (!ItemStack.isSameItemSameComponents(output, expected)
                        || output.getCount() >= output.getMaxStackSize()) {
                    stampProgress = 0; return;
                }
            }
        } else { // REDEEM
            if (input.isEmpty() || !(input.getItem() instanceof StampedShareItem)) {
                stampProgress = 0; return;
            }
            Integer cid = StampedShareItem.getCompanyId(input);
            if (cid == null || cid != boundCompanyId) { stampProgress = 0; return; }
            if (company.getTotalSharesIssued() <= 0L) { stampProgress = 0; return; }
            if (!output.isEmpty()) {
                if (!(output.getItem() instanceof BlankShareItem)
                        || output.getCount() >= output.getMaxStackSize()) {
                    stampProgress = 0; return;
                }
            }
        }

        stampProgress++;
        if (stampProgress >= STAMP_TICKS_PER_CYCLE) {
            stampProgress = 0;
            if (mode == Mode.STAMP) {
                if (!cm.stampShare(boundCompanyId)) { setChanged(); return; }
                input.shrink(1);
                items.set(SLOT_INPUT, input);
                ItemStack made = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), boundCompanyId);
                if (output.isEmpty()) items.set(SLOT_OUTPUT, made);
                else output.grow(1);
                if (queuedStamps > 0) queuedStamps--;
                logLedger(company, TransactionLogRecord.Kind.SHARE_STAMP);
            } else {
                if (!cm.redeemShare(boundCompanyId)) { setChanged(); return; }
                input.shrink(1);
                items.set(SLOT_INPUT, input);
                if (output.isEmpty()) items.set(SLOT_OUTPUT, new ItemStack(BankSystemItems.BLANK_SHARE.get()));
                else output.grow(1);
                logLedger(company, TransactionLogRecord.Kind.SHARE_REDEEM);
            }
            setChanged();
        }
    }

    private void logLedger(Company company, TransactionLogRecord.Kind kind) {
        TransactionLogManager mgr = BankSystemModBackend.getTransactionLogManager();
        if (mgr == null) return;
        try {
            mgr.save(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    company.getBankAccountNr(), linkedByUUID, kind, (short) 0, 1L,
                    null, company.getCompanyId(), System.currentTimeMillis(), null));
        } catch (RuntimeException ignored) { }
    }

    // -------- NBT --------
    @Override
    public void loadAdditional(@NotNull CompoundTag tag, HolderLookup.Provider p) {
        super.loadAdditional(tag, p);
        items.clear();
        NonNullList<ItemStack> tmp = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        net.minecraft.world.ContainerHelper.loadAllItems(tag, tmp, p);
        for (int i = 0; i < tmp.size(); i++) items.set(i, tmp.get(i));
        boundCompanyId = tag.contains("BoundCompanyId") ? tag.getInt("BoundCompanyId") : -1;
        linkedByUUID = tag.hasUUID("LinkedByUUID") ? tag.getUUID("LinkedByUUID") : null;
        linkedAt = tag.getLong("LinkedAt");
        stampProgress = tag.getInt("StampProgress");
        queuedStamps = tag.getInt("QueuedStamps");
        mode = tag.getInt("Mode") == 1 ? Mode.REDEEM : Mode.STAMP;
        allowHopperRedeem = tag.getBoolean("AllowHopperRedeem");
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.Provider p) {
        super.saveAdditional(tag, p);
        net.minecraft.world.ContainerHelper.saveAllItems(tag, items, p);
        tag.putInt("BoundCompanyId", boundCompanyId);
        if (linkedByUUID != null) tag.putUUID("LinkedByUUID", linkedByUUID);
        tag.putLong("LinkedAt", linkedAt);
        tag.putInt("StampProgress", stampProgress);
        tag.putInt("QueuedStamps", queuedStamps);
        tag.putInt("Mode", mode.ordinal());
        tag.putBoolean("AllowHopperRedeem", allowHopperRedeem);
    }

    // -------- Container --------
    @Override protected Component getDefaultName() { return TITLE; }
    @Override public Component getDisplayName() { return TITLE; }
    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { for (ItemStack s : items) if (!s.isEmpty()) return false; return true; }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) {
        ItemStack r = net.minecraft.world.ContainerHelper.removeItem(items, slot, amount);
        if (!r.isEmpty()) setChanged();
        return r;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        return net.minecraft.world.ContainerHelper.takeItem(items, slot);
    }
    @Override public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > stack.getMaxStackSize()) stack.setCount(stack.getMaxStackSize());
        setChanged();
    }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> n) {
        for (int i = 0; i < items.size(); i++) items.set(i, i < n.size() ? n.get(i) : ItemStack.EMPTY);
    }
    @Override public boolean stillValid(Player p) {
        if (level == null || level.getBlockEntity(worldPosition) != this) return false;
        return p.distanceToSqr(worldPosition.getX()+0.5, worldPosition.getY()+0.5, worldPosition.getZ()+0.5) <= 64.0;
    }
    @Override public void clearContent() { items.clear(); }

    // WorldlyContainer
    @Override
    public int[] getSlotsForFace(Direction side) {
        if (side == Direction.UP) return new int[]{SLOT_INPUT};
        if (side == Direction.DOWN) return new int[]{SLOT_OUTPUT};
        return new int[0];
    }
    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction dir) {
        if (slot != SLOT_INPUT || dir != Direction.UP) return false;
        if (mode == Mode.STAMP) return stack.getItem() instanceof BlankShareItem;
        // REDEEM
        if (!allowHopperRedeem) return false;
        if (!(stack.getItem() instanceof StampedShareItem)) return false;
        Integer cid = StampedShareItem.getCompanyId(stack);
        return cid != null && cid == boundCompanyId;
    }
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        return slot == SLOT_OUTPUT && dir == Direction.DOWN && !stack.isEmpty();
    }

    public void dropContents() {
        if (level == null) return;
        Containers.dropContents(level, worldPosition, items);
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inv) {
        return new net.kroia.banksystem.minecraft.menu.custom.ShareStamperContainerMenu(id, inv, this);
    }
}
