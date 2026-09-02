/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.lightovich.twm.Difficulty;
import org.lightovich.twm.Msg;
import org.lightovich.twm.Role;
import org.lightovich.twm.Twm;
import org.lightovich.twm.net.TwmPayloads;
import org.lightovich.twm.server.Limits;
import org.lightovich.twm.server.ModCheck;
import org.lightovich.twm.server.lobby.VoidLobby;
import org.lightovich.twm.server.proxy.NetworkConnect;
import org.lightovich.twm.server.PlayerRestore;
import org.lightovich.twm.server.TwmConfig;
import org.lightovich.twm.server.VoiceRules;
import org.lightovich.twm.server.arena.ArenaManager;
import org.lightovich.twm.server.arena.ArenaManager;

/**
 * Лобби: реестр комнат, подбор, приглашения и снимок состояния для интерфейса.
 *
 * <p>Здесь же живёт единственный реестр «кто в какой команде» — {@link #BY_MEMBER}. Игровая
 * часть ({@link PartyManager}) только спрашивает его, но не ведёт свой список: два реестра
 * разошлись бы при первом же обрыве связи.
 *
 * <p>Права проверяются только тут. Клиент присылает намерение, любое нарушение — молчаливый
 * отказ и тоаст {@code kind:"error"}; исключение до клиента не доходит никогда.
 */
public final class LobbyManager {

    /** Похожие символы (0/O, 1/I) выброшены: код диктуют голосом и вводят руками. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 4;
    private static final long INVITE_LIFETIME_MS = 60_000L;

    /** Приглашения истекают редко: проверять их каждый тик — впустую жечь такт. */
    private static final int EXPIRY_PERIOD_TICKS = 20;

    /**
     * Единственная карта, которую читают не только из серверного потока: миксины общего
     * конфига ({@code PlayerEntityMixin}, {@code EntityMixin}) спрашивают её и на клиенте.
     * На выделенном сервере клиентская копия всегда пуста, но в одиночке и по локальной сети
     * это один процесс и два потока — обычная {@code HashMap} там ловит гонку на перестройке.
     */
    private static final Map<UUID, Party> BY_MEMBER = new ConcurrentHashMap<>();

    /** Порядок вставки — это порядок комнат в списке: он обязан не прыгать между снимками. */
    private static final Map<String, Party> BY_CODE = new LinkedHashMap<>();
    private static final List<UUID> QUEUE = new ArrayList<>();
    private static final List<Invite> INVITES = new ArrayList<>();
    private static final Random RANDOM = new Random();

    /** Неудачные попытки пароля: код лобби виден в списке, значит пароль можно перебирать. */
    private static final Map<UUID, PasswordAttempts> PASSWORD_TRIES = new HashMap<>();

    private static int expiryCountdown = EXPIRY_PERIOD_TICKS;

    private LobbyManager() {
    }

    /** Приглашение привязано к коду лобби, а не к хосту: хост может смениться до ответа. */
    private record Invite(UUID target, UUID from, String code, long expiresAt) {
    }

    /** Счётчик попыток пароля в скользящем окне. Считается на игрока, а не на лобби. */
    private static final class PasswordAttempts {
        private int failures;
        private long windowStart;
    }

    // --- Доступ для игровой части -----------------------------------------

    static Party partyOf(UUID playerId) {
        return BY_MEMBER.get(playerId);
    }

    /** Лобби по коду — для игровой части: она знает арену, а арена знает только код. */
    static Party byCode(String code) {
        return BY_CODE.get(code);
    }

    /** Забег по коду лобби — для админских команд. {@code null}, если такого лобби нет. */
    public static boolean stopByCode(MinecraftServer server, String code) {
        Party party = BY_CODE.get(normalizeCode(code));
        if (party == null || !party.active) {
            return false;
        }
        PartyManager.stopGame(server, party, Msg.text("twm.chat.stopped_admin"));
        notifyMembers(server, party, "warning", "twm.notify.game_stopped",
                "twm.notify.back_to_lobby", null);
        broadcast(server, party, null);
        return true;
    }

    /** Копия списка: игровой цикл может остановить игру прямо во время обхода. */
    static List<Party> playingParties() {
        List<Party> playing = new ArrayList<>();
        for (Party party : BY_CODE.values()) {
            if (party.active) {
                playing.add(party);
            }
        }
        return playing;
    }

    /** Аварийная остановка из игрового цикла: состав распался, вести общее тело нечем. */
    static void forceStop(MinecraftServer server, Party party, String reason) {
        party.rotatedLastRun = false;
        PartyManager.stopGame(server, party, Msg.text(reason));
        notifyMembers(server, party, "warning", "twm.notify.game_stopped", reason, null);
        broadcast(server, party, null);
    }

    // --- Приём намерений ---------------------------------------------------

    /** Разбор намерения из веб-лобби. Неизвестное действие ничем не отличается от отказа. */
    public static void handleAction(ServerPlayerEntity player, String action, String argument) {
        // Отказ по частоте — молчаливый. Ответить на него значило бы усилить один входящий
        // пакет в один исходящий, то есть оставить ровно тот канал, ради которого лимит и стоит.
        if (!Limits.allow(player.getUuid(), Limits.Channel.LOBBY)) {
            return;
        }
        String error = dispatch(player, action, argument);
        if (error != null) {
            notify(player, "error", "twm.notify.lobby", error);
        }
        push(player, error == null ? "" : error);
    }

