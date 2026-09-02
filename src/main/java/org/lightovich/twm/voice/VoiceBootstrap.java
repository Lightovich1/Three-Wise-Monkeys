/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Настройка Simple Voice Chat под режим — до того, как он сам её прочитает.
 *
 * <p>Точка входа {@code preLaunch} выбрана не для красоты: голосовой чат читает свои
 * настройки в собственном инициализаторе, а порядок инициализаторов двух независимых модов
 * Fabric не гарантирует. {@code preLaunch} же выполняется у всех модов раньше любого
 * инициализатора — только оттуда можно успеть положить файл первым.
 *
 * <p>Правило записи одно и оно жёсткое: <b>чужой существующий конфиг не переписывается</b>.
 * Отсутствующий файл создаётся с нужными режиму значениями, существующий только проверяется,
 * и о расхождении пишется в лог. Иначе мод каждый запуск затирал бы выбранный игроком
 * микрофон или осознанное решение администратора сервера.
 *
 * <p>Файл при этом пишется неполный — только наши ключи. Голосовой чат дополняет недостающее
 * своими значениями по умолчанию вместе с комментариями, поэтому дублировать весь его конфиг
 * (и стареть вместе с ним) не нужно.
 */
public final class VoiceBootstrap implements PreLaunchEntrypoint {

    /** Свой логгер, а не общий из {@code Twm}: preLaunch не имеет права трогать классы игры. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ThreeWiseMonkeys");

    /**
     * Единственное, что режиму нужно от серверного конфига.
     *
     * <p>{@code force_voice_chat=false} — потому что вход без голосового чата не даёт
     * преимуществ: права ролей проверяет сервер, а без мода игрок просто не говорит и не
     * слышит. Зато включённая проверка выкидывает ванильных ботов, которыми проверяется
     * серверная сторона режима. О незакрытом микрофоне участника команда узнаёт при старте
     * забега — там это и важно, а не на входе в игру.
     */
    private static final Map<String, String> SERVER_DEFAULTS = new LinkedHashMap<>();

    /**
     * Клиентские значения: убрать всё, что стоит между запуском и разговором.
     *
     * <p>{@code muted=true} и мастер по первому запуску — разумные значения для обычного
     * сервера и лишние здесь: режим начинается с разговора втроём, и упереться на входе в
     * экран настройки микрофона означает не начать его вовсе.
     *
     * <p>{@code microphone_activation_type=VOICE} — не вкусовщина. По умолчанию голосовой чат
     * работает по клавише, а свободных рук в этом режиме нет ни у кого: слепой держит мышь и
     * клавиатуру на взаимодействиях, глухой ведёт тело, у немого и так только круг мыслей.
     *
     * <p>{@code config_version} записывается обязательно: без него голосовой чат считает
     * конфиг доисторическим и накатывает миграцию, которая сбрасывает {@code
     * onboarding_finished} обратно — то есть ровно то, что мы здесь и ставим.
     */
    private static final Map<String, String> CLIENT_DEFAULTS = new LinkedHashMap<>();

    static {
        SERVER_DEFAULTS.put("force_voice_chat", "false");

        CLIENT_DEFAULTS.put("config_version", "1");
        CLIENT_DEFAULTS.put("onboarding_finished", "true");
        CLIENT_DEFAULTS.put("muted", "false");
        CLIENT_DEFAULTS.put("microphone_activation_type", "VOICE");
    }

    @Override
    public void onPreLaunch() {
        Path folder = FabricLoader.getInstance().getConfigDir().resolve("voicechat");
        boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        if (client) {
            apply(folder.resolve("voicechat-client.properties"), CLIENT_DEFAULTS);
        } else {
            apply(folder.resolve("voicechat-server.properties"), SERVER_DEFAULTS);
        }
    }

    private static void apply(Path file, Map<String, String> defaults) {
        try {
            if (Files.exists(file)) {
                report(file, defaults);
                return;
            }
            Files.createDirectories(file.getParent());
            StringBuilder text = new StringBuilder(
                    "# Создано Three Wise Monkeys: значения, без которых режим неудобен.\n"
                            + "# Остальное допишет сам Simple Voice Chat. Правки не затираются:\n"
                            + "# существующий файл мод только читает и предупреждает о расхождениях.\n");
            defaults.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            LOGGER.info("Создан конфиг голосового чата {}", file.getFileName());
        } catch (IOException e) {
            LOGGER.warn("Конфиг голосового чата не записан: {}", file, e);
        }
    }

    /**
     * Разбор существующего файла нарочно наивный — {@code ключ=значение} построчно. Полный
     * {@link java.util.Properties} здесь только мешал бы: он молча съедает экранирование и
     * нужен ради значений, которых в этих ключах не бывает.
     */
    private static void report(Path file, Map<String, String> defaults) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int separator = trimmed.indexOf('=');
            if (trimmed.startsWith("#") || separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String expected = defaults.get(key);
            if (expected != null && !expected.equalsIgnoreCase(trimmed.substring(separator + 1).trim())) {
                LOGGER.warn("{}: {} не равен {} — режиму так неудобно, но чужой конфиг не трогаем",
                        file.getFileName(), key, expected);
            }
        }
    }
}
