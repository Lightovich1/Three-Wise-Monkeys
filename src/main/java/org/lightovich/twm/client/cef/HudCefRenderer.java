/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client.cef;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

/** Рисует HUD-браузер прозрачным слоем поверх игры. Мышь не перехватывается. */
public final class HudCefRenderer {

    /**
     * Слой браузера уезжает вперёд по глубине. У ног экран заполняет чёрная заливка эхолокации,
     * и на одном {@code z} два квада начинают спорить за пиксели — именно это выглядело как
     * мерцающий и затемнённый HUD.
     */
    private static final float LAYER_DEPTH = 250.0F;

    private static int lastWidth = -1;
    private static int lastHeight = -1;

    private HudCefRenderer() {
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Команда для HUD больше не обязательна: подбор, лобби и уведомления живут и вне игры.
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        HudCefManager.ensureInitialized();

        int framebufferWidth = client.getWindow().getFramebufferWidth();
        int framebufferHeight = client.getWindow().getFramebufferHeight();
        if (framebufferWidth != lastWidth || framebufferHeight != lastHeight) {
            HudCefManager.resize(framebufferWidth, framebufferHeight);
            lastWidth = framebufferWidth;
            lastHeight = framebufferHeight;
        }

        int texture = HudCefManager.textureId();
        if (texture <= 0) {
            return;
        }

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, LAYER_DEPTH);

        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        CefBlend.premultiplied();
        RenderSystem.disableDepthTest();

        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        BufferBuilder buffer = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(matrix, 0.0F, 0.0F, 0.0F).texture(0.0F, 0.0F);
        buffer.vertex(matrix, 0.0F, height, 0.0F).texture(0.0F, 1.0F);
        buffer.vertex(matrix, width, height, 0.0F).texture(1.0F, 1.0F);
        buffer.vertex(matrix, width, 0.0F, 0.0F).texture(1.0F, 0.0F);
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        // Состояние возвращается полностью: следом рисуется ванильный интерфейс рук и головы,
        // и оставленный чужой режим смешивания портит хотбар и полоски.
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        context.getMatrices().pop();
    }

    /** Общий для обоих браузеров режим смешивания. */
    static final class CefBlend {

        private CefBlend() {
        }

        /**
         * CEF отдаёт кадр с premultiplied alpha. Обычный {@code defaultBlendFunc} умножает цвет
         * на альфу второй раз, и полупрозрачные панели выглядят вдвое темнее задуманного —
         * ровно та жалоба, с которой начался разбор.
         */
        static void premultiplied() {
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
        }
    }
}
