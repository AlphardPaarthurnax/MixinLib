package com.github.alphardpaarthurnax.mixinlib.bootstrap;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java Agent 入口。
 * Launcher JVM 只安装交接转换器，不初始化 Mixin。
 */
public final class AgentEntry {
    private static final AtomicBoolean HANDOFF_INSTALLED = new AtomicBoolean();
    private static volatile Instrumentation instrumentation;

    private AgentEntry() {
    }

    public static void premain(String arguments, Instrumentation value) {
        instrumentation = value;
        String phase = System.getProperty("mixinlib.phase", "launcher");
        if ("launcher".equals(phase)) {
            MixinLog.beginLaunch();
        }
        MixinLog.info("[Agent] phase=" + phase);

        if ("game".equals(phase)) {
            return;
        }
        if (HANDOFF_INSTALLED.compareAndSet(false, true)) {
            value.addTransformer(new HandoffTransformer(), false);
            MixinLog.info("[Agent] launcher handoff transformer installed");
        }
    }

    public static Instrumentation instrumentation() {
        Instrumentation value = instrumentation;
        if (value == null) {
            throw new IllegalStateException("MixinLib was not started with -javaagent");
        }
        return value;
    }
}
