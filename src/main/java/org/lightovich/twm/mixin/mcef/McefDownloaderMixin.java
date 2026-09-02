/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.mixin.mcef;

import java.io.IOException;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFDownloader;
import com.cinemamod.mcef.internal.MCEFDownloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.lightovich.twm.Twm;
import org.lightovich.twm.client.cef.install.CefFetcher;
import org.lightovich.twm.client.cef.install.CefInstall;

/**
 * Замена транспорта у загрузчика MCEF.
 *
 * <p>Сам порядок действий MCEF не трогаем — экран загрузки, распаковка и признак готовности
 * остаются его. Подменяются три места, каждое из которых у него сломано; разбор — в
 * {@link CefInstall}.
 *
 * <p>Миксин ставится только когда MCEF действительно загружен: список отдаёт
 * {@link McefMixinPlugin}, иначе конфигурация миксинов упала бы на ненайденном классе.
 */
@Mixin(value = MCEFDownloader.class, remap = false)
public abstract class McefDownloaderMixin {

    /**
     * Адрес, с которым загрузчик создан. Берём его, а не настройку: настройку могли поменять
     * после создания загрузчика, и тогда мы качали бы не оттуда, откуда качает MCEF. Раньше
     * тут стояло {@code MCEF.getSettings().getDownloadMirror()} — на пустых настройках это
     * давало {@code null}, очередь адресов оказывалась пустой, и загрузка падала с «нет ни
     * одного адреса» ещё до первого байта.
     */
    @Shadow
    public abstract String getHost();

    /**
     * Проверенная установка снимает нужду в сети целиком.
     *
     * <p>В оригинале контрольная сумма качается на каждом запуске, и неудача помечает MCEF
     * как {@code failed} — то есть отсутствие интернета отключает интерфейс мода при полностью
     * установленных бинарях. Совпадение сборки проверяем по метке, а не на слово.
     */
    @Inject(method = "downloadJavaCefChecksum", at = @At("HEAD"), cancellable = true)
    private void twm$skipWhenVerified(CallbackInfoReturnable<Boolean> info) {
        if (CefInstall.isVerified(twm$commit())) {
            Twm.LOGGER.info("Установка Chromium проверена ранее — сеть не нужна");
            info.setReturnValue(true);
        }
    }

    /**
     * Совпавшая сумма при целой папке — законный повод поставить метку.
     *
     * <p>Без этого метку получала бы только свежескачанная установка: у той, что уже лежала
     * до появления мода, распаковки больше не будет никогда, и запуск без сети остался бы
     * для неё невозможен.
     */
    @Inject(method = "downloadJavaCefChecksum", at = @At("RETURN"))
    private void twm$rememberMatch(CallbackInfoReturnable<Boolean> info) {
        if (!info.getReturnValueZ() || CefInstall.isVerified(twm$commit())) {
            return;
        }
        if (CefInstall.looksComplete(CefInstall.platformDir())) {
            CefInstall.markVerified(CefInstall.expectedArchiveSha(), twm$commit());
        }
    }

    /** Своя загрузка: докачка, повторы, зеркала, сверка суммы. */
    @Inject(method = "downloadJavaCefBuild", at = @At("HEAD"), cancellable = true)
    private void twm$download(CallbackInfo info) throws IOException {
        String commit = twm$commit();
        if (commit == null || commit.isBlank()) {
            // Адрес архива без хэша сборки не собрать. Уступаем оригиналу: он возьмёт хэш
            // тем же способом и упадёт там же, но с понятной ему диагностикой.
            return;
        }
        // Диагностика на один экран: следующий отчёт «не качается» должен читаться из лога,
        // а не воспроизводиться на чужой машине.
        Twm.LOGGER.info("Загрузка Chromium: платформа {}, сборка {}, адрес {}, папка {}",
                CefInstall.platform(), commit, getHost(), CefInstall.librariesDir().toAbsolutePath());
        CefFetcher.fetch(CefInstall.platform(), commit, getHost(), new CefFetcher.Report() {
            @Override
            public void task(String text) {
                MCEFDownloadListener.INSTANCE.setTask(text);
            }

            @Override
            public void progress(float fraction) {
                MCEFDownloadListener.INSTANCE.setProgress(fraction);
            }
        });
        info.cancel();
    }

    /**
     * Остатки прошлой распаковки убираются до новой.
     *
     * <p>Распаковка идёт поверх существующей папки, поэтому файл, уцелевший от оборванной
     * попытки, пережил бы и переустановку — и продолжал бы ронять Chromium.
     */
    @Inject(method = "extractJavaCefBuild", at = @At("HEAD"))
    private void twm$wipeBeforeExtract(boolean deleteArchive, CallbackInfo info) {
        CefInstall.deleteRecursively(CefInstall.platformDir());
    }

    /**
     * Итог распаковки проверяется сразу.
     *
     * <p>Если она оборвалась, метку версии надо снести здесь же: MCEF её уже записал, и без
     * этого следующий запуск увидит совпавшую сумму и решит, что качать нечего. Именно так
     * поломка и переживала перезапуски у игроков.
     */
    @Inject(method = "extractJavaCefBuild", at = @At("RETURN"))
    private void twm$verifyAfterExtract(boolean deleteArchive, CallbackInfo info) {
        if (CefInstall.looksComplete(CefInstall.platformDir())) {
            CefInstall.markVerified(CefInstall.expectedArchiveSha(), twm$commit());
            return;
        }
        Twm.LOGGER.error("Распаковка Chromium не завершилась — установка сброшена, "
                + "следующий запуск скачает её заново");
        CefInstall.deleteRecursively(CefInstall.platformDir());
        CefInstall.deleteQuietly(CefInstall.versionMark());
        CefInstall.deleteQuietly(CefInstall.verifiedMark());
    }

    private static String twm$commit() {
        try {
            return MCEF.getJavaCefCommit();
        } catch (IOException e) {
            Twm.LOGGER.warn("Хэш сборки java-cef не прочитан", e);
            return null;
        }
    }
}
