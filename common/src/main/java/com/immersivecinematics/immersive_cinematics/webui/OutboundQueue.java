package com.immersivecinematics.immersive_cinematics.webui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 单个 WebSocket 会话的有界发送队列（纯 Java，无 Minecraft 依赖）。
 *
 * <p>设计目标：渲染线程每帧往这里塞一帧，永不阻塞、永不分配大对象；网络发送由会话自己的
 * 单条发送线程通过 {@link #poll()} 串行取走。队列满时的策略是<b>丢帧不丢文本</b>：
 * 先丢队头最旧的帧，仍满再丢最旧文本（帧的丢弃计入 {@link #droppedFrames()}，文本的丢弃计入
 * {@link #droppedTexts()}）。
 *
 * <p>语义边界：
 * <ul>
 *   <li>{@link #enqueueFrame(byte[])}：满 → 丢最旧帧（计数）；若队列全是文本仍满 → 丢本帧（计数）并返回 false。</li>
 *   <li>{@link #enqueueText(String)}：满 → 先丢最旧帧；仍满 → 丢最旧文本（计数），本文本一定入队。</li>
 *   <li>{@link #poll()}：阻塞到有消息或队列被 {@link #close()}；关闭且空时返回 {@code null}。</li>
 *   <li>{@link #close()}：幂等；置关闭、清空残留消息、唤醒阻塞的 {@code poll()}。</li>
 * </ul>
 *
 * <p>所有方法均 {@code synchronized} 于同一把锁，跨线程直接调用安全。
 */
public final class OutboundQueue {

    /** 队列容量（条消息）。 */
    public static final int CAPACITY = 16;

    /** 一条待发送消息：{@code frame == true} 表示二进制帧流（opcode 0x82），否则为 UTF-8 文本（opcode 0x81）。 */
    public record Outbound(byte[] payload, boolean frame) {
    }

    private final ArrayDeque<Outbound> messages = new ArrayDeque<>(CAPACITY);
    private boolean closed;
    private long sentFrames;
    private long droppedFrames;
    private long droppedTexts;

    /**
     * 入队一帧二进制消息。满时丢最旧帧；若仍满（队列全是文本）则丢本帧。
     *
     * @return 本帧是否成功入队
     */
    public synchronized boolean enqueueFrame(byte[] payload) {
        if (closed) return false;
        if (messages.size() >= CAPACITY && !dropOldestFrame()) {
            droppedFrames++;
            return false;
        }
        messages.addLast(new Outbound(payload, true));
        notifyAll();
        return true;
    }

    /** 入队一条文本消息；文本优先于帧，永不因满载而丢本条（除非会话已关闭）。 */
    public synchronized void enqueueText(String text) {
        if (closed) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        if (messages.size() >= CAPACITY) {
            if (!dropOldestFrame()) {
                // 队列里没有可丢的帧：丢最旧文本，为本条腾位置
                messages.pollFirst();
                droppedTexts++;
            }
        }
        messages.addLast(new Outbound(payload, false));
        notifyAll();
    }

    /**
     * 取一条待发送消息，队列空且未关闭时阻塞等待。
     *
     * @return 队头消息；队列已关闭且清空后返回 {@code null}（发送线程据此退出）
     */
    public synchronized Outbound poll() {
        while (messages.isEmpty() && !closed) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Outbound message = messages.pollFirst();
        if (message == null) return null;
        if (message.frame()) sentFrames++;
        return message;
    }

    /** 关闭队列：置关闭、清空残留消息、唤醒阻塞的发送线程。幂等。 */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        messages.clear();
        notifyAll();
    }

    /** 已出队（准备写出）的帧数。 */
    public synchronized long sentFrames() {
        return sentFrames;
    }

    /** 因队列满而丢弃的帧数（含被顶掉的旧帧与被拒的新帧）。 */
    public synchronized long droppedFrames() {
        return droppedFrames;
    }

    /** 因队列满且无帧可丢而丢弃的文本数。 */
    public synchronized long droppedTexts() {
        return droppedTexts;
    }

    /** 队列是否已关闭。 */
    public synchronized boolean isClosed() {
        return closed;
    }

    /** 从队头找第一条帧消息移除；队列里没有帧时返回 false。调用方须持锁。 */
    private boolean dropOldestFrame() {
        for (Iterator<Outbound> it = messages.iterator(); it.hasNext(); ) {
            if (it.next().frame()) {
                it.remove();
                droppedFrames++;
                return true;
            }
        }
        return false;
    }
}
