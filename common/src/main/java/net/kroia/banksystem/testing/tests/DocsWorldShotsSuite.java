package net.kroia.banksystem.testing.tests;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.MoneyStockpileBlockEntity;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.networking.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.gui.Gui;
import net.kroia.testkit.gui.Screenshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * BS-291: the BankSystem pictures in the world (Docs/planning/images.md, section 1), taken in the home corner placed
 * from {@code Docs/planning/structures/home-hero.nbt}, plus the wall of money items next to it. Runs after
 * {@link DocsScreenshotSuite} in the same client and uses its fixture (players, Savings and its week of history).
 * <p>
 * In a BankSystem-only cell the corner's StockMarket blocks, displays and the Trading Software frame are missing:
 * the empty frame is removed, and blocks-overview puts the ATM in the Stock Market Block's gap.
 */
public class DocsWorldShotsSuite extends DocsScreenshotSuite {

    private static final String STRUCTURE = "Docs/planning/structures/home-hero.nbt";
    private static final String STRUCTURE_PROPERTY = "banksystem.docs.structure";

    private static final BlockPos UPLOAD_BLOCK = new BlockPos(7, -59, -8), DOWNLOAD_BLOCK = new BlockPos(7, -58, -5);
    /** Why the corner is missing (the corner shots skip with it), or null. */
    private static @Nullable String cornerProblem = "home corner not placed yet";

    @Override
    public void registerTests() {
        addTest("fixture", this::test_fixture);
        addTest("home_corner", this::test_homeCorner);
        addTest("blocks_overview", () -> corner("banksystem/blocks-overview.png", 15.5, -58.0, -7.0, 53, 10, this::terminalRow));
        // eye (feet + 1.62) level with the centre of the 2x2 display
        addTest("display_in_world", () -> withFov(50, () -> corner("banksystem/display-in-world.png", 15.0, -58.62, 1.5, 0, 0, null)));
        addTest("money_stockpile", () -> withFov(50, () -> corner("banksystem/money-stockpile.png", 12.5, -58.7, 3.6, 0, 25, null)));
        addTest("automation_setup", () -> corner("banksystem/automation-setup.png", 11.5, -59, -3.5, 127, 13, this::automation));
        addTest("money_items", this::test_moneyItems);
    }

    // ---------------------------------------------------------------- home corner

    /** Places the home corner and points its Bank Displays and Money Stockpile at this world. */
    private TestResult test_homeCorner() {
        if (fixture == null) return skip(String.valueOf(fixtureProblem));
        Path source = findStructure();
        if (source == null) return skip(cornerProblem = "no " + STRUCTURE + " in a parent folder of the game dir (or -D" + STRUCTURE_PROPERTY + ")");
        MinecraftServer server = server();
        try {
            Path target = server.getWorldPath(LevelResource.GENERATED_DIR).resolve("docs/structures/home-hero.nbt");
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return fail(cornerProblem = "structure not copied: " + e);
        }
        // the cell's client renders 2 chunks; the corner and the sky behind it need more
        Gui.onClient(() -> Minecraft.getInstance().options.renderDistance().set(8));
        // placed and filled while the player is far away, so the client loads the corner already filled
        for (String command : List.of("gamemode creative", "tp @s 16 -59 -600", "forceload add 0 -16 31 15")) {
            Gui.sendCommand(command);
            Gui.waitTicks(2);
        }
        Gui.waitTicks(60);
        Gui.sendCommand("place template docs:home-hero 6 -60 -13");
        Gui.waitTicks(20);
        Gui.sendCommand("kill @e[type=!minecraft:player,type=!minecraft:item_frame]"); // the flat world's animals
        // the Trading Software frame is empty without StockMarket
        Gui.sendCommand("kill @e[type=minecraft:item_frame,nbt=!{Item:{}}]");
        String problem = onServer(() -> fillCorner(server.overworld()));
        Gui.sendCommand("tp @s 16 -59 -4");
        Gui.waitTicks(5);
        Gui.sendCommand("forceload remove 0 -16 31 15");
        if (problem != null) return fail(cornerProblem = problem);
        Gui.waitTicks(200); // chunks around the corner, display contents, chart data
        cornerProblem = null;
        return pass("placed from " + source + (Platform.isModLoaded("stockmarket") ? "" : "; without StockMarket"));
    }

    /** The structure file: the system property, else {@link #STRUCTURE} below a parent folder of the game dir. */
    private static @Nullable Path findStructure() {
        String property = System.getProperty(STRUCTURE_PROPERTY);
        if (property != null) return Files.isRegularFile(Paths.get(property)) ? Paths.get(property) : null;
        Path dir = Gui.onClient(() -> Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath());
        for (Path p = dir; p != null; p = p.getParent())
            if (Files.isRegularFile(p.resolve(STRUCTURE))) return p.resolve(STRUCTURE);
        return null;
    }

