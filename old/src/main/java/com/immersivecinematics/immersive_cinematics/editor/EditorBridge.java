package com.immersivecinematics.immersive_cinematics.editor;

public interface EditorBridge {

    void setTime(float seconds);

    void pushScript(String jsonContent);

    void play();

    void pause();

    void stop();
}
