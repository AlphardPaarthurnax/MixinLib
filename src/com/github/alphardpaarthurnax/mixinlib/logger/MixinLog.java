package com.github.alphardpaarthurnax.mixinlib.logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.asm.logging.Level;

/** 独立日志 */
public final class MixinLog {
    private static final String LOG_NAME = "mixinlib.log";
    private static final Pattern SLOT = Pattern.compile("mixinlib\\.log\\.(\\d+)");
    private static final Object LOCK = new Object();
    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final AtomicBoolean ROTATED = new AtomicBoolean();

    /** 保留近 n 次启动日志历史（槽位 1..n）；H<=0 表示不保留历史。 */
    private static final int LOG_LIMIT = Integer.getInteger("mixinlib.logLimit", 5);
    /** Mixin 通道最低级别；null 表示 OFF。 */
    private static final Level MIN_LEVEL = parseMinLevel();
    private static final boolean MIXIN_LOG_ENABLED =
            Boolean.parseBoolean(System.getProperty("mixinlib.mixin.log", "true"));
    private static final List<String> LOG_FILTER = parseFilter(
            System.getProperty("mixinlib.mixin.logFilter", ""));

    private MixinLog() {
    }

    /**
     * Launcher JVM 每次启动时调用一次：把当前日志归档为 {@code mixinlib.log.1}，
     * 更老的槽位依次后移（{@code .1} 最新、{@code .H} 最老），然后新建空日志。
     *
     * <p>单文件不限制大小；历史数由 {@code mixinlib.logLimit} 决定。级联失败时
     * 立即停止后续移动、不删除/不覆盖当前日志，回退为继续 append。
     */
    public static void beginLaunch() {
        if (!ROTATED.compareAndSet(false, true)) {
            return;
        }
        File file = logFile();
        int history = LOG_LIMIT;
        synchronized (LOCK) {
            try {
                ensureParent(file);
                Path log = file.toPath();
                if (history <= 0) {
                    for (Path slot : slots(log.getParent())) {
                        Files.delete(slot);
                    }
                    Files.deleteIfExists(log);
                    Files.createFile(log);
                } else {
                    // 3: 淘汰最老槽位 .H
                    Files.deleteIfExists(slot(log, history));
                    // 4: 严格从最老到最新级联，避免覆盖尚未转移的槽位
                    for (int index = history - 1; index >= 1; index--) {
                        Path source = slot(log, index);
                        if (Files.exists(source)) {
                            move(source, slot(log, index + 1));
                        }
                    }
                    // 5: 当前日志 -> .1
                    if (Files.exists(log)) {
                        move(log, slot(log, 1));
                    }
                    // 6: 新建空日志
                    Files.createFile(log);
                    // 7: 清理超出 1..H 的遗留槽位
                    for (Path slot : slots(log.getParent())) {
                        Matcher matcher = SLOT.matcher(slot.getFileName().toString());
                        if (matcher.matches() && Integer.parseInt(matcher.group(1)) > history) {
                            Files.delete(slot);
                        }
                    }
                }
                info("[Log] launch rotation: archived=" + (history > 0 ? "mixinlib.log.1" : "none")
                        + " slots=" + (history > 0 ? "1.." + history : "none"));
            } catch (Throwable throwable) {
                // E6：级联是多步操作，失败即停；绝不主动删除或覆盖当前日志。
                System.err.println("[MixinLib] 日志轮转失败，回退为追加写入: " + file);
                throwable.printStackTrace(System.err);
                ensureParent(file);
            }
        }
    }

    public static void info(String message) {
        write(message, null, null);
    }

    public static void error(String message, Throwable throwable) {
        write(message, throwable, null);
    }

    /**
     * Mixin / MixinExtras 通道统一出口。{@code sourceOverride} 直接作为 caller 名，
     * 跳过 {@link StackWalker}（热路径），也避免把 caller 全记成适配器类。
     */
    public static void mixin(Level level, String loggerId, String message, Throwable throwable) {
        if (!MIXIN_LOG_ENABLED || MIN_LEVEL == null || level == null) {
            return;
        }
        if (level.ordinal() > MIN_LEVEL.ordinal() || !allowed(loggerId)) {
            return;
        }
        String id = loggerId == null ? "mixin" : loggerId;
        String module = "Mixin:" + id + "/" + level;
        write("[" + module + "] " + level + " " + (message == null ? "null" : message),
                throwable, id);
    }

    private static void write(String message, Throwable throwable, String sourceOverride) {
        synchronized (LOCK) {
            File file = logFile();
            LogMessage parsed = LogMessage.parse(message);
            String source = sourceOverride != null ? sourceOverride : callerClassName();
            String line = Instant.now() + " [" + Thread.currentThread().getName() + "/"
                    + parsed.module + "] : " + source + " - " + parsed.content;
            ensureParent(file);
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                writer.println(line);
                if (throwable != null) {
                    throwable.printStackTrace(writer);
                }
            } catch (Throwable ignored) {
                System.err.println(line);
                if (throwable != null) {
                    throwable.printStackTrace(System.err);
                }
            }
        }
    }

    private static String callerClassName() {
        StackWalker.StackFrame callerFrame = STACK_WALKER.walk(frames -> frames
                .filter(frame -> frame.getDeclaringClass() != MixinLog.class)
                .findFirst()
                .orElse(null));
        Class<?> caller = callerFrame == null
                ? MixinLog.class
                : callerFrame.getDeclaringClass();
        while (caller.getEnclosingClass() != null) {
            caller = caller.getEnclosingClass();
        }
        String simple = caller.getSimpleName();
        return simple.isEmpty() ? caller.getName() : simple;
    }

    private static File logFile() {
        return new File(logDirectory(), LOG_NAME);
    }

    private static Path slot(Path log, int number) {
        return log.resolveSibling(LOG_NAME + "." + number);
    }

    private static List<Path> slots(Path directory) throws IOException {
        List<Path> result = new ArrayList<>();
        if (directory == null) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, LOG_NAME + ".*")) {
            for (Path path : stream) {
                if (SLOT.matcher(path.getFileName().toString()).matches()) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException notAtomic) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensureParent(File file) {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
    }

    private static File logDirectory() {
        String configured = System.getProperty("com.fs.starfarer.settings.paths.logs");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("user.dir", ".");
        }
        return new File(configured).getAbsoluteFile();
    }

    private static Level parseMinLevel() {
        String value = System.getProperty("mixinlib.mixin.logLevel", "INFO");
        if ("OFF".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Level.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return Level.INFO;
        }
    }

    private static List<String> parseFilter(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String item : value.split(",")) {
            String prefix = item.trim();
            if (!prefix.isEmpty()) {
                result.add(prefix);
            }
        }
        return result;
    }

    private static boolean allowed(String loggerId) {
        if (LOG_FILTER.isEmpty()) {
            return true;
        }
        if (loggerId == null) {
            return false;
        }
        for (String prefix : LOG_FILTER) {
            if (loggerId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private record LogMessage(String module, String content) {
        static LogMessage parse(String message) {
            String value = message == null ? "null" : message;
            if (value.startsWith("[")) {
                int close = value.indexOf(']');
                if (close > 1) {
                    String module = value.substring(1, close);
                    String content = value.substring(close + 1).stripLeading();
                    if (content.startsWith(":")) {
                        content = content.substring(1).stripLeading();
                    }
                    return new LogMessage(module, content);
                }
            }
            return new LogMessage("MixinLib", value);
        }
    }
}
