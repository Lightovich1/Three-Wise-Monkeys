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

/** Три роли общего тела. Идентификатор стабилен: он ходит по сети и в веб-интерфейс. */
public enum Role {
    /** Немой. Видит и слышит, говорит только мыслями. Пассивная камера. */
    HEAD("head"),
    /** Слепой. Слышит и говорит. Физическое тело: инвентарь, взаимодействия, здоровье. */
    HANDS("hands"),
    /** Глухой. Видит эхолокацией и говорит. Двигает общее тело. */
    LEGS("legs");

    private final String id;

    Role(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Название роли живёт в языковых файлах: оно показывается и в чате, и в веб-интерфейсе. */
    public String translationKey() {
        return "twm.role." + id;
    }

    public static Optional<Role> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (Role role : values()) {
            if (role.id.equals(normalized)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
