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

import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import org.lightovich.twm.client.ClientParty;
import org.lightovich.twm.client.TwmSettings;

/**
 * Рисует эхолокацию ног: чёрный экран и белые контуры запомненных поверхностей.
 *
 * <p>Проекция считается настоящей матрицей вида-проекции кадра, а не самодельной формулой.
 * В прошлой версии вектор «вправо» был выведен вручную и оказался зеркальным, из-за чего
 * лево и право менялись местами; с матрицей игры такая ошибка невозможна в принципе.
 */
public final class EchoRenderer {

    private static final int[] DOWN_FACE = {0, 1, 5, 4};
    private static final int[] UP_FACE = {2, 3, 7, 6};
    private static final int[] NORTH_FACE = {0, 1, 3, 2};
    private static final int[] SOUTH_FACE = {4, 5, 7, 6};
    private static final int[] WEST_FACE = {0, 2, 6, 4};
    private static final int[] EAST_FACE = {1, 3, 7, 5};

    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static Vec3d cameraPosition = Vec3d.ZERO;
    private static boolean hasFrame;

    private static final Vector4f SCRATCH = new Vector4f();
    private static final float[] PROJECTED_X = new float[8];
    private static final float[] PROJECTED_Y = new float[8];
    private static final boolean[] PROJECTED_VALID = new boolean[8];

    private EchoRenderer() {
    }

    /** Матрицы кадра запоминаются в мировом проходе: в проходе интерфейса они уже ортографические. */
    public static void captureFrame(WorldRenderContext context) {
        if (!ClientParty.isLegs()) {
            hasFrame = false;
            return;
        }
        VIEW_PROJECTION.set(context.projectionMatrix()).mul(context.positionMatrix());
        cameraPosition = context.camera().getPos();
        hasFrame = true;
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        context.fill(0, 0, width, height, 0xFF000000);
        if (!hasFrame || client.player == null) {
            return;
        }

        Matrix4f pose = context.getMatrices().peek().getPositionMatrix();
        BufferBuilder lines = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        // Цвет и яркость — настройка читаемости, а не правил: дальность и время жизни эха
        // задаются самой механикой, иначе настройками можно было бы облегчить себе игру.
        int rgb = TwmSettings.echoRgb();
        float brightness = TwmSettings.echo().brightness();

        for (Map.Entry<BlockPos, Echolocation.Echo> entry : Echolocation.memory().entrySet()) {
            float alpha = Echolocation.alphaOf(entry.getValue()) * brightness;
            if (alpha <= 0.0F) {
                continue;
            }
            drawBlock(lines, pose, entry.getKey(), entry.getValue().faces(),
                    (int) (alpha * 255.0F) << 24 | rgb, width, height);
        }

        // Пустой кадр — обычное дело: памяти ещё нет, всё за спиной или всё за экраном.
        // end() на пустом буфере роняет игру, поэтому здесь только endNullable().
        BuiltBuffer built = lines.endNullable();
        if (built == null) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(TwmSettings.echo().lineWidth());
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.lineWidth(1.0F);
        RenderSystem.disableBlend();
    }

    /**
     * Быстрый отсев по центру блока: одно преобразование вместо восьми.
     *
     * <p>Память эхолокации держит тысячи блоков, и считать все восемь углов для каждого — это
     * сотни тысяч матричных операций на кадр. Так проверка стоит одну восьмую, а до полного
     * расчёта доживают только блоки, реально попадающие в кадр. Допуск растёт с приближением:
     * блок в упор занимает пол-экрана, даже когда его центр за краем.
     */
    private static boolean isPotentiallyVisible(BlockPos position, int width, int height) {
        SCRATCH.set((float) (position.getX() + 0.5D - cameraPosition.x),
                (float) (position.getY() + 0.5D - cameraPosition.y),
                (float) (position.getZ() + 0.5D - cameraPosition.z), 1.0F);
        VIEW_PROJECTION.transform(SCRATCH);
        if (SCRATCH.w <= 1.0E-4F) {
            return false;
        }
        float x = (SCRATCH.x / SCRATCH.w * 0.5F + 0.5F) * width;
        float y = (0.5F - SCRATCH.y / SCRATCH.w * 0.5F) * height;
        float margin = Math.min(width, width * 2.0F / Math.max(1.0F, SCRATCH.w));
        return x >= -margin && x <= width + margin && y >= -margin && y <= height + margin;
    }

    private static void drawBlock(BufferBuilder lines, Matrix4f pose, BlockPos position, int faces,
                                  int color, int width, int height) {
        if (!isPotentiallyVisible(position, width, height)) {
            return;
        }
        for (int corner = 0; corner < 8; corner++) {
            double x = position.getX() + ((corner & 1) == 0 ? 0.0D : 1.0D) - cameraPosition.x;
            double y = position.getY() + ((corner & 2) == 0 ? 0.0D : 1.0D) - cameraPosition.y;
            double z = position.getZ() + ((corner & 4) == 0 ? 0.0D : 1.0D) - cameraPosition.z;
            SCRATCH.set((float) x, (float) y, (float) z, 1.0F);
            VIEW_PROJECTION.transform(SCRATCH);
            if (SCRATCH.w <= 1.0E-4F) {
                PROJECTED_VALID[corner] = false;
                continue;
            }
            PROJECTED_VALID[corner] = true;
            PROJECTED_X[corner] = (SCRATCH.x / SCRATCH.w * 0.5F + 0.5F) * width;
            PROJECTED_Y[corner] = (0.5F - SCRATCH.y / SCRATCH.w * 0.5F) * height;
        }

        for (Direction direction : Direction.values()) {
            if ((faces & 1 << direction.ordinal()) == 0 || !facesCamera(position, direction)) {
                continue;
            }
            int[] face = corners(direction);
            for (int edge = 0; edge < face.length; edge++) {
                int start = face[edge];
                int end = face[(edge + 1) % face.length];
                if (!PROJECTED_VALID[start] || !PROJECTED_VALID[end]) {
                    continue;
                }
                lines.vertex(pose, PROJECTED_X[start], PROJECTED_Y[start], 0.0F).color(color);
                lines.vertex(pose, PROJECTED_X[end], PROJECTED_Y[end], 0.0F).color(color);
            }
        }
    }

    /** Обратные грани не рисуются: иначе каждый блок выглядит проволочным кубом, а не поверхностью. */
    private static boolean facesCamera(BlockPos position, Direction direction) {
        double faceX = position.getX() + 0.5D + direction.getOffsetX() * 0.5D;
        double faceY = position.getY() + 0.5D + direction.getOffsetY() * 0.5D;
        double faceZ = position.getZ() + 0.5D + direction.getOffsetZ() * 0.5D;
        return (cameraPosition.x - faceX) * direction.getOffsetX()
                + (cameraPosition.y - faceY) * direction.getOffsetY()
                + (cameraPosition.z - faceZ) * direction.getOffsetZ() > 0.0D;
    }

    private static int[] corners(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN_FACE;
            case UP -> UP_FACE;
            case NORTH -> NORTH_FACE;
            case SOUTH -> SOUTH_FACE;
            case WEST -> WEST_FACE;
            case EAST -> EAST_FACE;
        };
    }
}
