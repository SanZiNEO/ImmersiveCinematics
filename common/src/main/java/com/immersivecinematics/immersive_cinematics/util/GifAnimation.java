package com.immersivecinematics.immersive_cinematics.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import java.util.List;

/**
 * GIF 动图数据（0.3.5 第5轮 5C）。
 * <p>
 * 拆帧后持有全部 NativeImage 帧与每帧延迟；渲染时按 globalTime 计算帧索引，
 * 把目标帧 copy 进同一个 DynamicTexture 并 upload，显存只占一帧。
 */
public class GifAnimation {

    private final List<NativeImage> frames;
    private final int[] delays;
    private final int width;
    private final int height;
    private final DynamicTexture texture;
    private int currentFrame = -1;

    public GifAnimation(List<NativeImage> frames, int[] delays, int width, int height, DynamicTexture texture) {
        this.frames = frames;
        this.delays = delays;
        this.width = width;
        this.height = height;
        this.texture = texture;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** 按全局时间推进到对应帧；帧变化时才 copy + upload */
    public void update(float globalTime) {
        if (frames.isEmpty()) return;
        int frame = frameIndex(globalTime);
        if (frame == currentFrame) return;
        currentFrame = frame;
        NativeImage target = texture.getPixels();
        if (target != null) {
            target.copyFrom(frames.get(frame));
            texture.upload();
        }
    }

    private int frameIndex(float globalTime) {
        if (frames.size() <= 1) return 0;
        long total = 0;
        for (int delay : delays) total += Math.max(1, delay);
        long t = (long)(globalTime * 1000.0) % total;
        long acc = 0;
        for (int i = 0; i < frames.size(); i++) {
            acc += Math.max(1, delays[i]);
            if (t < acc) return i;
        }
        return frames.size() - 1;
    }

    public void close() {
        for (NativeImage frame : frames) {
            frame.close();
        }
        frames.clear();
        texture.close();
    }
}
