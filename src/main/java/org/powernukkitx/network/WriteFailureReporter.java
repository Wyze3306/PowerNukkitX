package org.powernukkitx.network;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.protocol.bedrock.codec.PacketSerializeException;
import org.powernukkitx.utils.FaultBarrier;

/**
 * Tail handler that makes a failed outbound write visible.
 * <p>
 * Netty reports an outbound failure by failing the write promise, and a promise nobody listens to
 * drops its cause on the floor. Packet serialisation runs inside that write, on the netty thread and
 * far away from the game code that queued the packet, so a packet built with a field left null -
 * the usual mistake - used to sink one session's batch without a single line in the console. The
 * listener installed here belongs to no session in particular, so a failure stays with the channel
 * it happened on.
 */
final class WriteFailureReporter extends ChannelDuplexHandler {

    private static final ChannelFutureListener REPORT_FAILURE = future -> {
        final Throwable cause = future.cause();
        if (cause != null) {
            FaultBarrier.report("writing to a session", future.channel().remoteAddress(), cause);
        }
    };

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!promise.isVoid()) {
            promise.addListener(REPORT_FAILURE);
        }
        ctx.write(msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isDecodeFailure(cause)) {
            ctx.close();
            return;
        }
        // Not forwarded: netty's own tail handler would only restate it as "nobody handled this".
        FaultBarrier.report("handling a session channel event", ctx.channel().remoteAddress(), cause);
    }

    private static boolean isDecodeFailure(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof PacketSerializeException) {
                return true;
            }
        }
        return false;
    }
}
