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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import org.lightovich.twm.Twm;

/**
 * Серверные настройки режима — {@code config/twm-server.json}.
 *
 * <p>Всё, что здесь лежит, — решения владельца сервера, а не игрока: они меняют правила и
 * нагрузку, поэтому в клиентский {@code twm.json} их выносить нельзя. Клиент узнаёт из этого
 * файла ровно два факта, и оба приходят ему готовыми внутри снимка лобби: заперто ли меню и
 * открывается ли оно само. Остального клиент не видит вообще.
 *
 * <p>Файл создаётся, если его нет, и <b>никогда не переписывается</b>, если он есть, — то же
 * правило, что у чужих конфигов. Неизвестные и битые значения откатываются к умолчанию молча:
 * сервер обязан подняться с испорченным конфигом, а не упасть на нём.
 */
public final class TwmConfig {

    /** Одиночная арена: 2000×2000 блоков вокруг центра, то есть ±1000 по каждой оси. */
    private static final int DEFAULT_ARENA_SIZE = 2000;
    private static final int MIN_ARENA_SIZE = 256;
    private static final int MAX_ARENA_SIZE = 30_000_000;

    private static volatile TwmConfig current = new TwmConfig(new JsonObject());

    private final boolean lockMenu;
    private final boolean autoOpenMenu;
    private final boolean voidLobby;
    private final boolean requireMod;
    private final String modMissingMessage;
    private final String modMissingUrl;
    private final String modMissingServer;
    private final int listedLobbies;
    private final int listedCandidates;

    private final boolean arenaEnabled;
    private final int arenaSize;
    private final int arenaNetherSize;
    private final int arenaEndSize;
    private final boolean arenaKeepAfterRun;
    private final boolean arenaFreshStart;
    private final boolean arenaPrewarm;
    private final int arenaPrewarmLimit;
    private final int arenaPrewarmIdleSeconds;
    private final boolean arenaOwnDragon;

    private final boolean voiceLobbyGroups;
    private final boolean rotateRoles;
    private final boolean runSummary;

    private final String announceSummaryText;
    private final String announceButtonLabel;
    private final String announceButtonServer;
    private final String announceNavLabel;
    private final String announceNavServer;

    private final boolean proxyEnabled;
    private final boolean proxyRequireToken;
    private final String proxyToken;
    private final boolean proxyDebug;
    private final String proxyNoDataMessage;
    private final String proxyBadTokenMessage;

    private final double lobbyActionsPerSecond;
    private final double thoughtsPerSecond;
    private final double dropsPerSecond;
    private final int passwordAttempts;
    private final int passwordWindowSeconds;

