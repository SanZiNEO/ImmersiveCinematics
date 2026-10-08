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

/**
 * Adobe / IRIDAS Cube LUT（{@code .cube}）解析器与解析结果 —— 纯 Java、零外部依赖、无 Minecraft 依赖。
 *
 * <h2>格式基线</h2>
 * 以 Adobe/IRIDAS Cube LUT Specification 1.0 为基线，并兼容 <b>DaVinci Resolve</b> 导出的变体
 * （{@code LUT_1D_INPUT_RANGE} / {@code LUT_3D_INPUT_RANGE}）。行为对齐 OpenColorIO（Iridas / Resolve
 * 两个解析器）、three.js {@code LUTCubeLoader}、FFmpeg {@code vf_lut3d}、libplacebo 四家实现的公共口径，
 * 详见 {@code example/lut-reference/README.md}。
 *
 * <h2>接受的指令（大小写不敏感，各至多一次，必须位于数据区之前）</h2>
 * <ul>
 *   <li>{@code TITLE "..."} —— 带引号文本（无引号时取整行剩余内容），仅保存不参与解析；</li>
 *   <li>{@code LUT_1D_SIZE N} —— 1D 尺寸，{@value #MIN_SIZE}..{@value #MAX_SIZE_1D}；</li>
 *   <li>{@code LUT_3D_SIZE N} —— 3D 尺寸，{@value #MIN_SIZE}..{@value #MAX_SIZE_3D}；</li>
 *   <li>{@code DOMAIN_MIN r g b} / {@code DOMAIN_MAX r g b} —— 输入域（逐通道），默认 {@code 0 0 0} / {@code 1 1 1}；</li>
 *   <li>{@code LUT_1D_INPUT_RANGE min max} / {@code LUT_3D_INPUT_RANGE min max} —— Resolve 写法，
 *       语义 = 对应 LUT 的输入域（三通道同值），优先级高于 {@code DOMAIN_*}；</li>
 *   <li>{@code #} 注释行 —— 可出现在任意位置（含数据区中间）。</li>
 * </ul>
 * 未知指令一律报错（宁可拒绝也不误读文件）。
 *
 * <h2>数据区</h2>
 * 每行 3 个空白分隔的浮点数，<b>红变化最快</b>：{@code data[(b * size + g) * size + r]}（见 {@link #index3D}）。
 * 数据值<b>不受限</b>（可 &lt;0、&gt;1，也可超出 DOMAIN），解析阶段不钳制、不归一化。
 * 1D + 3D 组合文件（Resolve shaper 形态）中 <b>1D 数据在前、3D 数据在后</b>，
 * 应用顺序为 <b>先 1D 后 3D</b>（{@link #has1D()} / {@link #has3D()} 的调用方需遵守）。
 *
 * <h2>容错口径（"宽进严出"）</h2>
 * <ul>
 *   <li>前导/多余空白、CR/LF/CRLF 混用、UTF-8 BOM：全部容忍；</li>
 *   <li>科学计数法（{@code 1e-3} / {@code 1E3}）、省略前导零（{@code .5}）、显式正号：全部接受；</li>
 *   <li>行尾多余字符（含行尾逗号，FFmpeg {@code sscanf("%f %f %f")} 口径）：前两个值必须是完整数字，
 *       第三个值之后的任何内容一律忽略（{@code 0.1 0.2 0.3,} 与 {@code 0.1 0.2 0.3,4} 都读成 {@code 0.1 0.2 0.3}）；</li>
 *   <li>数据条数<b>严格</b>校验：3D = {@code N³} 条、1D = {@code N} 条，组合文件为两者之和（OCIO 口径）；</li>
 *   <li>NaN / Infinity / 十六进制 / 后缀 {@code f} 等非普通浮点字面量：拒绝。</li>
 * </ul>
 *
 * <h2>DOMAIN 语义</h2>
 * DOMAIN 描述的是<b>输入域</b>，不改变数据值。查找时应做 {@code (v - min) / (max - min)} 再乘以
 * {@code size - 1}（不做 ffmpeg 那种忽略 {@code min} 的简化）。本类提供两套访问：
 * <ul>
 *   <li>{@code domainMin1D} / {@code domainMax1D} / {@code domainMin3D} / {@code domainMax3D}
 *       原始值 —— 归一化留给调用方；</li>
 *   <li>{@link #composed3D()} —— 把 1D shaper + 3D 两段与 DOMAIN 归一化一次性烘焙成<b>单张</b>
 *       输入域 {@code [0,1]³} 的 3D 表（渲染侧只吃这一张表，见该方法的等价性说明）。</li>
 * </ul>
 *
 * <h2>不可变</h2>
 * 解析结果不可变；{@link #data1D()} / {@link #data3D()} 与 {@link Composed3D#data()} 返回内部数组
 * （只读契约），调用方不得修改。
 */
