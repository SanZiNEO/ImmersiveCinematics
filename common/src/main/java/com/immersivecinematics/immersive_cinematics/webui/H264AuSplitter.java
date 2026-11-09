package com.immersivecinematics.immersive_cinematics.webui;

import java.io.ByteArrayOutputStream;

/**
 * H.264 Annex B 字节流 → access unit（AU）切分器。
 *
 * <p>输入是 ffmpeg 输出管道的任意分片（可能把一个 NAL、甚至一个起始码切成两半），
 * {@link #feed} 可增量喂入；内部保留未完成的半包，等后续字节补齐。</p>
 *
 * <p>切分规则：</p>
 * <ul>
 *   <li>起始码识别 {@code 00 00 01}（四字节形式 {@code 00 00 00 01} 等价，多出的 0 归并到起始码）。</li>
 *   <li>AU 内有 AUD（NAL type 9）时，AUD 即边界：见到下一个 AUD 就先输出上一个 AU。</li>
 *   <li>无 AUD 时按语义连续性切：已经出现过 VCL（type 1/5）后，再见到 VCL 或 SPS(7)/PPS(8) 就开新 AU。</li>
 *   <li>{@code keyframe} = 该 AU 内含 NAL type 5（IDR）。</li>
 * </ul>
 *
 * <p>输出的 AU 用四字节起始码重建（{@code 00 00 00 01} + NAL 负载），原始起始码长度不保留——
 * 解码端只按起始码扫描，长度无语义。</p>
 *
 * <p>线程边界：无内部同步；实例归单个读取线程所有。</p>
 */
public final class H264AuSplitter {

    /** AU 消费者。{@code annexB} 是独立数组，回调返回后可继续使用。 */
    public interface Sink {
        void onAccessUnit(boolean keyframe, byte[] annexB);
    }

    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final int START_CODE_LEN = 3;

    private final Sink sink;

    /** 未消费的输入字节（总是从某个起始码开始，或不足 3 字节的尾巴）。 */
    private byte[] buffer = new byte[64 * 1024];
    private int bufferLength;

    /** 当前 AU 的累积内容（含 4 字节起始码）。 */
    private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream(64 * 1024);

    private boolean hasAud;
    private boolean hasVcl;
    private boolean hasIdr;

    /**
     * @param sink AU 消费者，不可为 {@code null}
     */
    public H264AuSplitter(Sink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.sink = sink;
    }

    /**
     * 喂入一段字节。可任意切分，内部按需缓存。
     *
     * @param data   字节源
     * @param offset 起始偏移
     * @param length 长度
     */
    public void feed(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return;
        }
        if (offset < 0 || length > data.length - offset) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + " length=" + length + " capacity=" + data.length);
        }
        ensureCapacity(bufferLength + length);
        System.arraycopy(data, offset, buffer, bufferLength, length);
        bufferLength += length;
        scan();
    }

    /**
     * 流结束：把缓冲区里最后一个完整 NAL 收进当前 AU 并输出。
     * 之后实例可继续复用（状态已清空）。
     */
    public void flush() {
        int start = findStartCode(0);
        if (start == 0) {
            int end = bufferLength;
            while (end > START_CODE_LEN && buffer[end - 1] == 0) {
                end--;
            }
            int length = end - START_CODE_LEN;
            if (length > 0) {
                onNal(buffer[START_CODE_LEN] & 0x1F, buffer, START_CODE_LEN, length);
            }
        }
        bufferLength = 0;
        emit();
    }

    /** 扫描缓冲区，切出所有已经完整的 NAL。 */
    private void scan() {
        while (true) {
            int start = findStartCode(0);
            if (start < 0) {
                // 还没见到起始码：丢掉前面的垃圾，只留可能被切断的起始码前缀
                if (bufferLength > 3) {
                    dropPrefix(bufferLength - 3);
                }
                return;
            }
            if (start > 0) {
                dropPrefix(start);
            }

            int next = findStartCode(START_CODE_LEN);
            if (next < 0) {
                // 半包：这一 NAL 还没结束，留到下次
                return;
            }

            int end = next;
            if (end > START_CODE_LEN && buffer[end - 1] == 0) {
                // 四字节起始码的前导 0 属于下一个起始码，不算当前 NAL 的负载
                end--;
            }
            int length = end - START_CODE_LEN;
            if (length > 0) {
                onNal(buffer[START_CODE_LEN] & 0x1F, buffer, START_CODE_LEN, length);
            }
            dropPrefix(next);
        }
    }

    /** 处理一个完整 NAL：必要时先输出上一个 AU，再把它并入当前 AU。 */
    private void onNal(int type, byte[] src, int offset, int length) {
        boolean vcl = type == 1 || type == 5;

        if (accessUnit.size() > 0) {
            boolean boundary = type == 9
                    || (!hasAud && hasVcl && (vcl || type == 7 || type == 8));
            if (boundary) {
                emit();
            }
        }

        accessUnit.write(START_CODE, 0, START_CODE.length);
        accessUnit.write(src, offset, length);

        if (type == 9) {
            hasAud = true;
        }
        if (vcl) {
            hasVcl = true;
        }
        if (type == 5) {
            hasIdr = true;
        }
    }

    /** 输出当前 AU（为空则什么也不做）并清空状态。 */
    private void emit() {
        if (accessUnit.size() == 0) {
            return;
        }
        byte[] annexB = accessUnit.toByteArray();
        boolean keyframe = hasIdr;
        accessUnit.reset();
        hasAud = false;
        hasVcl = false;
        hasIdr = false;
        sink.onAccessUnit(keyframe, annexB);
    }

    /** 查找 {@code 00 00 01}，返回其首字节下标；找不到返回 -1。 */
    private int findStartCode(int from) {
        int limit = bufferLength - START_CODE_LEN + 1;
        for (int i = Math.max(0, from); i < limit; i++) {
            if (buffer[i] == 0 && buffer[i + 1] == 0 && buffer[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    /** 从缓冲区头部丢弃 {@code count} 字节。 */
    private void dropPrefix(int count) {
        if (count <= 0) {
            return;
        }
        if (count >= bufferLength) {
            bufferLength = 0;
            return;
        }
        System.arraycopy(buffer, count, buffer, 0, bufferLength - count);
        bufferLength -= count;
    }

    /** 保证缓冲区容量；只增不减（峰值 = 最大 AU 尺寸）。 */
    private void ensureCapacity(int required) {
        if (required <= buffer.length) {
            return;
        }
        int capacity = buffer.length;
        while (capacity < required) {
            capacity <<= 1;
        }
        byte[] grown = new byte[capacity];
        System.arraycopy(buffer, 0, grown, 0, bufferLength);
        buffer = grown;
    }
}