    private TwmConfig(JsonObject root) {
        JsonObject lobby = child(root, "lobby");
        this.lockMenu = bool(lobby, "lockMenu", true);
        this.autoOpenMenu = bool(lobby, "autoOpenMenu", true);
        this.voidLobby = bool(lobby, "voidWorld", false);
        this.requireMod = bool(lobby, "requireMod", false);
        this.modMissingMessage = text(lobby, "modMissingMessage",
                "This server needs the Three Wise Monkeys mod.");
        this.modMissingUrl = text(lobby, "modMissingUrl", "");
        this.modMissingServer = text(lobby, "modMissingServer", "");
        // Снимок уезжает одной строкой в пакет, а строка пакета ограничена сверху. Списки
        // режутся здесь, а не «когда-нибудь переполнится»: переполнение — это исключение
        // при отправке, то есть разрыв связи у случайного игрока.
        this.listedLobbies = clamp(integer(lobby, "listedLobbies", 60), 5, 200);
        this.listedCandidates = clamp(integer(lobby, "listedCandidates", 40), 5, 200);

        JsonObject arena = child(root, "arena");
        this.arenaEnabled = bool(arena, "enabled", true);
        this.arenaSize = clamp(integer(arena, "size", DEFAULT_ARENA_SIZE), MIN_ARENA_SIZE, MAX_ARENA_SIZE);
        this.arenaNetherSize = clamp(integer(arena, "netherSize", DEFAULT_ARENA_SIZE),
                MIN_ARENA_SIZE, MAX_ARENA_SIZE);
        this.arenaEndSize = clamp(integer(arena, "endSize", DEFAULT_ARENA_SIZE),
                MIN_ARENA_SIZE, MAX_ARENA_SIZE);
        this.arenaKeepAfterRun = bool(arena, "keepAfterRun", false);
        this.arenaFreshStart = bool(arena, "freshStart", true);
        this.arenaPrewarm = bool(arena, "prewarm", true);
        this.arenaPrewarmLimit = clamp(integer(arena, "prewarmLimit", 4), 0, 64);
        this.arenaPrewarmIdleSeconds = clamp(integer(arena, "prewarmIdleSeconds", 120), 10, 3600);
        this.arenaOwnDragon = bool(arena, "ownDragon", true);

        JsonObject run = child(root, "run");
        this.voiceLobbyGroups = bool(run, "voiceLobbyGroups", true);
        this.rotateRoles = bool(run, "rotateRoles", true);
        this.runSummary = bool(run, "summary", true);

        JsonObject announce = child(root, "announce");
        this.announceSummaryText = text(announce, "summaryText", "");
        this.announceButtonLabel = text(announce, "buttonLabel", "");
        this.announceButtonServer = text(announce, "buttonServer", "");
        this.announceNavLabel = text(announce, "navLabel", "");
        this.announceNavServer = text(announce, "navServer", "");

        JsonObject proxy = child(root, "proxy");
        this.proxyEnabled = bool(proxy, "enabled", false);
        this.proxyRequireToken = bool(proxy, "requireToken", true);
        this.proxyToken = text(proxy, "token", "");
        this.proxyDebug = bool(proxy, "debug", false);
        this.proxyNoDataMessage = text(proxy, "noDataMessage",
                "This server accepts connections through the network proxy only.");
        this.proxyBadTokenMessage = text(proxy, "badTokenMessage",
                "Proxy authentication failed.");

        JsonObject limits = child(root, "limits");
        this.lobbyActionsPerSecond = clamp(number(limits, "lobbyActionsPerSecond", 8.0), 1.0, 200.0);
        this.thoughtsPerSecond = clamp(number(limits, "thoughtsPerSecond", 4.0), 1.0, 200.0);
        this.dropsPerSecond = clamp(number(limits, "dropsPerSecond", 8.0), 1.0, 200.0);
        this.passwordAttempts = clamp(integer(limits, "passwordAttempts", 5), 1, 1000);
        this.passwordWindowSeconds = clamp(integer(limits, "passwordWindowSeconds", 60), 5, 3600);
    }

    public static TwmConfig get() {
        return current;
    }

