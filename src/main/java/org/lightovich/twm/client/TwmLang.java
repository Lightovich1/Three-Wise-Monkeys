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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;

import org.lightovich.twm.Msg;
import org.lightovich.twm.Twm;

/**
 * Язык мода. Один словарь на весь мод: и для строк, которые приходят с сервера ключами, и для
 * подписей, которые Java отдаёт странице.
 *
 * <p>Почему не {@code Text.translatable} на всё: половина строк уезжает в Chromium, а там нет
 * ни языка игры, ни ресурспаков. Значит переводить обязан Java-слой, и делать это он должен из
 * того же файла, что и ванильный переводчик, — иначе две таблицы разойдутся при первой правке.
 * Файлы {@code assets/twm/lang/*.json} читаются прямо из jar: это те же файлы, которыми игра
 * переводит названия биндов.
 *
 * <p>Язык выбирает игрок в настройках мода: {@code auto} следует за языком игры, {@code ru} и
 * {@code en} фиксируют. Переключатель отдельный от игры намеренно: язык интерфейса режима —
 * дело договорённости троих, и менять ради него язык всей игры никто не станет.
 */
public final class TwmLang {

    private static final String FALLBACK = "ru";

    private static final Map<String, Map<String, String>> BUNDLES = new HashMap<>();

    /** Последний посчитанный код языка: {@link #code()} зовётся на каждую строку снимка. */
    private static String cached = "";
    private static String cachedFrom = "";

    private TwmLang() {
    }

    /** Действующий код языка: {@code ru} или {@code en}. */
    public static String code() {
        String choice = TwmSettings.ui().language();
        if (!"auto".equals(choice)) {
            return BUNDLES.containsKey(choice) || load(choice) != null ? choice : FALLBACK;
        }
        String game = gameLanguage();
        if (!game.equals(cachedFrom)) {
            cachedFrom = game;
            cached = game.startsWith("ru") || game.startsWith("uk") || game.startsWith("be")
                    ? "ru" : "en";
        }
        return cached;
    }

    /** Перевод по ключу. Неизвестный ключ возвращается как есть — это видно и не роняет экран. */
    public static String get(String key) {
        Map<String, String> bundle = bundle(code());
        String value = bundle.get(key);
        if (value != null) {
            return value;
        }
        value = bundle(FALLBACK).get(key);
        return value != null ? value : key;
    }

    /**
     * Разворачивает строку, упакованную {@link Msg}: ключ плюс аргументы вместо {@code %s}.
     *
     * <p>Строка без разделителя и без известного ключа возвращается нетронутой: так через
     * переводчик безопасно проходят имена лобби, которые придумал игрок.
     */
    public static String resolve(String packed) {
        if (packed == null || packed.isEmpty()) {
            return "";
        }
        int separator = packed.indexOf(Msg.SEPARATOR);
        if (separator < 0) {
            return get(packed);
        }
        String key = packed.substring(0, separator);
        String[] arguments = packed.substring(separator + 1).split(String.valueOf(Msg.SEPARATOR), -1);
        // Аргументом бывает другой ключ — например название сложности внутри «Сложность: %s».
        // Ник игрока ключом словаря не окажется никогда, поэтому проверка безопасна.
        for (int index = 0; index < arguments.length; index++) {
            if (bundle(code()).containsKey(arguments[index])) {
                arguments[index] = get(arguments[index]);
            }
        }
        return format(get(key), arguments);
    }

    /** Подстановка по порядку. Своя, а не {@code String.format}: ник с «%» её не ломает. */
    public static String format(String pattern, String... arguments) {
        StringBuilder result = new StringBuilder(pattern.length() + 16);
        int index = 0;
        int position = 0;
        while (position < pattern.length()) {
            char symbol = pattern.charAt(position);
            if (symbol == '%' && position + 1 < pattern.length() && pattern.charAt(position + 1) == 's') {
                result.append(index < arguments.length ? arguments[index] : "");
                index++;
                position += 2;
                continue;
            }
            result.append(symbol);
            position++;
        }
        return result.toString();
    }

    /** Список языков для переключателя интерфейса: код и самоназвание. */
    public static Map<String, String> languages() {
        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("ru", "Русский");
        languages.put("en", "English");
        return languages;
    }

    private static String gameLanguage() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || client.options.language == null) {
            return FALLBACK;
        }
        return client.options.language.toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> bundle(String language) {
        Map<String, String> bundle = BUNDLES.get(language);
        if (bundle != null) {
            return bundle;
        }
        Map<String, String> loaded = load(language);
        return loaded != null ? loaded : Map.of();
    }

    private static Map<String, String> load(String language) {
        String file = "ru".equals(language) ? "ru_ru" : "en".equals(language) ? "en_us" : null;
        if (file == null) {
            return null;
        }
        Map<String, String> bundle = new HashMap<>();
        try (InputStream stream = TwmLang.class.getClassLoader()
                .getResourceAsStream("assets/twm/lang/" + file + ".json")) {
            if (stream == null) {
                Twm.LOGGER.warn("Языковой файл не найден: {}", file);
                BUNDLES.put(language, bundle);
                return bundle;
            }
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    bundle.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            Twm.LOGGER.warn("Языковой файл {} не читается", file, e);
        }
        BUNDLES.put(language, bundle);
        return bundle;
    }
}
