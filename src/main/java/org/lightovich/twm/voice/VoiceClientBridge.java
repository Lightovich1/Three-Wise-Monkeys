/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.voice;

import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

import org.lightovich.twm.client.VoiceStatus;

/**
 * Клиентская половина моста. Вынесена отдельным классом намеренно: она трогает клиентское
 * состояние режима, и выделенный сервер не должен её загружать — {@link TwmVoicechatPlugin}
 * обращается сюда только в клиентском окружении.
 *
 * <p>Запреты здесь дублируют серверные и правом не являются. Смысл дубля в другом:
 *
 * <ul>
 *   <li>немой не кодирует и не отправляет звук вообще — сервер иначе получал бы поток пакетов
 *       только затем, чтобы каждый выбросить;</li>
 *   <li>глухой не проигрывает пришедшее — на случай звука, рождённого на самом клиенте
 *       (плагины, локальный сервер), до которого серверная проверка не дотягивается.</li>
 * </ul>
 */
final class VoiceClientBridge {

    private VoiceClientBridge() {
    }

    static void markInstalled() {
        VoiceStatus.markInstalled();
    }

    static void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatConnectionEvent.class, event ->
                VoiceStatus.setConnected(event.isConnected()));

        registration.registerEvent(ClientSoundEvent.class, event -> {
            if (!VoiceStatus.canSpeak()) {
                event.cancel();
            }
        });

        // Подписываться нужно на три вложенных вида звука, а не на общий
        // ClientReceiveSoundEvent: диспетчер голосового чата ищет обработчики по точному классу
        // события, и подписка на родителя молча не срабатывает ни разу.
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class,
                VoiceClientBridge::silenceIfDeaf);
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class,
                VoiceClientBridge::silenceIfDeaf);
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class,
                VoiceClientBridge::silenceIfDeaf);
    }

    private static void silenceIfDeaf(ClientReceiveSoundEvent event) {
        if (!VoiceStatus.canHear()) {
            event.cancel();
        }
    }
}