    /** Server thread: Savings' Balance History on both Bank Displays, a fresh Money Stockpile. Null or what is missing. */
    private static @Nullable String fillCorner(ServerLevel level) {
        if (!"banksystem:bank_terminal_block".equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(7, -59, -2)).getBlock()).toString()))
            return "the home corner was not placed (no Bank Terminal Block at 7 -59 -2)";
        // the main blocks of the 2x1 and the 2x2 Bank Display
        for (BlockPos pos : List.of(new BlockPos(12, -57, 5), new BlockPos(15, -57, 5))) {
            if (!(level.getBlockEntity(pos) instanceof BankSystemDisplayBlockEntity display))
                return "no Bank Display at " + pos;
            display.setConfig(BankSystemDisplayBlockEntity.DisplayType.BALANCE_HISTORY, fixture.savings());
        }
        if (!(level.getBlockEntity(new BlockPos(12, -58, 5)) instanceof MoneyStockpileBlockEntity pile))
            return "no Money Stockpile at 12 -58 5";
        pile.getItems().clear();
        List<ItemStack> money = BankSystemItems.getMoneyItems(); // coins first, then the notes by value
        for (int i = 0; i < money.size(); i++)
            pile.addItems(money.get(i).copyWithCount(i < 6 ? 1 : i < 12 ? 2 : 5));
        return null;
    }

    // ---------------------------------------------------------------- shots

    /** A shot from a camera spot of images.md (feet position, yaw, pitch); the player flies, so spots above the floor hold. */
    private TestResult corner(String file, double x, double y, double z, float yaw, float pitch, @Nullable Supplier<String> prepare) {
        if (cornerProblem != null) return skip(cornerProblem);
        return shot(file, SCENE, () -> {
            fly(x, y, z, yaw, pitch);
            return prepare == null ? null : prepare.get();
        }, null);
    }

    private static void fly(double x, double y, double z, float yaw, float pitch) {
        Gui.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
        Gui.onClient(() -> {
            var player = Minecraft.getInstance().player;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        Gui.waitTicks(40);
    }

    /**
     * The Terminal Block and the ATM join the west wall as in the terminal-family picture (ATM in place of the Share
     * Stamper). Without StockMarket the ATM takes the Stock Market Block's empty spot and the Share Stamper stays.
     */
    private @Nullable String terminalRow() {
        Gui.sendCommand("setblock 7 -59 -4 banksystem:terminal_block[facing=east]");
        Gui.sendCommand("setblock 7 -59 " + (Platform.isModLoaded("stockmarket") ? -1 : -3) + " banksystem:atm_block[facing=east]");
        Gui.waitTicks(20);
        return null;
    }

    /** Both levers on, the Upload and Download Block connected to Savings. */
    private @Nullable String automation() {
        String problem = onServer(() -> {
            var level = server().overworld();
            ServerPlayer player = server().getPlayerList().getPlayer(fixture.self());
            if (!(level.getBlockEntity(UPLOAD_BLOCK) instanceof BankUploadBlockEntity upload)) return "no Bank Upload Block at " + UPLOAD_BLOCK;
            if (!(level.getBlockEntity(DOWNLOAD_BLOCK) instanceof BankDownloadBlockEntity download)) return "no Bank Download Block at " + DOWNLOAD_BLOCK;
            upload.handlePacket(player, new UpdateBankUploadBlockEntityPacket(UPLOAD_BLOCK, true, false, fixture.savings()));
            download.applySettings(fixture.self(), fixture.savings(), List.of());
            return null;
        });
        if (problem != null) return problem;
        Gui.sendCommand("setblock 7 -59 -7 minecraft:lever[face=wall,facing=south,powered=true]");
        Gui.sendCommand("setblock 7 -58 -4 minecraft:lever[face=wall,facing=south,powered=true]");
        Gui.waitTicks(20);
        return null;
    }

    /**
     * Every coin and banknote in item frames on a wall of stripped oak logs, sorted by value: the coins in the top row,
     * then 5 $ to 500 $, then 1'000 $ to 1'000'000 $. Built away from the corner (x 37..50, z 30), camera straight in front.
     */
    private TestResult test_moneyItems() {
        if (fixture == null) return skip(String.valueOf(fixtureProblem));
        for (String command : List.of("fill 33 -60 20 54 -50 31 minecraft:air", "fill 37 -60 30 50 -55 30 minecraft:stripped_oak_log",
                "fill 33 -61 20 54 -61 29 minecraft:jungle_planks", "kill @e[type=minecraft:item_frame,x=33,y=-61,z=20,dx=21,dy=11,dz=11]")) {
            Gui.sendCommand(command);
            Gui.waitTicks(2);
        }
        String problem = onServer(() -> {
            ServerLevel level = server().overworld();
            List<ItemStack> money = BankSystemItems.getMoneyItems();
            if (money.size() != 21) return "expected 21 money items, got " + money.size();
            for (int i = 0; i < money.size(); i++) {
                int row = i < 6 ? 0 : i < 13 ? 1 : 2, col = i < 6 ? i : i < 13 ? i - 6 : i - 13;
                // the camera looks south: east (higher x) is on the left, so the values climb from east to west
                int x = (row == 2 ? 47 : 46) - col, y = -57 - row;
                ItemFrame frame = new ItemFrame(level, new BlockPos(x, y, 29), Direction.NORTH);
                frame.setItem(money.get(i).copyWithCount(1), false);
                level.addFreshEntity(frame);
            }
            return null;
        });
        if (problem != null) return fail(problem);
        Gui.waitTicks(20);
        return withFov(50, () -> shot("banksystem/money-items.png", SCENE, () -> {
            fly(44.0, -59.12, 24.0, 0, 0);
            return null;
        }, null));
    }

    /** Runs {@code shot} with a vertical field of view of {@code fov} degrees and no FOV effects, then restores both. */
    private static TestResult withFov(int fov, Supplier<TestResult> shot) {
        Options options = Gui.onClient(() -> Minecraft.getInstance().options);
        int oldFov = Gui.onClient(() -> options.fov().get());
        double oldEffects = Gui.onClient(() -> options.fovEffectScale().get());
        Gui.onClient(() -> {
            options.fov().set(fov);
            options.fovEffectScale().set(0.0);
        });
        try {
            return shot.get();
        } finally {
            Gui.onClient(() -> {
                options.fov().set(oldFov);
                options.fovEffectScale().set(oldEffects);
            });
        }
    }
}