    private static String dispatch(ServerPlayerEntity player, String action, String argument) {
        switch (action) {
            case "open" -> {
                return null;
            }
            case "create" -> {
                JsonObject json = parse(argument);
                return create(player, text(json, "name", Party.NAME_LIMIT), flag(json, "private"),
                        text(json, "password", Party.PASSWORD_LIMIT),
                        Difficulty.fromId(text(json, "difficulty", 8)).orElse(Difficulty.EASY));
            }
            case "join" -> {
                JsonObject json = parse(argument);
                return join(player, text(json, "code", 8), text(json, "password", Party.PASSWORD_LIMIT));
            }
            case "quick" -> {
                return quickPlay(player);
            }
            case "queue_leave" -> {
                return leaveQueue(player);
            }
            case "invite" -> {
                return invite(player, argument);
            }
            case "accept" -> {
                return accept(player, argument);
            }
            case "decline" -> {
                return decline(player, argument);
            }
            case "role" -> {
                Role role = Role.fromId(argument).orElse(null);
                return role == null ? "twm.err.unknown_role" : chooseRole(player, role);
            }
            case "role_clear" -> {
                return clearRole(player);
            }
            case "rotate" -> {
                return toggleRotation(player);
            }
            case "difficulty" -> {
                Difficulty difficulty = Difficulty.fromId(argument).orElse(null);
                return difficulty == null ? "twm.err.unknown_difficulty" : setDifficulty(player, difficulty);
            }
            case "echo_cooldown" -> {
                return setEchoCooldown(player, seconds(argument));
            }
            case "lobby_settings" -> {
                JsonObject json = parse(argument);
                return updateSettings(player, text(json, "name", Party.NAME_LIMIT),
                        flag(json, "private"), text(json, "password", Party.PASSWORD_LIMIT));
            }
            case "start" -> {
                return start(player);
            }
            case "stop" -> {
                return stop(player);
            }
            case "leave" -> {
                return leave(player);
            }
            case "network_connect" -> {
                return NetworkConnect.request(player, argument);
            }
            default -> {
                return "twm.err.unknown_action";
            }
        }
    }

    // --- Действия ----------------------------------------------------------

    /** Возвращает текст отказа или {@code null}, если всё получилось. Так же — у всех действий. */
    public static String create(ServerPlayerEntity player, String name, boolean locked,
                                String password, Difficulty difficulty) {
        if (BY_MEMBER.containsKey(player.getUuid())) {
            return "twm.err.already_in_lobby";
        }
        Party party = openLobby(player, name, locked, password, difficulty);
        chatMembers(player.getServer(), party,
                Msg.text(Msg.of("twm.chat.lobby_created", party.code)).copy().formatted(Formatting.GREEN));
        broadcast(player.getServer(), party, player);
        return null;
    }

    public static String join(ServerPlayerEntity player, String code, String password) {
        if (BY_MEMBER.containsKey(player.getUuid())) {
            return "twm.err.already_in_lobby";
        }
        Party party = BY_CODE.get(normalizeCode(code));
        if (party == null) {
            return "twm.err.no_lobby_code";
        }
        if (party.active) {
            return "twm.err.lobby_playing";
        }
        if (!party.hasFreeSlot()) {
            return "twm.err.lobby_full";
        }
        // Приглашение заменяет пароль: хост уже подтвердил, что этого игрока ждут.
        boolean invited = takeInvite(player.getUuid(), party.code);
        if (party.locked && !invited && !party.password.equals(password)) {
            return notePasswordFailure(player.getUuid());
        }
        PASSWORD_TRIES.remove(player.getUuid());
        enter(player, party);
        return null;
    }

    /**
     * Подбор. Сначала случайное открытое лобби со свободным местом — так игроки собираются
     * вокруг уже начатых наборов, а не плодят пустые комнаты. Нет таких — очередь.
     */
    public static String quickPlay(ServerPlayerEntity player) {
        if (BY_MEMBER.containsKey(player.getUuid())) {
            return "twm.err.already_in_lobby";
        }
        List<Party> open = new ArrayList<>();
        for (Party party : BY_CODE.values()) {
            if (!party.active && !party.locked && party.hasFreeSlot()) {
                open.add(party);
            }
        }
        if (!open.isEmpty()) {
            Party party = open.get(RANDOM.nextInt(open.size()));
            enter(player, party);
            notify(player, "success", "twm.notify.matchmaking",
                    Msg.of("twm.notify.joined_lobby", title(player.getServer(), party)));
            return null;
        }
        if (!QUEUE.contains(player.getUuid())) {
            QUEUE.add(player.getUuid());
        }
        notify(player, "info", "twm.notify.matchmaking", Msg.of("twm.notify.queue_wait",
                String.valueOf(QUEUE.size()), String.valueOf(Party.MEMBER_COUNT)));
        if (!formLobbyFromQueue(player.getServer())) {
            broadcast(player.getServer(), null, player);
        }
        return null;
    }

    public static String leaveQueue(ServerPlayerEntity player) {
        if (!QUEUE.remove(player.getUuid())) {
            return "twm.err.not_in_queue";
        }
        broadcast(player.getServer(), null, player);
        return null;
    }

