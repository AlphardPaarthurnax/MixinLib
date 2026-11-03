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

    public String bridgeJar() {
        return bridgeJar;
    }

    List<String> toArguments() {
        List<String> arguments = new ArrayList<>();
        for (String path : coreClasspath) {
            arguments.add("--core-jar");
            arguments.add(path);
        }
        for (String path : modJars) {
            arguments.add("--mod-jar");
            arguments.add(path);
        }
        return arguments;
    }

    static LaunchSnapshot fromArguments(String[] arguments) throws Exception {
        LaunchSnapshot snapshot = create();
        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            List<String> paths;
            switch (option) {
                case "--core-jar":
                    paths = snapshot.coreClasspath;
                    break;
                case "--mod-jar":
                    paths = snapshot.modJars;
                    break;
                default:
                    throw new IOException("Unknown MixinLib launch argument: " + option);
            }
            if (++index >= arguments.length || arguments[index].isBlank()
                    || arguments[index].startsWith("--")) {
                throw new IOException("Missing path after " + option);
            }
            paths.add(new File(arguments[index]).getCanonicalPath());
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
