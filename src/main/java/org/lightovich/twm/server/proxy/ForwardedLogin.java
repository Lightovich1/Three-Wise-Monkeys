/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.server.proxy;

import java.net.SocketAddress;
import java.util.UUID;

import com.mojang.authlib.properties.PropertyMap;

/**
 * Данные, которые прокси приложил к рукопожатию: настоящий адрес игрока, его UUID и
 * свойства профиля (скин, подпись, токен BungeeGuard).
 *
 * <p>Живёт ровно от рукопожатия до логина одного соединения. Токен здесь уже отделён от
 * остальных свойств: он секрет прокси, и в готовый профиль игрока попасть не должен —
 * профиль виден плагинам и уезжает другим игрокам.
 */
public record ForwardedLogin(UUID uuid, SocketAddress address, PropertyMap properties, String token) {
}
