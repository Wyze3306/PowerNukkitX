package org.powernukkitx.utils.collection;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

public final class FreezableByteArray implements ByteArrayWrapper, AutoFreezable {
    private final FreezableArrayManager manager;
    private final AtomicReference<FreezeStatus> freezeStatus = new AtomicReference<>(FreezeStatus.NONE);
    private volatile int temperature;
    private final int rawLength;
    private byte[] data;

    FreezableByteArray(int length, @NotNull FreezableArrayManager manager) {
        this.rawLength = length;
        this.data = new byte[length];
        this.manager = manager;
        this.temperature = manager.getDefaultTemperature();
    }

    FreezableByteArray(@NotNull byte[] src, @NotNull FreezableArrayManager manager) {
        this.rawLength = src.length;
        this.data = src;
        this.manager = manager;
        this.temperature = manager.getDefaultTemperature();
    }

    public FreezableArrayManager getManager() {
        return manager;
    }

    @Override
    public FreezeStatus getFreezeStatus() {
        return freezeStatus.get();
    }

    @Override
    public int getTemperature() {
        return temperature;
    }

    @Override
    public void warmer(int temperature) {
        this.temperature = Math.min(manager.getBoilingPoint(), this.temperature + temperature);
    }

    @Override
    public void colder(int temperature) {
        this.temperature = Math.max(manager.getAbsoluteZero(), this.temperature - temperature);
    }

    // Every state change runs under the array's lock: the pool's freeze cycle and the chunk
    // threads touch the same arrays, and the old busy-wait let a thaw read a half-swapped buffer
    // (LZ4 corruption, upstream #3324). The copy keeps a reader holding the raw array unaffected.
    @Override
    public synchronized void freeze() {
        if (temperature > manager.getFreezingPoint()) return;
        if (!freezeStatus.compareAndSet(FreezeStatus.NONE, FreezeStatus.FREEZING)) return;
        try {
            data = LZ4Freezer.compressor.compress(data.clone());
            freezeStatus.set(FreezeStatus.FREEZE);
        } catch (RuntimeException | Error e) {
            freezeStatus.set(FreezeStatus.NONE);
            throw e;
        }
    }

    @Override
    public synchronized void deepFreeze() {
        if (temperature > manager.getAbsoluteZero()) return;
        // Both frozen states use the same compression format.
        if (freezeStatus.compareAndSet(FreezeStatus.FREEZE, FreezeStatus.DEEP_FREEZE)) return;
        if (!freezeStatus.compareAndSet(FreezeStatus.NONE, FreezeStatus.DEEP_FREEZING)) return;
        try {
            data = LZ4Freezer.deepCompressor.compress(data.clone());
            freezeStatus.set(FreezeStatus.DEEP_FREEZE);
        } catch (RuntimeException | Error e) {
            freezeStatus.set(FreezeStatus.NONE);
            throw e;
        }
    }

    @Override
    public synchronized void thaw() {
        FreezeStatus status = freezeStatus.get();
        if (status == FreezeStatus.FREEZE || status == FreezeStatus.DEEP_FREEZE) {
            freezeStatus.set(FreezeStatus.THAWING);
            try {
                data = LZ4Freezer.decompressor.decompress(data, rawLength);
                freezeStatus.set(FreezeStatus.NONE);
            } catch (RuntimeException | Error e) {
                freezeStatus.set(status);
                throw e;
            }
        }
        if (temperature < manager.getMeltingHeat()) temperature = manager.getMeltingHeat();
    }

    @Override
    public synchronized byte[] getRawBytes() {
        if (freezeStatus.get() != FreezeStatus.NONE) thaw();
        warmer(manager.getBatchOperationHeat());
        return data;
    }

    @Override
    public synchronized void setRawBytes(byte[] bytes) {
        data = bytes;
        freezeStatus.set(FreezeStatus.NONE);
    }

    @Override
    public synchronized byte getByte(int index) {
        if (freezeStatus.get() != FreezeStatus.NONE) thaw();
        warmer(manager.getSingleOperationHeat());
        return data[index];
    }

    @Override
    public synchronized void setByte(int index, byte b) {
        if (freezeStatus.get() != FreezeStatus.NONE) thaw();
        warmer(manager.getSingleOperationHeat());
        data[index] = b;
    }
}
