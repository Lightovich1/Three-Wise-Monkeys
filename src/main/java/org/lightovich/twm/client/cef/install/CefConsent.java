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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import com.cinemamod.mcef.internal.MCEFDownloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import org.lightovich.twm.Twm;

/**
 * Согласие игрока на установку бинарей Chromium.
 *
 * <p>Мод кладёт в {@code mods/mcef-libraries} около 350 МБ чужих файлов, скачанных с
 * стороннего CDN. Спросить об этом один раз — дешевле, чем объясняться потом; поэтому при
 * первом запуске экран {@link CefConsentScreen} перекрывает всё остальное, а загрузка до
 * ответа не делает ни одного сетевого запроса.
 *
 * <p>Ответ хранится файлом {@code config/twm-cef-consent.txt}: пока он на месте, вопроса
 * больше нет. Файл, а не поле в {@code twm.json}, намеренно — настройки читает и пишет
 * страница в Chromium, то есть ровно то, чего на момент вопроса ещё нет.
 *
 * <p><b>Почему это защёлка, а не флаг.</b> Спрашивает клиентский поток, а качает
 * собственный поток MCEF, запущенный из конструктора клиента. Второй обязан дождаться
 * первого — иначе 130 МБ уедут в фон, пока игрок читает вопрос, и согласие станет
 * формальностью. Отпускается защёлка и на «Выйти»: поток MCEF не демон и без этого
 * удержал бы JVM после закрытия окна.
 */
public final class CefConsent {

    /** Ответ получен: метка лежала на диске либо игрок нажал кнопку. */
    private static final CountDownLatch DECIDED = new CountDownLatch(1);

    private static volatile boolean granted;

    static {
        if (Files.isRegularFile(mark())) {
            granted = true;
            DECIDED.countDown();
        }
    }

    private CefConsent() {
    }

    /** Метка данного согласия. Удаление файла возвращает вопрос. */
    public static Path mark() {
        return FabricLoader.getInstance().getConfigDir().resolve("twm-cef-consent.txt");
    }

    /** Ответа ещё нет — экран нужен, сеть ждёт. */
    public static boolean pending() {
        return DECIDED.getCount() > 0;
    }

    /**
     * Показывает экран согласия, когда игра дошла до первого своего экрана.
     *
     * <p>Раньше нельзя: до конца загрузки ресурсов нет ни шрифта, ни переводов, а
     * {@link CefConsentScreen} собирает текст в {@code init()}.
     *
     * <p>Экран, который мы вытеснили, возвращается на место по «Согласиться» — к этому
     * моменту это уже главное меню.
     */
    public static void showWhenPending(MinecraftClient client) {
        if (!pending() || client.getOverlay() != null || client.currentScreen == null
                || client.currentScreen instanceof CefConsentScreen) {
            return;
        }
        holdScreens(true);
        client.setScreen(new CefConsentScreen(client.currentScreen));
    }

    /**
     * Отпускает захват экранов у MCEF на время вопроса.
     *
     * <p><b>Зачем.</b> {@code CefInitMixin} самого MCEF сидит на {@code setScreen} и, пока
     * загрузка не кончилась, подменяет своим загрузчиком <i>любой</i> экран. Проверка на
     * «список ванильных экранов» в нём обманчива: флаг рекурсии он в конце восстанавливает,
     * а не оставляет взведённым, поэтому каждый верхнеуровневый вызов идёт по ветке «первый»
     * и глотает всё подряд. Наш экран согласия уехал бы туда же — а загрузка при этом ждёт
     * ответа на него. Замкнутый круг: MCEF ждёт согласия, согласие ждёт экрана.
     *
     * <p><b>Как разрывается.</b> В том же методе MCEF есть ветка, где он ничего не
     * перехватывает: помеченный {@code failed} он только пишет в лог и пропускает экран
     * дальше. Это единственный рычаг снаружи — приоритет миксина не помогает, флаг рекурсии
     * приватный. Пометка снимается в {@link #accept()} до того, как экран уступит место,
     * так что дальше MCEF работает как обычно и показывает свой прогресс загрузки.
     *
     * <p>Ценой одной строки {@code MCEF failed to initialize!} в логе на каждый экран,
     * показанный за время вопроса. Их там две-три.
     */
    private static void holdScreens(boolean hold) {
        if (!FabricLoader.getInstance().isModLoaded("mcef")) {
            return;
        }
        MCEFDownloadListener.INSTANCE.setFailed(hold);
    }

    /**
     * Ждёт ответа игрока и отвечает, можно ли ставить.
     *
     * <p>Зовётся с потока загрузки MCEF — блокировать его безопасно, кадры игры рисует не он.
     */
    public static boolean awaitDecision() {
        if (DECIDED.getCount() == 0) {
            return granted;
        }
        Twm.LOGGER.info("Загрузка Chromium ждёт согласия игрока");
        try {
            DECIDED.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return granted;
    }

    /** Игрок согласился: метка на диск, захват экранов возвращён MCEF, поток загрузки отпущен. */
    public static void accept() {
        granted = true;
        write();
        // Порядок важен: пометку снимаем раньше, чем экран уступит место, иначе следующий
        // setScreen пройдёт мимо загрузчика MCEF и прогресс качания игрок не увидит вовсе.
        holdScreens(false);
        DECIDED.countDown();
        Twm.LOGGER.info("Согласие на установку Chromium получено");
    }

    /**
     * Ответа не будет: нажато «Выйти» либо клиент закрывается.
     *
     * <p>Метка не пишется — при следующем запуске вопрос задаётся снова.
     */
    public static void release() {
        if (DECIDED.getCount() == 0) {
            return;
        }
        Twm.LOGGER.info("Согласие на установку Chromium не дано — файлы не ставятся");
        DECIDED.countDown();
    }

    private static void write() {
        Path file = mark();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    # Согласие на скачивание и установку бинарей Chromium (MCEF),
                    # данное при первом запуске Three Wise Monkeys.
                    #
                    # Удалите файл, чтобы мод спросил снова.
                    accepted
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Не записалось — переспросим на следующем запуске. Ронять запуск из-за этого
            // нельзя: согласие уже дано, и мешать игроку играть незачем.
            Twm.LOGGER.warn("Метка согласия не записана: {}", file, e);
        }
    }
}
