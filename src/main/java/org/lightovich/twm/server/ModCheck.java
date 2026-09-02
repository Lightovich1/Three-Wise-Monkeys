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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.lightovich.twm.Twm;
import org.lightovich.twm.net.TwmPayloads;
import org.lightovich.twm.server.proxy.NetworkConnect;

/**
 * Отсев клиентов без мода.
 *
 * <p>Без мода игрок видит пустой мир и не понимает, куда попал: весь режим — интерфейс,
 * восприятие и клавиши — живёт на клиенте. Ваниль такого игрока не остановит: сервер для неё
 * обычный, и войти на него можно чем угодно.
 *
 * <p>Проверка отложена на несколько секунд не для красоты: список каналов приезжает от
 * клиента отдельным пакетом уже после входа в мир, и спросить о нём в момент входа —
 * значит выгнать всех, включая тех, у кого мод есть.
 *
 * <p>Выдворение идёт в два шага: сначала объяснение, и только через пару секунд — уход.
 * Причина в устройстве сети: <b>прокси съедает причину кика</b>. BungeeCord на кик с бэкенда
 * молча уводит игрока на резервный сервер, и текст, вложенный в отключение, не показывается
 * никогда — человека просто выбрасывает без объяснений. Поэтому объяснение уходит туда, что
 * прокси не трогает: чат, титул и строка над горячей панелью. Чат вдобавок переживает переход
 * на другой сервер, так что сообщение остаётся перед глазами уже в хабе.
 */
public final class ModCheck {

    /** Запас на приезд списка каналов. Клиент шлёт его сразу, секунды хватает с избытком. */
    private static final int GRACE_TICKS = 100;

    /** Пауза между объяснением и уходом: за это время клиент успевает отрисовать текст. */
    private static final int NOTICE_TICKS = 60;

    /**
     * Обратный отсчёт на игрока. Положительное значение — ждём список каналов, отрицательное —
     * объяснение уже показано, ждём момента увести.
     */
    private static final Map<UUID, Integer> PENDING = new HashMap<>();

    private ModCheck() {
    }

    public static void onJoin(ServerPlayerEntity player) {
        if (!TwmConfig.get().requireMod()) {
            return;
        }
        PENDING.put(player.getUuid(), GRACE_TICKS);
    }

    public static void onDisconnect(ServerPlayerEntity player) {
        PENDING.remove(player.getUuid());
    }

    public static void clear() {
        PENDING.clear();
    }

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int countdown = entry.getValue();
            if (countdown > 1 || countdown < -1) {
                entry.setValue(countdown > 0 ? countdown - 1 : countdown + 1);
                continue;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (countdown > 0) {
                if (ServerPlayNetworking.canSend(player, TwmPayloads.LobbyState.ID)) {
                    iterator.remove();
                    continue;
                }
                explain(player);
                entry.setValue(-NOTICE_TICKS);
                continue;
            }
            iterator.remove();
            evict(player);
        }
    }

    /** Объяснение тремя путями сразу: титул виден мгновенно, чат — остаётся. */
    private static void explain(ServerPlayerEntity player) {
        String message = TwmConfig.get().modMissingMessage();
        String url = TwmConfig.get().modMissingUrl();

        player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 60, 20));
        player.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal("Нужен мод").formatted(Formatting.GOLD)));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("Three Wise Monkeys").formatted(Formatting.GRAY)));
        player.networkHandler.sendPacket(new OverlayMessageS2CPacket(
                Text.literal(message).formatted(Formatting.YELLOW)));

        player.sendMessage(Text.empty(), false);
        player.sendMessage(Text.literal(message).formatted(Formatting.YELLOW), false);
        if (!url.isEmpty()) {
            MutableText link = Text.literal(url).setStyle(Style.EMPTY
                    .withColor(Formatting.AQUA)
                    .withUnderline(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Text.literal("Открыть ссылку"))));
            player.sendMessage(link, false);
        }
        player.sendMessage(Text.empty(), false);
    }

    /**
     * Уход. На сервере в сети игрока возвращают на соседний сервер, а не отключают: кик
     * прокси всё равно превратит в молчаливый переброс, а так человек хотя бы попадает
     * туда, куда мы его отправили, и с текстом в чате.
     */
    private static void evict(ServerPlayerEntity player) {
        String fallback = TwmConfig.get().modMissingServer();
        String name = player.getName().getString();
        if (!fallback.isEmpty() && TwmConfig.get().proxyEnabled()) {
            Twm.LOGGER.info("{} вошёл без мода — возвращаю на {}", name, fallback);
            NetworkConnect.send(player, fallback);
            return;
        }
        Twm.LOGGER.info("{} вошёл без мода — отключаю", name);
        player.networkHandler.disconnect(Text.literal(TwmConfig.get().modMissingMessage()));
    }
}
