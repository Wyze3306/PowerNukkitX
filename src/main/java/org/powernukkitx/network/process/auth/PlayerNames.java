package org.powernukkitx.network.process.auth;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.powernukkitx.utils.TextFormat;

/**
 * Which player names a login may carry.
 * <p>
 * An authenticated name is an Xbox gamertag, but in offline mode, or behind a proxy that admits
 * unsigned logins, the client picks its name freely, and the name ends up in logs, chat, commands,
 * scoreboards and lookups. A name is refused when it is empty, longer than {@value #MAX_LENGTH}
 * characters, starts or ends with a space, or contains a character no gamertag has and that could
 * forge text elsewhere: control characters (log lines), the formatting code sign, invisible and
 * direction-changing characters, private use glyphs, {@code @} (selectors), quotes and backslashes
 * (command arguments). Letters of every script, digits and inner spaces stay allowed.
 */
@ApiStatus.Internal
public final class PlayerNames {
    /** Gamertags have at most 16 characters; the rest is margin for proxies that add a prefix. */
    public static final int MAX_LENGTH = 32;

    private PlayerNames() {
    }

    public static boolean isValid(@Nullable String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        if (Character.isSpaceChar(name.charAt(0)) || Character.isWhitespace(name.charAt(0))
            || Character.isSpaceChar(name.charAt(name.length() - 1)) || Character.isWhitespace(name.charAt(name.length() - 1))) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == ' ') {
                continue;
            }
            if (c == TextFormat.ESCAPE || c == '@' || c == '"' || c == '\\' || Character.isISOControl(c)
                || Character.isSpaceChar(c) || Character.isWhitespace(c)) {
                return false;
            }
            switch (Character.getType(c)) {
                case Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.LINE_SEPARATOR,
                     Character.PARAGRAPH_SEPARATOR -> {
                    return false;
                }
                case Character.SURROGATE -> {
                    // Only a well-formed pair, outside the private use planes
                    if (!Character.isHighSurrogate(c) || i + 1 >= name.length()
                        || !Character.isLowSurrogate(name.charAt(i + 1))) {
                        return false;
                    }
                    final int codePoint = Character.toCodePoint(c, name.charAt(i + 1));
                    final int type = Character.getType(codePoint);
                    if (type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.FORMAT) {
                        return false;
                    }
                    i++;
                }
                default -> {
                }
            }
        }
        return true;
    }
}
