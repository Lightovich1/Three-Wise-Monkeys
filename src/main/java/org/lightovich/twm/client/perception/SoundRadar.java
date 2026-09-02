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
import java.util.List;
import java.util.Locale;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import org.lightovich.twm.client.ClientParty;
import org.lightovich.twm.client.TwmSettings;

/**
 * Слух рук в картинке. Руки слепые, но слышат — на лёгкой сложности услышанное показывается
 * дугой на кольце вокруг прицела: сторона дуги — направление на источник, толщина — дальность,
 * цвет и значок — что это было.
 *
 * <p>Кольцо, а не метки в мире: у рук туман в пять блоков, метка в проекции источника почти
 * всегда оказывалась бы за его краем и читалась бы как «где-то там». Кольцо вокруг прицела
 * читается одним взглядом и не требует поворачивать голову, чтобы понять, куда поворачиваться.
 *
 * <p>Здесь только сбор и отбор. Рисование — в {@link SoundRadarRenderer}: то же разделение,
 * что у эхолокации, и по той же причине — модель восприятия живёт своей жизнью, кадр своей.
 */
public final class SoundRadar {

    /** Дальше этого звук в кольцо не попадает: слышно ещё, а показывать уже нечего. */
    private static final double MAX_DISTANCE = 32.0D;

    /** Ближе этого источник считается своим: все трое стоят в одной точке. */
    private static final double SELF_DISTANCE = 1.6D;

    /** Больше на кольце не помещается — старейшие снимаются. */
    private static final int CAPACITY = 6;

    /**
     * Повторный звук того же рода почти с того же направления только продлевает дугу.
     * Иначе очередь ударов рисует шесть дуг друг на друге и кольцо становится сплошным.
     */
    private static final float MERGE_ANGLE = 12.0F;

    private static final List<Blip> BLIPS = new ArrayList<>();

    private SoundRadar() {
    }

    public static void clear() {
        synchronized (BLIPS) {
            BLIPS.clear();
        }
    }

    /** Дальность, за которой звук в кольцо не попадает. Нужна кадру для толщины дуги. */
    public static double maxDistance() {
        return MAX_DISTANCE;
    }

    public static List<Blip> blips() {
        synchronized (BLIPS) {
            return List.copyOf(BLIPS);
        }
    }

    public static void tick() {
        // Роль или сложность сменились — кольцо гаснет сразу, а не доживает свои дуги поверх
        // чужого экрана. Та же страховка, что у эхолокации: восприятие принадлежит роли.
        if (!ClientParty.isHands() || !ClientParty.difficulty().hasSoundVisualization()) {
            clear();
            return;
        }
        synchronized (BLIPS) {
            BLIPS.removeIf(blip -> --blip.ticksLeft <= 0);
        }
    }

