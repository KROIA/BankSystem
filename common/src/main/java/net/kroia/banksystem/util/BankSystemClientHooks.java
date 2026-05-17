package net.kroia.banksystem.util;

import net.kroia.banksystem.screen.custom.ATMScreen;
import net.kroia.banksystem.screen.custom.BankAccountManagementScreen;
import net.kroia.banksystem.screen.custom.BankSystemSettingScreen;
import net.kroia.banksystem.screen.custom.TestScreen;
import net.kroia.modutilities.gui.client.RecipeImageExporter;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;


public class BankSystemClientHooks {
    private static final Logger LOGGER = LogManager.getLogger("BankSystem");
    public static void openBankSystemSettingScreen()
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(BankSystemSettingScreen::openScreen);
    }

    public static void openATMScreen()
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(ATMScreen::openScreen);
    }

    public static void openBankAccountScreen(int accountNumber, boolean isAdminMode)
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(() -> {
            BankAccountManagementScreen.openScreen(accountNumber, isAdminMode);
        });
    }

    public static void openTestScreen()
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(() -> {
            TestScreen.openScreen();
        });
    }

    /**
     * Exports all BankSystem crafting recipe images to PNG files.
     * Renders each recipe using the RecipeImageExporter and saves them
     * to the game directory under recipe_exports/banksystem/.
     * Must run on the render thread via Minecraft.getInstance().execute().
     */
    public static void exportRecipeImages()
    {
        // Must run on the render thread for GL operations
        Minecraft.getInstance().execute(() -> {
            Path outputDir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("recipe_exports/banksystem");

            int successCount = 0;
            int totalRecipes = 50;

            // 1. Software
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"ITI", "ITI", "PPP"},
                    Map.of('I', "minecraft:iron_nugget", 'T', "minecraft:ink_sac", 'P', "minecraft:paper"),
                    "banksystem:software",
                    outputDir.resolve("recipe_software.png")))
                successCount++;

            // 2. Circuit Board
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{" Q ", "CCC", "PPP"},
                    Map.of('Q', "minecraft:quartz", 'C', "minecraft:copper_ingot", 'P', "minecraft:paper"),
                    "banksystem:circuit_board",
                    outputDir.resolve("recipe_circuit_board.png")))
                successCount++;

            // 3. Display
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"GIG", "GCG", "GIG"},
                    Map.of('G', "minecraft:glass_pane", 'I', "minecraft:iron_ingot", 'C', "banksystem:circuit_board"),
                    "banksystem:display",
                    outputDir.resolve("recipe_display.png")))
                successCount++;

            // 4. ATM Software
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"SD"},
                    Map.of('S', "banksystem:software", 'D', "minecraft:dispenser"),
                    "banksystem:atm_software",
                    outputDir.resolve("recipe_atm_software.png")))
                successCount++;

            // 5. Banking Software
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"ST"},
                    Map.of('S', "banksystem:software", 'T', "minecraft:gold_ingot"),
                    "banksystem:banking_software",
                    outputDir.resolve("recipe_banking_software.png")))
                successCount++;

            // 6. Bank Transmitter Module
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"I", "C"},
                    Map.of('I', "minecraft:iron_ingot", 'C', "banksystem:circuit_board"),
                    "banksystem:bank_transmitter_module",
                    outputDir.resolve("recipe_bank_transmitter_module.png")))
                successCount++;

            // 7. Bank Receiver Module
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"C", "I"},
                    Map.of('C', "banksystem:circuit_board", 'I', "minecraft:iron_ingot"),
                    "banksystem:bank_receiver_module",
                    outputDir.resolve("recipe_bank_receiver_module.png")))
                successCount++;

            // 8. Metal Case Block
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"###", "# #", "###"},
                    Map.of('#', "minecraft:iron_ingot"),
                    "banksystem:metal_case_block",
                    outputDir.resolve("recipe_metal_case_block.png")))
                successCount++;

            // 9. Terminal Block
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"IYI", "RXR", "I#I"},
                    Map.of('I', "minecraft:iron_nugget", 'Y', "banksystem:metal_case_block",
                           'R', "minecraft:redstone", 'X', "banksystem:display",
                           '#', "banksystem:circuit_board"),
                    "banksystem:terminal_block",
                    outputDir.resolve("recipe_terminal_block.png")))
                successCount++;

            // 10. BankSystem Display Block
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"IGI", "GDG", "IMI"},
                    Map.of('I', "minecraft:iron_nugget", 'G', "minecraft:glass_pane",
                           'D', "banksystem:display", 'M', "banksystem:money"),
                    "banksystem:banksystem_display_block",
                    outputDir.resolve("recipe_banksystem_display_block.png")))
                successCount++;

            // 11. Bank Download Block
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"IYI", "ITI", "ICI"},
                    Map.of('I', "minecraft:iron_nugget", 'Y', "banksystem:metal_case_block",
                           'T', "banksystem:bank_receiver_module", 'C', "minecraft:comparator"),
                    "banksystem:bank_download_block",
                    outputDir.resolve("recipe_bank_download_block.png")))
                successCount++;

            // 12. Bank Upload Block
            if (RecipeImageExporter.exportShapedRecipe(
                    new String[]{"IYI", "ITI", "ICI"},
                    Map.of('I', "minecraft:iron_nugget", 'Y', "banksystem:metal_case_block",
                           'T', "banksystem:bank_transmitter_module", 'C', "minecraft:comparator"),
                    "banksystem:bank_upload_block",
                    outputDir.resolve("recipe_bank_upload_block.png")))
                successCount++;

            // === Money conversion recipes ===

            // $1 from higher denominations
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money5"),
                    "banksystem:money", 5, outputDir.resolve("recipe_money_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money10"),
                    "banksystem:money", 10, outputDir.resolve("recipe_money_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money20"),
                    "banksystem:money", 20, outputDir.resolve("recipe_money_3.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money50"),
                    "banksystem:money", 50, outputDir.resolve("recipe_money_4.png"))) successCount++;

            // $5
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS ", "SSS"}, Map.of('S', "banksystem:money"),
                    "banksystem:money5", 1, outputDir.resolve("recipe_money5_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money10"),
                    "banksystem:money5", 2, outputDir.resolve("recipe_money5_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money20"),
                    "banksystem:money5", 4, outputDir.resolve("recipe_money5_3.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money50"),
                    "banksystem:money5", 10, outputDir.resolve("recipe_money5_4.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money100"),
                    "banksystem:money5", 20, outputDir.resolve("recipe_money5_5.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money200"),
                    "banksystem:money5", 40, outputDir.resolve("recipe_money5_6.png"))) successCount++;

            // $10
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS"}, Map.of('S', "banksystem:money5"),
                    "banksystem:money10", 1, outputDir.resolve("recipe_money10_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money20"),
                    "banksystem:money10", 2, outputDir.resolve("recipe_money10_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money50"),
                    "banksystem:money10", 5, outputDir.resolve("recipe_money10_3.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money100"),
                    "banksystem:money10", 10, outputDir.resolve("recipe_money10_4.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money200"),
                    "banksystem:money10", 20, outputDir.resolve("recipe_money10_5.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money500"),
                    "banksystem:money10", 50, outputDir.resolve("recipe_money10_6.png"))) successCount++;

            // $20
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS"}, Map.of('S', "banksystem:money10"),
                    "banksystem:money20", 1, outputDir.resolve("recipe_money20_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS", "SS"}, Map.of('S', "banksystem:money5"),
                    "banksystem:money20", 1, outputDir.resolve("recipe_money20_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money100"),
                    "banksystem:money20", 5, outputDir.resolve("recipe_money20_3.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money200"),
                    "banksystem:money20", 10, outputDir.resolve("recipe_money20_4.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money1000"),
                    "banksystem:money20", 20, outputDir.resolve("recipe_money20_5.png"))) successCount++;

            // $50
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SSZ"}, Map.of('S', "banksystem:money20", 'Z', "banksystem:money10"),
                    "banksystem:money50", 1, outputDir.resolve("recipe_money50_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money100"),
                    "banksystem:money50", 2, outputDir.resolve("recipe_money50_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money200"),
                    "banksystem:money50", 4, outputDir.resolve("recipe_money50_3.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money500"),
                    "banksystem:money50", 10, outputDir.resolve("recipe_money50_4.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money1000"),
                    "banksystem:money50", 20, outputDir.resolve("recipe_money50_5.png"))) successCount++;

            // $100
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS ", "SSS"}, Map.of('S', "banksystem:money20"),
                    "banksystem:money100", 1, outputDir.resolve("recipe_money100_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS"}, Map.of('S', "banksystem:money50"),
                    "banksystem:money100", 1, outputDir.resolve("recipe_money100_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money200"),
                    "banksystem:money100", 2, outputDir.resolve("recipe_money100_3.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money1000"),
                    "banksystem:money100", 10, outputDir.resolve("recipe_money100_4.png"))) successCount++;

            // $200
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS"}, Map.of('S', "banksystem:money100"),
                    "banksystem:money200", 1, outputDir.resolve("recipe_money200_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS", "SS"}, Map.of('S', "banksystem:money50"),
                    "banksystem:money200", 1, outputDir.resolve("recipe_money200_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money1000"),
                    "banksystem:money200", 5, outputDir.resolve("recipe_money200_3.png"))) successCount++;

            // $500
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS ", "SSS"}, Map.of('S', "banksystem:money100"),
                    "banksystem:money500", 1, outputDir.resolve("recipe_money500_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SSZ"}, Map.of('S', "banksystem:money200", 'Z', "banksystem:money100"),
                    "banksystem:money500", 1, outputDir.resolve("recipe_money500_2.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"S"}, Map.of('S', "banksystem:money1000"),
                    "banksystem:money500", 2, outputDir.resolve("recipe_money500_3.png"))) successCount++;

            // $1000
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS"}, Map.of('S', "banksystem:money500"),
                    "banksystem:money1000", 1, outputDir.resolve("recipe_money1000_1.png"))) successCount++;
            if (RecipeImageExporter.exportShapedRecipe(new String[]{"SS ", "SSS"}, Map.of('S', "banksystem:money200"),
                    "banksystem:money1000", 1, outputDir.resolve("recipe_money1000_2.png"))) successCount++;

            // Print status summary
            LOGGER.info("[BankSystem] Recipe export complete: {}/{} recipes exported to {}",
                    successCount, totalRecipes, outputDir.toAbsolutePath());
        });
    }
}