    public static String invite(ServerPlayerEntity host, String targetName) {
        Party party = BY_MEMBER.get(host.getUuid());
        if (party == null || !party.isHost(host.getUuid())) {
            return "twm.err.host_only_invite";
        }
        if (party.active) {
            return "twm.err.game_running";
        }
        if (!party.hasFreeSlot()) {
            return "twm.err.lobby_full";
        }
        ServerPlayerEntity target = host.getServer().getPlayerManager().getPlayer(targetName.trim());
        if (target == null) {
            return "twm.err.player_offline";
        }
        if (BY_MEMBER.containsKey(target.getUuid())) {
            return "twm.err.player_in_lobby";
        }
        INVITES.removeIf(invite -> invite.target().equals(target.getUuid())
                && invite.code().equals(party.code));
        INVITES.add(new Invite(target.getUuid(), host.getUuid(), party.code,
                System.currentTimeMillis() + INVITE_LIFETIME_MS));

        String from = host.getName().getString();
        notify(target, "invite", "twm.notify.invite",
                Msg.of("twm.notify.invite_text", from, title(host.getServer(), party)));
        target.sendMessage(Msg.text(Msg.of("twm.chat.invite", from, party.code))
                .copy().formatted(Formatting.AQUA), false);
        push(target, "");
        return null;
    }

    /** Пустой код принимается ради командного пути: у бота обычно одно приглашение. */
    public static String accept(ServerPlayerEntity player, String code) {
        Invite invite = findInvite(player.getUuid(), code);
        if (invite == null) {
            return "twm.err.invite_expired";
        }
        return join(player, invite.code(), "");
    }

    public static String decline(ServerPlayerEntity player, String code) {
        Invite invite = findInvite(player.getUuid(), code);
        if (invite == null) {
            return "twm.err.invite_missing";
        }
        INVITES.remove(invite);
        return null;
    }

