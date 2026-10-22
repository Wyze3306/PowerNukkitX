package org.powernukkitx.network.security;

import org.jetbrains.annotations.ApiStatus;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The addresses refused at the network level, each until a point in time or for good.
 * <p>
 * Temporary blocks are what an attack produces in bulk, so they are bounded: expired ones are
 * dropped when they are looked up and by {@link #purgeExpired(long)}, and once
 * {@code maxTemporary} are active no further temporary block is taken. Blocks for good are only
 * set by the operator and are kept until lifted.
 * <p>
 * An address has at most one block: a new one replaces the previous, as it always did.
 * <p>
 * Thread safe.
 */
@ApiStatus.Internal
public final class AddressBlockList {
    private final Set<InetAddress> forever = ConcurrentHashMap.newKeySet();
    private final Map<InetAddress, Long> blockedUntil = new ConcurrentHashMap<>();
    private final int maxTemporary;

    /**
     * @param maxTemporary how many temporary blocks may be active at once
     */
    public AddressBlockList(int maxTemporary) {
        this.maxTemporary = maxTemporary;
    }

    public void blockForever(InetAddress address) {
        this.blockedUntil.remove(address);
        this.forever.add(address);
    }

    /**
     * Blocks an address for {@code timeoutMillis} from {@code now}. A timeout of zero or less is a
     * block that is already over, so it only lifts the block the address had.
     *
     * @return {@code false} when the block was not taken because too many are active
     */
    public boolean block(InetAddress address, long timeoutMillis, long now) {
        this.forever.remove(address);
        if (timeoutMillis <= 0) {
            this.blockedUntil.remove(address);
            return true;
        }
        long until = Long.MAX_VALUE - timeoutMillis < now ? Long.MAX_VALUE : now + timeoutMillis;
        if (!this.blockedUntil.containsKey(address) && this.blockedUntil.size() >= this.maxTemporary) {
            this.purgeExpired(now);
            if (this.blockedUntil.size() >= this.maxTemporary) {
                return false;
            }
        }
        this.blockedUntil.put(address, until);
        return true;
    }

    public void unblock(InetAddress address) {
        this.forever.remove(address);
        this.blockedUntil.remove(address);
    }

    public boolean isBlocked(InetAddress address, long now) {
        if (this.forever.contains(address)) {
            return true;
        }
        Long until = this.blockedUntil.get(address);
        if (until == null) {
            return false;
        }
        if (until > now) {
            return true;
        }
        this.blockedUntil.remove(address, until);
        return false;
    }

    /**
     * Drops every temporary block that is over.
     */
    public void purgeExpired(long now) {
        this.blockedUntil.values().removeIf(until -> until <= now);
    }

    public int temporaryBlocks() {
        return this.blockedUntil.size();
    }
}
