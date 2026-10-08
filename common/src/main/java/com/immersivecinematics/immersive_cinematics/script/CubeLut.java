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
 * {@code size - 1}（不做 ffmpeg 那种忽略 {@code min} 的简化）。本类只提供 {@code domainMin1D} /
 * {@code domainMax1D} / {@code domainMin3D} / {@code domainMax3D} 原始值，归一化留给采样侧（本任务不做插值）。
 *
 * <h2>不可变</h2>
 * 解析结果不可变；{@link #data1D()} / {@link #data3D()} 返回内部数组（只读契约），调用方不得修改。
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
