package com.github.alphardpaarthurnax.mixinlib.mixin;

/** 保存当前线程正在转换的类、加载器和字节。*/
public final class TransformContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TransformContext() {
    }

    public static Scope enter(ClassLoader loader, String className, byte[] bytes) {
        State previous = CURRENT.get();
        CURRENT.set(new State(loader, className, bytes));
        return new Scope(previous);
    }

    public static ClassLoader loader() {
        State state = CURRENT.get();
        return state == null ? null : state.loader;
    }

    public static byte[] bytes(String className) {
        State state = CURRENT.get();
        return state != null && state.className.equals(className) ? state.bytes : null;
    }

    private record State(ClassLoader loader, String className, byte[] bytes) {
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;

        Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
