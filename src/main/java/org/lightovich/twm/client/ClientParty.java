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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import org.lightovich.twm.Difficulty;
import org.lightovich.twm.Role;

/**
 * Клиентское зеркало состояния команды. Только презентация: любое право уже проверено сервером,
 * здесь состояние нужно, чтобы не рисовать напарников и не давать прицелу цепляться за них.
 */
public final class ClientParty {

    /** Столько же, сколько у лобби по умолчанию: до первого снимка играть всё равно нечего. */
    private static final int DEFAULT_ECHO_COOLDOWN_TICKS = 300;

    private static Role role;
    private static Difficulty difficulty = Difficulty.EASY;

    /**
     * Кулдаун дальнего пинга ног в тиках. Правда о нём одна — серверная: его задаёт хост
     * в лобби, и клиент не имеет права подобрать себе значение полегче.
     */
    private static int echoCooldownTicks = DEFAULT_ECHO_COOLDOWN_TICKS;
    private static final Set<UUID> members = new HashSet<>();

    private static Vec3d pendingBodyPosition;
    private static boolean pendingOnGround;
    private static boolean pendingSneaking;
    private static float legsYaw;
    private static float legsPitch;
    private static boolean hasLegsRotation;

    private ClientParty() {
    }

    public static void apply(String roleId, String difficultyId, int cooldownTicks,
                             List<UUID> otherMembers) {
        role = Role.fromId(roleId).orElse(null);
        difficulty = Difficulty.fromId(difficultyId).orElse(Difficulty.EASY);
        echoCooldownTicks = cooldownTicks <= 0 ? DEFAULT_ECHO_COOLDOWN_TICKS : cooldownTicks;
        members.clear();
        members.addAll(otherMembers);
        pendingBodyPosition = null;
        hasLegsRotation = false;
    }

    public static void clear() {
        apply("", Difficulty.EASY.id(), DEFAULT_ECHO_COOLDOWN_TICKS, List.of());
    }

    public static int echoCooldownTicks() {
        return echoCooldownTicks;
    }

    public static Role role() {
        return role;
    }

    public static Difficulty difficulty() {
        return difficulty;
    }

    public static boolean isHead() {
        return role == Role.HEAD;
    }

    public static boolean isHands() {
        return role == Role.HANDS;
    }

    public static boolean isLegs() {
        return role == Role.LEGS;
    }

    public static boolean inParty() {
        return role != null;
    }

    /** Напарник по общему телу: он не должен ни рисоваться, ни попадать под прицел. */
    public static boolean isHiddenTeammate(UUID playerId) {
        return role != null && members.contains(playerId);
    }

    public static void receiveBodyState(double x, double y, double z, boolean onGround,
                                        boolean sneaking, float yaw, float pitch) {
        pendingBodyPosition = new Vec3d(x, y, z);
        pendingOnGround = onGround;
        pendingSneaking = sneaking;
        legsYaw = yaw;
        legsPitch = pitch;
        hasLegsRotation = true;
    }

    public static boolean hasLegsRotation() {
        return hasLegsRotation;
    }

    public static float legsYaw() {
        return legsYaw;
    }

    public static float legsPitch() {
        return legsPitch;
    }

    /**
     * Применяется в конце клиентского тика — после того, как ванильный тик уже запомнил
     * прошлую позицию. Тогда камера интерполируется между тиками как обычно, без рывков.
     */
    public static void applyPendingBodyPosition() {
        if (pendingBodyPosition == null || role == null || role == Role.LEGS) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            pendingBodyPosition = null;
            return;
        }
        client.player.setPosition(pendingBodyPosition);
        client.player.setOnGround(pendingOnGround);
        client.player.setVelocity(Vec3d.ZERO);
        // Приседание ног меняет высоту камеры общего тела: без переноса пассивные роли
        // смотрели бы из полного роста там, где тело уже пролезает под блоком.
        client.player.setSneaking(pendingSneaking);
        pendingBodyPosition = null;
    }
}
