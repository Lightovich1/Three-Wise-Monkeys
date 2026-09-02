/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client.perception;

import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import org.lightovich.twm.client.ClientParty;
import org.lightovich.twm.client.TwmSettings;

/**
 * Кольцо услышанного вокруг прицела рук.
 *
 * <p>Дуга стоит там, где источник, и остаётся там, пока игрок вертит головой: пеленг хранится
 * мировым, экранный угол считается каждый кадр. Толщина — дальность, цвет и значок — что это
 * было. Само кольцо не рисуется: постоянная окружность перед глазами превращается в прицел,
 * которому веришь, а слух прерывист по своей природе.
 */
public final class SoundRadarRenderer {

    /** Полуширина дуги: ближний звук занимает больше — направление на него точнее. */
    private static final float NEAR_HALF_ANGLE = 13.0F;
    private static final float FAR_HALF_ANGLE = 5.5F;

    private static final float NEAR_THICKNESS = 5.0F;
    private static final float FAR_THICKNESS = 1.6F;

    /** Дуга собирается отрезками по столько градусов: меньше — лишние вершины, больше — грани. */
    private static final float ARC_STEP = 3.0F;

    /** Появление быстрое, угасание долгое: щелчок слышен мгновенно, память о нём тает. */
    private static final float FADE_IN = 0.12F;

    private static final float ICON_GAP = 7.0F;
    private static final float ICON_SIZE = 3.6F;

    /**
     * Цвета читаются как данные: тусклый бумажный — шаг, плотный бумажный — работа с блоками,
     * охра — живое, вермильон — угроза. Та же тройка, что во всём интерфейсе мода.
     */
    private static final int STEP_COLOR = 0xB8AFA2;
    private static final int BLOCK_COLOR = 0xEDE7DC;
    private static final int MOB_COLOR = 0xE8CF9A;
    private static final int COMBAT_COLOR = 0xC8452F;

    /** Значки в единичном квадрате: пары точек, из которых складываются отрезки. */
    private static final float[] STEP_ICON = {
            -0.75F, -0.85F, -0.75F, -0.05F,
            0.55F, 0.05F, 0.55F, 0.85F
    };
    private static final float[] BLOCK_ICON = {
            -0.8F, -0.8F, 0.8F, -0.8F,
            0.8F, -0.8F, 0.8F, 0.8F,
            0.8F, 0.8F, -0.8F, 0.8F,
            -0.8F, 0.8F, -0.8F, -0.8F
    };
    private static final float[] MOB_ICON = {
            0.0F, -0.9F, 0.9F, 0.75F,
            0.9F, 0.75F, -0.9F, 0.75F,
            -0.9F, 0.75F, 0.0F, -0.9F
    };
    private static final float[] COMBAT_ICON = {
            0.0F, -1.0F, 0.0F, 1.0F,
            -0.87F, -0.5F, 0.87F, 0.5F,
            -0.87F, 0.5F, 0.87F, -0.5F
    };

    private SoundRadarRenderer() {
    }

