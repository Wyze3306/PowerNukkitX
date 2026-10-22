package org.powernukkitx.network.process.handler;

import org.powernukkitx.event.player.PlayerHackDetectedEvent;

import org.powernukkitx.PlayerHandle;
import org.powernukkitx.Server;
import org.powernukkitx.network.process.PacketHandler;
import org.powernukkitx.network.process.PlayerSessionHolder;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.data.TextPacketType;
import org.cloudburstmc.protocol.bedrock.data.payload.text.AuthorAndMessage;
import org.cloudburstmc.protocol.bedrock.data.payload.text.TextPacketBody;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;

/**
 * @author Kaooot
 */
@Slf4j
public class TextHandler implements PacketHandler<TextPacket> {

    @Override
    public void handle(TextPacket packet, PlayerSessionHolder holder, Server server) {
        PlayerHandle playerHandle = holder.getPlayerHandle();
        if (!playerHandle.player.spawned || !playerHandle.player.isAlive()) {
            return;
        }

        if (!playerHandle.packetRateLimiter.tryChat()) {
            return;
        }

        boolean isXboxAuth = Server.getInstance().getSettings().baseSettings().xboxAuth();
        if (isXboxAuth && !packet.getSendersXUID().equals(playerHandle.player.getXUID())) {
            log.warn("{} sent TextPacket with invalid xuid : {} != {}", playerHandle.getUsername(), packet.getSendersXUID(), holder.getPlayer().getXUID());
            return;
        }

        final TextPacketBody body = packet.getBody();

        if (!(body instanceof AuthorAndMessage authorAndMessage)) {
            playerHandle.player.close("§cPacket handling error");
            return;
        }
        if (packet.getMessageType().equals(TextPacketType.CHAT)) {
            String chatMessage = authorAndMessage.getMessage();
            int breakLine = chatMessage.indexOf('\n');
            // Chat messages shouldn't contain break lines so ignore text afterwards
            if (breakLine != -1) {
                chatMessage = chatMessage.substring(0, breakLine);
            }
            if (chatMessage.startsWith("/") && !playerHandle.packetRateLimiter.tryCommand()) {
                // Commands sent as chat count against the command limit like the command bar
                PlayerHackDetectedEvent event = new PlayerHackDetectedEvent(
                        playerHandle.player, PlayerHackDetectedEvent.HackType.COMMAND_SPAM);
                playerHandle.player.getServer().getPluginManager().callEvent(event);
                if (event.isKick()) {
                    playerHandle.player.getSession().close("Exceeding command spam rate-limit");
                }
                return;
            }
            playerHandle.player.chat(chatMessage);
        }
    }
}