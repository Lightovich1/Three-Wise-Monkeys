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

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.CameraController;

/**
 * Пока камера сцеплена с ногами, мышь до неё не доходит вообще.
 *
 * <p>Гасить поворот в конце тика недостаточно: внутри тика мышь уже успевает развернуть камеру,
 * и получается перетягивание — игрок ведёт мышью, мод возвращает обратно, картинка дрожит.
 * Ввод обрывается на входе, а свободный обзор включает удержание ALT.
 */
@Mixin(Entity.class)
public abstract class EntityLookMixin {

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void twm$blockLookWhileLocked(double deltaX, double deltaY, CallbackInfo info) {
        Entity self = (Entity) (Object) this;
        if (self == MinecraftClient.getInstance().player && CameraController.isLookLocked()) {
            info.cancel();
        }
    }
}
