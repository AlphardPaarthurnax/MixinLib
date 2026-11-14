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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

/** 初始化 Fabric Mixin */
public final class MixinRuntime {
    /** decorate 非幂等（S1-T5 实证）：每个 config 只允许补一次 mod 归属。 */
    private static final Set<String> DECORATED = ConcurrentHashMap.newKeySet();

    private MixinRuntime() {
    }

    public static IMixinTransformer initialise(TargetClassLoader target, URL[] targetClasspath,
                                               LaunchSnapshot snapshot) throws Exception {
        StarsectorMixinService.configure(target, targetClasspath);
        List<DiscoveredConfig> configs = discoverConfigurations(target, snapshot);

        MixinBootstrap.init();
        IMixinService service = MixinService.getService();
        if (!(service instanceof StarsectorMixinService starsector)) {
            throw new IllegalStateException("Unexpected Mixin service: " + service.getClass().getName());
        }

        for (DiscoveredConfig config : configs) {
            Mixins.addConfiguration(config.name());
            MixinLog.info("[Config] enabled " + config.name());
            attribute(config.name(), snapshot.modIdForJar(config.jarPath()), config.jarPath());
        }

        IMixinTransformer transformer = starsector.createTransformer();
        target.setTransformer(transformer);
        AgentEntry.instrumentation().addTransformer(new RuntimeTransformer(target, transformer), false);

        List<InjectorSnapshot.PluginExpectation> expectations = new ArrayList<>();
        for (DiscoveredConfig config : configs) {
            expectations.add(new InjectorSnapshot.PluginExpectation(
                    config.name(), config.pluginClass()));
        }
        InjectorSnapshot.configure(expectations);
        InjectorSnapshot.announce();
        // ③ 自检由 TargetClassLoader / RuntimeTransformer 在首次 transformClass 之后触发（S1-T1）。

        TransformDiagnostics.announce();
        MixinLog.info("[Runtime] Fabric Mixin active configs=" + configs.size()
                + " targetUrls=" + targetClasspath.length);
        return transformer;
    }

    /**
     * 给 sponge 补上 mod 归属，让审计行自己说出 {@code from mod <id>}。
     * 必须在 {@code Mixins.addConfiguration(name)} 之后调用。
     *
     * <p>modId 由 Launcher JVM 通过 {@code --mod-id} 交接（路径 A）。
     * 旧快照没有该参数、或反射 {@code spec.getId()} 失败时降级为 {@code (unknown)}，
     * 只写告警，绝不阻断启动。
     */
    private static void attribute(String configName, String modId, String jarPath) {
        if (!DECORATED.add(configName)) {
            return;
        }
        boolean unknown = modId == null || modId.isBlank();
        String value = unknown ? "(unknown)" : modId;
        String status = "OK";
        try {
            boolean found = false;
            for (Config config : Mixins.getConfigs()) {
                if (!configName.equals(config.getName())) {
                    continue;
                }
                found = true;
                IMixinConfig target = config.getConfig();
                if (target == null) {
                    status = "NO_IMIXINCONFIG";
                    break;
                }
                if (target.hasDecoration(FabricUtil.KEY_MOD_ID)) {
                    // 平台/第三方已写过同键：重复写入会抛 IllegalArgumentException（T5-C）。
                    status = "PLATFORM_VALUE";
                    break;
                }
                target.decorate(FabricUtil.KEY_MOD_ID, value);
                break;
            }
            if (!found) {
                status = "CONFIG_NOT_FOUND";
            }
        } catch (Throwable throwable) {
            status = "FAILED";
            MixinLog.error("[Attribution] decorate 失败 config=" + configName
                    + " mod=" + value, throwable);
        }
        MixinLog.info("[Attribution] config=" + configName + " mod=" + value
                + " jar=" + jarPath + " status=" + status
                + (unknown ? " reason=no-mod-id-in-launch-snapshot" : ""));
    }

    private static List<DiscoveredConfig> discoverConfigurations(TargetClassLoader target,
                                                                 LaunchSnapshot snapshot)
            throws IOException {
        Map<String, DiscoveredConfig> configs = new LinkedHashMap<>();
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
                    DiscoveredConfig previous = configs.putIfAbsent(name,
                            new DiscoveredConfig(name, bytes, file.getPath(), pluginClassOf(bytes)));
                    if (previous != null) {
                        throw new IOException("Duplicate Mixin config name " + name + " (latest source " + file + ")");
                    }
                    MixinLog.info("[Config] discovered " + name + " from " + file);
                }
            }
        }

        for (DiscoveredConfig config : configs.values()) {
            target.addResourceOverride(config.name(), config.bytes());
        }
        return new ArrayList<>(configs.values());
    }

    /** config JSON 是标准 JSON；读取失败一律降级为「未声明 plugin」，不影响启动。 */
    private static String pluginClassOf(byte[] bytes) {
        try {
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement plugin = root.getAsJsonObject().get("plugin");
            if (plugin == null || plugin.isJsonNull()) {
                return null;
            }
            String value = plugin.getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
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

    /** config 名 / 原始字节 / 来源 jar（用于 mod 归因）/ config 里声明的 plugin 类名。 */
    private record DiscoveredConfig(String name, byte[] bytes, String jarPath, String pluginClass) {
    }
}
