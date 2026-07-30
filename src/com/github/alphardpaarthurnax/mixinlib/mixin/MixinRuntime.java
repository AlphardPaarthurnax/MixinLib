package com.github.alphardpaarthurnax.mixinlib.mixin;

import com.github.alphardpaarthurnax.mixinlib.bootstrap.AgentEntry;
import com.github.alphardpaarthurnax.mixinlib.bootstrap.LaunchSnapshot;
import com.github.alphardpaarthurnax.mixinlib.janino.RuntimeTransformer;
import com.github.alphardpaarthurnax.mixinlib.loader.TargetClassLoader;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;
import com.github.alphardpaarthurnax.mixinlib.logger.TransformDiagnostics;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

/** 初始化 Fabric Mixin */
public final class MixinRuntime {
    private MixinRuntime() {
    }

    public static IMixinTransformer initialise(TargetClassLoader target, URL[] targetClasspath,
                                               LaunchSnapshot snapshot) throws Exception {
        StarsectorMixinService.configure(target, targetClasspath);
        List<String> configs = discoverConfigurations(target, snapshot);

        MixinBootstrap.init();
        IMixinService service = MixinService.getService();
        if (!(service instanceof StarsectorMixinService starsector)) {
            throw new IllegalStateException("Unexpected Mixin service: " + service.getClass().getName());
        }

        for (String config : configs) {
            Mixins.addConfiguration(config);
            MixinLog.info("[Config] enabled " + config);
        }

        IMixinTransformer transformer = starsector.createTransformer();
        target.setTransformer(transformer);
        AgentEntry.instrumentation().addTransformer(new RuntimeTransformer(target, transformer), false);
        TransformDiagnostics.announce();
        MixinLog.info("[Runtime] Fabric Mixin active configs=" + configs.size()
                + " targetUrls=" + targetClasspath.length);
        return transformer;
    }

    private static List<String> discoverConfigurations(TargetClassLoader target,
                                                       LaunchSnapshot snapshot) throws IOException {
        Map<String, byte[]> configs = new LinkedHashMap<>();
        String bridge = new File(snapshot.bridgeJar()).getCanonicalPath();

        for (String jarPath : snapshot.allModJars()) {
            File file = new File(jarPath).getCanonicalFile();
            if (file.getPath().equalsIgnoreCase(bridge)) {
                continue;
            }
            try (JarFile jar = new JarFile(file)) {
                List<String> names = declaredConfigs(jar);
                if (names.isEmpty()) {
                    names = scannedConfigs(jar);
                }
                for (String name : names) {
                    JarEntry entry = jar.getJarEntry(name);
                    if (entry == null || entry.isDirectory()) {
                        throw new IOException("Mixin config " + name + " is not present in " + file);
                    }
                    byte[] bytes;
                    try (InputStream stream = jar.getInputStream(entry)) {
                        bytes = stream.readAllBytes();
                    }
                    byte[] previous = configs.putIfAbsent(name, bytes);
                    if (previous != null) {
                        throw new IOException("Duplicate Mixin config name " + name + " (latest source " + file + ")");
                    }
                    MixinLog.info("[Config] discovered " + name + " from " + file);
                }
            }
        }

        for (Map.Entry<String, byte[]> config : configs.entrySet()) {
            target.addResourceOverride(config.getKey(), config.getValue());
        }
        return new ArrayList<>(configs.keySet());
    }

    private static List<String> declaredConfigs(JarFile jar) throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) {
            return Collections.emptyList();
        }
        Attributes attributes = manifest.getMainAttributes();
        String value = attributes.getValue("MixinConfigs");
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (String item : value.split(",")) {
            String name = item.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> scannedConfigs(JarFile jar) {
        List<String> names = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.toLowerCase(Locale.ROOT).endsWith(".mixin.json")) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }
}
