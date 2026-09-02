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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.lightovich.twm.client.cef.install.CefHashes;
import org.lightovich.twm.client.cef.install.CefInstall;

/**
 * Проверки на чистой логике — той, что не трогает игру.
 *
 * <p>Тестов здесь немного и они не про покрытие. Каждый закрывает место, где ошибка тихая:
 * ничего не падает, ничего не пишется в лог, а работать перестаёт у части игроков и
 * обнаруживается через неделю жалобой «у меня не качается».
 */
class PureLogicTest {

    /**
     * Регрессия. Файл контрольных сумм на CDN отдаётся в двух видах, и разбор, знавший
     * только первый, брал на Windows слово {@code Algorithm} вместо суммы. Эта строка
     * работала эталоном, архив не сходился с ней никогда, и установка Chromium на Windows
     * была не «ненадёжной», а невозможной.
     */
    @Test
    @DisplayName("Сумма читается и из вывода sha256sum, и из таблицы PowerShell")
    void extractShaUnderstandsBothFormats() {
        String unix = "426bf70ccc65cbcb752e4c8015eae032d8631e6d406278605223d07d444bf917  linux_amd64.tar.gz\n";
        String windows = "\r\nAlgorithm       Hash                                  Path\r\n"
                + "---------       ----                                  ----\r\n"
                + "SHA256          E98C385542620F31A594D6FC3C38ED6BCA8A547E24CED982A1339BB3333668BE  D:\\a\\java-cef\r\n";

        assertEquals("426bf70ccc65cbcb752e4c8015eae032d8631e6d406278605223d07d444bf917",
                CefInstall.extractSha(unix));
        assertEquals("e98c385542620f31a594d6fc3c38ed6bca8a547e24ced982a1339bb3333668be",
                CefInstall.extractSha(windows));
    }

    @Test
    @DisplayName("Мусор вместо суммы — это отсутствие суммы, а не сумма")
    void extractShaRejectsGarbage() {
        assertNull(CefInstall.extractSha(null));
        assertNull(CefInstall.extractSha(""));
        assertNull(CefInstall.extractSha("Algorithm Hash Path"));
        // Короче и длиннее шестидесяти четырёх знаков — не сумма.
        assertNull(CefInstall.extractSha("426bf70c"));
        assertNull(CefInstall.extractSha("z".repeat(64)));
    }

    /**
     * Вшитые суммы обязаны отвечать только на тот коммит java-cef, к которому они относятся.
     * Ответ на чужой коммит означал бы сверку архива с эталоном от другой сборки — то есть
     * вечно несходящуюся сумму, вернувшую бы ту же поломку с другой стороны.
     */
    @Test
    @DisplayName("Вшитая сумма отдаётся только для своей сборки java-cef")
    void pinnedHashesAnswerOnlyForTheirCommit() {
        assertEquals(64, CefHashes.of("linux_amd64", CefHashes.PINNED_COMMIT).length());
        assertEquals(64, CefHashes.of("windows_amd64", CefHashes.PINNED_COMMIT).length());
        assertNull(CefHashes.of("linux_amd64", "0000000000000000000000000000000000000000"));
        assertNull(CefHashes.of("plan9_amd64", CefHashes.PINNED_COMMIT));
        assertNull(CefHashes.of(null, CefHashes.PINNED_COMMIT));
    }

    @Test
    @DisplayName("Идентификаторы ролей и сложности разбираются устойчиво к регистру и мусору")
    void identifiersRoundTrip() {
        for (Role role : Role.values()) {
            assertEquals(role, Role.fromId(role.id()).orElseThrow());
            assertEquals(role, Role.fromId(role.id().toUpperCase()).orElseThrow());
        }
        for (Difficulty difficulty : Difficulty.values()) {
            assertEquals(difficulty, Difficulty.fromId(difficulty.id()).orElseThrow());
        }
        assertTrue(Role.fromId("").isEmpty());
        assertTrue(Role.fromId(null).isEmpty());
        assertTrue(Role.fromId("body").isEmpty());
        assertTrue(Difficulty.fromId("nightmare").isEmpty());
    }

    /**
     * Разделитель {@code Msg} не набирается с клавиатуры — на этом держится правило
     * «название лобби, придуманное игроком, невозможно спутать с упакованным ключом».
     */
    @Test
    @DisplayName("Ключ с аргументами упаковывается разделителем, которого нет во вводе игрока")
    void messagePacking() {
        String packed = Msg.of("twm.notify.member_joined", "Vertar", "2", "3");
        assertEquals("twm.notify.member_joined" + Msg.SEPARATOR + "Vertar"
                + Msg.SEPARATOR + "2" + Msg.SEPARATOR + "3", packed);
        assertEquals("twm.hint.ready", Msg.of("twm.hint.ready"));
        // Пустой аргумент остаётся позицией, а не исчезает: клиент разворачивает их по счёту.
        assertEquals("k" + Msg.SEPARATOR + "" + Msg.SEPARATOR + "b", Msg.of("k", null, "b"));
    }
}
