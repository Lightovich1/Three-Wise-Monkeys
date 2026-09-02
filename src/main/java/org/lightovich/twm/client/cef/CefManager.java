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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import net.minecraft.client.MinecraftClient;

import org.lightovich.twm.Twm;

/**
 * Жизненный цикл браузера интерфейса. Браузер один и переиспользуется между открытиями:
 * пересоздание даёт мерцание и теряет уже загруженную страницу.
 *
 * <p>MCEF — жёсткая зависимость: он вложен в jar мода и объявлен в {@code depends}, так что
 * без него загрузчик просто не запустит мод. Проверять его наличие в рантайме бессмысленно
 * и невозможно: класс {@code MCEFCursorChangeListener} стоит в поле, поэтому загрузка
 * самого CefManager уже требует MCEF. Раньше здесь была такая проверка, и она не могла
 * сработать ни разу — падение приходило из {@code <clinit>} раньше неё.
 */
public final class CefManager {

    /** AWT-курсор несовместим с LWJGL, а null в этом слоте роняет close(). */
    private static final MCEFCursorChangeListener NO_OP_CURSOR = cursorType -> {};

    private static final Map<String, Path> EXTRACTED = new HashMap<>();

    /** Взведён после неудачного создания браузера: вторую попытку каждый кадр делать незачем. */
    private static boolean cefBroken;
    private static MCEFBrowser browser;
    private static boolean browserClosed = true;
    private static int browserWidth;
    private static int browserHeight;

    private CefManager() {
    }

    /**
     * Страницу нельзя отдать браузеру прямо из jar: в IO-потоке CEF схема мода недоступна.
     * Файл распаковывается во временный и отдаётся как file://.
     */
    public static String resolveUrl(String resourcePath) {
        Path cached = EXTRACTED.get(resourcePath);
        if (cached != null) {
            return cached.toUri().toString();
        }
        try (InputStream stream = CefManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                Twm.LOGGER.error("Ресурс интерфейса не найден: {}", resourcePath);
                return "about:blank";
            }
            String name = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            Path file = Files.createTempFile("twm-", "-" + name);
            Files.copy(stream, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            EXTRACTED.put(resourcePath, file);
            return file.toUri().toString();
        } catch (IOException e) {
            Twm.LOGGER.error("Не удалось распаковать {}", resourcePath, e);
            return "about:blank";
        }
    }

    /** MCEF инициализируется асинхронно, поэтому проверять надо при каждом открытии. */
    public static boolean isAvailable() {
        return !cefBroken && MCEF.isInitialized();
    }

    public static MCEFBrowser openBrowser(String url) {
        if (!isAvailable()) {
            return null;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getFramebufferWidth();
        int height = client.getWindow().getFramebufferHeight();

        if (browser != null && !browserClosed) {
            if (browserWidth != width || browserHeight != height) {
                browser.resize(width, height);
                browserWidth = width;
                browserHeight = height;
            }
            return browser;
        }

        CefBridge.register();
        try {
            browser = MCEF.createBrowser(url, true);
            browser.setCursorChangeListener(NO_OP_CURSOR);
            browser.resize(width, height);
            browserWidth = width;
            browserHeight = height;
            browserClosed = false;
            Twm.LOGGER.info("Браузер интерфейса создан: {}x{}", width, height);
        } catch (Exception e) {
            Twm.LOGGER.error("Не удалось создать браузер интерфейса", e);
            cefBroken = true;
            browser = null;
            browserClosed = true;
        }
        return browser;
    }

    /** Переводит уже открытый браузер на другой экран без пересоздания. */
    public static void showScreen(String screen) {
        MCEFBrowser current = getBrowser();
        if (current != null) {
            CefBridge.pushScreen(current, screen);
        }
    }

    public static void closeBrowser() {
        if (browser != null && !browserClosed) {
            browser.close();
            browserClosed = true;
        }
        browser = null;
    }

    public static boolean isBrowserOpen() {
        return browser != null && !browserClosed;
    }

    public static MCEFBrowser getBrowser() {
        return browser != null && !browserClosed ? browser : null;
    }
}
