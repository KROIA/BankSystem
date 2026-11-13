package net.kroia.banksystem.minecraft.entity.custom;

import net.kroia.banksystem.util.ItemStackNbt;
import net.kroia.modutilities.NbtCompat;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.company.SlaveCompanyMirror;
import net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket;
import org.jetbrains.annotations.Nullable;
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
 * Bound via {@code /company stamper-bind}. On a slave (BS-262) each stamp/redeem is applied by the master
 * (STAMPER_APPLY) while one input item is held back; the MANAGE gate is a cached STAMPER_GATE answer.
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

    // BS-262 — slave: the input item held back while the master applies its stamp/redeem. Saved with the
    // request (transfer id, company, send time), so a pending request survives a chunk unload or restart.
    private ItemStack reserved = ItemStack.EMPTY;
    private String pendingTransferId = null;
    private int pendingCompanyId = -1;
    private long pendingSentMs = 0L;
    private boolean pendingInFlight = false; // transient: this session awaits the reply / status of the pending request

    // BS-262 — slave: last STAMPER_GATE answer. The single-viewer lock means one entry is enough.
    public static final long GATE_REFRESH_MS = 5_000L;
    private UUID gateViewer = null;
    private int gateCompanyId = -1;
    private boolean gateAllowed = false;
    private long gateCheckedAt = 0L;
    private long gateAskedAt = 0L;

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
        return companyName(boundCompanyId);
    }

    /** Company name from the master's CompanyManager, or from the SlaveCompanyMirror on a slave (BS-262). */
    private static String companyName(int companyId) {
        if (companyId < 0) return String.valueOf(companyId);
        CompanyManager cm = CompanyManager.get();
        Company c = cm != null ? cm.getById(companyId) : null;
        if (c != null) return c.getName();
        S2CCompanyVisualBulkPacket.Entry e = cm == null ? SlaveCompanyMirror.get(companyId) : null;
        return e != null && !e.internalName().isEmpty() ? e.internalName() : "#" + companyId;
    }

    private long getTotalIssued() {
        if (boundCompanyId < 0) return 0L;
        CompanyManager cm = CompanyManager.get();
        if (cm == null) {
            S2CCompanyVisualBulkPacket.Entry e = SlaveCompanyMirror.get(boundCompanyId);
            return e != null ? e.totalSharesIssued() : 0L;
        }
        Company c = cm.getById(boundCompanyId);
        return c != null ? c.getTotalSharesIssued() : 0L;
    }
    private long getMaxSupply() {
        if (boundCompanyId < 0) return 0L;
        CompanyManager cm = CompanyManager.get();
        if (cm == null) {
            S2CCompanyVisualBulkPacket.Entry e = SlaveCompanyMirror.get(boundCompanyId);
            return e != null ? e.maxSupply() : 0L;
        }
        Company c = cm.getById(boundCompanyId);
        return c != null ? c.getMaxSupply() : 0L;
    }

    /** True on a slave server: company data lives on the master (BS-262). */
    public static boolean isSlaveSide() {
        return BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer;
    }

    /**
     * MANAGE gate check — founder OR MANAGE on bound account OR banksystem admin. On a slave it reads the
     * cached STAMPER_GATE answer for the current viewer (BS-262, see {@link #slaveGateAllows}).
     */
    public boolean hasManagePermission(UUID uuid) {
        if (boundCompanyId < 0) return false;
        if (isSlaveSide()) return slaveGateAllows(uuid);
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
        if (prev >= 0 && prev != companyId)
            ejectContents(); // shares of the previous company must not stay in the block
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
        ejectContents(); // input and output (stamped shares) leave the block with the binding
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
        return level == null ? "" : level.dimension().location().toString(); // mc<=1.21.10
        //return level == null ? "" : level.dimension().identifier().toString(); // mc>=1.21.11
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
        if (level.isClientSide()) return;
        if (!(t instanceof ShareStamperBlockEntity be)) return;
        be.serverTick();
    }

    private void serverTick() {
        boolean slave = isSlaveSide();
        if (slave && pendingTransferId != null) {
            if (!pendingInFlight) sendPending(false); // loaded with a pending request: ask the master for its outcome
            return;
        }
        if (!processing) return;
        if (boundCompanyId < 0) { processing = false; return; }
        CompanyManager cm = CompanyManager.get();
        Company company = null;
        long issued, maxSupply;
        if (slave) {
            // BS-262: the master counts every stamp; the mirror only pre-checks the supply.
            if (!net.kroia.banksystem.util.MultiServerUtils.canInteractWithBankSystem()) return; // link down: pause
            S2CCompanyVisualBulkPacket.Entry e = SlaveCompanyMirror.get(boundCompanyId);
            issued = e != null ? e.totalSharesIssued() : 0L;
            maxSupply = e != null ? e.maxSupply() : 0L;
            if (e == null) warnCompanyMissing();
            if (e == null) return;
        } else {
            if (cm == null) return;
            company = cm.getById(boundCompanyId);
            issued = company != null ? company.getTotalSharesIssued() : 0L;
            maxSupply = company != null ? company.getMaxSupply() : 0L;
            if (company == null) warnCompanyMissing();
            if (company == null) return;
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
            if (issued + 1 > maxSupply) {
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
            if (issued <= 0L) {
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
            if (slave) {
                // BS-262: hold one input item back and let the master count the stamp/redeem.
                reserved = input.split(1);
                pendingCompanyId = boundCompanyId;
                pendingTransferId = net.kroia.banksystem.data.TransferJournal.newTransferId();
                pendingSentMs = System.currentTimeMillis();
                sendPending(true);
                setChanged();
                return;
            }
            if (mode == Mode.STAMP) {
                if (!cm.stampShare(boundCompanyId)) { setChanged(); return; }
                input.shrink(1);
                items.set(SLOT_INPUT, input);
                ItemStack made = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), boundCompanyId);
                if (output.isEmpty()) items.set(SLOT_OUTPUT, made);
                else output.grow(1);
                logStampRow(company, linkedByUUID, net.kroia.banksystem.api.ledger.LedgerType.SHARE_STAMP, null,
                        net.kroia.banksystem.banking.ledger.TransactionLedger.LedgerPos.of(this)); // BS-258
            } else {
                if (!cm.redeemShare(boundCompanyId)) { setChanged(); return; }
                input.shrink(1);
                items.set(SLOT_INPUT, input);
                if (output.isEmpty()) items.set(SLOT_OUTPUT, new ItemStack(BankSystemItems.BLANK_SHARE.get()));
                else output.grow(1);
                logStampRow(company, linkedByUUID, net.kroia.banksystem.api.ledger.LedgerType.SHARE_REDEEM, null,
                        net.kroia.banksystem.banking.ledger.TransactionLedger.LedgerPos.of(this)); // BS-258
            }
            setChanged();
        }
    }

    private void warnCompanyMissing() {
        if (idleLoggedOnce) return;
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.warn("[ShareStamper] bound company #" + boundCompanyId + " missing at " + worldPosition);
        idleLoggedOnce = true;
    }

    /**
     * One SHARE_STAMP / SHARE_REDEEM ledger row (master only). {@code ref} = the slave's transfer id (BS-262), else null;
     * {@code pos} = the stamper block (BS-258).
     */
    public static void logStampRow(Company company, @Nullable UUID actor, net.kroia.banksystem.api.ledger.LedgerType kind, @Nullable String ref,
                                   @Nullable net.kroia.banksystem.banking.ledger.TransactionLedger.LedgerPos pos) {
        // via TransactionLedger.write so the row gets the audit hooks and the TRANSACTION_LOGGED event (Task #119)
        net.kroia.banksystem.banking.ledger.TransactionLedger.write(TransactionLogRecord.of(company.getBankAccountNr(), kind, (short) 0, 1L,
                actor == null ? net.kroia.banksystem.api.ledger.LedgerActor.system() : net.kroia.banksystem.banking.ledger.TransactionLedger.player(actor),
                null, company.getCompanyId(), ref, null, null).withPos(pos));
    }

    // -------- BS-262: slave stamper (company data and the share count live on the master) --------

    /**
     * Slave: the cached STAMPER_GATE answer for {@code uuid}; refreshed about every {@link #GATE_REFRESH_MS} while
     * asked (menu open: stillValid every tick), never one request per tick. Denies without a fresh answer for this
     * viewer and company, while the master link is down or the company mirror is empty.
     */
    private boolean slaveGateAllows(UUID uuid) {
        if (level == null || level.isClientSide() || uuid == null) return false;
        if (!net.kroia.banksystem.util.MultiServerUtils.canInteractWithBankSystem() || SlaveCompanyMirror.isEmpty()) return false;
        if (!uuid.equals(gateViewer) || gateCompanyId != boundCompanyId) return false;
        long now = System.currentTimeMillis();
        if (now - gateAskedAt >= GATE_REFRESH_MS) askGate(uuid, boundCompanyId, null);
        return gateAllowed && now - gateCheckedAt < 3 * GATE_REFRESH_MS;
    }

    /** Slave: asks STAMPER_GATE, caches the answer and runs {@code then} with it (null = no answer) on the server thread. */
    private void askGate(UUID uuid, int companyId, @Nullable java.util.function.Consumer<Integer> then) {
        net.minecraft.server.MinecraftServer srv = level != null ? level.getServer() : null;
        if (srv == null) return;
        gateAskedAt = System.currentTimeMillis();
        net.kroia.banksystem.banking.company.AsyncCompanyManager.stamperGateAsync(companyId, uuid).thenAccept(code -> srv.execute(() -> {
            gateViewer = uuid;
            gateCompanyId = companyId;
            gateAllowed = code != null && code == net.kroia.banksystem.banking.company.AsyncCompanyManager.CODE_OK;
            gateCheckedAt = System.currentTimeMillis();
            if (then != null) then.accept(code);
        }));
    }

    /** Slave: refusal message for a STAMPER_GATE answer. */
    private static void gateRefused(net.minecraft.server.level.ServerPlayer player, @Nullable Integer code, int companyId) {
        player.sendSystemMessage(code != null && code == net.kroia.banksystem.banking.company.AsyncCompanyManager.CODE_UNTRUSTED_SLAVE
                ? Component.translatable("gui.banksystem.share_stamper.untrusted_slave")
                : Component.translatable("gui.banksystem.share_stamper.no_permission", companyName(companyId)));
    }

    /** Slave: opens the stamper menu once the master confirms the player may manage the bound company. */
    public void openAfterGate(net.minecraft.server.level.ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!net.kroia.banksystem.util.MultiServerUtils.canInteractWithBankSystem(uuid)) return; // tells the player
        if (!tryAcquireViewer(uuid)) {
            player.sendSystemMessage(Component.translatable("gui.banksystem.share_stamper.in_use"));
            return;
        }
        int companyId = boundCompanyId;
        askGate(uuid, companyId, code -> {
            boolean ok = code != null && code == net.kroia.banksystem.banking.company.AsyncCompanyManager.CODE_OK;
            if (ok && !isRemoved() && !player.hasDisconnected() && boundCompanyId == companyId) {
                dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(player, this, buf -> buf.writeBlockPos(worldPosition));
                return;
            }
            releaseViewer(uuid);
            if (!ok) gateRefused(player, code, companyId);
        });
    }

    /** Slave: binds to {@code companyId} once the master confirms MANAGE on it; optionally opens the menu after. */
    public void bindAfterGate(net.minecraft.server.level.ServerPlayer player, int companyId, boolean openAfter) {
        UUID uuid = player.getUUID();
        if (!net.kroia.banksystem.util.MultiServerUtils.canInteractWithBankSystem(uuid)) { releaseViewer(uuid); return; }
        askGate(uuid, companyId, code -> {
            if (code == null || code != net.kroia.banksystem.banking.company.AsyncCompanyManager.CODE_OK) {
                releaseViewer(uuid);
                gateRefused(player, code, companyId);
                return;
            }
            if (isRemoved() || (boundCompanyId >= 0 && boundCompanyId != companyId)) { releaseViewer(uuid); return; }
            bind(companyId, uuid);
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (openAfter && !player.hasDisconnected() && tryAcquireViewer(uuid))
                dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(player, this, buf -> buf.writeBlockPos(worldPosition));
        });
    }

    /** Slave: sends the pending stamp/redeem ({@code send}) or only asks the master for its outcome (after a reload). */
    private void sendPending(boolean send) {
        net.minecraft.server.MinecraftServer srv = level != null ? level.getServer() : null;
        if (srv == null) return;
        pendingInFlight = true;
        String tid = pendingTransferId;
        net.kroia.banksystem.banking.ledger.TransactionLedger.atBlock(this, // BS-258: the position goes in the request
                        () -> net.kroia.banksystem.banking.company.AsyncCompanyManager.stamperApplyAsync(pendingCompanyId,
                                reserved.getItem() instanceof StampedShareItem, linkedByUUID, tid, pendingSentMs, send))
                .whenComplete((code, ex) -> srv.execute(() -> finishPending(tid, ex != null ? null : code)));
    }

    /**
     * Slave, server thread: APPLIED → the output item; refused / never sent → the reserved item back to the input
     * (a refusal also stops processing); unknown ({@code code} null) → the item back AND processing stops.
     */
    private void finishPending(String tid, @Nullable Integer code) {
        if (isRemoved() || !tid.equals(pendingTransferId)) return;
        int ok = net.kroia.banksystem.banking.company.AsyncCompanyManager.CODE_OK;
        if (code != null && code == ok) {
            putOrDrop(SLOT_OUTPUT, reserved.getItem() instanceof StampedShareItem
                    ? new ItemStack(BankSystemItems.BLANK_SHARE.get())
                    : StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), pendingCompanyId));
        } else {
            // ponytail: an unknown redeem gives the share back (user decision a); if the master did count it, the
            // company shows a share excess of 1 that an admin acknowledges. Destroying it instead would lose the share.
            putOrDrop(SLOT_INPUT, reserved);
            if (code == null || code != net.kroia.banksystem.banking.company.AsyncCompanyManager.STAMPER_NOT_SENT) {
                processing = false;
                if (code == null && BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                    BACKEND_INSTANCES.LOGGER.warn("[ShareStamper] outcome of stamper request " + tid + " at " + worldPosition
                            + " unknown; item returned to the input, stamper stopped");
            }
        }
        reserved = ItemStack.EMPTY;
        pendingTransferId = null;
        pendingCompanyId = -1;
        pendingInFlight = false;
        stampProgress = 0;
        setChanged();
    }

    /** Into {@code slot} when it fits and the stamper is still bound to the pending company, else dropped on top. */
    private void putOrDrop(int slot, ItemStack stack) {
        ItemStack cur = items.get(slot);
        if (pendingCompanyId == boundCompanyId) {
            if (cur.isEmpty()) { items.set(slot, stack); return; }
            if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() + stack.getCount() <= cur.getMaxStackSize()) {
                cur.grow(stack.getCount());
                return;
            }
        }
        if (level != null)
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, stack);
    }

    // -------- NBT --------
    @Override // mc<=1.21.5
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider p) { super.loadAdditional(tag, p); loadState(tag, p); } // mc<=1.21.5
    //@Override // mc>=1.21.6
    //public void loadAdditional(net.minecraft.world.level.storage.ValueInput input) { super.loadAdditional(input); loadState(net.kroia.modutilities.NbtCompat.readAll(input), input.lookup()); } // mc>=1.21.6

    /** Version-neutral load of this block entity's data (called from loadAdditional). */
    private void loadState(CompoundTag tag, HolderLookup.Provider p) {
        items.clear();
        NonNullList<ItemStack> tmp = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        ItemStackNbt.loadAllItems(tag, tmp, p);
        for (int i = 0; i < tmp.size(); i++) items.set(i, tmp.get(i));
        boundCompanyId = tag.contains("BoundCompanyId") ? NbtCompat.getInt(tag, "BoundCompanyId") : -1;
        linkedByUUID = NbtCompat.hasUUID(tag, "LinkedByUUID") ? NbtCompat.getUUID(tag, "LinkedByUUID") : null;
        linkedAt = NbtCompat.getLong(tag, "LinkedAt");
        stampProgress = NbtCompat.getInt(tag, "StampProgress");
        mode = NbtCompat.getInt(tag, "Mode") == 1 ? Mode.REDEEM : Mode.STAMP;
        // Task v2.1.0 — NBT migration: legacy single `AllowHopperRedeem` flag maps to
        // both new autoInput / autoOutput toggles. Legacy `QueuedStamps` field discarded
        // (no back-compat needed beyond load — see COMPLETED_TASKS.md).
        if (tag.contains("AutoInput") || tag.contains("AutoOutput")) {
            autoInput = NbtCompat.getBoolean(tag, "AutoInput");
            autoOutput = NbtCompat.getBoolean(tag, "AutoOutput");
        } else if (NbtCompat.getBoolean(tag, "AllowHopperRedeem")) {
            autoInput = true;
            autoOutput = true;
        } else {
            autoInput = false;
            autoOutput = false;
        }
        processing = NbtCompat.getBoolean(tag, "Processing");
        // BS-262: a slave request still pending at save time; serverTick asks the master for its outcome.
        pendingTransferId = tag.contains("PendingTransferId") ? NbtCompat.getString(tag, "PendingTransferId") : null;
        pendingCompanyId = pendingTransferId != null ? NbtCompat.getInt(tag, "PendingCompanyId") : -1;
        pendingSentMs = NbtCompat.getLong(tag, "PendingSentMs");
        reserved = tag.contains("Reserved") ? ItemStackNbt.parseOptional(p, NbtCompat.getCompound(tag, "Reserved")) : ItemStack.EMPTY;
        pendingInFlight = false;
        // v2.1.0 Bug4 root cause — vanilla BE lifecycle invokes setLevel() BEFORE
        // loadAdditional() during chunk load, so the setLevel() reverse-index register
        // fired with boundCompanyId still at -1 (its default) and persisted bindings
        // were never re-added to CompanyManager's reverse index. Register here at the
        // end of load, once boundCompanyId is the persisted value. Master-only.
        if (level != null && !level.isClientSide() && boundCompanyId >= 0) {
            CompanyManager cm = CompanyManager.get();
            if (cm != null) cm.registerStamper(boundCompanyId, worldPosition, dimensionKey());
        }
    }

    @Override // mc<=1.21.5
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider p) { super.saveAdditional(tag, p); saveState(tag, p); } // mc<=1.21.5
    //@Override // mc>=1.21.6
    //protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) { super.saveAdditional(output); CompoundTag tag = new CompoundTag(); saveState(tag, level != null ? level.registryAccess() : net.kroia.modutilities.UtilitiesPlatform.getRegistryAccess()); net.kroia.modutilities.NbtCompat.writeAll(output, tag); } // mc>=1.21.6

    /** Version-neutral save of this block entity's data (called from saveAdditional). */
    private void saveState(CompoundTag tag, HolderLookup.Provider p) {
        ItemStackNbt.saveAllItems(tag, items, p);
        tag.putInt("BoundCompanyId", boundCompanyId);
        if (linkedByUUID != null) NbtCompat.putUUID(tag, "LinkedByUUID", linkedByUUID);
        tag.putLong("LinkedAt", linkedAt);
        tag.putInt("StampProgress", stampProgress);
        tag.putInt("Mode", mode.ordinal());
        tag.putBoolean("AutoInput", autoInput);
        tag.putBoolean("AutoOutput", autoOutput);
        tag.putBoolean("Processing", processing);
        if (pendingTransferId != null) {
            tag.putString("PendingTransferId", pendingTransferId);
            tag.putInt("PendingCompanyId", pendingCompanyId);
            tag.putLong("PendingSentMs", pendingSentMs);
        }
        if (!reserved.isEmpty()) tag.put("Reserved", ItemStackNbt.save(reserved, p));
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
        if (p.distanceToSqr(worldPosition.getX()+0.5, worldPosition.getY()+0.5, worldPosition.getZ()+0.5) > 64.0) return false;
        // A bound stamper is only usable with MANAGE on its company (founder / MANAGE / banksystem admin).
        // The server re-checks this every tick and before every slot click, so revoked rights close the
        // menu and a modified client cannot keep interacting. Unbound stampers keep the old behaviour.
        return boundCompanyId < 0 || level.isClientSide() || hasManagePermission(p.getUUID());
    }
    @Override public void clearContent() { items.clear(); }

    // Task #47 (v2.1.0) — release viewer lock if a caller runs Container#stopOpen.
    // AbstractContainerMenu#removed does NOT call stopOpen, so the menu close is handled
    // by ShareStamperContainerMenu#removed; setRemoved clears the lock on BE unload.
    @Override
    public void stopOpen(Player p) { // mc<=1.21.8
    //public void stopOpen(net.minecraft.world.entity.ContainerUser p) { // mc>=1.21.9
        super.stopOpen(p);
        releaseViewer(p.getUUID()); // mc<=1.21.8
        //releaseViewer(p.getLivingEntity().getUUID()); // mc>=1.21.9
    }

    @Override
    public void setRemoved() {
        currentViewer = null;
        // Task #51 (v2.1.0) — drop reverse index entry on BE unload/removal (master-only meaningful).
        if (level != null && !level.isClientSide() && boundCompanyId >= 0) {
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
        if (!lvl.isClientSide() && boundCompanyId >= 0) {
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

    //@Override // mc>=1.21.5
    //public void preRemoveSideEffects(BlockPos pos, BlockState state) { dropContents(); } // mc>=1.21.5

    public void dropContents() {
        if (level == null) return;
        Containers.dropContents(level, worldPosition, items);
        // BS-262: an item held back for a pending slave request drops like the slots; the request is dropped with the block
        if (!reserved.isEmpty())
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), reserved);
        reserved = ItemStack.EMPTY;
        pendingTransferId = null;
    }

    /**
     * Drops every slot on top of the (still standing) block and empties the inventory.
     * Server only; used when the binding is cleared or changed.
     */
    private void ejectContents() {
        if (level == null || level.isClientSide()) return;
        boolean any = false;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0,
                    worldPosition.getZ() + 0.5, stack.copy());
            items.set(i, ItemStack.EMPTY);
            any = true;
        }
        if (any) setChanged();
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inv) {
        return new net.kroia.banksystem.minecraft.menu.custom.ShareStamperContainerMenu(id, inv, this);
    }
}
