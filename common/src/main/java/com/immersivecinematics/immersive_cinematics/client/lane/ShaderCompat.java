package com.immersivecinematics.immersive_cinematics.client.lane;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Iris / Oculus 兼容适配层：只通过公开 API 与反射访问，不在编译期依赖任何光影模组。
 *
 * <p>能力探测：</p>
 * <ul>
 *   <li>pipeline：{@code Iris.getPipelineManager().getPipelineNullable()}（Iris/Oculus 公开入口）；</li>
 *   <li>renderTargets / shadowRenderTargets：pipeline 字段；</li>
 *   <li>历史目标：扫描光影包 composite/deferred/begin/prepare 的 fragment source，找
 *       {@code texture*(colortexN, texcoordPast|prvCoord|previous*)} 这类“上一帧采样”；
 *       只有真正被当作历史的 colortex 才会被隔离，不会误伤主颜色或 gbuffer；</li>
 *   <li>没有任何硬编码的 colortex 编号；探测不到就不隔离，并在日志里说明。</li>
 * </ul>
 *
 * <p>类名候选同时覆盖新包名（{@code net.irisshaders.iris}）与旧包名
 * （{@code net.coderbot.iris}）；字段/方法缺失时返回 null，由调用方静默降级。</p>
 *
 * <p>pipeline 变化（切换光影包 / 关闭光影）时，{@link #refresh()} 会重置每视角历史快照和
 * previous/tracker 状态；Iris 只清理自己的 pipeline，不会清理我们的缓存。</p>
 */
public final class ShaderCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String[] IRIS_CLASSES = {
            "net.irisshaders.iris.Iris",
            "net.coderbot.iris.Iris"
    };

    /** 只匹配“用上一帧坐标采样 colortex”的语句，不匹配 texelFetch 的当前帧邻域。 */
    private static final Pattern HISTORY_SAMPLE = Pattern.compile(
            "texture\\w*\\s*\\(\\s*colortex(\\d+)\\s*,\\s*(?:texcoordPast|prvCoord|previous\\w*)",
            Pattern.CASE_INSENSITIVE);

    private static Object pipeline;
    private static Object renderTargets;
    private static Object shadowTargets;
    private static int[] historyTargets = new int[0];

    private ShaderCompat() {
    }

    /**
     * 刷新 pipeline 缓存；pipeline 未变化时零反射开销。
     *
     * @return true = pipeline 发生变化（含光影关闭/开启），调用方应清空视角栈
     */
    public static boolean refresh() {
        Object current = currentPipeline();
        if (current == pipeline) {
            return false;
        }
        boolean first = pipeline == null && current != null;
        pipeline = current;
        renderTargets = field(current, "renderTargets");
        shadowTargets = field(current, "shadowRenderTargets");
        historyTargets = discoverHistoryTargets();
        if (first) {
            ViewStateCapture.onPipelineReady();
        } else {
            ViewStateCapture.onPipelineChanged();
        }
        // 旧快照属于旧 pipeline；切换光影后不能把旧历史 blit 回新 live。
        ColorHistoryCapture.reset();
        LOGGER.info("[shader-compat] pipeline={} historyTargets={} shadowTargets={} first={}",
                current == null ? "none" : current.getClass().getSimpleName(),
                java.util.Arrays.toString(historyTargets), shadowTargets != null, first);
        return true;
    }

    /** 光影历史目标；空数组 = 探测不到，不隔离。 */
    public static int[] historyTargets() {
        return historyTargets;
    }

    public static Object shadowTargets() {
        return shadowTargets;
    }

    public static Object target(int index) {
        return invoke(renderTargets, "get", new Class<?>[]{int.class}, index);
    }

    public static int width(Object target) {
        return intValue(invoke(target, "getWidth", new Class<?>[]{}));
    }

    public static int height(Object target) {
        return intValue(invoke(target, "getHeight", new Class<?>[]{}));
    }

    public static int format(Object target) {
        Object format = invoke(target, "getInternalFormat", new Class<?>[]{});
        return intValue(invoke(format, "getGlFormat", new Class<?>[]{}));
    }

    public static int mainTexture(Object target) {
        return intValue(invoke(target, "getMainTexture", new Class<?>[]{}));
    }

    public static int altTexture(Object target) {
        return intValue(invoke(target, "getAltTexture", new Class<?>[]{}));
    }

    private static Object currentPipeline() {
        for (String className : IRIS_CLASSES) {
            Class<?> iris = classOrNull(className);
            if (iris == null) {
                continue;
            }
            Object manager = invokeStatic(iris, "getPipelineManager", new Class<?>[]{});
            Object current = invoke(manager, "getPipelineNullable", new Class<?>[]{});
            if (current == null) {
                Object optional = invoke(manager, "getPipeline", new Class<?>[]{});
                if (optional instanceof Optional<?> value) {
                    current = value.orElse(null);
                }
            }
            return current;
        }
        return null;
    }

    private static int[] discoverHistoryTargets() {
        Set<Integer> targets = new LinkedHashSet<>();
        Object pack = field(pipeline, "pack");
        Object programSet = field(pack, "base");
        scanPrograms(targets, programSet);
        if (targets.isEmpty()) {
            scanPack(targets);
        }
        return targets.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void scanPrograms(Set<Integer> targets, Object programSet) {
        if (programSet == null) {
            return;
        }
        for (String getter : new String[]{"getComposite", "getDeferred", "getBegin", "getPrepare"}) {
            scanArray(targets, invoke(programSet, getter, new Class<?>[]{}));
        }
        Object finalSource = invoke(programSet, "getCompositeFinal", new Class<?>[]{});
        if (finalSource instanceof Optional<?> optional) {
            scanSource(targets, optional.orElse(null));
        }
    }

    private static void scanArray(Set<Integer> targets, Object value) {
        if (value instanceof Object[] array) {
            for (Object source : array) {
                scanSource(targets, source);
            }
        }
    }

    private static void scanSource(Set<Integer> targets, Object source) {
        if (source == null) {
            return;
        }
        Object fragment = invoke(source, "getFragmentSource", new Class<?>[]{});
        if (!(fragment instanceof Optional<?> optional) || !(optional.orElse(null) instanceof String text)) {
            return;
        }
        Matcher matcher = HISTORY_SAMPLE.matcher(text);
        while (matcher.find()) {
            try {
                targets.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // malformed match; skip
            }
        }
    }

    /** 包内源码兜底：ProgramSource 可能保留 #include 原文，直接扫当前光影包目录/zip。 */
    private static void scanPack(Set<Integer> targets) {
        Class<?> iris = classOrNull(IRIS_CLASSES[0]);
        if (iris == null) {
            iris = classOrNull(IRIS_CLASSES[1]);
        }
        if (iris == null) {
            return;
        }
        Object directory = staticField(iris, "shaderpacksDirectory");
        Object packName = staticField(iris, "currentPackName");
        if (!(directory instanceof Path base) || !(packName instanceof String name)) {
            return;
        }
        Path pack = base.resolve(name);
        try {
            if (Files.isDirectory(pack)) {
                try (Stream<Path> files = Files.walk(pack)) {
                    files.filter(Files::isRegularFile).forEach(path -> scanFile(targets, path));
                }
            } else if (Files.isRegularFile(pack)) {
                try (ZipFile zip = new ZipFile(pack.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !isShader(entry.getName())) {
                            continue;
                        }
                        try (InputStream input = zip.getInputStream(entry)) {
                            scanText(targets, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.info("[shader-compat] pack scan failed: {}", t.toString());
        }
    }

    private static void scanFile(Set<Integer> targets, Path path) {
        if (!isShader(path.toString())) {
            return;
        }
        try {
            scanText(targets, Files.readString(path));
        } catch (Throwable ignored) {
            // unreadable file; skip
        }
    }

    private static void scanText(Set<Integer> targets, String text) {
        Matcher matcher = HISTORY_SAMPLE.matcher(text);
        while (matcher.find()) {
            try {
                targets.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // malformed match; skip
            }
        }
    }

    private static boolean isShader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".fsh") || lower.endsWith(".glsl");
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeStatic(Class<?> owner, String name, Class<?>[] types, Object... args) {
        if (owner == null) {
            return null;
        }
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object invoke(Object owner, String name, Class<?>[] types, Object... args) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(owner, args);
            } catch (NoSuchMethodException ignored) {
                // continue
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static boolean invokeVoid(Object owner, String name) {
        if (owner == null) {
            return false;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                method.invoke(owner);
                return true;
            } catch (NoSuchMethodException ignored) {
                // continue
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    static Object field(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                // continue
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
