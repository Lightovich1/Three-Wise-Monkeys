/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import org.lightovich.twm.Twm;

/**
 * Все пакеты мода. Клиент никогда не является источником прав: он присылает намерение,
 * сервер проверяет роль и активность команды.
 */
public final class TwmPayloads {

    private TwmPayloads() {
    }

    private static Identifier id(String path) {
        return Identifier.of(Twm.MOD_ID, path);
    }

    /**
     * S2C. Назначение роли клиенту. {@code role} пустая строка означает выход из команды.
     * Список участников нужен клиенту, чтобы не рендерить и не прицеливаться в напарников.
     */
    public record RoleSync(String role, String difficulty, int echoCooldownTicks,
                           List<UUID> members) implements CustomPayload {
        public static final CustomPayload.Id<RoleSync> ID = new CustomPayload.Id<>(id("role_sync"));
        public static final PacketCodec<PacketByteBuf, RoleSync> CODEC =
                PacketCodec.of(RoleSync::write, RoleSync::read);

        private void write(PacketByteBuf buf) {
            buf.writeString(role);
            buf.writeString(difficulty);
            buf.writeVarInt(echoCooldownTicks);
            buf.writeVarInt(members.size());
            for (UUID member : members) {
                buf.writeUuid(member);
            }
        }

        private static RoleSync read(PacketByteBuf buf) {
            String role = buf.readString();
            String difficulty = buf.readString();
            int echoCooldownTicks = buf.readVarInt();
            int size = buf.readVarInt();
            List<UUID> members = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                members.add(buf.readUuid());
            }
            return new RoleSync(role, difficulty, echoCooldownTicks, members);
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * S2C. Положение общего тела и направление ног, отправляется каждый серверный тик
     * пассивным ролям и самим рукам. Поворот применяется только в locked-режиме камеры.
     */
    public record BodyState(double x, double y, double z, boolean onGround, boolean sneaking,
                            float yaw, float pitch) implements CustomPayload {
        public static final CustomPayload.Id<BodyState> ID = new CustomPayload.Id<>(id("body_state"));
        public static final PacketCodec<PacketByteBuf, BodyState> CODEC =
                PacketCodec.of(BodyState::write, BodyState::read);

        private void write(PacketByteBuf buf) {
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeBoolean(onGround);
            buf.writeBoolean(sneaking);
            buf.writeFloat(yaw);
            buf.writeFloat(pitch);
        }

        private static BodyState read(PacketByteBuf buf) {
            return new BodyState(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat());
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** C2S. Слепой выброс предмета головой: сервер сам берёт предмет из выбранного слота рук. */
    public record HeadDrop() implements CustomPayload {
        public static final CustomPayload.Id<HeadDrop> ID = new CustomPayload.Id<>(id("head_drop"));
        public static final PacketCodec<PacketByteBuf, HeadDrop> CODEC =
                PacketCodec.of((value, buf) -> {}, buf -> new HeadDrop());

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** C2S. Мысль головы: сервер проверяет роль и рассылает получателям сам. */
    public record ThoughtSend(String signal) implements CustomPayload {
        public static final CustomPayload.Id<ThoughtSend> ID = new CustomPayload.Id<>(id("thought_send"));
        public static final PacketCodec<PacketByteBuf, ThoughtSend> CODEC =
                PacketCodec.of(ThoughtSend::write, ThoughtSend::read);

        private void write(PacketByteBuf buf) {
            buf.writeString(signal);
        }

        private static ThoughtSend read(PacketByteBuf buf) {
            return new ThoughtSend(buf.readString());
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** S2C. Полученная мысль вместе с ником отправителя для истории в интерфейсе. */
    public record ThoughtReceive(String signal, String sender) implements CustomPayload {
        public static final CustomPayload.Id<ThoughtReceive> ID = new CustomPayload.Id<>(id("thought_receive"));
        public static final PacketCodec<PacketByteBuf, ThoughtReceive> CODEC =
                PacketCodec.of(ThoughtReceive::write, ThoughtReceive::read);

        private void write(PacketByteBuf buf) {
            buf.writeString(signal);
            buf.writeString(sender);
        }

        private static ThoughtReceive read(PacketByteBuf buf) {
            return new ThoughtReceive(buf.readString(), buf.readString());
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * S2C. Снимок лобби в виде JSON — его разбирает веб-интерфейс. Состояние собирает только
     * сервер: клиент не решает, кого можно пригласить и какая роль свободна.
     */
    public record LobbyState(String json) implements CustomPayload {
        public static final CustomPayload.Id<LobbyState> ID = new CustomPayload.Id<>(id("lobby_state"));
        public static final PacketCodec<PacketByteBuf, LobbyState> CODEC =
                PacketCodec.of(LobbyState::write, LobbyState::read);

        private void write(PacketByteBuf buf) {
            buf.writeString(json, 32767);
        }

        private static LobbyState read(PacketByteBuf buf) {
            return new LobbyState(buf.readString(32767));
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * C2S. Намерение из лобби: открыть, создать, войти, подбор, роль, старт, выйти.
     *
     * <p>Аргумент — либо простая строка (ник, код роли), либо JSON: у создания лобби и входа
     * по паролю несколько полей. Поэтому запас длины больше, чем у прежней версии с одним ником.
     */
    public record LobbyAction(String action, String argument) implements CustomPayload {
        public static final CustomPayload.Id<LobbyAction> ID = new CustomPayload.Id<>(id("lobby_action"));
        public static final PacketCodec<PacketByteBuf, LobbyAction> CODEC =
                PacketCodec.of(LobbyAction::write, LobbyAction::read);

        private void write(PacketByteBuf buf) {
            buf.writeString(action, 32);
            buf.writeString(argument, 1024);
        }

        private static LobbyAction read(PacketByteBuf buf) {
            return new LobbyAction(buf.readString(32), buf.readString(1024));
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * S2C. Итоги забега — JSON, как и снимок лобби, и по той же причине: его читает страница.
     *
     * <p>Считает их только сервер. Клиент не знает ни числа смертей чужих ролей, ни того,
     * дошло ли общее тело до Энда: он видит мир глазами одной роли, а итог — общий.
     */
    public record RunSummary(String json) implements CustomPayload {
        public static final CustomPayload.Id<RunSummary> ID = new CustomPayload.Id<>(id("run_summary"));
        public static final PacketCodec<PacketByteBuf, RunSummary> CODEC =
                PacketCodec.of(RunSummary::write, RunSummary::read);

        private void write(PacketByteBuf buf) {
            buf.writeString(json, 4096);
        }

        private static RunSummary read(PacketByteBuf buf) {
            return new RunSummary(buf.readString(4096));
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * S2C. Разовое уведомление поверх игры: приглашение, найденный подбор, отказ по паролю.
     *
     * <p>Отдельно от снимка лобби потому, что тоаст обязан показаться и с закрытым меню, а
     * снимок описывает состояние, а не событие: повторная отправка того же состояния не должна
     * заново мигать уведомлением.
     */
    public record Notify(String kind, String title, String text) implements CustomPayload {
        public static final CustomPayload.Id<Notify> ID = new CustomPayload.Id<>(id("notify"));
        public static final PacketCodec<PacketByteBuf, Notify> CODEC =
                PacketCodec.of(Notify::write, Notify::read);

        private void write(PacketByteBuf buf) {
            buf.writeString(kind, 16);
            buf.writeString(title, 64);
            buf.writeString(text, 256);
        }

        private static Notify read(PacketByteBuf buf) {
            return new Notify(buf.readString(16), buf.readString(64), buf.readString(256));
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Регистрирует типы пакетов. Вызывается из общего инициализатора, до обработчиков. */
    /**
     * S2C на чужом канале: просьба к прокси перевести игрока на другой сервер сети.
     *
     * <p>Канал {@code bungeecord:main} принадлежит не моду, а протоколу BungeeCord, поэтому
     * тело пакета — сырые байты в формате {@code DataOutputStream}, а не наши поля. До
     * клиента пакет не доходит: прокси снимает его по дороге. Регистрация нужна лишь затем,
     * чтобы ваниль умела его закодировать.
     */
    public record ProxyConnect(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<ProxyConnect> ID =
                new CustomPayload.Id<>(Identifier.of("bungeecord", "main"));
        public static final PacketCodec<PacketByteBuf, ProxyConnect> CODEC =
                PacketCodec.of(ProxyConnect::write, ProxyConnect::read);

        private void write(PacketByteBuf buf) {
            buf.writeBytes(data);
        }

        private static ProxyConnect read(PacketByteBuf buf) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new ProxyConnect(bytes);
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(RoleSync.ID, RoleSync.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(BodyState.ID, BodyState.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(ThoughtReceive.ID, ThoughtReceive.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(LobbyState.ID, LobbyState.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(Notify.ID, Notify.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(RunSummary.ID, RunSummary.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(ProxyConnect.ID, ProxyConnect.CODEC);

        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(HeadDrop.ID, HeadDrop.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(ThoughtSend.ID, ThoughtSend.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(LobbyAction.ID, LobbyAction.CODEC);
    }
}
