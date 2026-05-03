package com.immersivecinematics.immersive_cinematics.client;

import com.immersivecinematics.immersive_cinematics.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class ConfigScreen extends Screen {

    private static final int OPTION_WIDTH = 200;

    private final Screen parent;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("config.immersive_cinematics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int midX = width / 2;
        int y = height / 6;

        addRenderableWidget(Button.builder(
                Component.translatable("config.immersive_cinematics.showSkipHud",
                        toggleStr(Config.showSkipHud)),
                btn -> {
                    Config.setShowSkipHud(!Config.showSkipHud);
                    btn.setMessage(Component.translatable("config.immersive_cinematics.showSkipHud",
                            toggleStr(Config.showSkipHud)));
                })
                .bounds(midX - OPTION_WIDTH / 2, y, OPTION_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("config.immersive_cinematics.showSkipHud.tooltip")))
                .build());
        y += 28;

        addRenderableWidget(Button.builder(
                Component.translatable("config.immersive_cinematics.debugLogging",
                        toggleStr(Config.debugLogging)),
                btn -> {
                    Config.setDebugLogging(!Config.debugLogging);
                    btn.setMessage(Component.translatable("config.immersive_cinematics.debugLogging",
                            toggleStr(Config.debugLogging)));
                })
                .bounds(midX - OPTION_WIDTH / 2, y, OPTION_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("config.immersive_cinematics.debugLogging.tooltip")))
                .build());
        y += 28;

        y += 10;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> onClose())
                .bounds(midX - OPTION_WIDTH / 2, y, OPTION_WIDTH, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }

    private static String toggleStr(boolean v) {
        return v ? "§aON" : "§7OFF";
    }
}
