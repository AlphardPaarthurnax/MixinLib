package com.github.alphardpaarthurnax.mixinlib.bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 各 JVM 内存中的启动配置，通过命令行交接有序的核心路径和启用 Mod JAR 路径。 */
public final class LaunchSnapshot {
    String workingDirectory;
    String bridgeJar;
    String resolution;
    boolean fullscreen;
    boolean sound;
    int enabledModCount;
    List<String> coreClasspath = new ArrayList<>();
    List<String> modJars = new ArrayList<>();
    /** 与 modJars 同序：每个 jar 属于哪个 mod（Launcher 从 spec.getId() 取得）。 */
    List<String> modIds = new ArrayList<>();

    static LaunchSnapshot create() throws Exception {
        LaunchSnapshot snapshot = new LaunchSnapshot();
        snapshot.workingDirectory = new File(System.getProperty("user.dir", ".")).getCanonicalPath();
        File bridge = new File(AgentEntry.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getCanonicalFile();
        if (!bridge.isFile()) {
            throw new IOException("Agent code source is not a jar: " + bridge);
        }
        snapshot.bridgeJar = bridge.getPath();
        return snapshot;
    }

    public List<String> allModJars() {
        return List.copyOf(modJars);
    }

    /**
     * jar 绝对路径 → 所属 mod id（路径 A：Launcher 通过 {@code --mod-id} 交接）。
     *
     * <p>旧快照没有该参数、或某个 mod 的 {@code getId()} 反射失败，都会落到
     * {@code null} ⇒ 归因降级 {@code (unknown)}，绝不影响启动。
     */
    public String modIdForJar(String jarPath) {
        for (int index = 0; index < modJars.size(); index++) {
            if (!modJars.get(index).equalsIgnoreCase(jarPath)) {
                continue;
            }
            if (index >= modIds.size()) {
                return null;
            }
            String id = modIds.get(index);
            return "-".equals(id) ? null : id;
        }
        return null;
    }

    public String bridgeJar() {
        return bridgeJar;
    }

    List<String> toArguments() {
        List<String> arguments = new ArrayList<>();
        for (String path : coreClasspath) {
            arguments.add("--core-jar");
            arguments.add(path);
        }
        for (int index = 0; index < modJars.size(); index++) {
            arguments.add("--mod-jar");
            arguments.add(modJars.get(index));
            // 只增不减：旧格式快照没有这一对参数，Game JVM 侧 modIds 为空即降级。
            arguments.add("--mod-id");
            arguments.add(index < modIds.size() ? modIds.get(index) : "-");
        }
        return arguments;
    }

    static LaunchSnapshot fromArguments(String[] arguments) throws Exception {
        LaunchSnapshot snapshot = create();
        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            List<String> paths;
            boolean modId = false;
            switch (option) {
                case "--core-jar":
                    paths = snapshot.coreClasspath;
                    break;
                case "--mod-jar":
                    paths = snapshot.modJars;
                    break;
                case "--mod-id":
                    paths = snapshot.modIds;
                    modId = true;
                    break;
                default:
                    throw new IOException("Unknown MixinLib launch argument: " + option);
            }
            if (++index >= arguments.length || arguments[index].isBlank()
                    || arguments[index].startsWith("--")) {
                throw new IOException("Missing path after " + option);
            }
            // mod id 是标识符，不是文件路径；只有 jar 路径才做 canonical 化。
            paths.add(modId ? arguments[index]
                    : new File(arguments[index]).getCanonicalPath());
        }
        if (snapshot.coreClasspath.isEmpty()) {
            throw new IOException("Missing MixinLib core classpath; expected --core-jar <path>");
        }
        for (String path : snapshot.coreClasspath) {
            File entry = new File(path);
            if (!entry.isFile() && !entry.isDirectory()) {
                throw new IOException("Missing core classpath entry: " + path);
            }
        }
        for (String path : snapshot.modJars) {
            if (!new File(path).isFile()) {
                throw new IOException("Missing enabled mod jar: " + path);
            }
        }
        if (!new File(snapshot.workingDirectory).isDirectory()) {
            throw new IOException("Missing working directory: " + snapshot.workingDirectory);
        }
        return snapshot;
    }
}
