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

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Проверка установки Chromium до того, как MCEF решит, что качать.
 *
 * <p>Точка входа {@code preLaunch} выбрана по той же причине, что и у
 * {@link org.lightovich.twm.voice.VoiceBootstrap}: собственная проверка MCEF живёт в миксине
 * на конструкторе клиента, а {@code preLaunch} выполняется у всех модов раньше любого
 * инициализатора. Только оттуда можно успеть навести порядок первым.
 *
 * <p>Разбор дефектов загрузчика MCEF и смысл проверки — в {@link CefInstall}.
 */
public final class CefInstallBootstrap implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("ThreeWiseMonkeys");

    @Override
    public void onPreLaunch() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        try {
            CefInstall.audit();
        } catch (RuntimeException e) {
            // Проверка обязана быть незаметной: сломать ей запуск игры хуже, чем не проверить.
            LOGGER.warn("Проверка установки CEF не выполнена", e);
        }
        writeMirrorTemplate();
    }

    /**
     * Список зеркал создаётся пустым, но с объяснением.
     *
     * <p>Файл существует ради того случая, ради которого он и понадобился: у части игроков
     * официальный CDN MCEF отдаёт архив рвано или не отдаёт вовсе, и единственное лечение —
     * взять его с другого адреса. Пока файл пуст, ничего не меняется; вписанная строка
     * добавляет адрес в очередь попыток перед официальным.
     */
    private void writeMirrorTemplate() {
        Path file = CefMirrors.file();
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    # Зеркала для бинарей Chromium (MCEF). По одному адресу в строке.
                    # Строки, начинающиеся с #, игнорируются.
                    #
                    # Пробуются сверху вниз, официальный адрес MCEF идёт последним.
                    # От зеркала требуется та же раскладка путей, что у оригинала:
                    #
                    #   <адрес>/java-cef-builds/<commit>/<платформа>.tar.gz
                    #   <адрес>/java-cef-builds/<commit>/<платформа>.tar.gz.sha256
                    #
                    # Платформы: linux_amd64, windows_amd64, macos_amd64, macos_arm64.
                    #
                    # Файл можно вовсе не трогать: пустой список означает «только официальный CDN».
                    #
                    # https://cdn.example.org/mcef
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Список зеркал CEF не создан: {}", file, e);
        }
    }
}