public final class CubeLut {

    /** 1D / 3D LUT 的最小尺寸（至少 2 个采样点）。 */
    public static final int MIN_SIZE = 2;

    /**
     * 3D LUT 尺寸上限。
     * <p>取 FFmpeg 的 {@code MAX_LEVEL}（256）；README §5.5 建议的 129（OpenColorIO {@code Max3DLUTLength}）
     * 更保守，本实现按任务给出的越界边界（{@code >256} 判非法）取 256。真实 LUT 远小于此（Resolve 33/65、通用 64）。</p>
     */
    public static final int MAX_SIZE_3D = 256;

    /** 1D LUT 尺寸上限（FFmpeg {@code MAX_1D_LEVEL} 与 OpenColorIO {@code Max1DLUTLength} 同量级）。 */
    public static final int MAX_SIZE_1D = 65536;

    /**
     * {@link #composed3D()} 重采样路径的网格边长上限 S。
     * <p>上限取 64：S³ 个采样点在 16 位浮点纹理里是 2 MB（CPU 侧构建用 3 MB 临时 float），
     * 再往上对「1D shaper / DOMAIN 归一化」这类低曲率变换没有可见收益 —— 1D 段的合成误差随
     * S⁻² 收敛、3D 段在对齐搜索命中时逐位一致（见 {@link #composed3D()} 的尺寸规则）。
     * 纯 3D 且输入域 = {@code [0,1]³} 的常见文件（Resolve 33³ / 65³ 导出）走复用路径，
     * 不受此上限影响。</p>
     */
    public static final int COMPOSED_MAX_SIZE = 64;

