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

import com.cinemamod.mcef.MCEF;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import org.lightovich.twm.Twm;

/**
 * Единый обработчик загрузки страниц на общий CefClient.
 *
 * <p>{@code addLoadHandler} — сеттер с одним слотом, а не список: второй регистратор молча
 * затирает первый. Поэтому меню и HUD не вешают свои обработчики порознь, а различаются здесь
 * по ссылке на браузер.
 */
public final class CefLoadDispatcher {

    private static boolean registered;

    private CefLoadDispatcher() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        MCEF.getClient().addLoadHandler(new Handler());
        registered = true;
    }

    private static final class Handler extends CefLoadHandlerAdapter {
        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            if (frame == null || !frame.isMain()) {
                return;
            }
            if (browser == HudCefManager.getBrowser()) {
                HudCefManager.onPageLoaded();
                return;
            }
            if (browser == CefManager.getBrowser()) {
                Twm.LOGGER.debug("Страница меню загружена");
                CefBridge.onMenuLoaded();
            }
        }
    }
}
