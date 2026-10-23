package org.powernukkitx.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.powernukkitx.event.Event;
import org.powernukkitx.event.Listener;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MethodEventExecutorTest {

    public static class Ping extends Event {
    }

    public static class Probe implements Listener {
        public int calls;

        public void on(Ping ping) {
            calls++;
        }
    }

    /** Rend la méthode à la première lecture, puis null : le GC qui vide la référence entre deux lectures. */
    private static final class ClearedAfterFirstRead extends WeakReference<Method> {
        private int reads;

        ClearedAfterFirstRead(Method method) {
            super(method);
        }

        @Override
        public Method get() {
            return reads++ == 0 ? super.get() : null;
        }
    }

    private static Field reference() throws NoSuchFieldException {
        Field field = MethodEventExecutor.class.getDeclaredField("defineClassMethodRef");
        field.setAccessible(true);
        return field;
    }

    @AfterEach
    void reset() throws Exception {
        reference().set(null, new WeakReference<Method>(null));
    }

    @Test
    void compilesWhenTheGarbageCollectorClearsTheReferenceBetweenTwoReads() throws Exception {
        Method define = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
        reference().set(null, new ClearedAfterFirstRead(define));

        EventExecutor executor = MethodEventExecutor.compile(Probe.class, Probe.class.getMethod("on", Ping.class));

        assertNotNull(executor);
        Probe probe = new Probe();
        executor.execute(probe, new Ping());
        assertEquals(1, probe.calls);
    }

    @Test
    void compilesWhenTheReferenceIsAlreadyCleared() throws Exception {
        reference().set(null, new WeakReference<Method>(null));

        EventExecutor executor = MethodEventExecutor.compile(Probe.class, Probe.class.getMethod("on", Ping.class));

        assertNotNull(executor);
        Probe probe = new Probe();
        executor.execute(probe, new Ping());
        assertEquals(1, probe.calls);
    }
}
