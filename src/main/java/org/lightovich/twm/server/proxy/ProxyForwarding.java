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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import org.lightovich.twm.Twm;
import org.lightovich.twm.server.TwmConfig;

/**
 * Разбор рукопожатия, пришедшего через BungeeCord-совместимый прокси.
 *
 * <p>Прокси кладёт всё, что знает об игроке, в поле адреса рукопожатия, разделяя нулевыми
 * байтами: {@code хост\0адрес игрока\0uuid без дефисов\0свойства профиля}. Свойства — это
 * массив JSON, тот же, что отдаёт сессионный сервер Mojang, плюс свойство с секретом
 * прокси, если тот работает в режиме BungeeGuard.
 *
 * <p>Проверка секрета — единственное, что отличает игрока от того, кто постучался в бэкенд
 * напрямую и назвался чужим именем. Поэтому без секрета соединение не проходит, если
 * {@code proxy.requireToken} не выключен явно, а сам секрет вырезается из свойств: дальше по
 * коду профиль виден всем, включая других игроков.
 */
public final class ProxyForwarding {

    /** Имя свойства с секретом. Задано спецификацией BungeeGuard, прокси кладут именно его. */
    private static final String TOKEN_PROPERTY = "bungeeguard-token";

    /** Ванильный предел строки рукопожатия. Форвард в него не влезает — отсюда и миксин. */
    public static final int VANILLA_ADDRESS_LIMIT = 255;

    /** Предел строки в пакете вообще: дальше расширять нельзя, да и незачем. */
    public static final int FORWARDED_ADDRESS_LIMIT = 32767;

    private ProxyForwarding() {
    }

    /** Сколько байт разрешено читать в адресе рукопожатия при текущих настройках. */
    public static int addressLimit() {
        return TwmConfig.get().proxyEnabled() ? FORWARDED_ADDRESS_LIMIT : VANILLA_ADDRESS_LIMIT;
    }

    /**
     * Разбирает адрес рукопожатия. {@code null} означает «форварда нет» — это либо прямой
     * коннект в бэкенд, либо прокси без форвардинга; решение, пускать ли такого, принимает
     * логин, а не разбор.
     */
    public static ForwardedLogin parse(String rawAddress, SocketAddress socketAddress) {
        String[] parts = rawAddress.split("\0");
        if (parts.length < 3) {
            return null;
        }
        UUID uuid = parseUuid(parts[2]);
        if (uuid == null) {
            return null;
        }
        PropertyMap properties = new PropertyMap();
        String token = null;
        if (parts.length > 3) {
            token = readProperties(parts[3], properties);
        }
        return new ForwardedLogin(uuid, address(parts[1], socketAddress), properties, token);
    }

    /** Секрет сверяется целиком и всегда, даже если он не пришёл: иначе проверки нет вовсе. */
    public static boolean tokenValid(ForwardedLogin forwarded) {
        if (!TwmConfig.get().proxyRequireToken()) {
            return true;
        }
        String expected = TwmConfig.get().proxyToken();
        return !expected.isEmpty() && expected.equals(forwarded.token());
    }

    /**
     * Строка в лог при {@code proxy.debug}. Секрет в неё не попадает — только факт его
     * наличия: лог сервера читают чаще, чем конфиг, и утекает он легче.
     */
    public static void logForwarded(ForwardedLogin forwarded, String name) {
        if (!TwmConfig.get().proxyDebug()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Property property : forwarded.properties().values()) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(property.name());
        }
        Twm.LOGGER.info("Форвард прокси: игрок {}, uuid {}, адрес {}, свойства [{}], секрет {}",
                name, forwarded.uuid(), forwarded.address(), names,
                forwarded.token() == null ? "не пришёл" : "пришёл");
    }

    /**
     * UUID приезжает без дефисов — так его кладут все прокси со времён BungeeCord. Дефисы
     * вставляются на место, а не через регулярку: строка фиксированной длины, и разбор
     * обязан отвергнуть всё, что на неё не похоже.
     */
    private static UUID parseUuid(String raw) {
        try {
            if (raw.length() == 32) {
                return UUID.fromString(raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                        + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20));
            }
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            Twm.LOGGER.warn("Форвард прокси с нечитаемым uuid: {}", raw);
            return null;
        }
    }

    /** Возвращает секрет и наполняет карту всеми остальными свойствами. */
    private static String readProperties(String json, PropertyMap into) {
        String token = null;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                return null;
            }
            JsonArray array = parsed.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String name = string(object, "name");
                String value = string(object, "value");
                if (name == null || value == null) {
                    continue;
                }
                if (TOKEN_PROPERTY.equals(name.toLowerCase(Locale.ROOT))) {
                    token = value;
                    continue;
                }
                String signature = string(object, "signature");
                into.put(name, signature == null ? new Property(name, value)
                        : new Property(name, value, signature));
            }
        } catch (RuntimeException e) {
            Twm.LOGGER.warn("Свойства в форварде прокси не разобраны: {}", e.toString());
        }
        return token;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * Порт сохраняется от исходного сокета: настоящий порт игрока прокси не передаёт, а
     * нулевой ломает всё, что печатает адрес парой.
     */
    private static SocketAddress address(String ip, SocketAddress original) {
        int port = original instanceof InetSocketAddress inet ? inet.getPort() : 0;
        try {
            return new InetSocketAddress(ip, port);
        } catch (RuntimeException e) {
            Twm.LOGGER.warn("Адрес игрока из форварда не разобран ({}), оставляю адрес прокси", ip);
            return original;
        }
    }
}
