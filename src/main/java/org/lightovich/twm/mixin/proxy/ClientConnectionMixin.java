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

import java.net.SocketAddress;

import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import org.lightovich.twm.server.proxy.ForwardedHolder;
import org.lightovich.twm.server.proxy.ForwardedLogin;

/**
 * Соединение помнит форвард прокси от рукопожатия до логина и носит настоящий адрес игрока.
 *
 * <p>Адрес подменяется здесь, а не на логине: до логина соединение успевает попасть в логи,
 * в фильтры и в баны, и адрес прокси в них означал бы, что все игроки пришли с одного IP.
 */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin implements ForwardedHolder {

    @Mutable
    @Shadow
    private SocketAddress address;

    @Unique
    private ForwardedLogin twm$forwarded;

    @Override
    public ForwardedLogin twm$forwarded() {
        return this.twm$forwarded;
    }

    @Override
    public void twm$applyForwarded(ForwardedLogin forwarded) {
        this.twm$forwarded = forwarded;
        this.address = forwarded.address();
    }
}
