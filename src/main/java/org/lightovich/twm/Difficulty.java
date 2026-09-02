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

import java.util.Locale;
import java.util.Optional;

/**
 * Сложность восприятия. Определяет только клиентские модели восприятия ролей;
 * серверные права ролей от неё не зависят.
 *
 * <ul>
 *   <li>Ноги — модель эхолокации.</li>
 *   <li>Руки — визуализация звуков вокруг тела (руки слепые, но слышат).</li>
 * </ul>
 */
public enum Difficulty {
    /** Ноги: непрерывная волна, мир не пропадает. Руки: направленная визуализация звуков. */
    EASY("easy"),
    /** Ноги: волна только от шагов + пинг по кнопке с кулдауном. Руки: только настоящий звук. */
    HARD("hard");

    private final String id;

    Difficulty(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "twm.difficulty." + id;
    }

    /** Показывать ли рукам направленные метки услышанных звуков. */
    public boolean hasSoundVisualization() {
        return this == EASY;
    }

    public static Optional<Difficulty> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (Difficulty difficulty : values()) {
            if (difficulty.id.equals(normalized)) {
                return Optional.of(difficulty);
            }
        }
        return Optional.empty();
    }
}
