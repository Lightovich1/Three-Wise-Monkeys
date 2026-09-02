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
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;

import org.lightovich.twm.TwmSounds;

/**
 * Звуки интерфейса: короткие «тапы» под курсором.
 *
 * <p>Играет их мод, а не страница. У Chromium свой аудиовыход, мимо микшера игры: звук меню
 * оказался бы громче игры и не подчинялся её громкости. Значит страница шлёт намерение, а
 * решение — играть ли, с какой громкостью — остаётся здесь.
 *
 * <p>Отдельная защита от частоты: наведение в круговом меню меняется десятки раз в секунду при
 * одном движении мыши. Два тапа подряд ближе, чем за {@link #TAP_GAP_MS}, сливаются в дребезг,
 * поэтому лишние просто отбрасываются.
 */
public final class UiSounds {

    private static final long TAP_GAP_MS = 45L;

    private static long lastTapAt;

    private UiSounds() {
    }

    /** Наведение: самый тихий из трёх, звучит чаще всех. */
    public static void tap() {
        long now = System.currentTimeMillis();
        if (now - lastTapAt < TAP_GAP_MS) {
            return;
        }
        lastTapAt = now;
        play(air() ? TwmSounds.UI_TAP_AIR : TwmSounds.UI_TAP, 1.0F, 0.5F);
    }

    /** Обычное нажатие. */
    public static void click() {
        play(air() ? TwmSounds.UI_CLICK_AIR : TwmSounds.UI_CLICK, 1.0F, 1.0F);
    }

    /** Отправленная мысль: то же нажатие, но выше — выбор обязан звучать иначе, чем переход. */
    public static void send() {
        play(air() ? TwmSounds.UI_CLICK_AIR : TwmSounds.UI_CLICK, 1.18F, 1.0F);
    }

    /** Возврат, закрытие, отмена. */
    public static void back() {
        play(air() ? TwmSounds.UI_BACK_AIR : TwmSounds.UI_BACK, 1.0F, 0.9F);
    }

    /** Тембр выбирается на каждый звук: смена настройки обязана быть слышна сразу. */
    private static boolean air() {
        return "air".equals(TwmSettings.sound().uiStyle());
    }

    /** Имя звука со страницы. Неизвестное имя молча игнорируется: страница не задаёт список. */
    public static void byName(String name) {
        switch (name) {
            case "tap" -> tap();
            case "click" -> click();
            case "send" -> send();
            case "back" -> back();
            default -> {
            }
        }
    }

    private static void play(SoundEvent sound, float pitch, float share) {
        TwmSettings.Sound settings = TwmSettings.sound();
        if (!settings.uiEnabled() || settings.uiVolume() <= 0.0F) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSoundManager() == null) {
            return;
        }
        client.getSoundManager().play(PositionedSoundInstance.master(
                sound, pitch, settings.uiVolume() * share));
    }
}
