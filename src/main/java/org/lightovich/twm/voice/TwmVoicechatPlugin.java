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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.packets.Packet;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import org.lightovich.twm.Twm;
import org.lightovich.twm.server.TwmConfig;
import org.lightovich.twm.server.VoiceRules;

/**
 * Мост между режимом и Simple Voice Chat. Точка входа — {@code voicechat} в
 * {@code fabric.mod.json}: если голосового чата нет, этот класс не загружается вовсе, поэтому
 * мод остаётся играбельным на сервере и клиенте без него.
 *
 * <p>Оба запрета ставятся на сервере, и оба — отменой события:
 *
 * <ul>
 *   <li><b>Немой</b> — {@code MicrophonePacketEvent}. Отменённое событие означает, что пакет
 *       микрофона не пойдёт дальше обработки, то есть не уйдёт никому: ни в группу, ни
 *       соседям по расстоянию.</li>
 *   <li><b>Глухой</b> — три события звукового пакета. Они приходят по одному на каждого
 *       получателя, поэтому отменять их можно адресно: у глухого забирается всё, что для него
 *       предназначалось, а остальные слышат друг друга как обычно.</li>
 * </ul>
 *
 * <p>Клиентская половина зеркалит те же запреты у себя — не кодировать звук немого и не
 * проигрывать пришедшее глухому. Это трафик и презентация, а не право: подменённый клиент
 * упрётся в серверную проверку.
 */
public final class TwmVoicechatPlugin implements VoicechatPlugin {

    /**
     * Группы по коду лобби. Только серверный поток: их заводит и убирает лобби, а лобби
     * живёт в тике сервера.
     */
    private final Map<String, Group> lobbyGroups = new HashMap<>();

    @Override
    public String getPluginId() {
        return Twm.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            VoiceClientBridge.markInstalled();
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> VoiceRules.unbind());

        registration.registerEvent(MicrophonePacketEvent.class, event ->
                cancelIf(event, !VoiceRules.canSpeak(uuidOf(event.getSenderConnection()))));

        registration.registerEvent(EntitySoundPacketEvent.class, this::silenceDeaf);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::silenceDeaf);
        registration.registerEvent(StaticSoundPacketEvent.class, this::silenceDeaf);

        // Клиентские события существуют и на выделенном сервере, но никогда там не срабатывают.
        // Регистрируем их только на клиенте, чтобы серверу не пришлось грузить клиентские классы.
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            VoiceClientBridge.registerEvents(registration);
        }
    }

    /**
     * Голосовой сервер поднялся — с этого момента можно спрашивать, кто к нему подключён.
     * Проба уходит в {@link VoiceRules} лямбдой: тот класс живёт и без голосового чата и
     * ссылаться на его типы не имеет права.
     */
    private void onServerStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi api = event.getVoicechat();
        VoiceRules.bind(playerId -> {
            VoicechatConnection connection = api.getConnectionOf(playerId);
            return connection != null && connection.isInstalled() && connection.isConnected();
        });
        if (TwmConfig.get().voiceLobbyGroups()) {
            VoiceRules.bindGroups(new LobbyGroups(api));
        }
        Twm.LOGGER.info("Голосовой чат подключён к режиму: немой не говорит, глухой не слышит{}",
                TwmConfig.get().voiceLobbyGroups() ? ", лобби слышны только изнутри" : "");
    }

    /**
     * Голосовая группа на каждое лобби.
     *
     * <p>Тип {@code ISOLATED} выбран намеренно: он отрезает проксимити целиком. {@code NORMAL}
     * оставил бы соседей слышимыми, а именно от соседей группа и нужна — на общем спавне три
     * набирающиеся команды перекрикивают друг друга. Группа скрытая и непостоянная: она не
     * должна появляться в списке групп голосового чата и не должна переживать лобби.
     *
     * <p>Права ролей группа не отменяет: запреты немому и глухому стоят на событиях пакетов,
     * то есть срабатывают раньше того, как звук вообще пойдёт по группе.
     */
    private final class LobbyGroups implements VoiceRules.Groups {

        private final VoicechatServerApi api;

        private LobbyGroups(VoicechatServerApi api) {
            this.api = api;
        }

        @Override
        public void join(UUID playerId, String key, String name) {
            VoicechatConnection connection = api.getConnectionOf(playerId);
            if (connection == null) {
                // Игрок без голосового чата — законный случай, а не ошибка: он просто играет
                // молча. Заводить группу ради него незачем.
                return;
            }
            Group group = lobbyGroups.computeIfAbsent(key, code -> api.groupBuilder()
                    .setName(name)
                    .setType(Group.Type.ISOLATED)
                    .setPersistent(false)
                    .setHidden(true)
                    .build());
            connection.setGroup(group);
        }

        @Override
        public void leave(UUID playerId) {
            VoicechatConnection connection = api.getConnectionOf(playerId);
            if (connection != null) {
                connection.setGroup(null);
            }
        }

        @Override
        public void drop(String key) {
            Group group = lobbyGroups.remove(key);
            if (group != null) {
                api.removeGroup(group.getId());
            }
        }
    }

    private void silenceDeaf(PacketEvent<? extends Packet> event) {
        cancelIf(event, !VoiceRules.canHear(uuidOf(event.getReceiverConnection())));
    }

    /**
     * Отменяется только то, что вообще отменяемо. {@code isCancellable} проверяется явно:
     * молчаливый {@code cancel()}, который ничего не сделал, превратился бы в невидимую дыру
     * в правах, а так о ней хотя бы напишет лог.
     */
    private static void cancelIf(de.maxhenkel.voicechat.api.events.Event event, boolean forbidden) {
        if (!forbidden) {
            return;
        }
        if (!event.cancel()) {
            Twm.LOGGER.warn("Событие голосового чата не отменяется: {}",
                    event.getClass().getSimpleName());
        }
    }

    /** Отправитель или получатель может отсутствовать: звук умеет приходить не от игрока. */
    private static UUID uuidOf(VoicechatConnection connection) {
        return connection == null || connection.getPlayer() == null
                ? null : connection.getPlayer().getUuid();
    }
}
