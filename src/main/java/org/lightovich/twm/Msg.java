/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm;

import net.minecraft.text.Text;

/**
 * Упаковка «ключ перевода + аргументы» в одну строку.
 *
 * <p>Сервер не знает языка клиента, а строки лобби уходят не в чат, а в веб-интерфейс: там
 * {@code Text.translatable} не работает — страница получает готовый текст. Поэтому сервер шлёт
 * ключ, а переводит клиент, ровно перед тем как отдать строку в браузер.
 *
 * <p>Разделитель — {@code U+001F} (Unit Separator). Он не набирается с клавиатуры и не приходит
 * из полей ввода страницы, поэтому название лобби, придуманное игроком, невозможно спутать с
 * упакованным ключом: такое название просто не содержит разделителя и остаётся собой.
 */
public final class Msg {

    public static final char SEPARATOR = '\u001F';

    private Msg() {
    }

    public static String of(String key) {
        return key;
    }

    public static String of(String key, String... arguments) {
        StringBuilder packed = new StringBuilder(key);
        for (String argument : arguments) {
            packed.append(SEPARATOR).append(argument == null ? "" : argument);
        }
        return packed.toString();
    }

    /**
     * Тот же ключ, но для чата. Здесь переводит ванильный движок на стороне клиента, поэтому
     * серверу достаточно собрать {@link Text} и ничего не знать про язык получателя.
     */
    public static Text text(String packed) {
        int separator = packed.indexOf(SEPARATOR);
        if (separator < 0) {
            return Text.translatable(packed);
        }
        Object[] arguments = packed.substring(separator + 1)
                .split(String.valueOf(SEPARATOR), -1);
        return Text.translatable(packed.substring(0, separator), arguments);
    }
}
