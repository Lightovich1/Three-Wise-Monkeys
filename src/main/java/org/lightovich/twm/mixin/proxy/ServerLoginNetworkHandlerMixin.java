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

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
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
 * Логин через прокси: игрок входит с тем uuid и скином, которые дал прокси, а не с
 * оффлайн-профилем, посчитанным от ника.
 *
 * <p>Без этого один и тот же человек получал бы на этом сервере другой uuid, чем на
 * остальных серверах сети, — то есть чужой инвентарь, чужие права и чужую статистику.
 *
 * <p>При включённом форвардинге соединение без форварда не проходит. Это и есть защита
 * бэкенда: прямой коннект мимо прокси — способ назваться кем угодно, а вместе с проверкой
 * секрета ещё и подделать сам форвард уже нельзя.
 */
@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow
    @Final
    ClientConnection connection;

    @Shadow
    String profileName;

    @Shadow
    abstract void startVerify(GameProfile profile);

    @Shadow
    public abstract void disconnect(Text reason);

    @Inject(method = "onHello", at = @At("HEAD"), cancellable = true)
    private void twm$loginThroughProxy(LoginHelloC2SPacket packet, CallbackInfo info) {
        if (!TwmConfig.get().proxyEnabled() || this.connection.isLocal()) {
            return;
        }
        ForwardedLogin forwarded = ((ForwardedHolder) this.connection).twm$forwarded();
        if (forwarded == null) {
            info.cancel();
            this.disconnect(Text.literal(TwmConfig.get().proxyNoDataMessage()));
            return;
        }
        ProxyForwarding.logForwarded(forwarded, packet.name());
        if (!ProxyForwarding.tokenValid(forwarded)) {
            info.cancel();
            this.disconnect(Text.literal(TwmConfig.get().proxyBadTokenMessage()));
            return;
        }
        info.cancel();
        this.profileName = packet.name();
        GameProfile profile = new GameProfile(forwarded.uuid(), packet.name());
        profile.getProperties().putAll(forwarded.properties());
        this.startVerify(profile);
    }
}
