package dev.tuiframe.jline.presentation;

import dev.tuiframe.capability.ColorDepth;
import dev.tuiframe.style.CellColor;
import java.util.Arrays;

/** Session-local terminal colors, learned through read-only OSC reports. */
public final class TerminalPalette {
    private final int[] ansi = new int[16];
    private int foreground = -1;
    private long revision;

    public TerminalPalette() { Arrays.fill(ansi, -1); }

    /** Requests the default foreground and ANSI palette without changing either. */
    public static String query() {
        var request = new StringBuilder("\u001B]10;?\u001B\\\u001B]4");
        for (int index = 0; index < 16; index++) request.append(';').append(index).append(";?");
        return request.append("\u001B\\").toString();
    }

    public long revision() { return revision; }

    /** Accepts an OSC payload without its introducer or terminator. Invalid reports have no effect. */
    public boolean acceptOsc(String payload) {
        if (payload == null || payload.length() > 1024) return false;
        String[] parts = payload.split(";", -1);
        if (parts.length == 2 && parts[0].equals("10")) {
            int rgb = parseRgb(parts[1]);
            if (rgb < 0 || foreground == rgb) return false;
            foreground = rgb;
        } else if (parts.length >= 3 && parts.length % 2 == 1 && parts[0].equals("4")) {
            int[] next = ansi.clone();
            for (int part = 1; part < parts.length; part += 2) {
                int index = parseIndex(parts[part]);
                int rgb = parseRgb(parts[part + 1]);
                if (index < 0 || rgb < 0) return false;
                next[index] = rgb;
            }
            if (Arrays.equals(ansi, next)) return false;
            System.arraycopy(next, 0, ansi, 0, ansi.length);
        } else return false;
        revision++;
        return true;
    }

    /** A concrete foreground prevents bold from selecting the terminal's alternate ANSI colors. */
    String foregroundCode(CellColor color, ColorDepth depth) {
        if (depth != ColorDepth.TRUECOLOR && depth != ColorDepth.ANSI_256) return null;
        int index = ansiIndex(color);
        int rgb = color == CellColor.DEFAULT ? foreground : index < 0 ? -1 : ansi[index];
        if (rgb < 0) return null;
        int red = rgb >>> 16, green = rgb >>> 8 & 255, blue = rgb & 255;
        return depth == ColorDepth.TRUECOLOR ? "38;2;" + red + ';' + green + ';' + blue
            : "38;5;" + indexedColor(red, green, blue);
    }

    private static int ansiIndex(CellColor color) {
        return switch (color) {
            case BLACK -> 0; case RED -> 1; case GREEN -> 2; case YELLOW -> 3;
            case BLUE -> 4; case MAGENTA -> 5; case CYAN -> 6; case WHITE -> 7;
            case BRIGHT_BLACK -> 8; case BRIGHT_RED -> 9; case BRIGHT_GREEN -> 10; case BRIGHT_YELLOW -> 11;
            case BRIGHT_BLUE -> 12; case BRIGHT_MAGENTA -> 13; case BRIGHT_CYAN -> 14; case BRIGHT_WHITE -> 15;
            default -> -1;
        };
    }

    private static int parseIndex(String value) {
        if (value.isEmpty() || value.length() > 2) return -1;
        int index = 0;
        for (int offset = 0; offset < value.length(); offset++) {
            char digit = value.charAt(offset);
            if (digit < '0' || digit > '9') return -1;
            index = index * 10 + digit - '0';
        }
        return index < 16 ? index : -1;
    }

    private static int parseRgb(String value) {
        if (!value.startsWith("rgb:")) return -1;
        String[] channels = value.substring(4).split("/", -1);
        if (channels.length != 3) return -1;
        int rgb = 0;
        for (String channel : channels) {
            if (channel.isEmpty() || channel.length() > 4) return -1;
            int number = 0;
            for (int offset = 0; offset < channel.length(); offset++) {
                int digit = Character.digit(channel.charAt(offset), 16);
                if (digit < 0 || channel.charAt(offset) > 127) return -1;
                number = number * 16 + digit;
            }
            int maximum = (1 << (4 * channel.length())) - 1;
            rgb = rgb << 8 | (number * 255 + maximum / 2) / maximum;
        }
        return rgb;
    }

    private static int indexedColor(int red, int green, int blue) {
        // Avoid ANSI indices 0–15, whose bold/bright interpretation is terminal-dependent.
        int closest = 16, distance = Integer.MAX_VALUE;
        for (int index = 16; index < 256; index++) {
            int r, g, b;
            if (index >= 232) r = g = b = 8 + (index - 232) * 10;
            else {
                int cube = index - 16;
                r = cubeLevel(cube / 36); g = cubeLevel(cube / 6 % 6); b = cubeLevel(cube % 6);
            }
            int error = (red - r) * (red - r) + (green - g) * (green - g) + (blue - b) * (blue - b);
            if (error < distance) { distance = error; closest = index; }
        }
        return closest;
    }

    private static int cubeLevel(int level) { return level == 0 ? 0 : 55 + level * 40; }
}
