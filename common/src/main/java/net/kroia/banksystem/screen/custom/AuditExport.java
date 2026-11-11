package net.kroia.banksystem.screen.custom;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs; // mc<=26.2

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Issue #166 (v2.2.0) — CSV export of the audit rows, written on the client only.
 */
public final class AuditExport {
    private AuditExport() {}

    /** One CSV field: quoted when needed; a leading formula character is defused so Excel shows it as text. */
    public static String field(String s) {
        if (s == null) s = "";
        if (isFormula(s)) s = "'" + s;
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** "=..", "@..", "+x"/"-x" with a non-digit x; "-12.50" stays a number. */
    private static boolean isFormula(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        if (c == '=' || c == '@' || c == '\t' || c == '\r') return true;
        return (c == '+' || c == '-') && (s.length() == 1 || !Character.isDigit(s.charAt(1)));
    }

    /** UTF-8 BOM (Excel then reads the umlauts right), CRLF lines. */
    public static String csv(String[] header, List<String[]> rows) {
        StringBuilder b = new StringBuilder("﻿");
        line(b, header);
        for (String[] r : rows) line(b, r);
        return b.toString();
    }

    private static void line(StringBuilder b, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) b.append(',');
            b.append(field(cells[i]));
        }
        b.append("\r\n");
    }

    /** Native save dialog; blocks, so never call it on the render thread. Null = cancelled. */
    @Nullable
    public static Path askPath(String title, String defaultName) {
        try (MemoryStack stack = MemoryStack.stackPush()) { // mc<=26.2
            PointerBuffer filter = stack.mallocPointer(1); // mc<=26.2
            filter.put(stack.UTF8("*.csv")).flip(); // mc<=26.2
            String p = TinyFileDialogs.tinyfd_saveFileDialog(title, defaultName, filter, "CSV"); // mc<=26.2
            if (p == null || p.isBlank()) return null; // mc<=26.2
            return Path.of(p.toLowerCase().endsWith(".csv") ? p : p + ".csv"); // mc<=26.2
        } // mc<=26.2
        // 26.3 ships no tinyfd: no dialog, a fixed export folder instead (write() posts the path in chat)
        //Path dir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("banksystem").resolve("exports"); // mc>=26.3
        //dir.toFile().mkdirs(); // mc>=26.3
        //return dir.resolve("audit-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv"); // mc>=26.3
    }

    public static void write(Path path, String csv) throws IOException {
        Files.writeString(path, csv, StandardCharsets.UTF_8);
        //net.minecraft.client.Minecraft.getInstance().execute(() -> net.minecraft.client.Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(path.toAbsolutePath().toString()).withStyle(s -> s.withUnderlined(true).withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenFile(path.toAbsolutePath()))))); // mc>=26.3
    }
}
