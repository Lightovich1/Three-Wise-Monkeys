/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import org.lightovich.twm.Twm;

/**
 * Слепок игрока, снятый перед забегом, и единственный путь обратно.
 *
 * <p>Забег переводит голову и ноги в приключение, делает их невидимыми и неуязвимыми, а с
 * аренами ещё и уносит всех троих в другой мир. Всё это — состояние, которое ваниль сохраняет
 * в {@code playerdata}. Пока забег кончается штатно, снимает его {@code stopGame}; но сервер
 * умеет останавливаться и падать посреди забега, а лобби живёт только в памяти. Без слепка
 * на диске такой игрок возвращается на сервер невидимым неуязвимым призраком в приключении
 * и остаётся им навсегда — команды, которая могла бы его вернуть, уже не существует.
 *
 * <p>Поэтому слепок лежит рядом с миром, а не в памяти, и применяется в трёх местах: на
 * штатной остановке, на остановке сервера и на входе игрока, у которого слепок нашёлся, а
 * команды нет. Восстановленный слепок сразу удаляется — второй раз его применять не к чему.
 *
 * <p>Инвентарь, опыт и положение снимаются только на аренах. В своём мире с друзьями забег
 * идёт там же, где игрок стоял, и трогать его вещи незачем: это не режим со входом и выходом,
 * а способ поиграть в том же мире.
 */
public final class PlayerRestore extends PersistentState {

    private static final String ID = "twm_restore";

    private static final PersistentState.Type<PlayerRestore> TYPE =
            new PersistentState.Type<>(PlayerRestore::new, PlayerRestore::fromNbt, DataFixTypes.LEVEL);

    private final Map<UUID, NbtCompound> records = new HashMap<>();

    private PlayerRestore() {
    }

    /**
     * Слепки лежат в оверворлде: он есть всегда и переживает удаление арен. Класть их в мир
     * забега нельзя — этот мир и удаляется вместе с забегом.
     */
    public static PlayerRestore of(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, ID);
    }

    private static PlayerRestore fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        PlayerRestore state = new PlayerRestore();
        for (String key : nbt.getKeys()) {
            try {
                state.records.put(UUID.fromString(key), nbt.getCompound(key));
            } catch (IllegalArgumentException notUuid) {
                Twm.LOGGER.warn("Слепок с нечитаемым ключом пропущен: {}", key);
            }
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        records.forEach((playerId, record) -> nbt.put(playerId.toString(), record));
        return nbt;
    }

    public boolean has(UUID playerId) {
        return records.containsKey(playerId);
    }

    /**
     * Снять слепок. Повторный вызов для того же игрока ничего не делает: первый слепок описывает
     * состояние <b>до</b> забега, а второй описал бы уже изменённое модом.
     *
     * @param full снимать ли инвентарь, опыт и положение — верно только на аренах
     */
    public void capture(ServerPlayerEntity player, boolean full) {
        if (records.containsKey(player.getUuid())) {
            return;
        }
        NbtCompound record = new NbtCompound();
        record.putString("gamemode", player.interactionManager.getGameMode().asString());
        record.putBoolean("invisible", player.isInvisible());
        record.putBoolean("invulnerable", player.isInvulnerable());
        record.putBoolean("noGravity", player.hasNoGravity());
        record.putBoolean("full", full);
        if (full) {
            record.putString("world", player.getServerWorld().getRegistryKey().getValue().toString());
            record.putDouble("x", player.getX());
            record.putDouble("y", player.getY());
            record.putDouble("z", player.getZ());
            record.putFloat("yaw", player.getYaw());
            record.putFloat("pitch", player.getPitch());
            record.put("inventory", player.getInventory().writeNbt(new NbtList()));
            record.putInt("xpLevel", player.experienceLevel);
            record.putFloat("xpProgress", player.experienceProgress);
            record.putInt("xpTotal", player.totalExperience);
            record.putFloat("health", player.getHealth());
            record.putInt("food", player.getHungerManager().getFoodLevel());
            record.putFloat("saturation", player.getHungerManager().getSaturationLevel());
        }
        records.put(player.getUuid(), record);
        markDirty();
    }

    /**
     * Вернуть игрока и стереть слепок. Возвращает {@code false}, если слепка не было — это
     * законно: игрок мог зайти, ни разу не игравший.
     */
    public boolean restore(ServerPlayerEntity player) {
        NbtCompound record = records.remove(player.getUuid());
        if (record == null) {
            return false;
        }
        markDirty();

        GameMode mode = GameMode.byName(record.getString("gamemode"), GameMode.SURVIVAL);
        if (player.interactionManager.getGameMode() != mode) {
            player.changeGameMode(mode);
        }
        player.setInvisible(record.getBoolean("invisible"));
        player.setInvulnerable(record.getBoolean("invulnerable"));
        player.setNoGravity(record.getBoolean("noGravity"));
        player.calculateDimensions();

        if (!record.getBoolean("full")) {
            return true;
        }
        player.getInventory().clear();
        player.getInventory().readNbt(record.getList("inventory", NbtElement.COMPOUND_TYPE));
        player.setExperienceLevel(record.getInt("xpLevel"));
        player.setExperiencePoints(0);
        player.experienceProgress = record.getFloat("xpProgress");
        player.totalExperience = record.getInt("xpTotal");
        player.setHealth(Math.max(1.0F, record.getFloat("health")));
        player.getHungerManager().setFoodLevel(record.getInt("food"));
        player.getHungerManager().setSaturationLevel(record.getFloat("saturation"));

        ServerWorld world = worldOf(player.getServer(), record.getString("world"));
        if (world != null) {
            player.teleport(world, record.getDouble("x"), record.getDouble("y"), record.getDouble("z"),
                    Set.of(), record.getFloat("yaw"), record.getFloat("pitch"));
        } else {
            // Мир исчез вместе с датапаком или ареной: спавн — единственная точка, про которую
            // сервер знает наверняка. Лучше выйти на спавне, чем застрять в несуществующем мире.
            Twm.LOGGER.warn("Мир {} для возврата не найден, отправляю на спавн",
                    record.getString("world"));
            ServerWorld overworld = player.getServer().getOverworld();
            player.teleport(overworld, overworld.getSpawnPos().getX() + 0.5,
                    overworld.getSpawnPos().getY(), overworld.getSpawnPos().getZ() + 0.5,
                    Set.of(), 0.0F, 0.0F);
        }
        return true;
    }

    /** Слепок больше не нужен, а применять его не к кому: игрок офлайн и вернётся не скоро. */
    public void drop(UUID playerId) {
        if (records.remove(playerId) != null) {
            markDirty();
        }
    }

    private static ServerWorld worldOf(MinecraftServer server, String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return null;
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, identifier);
        return server.getWorld(key);
    }
}
