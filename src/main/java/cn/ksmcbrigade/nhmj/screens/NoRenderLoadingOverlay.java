package cn.ksmcbrigade.nhmj.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;

import java.util.Optional;
import java.util.function.Consumer;

public class NoRenderLoadingOverlay extends LoadingOverlay {
    public NoRenderLoadingOverlay(ReloadInstance reload, Consumer<Optional<Throwable>> onFinish, boolean fadeIn) {
        super(Minecraft.getInstance(), reload, onFinish, fadeIn);
    }

    @Override
    public void render(GuiGraphics p_281839_, int p_282704_, int p_283650_, float p_283394_) {}

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void drawProgressBar(GuiGraphics guiGraphics, int minX, int minY, int maxX, int maxY, float partialTick) {}
}
