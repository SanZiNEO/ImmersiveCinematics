package com.immersivecinematics.immersive_cinematics.script;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adobe / IRIDAS Cube LUT（{@code .cube}）解析器与解析结果 —— 纯 Java，唯一外部依赖 slf4j（合成表降级 WARN）。
 *
 * 格式基线 = Adobe/IRIDAS Cube LUT Specification 1.0，兼容 DaVinci Resolve 的 {@code LUT_1D_INPUT_RANGE} /
 * {@code LUT_3D_INPUT_RANGE}，口径对齐 OpenColorIO / three.js / FFmpeg / libplacebo。指令（大小写不敏感、
 * 各至多一次、必须在数据区之前）：{@code TITLE}、{@code LUT_1D_SIZE} / {@code LUT_3D_SIZE}
 * （{@value #MIN_SIZE}..{@value #MAX_SIZE_1D} / {@value #MAX_SIZE_3D}）、
 * {@code DOMAIN_MIN} / {@code DOMAIN_MAX}、
 * {@code LUT_*_INPUT_RANGE}（优先于 DOMAIN）、{@code #} 注释；未知指令报错。
 * 数据区：每行 3 个浮点数，红变化最快（{@link #index3D}）；值不钳制、不归一化（可为负 / 大于 1 / 超出 DOMAIN）。
 * 1D + 3D 组合文件中 1D 数据在前、3D 在后，应用顺序先 1D 后 3D；DOMAIN 只描述输入域、不改数据值，
 * 查表坐标 = {@code (v - min) / (max - min) × (size - 1)}（先钳到 [0,1]，结果即采样点下标空间）。
 * 容错「宽进严出」：空白 / BOM / 科学计数法 / 省略前导零 / 行尾多余字符容忍；条数严格等于声明值，NaN / Infinity / 十六进制 / 后缀 {@code f} 拒绝。
 *
 * 合成表（{@link #composed3D()}）：S×S×S，S = m + 1；
 * m0 = ceil(min(最细源格距的倒数, {@value #COMPOSED_MAX_SIZE} - 1))，
 * 最细 = 各通道 {@code (size3D - 1) / DOMAIN 跨度} 取大；再从 m0 向上搜到 {@code min(8·m0, 上限 - 1)}，取首个
 * 「源表折点平面全部落在合成网格上」的步数（格点对齐，避免插值走样）。1D 段令 {@code m = max(m, min(size1D - 1, 上限 - 1))}，
 * size1D 超上限时按上限重采样并记 WARN（误差有上界）。纯 1D LUT（无 3D 段）的合成表 = 对角表：每个格点 = 逐通道 1D 曲线作用在单位网格上。
 * 不可变：{@link #data1D()} / {@link #data3D()} / {@link Composed3D#data()} 返回内部只读数组，调用方不得修改。
 */
public final class CubeLut {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/CubeLut");

    /** 1D / 3D LUT 的最小尺寸（至少 2 个采样点）。 */
    public static final int MIN_SIZE = 2;

    /** 3D LUT 尺寸上限（对齐 FFmpeg {@code MAX_LEVEL}；真实 LUT 远小于此——Resolve 33/65、通用 64）。 */
    public static final int MAX_SIZE_3D = 256;

    /** 1D LUT 尺寸上限（FFmpeg {@code MAX_1D_LEVEL} 与 OpenColorIO {@code Max1DLUTLength} 同量级）。 */
    public static final int MAX_SIZE_1D = 65536;

    /**
     * {@link #composed3D()} 重采样路径的网格边长上限 S。
     * 取 64：S³ 个采样点在 16 位浮点纹理里约 2 MB，再大对 1D shaper / DOMAIN 归一化这类低曲率变换
     * 无可见收益（1D 段误差随采样数二阶 S⁻² 收敛）；纯 3D 单位域走复用路径，不受此上限影响。
     */
    public static final int COMPOSED_MAX_SIZE = 64;

    /**
     * 普通浮点字面量：接受 {@code .5} / {@code 1.} / {@code 1e-3} / {@code +1.5}，
     * 拒绝 NaN / Infinity / 0x / 后缀 f。
     */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");

    /** 严格整数（尺寸参数）：不接受 {@code 2.0} / {@code 0x2} / 空白。 */
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    private final String title;
    private final int size1D;
    private final int size3D;
    private final float[] data1D;
    private final float[] data3D;
    private final float[] domainMin1D;
    private final float[] domainMax1D;
    private final float[] domainMin3D;
    private final float[] domainMax3D;

    /** {@link #composed3D()} 的惰性缓存（不可变结果：重复调用返回同一实例）。 */
    private volatile Composed3D composed3D;

    private CubeLut(String title, int size1D, int size3D,
                    float[] data1D, float[] data3D,
                    float[] domainMin1D, float[] domainMax1D,
                    float[] domainMin3D, float[] domainMax3D) {
        this.title = title;
        this.size1D = size1D;
        this.size3D = size3D;
        this.data1D = data1D;
        this.data3D = data3D;
        this.domainMin1D = domainMin1D;
        this.domainMax1D = domainMax1D;
        this.domainMin3D = domainMin3D;
        this.domainMax3D = domainMax3D;
    }

    // ==================== 解析入口 ====================

    /** 解析一个 .cube 文件；错误信息中的文件名取 {@code file.getFileName()}。 */
    public static CubeLut parse(Path file) throws CubeLutException {
        String name = file == null || file.getFileName() == null ? String.valueOf(file) : file.getFileName().toString();
        return parse(file, name);
    }

    /**
     * 解析一个 .cube 文件。
     *
     * @param file        文件路径
     * @param displayName 错误信息中使用的文件名（可含相对路径）
     * @throws CubeLutException 读取失败或格式非法（带文件名 + 行号 + 原因）
     */
    public static CubeLut parse(Path file, String displayName) throws CubeLutException {
        if (file == null) {
            throw new CubeLutException(displayName, 0, "文件路径为 null");
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new CubeLutException(displayName, 0, "读取失败: " + e.getMessage(), e);
        }
        return parseLines(lines, displayName);
    }

    /** 解析一段 .cube 文本（测试 / 编辑器粘贴用）。 */
    public static CubeLut parseText(String text, String displayName) throws CubeLutException {
        if (text == null) {
            throw new CubeLutException(displayName, 0, "内容为 null");
        }
        return parseLines(Arrays.asList(text.split("\\R", -1)), displayName);
    }

    // ==================== 解析核心 ====================

    private static CubeLut parseLines(List<String> lines, String name) throws CubeLutException {
        String title = null;
        int size1D = 0;
        int size3D = 0;
        boolean seenTitle = false;
        boolean seen1D = false;
        boolean seen3D = false;
        boolean seenDomainMin = false;
        boolean seenDomainMax = false;
        boolean seenRange1D = false;
        boolean seenRange3D = false;
        float[] domainMin = {0f, 0f, 0f};
        float[] domainMax = {1f, 1f, 1f};
        float[] range1D = null;
        float[] range3D = null;
        FloatBuffer data = new FloatBuffer();
        boolean dataStarted = false;
        boolean anyContent = false;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String raw = lines.get(i);
            if (lineNo == 1 && !raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                raw = raw.substring(1); // 容忍 UTF-8 BOM
            }
            String line = raw.strip();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            anyContent = true;

            if (Character.isLetter(line.charAt(0))) {
                if (dataStarted) {
                    throw new CubeLutException(name, lineNo,
                            "指令必须位于数据区之前，却出现在数据之后: " + firstToken(line));
                }
                String[] tok = line.split("\\s+");
                String kw = tok[0].toUpperCase(Locale.ROOT);
                switch (kw) {
                    case "TITLE":
                        if (seenTitle) throw duplicate(name, lineNo, kw);
                        seenTitle = true;
                        title = parseTitle(line.substring(tok[0].length()));
                        break;
                    case "LUT_1D_SIZE":
                        if (seen1D) throw duplicate(name, lineNo, kw);
                        seen1D = true;
                        size1D = parseSize(name, lineNo, tok, kw, MAX_SIZE_1D);
                        break;
                    case "LUT_3D_SIZE":
                        if (seen3D) throw duplicate(name, lineNo, kw);
                        seen3D = true;
                        size3D = parseSize(name, lineNo, tok, kw, MAX_SIZE_3D);
                        break;
                    case "DOMAIN_MIN":
                        if (seenDomainMin) throw duplicate(name, lineNo, kw);
                        seenDomainMin = true;
                        domainMin = parseTriple(name, lineNo, tok, kw);
                        break;
                    case "DOMAIN_MAX":
                        if (seenDomainMax) throw duplicate(name, lineNo, kw);
                        seenDomainMax = true;
                        domainMax = parseTriple(name, lineNo, tok, kw);
                        break;
                    case "LUT_1D_INPUT_RANGE":
                        if (seenRange1D) throw duplicate(name, lineNo, kw);
                        seenRange1D = true;
                        range1D = parseRange(name, lineNo, tok, kw);
                        break;
                    case "LUT_3D_INPUT_RANGE":
                        if (seenRange3D) throw duplicate(name, lineNo, kw);
                        seenRange3D = true;
                        range3D = parseRange(name, lineNo, tok, kw);
                        break;
                    default:
                        throw new CubeLutException(name, lineNo,
                                "未知指令: " + tok[0] + "（数据行必须以数值开头）");
                }
            } else {
                dataStarted = true;
                parseDataLine(name, lineNo, line, data);
            }
        }

        if (!anyContent) {
            throw new CubeLutException(name, 0, "空文件");
        }
        if (size1D == 0 && size3D == 0) {
            throw new CubeLutException(name, 0, "缺少 LUT_1D_SIZE / LUT_3D_SIZE 尺寸声明");
        }
        validateDomain(name, "DOMAIN_MIN/DOMAIN_MAX", domainMin, domainMax);
        if (range1D != null) {
            validateRange(name, "LUT_1D_INPUT_RANGE", range1D);
        }
        if (range3D != null) {
            validateRange(name, "LUT_3D_INPUT_RANGE", range3D);
        }

        long entries1D = size1D;
        long entries3D = size3D == 0 ? 0L : (long) size3D * size3D * size3D;
        long expectedValues = (entries1D + entries3D) * 3;
        if (data.size() != expectedValues) {
            throw new CubeLutException(name, 0, String.format(Locale.ROOT,
                    "数据条数不符: 期望 %d 条（1D %d + 3D %d），实际 %d 条",
                    entries1D + entries3D, entries1D, entries3D, data.size() / 3));
        }

        float[] all = data.toArray();
        int split1D = size1D * 3;
        float[] data1D = size1D == 0 ? null : Arrays.copyOfRange(all, 0, split1D);
        float[] data3D = size3D == 0 ? null : Arrays.copyOfRange(all, split1D, all.length);

        // Resolve 的 *_INPUT_RANGE 优先于 DOMAIN_*（后者是全局缺省）
        float[] min1D = range1D != null ? new float[]{range1D[0], range1D[0], range1D[0]} : domainMin.clone();
        float[] max1D = range1D != null ? new float[]{range1D[1], range1D[1], range1D[1]} : domainMax.clone();
        float[] min3D = range3D != null ? new float[]{range3D[0], range3D[0], range3D[0]} : domainMin.clone();
        float[] max3D = range3D != null ? new float[]{range3D[1], range3D[1], range3D[1]} : domainMax.clone();

        return new CubeLut(title, size1D, size3D, data1D, data3D, min1D, max1D, min3D, max3D);
    }

    private static void parseDataLine(String name, int lineNo, String line, FloatBuffer out)
            throws CubeLutException {
        String[] tok = line.split("\\s+");
        if (tok.length < 3) {
            throw new CubeLutException(name, lineNo, "数据行需要 3 个数值，实际 " + tok.length + " 个: " + line);
        }
        // 前两个值必须是完整数字；第三个值之后的行尾多余字符（含逗号）忽略（FFmpeg sscanf 口径）
        out.add(dataValue(name, lineNo, tok[0], 1, true));
        out.add(dataValue(name, lineNo, tok[1], 2, true));
        out.add(dataValue(name, lineNo, tok[2], 3, false));
    }

    private static float dataValue(String name, int lineNo, String token, int ordinal, boolean strict)
            throws CubeLutException {
        Matcher m = NUMBER.matcher(token);
        String literal = null;
        if (strict ? m.matches() : m.lookingAt()) {
            literal = strict ? token : m.group();
        }
        Float value = literal == null ? null : parseFloat(literal);
        if (value == null) {
            throw new CubeLutException(name, lineNo,
                    "数据行第 " + ordinal + " 个值不是有效数值: " + token);
        }
        return value;
    }

    private static int parseSize(String name, int lineNo, String[] tok, String kw, int max)
            throws CubeLutException {
        if (tok.length != 2) {
            throw new CubeLutException(name, lineNo,
                    kw + " 需要 1 个整数参数，实际 " + (tok.length - 1) + " 个");
        }
        if (!INTEGER.matcher(tok[1]).matches()) {
            throw new CubeLutException(name, lineNo, kw + " 尺寸不是整数: " + tok[1]);
        }
        int n;
        try {
            n = Integer.parseInt(tok[1]);
        } catch (NumberFormatException e) {
            throw new CubeLutException(name, lineNo, kw + " 尺寸超出整数范围: " + tok[1]);
        }
        if (n < MIN_SIZE || n > max) {
            throw new CubeLutException(name, lineNo,
                    kw + " 尺寸越界: " + n + "（允许 " + MIN_SIZE + ".." + max + "）");
        }
        return n;
    }

    private static float[] parseTriple(String name, int lineNo, String[] tok, String kw)
            throws CubeLutException {
        if (tok.length != 4) {
            throw new CubeLutException(name, lineNo,
                    kw + " 需要 3 个数值参数，实际 " + (tok.length - 1) + " 个");
        }
        float[] v = new float[3];
        for (int c = 0; c < 3; c++) {
            Float f = strictNumber(tok[c + 1]);
            if (f == null) {
                throw new CubeLutException(name, lineNo,
                        kw + " 第 " + (c + 1) + " 个值不是有效数值: " + tok[c + 1]);
            }
            v[c] = f;
        }
        return v;
    }

    private static float[] parseRange(String name, int lineNo, String[] tok, String kw)
            throws CubeLutException {
        if (tok.length != 3) {
            throw new CubeLutException(name, lineNo,
                    kw + " 需要 2 个数值参数（min max），实际 " + (tok.length - 1) + " 个");
        }
        Float min = strictNumber(tok[1]);
        Float max = strictNumber(tok[2]);
        if (min == null || max == null) {
            throw new CubeLutException(name, lineNo,
                    kw + " 参数不是有效数值: " + tok[1] + " " + tok[2]);
        }
        return new float[]{min, max};
    }

    private static void validateDomain(String name, String what, float[] min, float[] max)
            throws CubeLutException {
        String[] channel = {"R", "G", "B"};
        for (int c = 0; c < 3; c++) {
            if (!(max[c] > min[c])) {
                throw new CubeLutException(name, 0, what + " 下界必须小于上界（通道 " + channel[c]
                        + ": min=" + min[c] + ", max=" + max[c] + "）");
            }
        }
    }

    private static void validateRange(String name, String kw, float[] range) throws CubeLutException {
        if (!(range[1] > range[0])) {
            throw new CubeLutException(name, 0,
                    kw + " 下界必须小于上界（min=" + range[0] + ", max=" + range[1] + "）");
        }
    }

    private static CubeLutException duplicate(String name, int lineNo, String kw) {
        return new CubeLutException(name, lineNo, "指令重复: " + kw);
    }

    private static String parseTitle(String rest) {
        String t = rest.strip();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String firstToken(String line) {
        int sp = 0;
        while (sp < line.length() && !Character.isWhitespace(line.charAt(sp))) {
            sp++;
        }
        return line.substring(0, sp);
    }

    private static Float strictNumber(String token) {
        return NUMBER.matcher(token).matches() ? parseFloat(token) : null;
    }

    private static Float parseFloat(String literal) {
        try {
            float v = Float.parseFloat(literal);
            return Float.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 访问器 ====================

    /** TITLE 文本；未声明返回 {@code null}。 */
    public String title() {
        return title;
    }

    /** 是否含 1D 部分。 */
    public boolean has1D() {
        return size1D > 0;
    }

    /** 是否含 3D 部分。 */
    public boolean has3D() {
        return size3D > 0;
    }

    /** 1D 尺寸；无 1D 部分返回 0。 */
    public int size1D() {
        return size1D;
    }

    /** 3D 尺寸；无 3D 部分返回 0。 */
    public int size3D() {
        return size3D;
    }

    /** 3D 采样点总数（{@code size3D³}）；无 3D 部分返回 0。 */
    public int entries3D() {
        return size3D == 0 ? 0 : size3D * size3D * size3D;
    }

    /**
     * 1D 数据（RGB 交错，长度 {@code size1D * 3}，第 i 个采样点 = {@code data[3i..3i+2]}）；
     * 无 1D 部分返回 {@code null}。返回内部数组，调用方不得修改。
     */
    public float[] data1D() {
        return data1D;
    }

    /**
     * 3D 数据（RGB 交错，长度 {@code size3D³ * 3}，红最快，见 {@link #index3D}）；
     * 无 3D 部分返回 {@code null}。返回内部数组，调用方不得修改。
     */
    public float[] data3D() {
        return data3D;
    }

    /** 1D 输入域下界（长度 3）；无 1D 部分时为 DOMAIN 缺省值。返回内部数组，调用方不得修改。 */
    public float[] domainMin1D() {
        return domainMin1D;
    }

    /** 1D 输入域上界（长度 3）。返回内部数组，调用方不得修改。 */
    public float[] domainMax1D() {
        return domainMax1D;
    }

    /** 3D 输入域下界（长度 3）。返回内部数组，调用方不得修改。 */
    public float[] domainMin3D() {
        return domainMin3D;
    }

    /** 3D 输入域上界（长度 3）。返回内部数组，调用方不得修改。 */
    public float[] domainMax3D() {
        return domainMax3D;
    }

    // ==================== 合成 3D 表 ====================

    /**
     * 合成后的单张 3D 表：1D → 3D 两段与各自 DOMAIN 归一化烘焙进一张 {@code S×S×S} 表（S 规则见类 javadoc），
     * 采样坐标 {@code x ∈ [0,1]³} 直接对应网格，红最快、值不钳制；纯 3D 单位域直接复用原表（逐位不变）。
     * 惰性计算并缓存：同一实例重复调用返回同一 {@link Composed3D}（渲染侧按引用比较，换 LUT 才重传）。
     * 单表 = 单采样器：1D 与 3D 各占一个会到 13 个，而 MC 1.20.1 只有 12 个纹理单元，第 13 个在
     * {@code ShaderInstance.apply()} 越界崩溃（采样器压缩见 {@code LutBindGuard}）。
     */
    public Composed3D composed3D() {
        Composed3D result = composed3D;
        if (result == null) {
            synchronized (this) {
                result = composed3D;
                if (result == null) {
                    result = compose3D();
                    composed3D = result;
                }
            }
        }
        return result;
    }

    /** 构建合成表（纯函数；调用方负责缓存，见 {@link #composed3D()}）。 */
    private Composed3D compose3D() {
        if (size3D > 0 && size1D == 0
                && isUnitDomain(domainMin3D, domainMax3D)) {
            return new Composed3D(size3D, data3D, false);   // 复用原表：查表坐标逐位相同
        }
        int size = composedSteps() + 1;
        float[] out = new float[size * size * size * 3];
        float[] scratch = new float[3];
        for (int b = 0; b < size; b++) {
            for (int g = 0; g < size; g++) {
                for (int r = 0; r < size; r++) {
                    evaluate(grid(r, size), grid(g, size), grid(b, size), scratch);
                    int idx = index3D(size, r, g, b) * 3;
                    out[idx] = scratch[0];
                    out[idx + 1] = scratch[1];
                    out[idx + 2] = scratch[2];
                }
            }
        }
        return new Composed3D(size, out, true);
    }

    /** 合成网格步数 {@code m = S - 1}：3D 源格距与 1D 长度取大，再向上做格点对齐搜索，均封顶 {@value #COMPOSED_MAX_SIZE} - 1。 */
    private int composedSteps() {
        int m = 1;
        if (size3D > 0) {
            double finest = 0.0;
            for (int c = 0; c < 3; c++) {
                finest = Math.max(finest, (size3D - 1) / (double) (domainMax3D[c] - domainMin3D[c]));
            }
            m = Math.max(1, (int) Math.ceil(Math.min(finest, COMPOSED_MAX_SIZE - 1.0)));
            // 格点对齐搜索：对齐 = 源表的折点平面都落在合成网格上（避免重采样走样）
            int limit = Math.min(m * 8, COMPOSED_MAX_SIZE - 1);
            for (int candidate = m; candidate <= limit; candidate++) {
                if (gridAligned(candidate)) {
                    m = candidate;
                    break;
                }
            }
        }
        if (size1D > 0) {
            if (size1D > COMPOSED_MAX_SIZE) {
                // 1D 段被上限截断（如 1024 / 4096 点 shaper 只按 64 点重采样）：精度损失有上界，不阻断
                LOGGER.warn("1D LUT 尺寸 {} 超过合成表上限 {}：1D 段按 {} 点重采样（有精度损失），"
                        + "见 CubeLut#composed3D 的尺寸规则", size1D, COMPOSED_MAX_SIZE, COMPOSED_MAX_SIZE);
            }
            m = Math.max(m, Math.min(size1D - 1, COMPOSED_MAX_SIZE - 1));
        }
        return m;
    }

    /** 步数 {@code m} 下，源表的格点平面是否都落在合成网格上（浮点域值只能按容差判整）。 */
    private boolean gridAligned(int m) {
        for (int c = 0; c < 3; c++) {
            double span = domainMax3D[c] - domainMin3D[c];
            if (!nearInteger(domainMin3D[c] * m) || !nearInteger(span * m / (size3D - 1))) {
                return false;
            }
        }
        return true;
    }

    /** 是否（按 {@code 1e-4} 容差）为整数。 */
    private static boolean nearInteger(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-4;
    }

    /** 网格坐标：{@code [0,1]} 上第 i 个格点（{@code i / (size-1)}）。 */
    private static float grid(int i, int size) {
        return size <= 1 ? 0.0F : i / (float) (size - 1);
    }

    /** 输入域是否为单位域（{@code [0,1]³}，逐通道精确比较；解析期已保证 {@code max > min}）。 */
    private static boolean isUnitDomain(float[] min, float[] max) {
        return min[0] == 0.0F && min[1] == 0.0F && min[2] == 0.0F
                && max[0] == 1.0F && max[1] == 1.0F && max[2] == 1.0F;
    }

    /**
     * 完整变换：1D 段（先）→ 3D 段（后），各段按自己的 DOMAIN 归一化。
     * 输出写进 {@code out}（长度 3），避免逐格点分配。
     */
    private void evaluate(float r, float g, float b, float[] out) {
        float v0 = r;
        float v1 = g;
        float v2 = b;
        if (size1D > 0) {
            v0 = sample1D(0, v0);
            v1 = sample1D(1, v1);
            v2 = sample1D(2, v2);
        }
        if (size3D > 0) {
            sample3D(v0, v1, v2, out);
        } else {
            out[0] = v0;
            out[1] = v1;
            out[2] = v2;
        }
    }

    /** 1D 段（逐通道各自的曲线）：DOMAIN 归一化 + 相邻采样点线性插值（与着色器纹理 LINEAR 同口径）。 */
    private float sample1D(int channel, float v) {
        float t = normalize(v, domainMin1D[channel], domainMax1D[channel]) * (size1D - 1);
        int k = (int) Math.floor(t);
        if (k >= size1D - 1) {
            return data1D[(size1D - 1) * 3 + channel];
        }
        float f = t - k;
        return data1D[k * 3 + channel] * (1.0F - f) + data1D[(k + 1) * 3 + channel] * f;
    }

    /**
     * 3D 段：DOMAIN 归一化 + 四面体（tetrahedral）插值 ——
     * 与 {@code ic_color_adjust.fsh} 的 lut3dLookup 逐分支一致。
     */
    private void sample3D(float r, float g, float b, float[] out) {
        float tr = normalize(r, domainMin3D[0], domainMax3D[0]) * (size3D - 1);
        float tg = normalize(g, domainMin3D[1], domainMax3D[1]) * (size3D - 1);
        float tb = normalize(b, domainMin3D[2], domainMax3D[2]) * (size3D - 1);
        int pr = (int) Math.floor(tr);
        int pg = (int) Math.floor(tg);
        int pb = (int) Math.floor(tb);
        int nr = Math.min(pr + 1, size3D - 1);
        int ng = Math.min(pg + 1, size3D - 1);
        int nb = Math.min(pb + 1, size3D - 1);
        float dr = tr - pr;
        float dg = tg - pg;
        float db = tb - pb;
        int i000 = index3D(size3D, pr, pg, pb) * 3;
        int i111 = index3D(size3D, nr, ng, nb) * 3;
        int i1;
        int i2;
        float w0;
        float w1;
        float w2;
        float w3;
        if (dr > dg) {
            if (dg > db) {                       // r > g > b
                i1 = index3D(size3D, nr, pg, pb) * 3;
                i2 = index3D(size3D, nr, ng, pb) * 3;
                w0 = 1.0F - dr; w1 = dr - dg; w2 = dg - db; w3 = db;
            } else if (dr > db) {                // r > b >= g
                i1 = index3D(size3D, nr, pg, pb) * 3;
                i2 = index3D(size3D, nr, pg, nb) * 3;
                w0 = 1.0F - dr; w1 = dr - db; w2 = db - dg; w3 = dg;
            } else {                             // b >= r > g
                i1 = index3D(size3D, pr, pg, nb) * 3;
                i2 = index3D(size3D, nr, pg, nb) * 3;
                w0 = 1.0F - db; w1 = db - dr; w2 = dr - dg; w3 = dg;
            }
        } else {
            if (db > dg) {                       // b > g >= r
                i1 = index3D(size3D, pr, pg, nb) * 3;
                i2 = index3D(size3D, pr, ng, nb) * 3;
                w0 = 1.0F - db; w1 = db - dg; w2 = dg - dr; w3 = dr;
            } else if (db > dr) {                // g >= b > r
                i1 = index3D(size3D, pr, ng, pb) * 3;
                i2 = index3D(size3D, pr, ng, nb) * 3;
                w0 = 1.0F - dg; w1 = dg - db; w2 = db - dr; w3 = dr;
            } else {                             // g >= r >= b
                i1 = index3D(size3D, pr, ng, pb) * 3;
                i2 = index3D(size3D, nr, ng, pb) * 3;
                w0 = 1.0F - dg; w1 = dg - dr; w2 = dr - db; w3 = db;
            }
        }
        for (int c = 0; c < 3; c++) {
            out[c] = w0 * data3D[i000 + c] + w1 * data3D[i1 + c]
                    + w2 * data3D[i2 + c] + w3 * data3D[i111 + c];
        }
    }

    /** DOMAIN 归一化：{@code clamp((v - min) / (max - min), 0, 1)}（域外钳制，与着色器同口径）。 */
    private static float normalize(float v, float min, float max) {
        float u = (v - min) / (max - min);
        if (u < 0.0F) {
            return 0.0F;
        }
        return u > 1.0F ? 1.0F : u;
    }

    /**
     * {@link CubeLut#composed3D()} 的结果：输入域已归一化到 {@code [0,1]³} 的不可变 3D 表。
     *
     * @param size      网格边长 S（表为 {@code S×S×S}）
     * @param data      表数据（RGB 交错、红最快、长度 {@code S³×3}；{@code resampled = false} 时即原表）
     * @param resampled 是否重采样过（{@code false} = 直接复用原 3D 表，输入域本就是 {@code [0,1]³}）
     */
    public record Composed3D(int size, float[] data, boolean resampled) {

        public Composed3D {
            if (size < MIN_SIZE) {
                throw new IllegalArgumentException("合成表尺寸越界: " + size);
            }
            if (data == null || data.length != size * size * size * 3) {
                throw new IllegalArgumentException("合成表数据长度不符: size=" + size
                        + ", length=" + (data == null ? -1 : data.length));
            }
        }
    }

    /**
     * 3D 数据索引（红最快）：{@code (b * size + g) * size + r}。
     *
     * @param size 3D 尺寸
     * @param r    红通道格点索引（0..size-1，变化最快）
     * @param g    绿通道格点索引
     * @param b    蓝通道格点索引（变化最慢）
     * @return 该格点在 {@link #data3D()} 中的采样点下标（乘 3 得分量下标）
     */
    public static int index3D(int size, int r, int g, int b) {
        return (b * size + g) * size + r;
    }

    /** {@link #index3D(int, int, int, int)} 的实例版本（用本 LUT 的 {@link #size3D()}）。 */
    public int index3D(int r, int g, int b) {
        return index3D(size3D, r, g, b);
    }

    @Override
    public String toString() {
        return "CubeLut{title=" + title + ", size1D=" + size1D + ", size3D=" + size3D
                + ", domain1D=[" + domainMin1D[0] + ".." + domainMax1D[0] + "]"
                + ", domain3D=[" + domainMin3D[0] + ".." + domainMax3D[0] + "]}";
    }

    // ==================== 内部工具 ====================

    /** 解析失败异常：携带文件名 + 行号（0 = 与具体行无关）+ 原因。 */
    public static class CubeLutException extends Exception {

        private final String file;
        private final int line;

        public CubeLutException(String file, int line, String reason) {
            super(file + (line > 0 ? ":" + line : "") + ": " + reason);
            this.file = file;
            this.line = line;
        }

        public CubeLutException(String file, int line, String reason, Throwable cause) {
            super(file + (line > 0 ? ":" + line : "") + ": " + reason, cause);
            this.file = file;
            this.line = line;
        }

        /** 出错文件（展示名）。 */
        public String getFile() {
            return file;
        }

        /** 出错行号；0 表示与具体行无关。 */
        public int getLine() {
            return line;
        }
    }

    /** 增长型 float 缓冲，避免解析期装箱。 */
    private static final class FloatBuffer {
        private float[] array = new float[3 * 64];
        private int size;

        void add(float value) {
            if (size == array.length) {
                array = Arrays.copyOf(array, array.length * 2);
            }
            array[size++] = value;
        }

        int size() {
            return size;
        }

        float[] toArray() {
            return size == array.length ? array : Arrays.copyOf(array, size);
        }
    }
}
