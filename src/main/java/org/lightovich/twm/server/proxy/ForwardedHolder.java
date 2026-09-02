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

/**
 * Соединение, которое запомнило форвард прокси. Реализуется миксином на {@code ClientConnection}:
 * данные рукопожатия нужны на логине, а между этими двумя точками у ванили нет ничего общего,
 * кроме самого соединения. Статическая карта здесь была бы утечкой — соединение может умереть
 * между рукопожатием и логином, и вычищать её было бы некому.
 */
public interface ForwardedHolder {

    ForwardedLogin twm$forwarded();

    /** Кладёт форвард и подменяет адрес соединения настоящим адресом игрока. */
    void twm$applyForwarded(ForwardedLogin forwarded);
}
