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

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import org.lightovich.twm.Twm;

/**
 * Развод одной-единственной клавиши с Simple Voice Chat.
 *
 * <p>«Меню голосового чата» у него по умолчанию на {@code V}, а у режима на {@code V} висит
 * дальний эхо-пинг ног. Совпадение не безобидное: пинг нужен глухому в движении, и вместе с
 * ним посреди забега открывался бы полноэкранный список игроков голосового чата.
 *
 * <p>Уступает чужой бинд, а не свой: пинг описан в справке режима и стоит на видном месте
 * HUD, а меню голосового чата за игру открывают в лучшем случае раз. Переносится он один раз
 * за запуск — если игрок вернёт {@code V} обратно осознанно, мод спорить не станет.
 */
public final class VoiceKeys {

    private static final String VOICE_MENU_KEY = "key.voice_chat";
    private static final int FALLBACK = GLFW.GLFW_KEY_K;

    private static boolean resolved;

    private VoiceKeys() {
    }

    /**
     * Вызывается из клиентского тика, а не из инициализатора: на момент инициализации мода
     * настроек игры ещё нет, а значит нет и списка биндов, который нужно проверить.
     */
    public static void resolveOnce(MinecraftClient client) {
        if (resolved || client.options == null) {
            return;
        }
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded("voicechat")) {
            return;
        }
        KeyBinding menu = find(client, VOICE_MENU_KEY);
        if (menu == null || menu.isUnbound() || TwmClient.PING.isUnbound()
                || !menu.getBoundKeyTranslationKey().equals(TwmClient.PING.getBoundKeyTranslationKey())) {
            return;
        }
        InputUtil.Key fallback = InputUtil.fromKeyCode(FALLBACK, 0);
        if (isTaken(client, fallback.getTranslationKey())) {
            Twm.LOGGER.warn("Меню голосового чата и эхо-пинг оба на {}, а запасная клавиша занята",
                    menu.getBoundKeyLocalizedText().getString());
            return;
        }
        menu.setBoundKey(fallback);
        // Без пересборки индекса клавиша остаётся в старом слоте и бинд не срабатывает.
        KeyBinding.updateKeysByCode();
        client.options.write();
        Twm.LOGGER.info("Меню голосового чата перенесено на {}: {} занят эхо-пингом",
                menu.getBoundKeyLocalizedText().getString(),
                TwmClient.PING.getBoundKeyLocalizedText().getString());
    }

    /** Поиск по названию действия, а не по классу: чужой бинд остаётся чужим. */
    private static KeyBinding find(MinecraftClient client, String actionKey) {
        for (KeyBinding binding : client.options.allKeys) {
            if (binding.getTranslationKey().equals(actionKey)) {
                return binding;
            }
        }
        return null;
    }

    /** Переезжать на занятую клавишу бессмысленно: конфликт просто сменит адрес. */
    private static boolean isTaken(MinecraftClient client, String boundKey) {
        for (KeyBinding binding : client.options.allKeys) {
            if (!binding.isUnbound() && binding.getBoundKeyTranslationKey().equals(boundKey)) {
                return true;
            }
        }
        return false;
    }
}
