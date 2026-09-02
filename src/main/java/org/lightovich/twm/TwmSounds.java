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

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Звуки мода. Регистрируются в общем инициализаторе: реестр звуков синхронизируется
 * с сервером, и запись обязана существовать на обеих сторонах.
 */
public final class TwmSounds {

    /** Сонарный импульс ног. Это обратная связь игроку, а не звук в мире. */
    public static final SoundEvent ECHO_PING = register("echo_ping");

    /**
     * Звуки интерфейса. Они не звучат в мире и никого, кроме нажавшего, не касаются, но
     * регистрируются здесь же: реестр звуков синхронизируется с сервером, и запись обязана
     * существовать на обеих сторонах, иначе клиент отвалится на рассинхроне реестров.
     */
    public static final SoundEvent UI_TAP = register("ui_tap");
    public static final SoundEvent UI_CLICK = register("ui_click");
    public static final SoundEvent UI_BACK = register("ui_back");

    /** Второй тембр интерфейса: тот же набор из трёх, но выдохом вместо тона. */
    public static final SoundEvent UI_TAP_AIR = register("ui_tap_air");
    public static final SoundEvent UI_CLICK_AIR = register("ui_click_air");
    public static final SoundEvent UI_BACK_AIR = register("ui_back_air");

    private TwmSounds() {
    }

    private static SoundEvent register(String name) {
        Identifier id = Identifier.of(Twm.MOD_ID, name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** Достаточно обращения к классу: константы регистрируются при его загрузке. */
    public static void initialize() {
    }
}
