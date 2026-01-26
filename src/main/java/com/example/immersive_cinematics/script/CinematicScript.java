package com.example.immersive_cinematics.script;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 电影剧本数据模型
 * 表示一个完整的电影序列，包含时间轴上的多个动作步骤
 */
public class CinematicScript {

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    @SerializedName("timeline")
    private List<TimelineStep> timeline;

    public CinematicScript() {
        this.name = "Untitled Script";
        this.description = "No description";
        this.timeline = new ArrayList<>();
    }

    public CinematicScript(String name, String description) {
        this.name = name;
        this.description = description;
        this.timeline = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<TimelineStep> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<TimelineStep> timeline) {
        this.timeline = timeline;
    }

    public void addStep(TimelineStep step) {
        this.timeline.add(step);
    }

    public void removeStep(int index) {
        if (index >= 0 && index < timeline.size()) {
            this.timeline.remove(index);
        }
    }

    public TimelineStep getStep(int index) {
        if (index >= 0 && index < timeline.size()) {
            return this.timeline.get(index);
        }
        return null;
    }

    /**
     * 时间轴步骤基类
     */
    public abstract static class TimelineStep {
        @SerializedName("type")
        private StepType type;

        @SerializedName("duration")
        private double duration;

        public TimelineStep(StepType type, double duration) {
            this.type = type;
            this.duration = duration;
        }

        public StepType getType() {
            return type;
        }

        public double getDuration() {
            return duration;
        }

        public void setDuration(double duration) {
            this.duration = duration;
        }
    }

    /**
     * 移动步骤
     */
    public static class MoveStep extends TimelineStep {
        @SerializedName("path_type")
        private PathType pathType;

        @SerializedName("start_position")
        private Vec3 startPosition;

        @SerializedName("end_position")
        private Vec3 endPosition;

        @SerializedName("control_point")
        private Vec3 controlPoint;

        @SerializedName("center_position")
        private Vec3 centerPosition;

        @SerializedName("radius")
        private double radius;

        @SerializedName("speed")
        private double speed;

        @SerializedName("height")
        private double height;

        @SerializedName("target_point")
        private Vec3 targetPoint;

        @SerializedName("background_point")
        private Vec3 backgroundPoint;

        @SerializedName("strength")
        private double strength;

        @SerializedName("forward")
        private boolean forward;

        @SerializedName("start_rotation")
        private Vec3 startRotation;

        @SerializedName("end_rotation")
        private Vec3 endRotation;

        @SerializedName("start_fov")
        private float startFOV;

        @SerializedName("end_fov")
        private float endFOV;

        @SerializedName("heading_offset")
        private double headingOffset;

        public MoveStep() {
            super(StepType.MOVE, 0);
        }

        public PathType getPathType() {
            return pathType;
        }

        public void setPathType(PathType pathType) {
            this.pathType = pathType;
        }

        public Vec3 getStartPosition() {
            return startPosition;
        }

        public void setStartPosition(Vec3 startPosition) {
            this.startPosition = startPosition;
        }

        public Vec3 getEndPosition() {
            return endPosition;
        }

        public void setEndPosition(Vec3 endPosition) {
            this.endPosition = endPosition;
        }

        public Vec3 getControlPoint() {
            return controlPoint;
        }

        public void setControlPoint(Vec3 controlPoint) {
            this.controlPoint = controlPoint;
        }

        public Vec3 getCenterPosition() {
            return centerPosition;
        }

        public void setCenterPosition(Vec3 centerPosition) {
            this.centerPosition = centerPosition;
        }

        public double getRadius() {
            return radius;
        }

        public void setRadius(double radius) {
            this.radius = radius;
        }

        public double getSpeed() {
            return speed;
        }

