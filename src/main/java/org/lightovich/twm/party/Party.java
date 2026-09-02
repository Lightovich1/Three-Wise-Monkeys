/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.party;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.lightovich.twm.Difficulty;
import org.lightovich.twm.Role;

/**
 * Лобби на троих и оно же — играющая команда. Сущность одна на оба состояния намеренно:
 * разделение «комната» и «команда» дало бы два места правды о том, кто с кем играет, и
 * рассинхрон при остановке игры. Живёт только в памяти сервера: при остановке сервера
 * лобби исчезают.
 */
public final class Party {

    static final int MEMBER_COUNT = 3;

    /**
     * Кулдаун дальнего пинга ног, в тиках. Задаёт хост: от него зависит, насколько часто
     * ноги «освещают» местность целиком, то есть темп всего забега. Пятнадцать секунд —
     * значение по умолчанию: прежние пять превращали пинг в фон, который жмут не думая.
     */
    static final int DEFAULT_ECHO_COOLDOWN_TICKS = 300;
    static final int MIN_ECHO_COOLDOWN_TICKS = 100;
    static final int MAX_ECHO_COOLDOWN_TICKS = 1200;

    static final int NAME_LIMIT = 24;
    static final int PASSWORD_LIMIT = 32;

    /** Четыре символа A–Z0–9 без похожих. Уникален: по нему игрок входит вручную. */
    final String code;

    /** Порядок входа сохраняется: по нему же назначается новый хост, если прежний вышел. */
    final Set<UUID> members = new LinkedHashSet<>();

    /**
     * Карта ролей читается из клиентского потока тоже: миксины общего конфига спрашивают роль
     * игрока, а в одиночке и по локальной сети клиент и сервер — один процесс. {@code EnumMap}
     * стоял здесь ради порядка перечисления, но порядок берётся из {@code Role.values()}
     * везде, где он важен, — потери нет.
     */
    final Map<Role, UUID> roles = new ConcurrentHashMap<>();

    /** Хост: единственный, кто меняет настройки, зовёт игроков и запускает игру. */
    UUID host;

    /** Пустое имя не подставляется здесь: подпись зависит от ника хоста, а хост меняется. */
    String name = "";

    /** Приватное лобби: в списке видно под замком, вход по паролю или по приглашению. */
    boolean locked;
    String password = "";
    Difficulty difficulty = Difficulty.EASY;
    int echoCooldownTicks = DEFAULT_ECHO_COOLDOWN_TICKS;

    /** Идёт игра. Роли при остановке сохраняются, поэтому отдельного состояния ролей нет. */
    volatile boolean active;

    /**
     * Сдвигать ли роли по кругу после забега, законченного хостом. Включено по умолчанию:
     * увидеть режим целиком можно только с трёх сторон, а сами игроки роли не меняют —
     * занявший однажды «свою» остаётся на ней и уходит с половиной игры.
     */
    boolean rotateRoles = true;

    /** Идёт общий цикл смерти: синхронизация тела приостановлена до возрождения всех троих. */
    boolean deathCycle;

    // --- Итоги забега -----------------------------------------------------
    // Считаются на сервере и показываются команде на экране итогов. Клиенту тут верить
    // нечему: он не знает ни числа смертей чужих ролей, ни того, дошло ли тело до Энда.

    long startedAt;
    int deaths;
    boolean reachedEnd;
    boolean dragonKilled;

    /** Роли сдвинулись на этом забеге — итоги покажут каждому, кем он будет в следующем. */
    boolean rotatedLastRun;

    /**
     * Момент, с которого укомплектованное лобби перестало быть укомплектованным. Нужен только
     * заранее поднятой арене: она ждёт кнопку хоста, но не вечно.
     */
    long notReadySince;

    /**
     * Наибольшая высота падения ног за текущий полёт. Ноги неуязвимы, поэтому урон от падения
     * им не приходит и его нужно передать телу вручную — иначе с обрыва можно шагать бесплатно.
     */
    float legsFallDistance;

    Party(String code, UUID host) {
        this.code = code;
        this.host = host;
        this.members.add(host);
    }

    Role roleOf(UUID memberId) {
        for (Map.Entry<Role, UUID> entry : roles.entrySet()) {
            if (entry.getValue().equals(memberId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    UUID memberWithRole(Role role) {
        return roles.get(role);
    }

    boolean isHost(UUID playerId) {
        return host.equals(playerId);
    }

    boolean hasFreeSlot() {
        return members.size() < MEMBER_COUNT;
    }

    /** Полный состав и все три роли заняты — единственные условия старта. */
    boolean readyToStart() {
        return members.size() == MEMBER_COUNT && roles.size() == MEMBER_COUNT;
    }
}
