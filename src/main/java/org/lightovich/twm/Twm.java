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

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.lightovich.twm.net.TwmPayloads;
import org.lightovich.twm.party.LobbyManager;
import org.lightovich.twm.party.PartyManager;
import org.lightovich.twm.server.Limits;
import org.lightovich.twm.server.ModCheck;
import org.lightovich.twm.server.RoleRules;
import org.lightovich.twm.server.TwmCommands;
import org.lightovich.twm.server.TwmConfig;
import org.lightovich.twm.server.arena.ArenaManager;
import org.lightovich.twm.server.lobby.VoidLobby;

/** Общий инициализатор: сеть, права ролей, жизненный цикл команды. */
public final class Twm implements ModInitializer {

    public static final String MOD_ID = "twm";
    public static final Logger LOGGER = LoggerFactory.getLogger("ThreeWiseMonkeys");

    @Override
    public void onInitialize() {
        TwmConfig.load();
        TwmSounds.initialize();
        TwmPayloads.register();
        RoleRules.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                TwmCommands.register(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PartyManager.tick(server);
            LobbyManager.tick(server);
            ModCheck.tick(server);
        });

        // Пустой мир под лобби поднимается один раз на запуск: он персистентный, и его
        // содержимое (платформа) переживает рестарт вместе с самим миром.
        ServerLifecycleEvents.SERVER_STARTED.register(VoidLobby::open);

        // Вошедший обязан сразу увидеть список лобби, а хосты — обновлённых кандидатов:
        // снимок собирает сервер, клиент не опрашивает состояние сам.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                LobbyManager.onJoin(handler.getPlayer()));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LobbyManager.onDisconnect(handler.getPlayer()));

        // Штатная остановка обязана снять с ролей приключение, невидимость и неуязвимость:
        // иначе они уедут в playerdata, а команды, которая могла бы их снять, после рестарта
        // уже не будет — лобби живёт только в памяти. Аварийное падение ловит слепок на входе.
        ServerLifecycleEvents.SERVER_STOPPING.register(LobbyManager::onServerStopping);

        // Одиночный мир и локальная сеть живут в том же процессе, что и клиент: без сброса
        // статика пережила бы выход из мира и раздала роли игрокам следующего.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LobbyManager.clearAll();
            ArenaManager.clearAll();
            Limits.clear();
            ModCheck.clear();
            VoidLobby.close();
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity player) {
                PartyManager.onDeath(player);
            } else if (entity instanceof EnderDragonEntity
                    && entity.getWorld() instanceof ServerWorld world) {
                PartyManager.onDragonKilled(world);
            }
        });

        // Сущность игрока после возрождения новая, и ваниль переносит в неё инвентарь только
        // при keepInventory. Голове и ногам вещи переносим сами: ронять их было не за что.
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) ->
                PartyManager.carryBelongings(oldPlayer, newPlayer));

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                PartyManager.onRespawn(newPlayer));

        // Лимит проверяется в сетевом потоке, до передачи в серверный: смысл лимита в том,
        // чтобы поток тиков вообще не увидел поток мусора.
        ServerPlayNetworking.registerGlobalReceiver(TwmPayloads.HeadDrop.ID, (payload, context) -> {
            if (!Limits.allow(context.player().getUuid(), Limits.Channel.DROP)) {
                return;
            }
            context.server().execute(() -> PartyManager.dropFromHead(context.player()));
        });

        ServerPlayNetworking.registerGlobalReceiver(TwmPayloads.LobbyAction.ID, (payload, context) ->
                context.server().execute(() -> LobbyManager.handleAction(
                        context.player(), payload.action(), payload.argument())));

        ServerPlayNetworking.registerGlobalReceiver(TwmPayloads.ThoughtSend.ID, (payload, context) -> {
            if (!Limits.allow(context.player().getUuid(), Limits.Channel.THOUGHT)) {
                return;
            }
            context.server().execute(() -> {
                ServerPlayerEntity sender = context.player();
                // Сигнал обязан быть из словаря, а адресатов выбирает только сервер:
                // клиенту нельзя доверять ни текст мысли, ни то, кому она уйдёт.
                Thought.fromId(payload.signal()).ifPresent(thought -> {
                    for (ServerPlayerEntity recipient : PartyManager.thoughtRecipients(sender)) {
                        ServerPlayNetworking.send(recipient, new TwmPayloads.ThoughtReceive(
                                thought.id(), sender.getName().getString()));
                    }
                });
            });
        });

        LOGGER.info("Three Wise Monkeys загружен");
    }
}