        public void setSpeed(double speed) {
            this.speed = speed;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public Vec3 getTargetPoint() {
            return targetPoint;
        }

        public void setTargetPoint(Vec3 targetPoint) {
            this.targetPoint = targetPoint;
        }

        public Vec3 getBackgroundPoint() {
            return backgroundPoint;
        }

        public void setBackgroundPoint(Vec3 backgroundPoint) {
            this.backgroundPoint = backgroundPoint;
        }

        public double getStrength() {
            return strength;
        }

        public void setStrength(double strength) {
            this.strength = strength;
        }

        public boolean isForward() {
            return forward;
        }

        public void setForward(boolean forward) {
            this.forward = forward;
        }

        public Vec3 getStartRotation() {
            return startRotation;
        }

        public void setStartRotation(Vec3 startRotation) {
            this.startRotation = startRotation;
        }

        public Vec3 getEndRotation() {
            return endRotation;
        }

        public void setEndRotation(Vec3 endRotation) {
            this.endRotation = endRotation;
        }

        public float getStartFOV() {
            return startFOV;
        }

        public void setStartFOV(float startFOV) {
            this.startFOV = startFOV;
        }

        public float getEndFOV() {
            return endFOV;
        }

        public void setEndFOV(float endFOV) {
            this.endFOV = endFOV;
        }

        public double getHeadingOffset() {
            return headingOffset;
        }

        public void setHeadingOffset(double headingOffset) {
            this.headingOffset = headingOffset;
        }
    }

    /**
     * 等待步骤
     */
    public static class WaitStep extends TimelineStep {
        public WaitStep(double duration) {
            super(StepType.WAIT, duration);
        }
    }

    /**
     * 标题/HUD显示步骤
     */
    public static class TitleStep extends TimelineStep {
        @SerializedName("title")
        private String title;

        @SerializedName("subtitle")
        private String subtitle;

        @SerializedName("fade_in")
        private int fadeIn;

        @SerializedName("stay")
        private int stay;

        @SerializedName("fade_out")
        private int fadeOut;

        @SerializedName("black_bars")
        private boolean blackBars;

        @SerializedName("hud_text")
        private String hudText;

        public TitleStep() {
            super(StepType.TITLE, 0);
            this.fadeIn = 10;
            this.stay = 50;
            this.fadeOut = 20;
            this.blackBars = false;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSubtitle() {
            return subtitle;
        }

        public void setSubtitle(String subtitle) {
            this.subtitle = subtitle;
        }

        public int getFadeIn() {
            return fadeIn;
        }

        public void setFadeIn(int fadeIn) {
            this.fadeIn = fadeIn;
        }

        public int getStay() {
            return stay;
        }

        public void setStay(int stay) {
            this.stay = stay;
        }

        public int getFadeOut() {
            return fadeOut;
        }

        public void setFadeOut(int fadeOut) {
            this.fadeOut = fadeOut;
        }

        public boolean hasBlackBars() {
            return blackBars;
        }

        public void setBlackBars(boolean blackBars) {
            this.blackBars = blackBars;
        }

        public String getHudText() {
            return hudText;
        }

        public void setHudText(String hudText) {
            this.hudText = hudText;
        }
    }

    /**
     * 指令执行步骤
     */
    public static class CommandStep extends TimelineStep {
        @SerializedName("command")
        private String command;

        @SerializedName("execute_as_player")
        private boolean executeAsPlayer;

        public CommandStep() {
            super(StepType.COMMAND, 0);
            this.executeAsPlayer = false;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public boolean isExecuteAsPlayer() {
            return executeAsPlayer;
        }

        public void setExecuteAsPlayer(boolean executeAsPlayer) {
            this.executeAsPlayer = executeAsPlayer;
        }
    }

    /**
     * 步骤类型枚举
     */
    public enum StepType {
        @SerializedName("move")
        MOVE,
        @SerializedName("wait")
        WAIT,
        @SerializedName("title")
        TITLE,
        @SerializedName("command")
        COMMAND
    }

    /**
     * 路径类型枚举
     */
    public enum PathType {
        @SerializedName("direct")
        DIRECT,
        @SerializedName("smooth")
        SMOOTH,
        @SerializedName("orbit")
        ORBIT,
        @SerializedName("bezier")
        BEZIER,
        @SerializedName("spiral")
        SPIRAL,
        @SerializedName("dolly")
        DOLLY,
        @SerializedName("stationary_pan")
        STATIONARY_PAN
    }
}