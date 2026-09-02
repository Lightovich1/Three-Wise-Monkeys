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

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.TwmClient;

/**
 * Смена сервера внутри сети прокси.
 *
 * <p>Прокси переключает бэкенд, не разрывая соединение: клиент получает новый пакет входа в
 * игру, а событие отключения не приходит вовсе. Состояние мода при этом остаётся от прежнего
 * сервера — и запертое меню прежнего сервера запирало игрока уже на новом, где мода нет и
 * снять запрет некому.
 *
 * <p>Поэтому сброс висит на самом пакете входа: он приходит и при первом входе, и при каждом
 * переключении, а событиям Fabric переключение бэкенда не видно. На возрождение и смену
 * измерения пакет не приходит — забег в арене сброс не задевает.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameJoin", at = @At("HEAD"))
    private void twm$resetOnServerChange(GameJoinS2CPacket packet, CallbackInfo info) {
        TwmClient.resetForNewServer();
    }
}
