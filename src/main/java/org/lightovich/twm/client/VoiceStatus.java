/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client;

import org.lightovich.twm.Role;

/**
 * Клиентское зеркало голосового чата: установлен ли он и подключён ли к серверу.
 *
 * <p>Права ролей здесь не хранятся — они выводятся из роли, а роль уже лежит в
 * {@link ClientParty}. Второе место правды о том, кто говорит и кто слышит, разошлось бы с
 * серверным при первой же гонке пакетов.
 *
 * <p>Класс намеренно ничего не знает ни про Simple Voice Chat, ни про Minecraft: его читает
 * сборщик состояния HUD, а пишет мост голосового чата, который загружается только вместе с
 * самим голосовым чатом.
 */
public final class VoiceStatus {

    private static volatile boolean installed;
    private static volatile boolean connected;

    private VoiceStatus() {
    }

    /** Вызывается мостом при инициализации плагина: мод голосового чата в игре. */
    public static void markInstalled() {
        installed = true;
    }

    public static void setConnected(boolean value) {
        connected = value;
    }

    /** Голос работает: мод стоит и соединение с голосовым сервером живо. */
    public static boolean ready() {
        return installed && connected;
    }

    public static boolean installed() {
        return installed;
    }

    public static boolean canSpeak() {
        Role role = ClientParty.role();
        return role != Role.HEAD;
    }

    public static boolean canHear() {
        Role role = ClientParty.role();
        return role != Role.LEGS;
    }
}
