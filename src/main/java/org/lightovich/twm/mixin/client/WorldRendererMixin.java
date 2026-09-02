/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.ClientParty;

/**
 * Небо рукам не рисуется вообще.
 *
 * <p>Небесный купол, солнце и звёзды рисуются без учёта дальности тумана, поэтому одного
 * ограничения обзора мало: над горизонтом оставалась бы светлая полоса, по которой слепой
 * читает время суток и стороны света. Фон и так очищается в чёрный
 * ({@code BackgroundRendererMixin}), так что отмены достаточно.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void twm$hideSkyFromHands(Matrix4f positionMatrix, Matrix4f projectionMatrix,
                                      float tickDelta, Camera camera, boolean thickFog,
                                      Runnable fogCallback, CallbackInfo info) {
        if (ClientParty.isHands()) {
            info.cancel();
        }
    }
}
