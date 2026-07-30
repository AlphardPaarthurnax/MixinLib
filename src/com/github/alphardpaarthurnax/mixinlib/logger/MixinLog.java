package com.github.alphardpaarthurnax.mixinlib.logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
/** 独立日志 */
public final class MixinLog {
    private static final long MAX_LOG_BYTES = 5000L * 1024L;
    private static final Object LOCK = new Object();
    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private MixinLog() {
    }

    public static void beginLaunch() {
        boolean rotated = false;
        synchronized (LOCK) {
            File file = logFile();
            try {
                ensureParent(file);
                if (file.isFile() && file.length() > MAX_LOG_BYTES) {
                    Files.delete(file.toPath());
                    Files.createFile(file.toPath());
                    rotated = true;
                } else if (!file.exists()) {
                    Files.createFile(file.toPath());
                }
            } catch (Throwable throwable) {
                System.err.println("[MixinLib] 无法检查或轮转日志文件 " + file);
                throwable.printStackTrace(System.err);
            }
        }
        if (rotated) {
            info("[Log] 旧日志超过 5000 KB，已删除并新建日志文件");
        }
    }

    public static void info(String message) {
        write(message, null);
    }

    public static void error(String message, Throwable throwable) {
        write(message, throwable);
    }

    private static void write(String message, Throwable throwable) {
        synchronized (LOCK) {
            File file = logFile();
            LogMessage parsed = LogMessage.parse(message);
            String line = Instant.now() + " [" + Thread.currentThread().getName() + "/"
                    + parsed.module + "] : " + callerClassName() + " - " + parsed.content;
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
        return new File(logDirectory(), "mixinlib.log");
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
