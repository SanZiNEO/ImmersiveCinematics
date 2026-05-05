package com.immersivecinematics.immersive_cinematics.client;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.editor.EditorBridge;

import java.util.function.IntConsumer;

public class EditorBridgeImpl implements EditorBridge {

    public static final EditorBridgeImpl INSTANCE = new EditorBridgeImpl();

    private IntConsumer fboCallback;

    @Override
    public void setTime(float seconds) {
        CameraManager.INSTANCE.setTime(seconds);
    }

    @Override
    public void pushScript(String jsonContent) {
        CameraManager.INSTANCE.pushScript(jsonContent);
    }

    @Override
    public void play() {
        CameraManager.INSTANCE.resume();
    }

    @Override
    public void pause() {
        CameraManager.INSTANCE.pause();
    }

    @Override
    public void stop() {
        CameraManager.INSTANCE.stop();
    }

    @Override
    public void setFboCallback(IntConsumer fboIdCallback) {
        this.fboCallback = fboIdCallback;
    }

    public void notifyFbo(int textureId) {
        if (fboCallback != null) {
            fboCallback.accept(textureId);
        }
    }
}
