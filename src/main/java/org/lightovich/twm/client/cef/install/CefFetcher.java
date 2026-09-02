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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.GZIPInputStream;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Загрузка архива Chromium вместо загрузчика MCEF.
 *
 * <p>Отличий от оригинала четыре, и каждое отвечает конкретной жалобе:
 *
 * <ul>
 *   <li><b>Готовый архив рядом.</b> Прежде сети смотрим в {@code twm-cef/} — если файл уже
 *       лежит и сходится по сумме, сеть не нужна вовсе. Это единственный способ раздать
 *       125 МБ людям, у которых CDN не открывается: один файл в папку.</li>
 *   <li><b>Докачка.</b> Кусок пишется в {@code .part} и продолжается запросом {@code Range},
 *       а не начинается заново. Обрыв на 90% стоит остатка, а не всей закачки.</li>
 *   <li><b>Таймауты и повторы.</b> Подвисшее соединение рвётся по чтению, а не висит вечно;
 *       адреса перебираются по кругу с паузой.</li>
 *   <li><b>Сверка.</b> Скачанное сверяется с {@code .sha256}, а если суммы взять негде —
 *       хотя бы прогоняется через gzip целиком. Битый архив не доходит до распаковки, а не
 *       распаковывается наполовину и запоминается как удачный.</li>
 * </ul>
 */
