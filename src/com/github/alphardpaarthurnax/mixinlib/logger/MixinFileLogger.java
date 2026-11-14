package com.github.alphardpaarthurnax.mixinlib.logger;

import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

/**
 * sponge / MixinExtras 共用的日志适配器：把 {@code ILogger} 的四个出口全部转写到
 * {@link MixinLog}，也就是同一个 {@code mixinlib.log}。
 *
 * <p>只依赖 JDK、sponge 的日志基类和 {@code MixinLog}：不碰 {@code targetLoader()}、
 * 不触发被织入的类加载，因为在 {@code MixinService.getService()} 实例化极早期
 * （logBuffer flush）就会创建本类（S1-T3 实证）。
 */
public final class MixinFileLogger extends LoggerAdapterAbstract implements ILogger {

    public MixinFileLogger(String id) {
        super(id);
    }

    @Override
    public String getType() {
        return "MixinLib File Logger";
    }

    @Override
    public void log(Level level, String message, Object... args) {
        MixinLog.mixin(level, getId(), format(message, args), null);
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
        MixinLog.mixin(level, getId(), format(message), throwable);
    }

    @Override
    public void catching(Level level, Throwable throwable) {
        MixinLog.mixin(level, getId(), "catching " + throwable, null);
    }

    @Override
    public <T extends Throwable> T throwing(T throwable) {
        MixinLog.mixin(Level.DEBUG, getId(), "throwing " + throwable, null);
        return throwable;
    }

    private static String format(String message, Object... args) {
        return new LoggerAdapterAbstract.FormattedMessage(message, args).toString();
    }
}
