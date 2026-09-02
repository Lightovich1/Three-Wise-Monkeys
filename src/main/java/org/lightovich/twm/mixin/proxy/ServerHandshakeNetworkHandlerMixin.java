/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.mixin.proxy;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.handshake.ConnectionIntent;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.server.network.ServerHandshakeNetworkHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.server.TwmConfig;
import org.lightovich.twm.server.proxy.ForwardedHolder;
import org.lightovich.twm.server.proxy.ForwardedLogin;
import org.lightovich.twm.server.proxy.ProxyForwarding;

/**
 * Снимает с рукопожатия данные, приложенные прокси, и вешает их на соединение.
 *
 * <p>Разбор молчит, когда данных нет: решение «пускать или нет» принимает логин. Здесь его
 * принять нельзя — на рукопожатии соединение ещё не в том состоянии, в котором клиенту можно
 * отправить причину отказа, и игрок увидел бы разрыв без объяснения.
 *
 * <p>Пинг сервера (состояние STATUS) не трогается вовсе: списку серверов ни адрес игрока, ни
 * его профиль не нужны.
 */
@Mixin(ServerHandshakeNetworkHandler.class)
public abstract class ServerHandshakeNetworkHandlerMixin {

    @Shadow
    @Final
    private ClientConnection connection;

    @Inject(method = "onHandshake", at = @At("HEAD"))
    private void twm$captureForwardedLogin(HandshakeC2SPacket packet, CallbackInfo info) {
        if (!TwmConfig.get().proxyEnabled() || packet.intendedState() != ConnectionIntent.LOGIN) {
            return;
        }
        ForwardedLogin forwarded = ProxyForwarding.parse(packet.address(), this.connection.getAddress());
        if (forwarded != null) {
            ((ForwardedHolder) this.connection).twm$applyForwarded(forwarded);
        }
    }
}
