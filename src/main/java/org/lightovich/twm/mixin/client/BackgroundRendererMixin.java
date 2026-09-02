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

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.FogShape;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.ClientParty;

/**
 * Слепота рук: мир виден только вплотную, дальше — чернота.
 *
 * <p>Прототип на NeoForge получал то же самое событиями {@code ViewportEvent.RenderFog} и
 * {@code ComputeFogColor}; на Fabric событий фога нет, поэтому дистанция и цвет задаются
 * прямо в конце ванильного расчёта. Одной дистанции мало: цвет тумана по умолчанию берётся
 * от неба, и без затемнения руки упирались бы в светлую голубую стену вместо темноты.
 */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {

    /** Полная темнота начинается здесь. Ровно то же значение, что было у прототипа. */
    private static final float TWM$SIGHT_RANGE = 5.0F;
    /** Начало затухания: у самого лица картинка должна оставаться чистой. */
    private static final float TWM$SIGHT_FADE_START = 0.75F;

    @Shadow
    private static float red;
    @Shadow
    private static float green;
    @Shadow
    private static float blue;

    @Inject(method = "render", at = @At("TAIL"))
    private static void twm$blackenFogForHands(Camera camera, float tickDelta, ClientWorld world,
                                               int viewDistance, float skyDarkness, CallbackInfo info) {
        if (!ClientParty.isHands()) {
            return;
        }
        red = 0.0F;
        green = 0.0F;
        blue = 0.0F;
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
    }

    @Inject(method = "applyFog", at = @At("TAIL"))
    private static void twm$limitSightForHands(Camera camera, BackgroundRenderer.FogType fogType,
                                               float viewDistance, boolean thickFog, float tickDelta,
                                               CallbackInfo info) {
        if (!ClientParty.isHands()) {
            return;
        }
        RenderSystem.setShaderFogStart(TWM$SIGHT_FADE_START);
        RenderSystem.setShaderFogEnd(TWM$SIGHT_RANGE);
        // Сфера, а не цилиндр: слепота одинакова во все стороны, включая вверх и вниз.
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }
}
