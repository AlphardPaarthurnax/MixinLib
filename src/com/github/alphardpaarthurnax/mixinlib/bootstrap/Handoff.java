package com.github.alphardpaarthurnax.mixinlib.bootstrap;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将 Launcher JVM 内容交接到 Game JVM 并启动*/
public final class Handoff {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private Handoff() {
    }

    public static boolean beforeGame(boolean fullscreen, boolean sound, String width, String height) {
        if ("game".equals(System.getProperty("mixinlib.phase"))) {
            return false;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return true;
        }

        try {
            LaunchSnapshot snapshot = capture(fullscreen, sound, width, height);
            Path snapshotFile = snapshot.writeAtomically();
            Process process = startGameProcess(snapshot, snapshotFile);
            MixinLog.info("[Handoff] game JVM started pid=" + process.pid()
                    + " enabledMods=" + snapshot.enabledMods.size()
                    + " jars=" + snapshot.allModJars().size());
            if (!Boolean.getBoolean("mixinlib.handoff.noExit")) {
                System.exit(0);
            }
            return true;
        } catch (Throwable throwable) {
            STARTED.set(false);
            MixinLog.error("[Handoff] failed; refusing to start CombatMain without Mixin", throwable);
            throw new IllegalStateException("Unable to start the MixinLib game JVM", throwable);
        }
    }

    private static LaunchSnapshot capture(boolean fullscreen, boolean sound, String width, String height) throws Exception {
        LaunchSnapshot snapshot = new LaunchSnapshot();
        snapshot.workingDirectory = new File(System.getProperty("user.dir", ".")).getCanonicalPath();
        snapshot.bridgeJar = bridgeJar().getCanonicalPath();
        snapshot.fullscreen = fullscreen;
        snapshot.sound = sound;
        snapshot.resolution = width + "x" + height;
        snapshot.coreClasspath = absoluteClasspath(snapshot.workingDirectory);

        Class<?> managerClass = Class.forName("com.fs.starfarer.launcher.ModManager", false,
                ClassLoader.getSystemClassLoader());
        Object manager = managerClass.getMethod("getInstance").invoke(null);
        List<?> enabled = new ArrayList<>((List<?>) managerClass.getMethod("getEnabledMods").invoke(manager));
        enabled.sort(Comparator.comparing(Handoff::sortKey));

        for (int index = enabled.size() - 1; index >= 0; index--) {
            Object spec = enabled.get(index);
            LaunchSnapshot.EnabledMod mod = new LaunchSnapshot.EnabledMod();
            mod.id = stringMethod(spec, "getId");
            mod.path = new File(stringMethod(spec, "getPath")).getCanonicalPath();
            @SuppressWarnings("unchecked")
            List<String> declaredJars = (List<String>) method(spec, "getJars").invoke(spec);
            for (String declared : declaredJars) {
                mod.jars.add(new File(mod.path, declared).getCanonicalPath());
            }
            snapshot.enabledMods.add(mod);
        }
        return snapshot;
    }

    private static Process startGameProcess(LaunchSnapshot snapshot, Path snapshotFile) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());

        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (isMixinLibAgent(argument) || argument.startsWith("-Dmixinlib.")
                    || argument.startsWith("-DlaunchDirect") || argument.startsWith("-DstartRes=")
                    || argument.startsWith("-DstartFS=") || argument.startsWith("-DstartSound=")) {
                continue;
            }
            command.add(argument);
        }

        command.add("-javaagent:" + snapshot.bridgeJar);
        command.add("-Dmixinlib.phase=game");
        command.add("-Dmixinlib.snapshot=" + snapshotFile.toAbsolutePath());
        command.add("-Dmixinlib.nonce=" + snapshot.nonce);
        command.add("-DlaunchDirect=true");
        command.add("-DstartRes=" + snapshot.resolution);
        command.add("-DstartFS=" + snapshot.fullscreen);
        command.add("-DstartSound=" + snapshot.sound);
        command.add("-classpath");
        command.add(snapshot.bridgeJar);
        command.add(GameBootstrap.class.getName());

        return new ProcessBuilder(command)
                .directory(new File(snapshot.workingDirectory))
                .inheritIO()
                .start();
    }

    private static String sortKey(Object spec) {
        try {
            String value = stringMethod(spec, "getSortString");
            return value == null || value.isEmpty() ? stringMethod(spec, "getName") : value;
        } catch (Exception throwable) {
            throw new IllegalStateException("Unable to read mod priority", throwable);
        }
    }

    private static Method method(Object target, String name) throws NoSuchMethodException {
        return target.getClass().getMethod(name);
    }

    private static String stringMethod(Object target, String name) throws Exception {
        Object value = method(target, name).invoke(target);
        return value == null ? null : value.toString();
    }

    private static List<String> absoluteClasspath(String workingDirectory) throws Exception {
        List<String> entries = new ArrayList<>();
        String[] parts = System.getProperty("java.class.path", "").split(File.pathSeparator);
        for (String part : parts) {
            if (!part.isBlank()) {
                File file = new File(part);
                if (!file.isAbsolute()) {
                    file = new File(workingDirectory, part);
                }
                entries.add(file.getCanonicalPath());
            }
        }
        return entries;
    }

    private static File bridgeJar() throws Exception {
        URI location = AgentEntry.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        File file = new File(location);
        if (!file.isFile()) {
            throw new IllegalStateException("Agent code source is not a jar: " + file);
        }
        return file;
    }

    private static boolean isMixinLibAgent(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        return lower.startsWith("-javaagent:") && lower.contains("mixinlib-bridge");
    }

    private static String javaExecutable() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File windows = new File(bin, "java.exe");
        return windows.isFile() ? windows.getAbsolutePath() : new File(bin, "java").getAbsolutePath();
    }
}
