package com.github.alphardpaarthurnax.mixinlib.bootstrap;

import com.github.alphardpaarthurnax.mixinlib.loader.TargetClassLoader;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;
import com.github.alphardpaarthurnax.mixinlib.mixin.MixinRuntime;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Game JVM 的主类。在准备好Mixin后调用原版入口 */
public final class GameBootstrap {
    private GameBootstrap() {
    }

    public static void main(String[] arguments) throws Throwable {
        if (!"game".equals(System.getProperty("mixinlib.phase"))) {
            throw new IllegalStateException("GameBootstrap requires -Dmixinlib.phase=game");
        }
        String snapshotProperty = System.getProperty("mixinlib.snapshot");
        String nonce = System.getProperty("mixinlib.nonce");
        if (snapshotProperty == null || nonce == null) {
            throw new IllegalStateException("Missing MixinLib snapshot/token");
        }

        try (LaunchSnapshot.Claimed claimed = LaunchSnapshot.claim(Path.of(snapshotProperty), nonce)) {
            LaunchSnapshot snapshot = claimed.snapshot;
            URL[] urls = targetUrls(snapshot);
            TargetClassLoader target = new TargetClassLoader(
                    urls, GameBootstrap.class.getClassLoader());
            MixinRuntime.initialise(target, urls, snapshot);

            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(target);
            try {
                MixinLog.info("[Bootstrap] entering Starsector with unified target loader");
                Class<?> launcher = Class.forName("com.fs.starfarer.StarfarerLauncher", true, target);
                Method main = launcher.getMethod("main", String[].class);
                main.invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException throwable) {
                throw throwable.getCause();
            } finally {
                thread.setContextClassLoader(previous);
            }
        } catch (Throwable throwable) {
            MixinLog.error("[Bootstrap] fatal game startup failure", throwable);
            throw throwable;
        }
    }

    private static URL[] targetUrls(LaunchSnapshot snapshot) throws Exception {
        Map<String, URL> ordered = new LinkedHashMap<>();
        String bridge = new File(snapshot.bridgeJar).getCanonicalPath();
        addFiles(ordered, snapshot.coreClasspath, bridge);
        addFiles(ordered, snapshot.allModJars(), bridge);
        return ordered.values().toArray(URL[]::new);
    }

    private static void addFiles(Map<String, URL> ordered, List<String> paths, String bridge) throws Exception {
        for (String path : paths) {
            File file = new File(path).getCanonicalFile();
            if (file.getPath().equalsIgnoreCase(bridge)) {
                continue;
            }
            ordered.putIfAbsent(file.getPath(), file.toURI().toURL());
        }
    }
}