    /** Читается один раз на запуск сервера: правка на живом сервере требует перезапуска. */
    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("twm-server.json");
        JsonObject root = new JsonObject();
        if (Files.isRegularFile(path)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    root = parsed.getAsJsonObject();
                } else {
                    Twm.LOGGER.warn("twm-server.json не объект — беру умолчания");
                }
            } catch (IOException | RuntimeException e) {
                Twm.LOGGER.warn("twm-server.json не прочитан, беру умолчания: {}", e.toString());
            }
        }
        current = new TwmConfig(root);
        if (current.proxyEnabled() && current.proxyRequireToken() && current.proxyToken().isEmpty()) {
            // Отказ молчком был бы хуже: владелец видел бы «никто не заходит» без причины.
            Twm.LOGGER.error("Форвардинг прокси включён, но секрет не задан — вход закрыт всем. "
                    + "Впишите proxy.token из forwarding_secret прокси или снимите proxy.requireToken");
        }
        if (!Files.isRegularFile(path)) {
            write(path);
        }
    }

    /** Умолчания на диск — чтобы владелец сервера видел, что вообще можно настроить. */
    private static void write(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, """
                    {
                      "lobby": {
                        "lockMenu": true,
                        "autoOpenMenu": true,
                        "voidWorld": false,
                        "requireMod": false,
                        "modMissingUrl": "",
                        "modMissingServer": "",
                        "listedLobbies": 60,
                        "listedCandidates": 40
                      },
                      "arena": {
                        "enabled": true,
                        "size": 2000,
                        "netherSize": 2000,
                        "endSize": 2000,
                        "keepAfterRun": false,
                        "freshStart": true,
                        "prewarm": true,
                        "prewarmLimit": 4,
                        "prewarmIdleSeconds": 120,
                        "ownDragon": true
                      },
                      "run": {
                        "voiceLobbyGroups": true,
                        "rotateRoles": true,
                        "summary": true
                      },
                      "announce": {
                        "summaryText": "",
                        "buttonLabel": "",
                        "buttonServer": "",
                        "navLabel": "",
                        "navServer": ""
                      },
                      "proxy": {
                        "enabled": false,
                        "requireToken": true,
                        "token": "",
                        "debug": false
                      },
                      "limits": {
                        "lobbyActionsPerSecond": 8,
                        "thoughtsPerSecond": 4,
                        "dropsPerSecond": 8,
                        "passwordAttempts": 5,
                        "passwordWindowSeconds": 60
                      }
                    }
                    """, StandardCharsets.UTF_8);
            Twm.LOGGER.info("Создан {}", path);
        } catch (IOException e) {
            Twm.LOGGER.warn("twm-server.json не записан: {}", e.toString());
        }
    }

    /**
     * Меню нельзя закрыть, пока команда не в игре. Верно только вместе с аренами: на обычном
     * сервере с другими режимами запертое меню отняло бы у игрока весь остальной сервер.
     */
    public boolean lockMenu() {
        return lockMenu && arenaEnabled;
    }

    public boolean autoOpenMenu() {
        return autoOpenMenu;
    }

    /**
     * Лобби живёт в отдельном пустом мире, а не в главном мире сервера.
     *
     * <p>Верно только вместе с аренами: без них забег идёт там же, где стоит команда, и
     * уносить её в мир без блоков означало бы отобрать сам мир. Главный мир при этом никуда
     * не девается — с него арены берут генератор.
     */
    public boolean voidLobby() {
        return voidLobby && arenaEnabled;
    }

    /**
     * Клиента без мода отключать, а не оставлять в мире. Смотреть ему тут не на что: роли,
     * восприятие и весь интерфейс живут на его стороне.
     */
    public boolean requireMod() {
        return requireMod;
    }

    public String modMissingMessage() {
        return modMissingMessage;
    }

    /** Кликабельная ссылка на мод под сообщением. Пустая — строки со ссылкой не будет. */
    public String modMissingUrl() {
        return modMissingUrl;
    }

    /**
     * Сервер сети, куда возвращают клиента без мода, вместо отключения.
     *
     * <p>Нужен потому, что кик за прокси бесполезен: BungeeCord на кик с бэкенда уводит
     * игрока на резервный сервер и текст отключения не показывает. Уж лучше увести самим —
     * туда, куда решил владелец, и с объяснением в чате.
     */
    public String modMissingServer() {
        return modMissingServer;
    }

    public int listedLobbies() {
        return listedLobbies;
    }

    public int listedCandidates() {
        return listedCandidates;
    }

    public boolean arenaEnabled() {
        return arenaEnabled;
    }

    public int arenaSize() {
        return arenaSize;
    }

    public int arenaNetherSize() {
        return arenaNetherSize;
    }

    public int arenaEndSize() {
        return arenaEndSize;
    }

    public boolean arenaKeepAfterRun() {
        return arenaKeepAfterRun;
    }

    /**
     * Команда входит в арену налегке: пустой инвентарь, нулевой опыт, полное здоровье.
     * Забег — это путь от голых рук до портала, и прийти в него со снаряжением из лобби
     * значит пропустить сам забег. Свои вещи возвращает слепок на выходе.
     */
    public boolean arenaFreshStart() {
        return arenaFreshStart;
    }

    /**
     * Арена поднимается заранее — в тот момент, когда лобби укомплектовано и ждёт кнопки
     * хоста. Секунда на создание трёх миров никуда не девается, но перестаёт быть паузой
     * между «Начать» и первым кадром: она уходит туда, где команда и так стоит и говорит.
     */
    public boolean arenaPrewarm() {
        return arenaPrewarm;
    }

    /** Потолок заранее поднятых арен: каждая — три живых мира, и ждать они могут долго. */
    public int arenaPrewarmLimit() {
        return arenaPrewarmLimit;
    }

    /** Столько секунд неукомплектованное лобби держит заранее поднятую арену, потом она сносится. */
    public int arenaPrewarmIdleSeconds() {
        return arenaPrewarmIdleSeconds;
    }

    /**
     * Свой бой с драконом у каждой арены. Ваниль берёт состояние боя из общего {@code level.dat},
     * поэтому убитый одной командой дракон не появился бы у следующей.
     */
    public boolean arenaOwnDragon() {
        return arenaOwnDragon;
    }

    /** Своя голосовая группа на лобби: три команды на общем спавне не слышат друг друга. */
    public boolean voiceLobbyGroups() {
        return voiceLobbyGroups;
    }

    /** Роли сдвигаются по кругу после забега, законченного хостом. */
    public boolean rotateRoles() {
        return rotateRoles;
    }

    /** Экран итогов забега. */
    public boolean runSummary() {
        return runSummary;
    }

    /**
     * Строка от владельца сервера на экране итогов забега. Пустая — экрана не касается.
     *
     * <p>Всё, что лежит в этой секции, приходит от сервера и только от него: сборка у всех
     * одна, и на чужом сервере, где секция пуста, ни строки, ни кнопок не появляется.
     *
     * <p>Место выбрано по устройству интерфейса: пока команда в лобби, меню — это браузер
     * поверх экрана, и чат ей не виден вовсе. Итоги — единственный момент, когда все трое
     * смотрят в одну точку и решают, что делать дальше.
     */
    public String announceSummaryText() {
        return announceSummaryText;
    }

    /** Подпись кнопки на экране итогов. Без неё и без сервера кнопки нет. */
    public String announceButtonLabel() {
        return announceButtonLabel;
    }

    /** Сервер в конфиге прокси, куда уводит кнопка итогов и {@code /twm connect}. */
    public String announceButtonServer() {
        return announceButtonServer;
    }

    /** Подпись пункта в рейке меню, под списком комнат. Пустая — пункта нет. */
    public String announceNavLabel() {
        return announceNavLabel;
    }

    /**
     * Сервер, куда уводит пункт рейки. Отдельный от кнопки итогов намеренно: из меню уходят
     * в хаб сети, а с экрана итогов — туда, куда владелец зовёт после забега.
     */
    public String announceNavServer() {
        return announceNavServer;
    }

    /**
     * Сервер стоит за BungeeCord-совместимым прокси и берёт профиль игрока у него.
     *
     * <p>Включать только вместе с закрытым от мира портом бэкенда: с включённым форвардингом
     * профиль приходит из рукопожатия, и прямой коннект мимо прокси — это вход под любым ником.
     * Проверку секрета ({@code requireToken}) выключать незачем: она и есть то, что отличает
     * форвард прокси от подделки.
     */
    public boolean proxyEnabled() {
        return proxyEnabled;
    }

    public boolean proxyRequireToken() {
        return proxyRequireToken;
    }

    /** Секрет BungeeGuard: тот же, что у прокси в {@code forwarding_secret}. */
    public String proxyToken() {
        return proxyToken;
    }

    public boolean proxyDebug() {
        return proxyDebug;
    }

    public String proxyNoDataMessage() {
        return proxyNoDataMessage;
    }

    public String proxyBadTokenMessage() {
        return proxyBadTokenMessage;
    }

    public double lobbyActionsPerSecond() {
        return lobbyActionsPerSecond;
    }

    public double thoughtsPerSecond() {
        return thoughtsPerSecond;
    }

    public double dropsPerSecond() {
        return dropsPerSecond;
    }

    public int passwordAttempts() {
        return passwordAttempts;
    }

    public int passwordWindowSeconds() {
        return passwordWindowSeconds;
    }

    private static JsonObject child(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static boolean bool(JsonObject owner, String key, boolean fallback) {
        JsonElement element = owner.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException notBoolean) {
            return fallback;
        }
    }

    private static String text(JsonObject owner, String key, String fallback) {
        JsonElement element = owner.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        String value = element.getAsString();
        return value.isBlank() ? fallback : value;
    }

    private static int integer(JsonObject owner, String key, int fallback) {
        JsonElement element = owner.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException notNumber) {
            return fallback;
        }
    }

    private static double number(JsonObject owner, String key, double fallback) {
        JsonElement element = owner.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException notNumber) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
