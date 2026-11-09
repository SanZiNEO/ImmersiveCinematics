package com.immersivecinematics.immersive_cinematics.webui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 预览流帧协议（WebSocket 二进制帧）。
 *
 * <p>固定 18 字节头（大端）+ 负载：</p>
 *
 * <pre>
 * offset  size  字段
 * 0       1     type        {@link #TYPE_RGBA} / {@link #TYPE_H264}
 * 1       1     flags       bit0 = {@link #FLAG_KEYFRAME}
 * 2       4     frameId     u32（捕获层自 0 起单调自增，编码输出原样配对）
 * 6       8     ptsMicros   u64（相对编码会话首帧的微秒数）
 * 14      2     width       u16（编码流为编码器输入尺寸）
 * 16      2     height      u16
 * 18      ...   payload
 * </pre>
 *
 * <p>{@link #TYPE_RGBA} 的负载是 {@code width * height * 4} 字节 RGBA8，行序自顶向下；
 * {@link #TYPE_H264} 的负载是一个 Annex B access unit（可含多个 NAL）。</p>
 *
 * <p>解析严格校验头部长度、type 与负载尺寸；任何不一致都返回 {@code null}，不抛异常——
 * 前端可随时发来任意负载，服务端不能因此炸掉。</p>
 */
public final class PreviewProtocol {

    /** 原始 RGBA8 帧。 */
    public static final int TYPE_RGBA = 1;

    /** H.264 Annex B access unit。 */
    public static final int TYPE_H264 = 2;

    /** 关键帧标记（仅对 {@link #TYPE_H264} 有意义）。 */
    public static final int FLAG_KEYFRAME = 0x01;

    /** 固定头长度（字节）。 */
    public static final int HEADER_BYTES = 18;

    private PreviewProtocol() {
    }

    /** 一帧的解析结果。{@code payload} 是独立拷贝，与传入数组不共享内存。 */
    public record Message(int type, int flags, long frameId, long ptsMicros,
                          int width, int height, byte[] payload) {
    }

    /**
     * 打包一帧。头部按大端写出，负载<b>复制</b>进结果数组，调用方可立即释放捕获帧。
     *
     * @param type      帧类型
     * @param flags     标志位
     * @param frameId   帧序号（取低 32 位）
     * @param ptsMicros 相对首帧的微秒数
     * @param width     宽度（编码流为编码器输入尺寸；RGBA 必须与负载匹配）
     * @param height    高度
     * @param payload   负载；{@code null} 视为空
     * @return 完整帧字节（头 + 负载的新数组）
     */
    public static byte[] pack(int type, int flags, long frameId, long ptsMicros,
                              int width, int height, byte[] payload) {
        int payloadLength = payload == null ? 0 : payload.length;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + payloadLength).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) type);
        buf.put((byte) flags);
        buf.putInt((int) frameId);
        buf.putLong(ptsMicros);
        buf.putShort((short) width);
        buf.putShort((short) height);
        if (payloadLength > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    /**
     * 解析一帧。非法输入返回 {@code null}（长度不足 / type 未知 / 尺寸或负载不匹配）。
     *
     * @param data 完整帧字节
     * @return 解析结果；非法返回 {@code null}
     */
    public static Message parse(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int type = buf.get() & 0xFF;
        if (type != TYPE_RGBA && type != TYPE_H264) {
            return null;
        }
        int flags = buf.get() & 0xFF;
        long frameId = buf.getInt() & 0xFFFFFFFFL;
        long ptsMicros = buf.getLong();
        int width = buf.getShort() & 0xFFFF;
        int height = buf.getShort() & 0xFFFF;
        int payloadLength = data.length - HEADER_BYTES;

        if (type == TYPE_RGBA) {
            if (width <= 0 || height <= 0) {
                return null;
            }
            long expected = (long) width * (long) height * 4L;
            if (payloadLength != expected) {
                return null;
            }
        } else {
            if (width <= 0 || height <= 0) {
                return null;
            }
            if (payloadLength <= 0) {
                return null;
            }
        }

        byte[] payload = new byte[payloadLength];
        buf.get(payload);
        return new Message(type, flags, frameId, ptsMicros, width, height, payload);
    }
}
