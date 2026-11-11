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
        mc.disconnect(createScreen(mc, refusal));
    }

    private static Screen createScreen(Minecraft mc, StartupRefusal refusal) {
        Runnable back = () -> mc.setScreen(new SelectWorldScreen(new TitleScreen()));
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
            default -> {
                return new AlertScreen(back, title, Component.translatable("gui.banksystem.load_refused.other"));
            }
        }
        for (int i = 0; i < refusal.lines().size() && i < MAX_LINES; i++)
            message.append("\n" + refusal.lines().get(i));
        if (refusal.lines().size() > MAX_LINES)
            message.append("\n...");
        message.append("\n\n").append(Component.translatable(refusal.kind() == StartupRefusal.Kind.MISSING_MODS
                ? "gui.banksystem.load_refused.missing_hint" : "gui.banksystem.load_refused.merge_hint"));
        return new ConfirmScreen(confirmed -> {
            if (confirmed && setFlag(refusal.worldDir(), flag))
                mc.createWorldOpenFlows().openWorld(refusal.worldDir().getFileName().toString(), back);
            else
                back.run();
        }, title, message, Component.translatable(button), Component.translatable("gui.banksystem.load_refused.back"));
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
