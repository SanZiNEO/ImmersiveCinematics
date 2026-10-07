package com.immersivecinematics.immersive_cinematics.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.LanguageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文本资源（脚本 i18n）——把 {@code @lang:<key>} 引用解析成客户端当前语言的译文。
 * <p>
 * 文本 = 第三种资源（定稿见 {@code plans/0.3.6/script-i18n.md}）：与图片 / 音频同构，
 * 一个语言一个扁平字典文件 {@code <游戏目录>/immersive_cinematics/resource/lang/<语言>.json}，
 * 客户端按需读取、不走服务器流量、不进资源包。
 * <p>
 * 语法与回退（§2.2 / §3.2）：
 * <ul>
 *   <li>字段值以 {@code @lang:} 开头才查表，其余字符串原样渲染——与普通文案零歧义；</li>
 *   <li>字面文案以 {@code @@lang:} 开头表示转义，输出 {@code @lang:} 开头的原文（§6-6）；</li>
 *   <li>回退链 = 当前语言 → {@code en_us} → 原样显示引用串（不静默，玩家能看出作者漏了 key）；</li>
 *   <li>key 语法 = 一个或多个段（段沿用 {@code meta.id} 的 {@code ^[a-zA-Z0-9_]{1,32}$}），用 {@code .} 连接；
 *       不合法的 key 按"未解析"处理（原样显示引用串）；</li>
 *   <li>语言代码严格小写（§6-2），不合格一律回落 {@code en_us}。</li>
 * </ul>
 * 缓存 = 进程内静态 map（与 {@link TextureLoader} 同款：加载一次、不参与 MC 资源重载，改译文后重启客户端生效，§3.4）。
 * 资源缺失 / 解析失败只记日志、不阻塞播放（§8-1）。
 */
public final class LangResources {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/LangResources");

    /** 引用前缀：字段值以它开头才查表（§2.2） */
    public static final String PREFIX = "@lang:";
    /** 转义前缀：{@code @@lang:xxx} 渲染为字面量 {@code @lang:xxx}（§6-6） */
    public static final String ESCAPED_PREFIX = "@@lang:";
    /** 文本资源子目录名：{@code <resource>/lang/}（§2.1） */
    public static final String LANG_DIR = "lang";
    /** 回退语言：当前语言查不到时兜底（§3.2，与 MC 的 {@code Language.DEFAULT} 一致） */
    public static final String FALLBACK_LANGUAGE = "en_us";

