package com.immersivecinematics.immersive_cinematics.editor;

import java.util.function.IntConsumer;

public interface EditorBridge {

    void setTime(float seconds);

    void pushScript(String jsonContent);

    void play();

    void pause();

    void stop();

    void setFboCallback(IntConsumer fboIdCallback);
}