public final class CefFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("ThreeWiseMonkeys");

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** Круг по всем адресам считается одной попыткой: смысл повтора — переждать, а не долбить. */
    private static final int ROUNDS = 3;
    private static final long RETRY_PAUSE_MS = 3_000;

    /** Обратная связь для экрана загрузки MCEF. Через интерфейс, чтобы не тащить сюда его классы. */
    public interface Report {
        void task(String text);

        /** Доля от нуля до единицы — в той же шкале, что у экрана загрузки MCEF. */
        void progress(float fraction);
    }

    private CefFetcher() {
    }

    /**
     * Кладёт в папку библиотек проверенный {@code <платформа>.tar.gz}.
     *
     * @param commit хэш сборки java-cef, он же часть пути на всех зеркалах
     * @param officialHost адрес из настроек MCEF; идёт последним в очереди
     */
    public static void fetch(String platform, String commit, String officialHost, Report report)
            throws IOException {
        Path libraries = CefInstall.librariesDir();
        Files.createDirectories(libraries);
        Path target = libraries.resolve(platform + ".tar.gz");
        Path part = libraries.resolve(platform + ".tar.gz.part");
        List<String> mirrors = CefMirrors.chain(officialHost);

        String expected = resolveExpectedSha(platform, commit, mirrors);

        // Уже скачанный архив — тоже результат: MCEF мог упасть между загрузкой и распаковкой.
        if (accept(target, expected)) {
            LOGGER.info("Архив Chromium уже скачан и сверен: {}", target);
            report.progress(1.0F);
            return;
        }
        Path local = findLocalCopy(platform);
        if (local != null && accept(local, expected)) {
            LOGGER.info("Архив Chromium взят из локальной копии: {}", local);
            report.task("Chromium: локальная копия");
            Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
            report.progress(1.0F);
            return;
        }
        if (mirrors.isEmpty()) {
            throw new IOException("Нет ни одного адреса для загрузки Chromium");
        }

        IOException last = null;
        for (int round = 1; round <= ROUNDS; round++) {
            for (String host : mirrors) {
                String url = archiveUrl(host, commit, platform);
                try {
                    report.task(taskText(round, host));
                    download(url, part, report);
                    if (accept(part, expected)) {
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Архив Chromium загружен и сверен: {}", url);
                        return;
                    }
                    // Сумма не сошлась — продолжать этот файл нельзя, иначе докачка вечно
                    // будет достраивать заведомо испорченное начало.
                    LOGGER.warn("Архив с {} не прошёл сверку, качаю заново", host);
                    CefInstall.deleteQuietly(part);
                    last = new IOException("Архив не прошёл сверку: " + url);
                } catch (IOException e) {
                    LOGGER.warn("Загрузка с {} не удалась: {}", host, e.toString());
                    last = e;
                }
            }
            if (round < ROUNDS) {
                pause();
            }
        }
        throw last == null ? new IOException("Chromium не загружен") : last;
    }

    /**
     * Ожидаемая сумма архива.
     *
     * <p>Порядок не случаен. Первым идёт эталон, вшитый в сборку ({@link CefHashes}): он не
     * зависит от сети вообще, а значит подменившему зеркало нечего подменить. Всё остальное —
     * запасные пути на случай, когда MCEF обновится и вшитых сумм для его коммита нет.
     *
     * <p>Отсутствие суммы не повод останавливаться, но повод сказать об этом вслух: архив
     * тогда проверяется только целостностью, то есть «скачался целиком», а не «тот самый».
     */
    private static String resolveExpectedSha(String platform, String commit, List<String> mirrors) {
        String pinned = CefHashes.of(platform, commit);
        if (pinned != null) {
            return pinned;
        }
        LOGGER.warn("Сборка java-cef {} не совпадает с вшитой {} — эталон придётся брать из сети",
                commit, CefHashes.PINNED_COMMIT);
        String recorded = CefInstall.expectedArchiveSha();
        if (recorded != null && recorded.length() == 64) {
            return recorded;
        }
        for (String host : mirrors) {
            try {
                String sha = parseSha(readText(archiveUrl(host, commit, platform) + ".sha256"));
                if (sha != null) {
                    return sha;
                }
            } catch (IOException e) {
                LOGGER.warn("Сумма с {} не получена: {}", host, e.toString());
            }
        }
        LOGGER.warn("Контрольная сумма Chromium недоступна — проверю архив только целостностью");
        return null;
    }

    /** Разбор файла сумм. Оба его вида и почему это важно — в {@link CefInstall#extractSha}. */
    private static String parseSha(String body) {
        return CefInstall.extractSha(body);
    }

    private static String archiveUrl(String host, String commit, String platform) {
        return host + "/java-cef-builds/" + commit + "/" + platform + ".tar.gz";
    }

    private static String taskText(int round, String host) {
        String where = host.replaceFirst("^https?://", "");
        return round == 1
                ? "Chromium: " + where
                : "Chromium: " + where + " (попытка " + round + ")";
    }

    /**
     * Куда положить архив, чтобы игра его не качала.
     *
     * <p>Две папки, обе очевидные для человека, которому файл прислали в мессенджере:
     * рядом с игрой и рядом с настройками мода.
     */
    private static Path findLocalCopy(String platform) {
        String name = platform + ".tar.gz";
        Path[] candidates = {
            FabricLoader.getInstance().getGameDir().resolve("twm-cef").resolve(name),
            FabricLoader.getInstance().getConfigDir().resolve("twm-cef").resolve(name)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Файл годен: сумма сошлась, а если суммы нет — архив хотя бы распаковывается целиком. */
    private static boolean accept(Path file, String expected) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (expected != null) {
                return CefInstall.sha256(file).equalsIgnoreCase(expected);
            }
            return isReadableGzip(file);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Полный прогон через gzip. Оборванная закачка ловится здесь исключением: у gzip в конце
     * лежат длина и CRC, и до них поток просто не доходит.
     */
    private static boolean isReadableGzip(Path file) {
        byte[] buffer = new byte[1 << 16];
        try (InputStream stream = new GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
            while (stream.read(buffer) > 0) {
                // Читаем ради проверки: gzip сверяет CRC и длину сам, на закрытии потока.
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Архив Chromium не читается как gzip: {}", e.toString());
            return false;
        }
    }

    /**
     * Загрузка с докачкой.
     *
     * <p>Сервер вправе не поддержать {@code Range} и ответить 200 вместо 206 — тогда
     * начинаем файл заново, иначе к недокачанному куску приклеилось бы второе начало.
     */
    private static void download(String url, Path part, Report report) throws IOException {
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        HttpURLConnection connection = open(url);
        if (have > 0) {
            connection.setRequestProperty("Range", "bytes=" + have + "-");
        }
        connection.connect();
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " на " + url);
        }
        boolean resumed = code == HttpURLConnection.HTTP_PARTIAL;
        if (!resumed) {
            have = 0;
        }
        long total = connection.getContentLengthLong();
        long full = total > 0 ? have + total : -1;

        try (InputStream input = connection.getInputStream();
             OutputStream output = resumed
                     ? Files.newOutputStream(part, StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                     : Files.newOutputStream(part, StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[1 << 16];
            long done = have;
            long lastReported = -1;
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                done += read;
                if (full > 0) {
                    // Обновляем не чаще, чем меняется целый процент: экран загрузки читает
                    // это поле каждый кадр, и дёргать его на каждые 64 КБ незачем.
                    long percent = done * 100 / full;
                    if (percent != lastReported) {
                        lastReported = percent;
                        report.progress((float) done / full);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        // Часть CDN и корпоративных прокси отдают 403 клиенту без внятного User-Agent.
        connection.setRequestProperty("User-Agent", "ThreeWiseMonkeys/CEF-installer");
        return connection;
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
