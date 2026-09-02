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

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import org.lightovich.twm.client.LobbySnapshot;
import org.lightovich.twm.client.TwmKeys;
import org.lightovich.twm.client.TwmLang;

/** Полноэкранный экран, показывающий offscreen-буфер браузера как GL-текстуру. */
public class CefScreen extends Screen {

    private final String screenId;
    private MCEFBrowser browser;

    public CefScreen(String screenId) {
        super(Text.empty());
        this.screenId = screenId;
        CefBridge.requestScreen(screenId);
        boolean alreadyOpen = CefManager.isBrowserOpen();
        this.browser = CefManager.openBrowser(CefManager.resolveUrl("twm/ui/menu.html"));
        if (alreadyOpen && this.browser != null) {
            // Страница уже загружена — обработчик onLoadEnd больше не сработает,
            // поэтому нужный экран и актуальную роль отдаём вручную.
            CefBridge.pushRole(this.browser);
            CefBridge.pushScreen(this.browser, screenId);
        }
    }

    public String screenId() {
        return screenId;
    }

    @Override
    protected void init() {
        if (browser != null && CefManager.isBrowserOpen()) {
            browser.resize(client.getWindow().getFramebufferWidth(),
                    client.getWindow().getFramebufferHeight());
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (browser == null || !CefManager.isBrowserOpen()) {
            context.fill(0, 0, width, height, 0xFF11131A);
            context.drawCenteredTextWithShadow(textRenderer, TwmLang.get("twm.screen.no_mcef"),
                    width / 2, height / 2 - 6, 0xFFB4BCD0);
            context.drawCenteredTextWithShadow(textRenderer, TwmLang.get("twm.screen.no_mcef_hint"),
                    width / 2, height / 2 + 8, 0xFF6E7891);
            return;
        }

        browser.sendMouseMove(toFramebufferX(mouseX), toFramebufferY(mouseY));
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, browser.getRenderer().getTextureID());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        // Кадр CEF приходит с premultiplied alpha: обычный режим смешивания гасит цвет дважды.
        HudCefRenderer.CefBlend.premultiplied();

        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        BufferBuilder buffer = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(matrix, 0.0F, 0.0F, 0.0F).texture(0.0F, 0.0F);
        buffer.vertex(matrix, 0.0F, height, 0.0F).texture(0.0F, 1.0F);
        buffer.vertex(matrix, width, height, 0.0F).texture(1.0F, 1.0F);
        buffer.vertex(matrix, width, 0.0F, 0.0F).texture(1.0F, 0.0F);
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Назначение бинда мышью. Левая и правая кнопки в биндах не участвуют намеренно: ими
        // жмут сам интерфейс, и первое же нажатие после «назначить» съело бы захват.
        if (TwmKeys.isCapturing()) {
            if (button >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                TwmKeys.applyMouseCapture(button);
            } else {
                TwmKeys.cancelCapture();
            }
            CefBridge.pushCaptureAndSettings();
            return true;
        }
        if (browser != null && CefManager.isBrowserOpen()) {
            browser.sendMousePress(toFramebufferX((int) mouseX), toFramebufferY((int) mouseY),
                    toCefButton(button));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Отпускание после назначения странице не отдаём: браузер получил бы release без press.
        if (TwmKeys.isCapturing()) {
            return true;
        }
        if (browser != null && CefManager.isBrowserOpen()) {
            browser.sendMouseRelease(toFramebufferX((int) mouseX), toFramebufferY((int) mouseY),
                    toCefButton(button));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (browser != null && CefManager.isBrowserOpen()) {
            browser.sendMouseWheel(toFramebufferX((int) mouseX), toFramebufferY((int) mouseY),
                    vertical * 40.0D, 0);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Пока идёт назначение бинда, экран забирает клавиатуру себе целиком: иначе выбранная
        // клавиша заодно сработала бы как действие страницы, а Esc закрыл бы настройки.
        if (TwmKeys.isCapturing()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                TwmKeys.cancelCapture();
            } else {
                TwmKeys.applyCapture(keyCode, scanCode);
            }
            CefBridge.pushCaptureAndSettings();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        // При scanCode=0 Chromium не может получить X11-код и засыпает stderr предупреждениями.
        if (scanCode != 0 && browser != null && CefManager.isBrowserOpen()) {
            browser.sendKeyPress(keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (scanCode != 0 && browser != null && CefManager.isBrowserOpen()) {
            browser.sendKeyRelease(keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (browser != null && CefManager.isBrowserOpen()) {
            browser.sendKeyTyped(character, modifiers);
        }
        return true;
    }

    @Override
    public void close() {
        // Браузер намеренно остаётся жить: пересоздание даёт мерцание и заново грузит страницу.
        browser = null;
        if (client != null) {
            leave(client, screenId);
        }
    }

    /**
     * Единственный выход из веб-экрана. Через него идут и Esc, и {@code {"cmd":"close"}} со
     * страницы: две разные политики выхода разошлись бы при первой правке.
     *
     * <p>Пока меню заперто (серверный режим, забег не начат), в мир выйти нельзя — там
     * игроку нечего делать, весь режим начинается в лобби. Но заперто именно меню, а не
     * игрок: со справки и круга возврат идёт на главный экран, а с главного открывается
     * ванильная пауза, то есть выход с сервера остаётся доступен всегда.
     */
    public static void leave(MinecraftClient client, String screenId) {
        // Без браузера запирать нечего: см. TwmClient.enforceMenuLock.
        if (!LobbySnapshot.menuLocked() || !CefManager.isAvailable()) {
            client.setScreen(null);
            return;
        }
        String home = LobbySnapshot.inLobby() ? "lobby" : "main";
        if (!home.equals(screenId)) {
            client.setScreen(new CefScreen(home));
            return;
        }
        client.setScreen(new GameMenuScreen(true));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int toFramebufferX(int guiX) {
        return (int) (guiX * client.getWindow().getScaleFactor());
    }

    private int toFramebufferY(int guiY) {
        return (int) (guiY * client.getWindow().getScaleFactor());
    }

    private static int toCefButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1;
            default -> 0;
        };
    }
}
