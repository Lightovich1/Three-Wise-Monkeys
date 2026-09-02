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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import org.lightovich.twm.Twm;

/**
 * Клиентские настройки восприятия и интерфейса — {@code config/twm.json}.
 *
 * <p>Значения приходят со страницы настроек, то есть из Chromium. Странице не доверяем: любое
 * число зажимается в диапазон здесь, в Java, а неизвестный путь молча игнорируется. Иначе
 * подменённая страница выставила бы, например, нулевую яркость и ослепила бы ноги окончательно.
 *
 * <p>Битый или частично заполненный файл не должен ронять запуск: разбор всегда начинается с
 * значений по умолчанию, а всё, что не разобралось, остаётся дефолтным.
 */
public final class TwmSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private static Data data = new Data();

    private TwmSettings() {
    }

    public static Sound sound() {
        return data.sound;
    }

    public static Echo echo() {
        return data.echo;
    }

    public static Radar radar() {
        return data.radar;
    }

    public static Hud hud() {
        return data.hud;
    }

    public static Ui ui() {
        return data.ui;
    }

    /** Время жизни эха в тиках: страница оперирует секундами, механика — тиками. */
    public static int echoLifeTicks() {
        return Math.round(data.echo.lifeSeconds * 20.0F);
    }

    /** Сколько тиков живёт дуга на кольце звуков. */
    public static int radarTicks() {
        return Math.round(data.radar.seconds * 20.0F);
    }

    /** Сколько тиков висит карточка мысли. */
    public static int thoughtTicks() {
        return Math.round(data.hud.thoughtSeconds * 20.0F);
    }

    /** Цвет контуров эхолокации как 0xRRGGBB. Битую строку заменяет цвет по умолчанию. */
    public static int echoRgb() {
        try {
            return Integer.parseInt(data.echo.color.substring(1), 16);
        } catch (RuntimeException e) {
            return 0x9FF2FF;
        }
    }

    public static void load() {
        Path file = file();
        if (!Files.exists(file)) {
            save();
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Data parsed = GSON.fromJson(raw, Data.class);
            if (parsed != null) {
                data = parsed;
            }
        } catch (IOException | RuntimeException e) {
            Twm.LOGGER.warn("Настройки не читаются, взяты значения по умолчанию", e);
            data = new Data();
        }
        repair();
        save();
    }

    public static void save() {
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Twm.LOGGER.warn("Настройки не сохранены", e);
        }
    }

    public static void reset() {
        data = new Data();
        save();
    }

    /**
     * Точечная правка по пути вида {@code echo.brightness}. Возвращает {@code false}, если путь
     * неизвестен — тогда страницу просто не переспрашивают, а мод продолжает работать.
     */
    public static boolean set(String path, JsonElement value) {
        if (path == null || value == null || !value.isJsonPrimitive()) {
            return false;
        }
        switch (path) {
            case "sound.pingEnabled" -> data.sound.pingEnabled = value.getAsBoolean();
            case "sound.pingVolume" -> data.sound.pingVolume = clamp(value.getAsFloat(), 0.0F, 1.0F);
            case "sound.waveEnabled" -> data.sound.waveEnabled = value.getAsBoolean();
            case "sound.waveVolume" -> data.sound.waveVolume = clamp(value.getAsFloat(), 0.0F, 1.0F);
            case "sound.uiEnabled" -> data.sound.uiEnabled = value.getAsBoolean();
            case "sound.uiVolume" -> data.sound.uiVolume = clamp(value.getAsFloat(), 0.0F, 1.0F);
            case "sound.uiStyle" -> data.sound.uiStyle = style(value.getAsString(), data.sound.uiStyle);
            case "echo.brightness" -> data.echo.brightness = clamp(value.getAsFloat(), 0.3F, 1.0F);
            case "echo.lineWidth" -> data.echo.lineWidth = clamp(value.getAsFloat(), 1.0F, 4.0F);
            case "echo.lifeSeconds" -> data.echo.lifeSeconds = clamp(value.getAsFloat(), 3.0F, 20.0F);
            case "echo.color" -> data.echo.color = color(value.getAsString(), data.echo.color);
            case "radar.enabled" -> data.radar.enabled = value.getAsBoolean();
            case "radar.brightness" -> data.radar.brightness = clamp(value.getAsFloat(), 0.3F, 1.0F);
            case "radar.radius" -> data.radar.radius = clamp(value.getAsFloat(), 24.0F, 96.0F);
            case "radar.seconds" -> data.radar.seconds = clamp(value.getAsFloat(), 0.5F, 4.0F);
            case "hud.scale" -> data.hud.scale = clamp(value.getAsFloat(), 0.7F, 1.6F);
            case "hud.thoughtSeconds" -> data.hud.thoughtSeconds = clamp(value.getAsFloat(), 2.0F, 10.0F);
            case "hud.toasts" -> data.hud.toasts = value.getAsBoolean();
            case "ui.language" -> data.ui.language = language(value.getAsString(), data.ui.language);
            default -> {
                Twm.LOGGER.debug("Неизвестная настройка: {}", path);
                return false;
            }
        }
        save();
        return true;
    }

    /** Снимок для страницы. Список биндов добавляет мост: он живёт не в файле, а в options.txt. */
    public static JsonObject toJson() {
        return GSON.toJsonTree(data).getAsJsonObject();
    }

    /** Чужой файл мог прийти из другой версии: недостающие секции восстанавливаются, числа зажимаются. */
    private static void repair() {
        if (data.sound == null) {
            data.sound = new Sound();
        }
        if (data.echo == null) {
            data.echo = new Echo();
        }
        if (data.radar == null) {
            data.radar = new Radar();
        }
        if (data.hud == null) {
            data.hud = new Hud();
        }
        if (data.ui == null) {
            data.ui = new Ui();
        }
        data.sound.pingVolume = clamp(data.sound.pingVolume, 0.0F, 1.0F);
        data.sound.waveVolume = clamp(data.sound.waveVolume, 0.0F, 1.0F);
        data.sound.uiVolume = clamp(data.sound.uiVolume, 0.0F, 1.0F);
        data.sound.uiStyle = style(data.sound.uiStyle, "soft");
        data.radar.brightness = clamp(data.radar.brightness, 0.3F, 1.0F);
        data.radar.radius = clamp(data.radar.radius, 24.0F, 96.0F);
        data.radar.seconds = clamp(data.radar.seconds, 0.5F, 4.0F);
        data.echo.brightness = clamp(data.echo.brightness, 0.3F, 1.0F);
        data.echo.lineWidth = clamp(data.echo.lineWidth, 1.0F, 4.0F);
        data.echo.lifeSeconds = clamp(data.echo.lifeSeconds, 3.0F, 20.0F);
        data.echo.color = color(data.echo.color, "#9FF2FF");
        data.hud.scale = clamp(data.hud.scale, 0.7F, 1.6F);
        data.hud.thoughtSeconds = clamp(data.hud.thoughtSeconds, 2.0F, 10.0F);
        data.ui.language = language(data.ui.language, "auto");
    }

    /** Неизвестный тембр интерфейса — тот же случай, что неизвестный язык: берём обычный. */
    private static String style(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "soft", "air" -> normalized;
            default -> fallback;
        };
    }

    /** Неизвестный код языка — это не повод падать: интерфейс просто следует за игрой. */
    private static String language(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "auto", "ru", "en" -> normalized;
            default -> fallback;
        };
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.min(max, Math.max(min, value));
    }

    private static String color(String value, String fallback) {
        return value != null && COLOR.matcher(value).matches() ? value : fallback;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("twm.json");
    }

    /** Имена полей совпадают с контрактом: страница читает этот же объект без переводчика. */
    private static final class Data {
        private Sound sound = new Sound();
        private Echo echo = new Echo();
        private Radar radar = new Radar();
        private Hud hud = new Hud();
        private Ui ui = new Ui();
    }

    public static final class Sound {
        private boolean pingEnabled = true;
        private float pingVolume = 1.0F;
        /**
         * Отклик на каждый шаг выключен по умолчанию: на сложной ноги шагают непрерывно, и
         * звук на каждый шаг превращается в фон, который перестают слышать. Включается тем,
         * кому он нужен как метроном.
         */
        private boolean waveEnabled = false;
        private float waveVolume = 0.45F;
        private boolean uiEnabled = true;
        private float uiVolume = 0.7F;
        /**
         * Тембр интерфейса. {@code soft} — мягкий тон, {@code air} — короткий выдох без
         * высоты тона вовсе. Два, а не один: «приятный звук» — вкус, а не характеристика,
         * и спорить о нём дешевле переключателем, чем правкой синтеза.
         */
        private String uiStyle = "soft";

        public boolean pingEnabled() {
            return pingEnabled;
        }

        public float pingVolume() {
            return pingVolume;
        }

        public boolean waveEnabled() {
            return waveEnabled;
        }

        public float waveVolume() {
            return waveVolume;
        }

        public boolean uiEnabled() {
            return uiEnabled;
        }

        public float uiVolume() {
            return uiVolume;
        }

        public String uiStyle() {
            return uiStyle;
        }
    }

    /**
     * Кольцо услышанного у рук. Настраивается только читаемость: что именно попадает в кольцо
     * и на какой сложности оно вообще есть — правила игры, а не вкус.
     */
    public static final class Radar {
        private boolean enabled = true;
        private float brightness = 1.0F;
        private float radius = 44.0F;
        private float seconds = 1.6F;

        public boolean enabled() {
            return enabled;
        }

        public float brightness() {
            return brightness;
        }

        public float radius() {
            return radius;
        }

        public float seconds() {
            return seconds;
        }
    }

    public static final class Echo {
        private float brightness = 1.0F;
        private float lineWidth = 1.5F;
        private float lifeSeconds = 12.0F;
        private String color = "#9FF2FF";

        public float brightness() {
            return brightness;
        }

        public float lineWidth() {
            return lineWidth;
        }

        public float lifeSeconds() {
            return lifeSeconds;
        }
    }

    public static final class Hud {
        private float scale = 1.0F;
        private float thoughtSeconds = 4.0F;
        private boolean toasts = true;

        public float scale() {
            return scale;
        }

        public boolean toasts() {
            return toasts;
        }
    }

    /** Язык интерфейса: {@code auto} следует за языком игры, {@code ru} и {@code en} фиксируют. */
    public static final class Ui {
        private String language = "auto";

        public String language() {
            return language;
        }
    }
}
