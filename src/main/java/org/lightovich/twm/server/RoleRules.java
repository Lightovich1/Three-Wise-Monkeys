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

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;

import org.lightovich.twm.Role;
import org.lightovich.twm.party.PartyManager;

/**
 * Серверные права ролей. Клиентское скрытие интерфейса — только презентация; настоящий
 * запрет живёт здесь, поэтому подменённый клиент ничего не выигрывает.
 *
 * <p>Голова и ноги переведены в приключение, так что ломание и установка блоков уже
 * отсекаются самой игрой. Эти проверки закрывают остальные пути: предметы, сущности,
 * контейнеры, чат и PvP внутри команды.
 */
public final class RoleRules {

    private RoleRules() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) ->
                allowed(player));

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                allowed(player) ? ActionResult.PASS : ActionResult.FAIL);

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                allowed(player) ? ActionResult.PASS : ActionResult.FAIL);

        UseItemCallback.EVENT.register((player, world, hand) ->
                allowed(player)
                        ? TypedActionResult.pass(player.getStackInHand(hand))
                        : TypedActionResult.fail(player.getStackInHand(hand)));

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                allowed(player) ? ActionResult.PASS : ActionResult.FAIL);

        AttackEntityCallback.EVENT.register((player, world, hand, target, hitResult) ->
                canAttack(player, target) ? ActionResult.PASS : ActionResult.FAIL);

        // Немой не говорит вообще: ни голосом, ни текстом. Это его единственное ограничение,
        // ради которого существует панель мыслей.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (muted(sender)) {
                sender.sendMessage(Text.translatable("twm.chat.head_muted"), true);
                return false;
            }
            return true;
        });

        // Тот же запрет для команд, которые несут текст: /msg, /tell, /me, /say, /teammsg.
        // Без него немота обходилась одной командой — а вместе с ней обходилась вся роль,
        // потому что панель мыслей нужна ровно там, где сказать словами нельзя.
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, params) -> {
            ServerPlayerEntity sender = senderOf(source);
            if (sender != null && muted(sender)) {
                sender.sendMessage(Text.translatable("twm.chat.head_muted"), true);
                return false;
            }
            return true;
        });
    }

    private static boolean allowed(PlayerEntity player) {
        return PartyManager.canInteract(player.getUuid());
    }

    /** Немой — только тот, кто прямо сейчас играет головой. Вне забега говорят все. */
    private static boolean muted(ServerPlayerEntity player) {
        return PartyManager.roleOf(player.getUuid()).orElse(null) == Role.HEAD
                && PartyManager.isInActiveParty(player.getUuid());
    }

    /** Команду мог выполнить и не игрок: консоль, командный блок, функция. */
    private static ServerPlayerEntity senderOf(ServerCommandSource source) {
        return source.getEntity() instanceof ServerPlayerEntity player ? player : null;
    }

    /**
     * Внутри команды PvP запрещён, иначе один участник мог бы убить общее тело.
     *
     * <p>Именно внутри: проверять «цель играет» вместо «цель в моей команде» значило бы
     * сделать любую играющую команду неуязвимой для всего сервера — начал забег и стал
     * бессмертным в PvP. Мод обязан оставаться безвредным для мира, в котором стоит.
     */
    private static boolean canAttack(PlayerEntity player, Entity target) {
        if (!allowed(player)) {
            return false;
        }
        return !(target instanceof ServerPlayerEntity targetPlayer)
                || !PartyManager.shareParty(player.getUuid(), targetPlayer.getUuid());
    }
}
