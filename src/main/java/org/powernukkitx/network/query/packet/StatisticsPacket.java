package org.powernukkitx.network.query.packet;

import org.powernukkitx.network.query.QueryPacket;
import io.netty.buffer.ByteBuf;

public class StatisticsPacket implements QueryPacket {
    private static final short ID = 0x00;
    // Both
    private int sessionId;
    // Request
    private int token;
    private boolean full;
    // Response, kept as bytes: the packet may be dropped before it is ever encoded, and a ByteBuf
    // held here would then never be released
    private byte[] payload;

    @Override
    public void decode(ByteBuf buffer) {
        sessionId = buffer.readInt();
        token = buffer.readInt();
        full = (buffer.isReadable());
        buffer.skipBytes(buffer.readableBytes());
    }

    @Override
    public void encode(ByteBuf buffer) {
        buffer.writeInt(sessionId);
        if (payload != null) {
            buffer.writeBytes(payload);
        }
    }

    @Override
    public short getId() {
        return ID;
    }

    @Override
    public int getSessionId() {
        return sessionId;
    }

    @Override
    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }

    public int getToken() {
        return token;
    }

    public void setToken(int token) {
        this.token = token;
    }

    public boolean isFull() {
        return full;
    }

    public void setFull(boolean full) {
        this.full = full;
    }

    public ByteBuf getPayload() {
        return payload == null ? io.netty.buffer.Unpooled.EMPTY_BUFFER : io.netty.buffer.Unpooled.wrappedBuffer(payload);
    }

    /**
     * Copies and releases {@code payload}.
     */
    public void setPayload(ByteBuf payload) {
        try {
            this.payload = io.netty.buffer.ByteBufUtil.getBytes(payload);
        } finally {
            payload.release();
        }
    }
}