    /** key 语法：段 = {@code meta.id} 的字符约束，段间用 {@code .} 连接（§2.3） */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,32}(\\.[a-zA-Z0-9_]{1,32})*$");
    /** key 总长上限（防病态输入；远大于正常 key） */
    private static final int MAX_KEY_LENGTH = 256;
    /** 语言代码：严格小写（§6-2）；同时天然挡住 {@code ..} / 盘符等路径穿越形态 */
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z0-9_]{2,16}$");

    /** {@code <langDir 绝对路径>|<语言>} → 扁平字典；空 map = 文件缺失或解析失败（§3.4 进程内缓存） */
    private static final Map<String, Map<String, String>> dictionaries = new HashMap<>();

    private LangResources() {}

    // ========== 解析入口 ==========

    /**
     * 客户端绑定解析：用客户端当前语言（{@code LanguageManager.getSelected()}）+ {@code resource/lang/} 查表。
     * <p>
     * 非引用串（普通文案 / 转义串）不会触碰客户端状态，可安全调用。
     *
     * @param raw 脚本字段原值（字幕 {@code text} / {@code meta.description}）
     * @return 译文；未命中 / 非法 key / 无客户端状态时返回原值（引用串原样显示）
     */
    public static String resolve(String raw) {
        if (!isReference(raw)) return literal(raw);
        Minecraft mc;
        try {
            mc = Minecraft.getInstance();
        } catch (Throwable t) {
            return raw; // 无客户端状态（无头 / 服务端进程）：不解析，原样显示
        }
        if (mc == null) return raw;
        return resolve(raw, clientLanguage(mc), ResourcePath.getBasePath().resolve(LANG_DIR));
    }

    /**
     * 纯解析（不依赖客户端状态，便于自检 / 复用）：按给定语言与字典目录解析。
     *
     * @param raw      脚本字段原值
     * @param language 语言代码（不合格一律按 {@link #FALLBACK_LANGUAGE} 处理）
     * @param langDir  字典目录（通常为 {@code resource/lang/}）
     */
    public static String resolve(String raw, String language, Path langDir) {
        if (!isReference(raw)) return literal(raw);
        String key = raw.substring(PREFIX.length()).trim();
        if (langDir == null) return raw;
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH || !KEY_PATTERN.matcher(key).matches()) {
            LOGGER.debug("Invalid lang key, shown as-is: {}", key);
            return raw;
        }
        String value = lookup(key, language, langDir);
        return value != null ? value : raw;
    }

    /** 是否 {@code @lang:<key>} 引用（{@code @@lang:} 转义串不算引用） */
    public static boolean isReference(String raw) {
        return raw != null && raw.startsWith(PREFIX) && !raw.startsWith(ESCAPED_PREFIX);
    }

    // ========== 字典加载 ==========

    /**
     * 读取一个语言文件的扁平字典（key → 译文）。口径同 MC 自己的 lang 文件：根是扁平 JSON 对象、
     * 值只收字符串（非字符串忽略）。
     * <p>
     * 编辑器列举（{@code resource.list}）复用这里，保证与运行时同一套解析口径。
     *
     * @return 字典；文件缺失 / 不是扁平对象 / 解析失败返回空 map
     */
    public static Map<String, String> readDictionary(Path file) {
        if (file == null || !Files.isRegularFile(file)) return Collections.emptyMap();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("Lang file is not a flat JSON object: {}", file);
                return Collections.emptyMap();
            }
            Map<String, String> dict = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    dict.put(entry.getKey(), value.getAsString());
                } else {
                    LOGGER.debug("Lang entry is not a string, ignored: {} ({})", entry.getKey(), file.getFileName());
                }
            }
            return dict;
        } catch (Exception e) {
            LOGGER.warn("Failed to read lang file: {}", file, e);
            return Collections.emptyMap();
        }
    }

    /** 清空字典缓存（自检 / 将来接资源重载用；正常运行不需要） */
    public static void clearCache() {
        dictionaries.clear();
    }

    // ========== 内部实现 ==========

    /** 查表：当前语言 → en_us；都未命中返回 null（调用方回落原样串） */
    private static String lookup(String key, String language, Path langDir) {
        String code = normalizeLanguage(language);
        String value = dictionary(code, langDir).get(key);
        if (value != null) return value;
        if (!FALLBACK_LANGUAGE.equals(code)) {
            value = dictionary(FALLBACK_LANGUAGE, langDir).get(key);
            if (value != null) return value;
        }
        LOGGER.debug("Lang key not found: {} (language {}, dir {})", key, code, langDir);
        return null;
    }

    /** 懒加载 + 缓存某语言的字典；缺失/失败缓存空 map，避免反复读盘刷日志 */
    private static Map<String, String> dictionary(String language, Path langDir) {
        String code = normalizeLanguage(language);
        String cacheKey = langDir.toAbsolutePath().normalize() + "|" + code;
        Map<String, String> cached = dictionaries.get(cacheKey);
        if (cached != null) return cached;

        Path file = langDir.resolve(code + ".json");
        Map<String, String> dict;
        if (Files.isRegularFile(file)) {
            dict = Collections.unmodifiableMap(readDictionary(file));
        } else {
            LOGGER.debug("Lang file not found: {}", file);
            dict = Collections.emptyMap();
        }
        dictionaries.put(cacheKey, dict);
        return dict;
    }

    /** 语言代码归一化：严格小写 + 字符约束（§6-2），不合格一律回落 en_us */
    private static String normalizeLanguage(String language) {
        String code = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return LANGUAGE_PATTERN.matcher(code).matches() ? code : FALLBACK_LANGUAGE;
    }

    /** 客户端当前语言代码；语言管理器不可用（初始化早期）时回落 en_us */
    private static String clientLanguage(Minecraft mc) {
        try {
            LanguageManager manager = mc.getLanguageManager();
            if (manager != null) {
                String selected = manager.getSelected();
                if (selected != null && !selected.isEmpty()) return selected;
            }
        } catch (Throwable ignored) {
            // 语言管理器尚不可用：走 en_us
        }
        return FALLBACK_LANGUAGE;
    }

    /** 非引用串的字面结果：null → 空串；{@code @@lang:} → {@code @lang:}（§6-6 转义） */
    private static String literal(String raw) {
        if (raw == null) return "";
        return raw.startsWith(ESCAPED_PREFIX) ? raw.substring(1) : raw;
    }
}
