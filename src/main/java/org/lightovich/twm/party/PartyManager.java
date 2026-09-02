/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.party;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import org.lightovich.twm.Role;
import org.lightovich.twm.net.TwmPayloads;
import org.lightovich.twm.server.PlayerRestore;
import org.lightovich.twm.server.TwmConfig;
import org.lightovich.twm.server.arena.Arena;
import org.lightovich.twm.server.arena.ArenaManager;

/**
 * Игровая половина команды: синхронизация общего тела, права ролей и общий цикл смерти.
 * Кто с кем в команде — спрашивается у {@link LobbyManager}, второго реестра здесь нет.
 *
 * <p>Модель тела: руки — единственная физическая сущность (видимая, инвентарь, здоровье,
 * ванильные взаимодействия). Ноги — реальная коллидируемая сущность-контроллер: они ходят
 * обычным ванильным движением, а сервер переносит принятое смещение на тело. Голова —
 * пассивный якорь камеры без хитбокса. Голова и ноги не могут быть выбраны прицелом,
 * снарядом или мобом (см. миксины), поэтому серверу не нужно перенаправлять урон и подбор.
 */
public final class PartyManager {

    /**
     * Кому мы отменили выпадение вещей на общей смерти.
     *
     * <p>Метка нужна потому, что решение принимается в момент смерти, а применяется в момент
     * возрождения — между ними забег могли остановить, и спрашивать «состоит ли он ещё в
     * играющей команде» было бы уже поздно: ответ стал бы «нет», а вещи пропали бы совсем.
     * Решает тот, кто отменил; метка снимается при переносе.
     */
    private static final Set<UUID> KEPT_ON_DEATH = ConcurrentHashMap.newKeySet();

    private PartyManager() {
    }

    /**
     * Общая смерть — это смерть <b>тела</b>. Голова и ноги неуязвимы и умирают только потому,
     * что команда умирает целиком: повлиять на это они не могли, тела у них нет, а инвентарь
     * забега держат руки. Ронять их личные вещи не за что.
     *
     * <p>Ваниль иначе не умеет: она либо роняет инвентарь, либо (с {@code keepInventory})
     * не роняет ни у кого, включая руки, — а смерть тела обязана стоить команде вещей.
     * Поэтому выпадение отменяется адресно, и вещи переносятся в новую сущность игрока.
     */
    public static boolean keepsBelongingsOnDeath(UUID playerId) {
        if (!isHiddenAnchor(playerId)) {
            return false;
        }
        KEPT_ON_DEATH.add(playerId);
        return true;
    }

    /** Перенести вещи и опыт в возрождённую сущность. Метка снимается: она одноразовая. */
    public static void carryBelongings(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer) {
        if (!KEPT_ON_DEATH.remove(oldPlayer.getUuid())) {
            return;
        }
        newPlayer.getInventory().clone(oldPlayer.getInventory());
        newPlayer.experienceLevel = oldPlayer.experienceLevel;
        newPlayer.totalExperience = oldPlayer.totalExperience;
        newPlayer.experienceProgress = oldPlayer.experienceProgress;
    }

    /** Вышедший метку не уносит: возрождаться ему уже негде. */
    public static void forgetBelongings(UUID playerId) {
        KEPT_ON_DEATH.remove(playerId);
    }

    // --- Запросы состояния -------------------------------------------------

    public static Optional<Role> roleOf(UUID playerId) {
        Party party = LobbyManager.partyOf(playerId);
        return party == null ? Optional.empty() : Optional.ofNullable(party.roleOf(playerId));
    }

    public static boolean isInActiveParty(UUID playerId) {
        Party party = LobbyManager.partyOf(playerId);
        return party != null && party.active;
    }

    /** Роль владеет всеми ванильными взаимодействиями: это только руки. */
    public static boolean canInteract(UUID playerId) {
        Party party = LobbyManager.partyOf(playerId);
        if (party == null || !party.active) {
            return true;
        }
        return party.roleOf(playerId) == Role.HANDS;
    }

    /** Скрытая роль (голова/ноги) активной команды — её нельзя выбрать прицелом или снарядом. */
    public static boolean isHiddenAnchor(UUID playerId) {
        Party party = LobbyManager.partyOf(playerId);
        return party != null && party.active && party.roleOf(playerId) != Role.HANDS;
    }

