package com.immersivecinematics.immersive_cinematics.editor.model;

import java.util.ArrayList;
import java.util.List;

public class EditorScript {
    public String id = "";
    public String name = "";
    public String author = "";
    public int version = 3;
    public String description = "";
    public String dimension;

    public boolean blockKeyboard = true;
    public boolean blockMouse = true;
    public boolean blockMobAi;
    public boolean hideHud = true;
    public boolean hideArm = true;
    public boolean suppressBob = true;
    public Boolean hideChat;
    public Boolean hideScoreboard;
    public Boolean hideActionBar;
    public Boolean hideTitle;
    public Boolean hideSubtitles;
    public Boolean hideHotbar;
    public Boolean hideCrosshair;
    public boolean renderPlayerModel = true;
    public boolean pauseWhenGamePaused = true;
    public boolean interruptible = true;
    public boolean skippable = true;
    public boolean holdAtEnd;

    public List<EditorTrigger> triggers = new ArrayList<>();
    public float totalDuration = 10f;
    public List<EditorTrack> tracks = new ArrayList<>();

    public EditorScript() {
        tracks.add(new EditorTrack("camera"));
    }

    public EditorScript copy() {
        EditorScript s = new EditorScript();
        s.id = id;
        s.name = name;
        s.author = author;
        s.version = version;
        s.description = description;
        s.dimension = dimension;
        s.blockKeyboard = blockKeyboard;
        s.blockMouse = blockMouse;
        s.blockMobAi = blockMobAi;
        s.hideHud = hideHud;
        s.hideArm = hideArm;
        s.suppressBob = suppressBob;
        s.hideChat = hideChat;
        s.hideScoreboard = hideScoreboard;
        s.hideActionBar = hideActionBar;
        s.hideTitle = hideTitle;
        s.hideSubtitles = hideSubtitles;
        s.hideHotbar = hideHotbar;
        s.hideCrosshair = hideCrosshair;
        s.renderPlayerModel = renderPlayerModel;
        s.pauseWhenGamePaused = pauseWhenGamePaused;
        s.interruptible = interruptible;
        s.skippable = skippable;
        s.holdAtEnd = holdAtEnd;
        for (EditorTrigger t : triggers) {
            s.triggers.add(t.copy());
        }
        s.totalDuration = totalDuration;
        for (EditorTrack t : tracks) {
            s.tracks.add(t.copy());
        }
        return s;
    }
}