    public static String chooseRole(ServerPlayerEntity player, Role role) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null) {
            return "twm.err.not_in_lobby";
        }
        if (party.active) {
            return "twm.err.roles_locked";
        }
        UUID occupant = party.roles.get(role);
        if (occupant != null && !occupant.equals(player.getUuid())) {
            return "twm.err.role_taken";
        }
        party.roles.values().removeIf(player.getUuid()::equals);
        party.roles.put(role, player.getUuid());
        chatMembers(player.getServer(), party, Msg.text(Msg.of("twm.chat.role_taken",
                player.getName().getString(), role.translationKey())));
        broadcast(player.getServer(), party, player);
        return null;
    }

    public static String clearRole(ServerPlayerEntity player) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null) {
            return "twm.err.not_in_lobby";
        }
        if (party.active) {
            return "twm.err.roles_locked";
        }
        if (!party.roles.values().removeIf(player.getUuid()::equals)) {
            return "twm.err.no_role";
        }
        broadcast(player.getServer(), party, player);
        return null;
    }

    public static String setDifficulty(ServerPlayerEntity player, Difficulty difficulty) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null || !party.isHost(player.getUuid())) {
            return "twm.err.host_only_difficulty";
        }
        if (party.active) {
            return "twm.err.difficulty_locked";
        }
        party.difficulty = difficulty;
        chatMembers(player.getServer(), party, Msg.text(Msg.of("twm.chat.difficulty",
                difficulty.translationKey())).copy().formatted(Formatting.GOLD));
        broadcast(player.getServer(), party, player);
        return null;
    }

    /**
     * Сдвиг ролей после забега. Правило команды, а не вкус игрока, поэтому им владеет хост
     * и меняется оно только до старта.
     */
    public static String toggleRotation(ServerPlayerEntity player) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null || !party.isHost(player.getUuid())) {
            return "twm.err.host_only_rotate";
        }
        if (party.active) {
            return "twm.err.difficulty_locked";
        }
        party.rotateRoles = !party.rotateRoles;
        broadcast(player.getServer(), party, player);
        return null;
    }

    /**
     * Кулдаун эхо-пинга — правило игры, а не вкус: он живёт в лобби рядом со сложностью и
     * едет всем троим в {@code RoleSync}. В клиентские настройки его выносить нельзя —
     * там ноги настроили бы себе непрерывный обзор.
     */
    public static String setEchoCooldown(ServerPlayerEntity player, int seconds) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null || !party.isHost(player.getUuid())) {
            return "twm.err.host_only_echo";
        }
        if (party.active) {
            return "twm.err.echo_locked";
        }
        party.echoCooldownTicks = Math.clamp(seconds * 20L,
                Party.MIN_ECHO_COOLDOWN_TICKS, Party.MAX_ECHO_COOLDOWN_TICKS);
        broadcast(player.getServer(), party, player);
        return null;
    }

    public static String updateSettings(ServerPlayerEntity player, String name, boolean locked,
                                        String password) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null || !party.isHost(player.getUuid())) {
            return "twm.err.host_only_settings";
        }
        // Посреди забега менять нечего: список комнат забег не показывает, а смена пароля
        // на живой игре — это способ запереть команду от вернувшегося участника.
        if (party.active) {
            return "twm.err.settings_locked";
        }
        party.name = name;
        party.locked = locked;
        party.password = password;
        broadcast(player.getServer(), party, player);
        return null;
    }

    public static String start(ServerPlayerEntity player) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null || !party.isHost(player.getUuid())) {
            return "twm.err.host_only_start";
        }
        if (party.active) {
            return "twm.err.game_running";
        }
        if (!party.readyToStart()) {
            return startHint(party, true);
        }
        MinecraftServer server = player.getServer();
        for (UUID memberId : party.members) {
            if (server.getPlayerManager().getPlayer(memberId) == null) {
                return "twm.err.member_offline";
            }
        }
        if (!PartyManager.startGame(server, party)) {
            return "twm.err.start_failed";
        }
        notifyMembers(server, party, "success", "twm.notify.game_started",
                Msg.of("twm.notify.difficulty_is", party.difficulty.translationKey()), null);
        chatMembers(server, party, Msg.text("twm.chat.game_started").copy().formatted(Formatting.GREEN));
        briefVoice(server, party);
        broadcast(server, party, null);
        return null;
    }

    /**
     * Голосовые права проговариваются на старте каждому лично: правило «немой не говорит,
     * глухой не слышит» невидимо, и без напоминания оно читается как поломанный микрофон.
     *
     * <p>Здесь же — единственная проверка установленного голосового чата. Раньше её ставить
     * некуда: на входе в игру она отбивала бы ботов, которыми проверяется серверная сторона,
     * а нужна она ровно в тот момент, когда команда собирается говорить.
     */
    private static void briefVoice(MinecraftServer server, Party party) {
        boolean available = VoiceRules.available();
        for (UUID memberId : party.members) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            Role role = party.roleOf(memberId);
            if (member == null || role == null) {
                continue;
            }
            if (!available) {
                member.sendMessage(Msg.text("twm.voice.absent").copy().formatted(Formatting.GRAY), false);
                continue;
            }
            member.sendMessage(Msg.text(VoiceRules.briefingKey(role)).copy().formatted(Formatting.GRAY), false);
            if (!VoiceRules.connected(memberId)) {
                notifyMembers(server, party, "warning", "twm.notify.voice",
                        Msg.of("twm.voice.missing", member.getName().getString()), null);
            }
        }
    }

    public static String stop(ServerPlayerEntity player) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null || !party.isHost(player.getUuid())) {
            return "twm.err.host_only_stop";
        }
        if (!party.active) {
            return "twm.err.game_not_running";
        }
        MinecraftServer server = player.getServer();
        // Ротация до остановки: экран итогов показывает каждому, кем он был и кем станет,
        // а собирается он внутри stopGame. Забег, оборванный уходом участника, ролей не
        // сдвигает — там состав уже распался, и «следующий раунд» начнётся не тем же втроём.
        party.rotatedLastRun = rotateRoles(party);
        PartyManager.stopGame(server, party, Msg.text("twm.chat.game_stopped"));
        if (party.rotatedLastRun) {
            chatMembers(server, party, Msg.text("twm.chat.rotated"));
        }
        notifyMembers(server, party, "info", "twm.notify.game_stopped",
                "twm.notify.back_to_lobby", null);
        broadcast(server, party, null);
        return null;
    }

    /**
     * Сдвиг ролей по кругу: голова берёт руки, руки — ноги, ноги — голову.
     *
     * <p>Направление выбрано по нагрузке, а не по алфавиту: голова весь забег только смотрит
     * и жмёт круг мыслей, поэтому следующей ей достаётся самая деятельная роль. Три забега
     * подряд дают каждому все три взгляда на одну и ту же игру — иначе занявший «свою» роль
     * остаётся на ней навсегда и видит половину режима.
     */
    private static boolean rotateRoles(Party party) {
        if (!TwmConfig.get().rotateRoles() || !party.rotateRoles
                || party.roles.size() != Party.MEMBER_COUNT) {
            return false;
        }
        UUID head = party.roles.get(Role.HEAD);
        UUID hands = party.roles.get(Role.HANDS);
        UUID legs = party.roles.get(Role.LEGS);
        party.roles.put(Role.HANDS, head);
        party.roles.put(Role.LEGS, hands);
        party.roles.put(Role.HEAD, legs);
        return true;
    }

    public static String leave(ServerPlayerEntity player) {
        Party party = BY_MEMBER.get(player.getUuid());
        if (party == null) {
            return QUEUE.remove(player.getUuid()) ? null : "twm.err.not_in_lobby";
        }
        detach(player.getServer(), party, player);
        broadcast(player.getServer(), party, player);
        return null;
    }

    // --- Жизненный цикл ----------------------------------------------------

    /**
     * Обрыв связи ничем не отличается от выхода по кнопке: та же остановка игры, тот же
     * перенос хоста. Разница только в том, что уходящему уже нечего показывать.
     */
    public static void onDisconnect(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        QUEUE.remove(player.getUuid());
        PASSWORD_TRIES.remove(player.getUuid());
        Limits.forget(player.getUuid());
        ModCheck.onDisconnect(player);
        PartyManager.forgetBelongings(player.getUuid());
        INVITES.removeIf(invite -> invite.target().equals(player.getUuid()));
        Party party = BY_MEMBER.get(player.getUuid());
        if (party != null) {
            detach(server, party, player);
        }
        broadcast(server, party, player);
    }

    /**
     * Вошедший должен сразу видеть список лобби, а хосты — обновлённый список кандидатов.
     *
     * <p>Здесь же — единственная защита от упавшего сервера. Забег переводит голову и ноги в
     * приключение, делает их невидимыми и неуязвимыми, а лобби живёт только в памяти: падение
     * или {@code kill -9} посреди забега сохранят это состояние в {@code playerdata} и не
     * оставят никого, кто мог бы его снять. Слепок на диске переживает и то, и другое.
     */
    public static void onJoin(ServerPlayerEntity player) {
        if (!BY_MEMBER.containsKey(player.getUuid())) {
            PlayerRestore restore = PlayerRestore.of(player.getServer());
            if (restore.restore(player)) {
                Twm.LOGGER.info("Состояние роли снято с {} по слепку: забег не был завершён",
                        player.getName().getString());
                player.sendMessage(Msg.text("twm.chat.restored").copy()
                        .formatted(Formatting.YELLOW), false);
            }
        }
        VoidLobby.place(player);
        ModCheck.onJoin(player);
        // Снимок вошедшему отправляется отдельно, а не рассылкой. На событии входа игрока
        // ещё нет в списке игроков сервера, а рассылка идёт именно по нему — вошедший
        // пропускался, и клиент оставался вовсе без снимка: без него мод не знал ни про
        // разрешение на автооткрытие, ни про запертое меню, ни про список лобби. Первый
        // снимок приходил только в ответ на действие игрока, то есть после того, как он
        // открывал меню руками — ровно поэтому «меню само не открывается» и выглядело так,
        // будто автооткрытия нет вовсе.
        push(player, "");
        broadcast(player.getServer(), null, null);
    }

    /**
     * Остановка сервера. Забеги закрываются штатно, чтобы приключение и невидимость не уехали
     * в сохранение: восстановить их потом можно только по слепку, а слепок — крайняя мера,
     * а не обычный путь.
     */
    public static void onServerStopping(MinecraftServer server) {
        for (Party party : playingParties()) {
            PartyManager.stopGame(server, party, Msg.text("twm.chat.stopped_shutdown"));
        }
    }

    /**
     * Полный сброс реестров. Нужен там, где сервер живёт в том же процессе, что и клиент:
     * вышел из одиночного мира, зашёл в другой — статика пережила бы оба и раздала роли
     * игрокам чужого мира.
     */
    public static void clearAll() {
        BY_MEMBER.clear();
        BY_CODE.clear();
        QUEUE.clear();
        INVITES.clear();
        PASSWORD_TRIES.clear();
        Limits.clear();
    }

    public static void tick(MinecraftServer server) {
        if (--expiryCountdown > 0) {
            return;
        }
        expiryCountdown = EXPIRY_PERIOD_TICKS;
        prewarmArenas(server);
        long now = System.currentTimeMillis();
        List<Invite> expired = new ArrayList<>();
        INVITES.removeIf(invite -> {
            boolean dead = invite.expiresAt() <= now || !BY_CODE.containsKey(invite.code());
            if (dead) {
                expired.add(invite);
            }
            return dead;
        });
        for (Invite invite : expired) {
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(invite.target());
            if (target != null) {
                push(target, "");
            }
        }
    }

    /**
     * Заранее поднятые арены.
     *
     * <p>Создание трёх миров стоит около секунды серверного потока, и раньше эта секунда
     * стояла ровно между нажатием «Начать» и первым кадром забега — то есть в самом заметном
     * месте. Теперь она уходит туда, где команда и так стоит: лобби укомплектовано, роли
     * выбраны, все ждут хоста.
     *
     * <p>За это платят живыми мирами, поэтому у прогрева два ограничителя: потолок числа
     * ожидающих арен и срок ожидания. Лобби, которое перестало быть полным и не собралось
     * обратно, свою арену теряет — иначе брошенные комнаты держали бы миры до перезапуска.
     */
    private static void prewarmArenas(MinecraftServer server) {
        TwmConfig config = TwmConfig.get();
        if (!config.arenaEnabled() || !config.arenaPrewarm()) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleLimit = config.arenaPrewarmIdleSeconds() * 1000L;
        int waiting = 0;
        for (Party party : BY_CODE.values()) {
            if (!party.active && ArenaManager.of(party.code) != null) {
                waiting++;
            }
        }
        for (Party party : List.copyOf(BY_CODE.values())) {
            if (party.active) {
                continue;
            }
            boolean ready = party.readyToStart() && allOnline(server, party);
            boolean prepared = ArenaManager.of(party.code) != null;
            if (ready) {
                party.notReadySince = 0L;
                if (!prepared && waiting < config.arenaPrewarmLimit()
                        && ArenaManager.open(server, party.code) != null) {
                    waiting++;
                }
                continue;
            }
            if (!prepared) {
                continue;
            }
            if (party.notReadySince == 0L) {
                party.notReadySince = now;
            } else if (now - party.notReadySince > idleLimit) {
                Twm.LOGGER.info("Арена {} снята: лобби не собралось за {} с",
                        party.code, config.arenaPrewarmIdleSeconds());
                ArenaManager.close(party.code);
                party.notReadySince = 0L;
                waiting--;
            }
        }
    }

    private static boolean allOnline(MinecraftServer server, Party party) {
        for (UUID memberId : party.members) {
            if (server.getPlayerManager().getPlayer(memberId) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Имя голосовой группы. Группа скрытая, в списке групп её не видно, поэтому имя нужно
     * только для отладки — и переводить его незачем.
     */
    private static String groupName(Party party) {
        return "TWM " + party.code;
    }

    /**
     * Выход участника. Игра останавливается: вдвоём общее тело не ведут. Роли оставшихся
     * сохраняются, чтобы после прихода третьего не пересобирать состав заново.
     */
    private static void detach(MinecraftServer server, Party party, ServerPlayerEntity member) {
        String name = member.getName().getString();
        if (party.active) {
            party.rotatedLastRun = false;
            // Порядок важен: состояние сущности возвращается всем, включая уходящего, — иначе
            // невидимость и приключение уедут в его сохранение и встретят его при следующем входе.
            PartyManager.stopGame(server, party, Msg.text(Msg.of("twm.chat.game_stopped_left", name)));
            notifyMembers(server, party, "warning", "twm.notify.game_stopped",
                    Msg.of("twm.notify.member_left", name), null);
        }
        UUID memberId = member.getUuid();
        boolean wasHost = party.isHost(memberId);
        party.members.remove(memberId);
        party.roles.values().removeIf(memberId::equals);
        BY_MEMBER.remove(memberId);
        VoiceRules.leaveGroup(memberId);

        if (party.members.isEmpty()) {
            BY_CODE.remove(party.code);
            INVITES.removeIf(invite -> invite.code().equals(party.code));
            VoiceRules.dropGroup(party.code);
            // Лобби распалось — заранее поднятая арена вместе с ним: три мира не должны
            // пережить команду, ради которой их подняли.
            ArenaManager.close(party.code);
            return;
        }
        if (wasHost) {
            party.host = party.members.iterator().next();
        }
        notifyMembers(server, party, "info", "twm.notify.lobby",
                Msg.of("twm.notify.member_left", name), null);
        chatMembers(server, party, Msg.text(Msg.of("twm.chat.member_left", name,
                String.valueOf(party.members.size()), String.valueOf(Party.MEMBER_COUNT))));
    }

    private static Party openLobby(ServerPlayerEntity host, String name, boolean locked,
                                   String password, Difficulty difficulty) {
        QUEUE.remove(host.getUuid());
        Party party = new Party(nextCode(), host.getUuid());
        party.name = name;
        party.locked = locked;
        party.password = password;
        party.difficulty = difficulty;
        BY_CODE.put(party.code, party);
        BY_MEMBER.put(host.getUuid(), party);
        INVITES.removeIf(invite -> invite.target().equals(host.getUuid()));
        VoiceRules.joinGroup(host.getUuid(), party.code, groupName(party));
        return party;
    }

    /** Общий вход в лобби: и по коду, и по приглашению, и подбором. */
    private static void enter(ServerPlayerEntity player, Party party) {
        MinecraftServer server = player.getServer();
        QUEUE.remove(player.getUuid());
        // Игрок в лобби больше не свободен, значит и остальные приглашения к нему не относятся.
        INVITES.removeIf(invite -> invite.target().equals(player.getUuid()));
        party.members.add(player.getUuid());
        BY_MEMBER.put(player.getUuid(), party);
        VoiceRules.joinGroup(player.getUuid(), party.code, groupName(party));

        String message = Msg.of("twm.notify.member_joined", player.getName().getString(),
                String.valueOf(party.members.size()), String.valueOf(Party.MEMBER_COUNT));
        notifyMembers(server, party, "info", "twm.notify.lobby", message, player.getUuid());
        chatMembers(server, party, Msg.text(message));
        broadcast(server, party, player);
    }

    /**
     * Очередь набралась — лобби создаётся само. Первый в очереди становится хостом: он ждал
     * дольше всех, и кому-то надо владеть кнопкой старта.
     */
    private static boolean formLobbyFromQueue(MinecraftServer server) {
        if (QUEUE.size() < Party.MEMBER_COUNT) {
            return false;
        }
        List<ServerPlayerEntity> group = new ArrayList<>();
        Iterator<UUID> waiting = QUEUE.iterator();
        while (waiting.hasNext() && group.size() < Party.MEMBER_COUNT) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(waiting.next());
            if (player == null) {
                waiting.remove();
                continue;
            }
            group.add(player);
        }
        if (group.size() < Party.MEMBER_COUNT) {
            return false;
        }
        for (ServerPlayerEntity player : group) {
            QUEUE.remove(player.getUuid());
        }
        Party party = openLobby(group.get(0), "twm.lobby.quick_name", false, "", Difficulty.EASY);
        for (int index = 1; index < group.size(); index++) {
            UUID memberId = group.get(index).getUuid();
            party.members.add(memberId);
            BY_MEMBER.put(memberId, party);
            INVITES.removeIf(invite -> invite.target().equals(memberId));
            VoiceRules.joinGroup(memberId, party.code, groupName(party));
        }
        notifyMembers(server, party, "success", "twm.notify.game_found",
                "twm.notify.pick_roles", null);
        chatMembers(server, party, Msg.text(Msg.of("twm.chat.quick_done", party.code))
                .copy().formatted(Formatting.GREEN));
        broadcast(server, party, null);
        return true;
    }

    // --- Рассылка ----------------------------------------------------------

    public static void push(ServerPlayerEntity player, String notice) {
        ServerPlayNetworking.send(player, new TwmPayloads.LobbyState(snapshot(player, notice)));
    }

    public static void notify(ServerPlayerEntity player, String kind, String title, String text) {
        ServerPlayNetworking.send(player, new TwmPayloads.Notify(kind, title, text));
    }

    /**
     * Снимок уходит всем, кого затронуло изменение: участникам лобби и всем, кто не в игре, —
     * у них поменялся список лобби. Инициатору снимок отправляет его собственный вызов, поэтому
     * он исключается: два подряд одинаковых снимка перерисовывают страницу дважды.
     */
    private static void broadcast(MinecraftServer server, Party changed, ServerPlayerEntity actor) {
        Set<UUID> sent = new HashSet<>();
        if (actor != null) {
            sent.add(actor.getUuid());
        }
        if (changed != null) {
            for (UUID memberId : changed.members) {
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
                if (member != null && sent.add(memberId)) {
                    push(member, "");
                }
            }
        }
        for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
            Party party = BY_MEMBER.get(online.getUuid());
            if ((party == null || !party.active) && sent.add(online.getUuid())) {
                push(online, "");
            }
        }
    }

    private static void notifyMembers(MinecraftServer server, Party party, String kind,
                                      String title, String text, UUID except) {
        for (UUID memberId : party.members) {
            if (memberId.equals(except)) {
                continue;
            }
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member != null) {
                notify(member, kind, title, text);
            }
        }
    }

    /** Чат остаётся рабочим каналом обратной связи: по нему живут тесты на ванильных ботах. */
    private static void chatMembers(MinecraftServer server, Party party, Text message) {
        for (UUID memberId : party.members) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member != null) {
                member.sendMessage(message, false);
            }
        }
    }

    // --- Снимок ------------------------------------------------------------

    private static String snapshot(ServerPlayerEntity player, String notice) {
        MinecraftServer server = player.getServer();
        Party party = BY_MEMBER.get(player.getUuid());
        boolean queued = QUEUE.contains(player.getUuid());

        JsonObject root = new JsonObject();
        JsonObject self = new JsonObject();
        self.addProperty("name", player.getName().getString());
        self.addProperty("uuid", player.getUuidAsString());
        root.add("self", self);
        // Режим сервера, а не выбор игрока: запертое меню и автооткрытие включает владелец
        // сервера в twm-server.json. Клиент узнаёт о них только отсюда — иначе у него был бы
        // второй источник правды, и подменённый клиент решал бы, запирать ли себя.
        JsonObject mode = new JsonObject();
        // Запирается меню только на выделенном сервере. В одиночке и по локальной сети хост —
        // он же владелец мира: запертое меню отняло бы у него собственный мир, а согласиться
        // на это он не мог, конфиг сервера пишется не там.
        mode.addProperty("lockMenu", TwmConfig.get().lockMenu() && server.isDedicated());
        mode.addProperty("autoOpen", TwmConfig.get().autoOpenMenu());
        mode.addProperty("arenas", TwmConfig.get().arenaEnabled());
        root.add("mode", mode);
        // Объявление владельца сервера. На чужом сервере секция пуста, и клиент, получив
        // пустоту, ничего не рисует: сборка у всех одна, а зовёт каждый сервер к себе сам.
        if (!TwmConfig.get().announceNavLabel().isEmpty()
                && !TwmConfig.get().announceNavServer().isEmpty()) {
            JsonObject announce = new JsonObject();
            announce.addProperty("navLabel", TwmConfig.get().announceNavLabel());
            root.add("announce", announce);
        }
        root.addProperty("phase", party == null ? (queued ? "queue" : "none")
                : (party.active ? "playing" : "lobby"));
        root.addProperty("notice", notice == null ? "" : notice);
        if (queued) {
            JsonObject queue = new JsonObject();
            queue.addProperty("size", QUEUE.size());
            queue.addProperty("needed", Party.MEMBER_COUNT);
            root.add("queue", queue);
        }
        root.add("invites", invitesJson(server, player));
        if (party != null) {
            root.add("lobby", lobbyJson(server, player, party));
        }
        root.add("lobbies", lobbiesJson(server, party));
        return root.toString();
    }

    private static JsonArray invitesJson(MinecraftServer server, ServerPlayerEntity player) {
        JsonArray invites = new JsonArray();
        for (Invite invite : INVITES) {
            Party party = BY_CODE.get(invite.code());
            if (!invite.target().equals(player.getUuid()) || party == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("code", invite.code());
            entry.addProperty("from", nameOf(server, invite.from()));
            entry.addProperty("lobby", title(server, party));
            invites.add(entry);
        }
        return invites;
    }

    private static JsonObject lobbyJson(MinecraftServer server, ServerPlayerEntity player, Party party) {
        boolean host = party.isHost(player.getUuid());
        JsonObject lobby = new JsonObject();
        lobby.addProperty("code", party.code);
        lobby.addProperty("name", title(server, party));
        lobby.addProperty("private", party.locked);
        lobby.addProperty("difficulty", party.difficulty.id());
        lobby.addProperty("echoCooldown", party.echoCooldownTicks / 20);
        lobby.addProperty("rotate", party.rotateRoles);
        lobby.addProperty("arenaReady", ArenaManager.of(party.code) != null);
        lobby.addProperty("state", party.active ? "playing" : "waiting");
        lobby.addProperty("host", host);
        lobby.addProperty("canStart", host && !party.active && party.readyToStart());
        lobby.addProperty("startHint", startHint(party, host));

        JsonArray members = new JsonArray();
        for (UUID memberId : party.members) {
            Role role = party.roleOf(memberId);
            JsonObject entry = new JsonObject();
            entry.addProperty("name", nameOf(server, memberId));
            entry.addProperty("uuid", memberId.toString());
            entry.addProperty("role", role == null ? "" : role.id());
            entry.addProperty("host", party.isHost(memberId));
            entry.addProperty("self", memberId.equals(player.getUuid()));
            members.add(entry);
        }
        lobby.add("members", members);

        JsonArray roles = new JsonArray();
        for (Role role : Role.values()) {
            UUID occupant = party.roles.get(role);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", role.id());
            entry.addProperty("name", role.translationKey());
            entry.addProperty("occupant", occupant == null ? "" : nameOf(server, occupant));
            entry.addProperty("occupantUuid", occupant == null ? "" : occupant.toString());
            entry.addProperty("mine", occupant != null && occupant.equals(player.getUuid()));
            roles.add(entry);
        }
        lobby.add("roles", roles);

        // Список режется по числу, а не по «сколько влезет»: снимок уходит одной строкой в
        // пакет с потолком длины, а на людном сервере свободных игроков сотни. Переполнение
        // строки — это исключение при отправке, то есть разрыв связи у случайного игрока.
        JsonArray candidates = new JsonArray();
        if (host && !party.active && party.hasFreeSlot()) {
            int limit = TwmConfig.get().listedCandidates();
            for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
                if (candidates.size() >= limit) {
                    break;
                }
                if (!BY_MEMBER.containsKey(online.getUuid()) && !isInvited(online.getUuid(), party.code)) {
                    candidates.add(online.getName().getString());
                }
            }
        }
        lobby.add("candidates", candidates);
        return lobby;
    }

    /** В игре список лобби не нужен и только мешает: экран занят игрой, а не выбором комнаты. */
    private static JsonArray lobbiesJson(MinecraftServer server, Party own) {
        JsonArray lobbies = new JsonArray();
        if (own != null && own.active) {
            return lobbies;
        }
        int limit = TwmConfig.get().listedLobbies();
        for (Party party : BY_CODE.values()) {
            if (lobbies.size() >= limit) {
                break;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("code", party.code);
            entry.addProperty("name", title(server, party));
            entry.addProperty("host", nameOf(server, party.host));
            entry.addProperty("private", party.locked);
            entry.addProperty("players", party.members.size());
            entry.addProperty("max", Party.MEMBER_COUNT);
            entry.addProperty("difficulty", party.difficulty.id());
            entry.addProperty("state", party.active ? "playing" : "waiting");
            entry.addProperty("joinable", own == null && !party.active && party.hasFreeSlot());
            lobbies.add(entry);
        }
        return lobbies;
    }

    private static String startHint(Party party, boolean host) {
        if (party.active) {
            return "twm.hint.playing";
        }
        if (party.members.size() < Party.MEMBER_COUNT) {
            return "twm.hint.need_three";
        }
        if (party.roles.size() < Party.MEMBER_COUNT) {
            return "twm.hint.need_roles";
        }
        return host ? "twm.hint.ready" : "twm.hint.host_starts";
    }

    /** Строки для {@code /twm list}: тот же список, что видит меню, но читаемый ботом. */
    public static List<String> describeLobbies(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        for (Party party : BY_CODE.values()) {
            lines.add(party.code + "  " + title(server, party)
                    + "  " + party.members.size() + "/" + Party.MEMBER_COUNT
                    + "  " + party.difficulty.id()
                    + "  " + (party.active ? "playing" : "waiting")
                    + (party.locked ? "  private" : ""));
        }
        return lines;
    }

    // --- Мелочи ------------------------------------------------------------

    private static String title(MinecraftServer server, Party party) {
        return party.name.isBlank()
                ? Msg.of("twm.lobby.default_name", nameOf(server, party.host))
                : party.name;
    }

    private static String nameOf(MinecraftServer server, UUID playerId) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        return player == null ? "?" : player.getName().getString();
    }

    /**
     * Перебор пароля. Приватное лобби видно в списке вместе с кодом — это осознанное решение
     * («закрытая комната приглашает спросить пароль»), и оно оставляет пароль единственной
     * преградой. Без счётчика попыток её нет: клиент шлёт варианты со скоростью сети.
     *
     * <p>Окно скользящее и считается на игрока: перебирать можно и по разным комнатам сразу.
     */
    private static String notePasswordFailure(UUID playerId) {
        PasswordAttempts attempts = PASSWORD_TRIES.computeIfAbsent(playerId,
                id -> new PasswordAttempts());
        long now = System.currentTimeMillis();
        long window = TwmConfig.get().passwordWindowSeconds() * 1000L;
        if (now - attempts.windowStart > window) {
            attempts.windowStart = now;
            attempts.failures = 0;
        }
        attempts.failures++;
        return attempts.failures > TwmConfig.get().passwordAttempts()
                ? "twm.err.password_throttled"
                : "twm.err.bad_password";
    }

    private static boolean isInvited(UUID target, String code) {
        for (Invite invite : INVITES) {
            if (invite.target().equals(target) && invite.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static Invite findInvite(UUID target, String code) {
        String wanted = normalizeCode(code);
        Invite found = null;
        for (Invite invite : INVITES) {
            if (!invite.target().equals(target)) {
                continue;
            }
            if (wanted.isEmpty() || invite.code().equals(wanted)) {
                found = invite;
            }
        }
        return found;
    }

    private static boolean takeInvite(UUID target, String code) {
        return INVITES.removeIf(invite -> invite.target().equals(target) && invite.code().equals(code));
    }

    private static String nextCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        do {
            code.setLength(0);
            for (int index = 0; index < CODE_LENGTH; index++) {
                code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
        } while (BY_CODE.containsKey(code.toString()));
        return code.toString();
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /** Мусор вместо JSON — не ошибка протокола, а обычный отказ: разбираем как пустой объект. */
    private static JsonObject parse(String argument) {
        if (argument == null || argument.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(argument);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException malformed) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject json, String key, int limit) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString().trim();
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    private static boolean flag(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    /** Нечисло приходит только от подменённой страницы: отвечаем нулём, его зажмёт вызывающий. */
    private static int seconds(String argument) {
        try {
            return Integer.parseInt(argument.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
