package org.powernukkitx.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.channels.ClosedChannelException;

/**
 * Closes a connection whose client stops taking what the server sends.
 * <p>
 * A write the transport cannot take yet waits in the channel's outbound buffer, which has no limit
 * of its own; the session keeps writing whatever happens around the player. A client that keeps
 * the connection alive but acknowledges next to nothing would make that buffer grow for as long as
 * it stays. Beyond {@link #MAX_PENDING_BYTES} waiting bytes the connection is closed.
 */
@Slf4j
final class OutboundBacklogGuard extends ChannelOutboundHandlerAdapter {
    static final String NAME = "outboundBacklogGuard";
    /** Far more than a join with a large view distance ever has waiting. */
    static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;

    private final long maxPendingBytes;

    OutboundBacklogGuard() {
        this(MAX_PENDING_BYTES);
    }

    OutboundBacklogGuard(long maxPendingBytes) {
        this.maxPendingBytes = maxPendingBytes;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        final ChannelOutboundBuffer pending = ctx.channel().unsafe().outboundBuffer();
        if (pending != null && pending.totalPendingWriteBytes() > this.maxPendingBytes) {
            ReferenceCountUtil.release(msg);
            if (!promise.isVoid()) {
                promise.tryFailure(new ClosedChannelException());
            }
            if (ctx.channel().isOpen()) {
                log.debug("Closing {}: {} bytes are waiting to be sent", ctx.channel().remoteAddress(), pending.totalPendingWriteBytes());
                ctx.channel().close();
            }
            return;
        }
        super.write(ctx, msg, promise);
    }
}
