/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client.perception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.lightovich.twm.Difficulty;
import org.lightovich.twm.TwmSounds;
import org.lightovich.twm.client.ClientParty;
import org.lightovich.twm.client.TwmSettings;

/**
 * Восприятие ног. Мир раскрывается расходящейся волной, а не залповым сканом раз в полминуты:
 * каждый блок проявляется ровно в тот момент, когда до него дошёл фронт, и гаснет по возрасту.
 * Прошлая версия перерисовывала весь радиус разом, из-за чего мир менялся рывками.
 *
 * <p>Лёгкая сложность: волны идут непрерывно, картина мира не пропадает.
 * Сложная: волна рождается только от шага, а дальний скан — по кнопке с кулдауном.
 */
public final class Echolocation {

    /** Дальний радиус: и непрерывная волна лёгкой сложности, и ручной пинг сложной. */
    private static final int FAR_RANGE = 24;
    /** Ближний радиус волны от шага. */
    private static final int STEP_RANGE = 10;
    /** Ограничение по высоте: без него скан уходит в перекрытия и потолки соседних этажей. */
    private static final int VERTICAL_RANGE = 8;

    private static final float FAR_SPEED = 0.55F;   // блоков за тик, ~11 блоков/сек
    private static final float STEP_SPEED = 0.45F;

    private static final int EASY_WAVE_PERIOD = 24;  // тиков между непрерывными волнами
    private static final int FADE_IN_TICKS = 4;
    private static final int FADE_OUT_TICKS = 30;
    private static final double STEP_DISTANCE = 1.6D;

    /** Блоки в упор всегда «нащупываются»: иначе легко воткнуться в стену между волнами. */
    private static final int TOUCH_RANGE = 2;

    /**
     * Базовая громкость сонара. Настройка идёт множителем к ней, а не абсолютным значением:
     * единица в настройках обязана звучать ровно так же, как звучало до появления настроек.
     */
    private static final float SOUND_BASE_VOLUME = 0.25F;

    /** Шаговая волна звучит выше и тише пинга — она фон, а не сигнал. */
    private static final float WAVE_PITCH = 1.7F;

    /**
     * Потолок памяти. В памяти оседают только поверхности (блок без единой открытой грани не
     * запоминается), поэтому даже под землёй счёт идёт на тысячи, а не на десятки тысяч.
     * Потолок нужен как страховка от пещерных систем, а не как рабочий режим: если он режет
     * обычную картину мира, мир исчезает пластами.
     */
    /** Насколько дольше держится память на сложной: 400 тиков против 240 в версии 0.3.2. */
    private static final float HARD_MEMORY_FACTOR = 1.667F;

    private static final int MAX_MEMORY = 16_000;

    /**
     * Бюджет раскрытия — на каждую волну, а не на все сразу.
     *
     * <p>Фронт дальней волны на полном радиусе требует около 1300 позиций за тик
     * ({@code 2π·r·2·VERTICAL_RANGE·speed}). Общий бюджет 700 на всех означал, что первая
     * волна съедала его целиком и не поспевала за собственным фронтом, вторая не получала
     * ни одного блока, а её origin успевал устареть на десятки тиков — мир раскрывался там,
     * где ноги уже не стоят, и не успевал обновиться до истечения памяти.
     */
    private static final int REVEAL_BUDGET_PER_WAVE = 1_800;

    /** Больше трёх волн одновременно не бывает нужно: старую всё равно перекрывает новая. */
    private static final int MAX_WAVES = 3;

    /**
     * Повторный приход волны в пределах этого срока только продлевает эхо. Грани пересчитывать
     * незачем: соседние волны накладываются, и без этого каждая заново опрашивает семь блоков
     * на позицию там, где картина заведомо свежая.
     */
    private static final int REFRESH_SKIP_TICKS = 20;

    /** Чистка просроченного — раз в столько тиков: проход по всей памяти каждый тик не нужен. */
    private static final int EXPIRE_INTERVAL = 10;

    /**
     * Изменение блока рядом рождает свою маленькую волну: ломание и постановка сами по себе
     * шумят, и картина вокруг обязана обновиться сразу, а не ждать следующего шага.
     */
    private static final int CHANGE_RANGE = 7;
    private static final float CHANGE_SPEED = 1.2F;

    /**
     * Ломать блоки можно очередями, а поставить волну на каждый удар кирки — это очередь волн,
     * которая вытеснит дальнюю. Одна волна на изменение и не чаще, чем раз в столько тиков;
     * само забывание сломанного блока при этом мгновенно и от кулдауна не зависит.
     */
    private static final int CHANGE_WAVE_INTERVAL = 4;

    /** Локальных волн держим не больше: место в очереди принадлежит дальней волне. */
    private static final int MAX_LOCAL_WAVES = 2;

