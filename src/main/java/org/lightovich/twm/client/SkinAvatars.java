/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

import org.lightovich.twm.Twm;

/**
 * Головы скинов для веб-интерфейса — как base64-PNG, без обращений в сеть.
 *
 * <p>Скин уже лежит в видеопамяти: игра качает его сама для отрисовки игрока. Поэтому текстура
 * читается обратно из GL, а не выкачивается второй раз с сервера сессий — иначе интерфейс
 * зависел бы от доступности Mojang и жил бы своей очередью загрузок.
 *
 * <p>Кэш ключуется идентификатором текстуры, а не только UUID: пока настоящий скин не пришёл,
 * игра отдаёт стандартный, и запомнить его навсегда значило бы показывать Стива живому игроку.
 */
public final class SkinAvatars {

    /** Голова в скине — квадрат 8×8 от (8,8), слой шляпы — такой же от (40,8). */
    private static final int HEAD_X = 8;
    private static final int HEAD_Y = 8;
    private static final int HAT_X = 40;
    private static final int HAT_Y = 8;
    private static final int PART = 8;

    /** Итоговый размер: 8×8 в интерфейсе выглядит как марка, увеличение идёт без сглаживания. */
    private static final int OUTPUT = 64;

    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private SkinAvatars() {
    }

    public static void clear() {
        CACHE.clear();
    }

    /**
     * Готовая аватарка или пусто, если скин ещё не загрузился. Вызывать только с рендер-потока:
     * чтение текстуры идёт через GL. Пустой ответ — не ошибка, страница переживает отсутствие
     * аватарки и получит её следующим запросом.
     */
    public static Optional<String> resolve(UUID uuid, String name) {
        if (!RenderSystem.isOnRenderThread()) {
            return Optional.empty();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerListEntry entry = lookup(client, uuid, name);
        if (entry == null) {
            return Optional.empty();
        }
        UUID key = entry.getProfile().getId();
        Identifier texture = entry.getSkinTextures().texture();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.texture.equals(texture)) {
            return Optional.of(cached.uri);
        }
        try {
            String uri = read(client, texture);
            if (uri == null) {
                return Optional.empty();
            }
            CACHE.put(key, new Cached(texture, uri));
            return Optional.of(uri);
        } catch (Exception e) {
            // Аватарка — украшение: любая беда с GL или PNG не должна снимать игрока с игры.
            Twm.LOGGER.warn("Аватарка не собрана для {}", key, e);
            return Optional.empty();
        }
    }

    private static PlayerListEntry lookup(MinecraftClient client, UUID uuid, String name) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) {
            return null;
        }
        PlayerListEntry entry = uuid == null ? null : handler.getPlayerListEntry(uuid);
        if (entry == null && name != null && !name.isBlank()) {
            entry = handler.getPlayerListEntry(name);
        }
        return entry;
    }

    private static String read(MinecraftClient client, Identifier texture) throws Exception {
        AbstractTexture loaded = client.getTextureManager().getOrDefault(texture, null);
        if (loaded == null || loaded.getGlId() <= 0) {
            return null;
        }
        RenderSystem.bindTexture(loaded.getGlId());
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        // Скин бывает и в HD: важна не абсолютная величина, а кратность канонической сетке 64×32.
        if (width < 64 || height < 32 || width % 64 != 0) {
            return null;
        }
        int unit = width / 64;
        try (NativeImage skin = new NativeImage(width, height, false);
             NativeImage head = new NativeImage(OUTPUT, OUTPUT, true)) {
            skin.loadFromTextureImage(0, false);
            copyPart(skin, head, HEAD_X * unit, HEAD_Y * unit, PART * unit, false);
            copyPart(skin, head, HAT_X * unit, HAT_Y * unit, PART * unit, true);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(head.getBytes());
        }
    }

    /** Ближайший сосед намеренно: сглаженный пиксель-арт выглядит замыленным пятном. */
    private static void copyPart(NativeImage source, NativeImage target, int sourceX, int sourceY,
                                 int size, boolean overlay) {
        for (int y = 0; y < OUTPUT; y++) {
            for (int x = 0; x < OUTPUT; x++) {
                int color = source.getColor(sourceX + x * size / OUTPUT, sourceY + y * size / OUTPUT);
                if (overlay && (color >>> 24) == 0) {
                    continue;
                }
                target.setColor(x, y, color);
            }
        }
    }

    private record Cached(Identifier texture, String uri) {
    }
}
