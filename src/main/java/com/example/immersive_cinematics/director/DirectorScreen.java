package com.example.immersive_cinematics.director;

import com.example.immersive_cinematics.camera.CinematicCameraEntity;
import com.example.immersive_cinematics.script.*;
import com.example.immersive_cinematics.trigger.WorldEventDetector;
import com.example.immersive_cinematics.util.CameraStateRecorder;
import com.example.immersive_cinematics.util.StructureScanner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.widget.ExtendedButton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本系统图形界面 - 分页版本
 * 提供三个功能页面：
 * 1. 结构搜寻和事件监听
 * 2. 脚本编辑
 * 3. 脚本储存和预览
 */
public class DirectorScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Component TITLE = Component.literal("镜头脚本编辑器");

    // 页面枚举
    private enum Page {
        EVENT_LISTENER,
        SCRIPT_EDITOR,
        SCRIPT_MANAGER
    }

    private Page currentPage = Page.EVENT_LISTENER;

    // 界面元素
    private Button eventListenerPageButton;
    private Button scriptEditorPageButton;
    private Button scriptManagerPageButton;

    // 事件监听器页面元素
    private EditBox structureSearchInput;
    private Button scanStructuresButton;
    private List<Button> modButtons;
    private List<Button> structureButtons;
    private List<Button> eventTypeButtons;
    private List<ResourceLocation> availableStructures;
    private ListMultimap<String, ResourceLocation> structuresByMod;
    private String selectedMod;
    private ResourceLocation selectedStructure;
    private CameraScript.TriggerType selectedEventType;

    // 脚本编辑器页面元素
    private EditBox scriptNameInput;
    private EditBox scriptDescriptionInput;
    private Button recordStartStateButton;
    private Button recordEndStateButton;
    private List<Button> pathTypeButtons;
    private Button createRouteButton;
    private EditBox durationInput;
    private EditBox radiusInput;
    private EditBox strengthInput;
    private CameraStateRecorder.CameraState startState;
    private CameraStateRecorder.CameraState endState;
    private CameraScript.PathType selectedPathType;

    // 脚本管理器页面元素
    private List<Button> scriptButtons;
    private Button playScriptButton;
    private Button stopScriptButton;
    private Button deleteScriptButton;
    private Button saveScriptButton;
    private Button loadScriptButton;

    // 当前选中的镜头脚本
    private CameraScript selectedScript;

    // 编辑状态
    private boolean isEditing;

    public DirectorScreen() {
        super(TITLE);
        this.isEditing = false;
        this.availableStructures = new ArrayList<>();
        this.structuresByMod = ArrayListMultimap.create();
        this.selectedMod = null;
        this.selectedStructure = null;
        this.selectedEventType = CameraScript.TriggerType.DIMENSION_CHANGE;
        this.selectedPathType = CameraScript.PathType.DIRECT;
    }

    @Override
    protected void init() {
        // 页面导航按钮
        int navButtonWidth = 120;
        int navButtonHeight = 25;
        int navButtonY = 10;

        eventListenerPageButton = addRenderableWidget(new ExtendedButton(10, navButtonY, navButtonWidth, navButtonHeight,
                Component.literal("事件监听器"), b -> switchPage(Page.EVENT_LISTENER)));

        scriptEditorPageButton = addRenderableWidget(new ExtendedButton(10 + navButtonWidth + 5, navButtonY, navButtonWidth, navButtonHeight,
                Component.literal("脚本编辑器"), b -> switchPage(Page.SCRIPT_EDITOR)));

        scriptManagerPageButton = addRenderableWidget(new ExtendedButton(10 + (navButtonWidth + 5) * 2, navButtonY, navButtonWidth, navButtonHeight,
                Component.literal("脚本管理器"), b -> switchPage(Page.SCRIPT_MANAGER)));

        // 初始化各页面
        initEventListenerPage();
        initScriptEditorPage();
        initScriptManagerPage();

        updatePageVisibility();
    }

    // 页面导航方法
    private void switchPage(Page page) {
        currentPage = page;
        updatePageVisibility();
        updateWidgetStates();
    }

    // 初始化事件监听器页面
    private void initEventListenerPage() {
        int startX = 10;
        int startY = 50;
        int elementWidth = 200;
        int elementHeight = 20;
        int spacing = 25;

        // 结构搜索
        structureSearchInput = new EditBox(font, startX, startY, elementWidth, elementHeight, Component.literal("搜索结构"));
        structureSearchInput.setMaxLength(50);
        structureSearchInput.setResponder(this::onStructureSearch);
        addWidget(structureSearchInput);

        startY += spacing;
        scanStructuresButton = addRenderableWidget(new ExtendedButton(startX, startY, elementWidth, elementHeight,
                Component.literal("扫描结构"), b -> onScanStructures()));

        startY += spacing;
        // 模组选择按钮区域
        modButtons = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int index = i;
            Button button = new ExtendedButton(startX, startY + i * 25, elementWidth, elementHeight,
                    Component.literal("模组 " + (i + 1)), b -> onModSelected(index));
            addRenderableWidget(button);
            modButtons.add(button);
        }

        // 结构选择按钮区域
        int structureStartX = startX + elementWidth + 10;
        structureButtons = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int index = i;
            Button button = new ExtendedButton(structureStartX, startY + i * 20, elementWidth, elementHeight,
                    Component.literal("结构 " + (i + 1)), b -> onStructureSelected(index));
            addRenderableWidget(button);
            structureButtons.add(button);
        }

        // 事件类型选择
        int eventStartX = startX;
        int eventStartY = startY + 6 * 25;
        eventTypeButtons = new ArrayList<>();
        for (CameraScript.TriggerType type : CameraScript.TriggerType.values()) {
            final CameraScript.TriggerType triggerType = type;
            Button button = new ExtendedButton(eventStartX, eventStartY, elementWidth, elementHeight,
                    Component.literal(getTriggerTypeName(type)), b -> onEventTypeSelected(triggerType));
            addRenderableWidget(button);
            eventTypeButtons.add(button);
            eventStartX += elementWidth + 10;
            if (eventStartX > width - elementWidth - 20) {
                eventStartX = startX;
                eventStartY += 25;
            }
        }
    }

    // 初始化脚本编辑器页面
    private void initScriptEditorPage() {
        int startX = 10;
        int startY = 50;
        int elementWidth = 150;
        int elementHeight = 20;
        int spacing = 25;

        // 脚本信息输入
        scriptNameInput = new EditBox(font, startX, startY, elementWidth, elementHeight, Component.literal("脚本名称"));
        scriptNameInput.setMaxLength(32);
        scriptNameInput.setResponder(this::onScriptNameChanged);
        addWidget(scriptNameInput);

        startY += spacing;
        scriptDescriptionInput = new EditBox(font, startX, startY, elementWidth, elementHeight, Component.literal("脚本描述"));
        scriptDescriptionInput.setMaxLength(100);
        scriptDescriptionInput.setResponder(this::onScriptDescriptionChanged);
        addWidget(scriptDescriptionInput);

        startY += spacing;
        recordStartStateButton = addRenderableWidget(new ExtendedButton(startX, startY, elementWidth, elementHeight,
                Component.literal("记录起始状态"), b -> onRecordStartState()));

        startY += spacing;
        recordEndStateButton = addRenderableWidget(new ExtendedButton(startX, startY, elementWidth, elementHeight,
                Component.literal("记录结束状态"), b -> onRecordEndState()));

        // 路径类型选择
        int pathStartX = startX + elementWidth + 10;
        int pathStartY = 50;
        int pathButtonWidth = 80;
        int pathButtonHeight = 20;
        pathTypeButtons = new ArrayList<>();
        for (CameraScript.PathType type : CameraScript.PathType.values()) {
            final CameraScript.PathType pathType = type;
            Button button = new ExtendedButton(pathStartX, pathStartY, pathButtonWidth, pathButtonHeight,
                    Component.literal(getPathTypeName(type)), b -> onPathTypeSelected(pathType));
            addRenderableWidget(button);
            pathTypeButtons.add(button);
            pathStartX += pathButtonWidth + 5;
            if (pathStartX > width - pathButtonWidth - 20) {
                pathStartX = startX + elementWidth + 10;
                pathStartY += 25;
            }
        }

        // 参数输入
        int paramStartX = startX + elementWidth + 10;
        int paramStartY = pathStartY + 30;
        durationInput = new EditBox(font, paramStartX, paramStartY, 80, elementHeight, Component.literal("持续时间"));
        durationInput.setMaxLength(5);
        durationInput.setValue("5.0");
        addWidget(durationInput);

        paramStartY += spacing;
        radiusInput = new EditBox(font, paramStartX, paramStartY, 80, elementHeight, Component.literal("轨道半径"));
        radiusInput.setMaxLength(5);
        radiusInput.setValue("5.0");
        addWidget(radiusInput);

        paramStartY += spacing;
        strengthInput = new EditBox(font, paramStartX, paramStartY, 80, elementHeight, Component.literal("变焦强度"));
        strengthInput.setMaxLength(5);
        strengthInput.setValue("1.0");
        addWidget(strengthInput);

        // 创建路线按钮
        int createButtonX = startX;
        int createButtonY = startY + spacing;
        createRouteButton = addRenderableWidget(new ExtendedButton(createButtonX, createButtonY, elementWidth * 2 + 10, elementHeight,
                Component.literal("创建运镜路线"), b -> onCreateRoute()));
    }

    // 初始化脚本管理器页面
    private void initScriptManagerPage() {
        int startX = 10;
        int startY = 50;
        int elementWidth = 200;
        int elementHeight = 20;
        int spacing = 25;

        // 脚本列表
        scriptButtons = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int index = i;
            Button button = new ExtendedButton(startX, startY + i * 25, elementWidth, elementHeight,
                    Component.literal("脚本 " + (i + 1)), b -> onScriptSelected(index));
            addRenderableWidget(button);
            scriptButtons.add(button);
        }

        // 操作按钮
        int buttonStartX = startX + elementWidth + 10;
        int buttonStartY = 50;
        playScriptButton = addRenderableWidget(new ExtendedButton(buttonStartX, buttonStartY, elementWidth, elementHeight,
                Component.literal("播放脚本"), b -> onPlayScript()));

        buttonStartY += spacing;
        stopScriptButton = addRenderableWidget(new ExtendedButton(buttonStartX, buttonStartY, elementWidth, elementHeight,
                Component.literal("停止播放"), b -> onStopScript()));

        buttonStartY += spacing;
        deleteScriptButton = addRenderableWidget(new ExtendedButton(buttonStartX, buttonStartY, elementWidth, elementHeight,
                Component.literal("删除脚本"), b -> onDeleteScript()));

        buttonStartY += spacing;
        saveScriptButton = addRenderableWidget(new ExtendedButton(buttonStartX, buttonStartY, elementWidth, elementHeight,
                Component.literal("保存脚本"), b -> onSaveScript()));

        buttonStartY += spacing;
        loadScriptButton = addRenderableWidget(new ExtendedButton(buttonStartX, buttonStartY, elementWidth, elementHeight,
                Component.literal("加载脚本"), b -> onLoadScript()));
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 渲染背景
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 渲染标题
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(1.5f, 1.5f, 1.5f);
        int titleX = (int) ((width / 2f - font.width(TITLE) / 2f) / 1.5f);
        int titleY = (int) ((50 / 1.5f));
        guiGraphics.drawString(font, TITLE, titleX, titleY, 0xFFFFFF);
        guiGraphics.pose().popPose();

        // 渲染页面内容
        switch (currentPage) {
            case EVENT_LISTENER:
                renderEventListenerPage(guiGraphics, mouseX, mouseY);
                break;
            case SCRIPT_EDITOR:
                renderScriptEditorPage(guiGraphics, mouseX, mouseY);
                break;
            case SCRIPT_MANAGER:
                renderScriptManagerPage(guiGraphics, mouseX, mouseY);
                break;
        }

        // 渲染导航按钮状态
        eventListenerPageButton.setFGColor(currentPage == Page.EVENT_LISTENER ? 0xFFFF00 : 0xFFFFFF);
        scriptEditorPageButton.setFGColor(currentPage == Page.SCRIPT_EDITOR ? 0xFFFF00 : 0xFFFFFF);
        scriptManagerPageButton.setFGColor(currentPage == Page.SCRIPT_MANAGER ? 0xFFFF00 : 0xFFFFFF);
    }

    // 渲染事件监听器页面
    private void renderEventListenerPage(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = 10;
        int y = height - 100;

        // 显示选中状态
        if (selectedMod != null) {
            guiGraphics.drawString(font, Component.literal("选中模组: " + selectedMod), x, y, 0x00FF00);
            y += 12;
        }
        if (selectedStructure != null) {
            guiGraphics.drawString(font, Component.literal("选中结构: " + StructureScanner.getStructureDisplayName(selectedStructure)), x, y, 0x00FF00);
            y += 12;
        }
        guiGraphics.drawString(font, Component.literal("选中事件类型: " + getTriggerTypeName(selectedEventType)), x, y, 0x00FF00);

        // 显示扫描状态
        if (!availableStructures.isEmpty()) {
            x = width - 200;
            y = 50;
            guiGraphics.drawString(font, Component.literal("结构数量: " + availableStructures.size()), x, y, 0xAAAAAA);
        }
    }

    // 渲染脚本编辑器页面
    private void renderScriptEditorPage(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = 10;
        int y = height - 150;

        // 显示记录的状态
        if (startState != null) {
            guiGraphics.drawString(font, Component.literal("起始状态: " + CameraStateRecorder.getStateDetails(startState)), x, y, 0x00FF00);
            y += 60;
        }
        if (endState != null) {
            guiGraphics.drawString(font, Component.literal("结束状态: " + CameraStateRecorder.getStateDetails(endState)), x, y, 0x00FF00);
            y += 60;
        }

        // 显示选中的路径类型
        if (selectedPathType != null) {
            x = width - 200;
            y = 50;
            guiGraphics.drawString(font, Component.literal("路径类型: " + getPathTypeName(selectedPathType)), x, y, 0xAAAAAA);
        }
    }

    // 渲染脚本管理器页面
    private void renderScriptManagerPage(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = 10;
        int y = height - 100;

        // 显示选中的脚本信息
        if (selectedScript != null) {
            guiGraphics.drawString(font, Component.literal("脚本信息:"), x, y, 0xAAAAAA);
            y += 12;
            guiGraphics.drawString(font, Component.literal("  名称: " + selectedScript.getName()), x, y, 0xFFFFFF);
            y += 12;
            guiGraphics.drawString(font, Component.literal("  描述: " + selectedScript.getDescription()), x, y, 0xFFFFFF);
            y += 12;
            guiGraphics.drawString(font, Component.literal("  规则数: " + selectedScript.getShotRuleCount()), x, y, 0xFFFFFF);
        }

        // 显示播放状态
        TimelineProcessor processor = TimelineProcessor.getInstance();
        if (minecraft != null && minecraft.player != null) {
            String scriptInfo = processor.getCurrentCameraScriptInfo(minecraft.player);
            if (scriptInfo != null) {
                x = width - 200;
                y = height - 50;
                guiGraphics.drawString(font, Component.literal("播放状态: " + scriptInfo), x, y, 0xFFFF00);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // 更新页面可见性
    private void updatePageVisibility() {
        // 事件监听器页面
        structureSearchInput.visible = currentPage == Page.EVENT_LISTENER;
        scanStructuresButton.visible = currentPage == Page.EVENT_LISTENER;
        for (Button button : modButtons) {
            button.visible = currentPage == Page.EVENT_LISTENER;
        }
        for (Button button : structureButtons) {
            button.visible = currentPage == Page.EVENT_LISTENER;
        }
        for (Button button : eventTypeButtons) {
            button.visible = currentPage == Page.EVENT_LISTENER;
        }

        // 脚本编辑器页面
        scriptNameInput.visible = currentPage == Page.SCRIPT_EDITOR;
        scriptDescriptionInput.visible = currentPage == Page.SCRIPT_EDITOR;
        recordStartStateButton.visible = currentPage == Page.SCRIPT_EDITOR;
        recordEndStateButton.visible = currentPage == Page.SCRIPT_EDITOR;
        for (Button button : pathTypeButtons) {
            button.visible = currentPage == Page.SCRIPT_EDITOR;
        }
        createRouteButton.visible = currentPage == Page.SCRIPT_EDITOR;
        durationInput.visible = currentPage == Page.SCRIPT_EDITOR;
        radiusInput.visible = currentPage == Page.SCRIPT_EDITOR;
        strengthInput.visible = currentPage == Page.SCRIPT_EDITOR;

        // 脚本管理器页面
        for (Button button : scriptButtons) {
            button.visible = currentPage == Page.SCRIPT_MANAGER;
        }
        playScriptButton.visible = currentPage == Page.SCRIPT_MANAGER;
        stopScriptButton.visible = currentPage == Page.SCRIPT_MANAGER;
        deleteScriptButton.visible = currentPage == Page.SCRIPT_MANAGER;
        saveScriptButton.visible = currentPage == Page.SCRIPT_MANAGER;
        loadScriptButton.visible = currentPage == Page.SCRIPT_MANAGER;
    }

    // 更新组件状态
    private void updateWidgetStates() {
        switch (currentPage) {
            case EVENT_LISTENER:
                updateEventListenerPageStates();
                break;
            case SCRIPT_EDITOR:
                updateScriptEditorPageStates();
                break;
            case SCRIPT_MANAGER:
                updateScriptManagerPageStates();
                break;
        }
    }

    private void updateEventListenerPageStates() {
        // 更新模组按钮状态
        for (int i = 0; i < modButtons.size(); i++) {
            Button button = modButtons.get(i);
            if (i < structuresByMod.keySet().size()) {
                String modName = new ArrayList<>(structuresByMod.keySet()).get(i);
                button.setMessage(Component.literal(modName));
                button.active = true;
                button.setFGColor(modName.equals(selectedMod) ? 0xFFFF00 : 0xFFFFFF);
            } else {
                button.setMessage(Component.literal("空"));
                button.active = false;
            }
        }

        // 更新结构按钮状态
        List<ResourceLocation> filteredStructures = new ArrayList<>();
        if (selectedMod != null) {
            filteredStructures = structuresByMod.get(selectedMod);
        }
        for (int i = 0; i < structureButtons.size(); i++) {
            Button button = structureButtons.get(i);
            if (i < filteredStructures.size()) {
                ResourceLocation structureKey = filteredStructures.get(i);
                button.setMessage(Component.literal(StructureScanner.getStructureDisplayName(structureKey)));
                button.active = true;
                button.setFGColor(structureKey.equals(selectedStructure) ? 0xFFFF00 : 0xFFFFFF);
            } else {
                button.setMessage(Component.literal("空"));
                button.active = false;
            }
        }

        // 更新事件类型按钮状态
        for (Button button : eventTypeButtons) {
            for (CameraScript.TriggerType type : CameraScript.TriggerType.values()) {
                if (button.getMessage().getString().equals(getTriggerTypeName(type))) {
                    button.setFGColor(type == selectedEventType ? 0xFFFF00 : 0xFFFFFF);
                    break;
                }
            }
        }
    }

    private void updateScriptEditorPageStates() {
        boolean hasStartState = startState != null;
        boolean hasEndState = endState != null;
        boolean canCreateRoute = hasStartState && hasEndState && selectedPathType != null;

        createRouteButton.active = canCreateRoute;

        for (Button button : pathTypeButtons) {
            for (CameraScript.PathType type : CameraScript.PathType.values()) {
                if (button.getMessage().getString().equals(getPathTypeName(type))) {
                    button.setFGColor(type == selectedPathType ? 0xFFFF00 : 0xFFFFFF);
                    break;
                }
            }
        }
    }

    private void updateScriptManagerPageStates() {
        List<CameraScript> scripts = new ArrayList<>(CameraScriptStorage.getInstance().getAllScripts().values());

        for (int i = 0; i < scriptButtons.size(); i++) {
            Button button = scriptButtons.get(i);
            if (i < scripts.size()) {
                CameraScript script = scripts.get(i);
                button.setMessage(Component.literal(script.getName()));
                button.active = true;
                button.setFGColor(script.equals(selectedScript) ? 0xFFFF00 : 0xFFFFFF);
            } else {
                button.setMessage(Component.literal("空"));
                button.active = false;
            }
        }

        boolean hasSelectedScript = selectedScript != null;
        boolean isScriptActive = minecraft != null && minecraft.player != null &&
                TimelineProcessor.getInstance().isCameraScriptActive(minecraft.player);

        playScriptButton.active = hasSelectedScript && !isScriptActive;
        stopScriptButton.active = isScriptActive;
        deleteScriptButton.active = hasSelectedScript;
        saveScriptButton.active = hasSelectedScript;
        loadScriptButton.active = true;
    }

    // 事件监听器页面方法
    private void onStructureSearch(String text) {
        // TODO: 实现结构搜索功能
    }

    private void onScanStructures() {
        // TODO: 实现服务器端结构扫描
        LOGGER.info("开始扫描结构...");
        availableStructures = new ArrayList<>();
        structuresByMod = ArrayListMultimap.create();
        selectedMod = null;
        selectedStructure = null;
        updateEventListenerPageStates();
    }

    private void onModSelected(int index) {
        List<String> mods = new ArrayList<>(structuresByMod.keySet());
        if (index < mods.size()) {
            selectedMod = mods.get(index);
            selectedStructure = null;
            updateEventListenerPageStates();
        }
    }

    private void onStructureSelected(int index) {
        if (selectedMod != null) {
            List<ResourceLocation> structures = structuresByMod.get(selectedMod);
            if (index < structures.size()) {
                selectedStructure = structures.get(index);
                updateEventListenerPageStates();
            }
        }
    }

    private void onEventTypeSelected(CameraScript.TriggerType type) {
        selectedEventType = type;
        updateEventListenerPageStates();
    }

    // 脚本编辑器页面方法
    private void onScriptNameChanged(String text) {
        if (selectedScript == null) {
            selectedScript = new CameraScript(text, "");
        } else {
            selectedScript.setName(text);
        }
    }

    private void onScriptDescriptionChanged(String text) {
        if (selectedScript != null) {
            selectedScript.setDescription(text);
        }
    }

    private void onRecordStartState() {
        startState = CameraStateRecorder.recordCurrentState();
        LOGGER.info("记录起始状态: {}", startState);
    }

    private void onRecordEndState() {
        endState = CameraStateRecorder.recordCurrentState();
        LOGGER.info("记录结束状态: {}", endState);
    }

    private void onPathTypeSelected(CameraScript.PathType type) {
        selectedPathType = type;
        updateScriptEditorPageStates();
    }

    private void onCreateRoute() {
        if (startState != null && endState != null && selectedPathType != null) {
            if (selectedScript == null) {
                selectedScript = new CameraScript("未命名脚本", "");
            }

            // 创建运镜规则
            if (selectedScript.getShotRules().isEmpty()) {
                selectedScript.addShotRule(new CameraScript.ShotRule("默认规则"));
            }

            // 创建运镜路线
            CameraScript.MovementRoute route = CameraStateRecorder.createRouteFromStates(startState, endState, selectedPathType);
            route.setDuration(Double.parseDouble(durationInput.getValue()));
            if (selectedPathType == CameraScript.PathType.ORBIT || selectedPathType == CameraScript.PathType.SPIRAL) {
                route.setRadius(Double.parseDouble(radiusInput.getValue()));
            }
            if (selectedPathType == CameraScript.PathType.DOLLY_ZOOM) {
                route.setStrength(Double.parseDouble(strengthInput.getValue()));
            }

            selectedScript.getShotRules().get(0).addRoute(route);
            LOGGER.info("创建运镜路线: {}", route);

            // 重置记录状态
            startState = null;
            endState = null;
        }
    }

    // 脚本管理器页面方法
    private void onScriptSelected(int index) {
        List<CameraScript> scripts = new ArrayList<>(CameraScriptStorage.getInstance().getAllScripts().values());
        if (index < scripts.size()) {
            selectedScript = scripts.get(index);
            if (selectedScript != null) {
                scriptNameInput.setValue(selectedScript.getName());
                scriptDescriptionInput.setValue(selectedScript.getDescription());
            }
            updateScriptManagerPageStates();
        }
    }

    private void onPlayScript() {
        if (selectedScript != null && minecraft != null && minecraft.player != null) {
            TimelineProcessor.getInstance().startCameraScript(minecraft.player, selectedScript.getName());
        }
    }

    private void onStopScript() {
        if (minecraft != null && minecraft.player != null) {
            TimelineProcessor.getInstance().stopCameraScript(minecraft.player, true);
        }
    }

    private void onDeleteScript() {
        if (selectedScript != null) {
            CameraScriptStorage.getInstance().deleteScript(selectedScript.getName());
            CameraScriptStorage.getInstance().hotReload();
            selectedScript = null;
            updateScriptManagerPageStates();
        }
    }

    private void onSaveScript() {
        if (selectedScript != null) {
            CameraScriptStorage.getInstance().saveScript(selectedScript.getName(), selectedScript);
            CameraScriptStorage.getInstance().hotReload();
            LOGGER.info("脚本保存成功: {}", selectedScript.getName());
        }
    }

    private void onLoadScript() {
        // 刷新脚本列表
        CameraScriptStorage.getInstance().hotReload();
        updateScriptManagerPageStates();
    }

    // 辅助方法
    private String getPathTypeName(CameraScript.PathType pathType) {
        switch (pathType) {
            case DIRECT:
                return "直接线性";
            case SMOOTH:
                return "平滑曲线";
            case ORBIT:
                return "轨道运动";
            case BEZIER:
                return "贝塞尔曲线";
            case SPIRAL:
                return "螺旋运动";
            case DOLLY_ZOOM:
                return "滑动变焦";
            case STATIONARY_PAN:
                return "静态旋转";
            default:
                return "未知";
        }
    }

    private String getTriggerTypeName(CameraScript.TriggerType triggerType) {
        switch (triggerType) {
            case DIMENSION_CHANGE:
                return "维度切换";
            case STRUCTURE_ENTER:
                return "进入结构";
            case STRUCTURE_EXIT:
                return "离开结构";
            case PLAYER_LOGIN:
                return "玩家登录";
            case TIME_OF_DAY:
                return "时间触发";
            case BLOCK_BREAK:
                return "破坏方块";
            case ENTITY_KILL:
                return "击杀实体";
            default:
                return "未知";
        }
    }
}