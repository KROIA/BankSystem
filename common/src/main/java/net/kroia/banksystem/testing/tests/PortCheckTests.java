package net.kroia.banksystem.testing.tests;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.tree.CommandNode;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.block.custom.BankDownloadBlock;
import net.kroia.banksystem.minecraft.block.custom.BankUploadBlock;
import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.MoneyStockpileBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.entity.ConverterDropAllPacket;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MoneyDenominationOptimizer;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType; // mc<=1.21.11
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * BS-210: checks a Minecraft/loader port tends to break silently, without a human looking.
 * Built on 1.21.1 so they propagate to every port branch. Blocks are placed a few blocks next to
 * and above the online player (a loaded, ticking chunk) and removed again in the same call; the
 * player's inventory and creative flag are restored after every test that touches them.
 * <p>
 * Not covered here (see the BS-210 report): the client side of opening a screen (needs the block
 * synced to a real client first), the Fabric Transfer API / NeoForge item handler wrappers (need a
 * platform hook; the vanilla hopper path they wrap is covered), and the ATM screen (no menu).
 */
public class PortCheckTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.PORT_CHECK;
    }

    @Override
    public void registerTests() {
        addTest("registry_entries_present", this::registryEntriesPresent);
        addTest("lang_name_for_every_block_and_item", this::langNames);
        addTest("block_items_have_a_loot_table", this::blockItemsHaveLootTable);
        addTest("recipes_loaded", this::recipesLoaded);
        addTest("block_entities_keep_data_on_save_load", this::blockEntityRoundTrip);
        addTest("break_terminal_drops_once", () -> breakDrops(BankSystemBlocks.BANK_TERMINAL_BLOCK.get(),
                be -> ((BankTerminalBlockEntity) be).getInventory(UUID.randomUUID()).setItem(0, new ItemStack(Items.DIAMOND, 5)),
                Map.of(BankSystemBlocks.BANK_TERMINAL_BLOCK.get().asItem(), 1, Items.DIAMOND, 5)));
        addTest("break_upload_drops_once", () -> breakDrops(BankSystemBlocks.BANK_UPLOAD_BLOCK.get(),
                be -> ((Container) be).setItem(0, new ItemStack(Items.DIAMOND, 5)),
                Map.of(BankSystemBlocks.BANK_UPLOAD_BLOCK.get().asItem(), 1, Items.DIAMOND, 5)));
        addTest("break_download_drops_once", () -> breakDrops(BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get(),
                be -> ((Container) be).setItem(0, new ItemStack(Items.DIAMOND, 5)),
                Map.of(BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get().asItem(), 1, Items.DIAMOND, 5)));
        addTest("break_stamper_drops_once", () -> breakDrops(BankSystemBlocks.SHARE_STAMPER_BLOCK.get(),
                be -> {
                    ((Container) be).setItem(ShareStamperBlockEntity.SLOT_INPUT, new ItemStack(BankSystemItems.BLANK_SHARE.get(), 3));
                    ((Container) be).setItem(ShareStamperBlockEntity.SLOT_OUTPUT, new ItemStack(Items.PAPER, 2));
                },
                Map.of(BankSystemBlocks.SHARE_STAMPER_BLOCK.get().asItem(), 1, BankSystemItems.BLANK_SHARE.get(), 3, Items.PAPER, 2)));
        addTest("break_stockpile_drops_money_once", () -> breakDrops(BankSystemBlocks.MONEY_STOCKPILE_BLOCK.get(),
                be -> ((MoneyStockpileBlockEntity) be).addItems(new ItemStack(BankSystemItems.MONEY100.get(), 5)),
                Map.of(BankSystemItems.MONEY100.get(), 5)));
        addTest("stockpile_breaks_like_white_wool", this::stockpileLikeWool);
        addTest("upload_reacts_to_redstone", () -> redstone(BankSystemBlocks.BANK_UPLOAD_BLOCK.get(),
                (be, state) -> ((BankUploadBlockEntity) be).isSendingEnabled()
                        && state.getValue(BankUploadBlock.SENDING_STATE) == BankUploadBlock.SendingState.SENDING));
        addTest("download_reacts_to_redstone", () -> redstone(BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get(),
                (be, state) -> ((BankDownloadBlockEntity) be).isBlockIsPowered()
                        && state.getValue(BankDownloadBlock.RECEIVING_STATE) == BankDownloadBlock.ReceivingState.RECEIVING));
        addTest("hopper_inserts_into_upload", this::hopperInsertUpload);
        addTest("hopper_extracts_from_download", this::hopperExtractDownload);
        addTest("every_menu_opens_server_side", this::menusOpen);
        addTest("stamper_viewer_lock_released_on_menu_close", this::stamperLockReleased);
        addTest("full_inventory_stockpile_drops", this::fullInventoryStockpile);
        addTest("full_inventory_converter_drops", this::fullInventoryConverter);
        addTest("full_inventory_multi_server_drop", this::fullInventoryMultiServerDrop);
        addTest("terminal_craft_returns_output_and_remainders", this::terminalCraftRemainders);
        addTest("terminal_craft_drops_overflow", this::terminalCraftOverflow);
        addTest("admin_commands_need_op", this::adminCommandsNeedOp);
    }

    // ── Helpers ──

    private @org.jetbrains.annotations.Nullable ServerPlayer player() {
        MinecraftServer server = getServer();
        if (server == null) return null;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    /** {@code count} free spots in a row next to and above the player (each with air above and below), or null. */
    private static @org.jetbrains.annotations.Nullable BlockPos[] freeRow(ServerPlayer player, int count) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos start = player.blockPosition().offset(2, 3, 0);
        BlockPos[] row = new BlockPos[count];
        for (int i = 0; i < count; i++) {
            BlockPos p = start.offset(2 * i, 0, 0);
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() || !level.getBlockState(p.below()).isAir())
                return null;
            row[i] = p;
        }
        return row;
    }

    private static Set<ItemEntity> itemsIn(ServerLevel level, AABB box) {
        return new HashSet<>(level.getEntitiesOfClass(ItemEntity.class, box));
    }

    /** Item entities in {@code box} that were not in {@code before}, removed from the world, counted per item. */
    private static Map<Item, Integer> takeNewItems(ServerLevel level, AABB box, Set<ItemEntity> before) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (before.contains(e)) continue;
            counts.merge(e.getItem().getItem(), e.getItem().getCount(), Integer::sum);
            e.discard();
        }
        return counts;
    }

    private static List<ItemStack> snapshot(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            out.add(player.getInventory().getItem(i).copy());
        return out;
    }

    private static void restore(ServerPlayer player, List<ItemStack> saved) {
        for (int i = 0; i < saved.size(); i++)
            player.getInventory().setItem(i, saved.get(i));
    }

    /** Fills the 36 main slots with stone and empties the off hand; nothing more fits. */
    private static void fillInventory(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            player.getInventory().setItem(i, i < 36 ? new ItemStack(Items.STONE, 64) : ItemStack.EMPTY);
    }

    private static void clearInventory(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            player.getInventory().setItem(i, ItemStack.EMPTY);
    }

    private static int countInInventory(ServerPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            if (player.getInventory().getItem(i).is(item)) n += player.getInventory().getItem(i).getCount();
        return n;
    }

    private static Map<Item, Integer> nonZero(Map<Item, Integer> m) {
        Map<Item, Integer> out = new HashMap<>(m);
        out.values().removeIf(v -> v == 0);
        return out;
    }

    // ── Registration ──

    /** Every RegistrySupplier field of our block/item/block-entity/menu holders resolved to a registered entry. */
    private TestResult registryEntriesPresent() {
        int checked = 0;
        for (Class<?> holder : new Class<?>[]{BankSystemBlocks.class, BankSystemItems.class, BankSystemEntities.class, BankSystemMenus.class}) {
            for (Field f : holder.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(null) instanceof RegistrySupplier<?> rs) {
                        if (!rs.isPresent())
                            return fail(holder.getSimpleName() + "." + f.getName() + " (" + rs.getId() + ") is not registered");
                        checked++;
                    }
                } catch (ReflectiveOperationException e) {
                    return fail("could not read " + holder.getSimpleName() + "." + f.getName() + ": " + e);
                }
            }
        }
        return assertTrue("no registry entries found (holders renamed?)", checked > 20);
    }

    /** en_us.json has a name for every banksystem block and item. */
    private TestResult langNames() {
        JsonObject lang;
        try (InputStream in = BankSystemMod.class.getResourceAsStream("/assets/banksystem/lang/en_us.json")) {
            if (in == null) return fail("assets/banksystem/lang/en_us.json is not on the classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return fail("en_us.json could not be read: " + e);
        }
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!BankSystemMod.MOD_ID.equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) continue;
            checked++;
            if (!lang.has(block.getDescriptionId())) missing.add(block.getDescriptionId());
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (!BankSystemMod.MOD_ID.equals(BuiltInRegistries.ITEM.getKey(item).getNamespace())) continue;
            checked++;
            if (!lang.has(item.getDescriptionId())) missing.add(item.getDescriptionId());
        }
        if (checked == 0) return fail("no banksystem blocks or items in the registries");
        return missing.isEmpty() ? pass(checked + " blocks/items have an en_us name") : fail("en_us.json has no name for " + missing);
    }

    /** Every block that has an item drops something when broken (its loot table exists). */
    private TestResult blockItemsHaveLootTable() {
        MinecraftServer server = getServer();
        if (server == null) return skip("no server");
        List<String> missing = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            var key = BuiltInRegistries.BLOCK.getKey(block);
            if (!BankSystemMod.MOD_ID.equals(key.getNamespace()) || block.asItem() == Items.AIR) continue;
            LootTable table = server.reloadableRegistries().getLootTable(block.getLootTable()); // mc<=1.21.1
            //LootTable table = block.getLootTable().map(k -> server.reloadableRegistries().getLootTable(k)).orElse(LootTable.EMPTY); // mc>=1.21.2
            if (table == LootTable.EMPTY) missing.add(key.toString());
        }
        return missing.isEmpty() ? pass("every banksystem block with an item has a loot table")
                : fail("no loot table (block drops nothing when broken): " + missing);
    }

    /** Every recipe JSON BankSystem ships under data/banksystem/recipe/ is in the server's RecipeManager. */
    private TestResult recipesLoaded() {
        MinecraftServer server = getServer();
        if (server == null) return skip("no server");
        List<String> ids = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        server.getResourceManager().listResources("recipe", p -> p.getPath().endsWith(".json")).keySet().forEach(file -> {
            if (!file.getNamespace().equals(BankSystemMod.MOD_ID)) return;
            String path = file.getPath();
            var id = BankSystemMod.id(path.substring("recipe/".length(), path.length() - ".json".length()));
            ids.add(id.toString());
            boolean loaded = server.getRecipeManager().byKey(id).isPresent(); // mc<=1.21.1
            //boolean loaded = server.getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, id)).isPresent(); // mc>=1.21.2
            if (!loaded) missing.add(id.toString());
        });
        if (ids.isEmpty()) return fail("no banksystem recipe JSON in the server resources (folder renamed?)");
        return missing.isEmpty() ? pass(ids.size() + " recipes loaded") : fail("recipes not loaded (parse error?): " + missing);
    }

    // ── Block entity data ──

    private static CompoundTag save(BlockEntity be, HolderLookup.Provider reg) {
        return be.saveWithoutMetadata(reg);
    }

    private static void load(BlockEntity be, CompoundTag tag, HolderLookup.Provider reg) {
        be.loadWithComponents(tag, reg); // mc<=1.21.5
        //be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, reg, tag)); // mc>=1.21.6
    }

    /**
     * Each block entity, filled with data, saves a tag that loads into a fresh block entity which saves the same tag
     * again (nothing lost or changed on the way), and the tag differs from an empty block entity's (the data is in it).
     */
    private TestResult blockEntityRoundTrip() {
        MinecraftServer server = getServer();
        if (server == null) return skip("no server");
        HolderLookup.Provider reg = server.registryAccess();
        BlockPos pos = BlockPos.ZERO;
        ItemStack money = new ItemStack(BankSystemItems.MONEY100.get(), 5);
        Map<String, Supplier<BlockEntity>> factories = new java.util.LinkedHashMap<>();
        factories.put("terminal", () -> new BankTerminalBlockEntity(pos, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState()));
        factories.put("upload", () -> new BankUploadBlockEntity(pos, BankSystemBlocks.BANK_UPLOAD_BLOCK.get().defaultBlockState()));
        factories.put("download", () -> new BankDownloadBlockEntity(pos, BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get().defaultBlockState()));
        factories.put("stockpile", () -> new MoneyStockpileBlockEntity(pos, BankSystemBlocks.MONEY_STOCKPILE_BLOCK.get().defaultBlockState()));
        factories.put("stamper", () -> new ShareStamperBlockEntity(pos, BankSystemBlocks.SHARE_STAMPER_BLOCK.get().defaultBlockState()));
        factories.put("display", () -> new BankSystemDisplayBlockEntity(pos, BankSystemBlocks.BANKSYSTEM_DISPLAY_BLOCK.get().defaultBlockState()));
        Map<String, Consumer<BlockEntity>> fillers = Map.of(
                "terminal", be -> ((BankTerminalBlockEntity) be).getInventory(UUID.randomUUID()).setItem(3, new ItemStack(Items.DIAMOND, 7)),
                "upload", be -> {
                    ((BankUploadBlockEntity) be).setItem(4, new ItemStack(Items.DIAMOND, 7));
                    ((BankUploadBlockEntity) be).redstoneSignalChanged(true);
                },
                "download", be -> {
                    BankDownloadBlockEntity d = (BankDownloadBlockEntity) be;
                    d.setItem(4, new ItemStack(Items.DIAMOND, 7));
                    d.redstoneSignalChanged(true);
                    d.getWithdrawOrders().add(new BankDownloadBlockEntity.WithdrawOrder(ItemID.of(money),
                            7, BankDownloadBlockEntity.WithdrawCondition.HAS_MORE_THEN, 3));
                },
                "stockpile", be -> {
                    ((MoneyStockpileBlockEntity) be).addItems(money.copy());
                    ((MoneyStockpileBlockEntity) be).setFacing(Direction.EAST);
                },
                "stamper", be -> {
                    ShareStamperBlockEntity s = (ShareStamperBlockEntity) be;
                    s.setItem(ShareStamperBlockEntity.SLOT_INPUT, new ItemStack(BankSystemItems.BLANK_SHARE.get(), 3));
                    s.setMode(ShareStamperBlockEntity.Mode.REDEEM);
                    s.setAutoIo(true, false);
                },
                "display", be -> ((BankSystemDisplayBlockEntity) be).adoptConfig(BankSystemDisplayBlockEntity.DisplayType.BALANCE_HISTORY, 77));
        for (Map.Entry<String, Supplier<BlockEntity>> e : factories.entrySet()) {
            String name = e.getKey();
            try {
                CompoundTag empty = save(e.getValue().get(), reg);
                BlockEntity filled = e.getValue().get();
                fillers.get(name).accept(filled);
                CompoundTag first = save(filled, reg);
                TestResult r = assertFalse(name + ": filled data is missing from the saved tag", first.equals(empty));
                if (!r.passed()) return r;
                BlockEntity copy = e.getValue().get();
                load(copy, first.copy(), reg);
                CompoundTag second = save(copy, reg);
                r = assertEquals(name + ": data changed across save/load", first, second);
                if (!r.passed()) return r;
            } catch (Throwable t) {
                return fail(name + ": save/load threw " + t);
            }
        }
        return pass(factories.size() + " block entities keep their data across save/load");
    }

    // ── Breaking ──

    private TestResult breakDrops(Block block, Consumer<BlockEntity> fill, Map<Item, Integer> expected) {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        AABB box = new AABB(pos).inflate(3);
        try {
            level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return fail("no block entity after placing " + block);
            fill.accept(be);
            Set<ItemEntity> before = itemsIn(level, box);
            level.destroyBlock(pos, true);
            Map<Item, Integer> dropped = takeNewItems(level, box, before);
            TestResult r = assertTrue("block still there after breaking", level.getBlockState(pos).isAir());
            if (!r.passed()) return r;
            return assertEquals("drops of " + BuiltInRegistries.BLOCK.getKey(block), nonZero(expected), dropped);
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** The stockpile copies white wool's properties: same hardness, blast resistance and sound. */
    private TestResult stockpileLikeWool() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        Block stockpile = BankSystemBlocks.MONEY_STOCKPILE_BLOCK.get();
        Block wool = Blocks.WHITE_WOOL; // mc<=26.1
        //Block wool = Blocks.WOOL.white(); // mc>=26.2
        BlockPos pos = player.blockPosition();
        TestResult r = assertEquals("hardness", wool.defaultBlockState().getDestroySpeed(player.level(), pos),
                stockpile.defaultBlockState().getDestroySpeed(player.level(), pos));
        if (!r.passed()) return r;
        r = assertEquals("blast resistance", wool.getExplosionResistance(), stockpile.getExplosionResistance());
        if (!r.passed()) return r;
        return assertEquals("sound", wool.defaultBlockState().getSoundType(), stockpile.defaultBlockState().getSoundType());
    }

    // ── Redstone ──

    /** A redstone block next to it schedules the block tick; the tick switches the block on, and off again without power. */
    private TestResult redstone(Block block, BiFunction<BlockEntity, BlockState, Boolean> isOn) {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        BlockPos power = pos.above();
        try {
            level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            TestResult r = assertTrue("neighbour change did not schedule a block tick", level.getBlockTicks().hasScheduledTick(pos, block));
            if (!r.passed()) return r;
            level.getBlockState(pos).tick(level, pos, level.getRandom());
            r = assertTrue("not switched on by redstone", isOn.apply(level.getBlockEntity(pos), level.getBlockState(pos)));
            if (!r.passed()) return r;
            level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.getBlockState(pos).tick(level, pos, level.getRandom());
            return assertFalse("still on after the power was removed", isOn.apply(level.getBlockEntity(pos), level.getBlockState(pos)));
        } finally {
            level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof Container c) c.clearContent();
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    // ── Item transport (vanilla hopper path; the platform pipe wrappers wrap the same Container) ──

    private TestResult hopperInsertUpload() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        try {
            level.setBlock(pos, BankSystemBlocks.BANK_UPLOAD_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            Container upload = (Container) level.getBlockEntity(pos);
            ItemStack rest = HopperBlockEntity.addItem(null, upload, new ItemStack(Items.DIAMOND, 5), Direction.UP);
            TestResult r = assertTrue("hopper could not insert into the upload block", rest.isEmpty());
            if (!r.passed()) return r;
            int found = 0;
            for (int i = 0; i < upload.getContainerSize(); i++)
                if (upload.getItem(i).is(Items.DIAMOND)) found += upload.getItem(i).getCount();
            return assertEquals("diamonds in the upload block", 5, found);
        } finally {
            if (level.getBlockEntity(pos) instanceof Container c) c.clearContent();
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private TestResult hopperExtractDownload() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        BlockPos hopperPos = pos.below();
        try {
            level.setBlock(pos, BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
            Container download = (Container) level.getBlockEntity(pos);
            download.setItem(0, new ItemStack(Items.DIAMOND, 5));
            HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(hopperPos);
            TestResult r = assertTrue("hopper pulled nothing from the download block", HopperBlockEntity.suckInItems(level, hopper));
            if (!r.passed()) return r;
            r = assertTrue("hopper did not receive a diamond", hopper.getItem(0).is(Items.DIAMOND));
            if (!r.passed()) return r;
            return assertEquals("one diamond left the download block", 4, download.getItem(0).getCount());
        } finally {
            if (level.getBlockEntity(hopperPos) instanceof Container c) c.clearContent();
            if (level.getBlockEntity(pos) instanceof Container c) c.clearContent();
            level.setBlock(hopperPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    // ── Menus ──

    /** Each container block's server-side menu builds with the registered menu type and is valid for a nearby player. */
    private TestResult menusOpen() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        Map<Block, MenuType<?>> menus = new java.util.LinkedHashMap<>();
        menus.put(BankSystemBlocks.BANK_TERMINAL_BLOCK.get(), BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get());
        menus.put(BankSystemBlocks.BANK_UPLOAD_BLOCK.get(), BankSystemMenus.BANK_UPLOAD_CONTAINER_MENU.get());
        menus.put(BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get(), BankSystemMenus.BANK_DOWNLOAD_CONTAINER_MENU.get());
        menus.put(BankSystemBlocks.SHARE_STAMPER_BLOCK.get(), BankSystemMenus.SHARE_STAMPER_CONTAINER_MENU.get());
        try {
            for (Map.Entry<Block, MenuType<?>> e : menus.entrySet()) {
                level.setBlock(pos, e.getKey().defaultBlockState(), Block.UPDATE_ALL);
                String name = BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath();
                AbstractContainerMenu menu;
                try {
                    menu = ((MenuProvider) level.getBlockEntity(pos)).createMenu(9_000, player.getInventory(), player);
                } catch (Throwable t) {
                    return fail(name + ": creating the menu threw " + t);
                }
                TestResult r = assertNotNull(name + ": no menu", menu);
                if (!r.passed()) return r;
                try {
                    r = assertTrue(name + ": wrong menu type " + menu.getType(), menu.getType() == e.getValue());
                    if (!r.passed()) return r;
                    r = assertTrue(name + ": menu not valid for the player next to it", menu.stillValid(player));
                    if (!r.passed()) return r;
                } finally {
                    menu.removed(player);
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            return pass(menus.size() + " menus build on the server");
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Closing the stamper menu (AbstractContainerMenu.removed, what a close packet runs) frees the viewer lock. */
    private TestResult stamperLockReleased() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        try {
            level.setBlock(pos, BankSystemBlocks.SHARE_STAMPER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            ShareStamperBlockEntity stamper = (ShareStamperBlockEntity) level.getBlockEntity(pos);
            TestResult r = assertTrue("viewer lock not acquired", stamper.tryAcquireViewer(player.getUUID()));
            if (!r.passed()) return r;
            AbstractContainerMenu menu = stamper.createMenu(9_001, player.getInventory(), player);
            r = assertNotNull("no stamper menu", menu);
            if (!r.passed()) return r;
            menu.removed(player);
            r = assertNull("viewer lock still held after the menu closed", stamper.getCurrentViewer());
            if (!r.passed()) return r;
            return assertTrue("another player cannot open the stamper after the menu closed", stamper.tryAcquireViewer(UUID.randomUUID()));
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    // ── Full inventory ──

    /** Runs {@code body} with a full survival inventory (creative would void what does not fit); restores afterwards. */
    private TestResult withFullInventory(ServerPlayer player, Supplier<TestResult> body) {
        List<ItemStack> saved = snapshot(player);
        boolean instabuild = player.getAbilities().instabuild;
        try {
            player.getAbilities().instabuild = false;
            fillInventory(player);
            return body.get();
        } finally {
            player.getAbilities().instabuild = instabuild;
            restore(player, saved);
        }
    }

    private TestResult fullInventoryStockpile() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        Item money = BankSystemItems.MONEY100.get();
        AABB box = player.getBoundingBox().inflate(4);
        try {
            level.setBlock(pos, BankSystemBlocks.MONEY_STOCKPILE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            ((MoneyStockpileBlockEntity) level.getBlockEntity(pos)).addItems(new ItemStack(money, 5));
            return withFullInventory(player, () -> {
                Set<ItemEntity> before = itemsIn(level, box);
                // Off hand empty, main inventory full: taking from the stockpile must drop at the player.
                level.getBlockState(pos).useItemOn(ItemStack.EMPTY, level, player, InteractionHand.OFF_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                Map<Item, Integer> dropped = takeNewItems(level, box, before);
                return assertEquals("money taken from the stockpile with a full inventory", Map.of(money, 5), dropped);
            });
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** "Drop all" of the ATM converter drops the cached money as items at the player, inventory full or not. */
    private TestResult fullInventoryConverter() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        ConverterCacheManager cache = ConverterCacheManager.get();
        if (cache == null) return skip("no converter cache on this server");
        ServerLevel level = (ServerLevel) player.level();
        UUID uuid = player.getUUID();
        AABB box = player.getBoundingBox().inflate(4);
        long previous = cache.clear(uuid);
        try {
            long amount = 12_345;
            Map<Item, Integer> expected = new HashMap<>();
            for (Map.Entry<ItemID, Long> e : MoneyDenominationOptimizer.split(amount).counts().entrySet())
                expected.merge(e.getKey().getStack().getItem(), e.getValue().intValue(), Integer::sum);
            return withFullInventory(player, () -> {
                cache.deposit(uuid, amount);
                Set<ItemEntity> before = itemsIn(level, box);
                ConverterDropAllPacket.dropAllForPlayer(player);
                Map<Item, Integer> dropped = takeNewItems(level, box, before);
                TestResult r = assertEquals("converter cache emptied", 0L, cache.clear(uuid));
                if (!r.passed()) return r;
                return assertEquals("converter money dropped at the player", nonZero(expected), dropped);
            });
        } finally {
            cache.clear(uuid);
            if (previous > 0) cache.deposit(uuid, previous);
        }
    }

    /** The multi-server drop request drops what does not fit with forceDrop, and returns it without. */
    private TestResult fullInventoryMultiServerDrop() {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        ServerLevel level = (ServerLevel) player.level();
        AABB box = player.getBoundingBox().inflate(4);
        ItemID money = ItemID.of(new ItemStack(BankSystemItems.MONEY100.get()));
        return withFullInventory(player, () -> {
            Set<ItemEntity> before = itemsIn(level, box);
            Map<ItemID, Long> kept = DropItemsInPlayerInventoryRequest.dropItems(getServer(), player.getUUID(), Map.of(money, 10L), false);
            TestResult r = assertEquals("without forceDrop the items come back", Map.of(money, 10L), kept);
            if (!r.passed()) return r;
            r = assertEquals("without forceDrop nothing is dropped", Map.of(), takeNewItems(level, box, before));
            if (!r.passed()) return r;
            before = itemsIn(level, box);
            Map<ItemID, Long> notDropped = DropItemsInPlayerInventoryRequest.dropItems(getServer(), player.getUUID(), Map.of(money, 10L), true);
            r = assertTrue("forceDrop left items undelivered: " + notDropped, notDropped.isEmpty());
            if (!r.passed()) return r;
            return assertEquals("forceDrop dropped the items at the player",
                    Map.of(BankSystemItems.MONEY100.get(), 10), takeNewItems(level, box, before));
        });
    }

    // ── Terminal crafting (vanilla path: "Use Bank Items" and "Auto-deposit output" off) ──

    private TestResult withTerminalMenu(java.util.function.BiFunction<ServerPlayer, BankTerminalContainerMenu, TestResult> body) {
        ServerPlayer player = player();
        if (player == null) return skip("no online player");
        BlockPos[] row = freeRow(player, 1);
        if (row == null) return skip("no free air next to the player");
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = row[0];
        List<ItemStack> saved = snapshot(player);
        boolean instabuild = player.getAbilities().instabuild;
        try {
            player.getAbilities().instabuild = false;
            level.setBlock(pos, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            BankTerminalContainerMenu menu = new BankTerminalContainerMenu(9_002, player.getInventory(),
                    (BankTerminalBlockEntity) level.getBlockEntity(pos));
            return body.apply(player, menu);
        } finally {
            player.getAbilities().instabuild = instabuild;
            restore(player, saved);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Shift-click crafting a cake puts the cake in the inventory, leaves the buckets in the grid, closing returns them. */
    private TestResult terminalCraftRemainders() {
        return withTerminalMenu((player, menu) -> {
            clearInventory(player);
            Container grid = menu.getCraftSlots();
            Item[] cake = {Items.MILK_BUCKET, Items.MILK_BUCKET, Items.MILK_BUCKET, Items.SUGAR, Items.EGG, Items.SUGAR,
                    Items.WHEAT, Items.WHEAT, Items.WHEAT};
            for (int i = 0; i < cake.length; i++)
                grid.setItem(i, new ItemStack(cake[i]));
            TestResult r = assertTrue("no cake in the result slot", menu.getSlot(BankTerminalContainerMenu.CRAFT_RESULT_SLOT_INDEX).getItem().is(Items.CAKE));
            if (!r.passed()) return r;
            menu.clicked(BankTerminalContainerMenu.CRAFT_RESULT_SLOT_INDEX, 0, ClickType.QUICK_MOVE, player); // mc<=1.21.11
            //menu.clicked(BankTerminalContainerMenu.CRAFT_RESULT_SLOT_INDEX, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player); // mc>=26.1
            r = assertEquals("cake in the inventory", 1, countInInventory(player, Items.CAKE));
            if (!r.passed()) return r;
            int buckets = 0;
            for (int i = 0; i < grid.getContainerSize(); i++) {
                ItemStack s = grid.getItem(i);
                if (s.is(Items.BUCKET)) buckets += s.getCount();
                else if (!s.isEmpty()) return fail("ingredient left in the grid: " + s);
            }
            r = assertEquals("empty buckets left in the grid", 3, buckets);
            if (!r.passed()) return r;
            menu.removed(player);
            return assertEquals("closing returned the buckets to the inventory", 3, countInInventory(player, Items.BUCKET));
        });
    }

    /**
     * With room for only 2 of 4 planks, shift-click crafting drops the other 2 at the player; closing the menu with a
     * full inventory drops what is left in the grid.
     */
    private TestResult terminalCraftOverflow() {
        return withTerminalMenu((player, menu) -> {
            ServerLevel level = (ServerLevel) player.level();
            AABB box = player.getBoundingBox().inflate(4);
            fillInventory(player);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 62));
            Container grid = menu.getCraftSlots();
            grid.setItem(0, new ItemStack(Items.OAK_LOG, 1));
            Set<ItemEntity> before = itemsIn(level, box);
            menu.clicked(BankTerminalContainerMenu.CRAFT_RESULT_SLOT_INDEX, 0, ClickType.QUICK_MOVE, player); // mc<=1.21.11
            //menu.clicked(BankTerminalContainerMenu.CRAFT_RESULT_SLOT_INDEX, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player); // mc>=26.1
            TestResult r = assertEquals("planks in the inventory", 64, countInInventory(player, Items.OAK_PLANKS));
            if (!r.passed()) return r;
            r = assertEquals("overflow planks dropped", Map.of(Items.OAK_PLANKS, 2), takeNewItems(level, box, before));
            if (!r.passed()) return r;
            r = assertTrue("log not consumed", grid.getItem(0).isEmpty());
            if (!r.passed()) return r;
            grid.setItem(4, new ItemStack(Items.DIAMOND, 3));
            before = itemsIn(level, box);
            menu.removed(player);
            return assertEquals("grid items dropped when closing with a full inventory", Map.of(Items.DIAMOND, 3), takeNewItems(level, box, before));
        });
    }

    // ── Commands ──

    /** The op-gated /banksystem subcommands are hidden from level-0 sources and available at op level 2. */
    private TestResult adminCommandsNeedOp() {
        MinecraftServer server = getServer();
        if (server == null) return skip("no server");
        CommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot().getChild("banksystem");
        if (root == null) return fail("/banksystem is not registered");
        List<String> names = new ArrayList<>(List.of("trust", "untrust", "op", "deop", "backup"));
        if (BankSystemMod.ENABLE_DEV_FEATURES) names.add("exportrecipes");
        List<CommandSourceStack> nonOps = new ArrayList<>(List.of(server.createCommandSourceStack().withPermission(0)));
        List<CommandSourceStack> ops = new ArrayList<>(List.of(server.createCommandSourceStack().withPermission(2)));
        ServerPlayer player = player();
        if (player != null && !dev.architectury.platform.Platform.isModLoaded("luckperms")) {
            nonOps.add(player.createCommandSourceStack().withPermission(0));
            ops.add(player.createCommandSourceStack().withPermission(2));
        }
        for (String name : names) {
            CommandNode<CommandSourceStack> node = root.getChild(name);
            if (node == null) return fail("/banksystem " + name + " is not registered");
            for (CommandSourceStack s : nonOps)
                if (node.canUse(s)) return fail("/banksystem " + name + " is usable without op (" + s.getTextName() + ")");
            for (CommandSourceStack s : ops)
                if (!node.canUse(s)) return fail("/banksystem " + name + " is refused for op level 2 (" + s.getTextName() + ")");
        }
        return pass(names.size() + " op-gated commands refuse non-ops and allow ops");
    }
}
