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

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.lightovich.twm.server.proxy.NetworkConnect;
import org.lightovich.twm.Difficulty;
import org.lightovich.twm.Msg;
import org.lightovich.twm.Role;
import org.lightovich.twm.party.LobbyManager;
import org.lightovich.twm.server.arena.Arena;
import org.lightovich.twm.server.arena.ArenaManager;

/**
 * Командный путь к тем же действиям лобби. Он не отладочный: без клиентского мода это
 * единственный способ собрать команду, и по нему же гоняются тесты на ванильных ботах.
 * Никакой своей логики прав здесь нет — все проверки живут в лобби.
 */
public final class TwmCommands {

    private TwmCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("twm")
                .then(literal("create")
                        .executes(context -> run(context, player ->
                                LobbyManager.create(player, "", false, "", Difficulty.EASY)))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(context -> run(context, player -> LobbyManager.create(player,
                                        StringArgumentType.getString(context, "name"),
                                        false, "", Difficulty.EASY)))))
                .then(literal("join")
                        .then(argument("code", StringArgumentType.word())
                                .executes(context -> run(context, player -> LobbyManager.join(player,
                                        StringArgumentType.getString(context, "code"), "")))
                                .then(argument("password", StringArgumentType.greedyString())
                                        .executes(context -> run(context, player -> LobbyManager.join(player,
                                                StringArgumentType.getString(context, "code"),
                                                StringArgumentType.getString(context, "password")))))))
                .then(literal("list").executes(TwmCommands::listLobbies))
                .then(literal("quick").executes(context -> run(context, LobbyManager::quickPlay)))
                .then(literal("leave").executes(context -> run(context, LobbyManager::leave)))
                .then(literal("role")
                        .then(argument("role", StringArgumentType.word())
                                .executes(context -> run(context, player -> selectRole(player,
                                        StringArgumentType.getString(context, "role"))))))
                .then(literal("difficulty")
                        .then(argument("difficulty", StringArgumentType.word())
                                .executes(context -> run(context, player -> Difficulty
                                        .fromId(StringArgumentType.getString(context, "difficulty"))
                                        .map(difficulty -> LobbyManager.setDifficulty(player, difficulty))
                                        .orElse("twm.chat.usage_difficulty")))))
                // Чатовый двойник кнопки на экране итогов: у игрока, закрывшего меню,
                // кнопки перед глазами нет, а уйти на соседний сервер он вправе так же.
                .then(literal("connect").executes(context -> run(context,
                        player -> NetworkConnect.request(player, ""))))
                .then(literal("start").executes(context -> run(context, LobbyManager::start)))
                .then(literal("stop").executes(context -> run(context, LobbyManager::stop)))
                .then(literal("invite")
                        .then(argument("player", EntityArgumentType.player())
                                .executes(TwmCommands::invitePlayer)))
                .then(literal("accept")
                        .executes(context -> run(context, player -> LobbyManager.accept(player, "")))
                        .then(argument("code", StringArgumentType.word())
                                .executes(context -> run(context, player -> LobbyManager.accept(player,
                                        StringArgumentType.getString(context, "code"))))))
                .then(admin()));
    }

    /**
     * Ветка владельца сервера. Она не про игру: игрок здесь ничего не выигрывает, а
     * администратору без неё нечем ни посмотреть нагрузку от арен, ни разжать зависший забег,
     * ни перечитать конфиг, не роняя сервер.
     *
     * <p>Работает и из консоли: проверять, поднимаются ли арены на этой машине, приходится
     * до того, как на сервер зашёл первый игрок.
     */
    private static LiteralArgumentBuilder<ServerCommandSource> admin() {
        return literal("admin")
                .requires(source -> source.hasPermissionLevel(3))
                .then(literal("arenas").executes(TwmCommands::listArenas))
                .then(literal("probe").executes(TwmCommands::probeArena))
                .then(literal("reload").executes(context -> {
                    TwmConfig.load();
                    context.getSource().sendFeedback(() -> Text.literal(
                            "twm-server.json перечитан"), true);
                    return 1;
                }))
                .then(literal("stop")
                        .then(argument("code", StringArgumentType.word())
                                .executes(TwmCommands::stopByCode)));
    }

    private static int listArenas(CommandContext<ServerCommandSource> context) {
        Map<String, Arena> arenas = ArenaManager.active();
        if (arenas.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("Активных арен нет"), false);
            return 0;
        }
        arenas.forEach((code, arena) -> context.getSource().sendFeedback(() -> Text.literal(
                code + "  сид " + arena.seed()
                        + "  центр " + arena.center().getX() + "/" + arena.center().getZ()
                        + "  крепость " + (arena.stronghold() == null
                                ? "нет" : arena.stronghold().toShortString())
                        + "  спавн " + arena.spawn().toShortString()), false));
        return arenas.size();
    }

    /**
     * Пробная арена: создаётся и тут же удаляется. Отвечает на единственный вопрос, который
     * нельзя проверить чтением конфига, — поднимаются ли три мира на этой машине и находится
     * ли по сиду крепость с порталом. Заодно показывает, сколько это стоит по времени.
     */
    private static int probeArena(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        long started = System.nanoTime();
        Arena arena = ArenaManager.open(source.getServer(), "PROBE");
        if (arena == null) {
            source.sendError(Text.literal("Арена не создалась — смотри лог сервера"));
            return 0;
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        String stronghold = arena.stronghold() == null
                ? "НЕ НАЙДЕНА — портала в Энд на этом сиде в границах арены не будет"
                : arena.stronghold().toShortString();
        source.sendFeedback(() -> Text.literal(
                "Арена поднялась за " + millis + " мс\n"
                        + "  сид " + arena.seed() + "\n"
                        + "  центр " + arena.center().getX() + "/" + arena.center().getZ() + "\n"
                        + "  спавн " + arena.spawn().toShortString() + "\n"
                        + "  крепость " + stronghold), false);
        ArenaManager.close("PROBE");
        return 1;
    }

    private static int stopByCode(CommandContext<ServerCommandSource> context) {
        String code = StringArgumentType.getString(context, "code");
        boolean stopped = LobbyManager.stopByCode(context.getSource().getServer(), code);
        context.getSource().sendFeedback(() -> Text.literal(stopped
                ? "Забег " + code + " остановлен"
                : "Забег " + code + " не найден или уже не идёт"), true);
        return stopped ? 1 : 0;
    }

    /** Единый хвост: отказ — в чат, свежий снимок — всегда, даже когда действие не прошло. */
    private static int run(CommandContext<ServerCommandSource> context,
                           CommandAction action) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String error = action.apply(player);
        if (error != null) {
            player.sendMessage(Msg.text(error).copy().formatted(Formatting.RED), false);
        }
        LobbyManager.push(player, error == null ? "" : error);
        return error == null ? 1 : 0;
    }

    /**
     * Цель приглашения достаётся до {@link #run}: {@code getPlayer} бросает проверяемое
     * исключение, а действие лобби по контракту возвращает текст отказа и ничего не бросает.
     */
    private static int invitePlayer(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        String target = EntityArgumentType.getPlayer(context, "player").getName().getString();
        return run(context, player -> LobbyManager.invite(player, target));
    }

    /** Отдельное слово {@code clear} вместо ещё одной ветки: роль освобождается тем же вводом. */
    private static String selectRole(ServerPlayerEntity player, String argument) {
        if ("clear".equalsIgnoreCase(argument)) {
            return LobbyManager.clearRole(player);
        }
        return Role.fromId(argument)
                .map(role -> LobbyManager.chooseRole(player, role))
                .orElse("twm.chat.usage_role");
    }

    private static int listLobbies(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        List<String> lines = LobbyManager.describeLobbies(player.getServer());
        if (lines.isEmpty()) {
            player.sendMessage(Text.translatable("twm.chat.no_lobbies"), false);
            return 1;
        }
        for (String line : lines) {
            player.sendMessage(Text.literal(line), false);
        }
        return lines.size();
    }

    /** Действие лобби возвращает текст отказа или {@code null}; исключений оно не бросает. */
    @FunctionalInterface
    private interface CommandAction extends Function<ServerPlayerEntity, String> {
    }
}
