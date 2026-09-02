/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.lightovich.twm.Twm;

/**
 * Последний снимок лобби, пришедший с сервера.
 *
 * <p>Разбирается ровно один раз на пакет и хранится целиком: страницу меню интересует весь JSON,
 * а HUD берёт из него фазу, очередь и краткую сводку лобби. Ничего своего клиент сюда не
 * дописывает — состав лобби и права считает только сервер.
 *
 * <p>Сырой снимок хранится рядом с переведённым. Сервер шлёт ключи, а не текст, и перевод —
 * дело клиента; но переводить надо уметь заново, не дожидаясь пакета: игрок вправе сменить
 * язык прямо в открытом меню, и список комнат обязан перерисоваться сразу.
 */
public final class LobbySnapshot {

    /** Поля снимка, в которых сервер присылает ключ, а не готовый текст. */
    private static final String[] LOBBY_TEXTS = {"name", "startHint"};

    private static JsonObject original;
    private static String raw = "";
    private static JsonObject parsed;
    private static boolean firstOfSession = true;
    private static boolean playing;
    private static boolean runStarted;
    private static boolean leaving;

    private LobbySnapshot() {
    }

    public static void accept(String json) {
        try {
            original = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            Twm.LOGGER.warn("Снимок лобби не разобран: {}", json, e);
            original = null;
        }
        relocalize();
        // Переход считается здесь, а не на странице: обучение открывается и с закрытым меню,
        // а закрытое меню про смену фазы ничего не знает.
        boolean now = phase().equals("playing");
        if (now && !playing) {
            runStarted = true;
        }
        playing = now;
    }

    /** Пересобирает переведённый снимок из сырого. Зовётся и при смене языка интерфейса. */
    public static void relocalize() {
        if (original == null) {
            parsed = null;
            raw = "";
            return;
        }
        parsed = localize(original.deepCopy());
        raw = parsed.toString();
    }

    /**
     * Перевод затрагивает только те поля, о которых договорён контракт. Ники, коды и названия,
     * придуманные игроками, проходят через {@code TwmLang.resolve} нетронутыми: в них нет
     * разделителя и они не совпадают с ключами словаря.
     */
    private static JsonObject localize(JsonObject root) {
        translate(root, "notice");
        if (root.get("lobby") instanceof JsonObject lobby) {
            for (String field : LOBBY_TEXTS) {
                translate(lobby, field);
            }
            if (lobby.get("roles") instanceof JsonArray roles) {
                for (JsonElement role : roles) {
                    translate(role.getAsJsonObject(), "name");
                }
            }
        }
        if (root.get("lobbies") instanceof JsonArray lobbies) {
            for (JsonElement entry : lobbies) {
                translate(entry.getAsJsonObject(), "name");
            }
        }
        if (root.get("invites") instanceof JsonArray invites) {
            for (JsonElement entry : invites) {
                translate(entry.getAsJsonObject(), "lobby");
            }
        }
        return root;
    }

    private static void translate(JsonObject owner, String field) {
        JsonElement value = owner.get(field);
        if (value != null && value.isJsonPrimitive()) {
            owner.addProperty(field, TwmLang.resolve(value.getAsString()));
        }
    }

    /** Сброс на входе и выходе: «первый снимок за подключение» открывает меню ровно один раз. */
    public static void clear() {
        original = null;
        raw = "";
        parsed = null;
        firstOfSession = true;
        playing = false;
        runStarted = false;
        leaving = false;
    }

    public static boolean hasSnapshot() {
        return parsed != null;
    }

    public static String raw() {
        return raw;
    }

    /** Отдаёт true один раз на каждый переход лобби в игру: это момент показа обучения. */
    public static boolean takeRunStart() {
        boolean started = runStarted;
        runStarted = false;
        return started;
    }

    /**
     * Ждём ли мы ещё первого открытия меню за это подключение.
     *
     * <p>Отдельно от {@link #takeFirstOfSession()} потому, что попытку нельзя тратить на
     * проверку: момент входа в мир занят чужим экраном (ваниль показывает загрузку
     * местности), и открывать меню там некуда — а второй попытки уже не будет.
     */
    public static boolean firstOfSession() {
        return firstOfSession;
    }

    /** Отдаёт true один-единственный раз после подключения. */
    public static boolean takeFirstOfSession() {
        boolean first = firstOfSession;
        firstOfSession = false;
        return first;
    }

    /** {@code none} | {@code queue} | {@code lobby} | {@code playing}. */
    public static String phase() {
        return string("phase", "none");
    }

    /**
     * Меню заперто: закрыть его в мир нельзя, пока забег не начался. Решение серверное и
     * приходит в снимке — на сервере, где мод стоит рядом с другими режимами, запертое меню
     * отняло бы у игрока весь остальной сервер, поэтому включает его владелец, а не клиент.
     *
     * <p>Умолчание — «не заперто»: сервер старой сборки поля не пришлёт, и запирать по
     * молчанию нельзя.
     */
    public static boolean menuLocked() {
        return !leaving && modeFlag("lockMenu") && !phase().equals("playing");
    }

    /**
     * Игрок нажал «уйти на другой сервер». До самого перехода проходит несколько тиков, и
     * всё это время запертое меню возвращалось бы на место — то есть кнопка выглядела бы
     * сломанной. Латч снимает запрет до прихода нового сервера.
     */
    public static void leaveRequested() {
        leaving = true;
    }

    /** Меню открывается само при входе на сервер. Тоже решение сервера, не клиента. */
    public static boolean autoOpen() {
        return modeFlag("autoOpen");
    }

    private static boolean modeFlag(String key) {
        JsonObject mode = object("mode");
        return mode != null && mode.has(key) && mode.get(key).isJsonPrimitive()
                && mode.get(key).getAsBoolean();
    }

    public static boolean inLobby() {
        String phase = phase();
        return phase.equals("lobby") || phase.equals("playing");
    }

    public static JsonObject queue() {
        return object("queue");
    }

    public static JsonObject lobby() {
        return object("lobby");
    }

    public static String selfName() {
        JsonObject self = object("self");
        return self != null && self.has("name") ? self.get("name").getAsString() : "";
    }

    private static String string(String key, String fallback) {
        if (parsed == null || !parsed.has(key) || !parsed.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return parsed.get(key).getAsString();
    }

    private static JsonObject object(String key) {
        if (parsed == null || !parsed.has(key) || !parsed.get(key).isJsonObject()) {
            return null;
        }
        return parsed.getAsJsonObject(key);
    }
}
