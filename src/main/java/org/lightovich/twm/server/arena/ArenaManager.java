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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

import org.lightovich.twm.Twm;
import org.lightovich.twm.server.TwmConfig;

/**
 * Арены: по три мира на команду, чтобы забеги не пересекались.
 *
 * <p>Задача — «несколько команд играют одновременно на одном сервере и не встречаются».
 * Разнести их по координатам одного мира мало: у мира одна граница на всех, один Энд и один
 * дракон, и любая пара команд рано или поздно встретится хотя бы там. Поэтому команда
 * получает собственный оверворлд, незер и Энд на общем сиде — вся ваниль внутри работает
 * как обычно, а встретиться негде по построению.
 *
 * <p><b>Портал в Энд гарантирован.</b> Крепость ищется по сиду до первого игрока
 * ({@code locateStructure} по кольцам, без генерации чанков), и центр арены ставится так,
 * чтобы крепость оказалась внутри границы, но не под ногами. Рамка пустая — очи ищут
 * ванильно, а значит нужен незер: он у команды свой.
 *
 * <p>Отключается целиком: {@code arena.enabled=false} в {@code twm-server.json}. Тогда забег
 * идёт там же, где стоит команда, — это режим «поиграть с друзьями в своём мире».
 */
public final class ArenaManager {

    /** Радиус поиска крепости в чанках. Первое кольцо ванильных крепостей — около 1280 блоков. */
    private static final int STRONGHOLD_SEARCH_CHUNKS = 200;

    /** Крепость не должна быть ни на спавне, ни у самой границы: искать её — часть забега. */
    private static final int STRONGHOLD_MIN_OFFSET = 250;
    private static final int STRONGHOLD_EDGE_MARGIN = 300;

    /**
     * Шаг и число колец при поиске места под спавн: 8 сторон, до 6 колец по 32 блока.
     * Каждая проба генерирует чанк, поэтому колец немного: 49 проб — это верхняя граница
     * для океана посреди арены, а обычно хватает первой.
     */
    private static final int SPAWN_RINGS = 6;
    private static final int SPAWN_STEP = 32;

    private static final Random RANDOM = new Random();

    private static final Map<String, Arena> BY_CODE = new HashMap<>();

    /**
     * Обратный указатель мир → арена. Читается из миксинов порталов, то есть с любого потока,
     * который тикает миры; отсюда конкурентная карта.
     */
    private static final Map<RegistryKey<World>, Arena> BY_WORLD = new ConcurrentHashMap<>();

    private ArenaManager() {
    }

    public static Arena of(String code) {
        return BY_CODE.get(code);
    }

    /** Живые арены — для админских команд и отчёта о нагрузке. */
    public static Map<String, Arena> active() {
        return Map.copyOf(BY_CODE);
    }

    public static Arena of(ServerWorld world) {
        return world == null ? null : BY_WORLD.get(world.getRegistryKey());
    }

    /** Ванильный ключ измерения, которым притворяется мир арены. Нужен логике порталов. */
    public static RegistryKey<World> vanillaKey(ServerWorld world) {
        Arena arena = of(world);
        if (arena == null) {
            return world.getRegistryKey();
        }
        if (world == arena.netherWorld()) {
            return World.NETHER;
        }
        if (world == arena.endWorld()) {
            return World.END;
        }
        return World.OVERWORLD;
    }

    /** Мир арены, соответствующий ванильному ключу. Обратная сторона {@link #vanillaKey}. */
    public static ServerWorld worldFor(Arena arena, RegistryKey<World> vanilla) {
        if (World.NETHER.equals(vanilla)) {
            return arena.netherWorld();
        }
        if (World.END.equals(vanilla)) {
            return arena.endWorld();
        }
        return arena.overworldWorld();
    }

