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
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import org.lightovich.twm.Thought;
import org.lightovich.twm.Twm;
import org.lightovich.twm.client.ClientParty;
import org.lightovich.twm.client.SkinAvatars;
import org.lightovich.twm.client.LobbySnapshot;
import org.lightovich.twm.client.ThoughtLog;
import org.lightovich.twm.client.TwmKeys;
import org.lightovich.twm.client.TwmLang;
import org.lightovich.twm.client.TwmSettings;
import org.lightovich.twm.client.UiSounds;
import org.lightovich.twm.net.TwmPayloads;

/**
 * Мост между интерфейсом и модом.
 *
 * <p>JS → Java: {@code window.cefQuery({request: JSON})}. Java → JS: {@code window.twm.on*(data)}.
 * Интерфейс присылает только намерение — решение остаётся за сервером, поэтому подменённая
 * страница не открывает недоступные действия. Полный список сообщений — в
 * {@code TwmPayloads} и комментарии рядом с обработчиками.
 */
public final class CefBridge {

    private static boolean registered;
    private static String lastStateJson;
    private static String lastSummaryJson;
    private static String pendingScreen = "main";

    private CefBridge() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        MCEF.getClient().getHandle().addMessageRouter(CefMessageRouter.create(new QueryHandler()));
        CefLoadDispatcher.register();
        registered = true;
    }

    /** Кэш нужен потому, что страница может загрузиться позже пришедшего состояния. */
    public static void cacheState(String json) {
        lastStateJson = json;
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser != null) {
            pushState(browser, json);
        }
    }

    /**
     * Итоги последнего забега. Кэшируются по той же причине, что и снимок лобби: экран итогов
     * мод открывает сам, а страница к этому моменту может быть ещё не загружена.
     */
    public static void cacheSummary(String json) {
        lastSummaryJson = json;
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser != null) {
            pushSummary(browser, json);
        }
    }

    public static void pushSummary(MCEFBrowser browser, String json) {
        exec(browser, "window.twm&&window.twm.onSummary&&window.twm.onSummary(" + json + ")");
    }

    /** Экран, который просили открыть до того, как страница успела загрузиться. */
    public static void requestScreen(String screen) {
        pendingScreen = screen;
    }

    public static void onMenuLoaded() {
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser == null) {
            return;
        }
        // Язык идёт первым: словарь и снимок приходят уже переведёнными, и страница должна
        // знать, каким набором подписей рисовать собственные тексты.
        pushMeta(browser);
        pushLanguage(browser);
        pushDictionary(browser);
        pushRole(browser);
        pushSettings();
        if (lastStateJson != null) {
            pushState(browser, lastStateJson);
        }
        if (lastSummaryJson != null) {
            pushSummary(browser, lastSummaryJson);
        }
        // Первое открытие идёт до готовности страницы, поэтому нужный экран отдаётся здесь,
        // иначе панель мыслей открывалась бы на главном экране.
        pushScreen(browser, pendingScreen);
    }

    /**
     * Версия сборки для подписи в углу меню. Берётся у загрузчика, а не из константы: строка в
     * интерфейсе обязана совпадать с тем jar, который сейчас лежит в {@code mods/}.
     */
    public static void pushMeta(MCEFBrowser browser) {
        JsonObject payload = new JsonObject();
        payload.addProperty("version", FabricLoader.getInstance().getModContainer(Twm.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse(""));
        exec(browser, "window.twm&&window.twm.onMeta&&window.twm.onMeta(" + payload + ")");
    }

    public static void pushState(MCEFBrowser browser, String json) {
        exec(browser, "window.twm&&window.twm.onState&&window.twm.onState(" + json + ")");
    }

    public static void pushScreen(MCEFBrowser browser, String screen) {
        exec(browser, "window.twm&&window.twm.onScreen&&window.twm.onScreen('" + screen + "')");
    }

    /** Роль определяет, что вообще показывать: панель мыслей есть только у головы. */
    public static void pushRole(MCEFBrowser browser) {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", ClientParty.role() == null ? "" : ClientParty.role().id());
        payload.addProperty("difficulty", ClientParty.difficulty().id());
        exec(browser, "window.twm&&window.twm.onRole&&window.twm.onRole(" + payload + ")");
    }

    /** Настройки и бинды идут одним сообщением: страница рисует их одним экраном. */
    public static void pushSettings() {
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser == null) {
            return;
        }
        JsonObject payload = TwmSettings.toJson();
        payload.add("keys", TwmKeys.toJson());
        exec(browser, "window.twm&&window.twm.onSettings&&window.twm.onSettings(" + payload + ")");
    }

    /** Состояние захвата клавиши вместе со свежим списком биндов — после каждого назначения. */
    public static void pushCaptureAndSettings() {
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser == null) {
            return;
        }
        exec(browser, "window.twm&&window.twm.onCapture&&window.twm.onCapture('"
                + TwmKeys.capturing() + "')");
        pushSettings();
    }

    /** Тоаст поверх меню. HUD получает своё уведомление отдельно: это разные страницы. */
    public static void pushToast(String kind, String title, String text) {
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", kind);
        payload.addProperty("title", title);
        payload.addProperty("text", text);
        exec(browser, "window.twm&&window.twm.onToast&&window.twm.onToast(" + payload + ")");
    }

    /** Словарь мыслей отдаётся из мода, чтобы подписи не разъезжались с проверкой на сервере. */
    private static void pushDictionary(MCEFBrowser browser) {
        JsonArray items = new JsonArray();
        for (Thought thought : Thought.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", thought.id());
            item.addProperty("label", TwmLang.get(thought.translationKey()));
            item.addProperty("group", thought.group().id());
            items.add(item);
        }
        exec(browser, "window.twm&&window.twm.onDictionary&&window.twm.onDictionary(" + items + ")");
    }

    /** Действующий язык и список доступных: страница рисует переключатель по этому списку. */
    public static void pushLanguage(MCEFBrowser browser) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", TwmLang.code());
        payload.addProperty("choice", TwmSettings.ui().language());
        JsonArray options = new JsonArray();
        TwmLang.languages().forEach((code, name) -> {
            JsonObject option = new JsonObject();
            option.addProperty("code", code);
            option.addProperty("name", name);
            options.add(option);
        });
        payload.add("options", options);
        exec(browser, "window.twm&&window.twm.onLang&&window.twm.onLang(" + payload + ")");
    }

    /**
     * Смена языка перерисовывает всё сразу: словарь мыслей, снимок лобби и HUD собраны в Java
     * и переводом страницы не чинятся. Снимок берётся из сохранённого сырого — переспрашивать
     * сервер незачем, состояние не изменилось.
     */
    public static void refreshLanguage() {
        LobbySnapshot.relocalize();
        lastStateJson = LobbySnapshot.raw();
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser != null) {
            pushLanguage(browser);
            pushDictionary(browser);
            if (!lastStateJson.isEmpty()) {
                pushState(browser, lastStateJson);
            }
        }
        pushSettings();
        HudCefManager.refreshLanguage();
    }

    private static void pushAvatar(String key, String uri) {
        MCEFBrowser browser = CefManager.getBrowser();
        if (browser == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty(key, uri);
        exec(browser, "window.twm&&window.twm.onAvatars&&window.twm.onAvatars(" + payload + ")");
    }

    private static void exec(MCEFBrowser browser, String script) {
        browser.executeJavaScript(script, "mod://twm/", 0);
    }

    private static final class QueryHandler extends CefMessageRouterHandlerAdapter {
        @Override
        public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                               boolean persistent, CefQueryCallback callback) {
            MinecraftClient client = MinecraftClient.getInstance();
            try {
                JsonObject command = JsonParser.parseString(request).getAsJsonObject();
                String name = command.get("cmd").getAsString();
                switch (name) {
                    case "lobby" -> {
                        String action = command.get("action").getAsString();
                        String argument = command.has("arg") ? command.get("arg").getAsString() : "";
                        client.execute(() -> {
                            ClientPlayNetworking.send(new TwmPayloads.LobbyAction(action, argument));
                            // Уход на соседний сервер: меню закрывается сразу, не дожидаясь
                            // перехода. Иначе игрок несколько тиков смотрит в то же меню и
                            // решает, что кнопка не сработала.
                            if ("network_connect".equals(action)) {
                                LobbySnapshot.leaveRequested();
                                client.setScreen(null);
                            }
                        });
                        callback.success("");
                    }
                    case "thought" -> {
                        String signal = command.get("signal").getAsString();
                        // Клиент проверяет словарь только чтобы не слать мусор; настоящая
                        // проверка роли и адресата остаётся на сервере.
                        Thought.fromId(signal).ifPresent(thought -> client.execute(() -> {
                            ClientPlayNetworking.send(new TwmPayloads.ThoughtSend(thought.id()));
                            // Голова видит собственную мысль ровно так же, как её видят ноги:
                            // без обратной связи «отправилось ли вообще» ответа нет, а говорить
                            // она не может и переспросить некому. Карточка своя, локальная —
                            // сервер шлёт мысль только ногам и вторым адресатом её не дублирует.
                            if (ClientParty.isHead() && client.player != null) {
                                ThoughtLog.add(thought.id(), client.player.getGameProfile().getName());
                            }
                            client.setScreen(null);
                        }));
                        callback.success("");
                    }
                    case "settings" -> {
                        handleSettings(client, command);
                        callback.success("");
                    }
                    case "avatar" -> {
                        handleAvatar(client, command);
                        callback.success("");
                    }
                    case "ui" -> {
                        // Звук интерфейса играет мод: у Chromium свой выход мимо микшера игры.
                        String sound = command.has("sound") ? command.get("sound").getAsString() : "";
                        client.execute(() -> UiSounds.byName(sound));
                        callback.success("");
                    }
                    case "close" -> {
                        // Политика выхода одна и живёт в экране: страница просит закрыться,
                        // а закрыться ли в мир или на главный экран — решает мод.
                        client.execute(() -> CefScreen.leave(client,
                                client.currentScreen instanceof CefScreen screen
                                        ? screen.screenId() : ""));
                        callback.success("");
                    }
                    default -> callback.failure(400, "Неизвестная команда: " + name);
                }
            } catch (Exception e) {
                Twm.LOGGER.warn("Ошибка запроса из интерфейса: {}", request, e);
                callback.failure(500, e.getMessage() == null ? "Внутренняя ошибка" : e.getMessage());
            }
            return true;
        }

        private void handleSettings(MinecraftClient client, JsonObject command) {
            String action = command.get("action").getAsString();
            client.execute(() -> {
                switch (action) {
                    case "set" -> {
                        // Диапазоны зажимает сама настройка: странице тут доверия не больше,
                        // чем любому другому вводу.
                        String path = command.get("path").getAsString();
                        if (TwmSettings.set(path, command.get("value"))) {
                            TwmSettings.save();
                        }
                        if ("ui.language".equals(path)) {
                            refreshLanguage();
                        } else {
                            pushSettings();
                        }
                    }
                    case "bind" -> {
                        TwmKeys.beginCapture(command.get("id").getAsString());
                        pushCaptureAndSettings();
                    }
                    case "unbind" -> {
                        TwmKeys.unbind(command.get("id").getAsString());
                        pushCaptureAndSettings();
                    }
                    case "reset" -> {
                        TwmSettings.reset();
                        pushSettings();
                    }
                    default -> Twm.LOGGER.warn("Неизвестное действие настроек: {}", action);
                }
            });
        }

        /**
         * Аватарка считывается из уже загруженной текстуры скина, а это можно делать только
         * с рендер-потока. Пустой ответ — не ошибка: страница показывает инициал и спросит снова.
         */
        private void handleAvatar(MinecraftClient client, JsonObject command) {
            String rawUuid = command.has("uuid") ? command.get("uuid").getAsString() : "";
            String name = command.has("name") ? command.get("name").getAsString() : "";
            client.execute(() -> {
                UUID uuid = parseUuid(rawUuid);
                Optional<String> uri = SkinAvatars.resolve(uuid, name);
                if (uri.isEmpty()) {
                    return;
                }
                // Ключом отвечаем тем же, чем спросили: страница ищет аватарку и по UUID, и по нику.
                pushAvatar(rawUuid.isEmpty() ? name : rawUuid, uri.get());
            });
        }

        private UUID parseUuid(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
