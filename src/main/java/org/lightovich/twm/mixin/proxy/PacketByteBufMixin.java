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

import net.minecraft.network.PacketByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import org.lightovich.twm.server.TwmConfig;
import org.lightovich.twm.server.proxy.ProxyForwarding;

/**
 * Снимает предел длины адреса в рукопожатии, пока включён форвардинг прокси.
 *
 * <p>Прокси кладёт в поле адреса uuid, свойства профиля и секрет — это сотни байт, а ваниль
 * читает адрес как {@code readString(255)} и рвёт соединение на декоде пакета, ещё до того,
 * как мод увидит рукопожатие. Поднимать предел в самом пакете нельзя: константа живёт в его
 * конструкторе, а туда миксин не встаёт — {@code this} там ещё не построен.
 *
 * <p>Поэтому правится сам {@code readString}: предел 255 поднимается до предела строки в
 * пакете вообще. Условие узкое намеренно — только при включённом форвардинге и только там,
 * где ваниль просила ровно 255. Без прокси метод работает как раньше, байт в байт.
 */
@Mixin(PacketByteBuf.class)
public abstract class PacketByteBufMixin {

    @ModifyVariable(method = "readString(I)Ljava/lang/String;", at = @At("HEAD"), argsOnly = true)
    private int twm$liftForwardedAddressLimit(int maxLength) {
        return TwmConfig.get().proxyEnabled() && maxLength == ProxyForwarding.VANILLA_ADDRESS_LIMIT
                ? ProxyForwarding.FORWARDED_ADDRESS_LIMIT
                : maxLength;
    }
}
