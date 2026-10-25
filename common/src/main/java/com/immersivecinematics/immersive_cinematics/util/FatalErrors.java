package com.immersivecinematics.immersive_cinematics.util;

/**
 * 致命错误识别 — 边界捕获（包处理 / 网络发送 / tick 清理）不能把 JVM 级故障当成普通业务异常吞掉。
 *
 * 判定为致命：内存耗尽、栈溢出、类链接失败、线程被强制终止，以及 GL 上下文丢失 / 渲染线程误用 /
 * 上下文未初始化这类「继续运行只会产生更多错误状态」的异常。调用方捕获后先
 * {@link #rethrowIfFatal(Throwable)}，再决定降级；非致命 {@link Error} 由调用方自行上抛。
 *
 * 这里按类名识别 {@code org.lwjgl.opengl.OpenGLException}，避免专用服务端未加载 LWJGL 时
 * 引用该类触发 {@link NoClassDefFoundError}。
 */
public final class FatalErrors {

    /** GL / 渲染线程相关的致命消息片段（GL 上下文已不可用，重试无意义）。 */
    private static final String[] FATAL_MESSAGES = {
            "Rendersystem called from wrong thread",
            "Not called from the render thread",
            "No GLCapabilities instance set",
            "No context is current",
            "GL_CONTEXT_LOST"
    };

    private static final String OPENGL_EXCEPTION = "org.lwjgl.opengl.OpenGLException";

    private FatalErrors() {}

    /** 是否为不应被边界捕获吞掉的致命错误。 */
    public static boolean isFatal(Throwable t) {
        if (t == null) return false;
        if (t instanceof OutOfMemoryError || t instanceof StackOverflowError
                || t instanceof LinkageError || t instanceof ThreadDeath) {
            return true;
        }
        // OpenGLException 一律致命；IllegalStateException 只在消息表明 GL 上下文已失效时致命
        if (isOpenGlException(t)) return true;
        return t instanceof IllegalStateException && hasFatalMessage(t);
    }

    /** 致命错误原样上抛（Error / RuntimeException，不引入受检异常）；非致命则返回。 */
    public static void rethrowIfFatal(Throwable t) {
        if (!isFatal(t)) return;
        if (t instanceof Error) throw (Error) t;
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        throw new RuntimeException(t);
    }

    /** GL 错误码转换：显存耗尽 → {@link OutOfMemoryError}，上下文丢失 → {@link IllegalStateException}。 */
    public static void rethrowIfFatalGlError(int code) {
        if (code == 0x0505) {
            throw new OutOfMemoryError("OpenGL GL_OUT_OF_MEMORY (0x0505)");
        }
        if (code == 0x0507) {
            throw new IllegalStateException("OpenGL GL_CONTEXT_LOST (0x0507)");
        }
    }

    private static boolean hasFatalMessage(Throwable t) {
        String message = t.getMessage();
        if (message == null) return false;
        for (String needle : FATAL_MESSAGES) {
            if (message.contains(needle)) return true;
        }
        return false;
    }

    private static boolean isOpenGlException(Throwable t) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            if (OPENGL_EXCEPTION.equals(c.getName())) return true;
        }
        return false;
    }
}