    /**
     * Создать арену для лобби. Возвращает {@code null}, если что-то пошло не так, — забег в
     * этом случае не начинается: лучше отказ в лобби, чем команда, брошенная в чужой мир.
     */
    public static Arena open(MinecraftServer server, String code) {
        if (BY_CODE.containsKey(code)) {
            return BY_CODE.get(code);
        }
        long started = System.nanoTime();
        long seed = RANDOM.nextLong();
        try {
            Fantasy fantasy = Fantasy.get(server);
            RuntimeWorldHandle overworld = openWorld(server, fantasy, code, "overworld",
                    DimensionTypes.OVERWORLD, World.OVERWORLD, seed);
            RuntimeWorldHandle nether = openWorld(server, fantasy, code, "nether",
                    DimensionTypes.THE_NETHER, World.NETHER, seed);
            RuntimeWorldHandle end = openWorld(server, fantasy, code, "end",
                    DimensionTypes.THE_END, World.END, seed);

            ServerWorld surface = overworld.asWorld();
            BlockPos stronghold = surface.locateStructure(StructureTags.EYE_OF_ENDER_LOCATED,
                    BlockPos.ORIGIN, STRONGHOLD_SEARCH_CHUNKS, false);
            BlockPos center = centerAround(stronghold);

            border(surface, center.getX(), center.getZ(), TwmConfig.get().arenaSize());
            // Незер сжат ванильно 1:8 — центр границы обязан стоять там, куда выведет портал.
            border(nether.asWorld(), center.getX() / 8, center.getZ() / 8,
                    TwmConfig.get().arenaNetherSize());
            // Энд считается от главного острова: он всегда у начала координат.
            border(end.asWorld(), 0, 0, TwmConfig.get().arenaEndSize());
            ownDragonFight(end.asWorld(), seed);

            BlockPos spawn = findSpawn(surface, center.getX(), center.getZ());
            surface.setSpawnPos(spawn, 0.0F);

            Arena arena = new Arena(code, seed, overworld, nether, end, center, spawn, stronghold);
            BY_CODE.put(code, arena);
            BY_WORLD.put(overworld.getRegistryKey(), arena);
            BY_WORLD.put(nether.getRegistryKey(), arena);
            BY_WORLD.put(end.getRegistryKey(), arena);

            Twm.LOGGER.info("Арена {} готова за {} мс: сид {}, центр {} {}, крепость {}",
                    code, (System.nanoTime() - started) / 1_000_000L, seed,
                    center.getX(), center.getZ(),
                    stronghold == null ? "не найдена" : stronghold.toShortString());
            if (stronghold == null) {
                Twm.LOGGER.warn("Арена {}: крепость по сиду не нашлась — портал в Энд придётся "
                        + "искать за границей арены. Забег всё равно играбелен, но цель недостижима.", code);
            }
            return arena;
        } catch (RuntimeException e) {
            Twm.LOGGER.error("Арена {} не создана", code, e);
            close(code);
            return null;
        }
    }

    /**
     * Удалить арену вместе с чанками. Зовётся из остановки забега — <b>после</b> того, как
     * игроков вернули по слепку: удалять мир, в котором кто-то стоит, значит выбрасывать его
     * в мир по умолчанию мимо всего, что мы про него запомнили.
     */
    public static void close(String code) {
        Arena arena = BY_CODE.remove(code);
        if (arena == null) {
            return;
        }
        BY_WORLD.remove(arena.overworld().getRegistryKey());
        BY_WORLD.remove(arena.nether().getRegistryKey());
        BY_WORLD.remove(arena.end().getRegistryKey());
        if (TwmConfig.get().arenaKeepAfterRun()) {
            arena.overworld().unload();
            arena.nether().unload();
            arena.end().unload();
            return;
        }
        arena.overworld().delete();
        arena.nether().delete();
        arena.end().delete();
        Twm.LOGGER.info("Арена {} удалена", code);
    }

    public static void clearAll() {
        for (String code : Map.copyOf(BY_CODE).keySet()) {
            close(code);
        }
        BY_CODE.clear();
        BY_WORLD.clear();
    }

    private static RuntimeWorldHandle openWorld(MinecraftServer server, Fantasy fantasy, String code,
                                                String suffix, RegistryKey<DimensionType> type,
                                                RegistryKey<World> template, long seed) {
        // Генератор берётся у одноимённого мира сервера: арена обязана выглядеть так же, как
        // обычный мир этой сборки, включая датапаки. Свой генератор писать здесь не для чего —
        // задача была «мир под команду», а не «другой мир».
        ChunkGenerator generator = server.getWorld(template).getChunkManager().getChunkGenerator();
        RuntimeWorldConfig config = new RuntimeWorldConfig()
                .setSeed(seed)
                .setDimensionType(type)
                .setGenerator(generator)
                .setShouldTickTime(true)
                .setMirrorOverworldDifficulty(true)
                .setMirrorOverworldGameRules(true);
        Identifier id = Identifier.of(Twm.MOD_ID,
                "arena_" + code.toLowerCase(Locale.ROOT) + "_" + suffix);
        RuntimeWorldHandle handle = fantasy.openTemporaryWorld(id, config);
        // Мир обязан тикать и без игроков: команда уходит в незер, а в оверворлде остаются
        // гореть печи и расти посевы — забег этого ждёт.
        handle.setTickWhenEmpty(true);
        return handle;
    }