    public static void render(DrawContext context, float tickDelta) {
        if (!ClientParty.isHands() || !ClientParty.difficulty().hasSoundVisualization()
                || !TwmSettings.radar().enabled()) {
            return;
        }
        List<SoundRadar.Blip> blips = SoundRadar.blips();
        if (blips.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        float cameraYaw = client.gameRenderer.getCamera().getYaw();
        float centerX = context.getScaledWindowWidth() / 2.0F;
        float centerY = context.getScaledWindowHeight() / 2.0F;
        float radius = TwmSettings.radar().radius();
        float brightness = TwmSettings.radar().brightness();
        Matrix4f pose = context.getMatrices().peek().getPositionMatrix();

        BufferBuilder arcs = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (SoundRadar.Blip blip : blips) {
            float life = blip.life(tickDelta);
            float alpha = alphaOf(life) * brightness;
            if (alpha <= 0.0F) {
                continue;
            }
            float near = nearness(blip.distance());
            float angle = MathHelper.wrapDegrees(blip.yaw() - cameraYaw);
            int color = (int) (alpha * 255.0F) << 24 | colorOf(blip.kind());
            arc(arcs, pose, centerX, centerY, radius,
                    MathHelper.lerp(near, FAR_THICKNESS, NEAR_THICKNESS),
                    angle, MathHelper.lerp(near, FAR_HALF_ANGLE, NEAR_HALF_ANGLE), color);
        }

        BuiltBuffer builtArcs = arcs.endNullable();
        if (builtArcs == null) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(builtArcs);

        BufferBuilder marks = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        for (SoundRadar.Blip blip : blips) {
            float life = blip.life(tickDelta);
            float alpha = alphaOf(life) * brightness;
            if (alpha <= 0.0F) {
                continue;
            }
            float near = nearness(blip.distance());
            float angle = MathHelper.wrapDegrees(blip.yaw() - cameraYaw);
            float thickness = MathHelper.lerp(near, FAR_THICKNESS, NEAR_THICKNESS);
            float markRadius = radius + thickness / 2.0F + ICON_GAP;
            int color = (int) (alpha * 255.0F) << 24 | colorOf(blip.kind());
            double radians = Math.toRadians(angle);
            icon(marks, pose, iconOf(blip.kind()),
                    centerX + (float) Math.sin(radians) * markRadius,
                    centerY - (float) Math.cos(radians) * markRadius,
                    ICON_SIZE, color);
        }
        BuiltBuffer builtMarks = marks.endNullable();
        if (builtMarks != null) {
            RenderSystem.lineWidth(1.6F);
            BufferRenderer.drawWithGlobalProgram(builtMarks);
            RenderSystem.lineWidth(1.0F);
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Ближе — ярче и толще. Единица в упор, ноль на пределе слышимости. */
    private static float nearness(float distance) {
        return 1.0F - MathHelper.clamp(distance / (float) SoundRadar.maxDistance(), 0.0F, 1.0F);
    }

    private static float alphaOf(float life) {
        float appeared = 1.0F - life;
        if (appeared < FADE_IN) {
            return appeared / FADE_IN;
        }
        return life;
    }

    private static void arc(BufferBuilder buffer, Matrix4f pose, float centerX, float centerY,
                            float radius, float thickness, float angle, float halfAngle, int color) {
        float inner = radius - thickness / 2.0F;
        float outer = radius + thickness / 2.0F;
        int steps = Math.max(2, Math.round(halfAngle * 2.0F / ARC_STEP));
        for (int step = 0; step < steps; step++) {
            float from = angle - halfAngle + halfAngle * 2.0F * step / steps;
            float to = angle - halfAngle + halfAngle * 2.0F * (step + 1) / steps;
            vertex(buffer, pose, centerX, centerY, inner, from, color);
            vertex(buffer, pose, centerX, centerY, outer, from, color);
            vertex(buffer, pose, centerX, centerY, outer, to, color);
            vertex(buffer, pose, centerX, centerY, inner, to, color);
        }
    }

    /** Ноль градусов — вверх, дальше по часовой: так же, как игрок поворачивается вправо. */
    private static void vertex(BufferBuilder buffer, Matrix4f pose, float centerX, float centerY,
                               float radius, float angle, int color) {
        double radians = Math.toRadians(angle);
        buffer.vertex(pose, centerX + (float) Math.sin(radians) * radius,
                centerY - (float) Math.cos(radians) * radius, 0.0F).color(color);
    }

    private static void icon(BufferBuilder buffer, Matrix4f pose, float[] shape,
                             float x, float y, float size, int color) {
        for (int index = 0; index < shape.length; index += 4) {
            buffer.vertex(pose, x + shape[index] * size, y + shape[index + 1] * size, 0.0F).color(color);
            buffer.vertex(pose, x + shape[index + 2] * size, y + shape[index + 3] * size, 0.0F).color(color);
        }
    }

    private static int colorOf(SoundRadar.Kind kind) {
        return switch (kind) {
            case STEP -> STEP_COLOR;
            case BLOCK -> BLOCK_COLOR;
            case MOB -> MOB_COLOR;
            case COMBAT -> COMBAT_COLOR;
        };
    }

    private static float[] iconOf(SoundRadar.Kind kind) {
        return switch (kind) {
            case STEP -> STEP_ICON;
            case BLOCK -> BLOCK_ICON;
            case MOB -> MOB_ICON;
            case COMBAT -> COMBAT_ICON;
        };
    }
}
