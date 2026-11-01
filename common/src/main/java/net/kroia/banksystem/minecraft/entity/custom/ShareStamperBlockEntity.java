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
 * Task #47 (v2.1.0) — Share Stamper Block Entity. Two slots: input (0), output (1).
 * Master-only tick; slave BEs idle. Bound via {@code /company stamper-bind}.
 */
public class ShareStamperBlockEntity extends BaseContainerBlockEntity implements MenuProvider, WorldlyContainer {

    public enum Mode { STAMP, REDEEM }

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;
    // Task v2.1.0 — 3x faster (was 200) per user feedback.
    public static final int STAMP_TICKS_PER_CYCLE = 67;

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) { BACKEND_INSTANCES = backend; }

    private static final Component TITLE =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".share_stamper");

    private final NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);

    private int boundCompanyId = -1;
    private UUID linkedByUUID = null;
    private long linkedAt = 0L;
    private int stampProgress = 0;
    private Mode mode = Mode.STAMP;
    // Task v2.1.0 — Start/Stop control replaces the queued-stamps counter.
    // While `processing` is true the BE ticks one stamp/redeem per cycle; it auto-stops when
    // input empty / output full / max supply reached (see serverTick).
    private boolean processing = false;
    // Task v2.1.0 — split hopper toggle: autoInput gates INSERT via UP face,
    // autoOutput gates EXTRACT via DOWN face (both default off; NBT-migrated from
    // legacy `AllowHopperRedeem`).
    private boolean autoInput = false;
    private boolean autoOutput = false;

    private boolean idleLoggedOnce = false;

    // Task #47 (v2.1.0) — single-viewer lock (transient; never persisted).
    // Held by whichever player currently has either the bind screen or the main
    // stamper screen open on this BE. Cleared on menu close, bind-screen close,
    // or BE removal. Concurrent right-clicks from other players get rejected.
    private UUID currentViewer = null;

    // ContainerData for menu sync.
    public final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            long ti = getTotalIssued();
            long ms = getMaxSupply();
            return switch (index) {
                case 0 -> stampProgress;
                case 1 -> processing ? 1 : 0;
                case 2 -> boundCompanyId;
                case 3 -> mode.ordinal();
                case 4 -> autoInput ? 1 : 0;
                case 5 -> autoOutput ? 1 : 0;
                case 6 -> (int) (ti & 0xFFFF);
                case 7 -> (int) ((ti >> 16) & 0xFFFF);
                case 8 -> (int) (ms & 0xFFFF);
                case 9 -> (int) ((ms >> 16) & 0xFFFF);
                default -> 0;
            };
        }
        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> stampProgress = value;
                case 1 -> processing = value != 0;
                case 2 -> boundCompanyId = value;
                case 3 -> mode = value == 1 ? Mode.REDEEM : Mode.STAMP;
                case 4 -> autoInput = value != 0;
                case 5 -> autoOutput = value != 0;
            }
        }
        @Override
        public int getCount() { return 10; }
    };

    public ShareStamperBlockEntity(BlockPos pos, BlockState state) {
        super(BankSystemEntities.SHARE_STAMPER_BLOCK_ENTITY.get(), pos, state);
    }

    // -------- accessors --------
    public int getBoundCompanyId() { return boundCompanyId; }
    public UUID getLinkedByUUID() { return linkedByUUID; }
    public long getLinkedAt() { return linkedAt; }
    public Mode getMode() { return mode; }
    public boolean isAutoInput() { return autoInput; }
    public boolean isAutoOutput() { return autoOutput; }
    public boolean isProcessing() { return processing; }
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
        int prev = this.boundCompanyId;
        this.boundCompanyId = companyId;
        this.linkedByUUID = linkedBy;
        this.linkedAt = System.currentTimeMillis();
        this.stampProgress = 0;
        setChanged();
        // Task #51 (v2.1.0) — maintain reverse index (master-only; CM is null on slave/client).
        CompanyManager cm = CompanyManager.get();
        if (cm != null) {
            String dim = dimensionKey();
            if (prev >= 0 && prev != companyId) cm.unregisterStamper(prev, worldPosition, dim);
            if (companyId >= 0) cm.registerStamper(companyId, worldPosition, dim);
        }
    }
    public void unbind() {
        int prev = this.boundCompanyId;
        this.boundCompanyId = -1;
        this.linkedByUUID = null;
        this.linkedAt = 0L;
        this.stampProgress = 0;
        this.processing = false;
        setChanged();
        // Task #51 (v2.1.0) — maintain reverse index.
        CompanyManager cm = CompanyManager.get();
        if (cm != null && prev >= 0) cm.unregisterStamper(prev, worldPosition, dimensionKey());
    }

    /** Dimension key as a stable string; empty when level is unattached. */
    private String dimensionKey() {
        return level == null ? "" : level.dimension().location().toString();
    }

    // -------- viewer lock (Task #47, v2.1.0) --------
    /** @return true if the caller may now open a stamper GUI on this BE (bind or main). */
    public synchronized boolean tryAcquireViewer(UUID uuid) {
        if (currentViewer == null || currentViewer.equals(uuid)) {
            currentViewer = uuid;
            return true;
        }
        return false;
    }
    public synchronized void releaseViewer(UUID uuid) {
        if (currentViewer != null && currentViewer.equals(uuid)) currentViewer = null;
    }
    public synchronized UUID getCurrentViewer() { return currentViewer; }
    public void setMode(Mode m) { this.mode = m; this.stampProgress = 0; setChanged(); }
    public void setProcessing(boolean p) { this.processing = p; if (!p) this.stampProgress = 0; setChanged(); }
    public void setAutoIo(boolean input, boolean output) {
        this.autoInput = input; this.autoOutput = output; setChanged();
    }

    // -------- tick --------
    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T t) {
        if (level.isClientSide) return;
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer) return;
        if (!(t instanceof ShareStamperBlockEntity be)) return;
        be.serverTick();
    }

    private void serverTick() {
        if (!processing) return;
        if (boundCompanyId < 0) { processing = false; return; }
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

        // Task v2.1.0 — auto-stop `processing` when the current mode can't advance
        // for one of the well-known "stuck" reasons: input empty, output full, or
        // (STAMP mode only) company hit maxSupply. Setting processing=false also
        // ripples through ContainerData index 1 so the client button flips back
        // to "Start" without an extra packet.
        if (mode == Mode.STAMP) {
            if (input.isEmpty() || !(input.getItem() instanceof BlankShareItem)) {
                stampProgress = 0; processing = false; setChanged(); return;
            }
            if (company.getTotalSharesIssued() + 1 > company.getMaxSupply()) {
                stampProgress = 0; processing = false; setChanged(); return;
            }
            ItemStack expected = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), boundCompanyId);
            if (!output.isEmpty()) {
                if (!ItemStack.isSameItemSameComponents(output, expected)
                        || output.getCount() >= output.getMaxStackSize()) {
                    stampProgress = 0; processing = false; setChanged(); return;
                }
            }
        } else { // REDEEM
            if (input.isEmpty() || !(input.getItem() instanceof StampedShareItem)) {
                stampProgress = 0; processing = false; setChanged(); return;
            }
            Integer cid = StampedShareItem.getCompanyId(input);
            if (cid == null || cid != boundCompanyId) {
                stampProgress = 0; processing = false; setChanged(); return;
            }
            if (company.getTotalSharesIssued() <= 0L) {
                stampProgress = 0; processing = false; setChanged(); return;
            }
            if (!output.isEmpty()) {
                if (!(output.getItem() instanceof BlankShareItem)
                        || output.getCount() >= output.getMaxStackSize()) {
                    stampProgress = 0; processing = false; setChanged(); return;
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
        mode = tag.getInt("Mode") == 1 ? Mode.REDEEM : Mode.STAMP;
        // Task v2.1.0 — NBT migration: legacy single `AllowHopperRedeem` flag maps to
        // both new autoInput / autoOutput toggles. Legacy `QueuedStamps` field discarded
        // (no back-compat needed beyond load — see COMPLETED_TASKS.md).
        if (tag.contains("AutoInput") || tag.contains("AutoOutput")) {
            autoInput = tag.getBoolean("AutoInput");
            autoOutput = tag.getBoolean("AutoOutput");
        } else if (tag.getBoolean("AllowHopperRedeem")) {
            autoInput = true;
            autoOutput = true;
        } else {
            autoInput = false;
            autoOutput = false;
        }
        processing = tag.getBoolean("Processing");
        // v2.1.0 Bug4 root cause — vanilla BE lifecycle invokes setLevel() BEFORE
        // loadAdditional() during chunk load, so the setLevel() reverse-index register
        // fired with boundCompanyId still at -1 (its default) and persisted bindings
        // were never re-added to CompanyManager's reverse index. Register here at the
        // end of load, once boundCompanyId is the persisted value. Master-only.
        if (level != null && !level.isClientSide && boundCompanyId >= 0) {
            CompanyManager cm = CompanyManager.get();
            if (cm != null) cm.registerStamper(boundCompanyId, worldPosition, dimensionKey());
        }
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.Provider p) {
        super.saveAdditional(tag, p);
        net.minecraft.world.ContainerHelper.saveAllItems(tag, items, p);
        tag.putInt("BoundCompanyId", boundCompanyId);
        if (linkedByUUID != null) tag.putUUID("LinkedByUUID", linkedByUUID);
        tag.putLong("LinkedAt", linkedAt);
        tag.putInt("StampProgress", stampProgress);
        tag.putInt("Mode", mode.ordinal());
        tag.putBoolean("AutoInput", autoInput);
        tag.putBoolean("AutoOutput", autoOutput);
        tag.putBoolean("Processing", processing);
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

    // Task #47 (v2.1.0) — release viewer lock when the container menu closes and when
    // the BE unloads. AbstractContainerMenu#removed calls Container#stopOpen(Player).
    @Override
    public void stopOpen(Player p) {
        super.stopOpen(p);
        releaseViewer(p.getUUID());
    }

    @Override
    public void setRemoved() {
        currentViewer = null;
        // Task #51 (v2.1.0) — drop reverse index entry on BE unload/removal (master-only meaningful).
        if (level != null && !level.isClientSide && boundCompanyId >= 0) {
            CompanyManager cm = CompanyManager.get();
            if (cm != null) cm.unregisterStamper(boundCompanyId, worldPosition, dimensionKey());
        }
        super.setRemoved();
    }

    @Override
    public void setLevel(Level lvl) {
        super.setLevel(lvl);
        // Task #51 (v2.1.0) — after NBT load + level attach, register this BE in the
        // Company reverse index. Master-only; on client and slave CompanyManager.get()
        // is null so this is a no-op there.
        if (!lvl.isClientSide && boundCompanyId >= 0) {
            CompanyManager cm = CompanyManager.get();
            if (cm != null) cm.registerStamper(boundCompanyId, worldPosition, dimensionKey());
        }
    }


    // WorldlyContainer
    @Override
    public int[] getSlotsForFace(Direction side) {
        if (side == Direction.UP) return new int[]{SLOT_INPUT};
        if (side == Direction.DOWN) return new int[]{SLOT_OUTPUT};
        return new int[0];
    }
    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction dir) {
        // Task v2.1.0 — insert only when autoInput gate is on AND the face is UP.
        if (slot != SLOT_INPUT || dir != Direction.UP || !autoInput) return false;
        if (mode == Mode.STAMP) return stack.getItem() instanceof BlankShareItem;
        // REDEEM
        if (!(stack.getItem() instanceof StampedShareItem)) return false;
        Integer cid = StampedShareItem.getCompanyId(stack);
        return cid != null && cid == boundCompanyId;
    }
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        // Task v2.1.0 — extract only when autoOutput gate is on AND face is DOWN.
        return slot == SLOT_OUTPUT && dir == Direction.DOWN && autoOutput && !stack.isEmpty();
    }

    // Task #47 (v2.1.0) — client sync of the binding + mode so tooltip / getDestroyProgress
    // can react without opening the menu. Contents are intentionally NOT synced to keep the
    // update tag small; only the identity-relevant fields ship.
    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public @NotNull CompoundTag getUpdateTag(HolderLookup.@NotNull Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("BoundCompanyId", boundCompanyId);
        tag.putInt("Mode", mode.ordinal());
        return tag;
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
