/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.server.arena;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

/**
 * Мир одной команды: три измерения на одном сиде и точка входа в них.
 *
 * <p>Три отдельных мира, а не три участка одного, — потому что у мира одна граница и один
 * набор правил измерения. Незер обязан быть незером (сжатие координат 1:8, крепости, блейзы),
 * Энд — Эндом (остров, дракон), а граница обязана быть своя у каждой команды: она и есть
 * то, что не даёт двум забегам встретиться.
 *
 * <p>Миры временные ({@code openTemporaryWorld}): забег кончился — их удаляют вместе с
 * чанками. Сервер режима, на котором копятся сотни брошенных миров по 2000×2000, кончается
 * дисковым переполнением, а не игрой.
 */
public record Arena(String code,
                    long seed,
                    RuntimeWorldHandle overworld,
                    RuntimeWorldHandle nether,
                    RuntimeWorldHandle end,
                    BlockPos center,
                    BlockPos spawn,
                    BlockPos stronghold) {

    public ServerWorld overworldWorld() {
        return overworld.asWorld();
    }

    public ServerWorld netherWorld() {
        return nether.asWorld();
    }

    public ServerWorld endWorld() {
        return end.asWorld();
    }

    public boolean contains(ServerWorld world) {
        return world == overworldWorld() || world == netherWorld() || world == endWorld();
    }
}
