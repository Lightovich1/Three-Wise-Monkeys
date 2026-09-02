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
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Очередь адресов, с которых берутся бинари Chromium.
 *
 * <p>Файл {@code config/twm-cef-mirrors.txt}, по адресу в строке. Официальный адрес MCEF
 * добавляется последним и не требует записи — список пустой означает «как было».
 */
public final class CefMirrors {

    private CefMirrors() {
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("twm-cef-mirrors.txt");
    }

    /**
     * Адреса в порядке попыток: сначала свои, официальный — последним.
     *
     * <p>{@code official} приходит из настроек самого MCEF, а не константой: игрок мог
     * прописать своё зеркало и там, и его выбор обязан работать.
     */
    public static List<String> chain(String official) {
        List<String> chain = new ArrayList<>();
        for (String line : read()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String host = trimmed.endsWith("/")
                    ? trimmed.substring(0, trimmed.length() - 1)
                    : trimmed;
            if (!chain.contains(host)) {
                chain.add(host);
            }
        }
        if (official != null && !official.isBlank() && !chain.contains(official)) {
            chain.add(official);
        }
        return chain;
    }

    private static List<String> read() {
        try {
            Path path = file();
            return Files.isRegularFile(path)
                    ? Files.readAllLines(path, StandardCharsets.UTF_8)
                    : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }
}
