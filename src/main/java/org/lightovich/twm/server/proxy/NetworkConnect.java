/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.server.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import org.lightovich.twm.Twm;
import org.lightovich.twm.net.TwmPayloads;
import org.lightovich.twm.party.PartyManager;
import org.lightovich.twm.server.TwmConfig;

/**
 * Перевод игрока на другой сервер сети.
 *
 * <p>Просьба уходит не клиенту, а прокси: тот перехватывает сообщение на канале
 * {@code bungeecord:main} по дороге и переключает соединение сам. Клиенту для этого ничего
 * не нужно — работает и с ванильным.
 *
 * <p>На сервере с запертым меню это единственный способ уйти на соседний сервер: до чата
 * игрок не добирается, пока забег не начался.
 */
public final class NetworkConnect {

    private NetworkConnect() {
    }

    /** Что именно нажали: пункт рейки меню или кнопку на экране итогов забега. */
    public static final String SLOT_NAV = "nav";

    /**
     * Просьба игрока увести его на сервер, заданный владельцем.
     *
     * <p>Куда именно — решает сервер: клиент присылает, какую кнопку нажали, а не имя
     * сервера. Иначе кнопка стала бы способом ходить по всей сети мимо её правил.
     *
     * <p>Во время забега уход запрещён: он останавливает игру всем троим, а нажать кнопку
     * можно и случайно. Забег заканчивают, а не покидают молча.
     *
     * @return ключ отказа или {@code null}, если просьба ушла прокси
     */
    public static String request(ServerPlayerEntity player, String slot) {
        String target = SLOT_NAV.equals(slot)
                ? TwmConfig.get().announceNavServer()
                : TwmConfig.get().announceButtonServer();
        if (target.isEmpty()) {
            return "twm.err.unknown_action";
        }
        if (PartyManager.isInActiveParty(player.getUuid())) {
            return "twm.err.connect_in_run";
        }
        send(player, target);
        return null;
    }

    public static void send(ServerPlayerEntity player, String server) {
        if (server.isEmpty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            // ByteArrayOutputStream не бросает — но подпись обязывает, а молчать нельзя.
            Twm.LOGGER.warn("Просьба к прокси не собрана: {}", e.toString());
            return;
        }
        player.networkHandler.sendPacket(
                new CustomPayloadS2CPacket(new TwmPayloads.ProxyConnect(bytes.toByteArray())));
    }
}
