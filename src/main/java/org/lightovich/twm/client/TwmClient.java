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

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import org.lightovich.twm.Twm;
import org.lightovich.twm.Difficulty;
import org.lightovich.twm.client.cef.CefBridge;
import org.lightovich.twm.client.cef.CefManager;
import org.lightovich.twm.client.cef.CefScreen;
import org.lightovich.twm.client.cef.HudCefManager;
import org.lightovich.twm.client.cef.HudCefRenderer;
import org.lightovich.twm.client.perception.EchoRenderer;
import org.lightovich.twm.client.perception.Echolocation;
import org.lightovich.twm.client.perception.SoundRadar;
import org.lightovich.twm.client.perception.SoundRadarRenderer;
import org.lightovich.twm.net.TwmPayloads;

/** Клиентский инициализатор: состояние команды, камера, восприятие ролей, веб-интерфейс. */
public final class TwmClient implements ClientModInitializer {

    private static final String CATEGORY = "key.categories.twm";

    public static final KeyBinding FREE_LOOK = new KeyBinding(
            "key.twm.free_look", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY);
    public static final KeyBinding PING = new KeyBinding(
            "key.twm.ping", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyBinding THOUGHTS = new KeyBinding(
            "key.twm.thoughts", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);
    public static final KeyBinding LOBBY = new KeyBinding(
            "key.twm.lobby", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F4, CATEGORY);
    public static final KeyBinding GUIDE = new KeyBinding(
            "key.twm.guide", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);

    @Override
    public void onInitializeClient() {
        for (KeyBinding binding : new KeyBinding[] {FREE_LOOK, PING, THOUGHTS, LOBBY, GUIDE}) {
            KeyBindingHelper.registerKeyBinding(binding);
        }
        TwmSettings.load();

        ClientPlayNetworking.registerGlobalReceiver(TwmPayloads.RoleSync.ID, (payload, context) ->
                context.client().execute(() -> {
                    ClientParty.apply(payload.role(), payload.difficulty(),
                            payload.echoCooldownTicks(), payload.members());
                    CameraController.reset();
                    Echolocation.reset();
                    if (CefManager.getBrowser() != null) {
                        CefBridge.pushRole(CefManager.getBrowser());
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(TwmPayloads.BodyState.ID, (payload, context) ->
                context.client().execute(() -> ClientParty.receiveBodyState(
                        payload.x(), payload.y(), payload.z(), payload.onGround(), payload.sneaking(),
                        payload.yaw(), payload.pitch())));

        ClientPlayNetworking.registerGlobalReceiver(TwmPayloads.ThoughtReceive.ID, (payload, context) ->
                context.client().execute(() -> ThoughtLog.add(payload.signal(), payload.sender())));

        ClientPlayNetworking.registerGlobalReceiver(TwmPayloads.LobbyState.ID, (payload, context) ->
                context.client().execute(() -> {
                    LobbySnapshot.accept(payload.json());
                    // Кэшируется переведённый снимок, а не сырой: сервер шлёт ключи, и странице
                    // они уехали бы как есть — «twm.role.head» вместо «Голова». Смена языка
                    // потом чинила картинку до первого же пакета, и дефект выглядел плавающим.
                    CefBridge.cacheState(LobbySnapshot.raw());
                    if (LobbySnapshot.takeRunStart()) {
                        openTutorial(context.client());
                    } else {
                        openMenuOnce(context.client());
                    }
                }));

        // Уведомление — событие: оно обязано дойти и с закрытым меню, поэтому идёт в HUD,
        // а в меню дублируется только если оно сейчас открыто.
        // Итоги забега приходят одним пакетом и сразу занимают экран: забег кончился, и
        // смотреть в мир команде уже незачем — там её вернули туда, откуда она уходила.
        ClientPlayNetworking.registerGlobalReceiver(TwmPayloads.RunSummary.ID, (payload, context) ->
                context.client().execute(() -> {
                    CefBridge.cacheSummary(payload.json());
                    context.client().setScreen(new CefScreen("summary"));
                }));

        ClientPlayNetworking.registerGlobalReceiver(TwmPayloads.Notify.ID, (payload, context) ->
                context.client().execute(() -> {
                    // Сервер прислал ключи: язык знает только клиент, и знает он его здесь.
                    String title = TwmLang.resolve(payload.title());
                    String text = TwmLang.resolve(payload.text());
                    HudCefManager.toast(payload.kind(), title, text);
                    CefBridge.pushToast(payload.kind(), title, text);
                }));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetForNewServer());

        WorldRenderEvents.END.register(EchoRenderer::captureFrame);
        // У ног обычный HUD отменяется целиком, и веб-слой рисует InGameHudMixin.
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            if (!ClientParty.isLegs()) {
                // Кольцо звуков — под веб-слоем: карточка мысли и тоасты обязаны читаться
                // поверх него, а не спорить с дугами за одни и те же пиксели.
                SoundRadarRenderer.render(context, tickCounter.getTickDelta(false));
                HudCefRenderer.render(context);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(TwmClient::onClientTick);
    }

    /**
     * Сброс всего, что мод помнит про сервер.
     *
     * <p>Зовётся и на разрыве связи, и на пакете входа в игру: за прокси переключение
     * бэкенда соединения не рвёт, и без второго повода состояние прежнего сервера уехало бы
     * на следующий — вместе с запертым меню, снять которое там уже некому.
     */
    public static void resetForNewServer() {
        ClientParty.clear();
        LobbySnapshot.clear();
        SkinAvatars.clear();
        CameraController.reset();
        Echolocation.reset();
        SoundRadar.clear();
        ThoughtLog.clear();
        // Браузер намеренно не закрываем: close() у CEF умеет подвиснуть на потоке выхода,
        // а он всё равно переиспользуется при следующем входе.
        HudCefManager.forgetState();
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        // Переключение окна с зажатой клавишей игра запоминает как нажатие, и персонаж
        // продолжает бежать сам. Снимаем нажатия, пока окно неактивно.
        if (!client.isWindowFocused()) {
            KeyBinding.unpressAll();
        }
        VoiceKeys.resolveOnce(client);
        ClientParty.applyPendingBodyPosition();
        CameraController.tick(FREE_LOOK.isPressed());
        Echolocation.tick();
        SoundRadar.tick();
        ThoughtLog.tick();
        HudCefManager.push();

        handleScreenKeys(client);
        handlePing();
        interceptHeadDrop(client);
        closeForbiddenScreens(client);
        openMenuOnce(client);
        enforceMenuLock(client);
    }

    private static void handleScreenKeys(MinecraftClient client) {
        boolean lobby = false;
        while (LOBBY.wasPressed()) {
            lobby = true;
        }
        boolean thoughts = false;
        while (THOUGHTS.wasPressed()) {
            thoughts = true;
        }
        boolean guide = false;
        while (GUIDE.wasPressed()) {
            guide = true;
        }
        if (client.currentScreen != null) {
            return;
        }
        if (lobby) {
            // Снимок запрашивается всегда: список лобби на сервере мог измениться, пока меню
            // было закрыто. Экран выбирается по последнему состоянию — в лобби открывать список
            // бессмысленно, а вне лобби бессмысленно открывать пустую карточку команды.
            ClientPlayNetworking.send(new TwmPayloads.LobbyAction("open", ""));
            client.setScreen(new CefScreen(LobbySnapshot.inLobby() ? "lobby" : "main"));
        } else if (thoughts && ClientParty.isHead()) {
            client.setScreen(new CefScreen("thoughts"));
        } else if (guide) {
            client.setScreen(new CefScreen("guide"));
        }
    }

    /**
     * Меню открывается само при входе на сервер: зайти и сразу увидеть «Найти игру» — это и
     * есть точка входа в режим. Разрешение приходит с сервера ({@code mode.autoOpen}), потому
     * что на сервере с другими режимами всплывающее меню только мешает.
     *
     * <p>Один раз за подключение. Дальше, если меню ещё и заперто, его держит открытым
     * {@link #enforceMenuLock}: это разные вещи — открыть однажды и не дать уйти в мир.
     */
    /**
     * Экран свободен под меню.
     *
     * <p>Кроме пустого экрана сюда входит ванильная загрузка местности: после перехода
     * между серверами сети она висит секундами, и ожидание её конца выглядело как «меню не
     * открывается». Подменять её безопасно — закрывает она себя сама из своего же тика, а
     * тикает только текущий экран, так что заменённая она уже ничего не закроет.
     */
    private static boolean menuScreenFree(MinecraftClient client) {
        return client.currentScreen == null
                || client.currentScreen instanceof DownloadingTerrainScreen;
    }

    private static void openMenuOnce(MinecraftClient client) {
        if (!LobbySnapshot.firstOfSession() || !LobbySnapshot.autoOpen()) {
            return;
        }
        // Забег уже идёт — открывать меню не надо, но попытка израсходована: она была про
        // вход на сервер, а не про «когда-нибудь потом».
        if (LobbySnapshot.phase().equals("playing")) {
            LobbySnapshot.takeFirstOfSession();
            return;
        }
        // Первый снимок приходит, когда игрок ещё смотрит на ванильный экран загрузки
        // местности, и поставленное поверх меню ваниль тут же снимет. Поэтому попытка
        // не тратится: она ждёт мира и свободного экрана, а зовёт её каждый тик.
        if (client.player == null || client.world == null || !menuScreenFree(client)) {
            return;
        }
        // Без Chromium открывать нечего: экран с сообщением о том, что интерфейса нет,
        // сам по себе игроку не нужен, а /twm работает и так.
        if (!CefManager.isAvailable()) {
            return;
        }
        LobbySnapshot.takeFirstOfSession();
        Twm.LOGGER.info("Меню открыто автоматически: сервер разрешил autoOpen");
        ClientPlayNetworking.send(new TwmPayloads.LobbyAction("open", ""));
        client.setScreen(new CefScreen(LobbySnapshot.inLobby() ? "lobby" : "main"));
    }

    /**
     * Пока забег не начат, а сервер держит меню запертым, игрок в мир не выходит: там ему
     * нечего делать, весь режим живёт в лобби. Экран возвращается сам, как только оказался
     * закрыт любым путём — Esc из паузы, чужой экран, конец обучения.
     *
     * <p>Проверка стоит в тике, а не только в {@code close()}, потому что закрыть экран умеет
     * не только мод: чужой мод, ванильный переход, разрыв связи. Одно место, которое чинит
     * состояние, надёжнее пяти, которые его не ломают.
     */
    private static void enforceMenuLock(MinecraftClient client) {
        if (!LobbySnapshot.menuLocked() || !menuScreenFree(client)) {
            return;
        }
        // Меню запирается только тем, у кого оно есть. Если Chromium не встал, запертым
        // оказался бы экран с сообщением о том, что интерфейса нет, — игрок не смог бы ни
        // играть, ни выйти в мир, ни собрать команду. Без браузера остаётся /twm, и путь к
        // нему обязан быть открыт.
        if (!CefManager.isAvailable()) {
            return;
        }
        client.setScreen(new CefScreen(LobbySnapshot.inLobby() ? "lobby" : "main"));
    }

    /**
     * Обучение своей роли в начале каждого забега.
     *
     * <p>Открывает мод, а не страница: меню к этому моменту чаще всего закрыто — забег
     * начинает хост, а остальные ждут его в мире. Пропуск живёт на самом экране, плюс
     * Esc закрывает его как любой другой.
     *
     * <p>Роль приходит отдельным пакетом {@code RoleSync}, который сервер шлёт раньше
     * снимка лобби; но полагаться на порядок нельзя, поэтому страница умеет ждать роль
     * и перерисоваться по её приходу.
     */
    private static void openTutorial(MinecraftClient client) {
        client.setScreen(new CefScreen("tutorial"));
    }

    private static void handlePing() {
        boolean pressed = false;
        while (PING.wasPressed()) {
            pressed = true;
        }
        if (pressed && ClientParty.isLegs() && ClientParty.difficulty() == Difficulty.HARD) {
            Echolocation.ping();
        }
    }

    /** Единственное действие головы с предметами: слепой выброс из выбранного слота рук. */
    private static void interceptHeadDrop(MinecraftClient client) {
        if (!ClientParty.isHead()) {
            return;
        }
        boolean dropped = false;
        while (client.options.dropKey.wasPressed()) {
            dropped = true;
        }
        if (dropped && client.currentScreen == null) {
            ClientPlayNetworking.send(new TwmPayloads.HeadDrop());
        }
    }

    /** Голова и ноги не видят предметную сетку — ни своего инвентаря, ни контейнеров. */
    private static void closeForbiddenScreens(MinecraftClient client) {
        if ((ClientParty.isHead() || ClientParty.isLegs())
                && client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
            client.setScreen(null);
        }
    }
}
