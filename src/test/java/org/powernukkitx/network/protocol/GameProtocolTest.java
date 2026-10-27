package org.powernukkitx.network.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GameProtocolTest {

    @Test
    void acceptsTheNativeVersionAlways() {
        Assertions.assertEquals(GameProtocol.V1_26_60, GameProtocol.accepted(2225, false));
        Assertions.assertEquals(GameProtocol.V1_26_60, GameProtocol.accepted(2225, true));
    }

    @Test
    void acceptsTheOlderVersionOnlyWhenEnabled() {
        Assertions.assertNull(GameProtocol.accepted(2193, false));
        Assertions.assertEquals(GameProtocol.V1_26_50, GameProtocol.accepted(2193, true));
    }

    @Test
    void refusesEveryOtherVersion() {
        for (int version : new int[]{0, 2192, 2207, 2226, 9999, -1}) {
            Assertions.assertNull(GameProtocol.accepted(version, true), String.valueOf(version));
        }
    }
}
