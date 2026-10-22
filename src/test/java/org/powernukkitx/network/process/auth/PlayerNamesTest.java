package org.powernukkitx.network.process.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNamesTest {

    @Test
    void gamertagsAreAccepted() {
        assertTrue(PlayerNames.isValid("Steve"));
        assertTrue(PlayerNames.isValid("Player 1234"));
        assertTrue(PlayerNames.isValid("x_Dragon-99"));
        assertTrue(PlayerNames.isValid("Ünïcödé Ñame"));
        assertTrue(PlayerNames.isValid("名前テスト"));
        assertTrue(PlayerNames.isValid("Name#1234"));
        assertTrue(PlayerNames.isValid(".FloodgateUser"));
    }

    @Test
    void emptyOrOversizedNamesAreRefused() {
        assertFalse(PlayerNames.isValid(null));
        assertFalse(PlayerNames.isValid(""));
        assertFalse(PlayerNames.isValid("a".repeat(PlayerNames.MAX_LENGTH + 1)));
        assertTrue(PlayerNames.isValid("a".repeat(PlayerNames.MAX_LENGTH)));
    }

    @Test
    void namesThatForgeTextAreRefused() {
        assertFalse(PlayerNames.isValid("Steve\n[Server] Steve was opped"), "a new log line");
        assertFalse(PlayerNames.isValid("Steve\r"));
        assertFalse(PlayerNames.isValid("Ste\u0000ve"));
        assertFalse(PlayerNames.isValid("§cAdmin"), "formatting codes");
        assertFalse(PlayerNames.isValid("@a"), "a target selector");
        assertFalse(PlayerNames.isValid("Steve\" op"), "a quote ending a command argument");
        assertFalse(PlayerNames.isValid("Ste\\ve"));
        assertFalse(PlayerNames.isValid("Ste​ve"), "an invisible character");
        assertFalse(PlayerNames.isValid("‮evets"), "a direction override");
        assertFalse(PlayerNames.isValid("Steve"), "a private use glyph");
        assertFalse(PlayerNames.isValid("Steve "), "a line separator");
        assertFalse(PlayerNames.isValid("Ste ve"), "a space that is not a space");
        assertFalse(PlayerNames.isValid("Ste\uD800ve"), "a lone surrogate");
    }

    @Test
    void outerSpacesAreRefused() {
        assertFalse(PlayerNames.isValid(" Steve"));
        assertFalse(PlayerNames.isValid("Steve "));
        assertFalse(PlayerNames.isValid("\tSteve"));
    }
}
