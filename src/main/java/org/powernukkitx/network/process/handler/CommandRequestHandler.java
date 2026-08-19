package org.powernukkitx.network.process.handler;

import org.powernukkitx.PlayerHandle;
import org.powernukkitx.Server;
import org.powernukkitx.event.player.PlayerCommandPreprocessEvent;
import org.powernukkitx.event.player.PlayerHackDetectedEvent;
import org.powernukkitx.network.process.PacketHandler;
import org.powernukkitx.network.process.PlayerSessionHolder;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;

/**
 * @author Kaooot
 */
@Slf4j
public class CommandRequestHandler implements PacketHandler<CommandRequestPacket> {

    private static final int MAX_COMMAND_LENGTH = 512;

    @Override
    public void handle(CommandRequestPacket packet, PlayerSessionHolder holder, Server server) {
        final PlayerHandle playerHandle = holder.getPlayerHandle();
        if (!playerHandle.packetRateLimiter.tryCommand()) {
            PlayerHackDetectedEvent event = new PlayerHackDetectedEvent(
                    playerHandle.player, PlayerHackDetectedEvent.HackType.COMMAND_SPAM);
            playerHandle.player.getServer().getPluginManager().callEvent(event);
            if (event.isKick()) {
                playerHandle.player.getSession().close("Exceeding command spam rate-limit");
            }
            return;
        }
        if (!playerHandle.player.spawned || !playerHandle.player.isAlive()) {
            return;
        }
        final String command = packet.getCommand();
        if (command == null || command.isEmpty()) {
            return;
        }
        // The wire format caps a command only by the batch size, so without this a client could send
        // a multi-megabyte command line and make the tick thread parse it. Same limit as
        // SettingsCommandPacket, the other client-driven path into executeCommand.
        if (command.length() > MAX_COMMAND_LENGTH) {
            log.warn("{} sent an oversized command ({} chars)", playerHandle.getUsername(), command.length());
            playerHandle.player.close("§cPacket handling error");
            return;
        }
        PlayerCommandPreprocessEvent playerCommandPreprocessEvent = new PlayerCommandPreprocessEvent(playerHandle.player, command);
        playerHandle.player.getServer().getPluginManager().callEvent(playerCommandPreprocessEvent);
        if (playerCommandPreprocessEvent.isCancelled()) {
            return;
        }
        playerHandle.player.getServer().executeCommand(playerCommandPreprocessEvent.getPlayer(), playerCommandPreprocessEvent.getMessage());
    }
}