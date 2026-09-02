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

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.lightovich.twm.Role;
import org.lightovich.twm.party.PartyManager;

/**
 * Права ролей на голос. Единственное место, где решается, кто говорит и кто слышит; мост в
 * Simple Voice Chat ({@code org.lightovich.twm.voice.TwmVoicechatPlugin}) только спрашивает.
 *
 * <p>Правило то же, что и у остальных прав: решает сервер. Клиент дублирует запрет у себя
 * (не кодирует звук немого, не проигрывает пришедший глухому), но это экономия трафика и
 * презентация — подменённый клиент ничего не выигрывает, потому что пакет всё равно не уйдёт
 * дальше сервера и не придёт с него.
 *
 * <table>
 *   <tr><th>Роль</th><th>Говорит</th><th>Слышит</th></tr>
 *   <tr><td>Голова (немой)</td><td>нет</td><td>да</td></tr>
 *   <tr><td>Руки (слепой)</td><td>да</td><td>да</td></tr>
 *   <tr><td>Ноги (глухой)</td><td>да</td><td>нет</td></tr>
 * </table>
 *
 * <p>Ограничения действуют только в игре. В лобби говорят и слышат все трое: договориться,
 * кто какую роль берёт, нужно голосом, а немого в лобби ещё нет.
 *
 * <p>Класс не ссылается на классы Simple Voice Chat ни одним полем: мод обязан загружаться
 * на сервере без него. Связь с голосовым чатом ставится извне через {@link #bind}.
 */
public final class VoiceRules {

    /** Проставляется плагином голосового чата. Пока голосового чата нет — никто не подключён. */
    private static volatile Predicate<UUID> connectionProbe;

    /** Управление голосовыми группами. Ставится тем же мостом и той же ценой — {@code null}. */
    private static volatile Groups groups;

    private VoiceRules() {
    }

    /**
     * Голосовая группа на лобби.
     *
     * <p>Голос в Simple Voice Chat по умолчанию проксимити: на общем спавне три набирающиеся
     * команды слышат друг друга и мешают друг другу договариваться о ролях. Группа делает
     * лобби слышимым только изнутри.
     *
     * <p>Интерфейс объявлен здесь, а реализация живёт в пакете {@code voice}: этот класс
     * обязан загружаться на сервере без голосового чата и не имеет права ссылаться на его
     * типы ни одним полем — ровно та же причина, по которой проба подключения приходит
     * лямбдой.
     */
    public interface Groups {
        /** Игрок попадает в группу лобби; группа создаётся, если её ещё нет. */
        void join(UUID playerId, String key, String name);

        /** Игрок выходит из своей группы и возвращается к обычному проксимити. */
        void leave(UUID playerId);

        /** Лобби распалось — группу можно убрать целиком. */
        void drop(String key);
    }

    public static void bindGroups(Groups control) {
        groups = control;
    }

    public static void joinGroup(UUID playerId, String key, String name) {
        Groups control = groups;
        if (control != null) {
            control.join(playerId, key, name);
        }
    }

    public static void leaveGroup(UUID playerId) {
        Groups control = groups;
        if (control != null) {
            control.leave(playerId);
        }
    }

    public static void dropGroup(String key) {
        Groups control = groups;
        if (control != null) {
            control.drop(key);
        }
    }

    /**
     * Мост сообщает, что голосовой чат в игре и как спросить про конкретного игрока.
     * Вызывается из плагина Simple Voice Chat, то есть только когда тот действительно загружен.
     */
    public static void bind(Predicate<UUID> probe) {
        connectionProbe = probe;
    }

    public static void unbind() {
        connectionProbe = null;
        groups = null;
    }

    /** Голосовой чат установлен на сервере и запущен. */
    public static boolean available() {
        return connectionProbe != null;
    }

    /**
     * Игрок подключён к голосовому чату и слышен. Отдельный вопрос от {@link #available()}:
     * сервер может держать голосовой чат, а конкретный клиент — не иметь мода.
     */
    public static boolean connected(UUID playerId) {
        Predicate<UUID> probe = connectionProbe;
        return probe != null && probe.test(playerId);
    }

    public static boolean canSpeak(UUID playerId) {
        return activeRole(playerId).map(role -> role != Role.HEAD).orElse(true);
    }

    public static boolean canHear(UUID playerId) {
        return activeRole(playerId).map(role -> role != Role.LEGS).orElse(true);
    }

    /** Ключ строки, которой роли объясняют её голосовые права при старте забега. */
    public static String briefingKey(Role role) {
        return switch (role) {
            case HEAD -> "twm.voice.head";
            case HANDS -> "twm.voice.hands";
            case LEGS -> "twm.voice.legs";
        };
    }

    /**
     * Роль есть только у того, кто прямо сейчас играет: в лобби ограничений нет.
     * {@code null} — законное значение: звук умеет приходить не от игрока, и такой пакет
     * никакой роли не принадлежит.
     */
    private static Optional<Role> activeRole(UUID playerId) {
        if (playerId == null || !PartyManager.isInActiveParty(playerId)) {
            return Optional.empty();
        }
        return PartyManager.roleOf(playerId);
    }
}
