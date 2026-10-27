package org.powernukkitx.network.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.powernukkitx.network.NetworkConstants;
import org.powernukkitx.network.protocol.legacy.LegacyCodec_v2193;

/**
 * A Minecraft version whose clients the server accepts, with the codec its sessions speak.
 * <p>
 * The server's content (blocks, items, entities, biomes) is always that of {@link #NATIVE}. A
 * session of an older version is sent the same packets, and its codec translates what the older
 * client does not know while it encodes them, see {@link LegacyCodec_v2193}. Nothing outside the
 * network layer has to care which version a player runs, but {@link #codec()} tells which one
 * when it matters.
 *
 * @see org.powernukkitx.Player#getGameProtocol()
 */
public enum GameProtocol {
    /**
     * Minecraft 1.26.50. Accepted only while {@code network-settings.multiVersion} is on.
     */
    V1_26_50(2193, LegacyCodec_v2193.CODEC),
    /**
     * Minecraft 1.26.60, the version the server's content is from.
     */
    V1_26_60(2225, NetworkConstants.CODEC);

    /**
     * The version whose content the server runs, always accepted.
     */
    public static final GameProtocol NATIVE = V1_26_60;

    private static final GameProtocol[] VALUES = values();

    private final int protocolVersion;
    private final BedrockCodec codec;

    GameProtocol(int protocolVersion, BedrockCodec codec) {
        if (codec.getProtocolVersion() != protocolVersion) {
            throw new IllegalStateException("Codec of " + this.name() + " speaks protocol " + codec.getProtocolVersion());
        }
        this.protocolVersion = protocolVersion;
        this.codec = codec;
    }

    /**
     * @return the network protocol version clients of this version send
     */
    public int protocolVersion() {
        return this.protocolVersion;
    }

    /**
     * @return the Minecraft version, such as {@code 1.26.50}
     */
    public @NotNull String minecraftVersion() {
        return this.codec.getMinecraftVersion();
    }

    /**
     * @return the codec sessions of this version encode and decode with; the same instance for
     * every session, so it may be compared by identity
     */
    public @NotNull BedrockCodec codec() {
        return this.codec;
    }

    /**
     * @return whether this is {@link #NATIVE}, whose packets need no translation
     */
    public boolean isNative() {
        return this == NATIVE;
    }

    /**
     * @return the version clients sending {@code protocolVersion} run, or {@code null} when the
     * server knows no such version. Says nothing about whether it is enabled.
     */
    public static @Nullable GameProtocol byProtocolVersion(int protocolVersion) {
        for (GameProtocol protocol : VALUES) {
            if (protocol.protocolVersion == protocolVersion) {
                return protocol;
            }
        }
        return null;
    }

    /**
     * @param multiVersion whether {@code network-settings.multiVersion} is on
     * @return the version a client sending {@code protocolVersion} is served as, or {@code null}
     * when the server does not accept that version
     */
    public static @Nullable GameProtocol accepted(int protocolVersion, boolean multiVersion) {
        final GameProtocol protocol = byProtocolVersion(protocolVersion);
        if (protocol == null || !protocol.isNative() && !multiVersion) {
            return null;
        }
        return protocol;
    }

    /**
     * @return the version whose codec is {@code codec}, compared by identity, or {@code null} for a
     * codec no version uses
     */
    public static @Nullable GameProtocol byCodec(@Nullable BedrockCodec codec) {
        for (GameProtocol protocol : VALUES) {
            if (protocol.codec == codec) {
                return protocol;
            }
        }
        return null;
    }

    /**
     * @return every version the server knows, oldest first; a new array on every call
     */
    public static GameProtocol @NotNull [] all() {
        return VALUES.clone();
    }
}
