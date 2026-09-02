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

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * Бинды мода для страницы настроек: список, захват клавиши и конфликты.
 *
 * <p>Свои настройки бинды не заводят: это обычные {@link KeyBinding}, они живут в
 * {@code options.txt} рядом с ванильными. Иначе одна и та же клавиша хранилась бы в двух местах
 * и расходилась после правки из ванильного меню управления.
 */
public final class TwmKeys {

    /**
     * Подпись берётся из того же ключа, которым бинд назван в ванильном меню управления:
     * два перевода одного действия рано или поздно разошлись бы.
     */
    private static final List<Bind> BINDS = List.of(
            new Bind("twm.lobby", TwmClient.LOBBY),
            new Bind("twm.thoughts", TwmClient.THOUGHTS),
            new Bind("twm.guide", TwmClient.GUIDE),
            new Bind("twm.free_look", TwmClient.FREE_LOOK),
            new Bind("twm.ping", TwmClient.PING));

    private static String capturing = "";

    private TwmKeys() {
    }

    public static boolean isCapturing() {
        return !capturing.isEmpty();
    }

    public static String capturing() {
        return capturing;
    }

    public static void beginCapture(String id) {
        capturing = find(id) == null ? "" : id;
    }

    public static void cancelCapture() {
        capturing = "";
    }

    /** Назначает захваченную клавишу текущему бинду. Захват завершается в любом случае. */
    public static void applyCapture(int keyCode, int scanCode) {
        Bind bind = find(capturing);
        capturing = "";
        if (bind != null) {
            rebind(bind, InputUtil.fromKeyCode(keyCode, scanCode));
        }
    }

    /**
     * То же для кнопки мыши: боковые кнопки — самый удобный бинд для эхо-пинга, а ванильное
     * меню управления их и так принимает. Ограничения по номеру здесь нет: что можно назначить,
     * решает вызывающий экран — он же отличает нажатие по интерфейсу от назначения.
     */
    public static void applyMouseCapture(int button) {
        Bind bind = find(capturing);
        capturing = "";
        if (bind != null) {
            rebind(bind, InputUtil.Type.MOUSE.createFromCode(button));
        }
    }

    public static void unbind(String id) {
        Bind bind = find(id);
        if (bind != null) {
            rebind(bind, InputUtil.UNKNOWN_KEY);
        }
    }

    /**
     * Список для {@code onSettings}. {@code conflict} считается по всем биндам игры: та же
     * клавиша у ванильного действия означает, что сработают оба, и игрок должен это видеть.
     */
    public static JsonArray toJson() {
        JsonArray array = new JsonArray();
        for (Bind bind : BINDS) {
            JsonObject item = new JsonObject();
            item.addProperty("id", bind.id());
            item.addProperty("label", TwmLang.get("key." + bind.id()));
            item.addProperty("key", bind.binding().getBoundKeyLocalizedText().getString());
            item.addProperty("conflict", hasConflict(bind.binding()));
            array.add(item);
        }
        return array;
    }

    /** Подпись клавиши для HUD: подсказка про эхо-пинг обязана совпадать с реальным биндом. */
    public static String label(String id) {
        Bind bind = find(id);
        return bind == null ? "" : bind.binding().getBoundKeyLocalizedText().getString();
    }

    private static void rebind(Bind bind, InputUtil.Key key) {
        bind.binding().setBoundKey(key);
        // Без пересборки индекса клавиша остаётся в старом слоте и бинд не срабатывает.
        KeyBinding.updateKeysByCode();
        MinecraftClient.getInstance().options.write();
    }

    private static boolean hasConflict(KeyBinding binding) {
        if (binding.isUnbound()) {
            return false;
        }
        String key = binding.getBoundKeyTranslationKey();
        for (KeyBinding other : MinecraftClient.getInstance().options.allKeys) {
            if (other != binding && !other.isUnbound() && other.getBoundKeyTranslationKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static Bind find(String id) {
        for (Bind bind : BINDS) {
            if (bind.id().equals(id)) {
                return bind;
            }
        }
        return null;
    }

    private record Bind(String id, KeyBinding binding) {
    }
}
