package com.github.alphardpaarthurnax.mixinlib.bootstrap;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** 存储 Launcher JVM 配置的数据*/
public final class LaunchSnapshot {
    static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    int schema = SCHEMA;
    String nonce = UUID.randomUUID().toString();
    String createdAt = Instant.now().toString();
    String workingDirectory;
    String bridgeJar;
    String resolution;
    boolean fullscreen;
    boolean sound;
    List<String> coreClasspath = new ArrayList<>();
    List<EnabledMod> enabledMods = new ArrayList<>();

    static final class EnabledMod {
        String id;
        String path;
        List<String> jars = new ArrayList<>();
    }

    public List<String> allModJars() {
        List<String> result = new ArrayList<>();
        for (EnabledMod mod : enabledMods) {
            result.addAll(mod.jars);
        }
        return result;
    }

    public String bridgeJar() {
        return bridgeJar;
    }

    Path writeAtomically() throws IOException {
        Path directory = snapshotDirectory();
        Files.createDirectories(directory);
        Path destination = directory.resolve("launch-" + nonce + ".json");
        Path temporary = Files.createTempFile(directory, "launch-", ".tmp");
        Files.writeString(temporary, GSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination);
        }
        return destination;
    }

    static Claimed claim(Path source, String expectedNonce) throws IOException {
        Path claimed = source.resolveSibling(source.getFileName() + ".claimed");
        try {
            Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, claimed);
        }

        LaunchSnapshot snapshot;
        try {
            snapshot = GSON.fromJson(Files.readString(claimed, StandardCharsets.UTF_8), LaunchSnapshot.class);
        } catch (Throwable throwable) {
            throw new IOException("Invalid MixinLib launch snapshot " + claimed, throwable);
        }
        snapshot.validate(expectedNonce);
        return new Claimed(snapshot, claimed);
    }

    private void validate(String expectedNonce) throws IOException {
        if (schema != SCHEMA) {
            throw new IOException("Unsupported snapshot schema " + schema);
        }
        if (nonce == null || !nonce.equals(expectedNonce)) {
            throw new IOException("Snapshot nonce mismatch");
        }
        if (workingDirectory == null || bridgeJar == null || resolution == null) {
            throw new IOException("Incomplete launch snapshot");
        }
        validateFiles(coreClasspath, "core classpath");
        validateFiles(allModJars(), "enabled mod jar");
    }

    private static void validateFiles(List<String> files, String kind) throws IOException {
        for (String value : files) {
            if (value == null || !new File(value).isFile()) {
                throw new IOException("Missing " + kind + ": " + value);
            }
        }
    }

    private static Path snapshotDirectory() {
        String logs = System.getProperty("com.fs.starfarer.settings.paths.logs");
        File base = logs == null || logs.isBlank()
                ? new File(System.getProperty("user.dir", "."))
                : new File(logs);
        return base.getAbsoluteFile().toPath().resolve("mixinlib-launch");
    }

    static final class Claimed implements AutoCloseable {
        final LaunchSnapshot snapshot;
        private final Path path;

        Claimed(LaunchSnapshot snapshot, Path path) {
            this.snapshot = snapshot;
            this.path = path;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException throwable) {
                MixinLog.error("[Handoff] unable to delete claimed snapshot " + path, throwable);
            }
        }
    }
}
