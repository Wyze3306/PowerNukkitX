package org.powernukkitx.network.query.handler;

import org.powernukkitx.event.server.QueryRegenerateEvent;
import org.powernukkitx.network.query.QueryEventListener;
import org.powernukkitx.network.query.enveloped.DirectAddressedQueryPacket;
import org.powernukkitx.network.query.packet.HandshakePacket;
import org.powernukkitx.network.query.packet.StatisticsPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

public class QueryPacketHandler extends SimpleChannelInboundHandler<DirectAddressedQueryPacket> {
    /** Requests one address may make per second; Query is polled by server lists, not spammed. */
    private static final int REQUESTS_PER_SECOND = 8;
    /** Distinct addresses tracked per window; beyond it new senders are dropped until it rolls. */
    private static final int MAX_TRACKED_ADDRESSES = 65_536;
    private static final long TOKEN_LIFETIME_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    private final QueryEventListener listener;
    private volatile byte[] token;
    private volatile byte[] previousToken;
    private volatile long tokenIssuedNanos = System.nanoTime();
    private final java.util.Map<java.net.InetAddress, int[]> requests = new java.util.HashMap<>();
    private long windowStartNanos = System.nanoTime();

    public QueryPacketHandler(QueryEventListener listener) {
        this.listener = listener;

        this.token = this.generateToken();
    }


    /**
     * Fixed one-second window per address. Runs on the channel's event loop only, so a plain map
     * is enough; it is cleared every window, which also bounds its size.
     */
    private boolean allow(InetSocketAddress sender) {
        long now = System.nanoTime();
        if (now - this.windowStartNanos >= 1_000_000_000L) {
            this.requests.clear();
            this.windowStartNanos = now;
        }
        if (now - this.tokenIssuedNanos >= TOKEN_LIFETIME_NANOS) {
            this.refreshToken();
        }
        int[] count = this.requests.get(sender.getAddress());
        if (count == null) {
            if (this.requests.size() >= MAX_TRACKED_ADDRESSES) {
                return false;
            }
            this.requests.put(sender.getAddress(), count = new int[1]);
        }
        return ++count[0] <= REQUESTS_PER_SECOND;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DirectAddressedQueryPacket packet) {
        if (!this.allow(packet.sender())) {
            return;
        }
        if (packet.content() instanceof HandshakePacket handshake) {
            handshake.setToken(getTokenString(packet.sender()));
            ctx.writeAndFlush(new DirectAddressedQueryPacket(handshake, packet.sender(), packet.recipient()), ctx.voidPromise());
        }
        if (packet.content() instanceof StatisticsPacket statistics) {
            // A token handed out just before a rotation stays good for one more lifetime
            if (statistics.getToken() != getTokenInt(packet.sender(), this.token)
                && (this.previousToken == null || statistics.getToken() != getTokenInt(packet.sender(), this.previousToken))) {
                return;
            }

            QueryRegenerateEvent data = this.listener.onQuery(packet.sender());

            if (statistics.isFull()) {
                statistics.setPayload(data.getLongQuery());
            } else {
                statistics.setPayload(data.getShortQuery());
            }
            ctx.writeAndFlush(new DirectAddressedQueryPacket(statistics, packet.sender(), packet.recipient()), ctx.voidPromise());
        }
    }

    private byte[] generateToken() {
        byte[] bytes = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(bytes);
        return bytes;
    }

    public void refreshToken() {
        this.previousToken = this.token;
        this.token = this.generateToken();
        this.tokenIssuedNanos = System.nanoTime();
    }

    private String getTokenString(InetSocketAddress socketAddress) {
        return Integer.toString(getTokenInt(socketAddress, this.token));

    }

    private int getTokenInt(InetSocketAddress socketAddress, byte[] secret) {
        return ByteBuffer.wrap(getToken(socketAddress, secret)).getInt();
    }

    private byte[] getToken(InetSocketAddress socketAddress, byte[] secret) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException var3) {
            throw new InternalError("SHA-512 not supported", var3);
        }

        digest.update(socketAddress.toString().getBytes());
        byte[] digested = digest.digest(secret);
        return Arrays.copyOf(digested, 4);
    }
}
