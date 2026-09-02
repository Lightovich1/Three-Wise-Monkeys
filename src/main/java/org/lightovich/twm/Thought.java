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
 * Словарь мыслей головы. Идентификатор стабилен и ходит по сети; текст и значок —
 * представление на стороне интерфейса.
 *
 * <p>Раскладка панели задаётся семантически, а не порядком объявления: в прототипе элементы
 * расставлялись по кругу по индексу перечисления, из-за чего «вправо» оказывалось слева.
 *
 * <p>Подписи здесь не хранятся вовсе — только ключи. Мысль читают на слух в чужом языке чаще,
 * чем любую другую строку мода, поэтому перевод обязан идти из общего файла, а не из кода.
 */
public enum Thought {
    // Срочные команды. Они в центре кругового меню: «стой» за два клика — это уже поздно.
    ATTACK("attack", Group.COMMAND),
    STOP("stop", Group.COMMAND),
    RUN("run", Group.COMMAND),

    FORWARD("forward", Group.DIRECTION),
    BACKWARD("backward", Group.DIRECTION),
    LEFT("left", Group.DIRECTION),
    RIGHT("right", Group.DIRECTION),
    UP("up", Group.DIRECTION),
    DOWN("down", Group.DIRECTION),

    MINE("mine", Group.ACTION),
    CRAFT("craft", Group.ACTION),
    PLACE("place", Group.ACTION),
    TAKE("take", Group.ACTION),

    DANGER("danger", Group.STATE),
    HEALTH("health", Group.STATE),
    HUNGER("hunger", Group.STATE),

    YES("yes", Group.ANSWER),
    NO("no", Group.ANSWER),
    REPEAT("repeat", Group.ANSWER),

    ZERO("0", Group.NUMBER),
    ONE("1", Group.NUMBER),
    TWO("2", Group.NUMBER),
    THREE("3", Group.NUMBER),
    FOUR("4", Group.NUMBER),
    FIVE("5", Group.NUMBER),
    SIX("6", Group.NUMBER),
    SEVEN("7", Group.NUMBER),
    EIGHT("8", Group.NUMBER),
    NINE("9", Group.NUMBER);

    private final String id;
    private final Group group;

    Thought(String id, Group group) {
        this.id = id;
        this.group = group;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "twm.thought." + id;
    }

    public Group group() {
        return group;
    }

    public static Optional<Thought> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (Thought thought : values()) {
            if (thought.id.equals(normalized)) {
                return Optional.of(thought);
            }
        }
        return Optional.empty();
    }

    public enum Group {
        COMMAND,
        DIRECTION,
        ACTION,
        STATE,
        ANSWER,
        NUMBER;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String translationKey() {
            return "twm.group." + id();
        }
    }
}
