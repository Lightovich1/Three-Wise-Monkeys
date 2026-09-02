/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client.cef;

import java.util.Optional;
import java.util.UUID;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import org.lightovich.twm.Thought;
import org.lightovich.twm.Twm;
import org.lightovich.twm.client.ClientParty;
import org.lightovich.twm.client.LobbySnapshot;
import org.lightovich.twm.client.SkinAvatars;
import org.lightovich.twm.client.ThoughtLog;
import org.lightovich.twm.client.TwmKeys;
import org.lightovich.twm.client.TwmLang;
import org.lightovich.twm.client.TwmSettings;
import org.lightovich.twm.client.VoiceStatus;
import org.lightovich.twm.client.perception.Echolocation;

/**
 * Второй, постоянно живущий браузер: прозрачный HUD поверх игры без обработки мыши.
 *
 * <p>Создаётся лениво при первом кадре в мире — держать его до входа в игру незачем,
 * это лишние ~100 МБ памяти. Но живёт он уже не только в команде: статус подбора,
 * название лобби и уведомления нужны игроку до начала забега.
 */
public final class HudCefManager {

    private static MCEFBrowser browser;
    private static boolean closed = true;
    private static String lastState;
    private static String selfAvatar = "";
    private static int toastCounter;

    private HudCefManager() {
    }

    public static void ensureInitialized() {
        if (browser != null && !closed) {
            return;
        }
        if (!CefManager.isAvailable()) {
            return;
        }
        CefLoadDispatcher.register();
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            browser = MCEF.createBrowser(CefManager.resolveUrl("twm/ui/hud.html"), true);
            browser.setCursorChangeListener(cursorType -> {});
            browser.resize(client.getWindow().getFramebufferWidth(),
                    client.getWindow().getFramebufferHeight());
            closed = false;
            Twm.LOGGER.info("HUD-браузер создан");
        } catch (Exception e) {
            Twm.LOGGER.error("Не удалось создать HUD-браузер", e);
            browser = null;
            closed = true;
        }
    }

    /**
     * Первое состояние почти всегда теряется: браузер создаётся лениво, и пуш успевает уйти
     * раньше готовности страницы. Поэтому по окончании загрузки состояние отдаётся заново.
     */
    public static void onPageLoaded() {
        lastState = null;
        push();
    }

    /** Пуш идёт только при изменении: HUD обновляется каждый кадр, а JS дёргать незачем. */
    public static void push() {
        if (browser == null || closed) {
            return;
        }
        String state = buildState().toString();
        if (state.equals(lastState)) {
            return;
        }
        lastState = state;
        browser.executeJavaScript("window.twmHud&&window.twmHud.set&&window.twmHud.set(" + state + ")",
                "mod://twm/", 0);
    }

    /** Разовое уведомление: событие, а не состояние, поэтому мимо снимка. */
    public static void toast(String kind, String title, String text) {
        if (browser == null || closed || !TwmSettings.hud().toasts()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", kind);
        payload.addProperty("title", title);
        payload.addProperty("text", text);
        payload.addProperty("id", ++toastCounter);
        browser.executeJavaScript("window.twmHud&&window.twmHud.toast&&window.twmHud.toast("
                + payload + ")", "mod://twm/", 0);
    }

    /**
     * Состояние собирается через Gson, а не конкатенацией: сюда попадают ники и названия лобби,
     * то есть чужой ввод — в строке без экранирования он ломал бы всю страницу.
     */
    private static JsonObject buildState() {
        JsonObject root = new JsonObject();
        root.addProperty("role", ClientParty.role() == null ? "" : ClientParty.role().id());
        root.addProperty("difficulty", ClientParty.difficulty().id());
        root.addProperty("pingReady", Echolocation.isPingReady());
        root.addProperty("phase", LobbySnapshot.phase());
        // HUD переводит себя сам: подписей у него пять, а обновляется он каждый тик.
        root.addProperty("lang", TwmLang.code());

        JsonArray thoughts = new JsonArray();
        for (ThoughtLog.Entry entry : ThoughtLog.entries()) {
            Optional<Thought> thought = Thought.fromId(entry.signal());
            JsonObject item = new JsonObject();
            item.addProperty("key", entry.key());
            item.addProperty("id", entry.signal());
            item.addProperty("label", thought
                    .map(value -> TwmLang.get(value.translationKey()))
                    .orElse(entry.signal()));
            item.addProperty("group", thought.map(value -> value.group().id()).orElse("action"));
            item.addProperty("ticks", entry.ticksLeft());
            item.addProperty("maxTicks", entry.maxTicks());
            thoughts.add(item);
        }
        root.add("thoughts", thoughts);

        JsonObject queue = LobbySnapshot.queue();
        if (queue != null) {
            root.add("queue", queue.deepCopy());
        }

        JsonObject lobby = LobbySnapshot.lobby();
        if (lobby != null) {
            JsonObject brief = new JsonObject();
            brief.addProperty("name", lobby.has("name") ? lobby.get("name").getAsString() : "");
            brief.addProperty("players", lobby.has("members")
                    ? lobby.getAsJsonArray("members").size() : 0);
            brief.addProperty("max", 3);
            brief.addProperty("state", lobby.has("state") ? lobby.get("state").getAsString() : "waiting");
            root.add("lobby", brief);
        }

        // Голос — состояние, а не событие: игрок обязан видеть, что ему сейчас можно, не
        // дожидаясь, пока попробует заговорить и не получит ответа.
        JsonObject voice = new JsonObject();
        voice.addProperty("speak", VoiceStatus.canSpeak());
        voice.addProperty("hear", VoiceStatus.canHear());
        voice.addProperty("ready", VoiceStatus.ready());
        root.add("voice", voice);

        JsonObject hud = new JsonObject();
        hud.addProperty("scale", TwmSettings.hud().scale());
        hud.addProperty("toasts", TwmSettings.hud().toasts());
        hud.addProperty("pingKey", TwmKeys.label("twm.ping"));
        root.add("hud", hud);

        JsonObject self = new JsonObject();
        self.addProperty("name", LobbySnapshot.selfName());
        self.addProperty("avatar", selfAvatar());
        root.add("self", self);
        return root;
    }

    /**
     * Своя аватарка запрашивается, пока не получена: скин приходит асинхронно, и первые кадры
     * после входа его ещё нет. Найденную запоминаем — читать текстуру каждый тик незачем.
     */
    private static String selfAvatar() {
        if (!selfAvatar.isEmpty()) {
            return selfAvatar;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return "";
        }
        UUID uuid = client.player.getUuid();
        selfAvatar = SkinAvatars.resolve(uuid, client.player.getGameProfile().getName()).orElse("");
        return selfAvatar;
    }

    public static void resize(int width, int height) {
        if (browser != null && !closed) {
            browser.resize(width, height);
        }
    }

    public static int textureId() {
        return browser == null || closed ? -1 : browser.getRenderer().getTextureID();
    }

    public static MCEFBrowser getBrowser() {
        return browser != null && !closed ? browser : null;
    }

    /**
     * Сбрасывает кэш состояния, оставляя браузер жить. Закрывать его на выходе с сервера
     * не стоит: CEF умеет подвиснуть в {@code close()}, а страница всё равно понадобится
     * при следующем входе.
     */
    public static void forgetState() {
        lastState = null;
        selfAvatar = "";
    }

    /** Смена языка не меняет состояние, поэтому обычный пуш её не заметит — сбрасываем кэш. */
    public static void refreshLanguage() {
        lastState = null;
        push();
    }
}
