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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Полученные мысли. Держит максимум две карточки: третья вытесняет старейшую — на слепом
 * экране ног длинный список читать некогда.
 *
 * <p>Срок жизни берётся из настроек в момент прихода мысли и запоминается в карточке: HUD рисует
 * полосу остатка, и она обязана считаться от того срока, с которым карточка появилась, а не от
 * текущего значения настройки.
 *
 * <p>У каждой карточки есть сквозной ключ. Он нужен странице HUD: состояние приходит каждый тик,
 * и без ключа она не отличит новую мысль от той же самой с изменившимся остатком — пришлось бы
 * пересобирать панель заново каждый тик, а это мерцание. Один сигнал может лежать в журнале
 * дважды, поэтому идентификатор мысли ключом быть не может.
 */
public final class ThoughtLog {

    private static final int CAPACITY = 2;

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private static long counter;

    private ThoughtLog() {
    }

    public static void add(String signal, String sender) {
        ENTRIES.addLast(new Entry(++counter, signal, sender, TwmSettings.thoughtTicks()));
        while (ENTRIES.size() > CAPACITY) {
            ENTRIES.removeFirst();
        }
    }

    public static void tick() {
        ENTRIES.removeIf(entry -> --entry.ticksLeft <= 0);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    public static final class Entry {
        private final long key;
        private final String signal;
        private final String sender;
        private final int maxTicks;
        private int ticksLeft;

        private Entry(long key, String signal, String sender, int maxTicks) {
            this.key = key;
            this.signal = signal;
            this.sender = sender;
            this.maxTicks = maxTicks;
            this.ticksLeft = maxTicks;
        }

        public long key() {
            return key;
        }

        public String signal() {
            return signal;
        }

        public String sender() {
            return sender;
        }

        public int ticksLeft() {
            return ticksLeft;
        }

        public int maxTicks() {
            return maxTicks;
        }
    }
}
