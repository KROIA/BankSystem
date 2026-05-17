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
            int totalRecipes = 12;

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

            // Print status summary
            LOGGER.info("[BankSystem] Recipe export complete: {}/{} recipes exported to {}",
                    successCount, totalRecipes, outputDir.toAbsolutePath());
        });
    }
}
