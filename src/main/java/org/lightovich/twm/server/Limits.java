/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Частотные лимиты входящих намерений.
 *
 * <p>Клиент мода — обычная программа на машине игрока, и переписать её может кто угодно.
 * Права ролей от этого защищены проверками, но частота — нет: {@code lobby_action} без
 * лимита усиливает один пакет в столько исходящих снимков, сколько на сервере игроков,
 * а {@code head_drop} без лимита вываливает инвентарь рук за тик и засыпает мир сущностями.
 *
 * <p>Ведро на игрока и канал: пополняется во времени, тратится на каждое намерение. Перебор —
 * молчаливый отказ, без сообщения игроку: сообщение само по себе стало бы каналом усиления,
 * а честный клиент в лимит не упирается никогда.
 */
public final class Limits {

    /** Каналы. Раздельные вёдра: спам мыслями не должен запирать выход из лобби. */
    public enum Channel {
        LOBBY,
        THOUGHT,
        DROP
    }

    /** Запас на всплеск: интерфейс шлёт очередь действий подряд, когда игрок торопится. */
    private static final double BURST_SECONDS = 2.0D;

    private static final Map<UUID, Bucket[]> BUCKETS = new ConcurrentHashMap<>();

    private Limits() {
    }

    public static boolean allow(UUID playerId, Channel channel) {
        double perSecond = switch (channel) {
            case LOBBY -> TwmConfig.get().lobbyActionsPerSecond();
            case THOUGHT -> TwmConfig.get().thoughtsPerSecond();
            case DROP -> TwmConfig.get().dropsPerSecond();
        };
        Bucket[] buckets = BUCKETS.computeIfAbsent(playerId, id -> newBuckets());
        return buckets[channel.ordinal()].take(perSecond, perSecond * BURST_SECONDS);
    }

    /** Вышедший игрок ведра не занимает: иначе карта растёт всю жизнь процесса. */
    public static void forget(UUID playerId) {
        BUCKETS.remove(playerId);
    }

    public static void clear() {
        BUCKETS.clear();
    }

    private static Bucket[] newBuckets() {
        Bucket[] buckets = new Bucket[Channel.values().length];
        for (int index = 0; index < buckets.length; index++) {
            buckets[index] = new Bucket();
        }
        return buckets;
    }

    /**
     * Пакеты приходят в сетевом потоке, а разбираются в серверном: ведро видят оба, поэтому
     * оно синхронизировано. Блокировка тут дешевле любой альтернативы — счёт идёт на десятки
     * взятий в секунду на игрока, а не на тысячи.
     */
    private static final class Bucket {

        private double tokens = Double.NaN;
        private long lastNanos;

        synchronized boolean take(double perSecond, double capacity) {
            long now = System.nanoTime();
            if (Double.isNaN(tokens)) {
                tokens = capacity;
            } else {
                double elapsed = (now - lastNanos) / 1.0E9D;
                tokens = Math.min(capacity, tokens + elapsed * perSecond);
            }
            lastNanos = now;
            if (tokens < 1.0D) {
                return false;
            }
            tokens -= 1.0D;
            return true;
        }
    }
}
