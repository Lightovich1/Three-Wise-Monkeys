/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client.cef.install;

import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Экран первого запуска: согласие на скачивание и установку бинарей Chromium.
 *
 * <p>Ванильный экран, а не страница в Chromium, по очевидной причине: спрашиваем мы как раз
 * о том, ставить ли Chromium. Отсюда же и {@code Text.translatable} вместо
 * {@link org.lightovich.twm.client.TwmLang} — тот переводит строки, уезжающие в браузер,
 * а здесь работает обычный переводчик игры, и добавленный языковой файл подхватится сам.
 *
 * <p>Выхода два и оба явные: Esc не закрывает, кнопки «назад» нет. Решение об установке
 * трёхсот мегабайт не должно приниматься случайным нажатием.
 */
public final class CefConsentScreen extends Screen {

    private static final int BACKGROUND = 0xFF11131A;
    private static final int TEXT = 0xFFD7DCE8;
    private static final int DIM = 0xFF6E7891;

    private static final int LINE = 10;
    private static final int PARAGRAPH = 8;
    private static final int TITLE_GAP = 20;
    private static final int BUTTON_STRIP = 44;

    /** Экран, который мы вытеснили: обычно загрузчик MCEF. Возвращается по «Согласиться». */
    private final Screen parent;

    private MultilineText what = MultilineText.EMPTY;
    private MultilineText privacy = MultilineText.EMPTY;
    private MultilineText refuse = MultilineText.EMPTY;
    private MultilineText footer = MultilineText.EMPTY;

    public CefConsentScreen(Screen parent) {
        super(Text.translatable("twm.consent.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int textWidth = Math.min(width - 60, 360);
        what = MultilineText.create(textRenderer, Text.translatable("twm.consent.what"), textWidth);
        privacy = MultilineText.create(textRenderer, Text.translatable("twm.consent.privacy"), textWidth);
        refuse = MultilineText.create(textRenderer, Text.translatable("twm.consent.refuse"), textWidth);
        footer = MultilineText.create(textRenderer, Text.translatable("twm.consent.footer"), textWidth);

        int buttonWidth = Math.min(150, (width - 24) / 2);
        int buttonY = height - BUTTON_STRIP;
        addDrawableChild(ButtonWidget.builder(Text.translatable("twm.consent.accept"), button -> accept())
                .dimensions(width / 2 - buttonWidth - 4, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("twm.consent.exit"), button -> quit())
                .dimensions(width / 2 + 4, buttonY, buttonWidth, 20).build());
    }

    /**
     * Ровная подложка вместо панорамы главного меню: соглашение показывается поверх
     * загрузчика MCEF, и вращающийся фон под текстом про 350 МБ только мешает читать.
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int y = top();
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y, 0xFFFFFFFF);
        y += TITLE_GAP;
        y = paragraph(context, what, y, TEXT);
        y = paragraph(context, privacy, y, TEXT);
        y = paragraph(context, refuse, y, TEXT);
        footer.drawCenterWithShadow(context, width / 2, y, LINE, DIM);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void accept() {
        CefConsent.accept();
        client.setScreen(parent);
    }

    private void quit() {
        // Защёлка отпускается до остановки клиента: поток загрузки MCEF не демон, и,
        // оставленный в ожидании, он удержал бы JVM живой после закрытия окна.
        CefConsent.release();
        client.scheduleStop();
    }

    private int paragraph(DrawContext context, MultilineText text, int y, int color) {
        text.drawCenterWithShadow(context, width / 2, y, LINE, color);
        return y + text.count() * LINE + PARAGRAPH;
    }

    /** Текст ставится по центру свободного места над кнопками, но не выше края экрана. */
    private int top() {
        int lines = what.count() + privacy.count() + refuse.count() + footer.count();
        int block = TITLE_GAP + lines * LINE + PARAGRAPH * 3;
        return Math.max(20, (height - BUTTON_STRIP - block) / 2);
    }
}