    private static final Map<BlockPos, Echo> MEMORY = new HashMap<>();
    private static final List<Wave> WAVES = new ArrayList<>();
    private static List<BlockPos> offsets;

    private static long clock;
    private static int nextEasyWave;
    private static long lastChangeWave = Long.MIN_VALUE;
    private static int pingCooldown;
    private static double walkedSinceStep;
    private static Vec3d lastPosition;

    private Echolocation() {
    }

    public static void reset() {
        MEMORY.clear();
        WAVES.clear();
        clock = 0L;
        nextEasyWave = 0;
        lastChangeWave = Long.MIN_VALUE;
        pingCooldown = 0;
        walkedSinceStep = 0.0D;
        lastPosition = null;
    }

    public static boolean isPingReady() {
        return pingCooldown <= 0;
    }

    /** Ручной пинг сложной сложности: одна дальняя волна с кулдауном. */
    public static void ping() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !ClientParty.isLegs() || pingCooldown > 0) {
            return;
        }
        addWave(new Wave(client.player.getEyePos(), FAR_RANGE, FAR_SPEED, false));
        pingCooldown = ClientParty.echoCooldownTicks();
        // Сонарный импульс — обратная связь игроку, а не звук в мире: он должен звучать
        // даже когда ноги перестанут слышать всё остальное.
        if (TwmSettings.sound().pingEnabled()) {
            play(client, 1.0F, TwmSettings.sound().pingVolume());
        }
    }

    /** Отдельного ассета у шаговой волны нет: тот же импульс, выше тоном и тише. */
    private static void playWaveSound(MinecraftClient client) {
        if (TwmSettings.sound().waveEnabled()) {
            play(client, WAVE_PITCH, TwmSettings.sound().waveVolume());
        }
    }

    private static void play(MinecraftClient client, float pitch, float volume) {
        if (volume <= 0.0F) {
            return;
        }
        client.getSoundManager().play(PositionedSoundInstance.master(
                TwmSounds.ECHO_PING, pitch, SOUND_BASE_VOLUME * volume));
    }

    public static void tick() {
        if (!ClientParty.isLegs()) {
            if (!MEMORY.isEmpty() || !WAVES.isEmpty()) {
                reset();
            }
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }

        clock++;
        if (pingCooldown > 0) {
            pingCooldown--;
        }
        spawnWaves(client, player);
        advanceWaves(client.world);
        revealTouchRange(client.world, player);
        if (clock % EXPIRE_INTERVAL == 0) {
            expire();
        }
    }

    private static void spawnWaves(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d position = player.getPos();
        if (lastPosition == null) {
            lastPosition = position;
        }
        walkedSinceStep += position.subtract(lastPosition).horizontalLength();
        lastPosition = position;

        // Шаг отмеряется на обеих сложностях: волну от него рождает только сложная, а звук
        // нужен всегда — он даёт ощущение, что мир отзывается на движение.
        boolean stepped = walkedSinceStep >= STEP_DISTANCE;
        if (stepped) {
            walkedSinceStep = 0.0D;
            playWaveSound(client);
        }

        if (ClientParty.difficulty() == Difficulty.EASY) {
            if (--nextEasyWave <= 0) {
                nextEasyWave = EASY_WAVE_PERIOD;
                addWave(new Wave(player.getEyePos(), FAR_RANGE, FAR_SPEED, false));
            }
            return;
        }
        if (stepped) {
            addWave(new Wave(player.getEyePos(), STEP_RANGE, STEP_SPEED, false));
        }
    }

    /**
     * Самая старая волна снимается: догонять фронт ей уже нечем, а бюджет она забирает.
     *
     * <p>Локальные волны от изменения блоков вытесняются первыми и держатся своей квотой.
     * Иначе очередь ударов киркой выбила бы дальнюю волну, и мир погас бы весь ради
     * обновления одного метра вокруг.
     */
    private static void addWave(Wave wave) {
        WAVES.add(wave);
        if (wave.local) {
            trim(true, MAX_LOCAL_WAVES);
        }
        trim(false, MAX_WAVES);
    }

    private static void trim(boolean localOnly, int keep) {
        int count = 0;
        for (Wave wave : WAVES) {
            if (!localOnly || wave.local) {
                count++;
            }
        }
        Iterator<Wave> iterator = WAVES.iterator();
        while (count > keep && iterator.hasNext()) {
            Wave wave = iterator.next();
            if (localOnly && !wave.local) {
                continue;
            }
            iterator.remove();
            count--;
        }
    }

    /**
     * Мир изменился: блок сломан, поставлен или подменён. Зовётся клиентским миксином на
     * каждое изменение блока в загруженном мире.
     *
     * <p>Память чинится сразу и всегда — сломанный блок обязан исчезнуть в тот же тик, а не
     * дожидаться следующей волны; вместе с ним пересчитываются грани шести соседей, иначе
     * стена вокруг проёма осталась бы без только что открывшихся граней. Это семь обращений
     * к памяти, дешевле одного блока в кадре.
     *
     * <p>Новую геометрию раскрывает маленькая волна от точки изменения: узнавать о
     * поставленном блоке «из ниоткуда» ноги не должны, а стук постановки они слышат.
     */
    public static void onBlockChanged(World world, BlockPos position) {
        if (!ClientParty.isLegs() || (MEMORY.isEmpty() && WAVES.isEmpty())) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world != world) {
            return;
        }
        forget(world, position);
        for (Direction direction : Direction.values()) {
            forget(world, position.offset(direction));
        }
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(position))
                > CHANGE_RANGE * CHANGE_RANGE) {
            return;
        }
        if (clock - lastChangeWave < CHANGE_WAVE_INTERVAL) {
            return;
        }
        lastChangeWave = clock;
        addWave(new Wave(Vec3d.ofCenter(position), CHANGE_RANGE, CHANGE_SPEED, true));
    }

    /**
     * Пересчёт одной запомненной позиции. Незапомненную не трогаем: волна раскрывает мир, а
     * изменение блока только чинит уже известное — иначе ноги узнавали бы о постановке блока
     * в стене, которой для них ещё не существует.
     */
    private static void forget(World world, BlockPos position) {
        Echo echo = MEMORY.get(position);
        if (echo == null) {
            return;
        }
        int faces = echoableFaces(world, position);
        if (faces == 0) {
            MEMORY.remove(position);
            return;
        }
        echo.faces = faces;
        echo.checkedAt = clock;
    }

    /**
     * Фронт каждой волны продвигается на скорость волны и раскрывает только те блоки, до
     * которых он дошёл именно в этот тик. Смещения заранее отсортированы по расстоянию,
     * поэтому волна просто двигает указатель по списку и не пересматривает весь радиус.
     */
    private static void advanceWaves(World world) {
        Iterator<Wave> iterator = WAVES.iterator();
        while (iterator.hasNext()) {
            Wave wave = iterator.next();
            wave.radius += wave.speed;
            int budget = REVEAL_BUDGET_PER_WAVE;
            List<BlockPos> shell = offsets();
            BlockPos origin = BlockPos.ofFloored(wave.origin);
            double limit = Math.min(wave.radius, wave.maxRadius);
            while (wave.cursor < shell.size() && budget > 0) {
                BlockPos offset = shell.get(wave.cursor);
                if (length(offset) > limit) {
                    break;
                }
                wave.cursor++;
                budget--;
                BlockPos position = origin.add(offset);
                if (!world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
                    continue;
                }
                remember(world, position);
            }
            // Волна снимается, только когда фронт дошёл до края и раскрывать больше нечего.
            // При исчерпанном бюджете она обязана доработать в следующем тике, иначе часть
            // блоков в её радиусе просто не появится.
            boolean nothingLeft = wave.cursor >= shell.size()
                    || length(shell.get(wave.cursor)) > wave.maxRadius;
            if (wave.radius >= wave.maxRadius && nothingLeft) {
                iterator.remove();
            }
        }
    }

    private static void revealTouchRange(World world, ClientPlayerEntity player) {
        BlockPos center = player.getBlockPos();
        for (int x = -TOUCH_RANGE; x <= TOUCH_RANGE; x++) {
            for (int y = -TOUCH_RANGE; y <= TOUCH_RANGE; y++) {
                for (int z = -TOUCH_RANGE; z <= TOUCH_RANGE; z++) {
                    remember(world, center.add(x, y, z));
                }
            }
        }
    }

    /**
     * Когда позиции коснулась волна. Продление эха и пересчёт граней — разные сроки, и мерить
     * их одним полем нельзя: в упор к ногам {@code revealTouchRange} касается блока каждый тик,
     * поэтому «прошло меньше {@code REFRESH_SKIP_TICKS} с прошлого касания» там истинно всегда.
     * С одним полем грани у ближних блоков не пересчитывались **никогда** — сломанный блок под
     * носом оставался в памяти навечно.
     */
    private static void remember(World world, BlockPos position) {
        Echo existing = MEMORY.get(position);
        if (existing != null) {
            existing.refreshedAt = clock;
            if (clock - existing.checkedAt < REFRESH_SKIP_TICKS) {
                return;
            }
        }
        int faces = echoableFaces(world, position);
        if (faces == 0) {
            MEMORY.remove(position);
            return;
        }
        if (existing != null) {
            existing.checkedAt = clock;
            existing.faces = faces;
            return;
        }
        MEMORY.put(position.toImmutable(), new Echo(clock, faces));
    }

    /**
     * Эхо возвращают только твёрдые поверхности. Жидкости, огонь и опасные блоки в скан не
     * попадают: ноги не должны узнавать об опасности раньше, чем о ней скажут словами.
     */
    private static int echoableFaces(World world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!state.isSolidBlock(world, position) || !state.getFluidState().isEmpty() || isForbidden(state)) {
            return 0;
        }
        int faces = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = position.offset(direction);
            BlockState neighbourState = world.getBlockState(neighbour);
            if (!neighbourState.isSolidBlock(world, neighbour) || !neighbourState.getFluidState().isEmpty()) {
                faces |= 1 << direction.ordinal();
            }
        }
        return faces;
    }

    private static boolean isForbidden(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.WATER) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.WITHER_ROSE);
    }

    private static void expire() {
        int lifetime = memoryTicks();
        MEMORY.entrySet().removeIf(entry -> clock - entry.getValue().refreshedAt > lifetime);
        if (MEMORY.size() <= MAX_MEMORY) {
            return;
        }
        // Переполнение: срезаем половину срока жизни, начиная с самого старого. Точная
        // сортировка тут не нужна — важно вернуться под потолок за один проход.
        long cutoff = clock - lifetime / 2;
        MEMORY.entrySet().removeIf(entry -> entry.getValue().refreshedAt < cutoff);
        Iterator<Map.Entry<BlockPos, Echo>> iterator = MEMORY.entrySet().iterator();
        while (MEMORY.size() > MAX_MEMORY && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * Настройка задаёт базовый срок памяти, сложность его домножает. Разница между сложностями
     * не косметическая: на сложной волна рождается только от шага, и памяти нужно больше, иначе
     * мир гаснет быстрее, чем ноги успевают его пройти. Пропорция сохранена прежней (240 / 400).
     */
    private static int memoryTicks() {
        int base = TwmSettings.echoLifeTicks();
        return ClientParty.difficulty() == Difficulty.EASY ? base : Math.round(base * HARD_MEMORY_FACTOR);
    }

    /**
     * Прозрачность блока: короткое проявление, ровное горение и затухание к концу памяти.
     * Именно это даёт ощущение волны вместо мгновенной подмены картинки.
     */
    public static float alphaOf(Echo echo) {
        long age = clock - echo.refreshedAt;
        if (age < FADE_IN_TICKS) {
            return (age + 1.0F) / (FADE_IN_TICKS + 1.0F);
        }
        int lifetime = memoryTicks();
        long remaining = lifetime - age;
        if (remaining < FADE_OUT_TICKS) {
            return Math.max(0.0F, remaining / (float) FADE_OUT_TICKS);
        }
        return 1.0F;
    }

    public static Map<BlockPos, Echo> memory() {
        return MEMORY;
    }

    private static double length(BlockPos offset) {
        return Math.sqrt(offset.getX() * offset.getX() + offset.getY() * offset.getY()
                + offset.getZ() * offset.getZ());
    }

    /** Смещения в пределах дальнего радиуса, отсортированные по расстоянию: скелет фронта волны. */
    private static List<BlockPos> offsets() {
        if (offsets != null) {
            return offsets;
        }
        List<BlockPos> result = new ArrayList<>();
        for (int x = -FAR_RANGE; x <= FAR_RANGE; x++) {
            for (int z = -FAR_RANGE; z <= FAR_RANGE; z++) {
                for (int y = -VERTICAL_RANGE; y <= VERTICAL_RANGE; y++) {
                    if (x * x + y * y + z * z <= FAR_RANGE * FAR_RANGE) {
                        result.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        result.sort((first, second) -> Double.compare(length(first), length(second)));
        offsets = List.copyOf(result);
        return offsets;
    }

    /** Один запомненный блок: когда его последний раз коснулась волна и какие грани открыты. */
    public static final class Echo {
        private long refreshedAt;
        /** Когда грани считались в последний раз — отдельно от срока жизни самого эха. */
        private long checkedAt;
        private int faces;

        private Echo(long refreshedAt, int faces) {
            this.refreshedAt = refreshedAt;
            this.checkedAt = refreshedAt;
            this.faces = faces;
        }

        public int faces() {
            return faces;
        }
    }

    private static final class Wave {
        private final Vec3d origin;
        private final int maxRadius;
        private final float speed;
        /** Волна от изменения блока: своя квота и вытесняется первой. */
        private final boolean local;
        private float radius;
        private int cursor;

        private Wave(Vec3d origin, int maxRadius, float speed, boolean local) {
            this.origin = origin;
            this.maxRadius = maxRadius;
            this.speed = speed;
            this.local = local;
        }
    }
}
