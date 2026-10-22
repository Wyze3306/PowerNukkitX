package org.powernukkitx.network.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressBlockListTest {

    private static InetAddress address(int last) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, (byte) last});
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void temporaryBlocksExpireAndAreDropped() {
        AddressBlockList list = new AddressBlockList(16);
        assertTrue(list.block(address(1), 1_000, 0));
        assertTrue(list.isBlocked(address(1), 999));
        assertFalse(list.isBlocked(address(1), 1_000));
        assertEquals(0, list.temporaryBlocks());
    }

    @Test
    void temporaryBlocksAreBounded() {
        AddressBlockList list = new AddressBlockList(2);
        assertTrue(list.block(address(1), 1_000, 0));
        assertTrue(list.block(address(2), 5_000, 0));
        assertFalse(list.block(address(3), 1_000, 0));
        assertFalse(list.isBlocked(address(3), 0));
        // Renewing an active block is always possible
        assertTrue(list.block(address(2), 9_000, 0));
        // Once one is over there is room again
        assertTrue(list.block(address(3), 1_000, 2_000));
        assertTrue(list.isBlocked(address(3), 2_500));
        assertEquals(2, list.temporaryBlocks());
    }

    @Test
    void foreverBlocksDoNotTakeTemporaryRoom() {
        AddressBlockList list = new AddressBlockList(1);
        list.blockForever(address(1));
        list.blockForever(address(2));
        assertTrue(list.block(address(3), 1_000, 0));
        assertTrue(list.isBlocked(address(1), Long.MAX_VALUE - 1));
        list.purgeExpired(Long.MAX_VALUE);
        assertTrue(list.isBlocked(address(2), Long.MAX_VALUE - 1));
        list.unblock(address(2));
        assertFalse(list.isBlocked(address(2), 0));
    }

    @Test
    void anExpiredTimeoutLiftsTheBlock() {
        AddressBlockList list = new AddressBlockList(16);
        list.blockForever(address(1));
        assertTrue(list.block(address(1), -1, 0));
        assertFalse(list.isBlocked(address(1), 0));
        assertEquals(0, list.temporaryBlocks());
    }

    @Test
    void hugeTimeoutsDoNotOverflow() {
        AddressBlockList list = new AddressBlockList(16);
        assertTrue(list.block(address(1), Long.MAX_VALUE, 1_000));
        assertTrue(list.isBlocked(address(1), 2_000));
    }
}