    /**
     * Центр арены. Крепость обязана попасть внутрь границы, но не оказаться под ногами: её
     * поиск — половина забега. Сдвиг случайный по направлению и расстоянию, поэтому две арены
     * на одном сиде всё равно играются по-разному.
     */
    private static BlockPos centerAround(BlockPos stronghold) {
        if (stronghold == null) {
            return BlockPos.ORIGIN;
        }
        int half = TwmConfig.get().arenaSize() / 2;
        int max = Math.max(STRONGHOLD_MIN_OFFSET + 1, half - STRONGHOLD_EDGE_MARGIN);
        int distance = STRONGHOLD_MIN_OFFSET + RANDOM.nextInt(Math.max(1, max - STRONGHOLD_MIN_OFFSET));
        double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
        return new BlockPos(
                stronghold.getX() + (int) (Math.cos(angle) * distance),
                stronghold.getY(),
                stronghold.getZ() + (int) (Math.sin(angle) * distance));
    }

    /**
     * Свой бой с драконом у каждой арены.
     *
     * <p>Бой заводит сам {@code ServerWorld} — по типу измерения, поэтому у мира арены он
     * есть и без нас. Беда в его начальном состоянии: ваниль берёт его из общего
     * {@code level.dat} сервера. На сервере, где дракона уже убивали, каждая новая команда
     * получала бы Энд с мёртвым драконом, выходным порталом наружу и без цели забега.
     *
     * <p>Заменяем бой на чистый: столбы, кристаллы и дракон рождаются заново, как в новом мире.
     */
    private static void ownDragonFight(ServerWorld end, long seed) {
        if (!TwmConfig.get().arenaOwnDragon()) {
            return;
        }
        end.setEnderDragonFight(new EnderDragonFight(end, seed, EnderDragonFight.Data.DEFAULT));
    }

    private static void border(ServerWorld world, int centerX, int centerZ, int size) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(centerX, centerZ);
        border.setSize(size);
        border.setSafeZone(2.0D);
        border.setDamagePerBlock(0.5D);
        border.setWarningBlocks(16);
        border.setWarningTime(0);
    }

    /**
     * Точка входа команды. Ищется по кольцам от центра: первая поверхность, где под ногами
     * твёрдый блок, а не вода и не лава. Высадить троих в океан — это не «сложное начало»,
     * это забег, который кончился, не начавшись.
     */
    private static BlockPos findSpawn(ServerWorld world, int centerX, int centerZ) {
        for (int ring = 0; ring <= SPAWN_RINGS; ring++) {
            int points = ring == 0 ? 1 : 8;
            for (int index = 0; index < points; index++) {
                double angle = index * Math.PI * 2.0D / points;
                int x = centerX + (int) (Math.cos(angle) * ring * SPAWN_STEP);
                int z = centerZ + (int) (Math.sin(angle) * ring * SPAWN_STEP);
                // Чанк берётся явно: World.getTopY у незагруженного чанка отвечает дном мира,
                // а не «не знаю», и вся проверка рельефа превращалась бы в высадку на -64.
                //
                // Статус SURFACE, а не FULL: нужен только рельеф, а полная генерация с
                // постройками, растительностью и светом стоит на порядок дороже — при полном
                // статусе поиск места занимал две секунды серверного потока на каждый забег.
                Chunk chunk = world.getChunk(x >> 4, z >> 4, ChunkStatus.SURFACE, true);
                int top = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG, x & 15, z & 15);
                int floor = chunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR_WG, x & 15, z & 15);
                if (top <= world.getBottomY() + 1) {
                    continue;
                }
                // Две отметки разошлись — сверху вода или лава: там высаживать нельзя.
                if (top != floor) {
                    continue;
                }
                return new BlockPos(x, top + 1, z);
            }
        }
        // Кругом вода или лава. Высаживаем над уровнем моря: утонуть на старте хуже, чем
        // упасть в воду с двух блоков.
        return new BlockPos(centerX, world.getSeaLevel() + 1, centerZ);
    }
}
