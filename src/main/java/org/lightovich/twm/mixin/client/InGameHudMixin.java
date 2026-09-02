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

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.ClientParty;
import org.lightovich.twm.client.cef.HudCefRenderer;
import org.lightovich.twm.client.perception.EchoRenderer;

/**
 * Интерфейс раздан по ролям.
 *
 * <p>Ноги: обычного интерфейса нет вообще: ни руки, ни хотбара, ни прицела. Замена делается на
 * границе всего интерфейса, а не отдельными слоями — иначе поздние ванильные слои всплывают
 * поверх чёрного экрана.
 *
 * <p>Руки и голова делят состояние одного тела: руки держат предметы и потому видят хотбар,
 * а состояние тела (здоровье, броня, сытость, опыт) читает голова — она единственная, кто
 * вообще видит. Слепой не смотрит на свои полоски, он спрашивает.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void twm$replaceHudForLegs(DrawContext context, RenderTickCounter tickCounter, CallbackInfo info) {
        if (ClientParty.isLegs()) {
            EchoRenderer.render(context);
            // Обычный HUD отменён целиком, поэтому веб-слой ног рисуется здесь же:
            // через HudRenderCallback он бы не дошёл.
            HudCefRenderer.render(context);
            info.cancel();
        }
    }

    /** Полоски состояния тела — только голове: здоровье, броня, сытость, воздух. */
    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void twm$hideStatusBarsFromHands(DrawContext context, CallbackInfo info) {
        if (ClientParty.isHands()) {
            info.cancel();
        }
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void twm$hideExperienceBarFromHands(DrawContext context, int x, CallbackInfo info) {
        if (ClientParty.isHands()) {
            info.cancel();
        }
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void twm$hideExperienceLevelFromHands(DrawContext context, RenderTickCounter tickCounter,
                                                  CallbackInfo info) {
        if (ClientParty.isHands()) {
            info.cancel();
        }
    }

    /** Хотбар — только рукам: предметы держат они, голова ими не распоряжается. */
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void twm$hideHotbarFromHead(DrawContext context, RenderTickCounter tickCounter,
                                        CallbackInfo info) {
        if (ClientParty.isHead()) {
            info.cancel();
        }
    }
}
