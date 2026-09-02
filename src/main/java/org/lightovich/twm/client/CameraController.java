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

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Сцепка камер с направлением ног.
 *
 * <p>На тестах прошлой версии голова и руки не понимали, куда идёт тело, и терялись.
 * Поэтому по умолчанию камера головы и рук жёстко смотрит туда же, куда ноги, а мышь до неё
 * вообще не доходит (см. {@code EntityLookMixin}) — иначе игрок ведёт мышью, мод возвращает
 * обратно, и картинка дрожит. Свободный обзор включает удержание ALT; после отпускания камера
 * за несколько тиков плавно доводится назад, потому что мгновенный скачок дезориентирует так же,
 * как и потеря направления.
 */
public final class CameraController {

    private static final int RETURN_TICKS = 6;

    private static boolean freeLook;
    private static int returnTicksLeft;

    private CameraController() {
    }

    /** Заблокирован ли поворот мышью прямо сейчас. Спрашивается из миксина ввода. */
    public static boolean isLookLocked() {
        if (!ClientParty.isHead() && !ClientParty.isHands()) {
            return false;
        }
        if (!ClientParty.hasLegsRotation()) {
            return false;
        }
        return !TwmClient.FREE_LOOK.isPressed();
    }

    public static boolean isFreeLook() {
        return freeLook;
    }

    public static void reset() {
        freeLook = false;
        returnTicksLeft = 0;
    }

    public static void tick(boolean freeLookHeld) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || !ClientParty.hasLegsRotation()) {
            return;
        }
        if (!ClientParty.isHead() && !ClientParty.isHands()) {
            reset();
            return;
        }

        if (freeLookHeld) {
            freeLook = true;
            returnTicksLeft = RETURN_TICKS;
            return;
        }
        freeLook = false;

        float targetYaw = ClientParty.legsYaw();
        float targetPitch = ClientParty.legsPitch();
        if (returnTicksLeft > 0) {
            float step = 1.0F / returnTicksLeft;
            targetYaw = MathHelper.lerpAngleDegrees(step, player.getYaw(), targetYaw);
            targetPitch = MathHelper.lerp(step, player.getPitch(), targetPitch);
            returnTicksLeft--;
        }
        player.setYaw(targetYaw);
        player.setPitch(targetPitch);
        player.setHeadYaw(targetYaw);
        player.setBodyYaw(targetYaw);
        // prevYaw/prevPitch намеренно не трогаем: между тиками камеру доводит обычная
        // ванильная интерполяция, и поворот выглядит непрерывным, а не пошаговым.
    }
}