    /**
     * Звук проигрывается — записать его, если он вообще что-то значит.
     *
     * <p>Зовётся из миксина на звуковую систему, а она работает не только с игрового потока,
     * поэтому список под замком. Считать здесь нечего: классификация — это разбор строки
     * идентификатора, дешевле любой попытки закешировать результат.
     */
    public static void record(SoundInstance instance) {
        if (!TwmSettings.radar().enabled() || !ClientParty.isHands()
                || !ClientParty.difficulty().hasSoundVisualization()) {
            return;
        }
        Kind kind = classify(instance);
        if (kind == null) {
            return;
        }
        // Звук приходит не с игрового потока, поэтому клиент и игрок проверяются оба:
        // на выходе из мира ссылка обнуляется ровно между этими двумя строками.
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player == null) {
            return;
        }
        Vec3d source = new Vec3d(instance.getX(), instance.getY(), instance.getZ());
        Vec3d listener = player.getEyePos();
        double distance = source.distanceTo(listener);
        // Звук «в себя» — это шаги команды, махи и возня в инвентаре: три роли стоят в одной
        // точке, и без отсева кольцо горело бы не переставая. Свой урон при этом показать надо:
        // слепому важнее всего понять, что бьют именно его.
        if (distance <= SELF_DISTANCE && kind != Kind.COMBAT) {
            return;
        }
        if (distance > MAX_DISTANCE) {
            return;
        }
        add(kind, worldYaw(listener, source), (float) distance);
    }

    /**
     * Направление на источник в мировых градусах — тем же счётом, что и yaw игрока.
     *
     * <p>Запоминается именно мировое направление, а не экранное: игрок вертит головой, и дуга
     * обязана оставаться там, где хрустнуло, а не ехать вместе с прицелом. Пересчёт в экранный
     * угол делает кадр — он и знает, куда смотрит камера прямо сейчас.
     */
    private static float worldYaw(Vec3d listener, Vec3d source) {
        double dx = source.x - listener.x;
        double dz = source.z - listener.z;
        if (dx * dx + dz * dz < 1.0E-6D) {
            return 0.0F;
        }
        return (float) (MathHelper.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
    }

    private static void add(Kind kind, float yaw, float distance) {
        int life = TwmSettings.radarTicks();
        synchronized (BLIPS) {
            for (Blip blip : BLIPS) {
                if (blip.kind == kind && Math.abs(MathHelper.wrapDegrees(blip.yaw - yaw)) <= MERGE_ANGLE) {
                    blip.yaw = yaw;
                    blip.distance = Math.min(blip.distance, distance);
                    blip.ticksLeft = life;
                    blip.maxTicks = life;
                    return;
                }
            }
            BLIPS.add(new Blip(kind, yaw, distance, life));
            while (BLIPS.size() > CAPACITY) {
                BLIPS.remove(0);
            }
        }
    }

    /**
     * Разбор идентификатора звука. Показываем только события: мобов, ломание и постановку
     * блоков, взрывы, урон и выстрелы. Музыка, погода, пещерная атмосфера и звуки самого
     * интерфейса не значат ничего — они бы только жгли кольцо.
     *
     * <p>Порядок проверок важен: шаг мобa приходит идентификатором блока
     * ({@code block.stone.step}) в категории существа, поэтому шаг отбирается первым.
     */
    private static Kind classify(SoundInstance instance) {
        SoundCategory category = instance.getCategory();
        if (category != SoundCategory.BLOCKS && category != SoundCategory.HOSTILE
                && category != SoundCategory.NEUTRAL && category != SoundCategory.PLAYERS) {
            return null;
        }
        String path = instance.getId().getPath().toLowerCase(Locale.ROOT);
        if (path.contains(".step") || path.contains("footstep")) {
            return Kind.STEP;
        }
        if (contains(path, "hurt", "death", "attack", "explode", "explosion", "shoot", "arrow",
                "bow", "trident", "fuse", "primed", "charge", "warning", "fall", "blast",
                "sting", "shear", "thorns")) {
            return Kind.COMBAT;
        }
        if (path.startsWith("entity.")) {
            return Kind.MOB;
        }
        if (contains(path, "break", "place", "destroy", "open", "close", "door", "chest",
                "lever", "button", "piston", "trapdoor", "fence_gate", "anvil", "brew", "smoke")) {
            return Kind.BLOCK;
        }
        return null;
    }

    private static boolean contains(String path, String... needles) {
        for (String needle : needles) {
            if (path.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Глухота ног. Ноги не слышат ничего из того, что показывает это кольцо: запрет и
     * визуализация обязаны опираться на один и тот же отбор, иначе на лёгкой руки видели бы
     * звук, которого ноги «не слышали», а на сложной — наоборот.
     *
     * <p>Обратная связь самих ног — сонарный импульс и волна — идёт мимо: у неё категория
     * {@code MASTER}, а сюда попадают только блоки и существа.
     */
    public static boolean muffledForLegs(SoundInstance instance) {
        return ClientParty.isLegs() && classify(instance) != null;
    }

    /** Что услышали. Цвет и значок задаёт кадр, здесь только смысл. */
    public enum Kind {
        STEP,
        BLOCK,
        MOB,
        COMBAT
    }

    /** Одна дуга на кольце. */
    public static final class Blip {
        private final Kind kind;
        private float yaw;
        private float distance;
        private int ticksLeft;
        private int maxTicks;

        private Blip(Kind kind, float yaw, float distance, int life) {
            this.kind = kind;
            this.yaw = yaw;
            this.distance = distance;
            this.ticksLeft = life;
            this.maxTicks = life;
        }

        public Kind kind() {
            return kind;
        }

        /** Мировое направление на источник. Экранный угол считает кадр. */
        public float yaw() {
            return yaw;
        }

        public float distance() {
            return distance;
        }

        /** Остаток жизни от единицы до нуля — по нему считается и прозрачность, и толщина. */
        public float life(float tickDelta) {
            return MathHelper.clamp((ticksLeft - tickDelta) / (float) maxTicks, 0.0F, 1.0F);
        }
    }
}
