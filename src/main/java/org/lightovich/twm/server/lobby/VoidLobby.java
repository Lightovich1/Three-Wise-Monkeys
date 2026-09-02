/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.server.lobby;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

import org.lightovich.twm.Twm;
import org.lightovich.twm.party.PartyManager;
import org.lightovich.twm.server.TwmConfig;

/**
 * Пустой мир, в котором команда стоит между забегами.
 *
 * <p>На сервере с аренами игрок вне забега смотрит в меню, а не в мир: меню заперто до
 * старта. Держать под этим меню обычный ландшафт незачем — он генерируется, тикает и
 * заселяется мобами, которые бьют человека, пока тот выбирает роль. Поэтому лобби — это
 * платформа из барьеров в мире без блоков вообще, мирной сложности и без смены суток.
 *
 * <p>Главный мир сервера при этом остаётся: с него арены берут генератор, то есть именно он
 * решает, как выглядит мир забега. Никто в нём не стоит — только спавн-чанки, и те можно
 * убрать правилом {@code spawnChunkRadius}.
 */
public final class VoidLobby {

    private static final Identifier WORLD_ID = Identifier.of(Twm.MOD_ID, "lobby");
    private static final int FLOOR_Y = 64;
    private static final int RADIUS = 8;
    private static final int WALL_HEIGHT = 3;
    private static final BlockPos SPAWN = new BlockPos(0, FLOOR_Y + 1, 0);

    private static RuntimeWorldHandle handle;

    private VoidLobby() {
    }

    /** Мир лобби или {@code null}, если пустое лобби выключено. */
    public static ServerWorld world() {
        return handle == null ? null : handle.asWorld();
    }

    public static void open(MinecraftServer server) {
        if (!TwmConfig.get().voidLobby()) {
            return;
        }
        RegistryEntry<Biome> biome = server.getRegistryManager()
                .get(RegistryKeys.BIOME)
                .getEntry(BiomeKeys.THE_VOID)
                .orElseThrow();
        // Слои не заданы вовсе — это и есть мир без блоков. Барьеры под ноги кладём сами.
        FlatChunkGeneratorConfig settings = new FlatChunkGeneratorConfig(Optional.empty(), biome, List.of());
        RuntimeWorldConfig config = new RuntimeWorldConfig()
                .setSeed(0L)
                .setDimensionType(DimensionTypes.OVERWORLD)
                .setGenerator(new FlatChunkGenerator(settings))
                // Время стоит: полдень над платформой читается лучше, чем ночь, а тикать
                // суткам в лобби незачем.
                .setShouldTickTime(false)
                .setTimeOfDay(6000L)
                .setDifficulty(Difficulty.PEACEFUL)
                .setFlat(true);
        handle = Fantasy.get(server).getOrOpenPersistentWorld(WORLD_ID, config);
        handle.setTickWhenEmpty(false);
        buildPlatform(handle.asWorld());
        Twm.LOGGER.info("Лобби живёт в пустом мире {}", WORLD_ID);
    }

    public static void close() {
        handle = null;
    }

    /**
     * Ставит вошедшего на платформу. Игрока в забеге не трогаем: он в арене, и «вернуть в
     * лобби» его должен конец забега, а не вход в сеть.
     */
    public static void place(ServerPlayerEntity player) {
        ServerWorld lobby = world();
        if (lobby == null || PartyManager.isInActiveParty(player.getUuid())) {
            return;
        }
        if (player.getServerWorld() != lobby) {
            player.teleport(lobby, SPAWN.getX() + 0.5, SPAWN.getY(), SPAWN.getZ() + 0.5,
                    Set.of(), player.getYaw(), 0.0F);
        }
        player.setSpawnPoint(lobby.getRegistryKey(), SPAWN, player.getYaw(), true, false);
    }

    /**
     * Платформа с бортиком. Барьер выбран потому, что его не видно и по нему нельзя ударить:
     * пол под ногами не должен отвлекать от меню, а бортик — единственное, что стоит между
     * зевающим игроком и падением в пустоту.
     */
    private static void buildPlatform(ServerWorld world) {
        if (!world.getBlockState(new BlockPos(0, FLOOR_Y, 0)).isAir()) {
            return;
        }
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                world.setBlockState(pos.set(x, FLOOR_Y, z), Blocks.BARRIER.getDefaultState());
                boolean edge = Math.abs(x) == RADIUS || Math.abs(z) == RADIUS;
                if (!edge) {
                    continue;
                }
                for (int y = 1; y <= WALL_HEIGHT; y++) {
                    world.setBlockState(pos.set(x, FLOOR_Y + y, z), Blocks.BARRIER.getDefaultState());
                }
            }
        }
        world.setSpawnPos(SPAWN, 0.0F);
    }
}
