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

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.ClientParty;

/**
 * Пассивные роли не сообщают серверу свою позицию.
 *
 * <p>Их положение задаёт сервер по движению ног. Если клиент продолжает слать собственные
 * пакеты позиции, сервер принимает устаревшую точку и тело каждый тик прыгает между двумя
 * координатами — прошлая версия боролась с этим, перезаписывая позицию в том же тике.
 * Проще не создавать второй источник движения вовсе.
 *
 * <p>Поворот руки отправляют по-прежнему: от него зависит направление взаимодействия,
 * которое сервер обязан знать.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

    @Inject(method = "sendMovementPackets", at = @At("HEAD"), cancellable = true)
    private void twm$suppressPositionPackets(CallbackInfo info) {
        if (!ClientParty.isHands() && !ClientParty.isHead()) {
            return;
        }
        info.cancel();
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (ClientParty.isHands()) {
            self.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    self.getYaw(), self.getPitch(), self.isOnGround()));
        }
    }
}