    /** 普通浮点字面量：接受 {@code .5} / {@code 1.} / {@code 1e-3} / {@code +1.5}，拒绝 NaN / Infinity / 0x / 后缀 f。 */
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
     * 3D 数据（RGB 交错，长度 {@code size3D³ * 3}，<b>红最快</b>，见 {@link #index3D}）；
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
     * <b>合成后的单张 3D 表</b>：把「1D shaper → 3D」两段与两段各自的 DOMAIN 输入域归一化全部
     * 烘焙进一张 {@code S×S×S} 表 —— 采样坐标 {@code x ∈ [0,1]³} <b>直接对应网格</b>
     * （免着色器归一化），红最快、值与 {@link #data3D()} 同口径（可超出 {@code [0,1]}，不钳制）。
     *
     * <p>为什么：渲染侧一张表 = 一个采样器。此前 1D 与 3D 各占一个采样器，加上画面与十条曲线共
     * 13 个采样器，而 MC 1.20.1 的 {@code GlStateManager} 只维护 12 个纹理单元（0~11）——
     * 第 13 个（单元 12）在 {@code ShaderInstance.apply()} 里越界崩溃。合成后只剩 {@code Lut3D}
     * 一个（单元 11，总数 12 顶格）。</p>
     *
     * <h3>三种形态</h3>
     * <ol>
     *   <li><b>纯 3D 且输入域 = {@code [0,1]³}</b>：<b>直接复用</b> {@link #data3D()} ——
     *       {@code S = size3D}、零重采样；此时 {@code x·(S-1)} 与旧的
     *       {@code (v-min)/(max-min)·(size-1)} 逐位相同，画面逐位不变；</li>
     *   <li><b>1D + 3D 组合</b>（Resolve shaper 形态）：{@code composed(v) = lut3D(lut1D(v))}，
     *       在与着色器<b>相同</b>的插值口径下（1D = 相邻采样点线性插值、3D = 四面体）于 {@code [0,1]³}
     *       网格上重采样成新表（<b>1D 先、输出喂 3D</b>）；</li>
     *   <li><b>纯 1D</b>：对角表 {@code composed(r,g,b) = (1D_r(r), 1D_g(g), 1D_b(b))}。
     *       四面体插值在任一分支下给某个输出分量的权重只落在该分量自己的两个网格层上
     *       （例如 {@code out.r = (1-d.r)·T[p.r] + d.r·T[n.r]}，与 g / b 无关），
     *       所以该表逐通道等价于「1D 曲线在 S 个点上采样 + 线性插值」。</li>
     * </ol>
     *
     * <h3>尺寸 {@code S}</h3>
     * {@code S = 1 + m}，其中步数 {@code m} 按「分辨率下限 + 格点对齐」取（上限 {@value #COMPOSED_MAX_SIZE}）：
     * <ul>
     *   <li>3D 段分辨率下限 {@code m0 = max_c ceil((size3D-1)/(domainMax_c-domainMin_c))} ——
     *       合成网格在 {@code [0,1]} 上的间距不低于源表在自己输入域上的间距（不丢源表细节）；</li>
     *   <li>对齐搜索：若 {@code [m0, 8·m0]} 内存在步数使源表的<b>格点平面</b>都落在合成网格上
     *       （{@code min_c·m} 与 {@code span_c·m/(size3D-1)} 都是整数，容差 {@code 1e-4}），取最小的那个 ——
     *       此时合成表与旧口径<b>逐位一致</b>（源表在格点之间是线性的，对齐后没有折点被抹平）；</li>
     *   <li>找不到对齐步数时取 {@code m0}：折点错位只影响源表格点附近一个合成单元，
     *       误差 {@code ~ 二阶差分 / (4m)}，对细网格 LUT 可忽略；</li>
     *   <li>1D 段：{@code m = max(m, min(size1D-1, }{@value #COMPOSED_MAX_SIZE}{@code -1))} ——
     *       shaper 的采样分辨率最多保留到上限（1D 表可以很大，65536 点没法整张塞进 3D 表）；
     *       被截断时误差 {@code ~ 二阶导数 / (8m²)}（二阶，收敛快）。</li>
     * </ul>
     *
     * <h3>等价性（与旧「1D 采样器 + DOMAIN 归一化」口径的偏差）</h3>
     * 合成表是原变换在 {@code S³} 个格点上的采样，渲染侧再做一次四面体插值：
     * <ul>
     *   <li><b>复用路径</b>（纯 3D + 单位域）：表就是原表、查表坐标逐位相同 → 偏差 <b>0</b>；</li>
     *   <li><b>对齐命中</b>（尺寸规则的对齐搜索成功）：源表的折点平面都落在合成网格上，
     *       原变换在合成单元内是线性的 → 偏差 <b>0</b>（只余浮点舍入，{@code ≲1e-7}）；</li>
     *   <li><b>对齐未命中</b>：折点被抹平在一个合成单元内，偏差 {@code ≤ |Δ斜率| / (4m)}
     *       （一阶，随 m 线性收敛；{@code Δ斜率} 是该折点两侧斜率之差，对细网格 LUT 很小）；</li>
     *   <li><b>1D 段被上限截断</b>（{@code size1D-1 > m}）：曲线被重采样到 m 个点后线性插值，
     *       偏差 {@code ≤ max|f''| / (8m²)}（二阶，随 m² 收敛）。</li>
     * </ul>
     * 也就是说：常见的 Resolve 导出（33³ / 65³、单位域）走复用路径逐位不变；带 shaper 或
     * 非单位域的文件在加载期一次算清，误差有上界且随 {@code m} 收敛（{@code m ≤ }{@value #COMPOSED_MAX_SIZE}{@code -1}）。
     *
     * <p>结果惰性计算并缓存：同一实例重复调用返回同一 {@link Composed3D}（渲染侧按引用比较，
     * 只在换 LUT 时重传纹理）。</p>
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
            return new Composed3D(size3D, data3D, false);   // 复用：与旧口径逐位一致
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

    /** 合成网格步数 {@code m = S - 1}（见 {@link #composed3D()} 的尺寸规则）。 */
    private int composedSteps() {
        int m = 1;
        if (size3D > 0) {
            double finest = 0.0;
            for (int c = 0; c < 3; c++) {
                finest = Math.max(finest, (size3D - 1) / (double) (domainMax3D[c] - domainMin3D[c]));
            }
            m = Math.max(1, (int) Math.ceil(Math.min(finest, COMPOSED_MAX_SIZE - 1.0)));
            // 格点对齐搜索（见 composed3D 的尺寸规则）：对齐 = 源表的折点平面都落在合成网格上
            int limit = Math.min(m * 8, COMPOSED_MAX_SIZE - 1);
            for (int candidate = m; candidate <= limit; candidate++) {
                if (gridAligned(candidate)) {
                    m = candidate;
                    break;
                }
            }
        }
        if (size1D > 0) {
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
     * 旧口径的完整变换：1D 段（先）→ 3D 段（后），各段按自己的 DOMAIN 归一化。
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

    /** 3D 段：DOMAIN 归一化 + 四面体（tetrahedral）插值 —— 与 {@code ic_color_adjust.fsh} 的 lut3dLookup 逐分支一致。 */
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
     * {@link CubeLut#composed3D()} 的结果：一张输入域已归一化到 {@code [0,1]³} 的 3D 表（不可变）。
     *
     * @param size      网格边长 S（表为 {@code S×S×S}）
     * @param data      表数据（RGB 交错、红最快、长度 {@code S³×3}；{@code resampled = false} 时
     *                  就是 {@link CubeLut#data3D()} 本身）
     * @param resampled 是否重采样过（{@code false} = 直接复用原 3D 表，输入域本来就是 {@code [0,1]³}）
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
     * @return 该格点在 {@link #data3D()} 中的<b>采样点</b>下标（乘 3 得分量下标）
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
