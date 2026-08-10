package org.powernukkitx.network.process.handler;

import org.powernukkitx.PlayerHandle;
import org.powernukkitx.Server;
import org.powernukkitx.event.player.PlayerCommandPreprocessEvent;
import org.powernukkitx.event.player.PlayerHackDetectedEvent;
import org.powernukkitx.network.process.PacketHandler;
import org.powernukkitx.network.process.PlayerSessionHolder;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.protocol.bedrock.packet.SettingsCommandPacket;

/**
 * @author Kaooot
 */
@Slf4j
public class SettingsCommandHandler implements PacketHandler<SettingsCommandPacket> {
    private static final int MAX_COMMAND_LENGTH = 512;

    @Override
    public void handle(SettingsCommandPacket packet, PlayerSessionHolder holder, Server server) {
        final PlayerHandle playerHandle = holder.getPlayerHandle();
        if (playerHandle == null || playerHandle.player == null) {
            return;
        }
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
        String command = packet.getCommand();
        if (command == null || command.isEmpty()) {
            return;
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            log.warn("{} sent an oversized SettingsCommand ({} chars)", playerHandle.getUsername(), command.length());
            playerHandle.player.close("§cPacket handling error");
            return;
        }
        // A settings command is a single line; anything after a break is the client
        // smuggling extra commands into one packet.
        int breakLine = command.indexOf('\n');
        if (breakLine != -1) {
            command = command.substring(0, breakLine);
        }
        if (command.isEmpty()) {
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
