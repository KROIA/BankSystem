package net.kroia.banksystem.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModBackend.StartupRefusal;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BS-195, singleplayer: after BankSystem refused to load a world, the integrated server stops
 * cleanly (no crash report). Once its thread has ended, this client tick replaces whatever
 * the client shows with a screen that explains why, offering the one-shot fix for missing
 * mods / merges, which writes the flag into the world's settings.json and reopens the world.
 */
public final class StartupRefusalClient {

    private static final int MAX_LINES = 8;
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private StartupRefusalClient() {
    }

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public static void onClientTick(Minecraft mc) {
        StartupRefusal refusal = BankSystemModBackend.getSingleplayerRefusal();
        if (refusal == null)
            return;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && !server.isShutdown())
            return; // still stopping
        BankSystemModBackend.clearSingleplayerRefusal();
        mc.disconnect(createScreen(mc, refusal), false);
    }

    private static Screen createScreen(Minecraft mc, StartupRefusal refusal) {
        Runnable back = () -> net.kroia.modutilities.gui.client.ClientCompat.setScreen(new SelectWorldScreen(new TitleScreen()));
        Component title = Component.translatable("gui.banksystem.load_refused.title");
        MutableComponent message = Component.empty();
        String flag;
        String button;
        switch (refusal.kind()) {
            case MISSING_MODS -> {
                message.append(Component.translatable("gui.banksystem.load_refused.missing", refusal.count()));
                flag = "CONFIRM_ITEMID_REMOVE_MISSING";
                button = "gui.banksystem.load_refused.remove_missing";
            }
            case MERGE -> {
                message.append(Component.translatable("gui.banksystem.load_refused.merge"));
                flag = "CONFIRM_ITEMID_MERGE";
                button = "gui.banksystem.load_refused.confirm_merge";
            }
            case MIGRATION_FAILED -> { // BS-274: the data upgrade failed; name the backup zip
                return new AlertScreen(back, title, refusal.lines().isEmpty()
                        ? Component.translatable("gui.banksystem.load_refused.migration_failed_no_backup")
                        : Component.translatable("gui.banksystem.load_refused.migration_failed", refusal.lines().get(0)));
            }
            default -> {
                return new AlertScreen(back, title, Component.translatable("gui.banksystem.load_refused.other"));
            }
        }
        appendCapped(message, refusal.lines());
        if (!refusal.pendingMerges().isEmpty()) { // BS-250
            message.append("\n\n").append(Component.translatable("gui.banksystem.load_refused.missing_merges"));
            appendCapped(message, refusal.pendingMerges());
        }
        message.append("\n\n").append(Component.translatable(refusal.kind() == StartupRefusal.Kind.MISSING_MODS
                ? "gui.banksystem.load_refused.missing_hint" : "gui.banksystem.load_refused.merge_hint"));
        it.unimi.dsi.fastutil.booleans.BooleanConsumer callback = confirmed -> {
            if (confirmed && setFlag(refusal.worldDir(), flag))
                mc.createWorldOpenFlows().openWorld(refusal.worldDir().getFileName().toString(), back);
            else
                back.run();
        };
        Component backLabel = Component.translatable("gui.banksystem.load_refused.back");
        if (refusal.missingItems().isEmpty())
            return new ConfirmScreen(callback, title, message, Component.translatable(button), backLabel);
        return new ConfirmScreen(callback, title, message, Component.translatable(button), backLabel) {
            @Override
            //protected void addButtons(int y) { // mc<=1.21.5
                //y = Math.min(y, height - 48); // second row below the yes/no buttons // mc<=1.21.5
                //super.addButtons(y); // mc<=1.21.5
                //addRenderableWidget(showItems().bounds(width / 2 - 155, y + 24, 310, 20).build()); // mc<=1.21.5
            protected void addButtons(net.minecraft.client.gui.layouts.LinearLayout buttonRow) { // mc>=1.21.6
                super.addButtons(buttonRow); // mc>=1.21.6
                layout.addChild(showItems().width(310).build()); // second row, laid out by init() // mc>=1.21.6
            }

            private net.minecraft.client.gui.components.Button.Builder showItems() {
                return net.minecraft.client.gui.components.Button.builder(
                        Component.translatable("gui.banksystem.load_refused.show_items", refusal.missingItems().size()),
                        b -> net.kroia.modutilities.gui.client.ClientCompat.setScreen(new MissingItemsScreen(this, refusal.missingItems())));
            }
        };
    }

    private static void appendCapped(MutableComponent message, java.util.List<String> lines) {
        for (int i = 0; i < lines.size() && i < MAX_LINES; i++)
            message.append("\n" + lines.get(i));
        if (lines.size() > MAX_LINES)
            message.append("\n... (+" + (lines.size() - MAX_LINES) + ")");
    }

    /** Sets {@code ServerBank.<flag> = true} in the world's settings.json, keeping everything else. */
    private static boolean setFlag(Path worldDir, String flag) {
        Path file = worldDir.resolve(BankSystemDataHandler.BASE_PATH).resolve("settings.json");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("ServerBank"))
                root.add("ServerBank", new JsonObject());
            root.getAsJsonObject("ServerBank").addProperty(flag, true);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
            return true;
        } catch (Exception e) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.error("Could not set " + flag + " in " + file, e);
            return false;
        }
    }
}
