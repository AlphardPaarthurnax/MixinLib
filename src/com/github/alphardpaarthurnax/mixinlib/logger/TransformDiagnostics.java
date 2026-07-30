package com.github.alphardpaarthurnax.mixinlib.logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 直接写入文件且独立于 Log4j 的诊断工具。 */
public final class TransformDiagnostics {
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger MAX_ACTIVE = new AtomicInteger();
    private static final AtomicInteger CONCURRENT_LOGS = new AtomicInteger();
    private static final Map<Long, Entry> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final int CONCURRENT_LOG_LIMIT =
            Integer.getInteger("mixinlib.diag.concurrentLimit", 512);

    private TransformDiagnostics() {
    }

    public static void announce() {
        if (ANNOUNCED.compareAndSet(false, true)) {
            MixinLog.info("[Diag:ENABLED] definition failures, Log4j definitions and concurrent "
                    + "Mixin transformer entries will be recorded; concurrentLimit="
                    + CONCURRENT_LOG_LIMIT);
        }
    }

    public static Scope enter(String kind, ClassLoader loader, String className) {
        announce();
        long sequence = SEQUENCE.incrementAndGet();
        Thread thread = Thread.currentThread();
        Entry entry = new Entry(sequence, kind, className, thread.getName(), thread.getId(),
                loaderDescription(loader), System.nanoTime());
        IN_FLIGHT.put(sequence, entry);
        int active = ACTIVE.incrementAndGet();
        MAX_ACTIVE.accumulateAndGet(active, Math::max);

        boolean watched = isWatched(className);
        boolean concurrent = active > 1;
        if (watched) {
            MixinLog.info("[Diag:TRANSFORM_ENTER] " + entry.describe()
                    + " active=" + active + " inFlight=" + snapshot());
        } else if (concurrent) {
            int logNumber = CONCURRENT_LOGS.incrementAndGet();
            if (logNumber <= CONCURRENT_LOG_LIMIT) {
                MixinLog.info("[Diag:TRANSFORM_CONCURRENT] " + entry.describe()
                        + " active=" + active + " maxActive=" + MAX_ACTIVE.get()
                        + " inFlight=" + snapshot());
            } else if (logNumber == CONCURRENT_LOG_LIMIT + 1) {
                MixinLog.info("[Diag:TRANSFORM_CONCURRENT_SUPPRESSED] limit="
                        + CONCURRENT_LOG_LIMIT + " maxActive=" + MAX_ACTIVE.get());
            }
        }
        return new Scope(entry, watched);
    }

    public static boolean isWatched(String className) {
        return className != null && (className.startsWith("org.apache.log4j.")
                || className.equals("com.fs.starfarer.StarfarerLauncher")
                || className.equals("com.fs.starfarer.combat.CombatMain"));
    }

    public static String threadDescription() {
        Thread thread = Thread.currentThread();
        return thread.getName() + "#" + thread.getId();
    }

    public static String loaderDescription(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    public static String definitionStack(Iterable<String> active) {
        List<String> names = new ArrayList<>();
        for (String name : active) {
            names.add(name);
        }
        return names.toString();
    }

    private static String snapshot() {
        List<Entry> entries = new ArrayList<>(IN_FLIGHT.values());
        entries.sort(Comparator.comparingLong(Entry::sequence));
        List<String> descriptions = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            descriptions.add(entry.describe());
        }
        return descriptions.toString();
    }

    private record Entry(long sequence, String kind, String className, String threadName,
                         long threadId, String loader, long startedNanos) {
        String describe() {
            return "seq=" + sequence + " kind=" + kind + " class=" + className
                    + " thread=" + threadName + "#" + threadId + " loader=" + loader;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Entry entry;
        private final boolean watched;
        private boolean closed;

        Scope(Entry entry, boolean watched) {
            this.entry = entry;
            this.watched = watched;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            IN_FLIGHT.remove(entry.sequence());
            int active = ACTIVE.decrementAndGet();
            if (watched) {
                long elapsedMicros = (System.nanoTime() - entry.startedNanos()) / 1_000L;
                MixinLog.info("[Diag:TRANSFORM_EXIT] " + entry.describe()
                        + " elapsedMicros=" + elapsedMicros + " active=" + active);
            }
        }
    }
}