    /** Физическое тело команды, к которой принадлежит игрок. */
    public static Optional<ServerPlayerEntity> bodyOf(ServerPlayerEntity player) {
        Party party = LobbyManager.partyOf(player.getUuid());
        if (party == null || !party.active) {
            return Optional.empty();
        }
        UUID hands = party.memberWithRole(Role.HANDS);
        return hands == null ? Optional.empty()
                : Optional.ofNullable(player.getServer().getPlayerManager().getPlayer(hands));
    }

    public static boolean shareParty(UUID first, UUID second) {
        Party party = LobbyManager.partyOf(first);
        return party != null && party == LobbyManager.partyOf(second);
    }

    // --- Старт и остановка игры -------------------------------------------

    /**
     * Перевод лобби в игру. Состав и роли проверяет лобби, здесь остаётся только физическая
     * часть: собрать троих в одной точке и раздать ролям режимы и видимость.
     */
    static boolean startGame(MinecraftServer server, Party party) {
        ServerPlayerEntity body = server.getPlayerManager().getPlayer(party.memberWithRole(Role.HANDS));
        if (body == null) {
            return false;
        }
        // Слепок снимается до первой правки состояния: он и есть путь обратно, в том числе
        // после падения сервера. Инвентарь и положение попадают в него только на аренах —
        // там забег уносит команду в отдельный мир, а в своём мире с друзьями трогать вещи
        // игрока незачем, он играет там же, где стоял.
        PlayerRestore restore = PlayerRestore.of(server);
        boolean relocating = TwmConfig.get().arenaEnabled();
        for (UUID memberId : party.members) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member != null) {
                restore.capture(member, relocating);
            }
        }

        Arena arena = null;
        if (relocating) {
            arena = ArenaManager.open(server, party.code);
            if (arena == null) {
                // Мир не поднялся — забег не начинаем. Бросить команду в общий мир сервера
                // хуже отказа: она уйдёт играть туда, где ей быть не полагалось.
                for (UUID memberId : party.members) {
                    restore.drop(memberId);
                }
                return false;
            }
        }

        party.active = true;
        party.deathCycle = false;
        party.startedAt = System.currentTimeMillis();
        party.deaths = 0;
        party.reachedEnd = false;
        party.dragonKilled = false;
        if (arena != null) {
            enterArena(server, party, arena);
            // Тело перечитывается после переноса и проверяется снова: команда уже в арене,
            // и оборвать старт «на полдороге» нельзя — надо откатить его целиком.
            body = server.getPlayerManager().getPlayer(party.memberWithRole(Role.HANDS));
            if (body == null) {
                stopGame(server, party, null);
                return false;
            }
        } else {
            for (UUID memberId : party.members) {
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
                if (member == null || member == body) {
                    continue;
                }
                member.teleport(body.getServerWorld(), body.getX(), body.getY(), body.getZ(),
                        member.getYaw(), member.getPitch());
            }
        }
        party.legsFallDistance = 0.0F;
        applyEntityState(server, party, body);
        sendRoleToAll(server, party);
        return true;
    }

    /**
     * Вход команды в арену: все трое в одну точку и, если так настроен сервер, налегке.
     * Пустой инвентарь — часть режима, а не потеря: свои вещи вернёт слепок на выходе.
     */
    private static void enterArena(MinecraftServer server, Party party, Arena arena) {
        Vec3d spawn = arena.spawn().toBottomCenterPos();
        boolean fresh = TwmConfig.get().arenaFreshStart();
        for (UUID memberId : party.members) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member == null) {
                continue;
            }
            member.teleport(arena.overworldWorld(), spawn.x, spawn.y, spawn.z, Set.of(),
                    member.getYaw(), 0.0F);
            if (!fresh) {
                continue;
            }
            member.getInventory().clear();
            member.setExperienceLevel(0);
            member.setExperiencePoints(0);
            member.totalExperience = 0;
            member.experienceProgress = 0.0F;
            member.setHealth(member.getMaxHealth());
            member.getHungerManager().setFoodLevel(20);
            member.getHungerManager().setSaturationLevel(5.0F);
        }
    }

    /**
     * Возврат команды в обычный режим. Роли в лобби при этом сохраняются, а клиенту уходит
     * пустая роль: для него «не играю» и «нет роли» — одно состояние, лобби он читает из снимка.
     */
    static void stopGame(MinecraftServer server, Party party, Text reason) {
        party.active = false;
        party.deathCycle = false;
        party.legsFallDistance = 0.0F;
        String summary = TwmConfig.get().runSummary() ? summaryJson(server, party) : null;
        PlayerRestore restore = PlayerRestore.of(server);
        for (UUID memberId : party.members) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member == null) {
                continue;
            }
            // Уходящего возвращать нельзя: телепорт через миры пересоздаёт сущность игрока, а
            // связь уже закрывается. Его слепок остаётся на диске и применится на входе — там
            // это делает LobbyManager.onJoin.
            if (member.isDisconnected()) {
                continue;
            }
            if (!restore.restore(member)) {
                // Слепка нет только если забег начался до появления слепков или файл потерян.
                // Снимаем ровно то, что ставили: без этого роль осталась бы невидимой навсегда.
                member.setInvisible(false);
                member.setNoGravity(false);
                member.setInvulnerable(false);
                member.changeGameMode(GameMode.SURVIVAL);
                member.calculateDimensions();
            }
            ServerPlayNetworking.send(member, new TwmPayloads.RoleSync("", party.difficulty.id(),
                    party.echoCooldownTicks, List.of()));
            if (summary != null) {
                ServerPlayNetworking.send(member, new TwmPayloads.RunSummary(summary));
            }
            if (reason != null) {
                member.sendMessage(reason.copy().formatted(Formatting.YELLOW), false);
            }
        }
        // Строго после возврата: удалять мир, в котором кто-то стоит, значит выбросить его
        // мимо слепка — в мир по умолчанию и не туда, откуда он пришёл.
        ArenaManager.close(party.code);
    }

    /**
     * Итоги забега. Собираются до возврата игроков: после него команда уже разошлась по своим
     * местам, а роли на экране должны быть теми, с которыми она играла.
     *
     * <p>Роль в списке — та, что была в забеге; {@code next} — та, что достанется в следующем.
     * Это единственное место, где ротация видна глазами: сама по себе она молча меняет карту
     * ролей, и команда узнавала бы о ней, только открыв лобби.
     */
    private static String summaryJson(MinecraftServer server, Party party) {
        JsonObject root = new JsonObject();
        root.addProperty("code", party.code);
        root.addProperty("difficulty", party.difficulty.id());
        root.addProperty("seconds", party.startedAt == 0L ? 0
                : (int) ((System.currentTimeMillis() - party.startedAt) / 1000L));
        root.addProperty("deaths", party.deaths);
        root.addProperty("reachedEnd", party.reachedEnd);
        root.addProperty("dragonKilled", party.dragonKilled);
        root.addProperty("arena", ArenaManager.of(party.code) != null);
        root.addProperty("rotated", party.rotatedLastRun);

        JsonArray roles = new JsonArray();
        for (Role role : Role.values()) {
            UUID occupant = party.roles.get(role);
            if (occupant == null) {
                continue;
            }
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(occupant);
            JsonObject entry = new JsonObject();
            // Роли уже сдвинуты: на экране показывается «кем он был» и «кем станет».
            entry.addProperty("role", party.rotatedLastRun ? previous(role).id() : role.id());
            entry.addProperty("next", role.id());
            entry.addProperty("uuid", occupant.toString());
            entry.addProperty("name", member == null ? "?" : member.getName().getString());
            roles.add(entry);
        }
        root.add("roles", roles);

        String announceText = TwmConfig.get().announceSummaryText();
        String announceLabel = TwmConfig.get().announceButtonLabel();
        boolean hasButton = !announceLabel.isEmpty()
                && !TwmConfig.get().announceButtonServer().isEmpty();
        if (!announceText.isEmpty() || hasButton) {
            JsonObject announce = new JsonObject();
            announce.addProperty("text", announceText);
            announce.addProperty("label", hasButton ? announceLabel : "");
            root.add("announce", announce);
        }
        return root.toString();
    }

    /** Обратная сторона сдвига «голова → руки → ноги → голова». */
    private static Role previous(Role role) {
        return switch (role) {
            case HANDS -> Role.HEAD;
            case LEGS -> Role.HANDS;
            case HEAD -> Role.LEGS;
        };
    }

    // --- Цикл тика ---------------------------------------------------------

    public static void tick(MinecraftServer server) {
        for (Party party : LobbyManager.playingParties()) {
            ServerPlayerEntity body = server.getPlayerManager().getPlayer(party.memberWithRole(Role.HANDS));
            ServerPlayerEntity legs = server.getPlayerManager().getPlayer(party.memberWithRole(Role.LEGS));
            ServerPlayerEntity head = server.getPlayerManager().getPlayer(party.memberWithRole(Role.HEAD));
            if (body == null || legs == null || head == null) {
                LobbyManager.forceStop(server, party, "twm.chat.stopped_offline");
                continue;
            }
            if (party.deathCycle) {
                finishDeathCycleIfReady(server, party, body, legs, head);
                continue;
            }

            Arena arena = ArenaManager.of(party.code);
            if (arena != null && !party.reachedEnd && body.getServerWorld() == arena.endWorld()) {
                // Цель забега засчитывается по телу: голова и ноги ходят за ним, а «дошли»
                // означает, что через портал прошли руки.
                party.reachedEnd = true;
            }

            moveBodyFromLegs(party, body, legs);
            applyEntityState(server, party, body);
            syncVitality(party, server, body);
            broadcastBodyState(server, party, body, legs);
        }
    }

    /**
     * Дальше этого расхождения тело считается унесённым чем-то внешним: телепортом, порталом.
     * Порог с запасом: в свободном падении ноги проходят почти четыре блока за тик, и более
     * тесная граница срабатывала бы на обычном длинном падении.
     */
    private static final double EXTERNAL_MOVE_THRESHOLD = 8.0D;

    /**
     * Тело повторяет положение ног. Ноги ходят обычным клиентским движением, поэтому коллизии,
     * спринт, прыжок и падение остаются ванильными — сервер не симулирует движение сам и не шлёт
     * ногам корректирующий телепорт.
     */
    private static void moveBodyFromLegs(Party party, ServerPlayerEntity body, ServerPlayerEntity legs) {
        if (legs.getServerWorld() != body.getServerWorld()
                || body.squaredDistanceTo(legs) > EXTERNAL_MOVE_THRESHOLD * EXTERNAL_MOVE_THRESHOLD) {
            // Тело — ведущая сущность команды. Если его перенесло не движением ног, ведомым
            // становится контроллер, а не наоборот: иначе телепорт тела откатывался бы каждый тик.
            legs.teleport(body.getServerWorld(), body.getX(), body.getY(), body.getZ(),
                    legs.getYaw(), legs.getPitch());
            party.legsFallDistance = 0.0F;
            return;
        }

        transferKnockbackToLegs(party, body, legs);
        transferFallDamageToBody(party, body, legs);

        boolean moved = Math.abs(legs.getX() - body.getX()) > 1.0E-4D
                || Math.abs(legs.getY() - body.getY()) > 1.0E-4D
                || Math.abs(legs.getZ() - body.getZ()) > 1.0E-4D;
        if (moved) {
            body.refreshPositionAndAngles(legs.getX(), legs.getY(), legs.getZ(),
                    body.getYaw(), body.getPitch());
            // Без обновления опорной позиции соединения ванильная античит-проверка примерно
            // через десяток блоков начинает отклонять честные пакеты как "moved wrongly".
            body.networkHandler.syncWithPlayerPosition();
        }
        body.setVelocity(Vec3d.ZERO);
        body.setOnGround(legs.isOnGround());
        // Поворот корпуса задают ноги, поворот головы модели остаётся за руками: тело идёт
        // туда, куда ведут ноги, и смотрит туда, куда целятся руки.
        body.setBodyYaw(legs.getYaw());
        body.setSneaking(legs.isSneaking());
        body.setSprinting(legs.isSprinting());
        body.setPose(legs.getPose());

        // Скорость ног сервер не трогает вообще. Движение игрока клиент-авторитетно: любая
        // серверная правка возвращается клиенту пакетом и сбивает разгон — получается
        // скольжение по инерции и заметная задержка на нажатие. Ходьбу, торможение и
        // прыжок целиком считает клиент ног, ванильно.
    }

    /**
     * Отдача от урона приходит в тело, но двигаются ноги. Без переноса взрыв или удар моба
     * просто гасился на месте: тело отбрасывать некуда, а контроллер об ударе не знал.
     */
    private static void transferKnockbackToLegs(Party party, ServerPlayerEntity body,
                                                ServerPlayerEntity legs) {
        if (!body.velocityModified) {
            return;
        }
        body.velocityModified = false;
        Vec3d knockback = body.getVelocity();
        if (knockback.lengthSquared() < 1.0E-4D) {
            return;
        }
        legs.setVelocity(legs.getVelocity().add(knockback));
        // Флаг заставляет сервер отправить ногам обновление скорости, поэтому отдачу
        // доигрывает их собственная ванильная физика со всеми коллизиями.
        legs.velocityModified = true;
    }

    /**
     * Ноги неуязвимы, чтобы весь урон доставался телу, — значит и урон от падения им не
     * приходит. Высота полёта копится по контроллеру, а при приземлении отдаётся телу.
     */
    private static void transferFallDamageToBody(Party party, ServerPlayerEntity body,
                                                 ServerPlayerEntity legs) {
        if (!legs.isOnGround()) {
            party.legsFallDistance = Math.max(party.legsFallDistance, legs.fallDistance);
            return;
        }
        float fallen = party.legsFallDistance;
        party.legsFallDistance = 0.0F;
        if (fallen > 3.0F) {
            body.handleFallDamage(fallen, 1.0F, body.getDamageSources().fall());
        }
    }

    /** Голова висит на теле как якорь камеры; ноги уже находятся в нужной точке сами. */
    private static void broadcastBodyState(MinecraftServer server, Party party,
                                           ServerPlayerEntity body, ServerPlayerEntity legs) {
        TwmPayloads.BodyState state = new TwmPayloads.BodyState(
                body.getX(), body.getY(), body.getZ(), body.isOnGround(), legs.isSneaking(),
                legs.getYaw(), legs.getPitch());
        for (UUID memberId : party.members) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member == null) {
                continue;
            }
            Role role = party.roleOf(memberId);
            if (role == Role.HEAD) {
                if (member.getServerWorld() != body.getServerWorld()) {
                    // Руки ушли в портал. refreshPositionAndAngles измерения не меняет — без
                    // этой ветки голова оставалась бы в прежнем мире на чужих координатах и
                    // смотрела бы в пустоту, пока команда идёт по незеру.
                    member.teleport(body.getServerWorld(), body.getX(), body.getY(), body.getZ(),
                            Set.of(), member.getYaw(), member.getPitch());
                } else {
                    member.refreshPositionAndAngles(body.getX(), body.getY(), body.getZ(),
                            member.getYaw(), member.getPitch());
                    member.networkHandler.syncWithPlayerPosition();
                }
            }
            if (role != Role.LEGS) {
                ServerPlayNetworking.send(member, state);
            }
        }
    }

    /** Руки — единственный источник здоровья и голода; остальные роли просто отражают его. */
    private static void syncVitality(Party party, MinecraftServer server, ServerPlayerEntity body) {
        float health = body.getHealth();
        int food = body.getHungerManager().getFoodLevel();
        float saturation = body.getHungerManager().getSaturationLevel();
        for (UUID memberId : party.members) {
            if (memberId.equals(body.getUuid())) {
                continue;
            }
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member != null && member.isAlive()) {
                member.setHealth(Math.min(health, member.getMaxHealth()));
                member.getHungerManager().setFoodLevel(food);
                member.getHungerManager().setSaturationLevel(saturation);
            }
        }
    }

    private static void applyEntityState(MinecraftServer server, Party party, ServerPlayerEntity body) {
        if (body.interactionManager.getGameMode() != GameMode.SURVIVAL) {
            body.changeGameMode(GameMode.SURVIVAL);
        }
        body.setInvisible(false);
        body.setNoGravity(true);
        for (UUID memberId : party.members) {
            if (memberId.equals(body.getUuid())) {
                continue;
            }
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member == null || !member.isAlive()) {
                continue;
            }
            Role role = party.roleOf(memberId);
            // Приключение вместо выживания: ломание и установка блоков отсекаются самой игрой,
            // а не нашей проверкой. Неуязвимость нужна потому, что тело одно — урон обязан
            // приходить только в руки, включая площадной урон, который не проверяет прицел.
            if (member.interactionManager.getGameMode() != GameMode.ADVENTURE) {
                member.changeGameMode(GameMode.ADVENTURE);
            }
            member.setInvisible(true);
            member.setInvulnerable(true);
            // Ногам нужна обычная гравитация: они физический контроллер движения.
            member.setNoGravity(role != Role.LEGS);
        }
    }

    // --- Общая смерть ------------------------------------------------------

    public static void onDeath(ServerPlayerEntity player) {
        Party party = LobbyManager.partyOf(player.getUuid());
        if (party == null || !party.active || party.deathCycle) {
            return;
        }
        party.deathCycle = true;
        party.deaths++;
        for (UUID memberId : party.members) {
            if (memberId.equals(player.getUuid())) {
                continue;
            }
            ServerPlayerEntity member = player.getServer().getPlayerManager().getPlayer(memberId);
            if (member != null && member.isAlive()) {
                member.setInvulnerable(false);
                member.kill();
            }
        }
    }


    /**
     * Роль пересылается после каждого возрождения. Это важнее, чем кажется: сущности игроков
     * пересоздаются, и клиент обязан заново узнать состав команды — иначе скрытые напарники
     * снова становятся выбираемыми прицелом и руки перестают попадать по блокам.
     */
    public static void onRespawn(ServerPlayerEntity player) {
        Party party = LobbyManager.partyOf(player.getUuid());
        if (party == null) {
            return;
        }
        // Точка возрождения игрока указывает в обычный мир сервера, а команда играет в своей
        // арене: без переноса общая смерть выкидывала бы всех троих из забега наружу.
        if (party.active) {
            Arena arena = ArenaManager.of(party.code);
            if (arena != null) {
                Vec3d spawn = arena.spawn().toBottomCenterPos();
                player.teleport(arena.overworldWorld(), spawn.x, spawn.y, spawn.z, Set.of(),
                        player.getYaw(), 0.0F);
            }
        }
        sendRole(player, party);
    }

    private static void finishDeathCycleIfReady(MinecraftServer server, Party party,
                                                ServerPlayerEntity body, ServerPlayerEntity legs,
                                                ServerPlayerEntity head) {
        if (!body.isAlive() || !legs.isAlive() || !head.isAlive()) {
            return;
        }
        party.deathCycle = false;
        body.setVelocity(Vec3d.ZERO);
        for (ServerPlayerEntity member : List.of(legs, head)) {
            member.teleport(body.getServerWorld(), body.getX(), body.getY(), body.getZ(),
                    body.getYaw(), body.getPitch());
            member.setVelocity(Vec3d.ZERO);
            member.calculateDimensions();
        }
        party.legsFallDistance = 0.0F;
        applyEntityState(server, party, body);
        sendRoleToAll(server, party);
    }

    // --- Ввод и действия ролей --------------------------------------------

    /**
     * Дракон убит в чьей-то арене. Ищем команду по коду арены, а не по убийце: последний удар
     * мог нанести кто угодно из троих, а достижение общее — как и всё остальное в этом режиме.
     */
    public static void onDragonKilled(ServerWorld world) {
        Arena arena = ArenaManager.of(world);
        if (arena == null) {
            return;
        }
        Party party = LobbyManager.byCode(arena.code());
        if (party != null && party.active) {
            party.dragonKilled = true;
        }
    }

    /** Голова выбрасывает предмет вслепую: она не получает ни инвентарь, ни сведения о предмете. */
    public static void dropFromHead(ServerPlayerEntity head) {
        Party party = LobbyManager.partyOf(head.getUuid());
        if (party == null || !party.active || party.roleOf(head.getUuid()) != Role.HEAD) {
            return;
        }
        bodyOf(head).ifPresent(body -> body.dropSelectedItem(false));
    }

    /** Получатели мысли: только ноги той же активной команды. */
    public static List<ServerPlayerEntity> thoughtRecipients(ServerPlayerEntity sender) {
        Party party = LobbyManager.partyOf(sender.getUuid());
        if (party == null || !party.active || party.roleOf(sender.getUuid()) != Role.HEAD) {
            return List.of();
        }
        ServerPlayerEntity legs = sender.getServer().getPlayerManager().getPlayer(party.memberWithRole(Role.LEGS));
        return legs == null ? List.of() : List.of(legs);
    }

    // --- Служебное ---------------------------------------------------------

    private static void sendRoleToAll(MinecraftServer server, Party party) {
        for (UUID memberId : party.members) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberId);
            if (member != null) {
                sendRole(member, party);
            }
        }
    }

    private static void sendRole(ServerPlayerEntity member, Party party) {
        Role role = party.active ? party.roleOf(member.getUuid()) : null;
        Set<UUID> others = new LinkedHashSet<>(party.members);
        others.remove(member.getUuid());
        ServerPlayNetworking.send(member, new TwmPayloads.RoleSync(
                role == null ? "" : role.id(), party.difficulty.id(), party.echoCooldownTicks,
                role == null ? List.of() : List.copyOf(others)));
    }
}
