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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Установка бинарей Chromium (MCEF) — состояние на диске и его проверка.
 *
 * <p>Класс сознательно не ссылается ни на классы игры, ни на классы MCEF: он работает из
 * точки входа {@code preLaunch}, то есть раньше, чем загружены и те, и другие.
 *
 * <h2>Зачем это вообще есть</h2>
 *
 * MCEF качает ~125 МБ архива и распаковывает из него ~330 МБ. Его собственный загрузчик
 * (разобран по байткоду 2.1.6) имеет три дефекта, из-за которых установка у части игроков
 * ломается наглухо:
 *
 * <ol>
 *   <li><b>Скачанный архив не сверяется с контрольной суммой.</b> Файл {@code .sha256}
 *       используется только как метка версии: качается свежий, сравнивается с лежащим рядом
 *       и, если совпал, загрузка пропускается. Сам {@code .tar.gz} не хэшируется никогда.
 *       Оборвавшаяся закачка распаковывается наполовину, метка при этом записывается — и
 *       следующий запуск считает установку готовой. Чинится только ручным удалением папки,
 *       чего игрок не знает.</li>
 *   <li><b>Загрузка без таймаутов, повторов и докачки.</b> {@code URL.openStream()} в один
 *       поток на 125 МБ: подвисшее соединение висит вечно, оборванное теряет всё скачанное.</li>
 *   <li><b>Готовая установка не спасает от недоступной сети.</b> Контрольная сумма качается
 *       на каждом запуске, и её неудача помечает MCEF как {@code failed} целиком — даже когда
 *       все 330 МБ давно лежат на диске.</li>
 * </ol>
 *
 * <p>Этот класс закрывает первый и третий пункты, {@link CefFetcher} — второй.
 *
 * <h2>Метка проверенной установки</h2>
 *
 * Рядом с папкой платформы кладётся {@code <платформа>.twm-ok} с суммой архива, из которого
 * она распакована. Метку пишем только мы и только после сверки — её наличие означает
 * «установка проверена нами», и тогда сеть на запуске не нужна совсем.
 *
 * <p>Чужая установка, сделанная самим MCEF до появления мода, метки не имеет. Сносить её
 * из-за этого нельзя — это 330 МБ на ровном месте. Поэтому для неё есть эвристика
 * {@link #looksComplete}: набор обязательных файлов, отсутствие пустых файлов и разумный
 * общий объём. Прошла — усыновляем и ставим метку, не прошла — сносим вместе с меткой
 * версии, и MCEF качает заново.
 */
public final class CefInstall {

    /** Свой логгер, а не общий из {@code Twm}: preLaunch не имеет права трогать классы игры. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ThreeWiseMonkeys");

    /**
     * Файлы, без которых распакованная установка бесполезна. Список короткий намеренно:
     * он ловит оборванную распаковку, а не заменяет собой полную опись 70 файлов, которая
     * протухнет на первом же обновлении MCEF.
     *
     * <p>Для macOS списка нет: там всё лежит внутри бандла {@code jcef_app.app}, структура
     * которого от версии к версии менялась. Хватит общих проверок по объёму и числу файлов.
     */
    private static final List<String> LINUX_ESSENTIALS = List.of(
            "libcef.so", "libjcef.so", "jcef_helper", "icudtl.dat", "resources.pak",
            "chrome_100_percent.pak", "snapshot_blob.bin", "v8_context_snapshot.bin", "locales");
    private static final List<String> WINDOWS_ESSENTIALS = List.of(
            "libcef.dll", "jcef.dll", "jcef_helper.exe", "icudtl.dat", "resources.pak",
            "chrome_100_percent.pak", "snapshot_blob.bin", "v8_context_snapshot.bin", "locales");

    /** Распакованная сборка любой платформы — сотни мегабайт; всё, что меньше, — обломок. */
    private static final long MIN_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final int MIN_FILE_COUNT = 20;

    private CefInstall() {
    }

    /**
     * Папка библиотек ровно та же, что вычисляет {@code CefDownloadMixin} самого MCEF:
     * {@code ../build/mcef-libraries} в среде разработки, иначе {@code mods/mcef-libraries}
     * относительно рабочей папки. Повторяем его логику, а не свойство {@code
     * mcef.libraries.path}: в preLaunch свойство ещё не выставлено.
     */
    public static Path librariesDir() {
        // Как только MCEF выставил свойство, оно и есть правда: там он ищет архив, туда
        // распаковывает и оттуда грузит нативные библиотеки. Повторять его вычисление, когда
        // готовый ответ уже лежит рядом, значит завести второе место правды о пути к 330 МБ —
        // и качать в одну папку, а распаковывать из другой.
        String declared = System.getProperty("mcef.libraries.path");
        if (declared != null && !declared.isBlank()) {
            return Path.of(declared);
        }
        // Свойства ещё нет (мы в preLaunch) — повторяем его вычисление один в один по
        // CefDownloadMixin самого MCEF.
        Path devBuild = Path.of("..", "build");
        if (Files.isDirectory(devBuild)) {
            return devBuild.resolve("mcef-libraries");
        }
        return Path.of("mods", "mcef-libraries");
    }

    /** Имя платформы в том же виде, что у {@code MCEFPlatform.getNormalizedName()}. */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String architecture = arch.equals("aarch64") ? "arm64" : "amd64";
        if (os.contains("windows")) {
            return "windows_" + architecture;
        }
        if (os.contains("mac")) {
            return "macos_" + architecture;
        }
        return "linux_" + architecture;
    }

    public static Path platformDir() {
        return librariesDir().resolve(platform());
    }

    /** Метка версии, которую ведёт сам MCEF: пока она совпадает, он не качает архив. */
    public static Path versionMark() {
        return librariesDir().resolve(platform() + ".tar.gz.sha256");
    }

    /** Наша метка: установка распакована из архива, сумма которого сверена. */
    public static Path verifiedMark() {
        return librariesDir().resolve(platform() + ".twm-ok");
    }

    /**
     * Установка проверена нами и собрана из той же сборки java-cef, которую ждёт MCEF.
     *
     * <p>Сборка сверяется обязательно: обновление MCEF меняет хэш коммита, и старые бинари
     * с новым загрузчиком не работают. Метка без коммита — это усыновлённая чужая установка:
     * она цела, но чья версия в ней лежит, мы не знаем, и на слово ей не верим.
     */
    public static boolean isVerified(String commit) {
        if (commit == null || commit.isBlank() || !Files.isDirectory(platformDir())) {
            return false;
        }
        return commit.equalsIgnoreCase(readMarkField("commit"));
    }

    /** {@code commit} может быть {@code null} — так помечается усыновлённая чужая установка. */
    public static void markVerified(String sha, String commit) {
        try {
            Files.writeString(verifiedMark(),
                    "platform=" + platform() + "\n"
                            + "sha256=" + (sha == null ? "" : sha) + "\n"
                            + "commit=" + (commit == null ? "" : commit) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Метка — оптимизация, а не условие работы: без неё просто вернётся сетевая проверка.
            LOGGER.warn("Метка проверенной установки CEF не записана", e);
        }
    }

    /** Ожидаемая сумма архива из метки версии MCEF; {@code null}, если метки ещё нет. */
    public static String expectedArchiveSha() {
        try {
            Path file = versionMark();
            return Files.isRegularFile(file)
                    ? extractSha(Files.readString(file, StandardCharsets.UTF_8))
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Сумма из файла контрольных сумм. Ищется первое слово из 64 шестнадцатеричных знаков,
     * где бы оно ни стояло, — и это не перестраховка, а исправление настоящей поломки.
     *
     * <p>На Linux и macOS файл выглядит как вывод {@code sha256sum}: «сумма пробел имя».
     * На Windows тот же файл на CDN отдаётся таблицей {@code Get-FileHash} из PowerShell —
     * с пустой строкой, заголовком {@code Algorithm/Hash/Path} и чертой под ним. Прежний
     * разбор брал первое слово файла и получал на Windows строку {@code Algorithm}.
     *
     * <p>Дальше эта строка работала как эталон: сумма скачанного архива с ней, разумеется,
     * не сходилась никогда. Мод считал архив битым, качал заново — три круга по 128 МБ, —
     * и в конце помечал MCEF как {@code failed}. То есть на Windows установка Chromium была
     * не «ненадёжной», а невозможной, и лечилась только подкладыванием готовых библиотек
     * руками. Ради этого же случая суммы теперь ещё и вшиты в сборку ({@code CefHashes}).
     */
    public static String extractSha(String body) {
        if (body == null) {
            return null;
        }
        for (String token : body.split("[\\s]+")) {
            if (token.length() == 64 && token.chars().allMatch(
                    c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return token.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * Приведение диска в честное состояние перед тем, как MCEF начнёт свою проверку.
     *
     * <p>Вызывается из preLaunch. Ничего не качает и не распаковывает — только решает,
     * можно ли доверять тому, что уже лежит.
     */
    public static void audit() {
        Path dir = platformDir();
        if (!Files.isDirectory(dir)) {
            // Устанавливать нечего, но обломки предыдущей попытки убрать стоит: недокачанный
            // архив MCEF не удаляет и качает поверх с нуля, занимая место дважды.
            deleteQuietly(librariesDir().resolve(platform() + ".tar.gz"));
            deleteQuietly(librariesDir().resolve(platform() + ".tar.gz.part"));
            deleteQuietly(librariesDir().resolve(platform() + ".tar.gz.sha256.temp"));
            return;
        }
        if (Files.isRegularFile(verifiedMark())) {
            return;
        }
        if (looksComplete(dir)) {
            // Установка чужая, но целая — усыновляем без коммита: проверять её на совпадение
            // версии нечем, а сносить 330 МБ из-за отсутствия нашей метки было бы разорением.
            markVerified(expectedArchiveSha(), null);
            LOGGER.info("Установка CEF признана целой и принята без перезакачки: {}", dir);
            return;
        }
        LOGGER.warn("Установка CEF повреждена — сношу, чтобы MCEF скачал её заново: {}", dir);
        deleteRecursively(dir);
        // Метку версии обязательно снести вместе с папкой. Без этого MCEF увидит совпавшую
        // сумму и решит, что качать нечего — ровно так поломка и переживала перезапуски.
        deleteQuietly(versionMark());
        deleteQuietly(verifiedMark());
        deleteQuietly(librariesDir().resolve(platform() + ".tar.gz"));
        deleteQuietly(librariesDir().resolve(platform() + ".tar.gz.part"));
    }

    /**
     * Эвристика целости для установки, которую делали не мы.
     *
     * <p>Оборванная распаковка — это отсутствующие файлы (архив кончился на середине) или
     * файл нулевой длины (кончился ровно на нём). Обе формы ловятся здесь.
     */
    public static boolean looksComplete(Path dir) {
        for (String name : essentials()) {
            Path entry = dir.resolve(name);
            if (!Files.exists(entry)) {
                LOGGER.warn("В установке CEF нет обязательного {}", name);
                return false;
            }
        }
        long bytes = 0;
        int files = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path entry : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                long size = Files.size(entry);
                if (size == 0) {
                    LOGGER.warn("В установке CEF пустой файл: {}", entry);
                    return false;
                }
                bytes += size;
                files++;
            }
        } catch (IOException e) {
            LOGGER.warn("Установку CEF не удалось обойти", e);
            return false;
        }
        if (files < MIN_FILE_COUNT || bytes < MIN_TOTAL_BYTES) {
            LOGGER.warn("Установка CEF слишком мала: {} файлов, {} МБ", files, bytes / 1048576);
            return false;
        }
        return true;
    }

    private static List<String> essentials() {
        String platform = platform();
        if (platform.startsWith("windows")) {
            return WINDOWS_ESSENTIALS;
        }
        if (platform.startsWith("linux")) {
            return LINUX_ESSENTIALS;
        }
        return List.of();
    }

    /** Sha-256 файла в том же виде, в каком его пишет {@code sha256sum}: нижний регистр hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 недоступен", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream stream = Files.newInputStream(file)) {
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    /** Файлы сумм имеют вид {@code <hex>  <имя>} — нужен только первый столбец. */
    private static String readFirstToken(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            int space = text.indexOf(' ');
            String token = space > 0 ? text.substring(0, space) : text;
            return token.isBlank() ? null : token;
        } catch (IOException e) {
            return null;
        }
    }

    /** Наша метка — простые строки {@code ключ=значение}; чего нет, того нет. */
    private static String readMarkField(String key) {
        try {
            Path file = verifiedMark();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator > 0 && line.substring(0, separator).trim().equals(key)) {
                    String value = line.substring(separator + 1).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    public static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Не удалось удалить {}", file, e);
        }
    }

    public static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            LOGGER.warn("Не удалось снести {}", root, e);
        }
    }
}
