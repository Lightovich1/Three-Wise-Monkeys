/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.mixin;

import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import org.lightovich.twm.server.arena.Arena;
import org.lightovich.twm.server.arena.ArenaManager;

/**
 * Портал в незер внутри арены ведёт в незер той же команды.
 *
 * <p>Ваниль вычисляет измерение по ключу мира: «я не незер, значит иду в незер». У миров
 * арены ключи свои, поэтому из арены портал вывел бы команду в общий незер сервера — то
 * есть ровно туда, где забеги встречаются.
 *
 * <p>Переписан не результат, а два вопроса, которые ваниль задаёт по дороге: «какое я
 * измерение» и «дай мир по ключу». Всё остальное — поиск парного портала, создание нового,
 * сжатие координат 1:8, зажим по границе — остаётся ванильным. Своя реализация здесь
 * означала бы переписать {@code PortalForcer}, а он не про арены.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    /**
     * Мир арены отвечает ключом того измерения, которым он является. Перехват накрывает оба
     * вопроса в методе: и «откуда идём» (иначе из незера арены портал вёл бы снова в незер),
     * и «куда пришли» (иначе ваниль сочла бы незер арены оверворлдом и искала бы выход не тем
     * радиусом).
     */
    @Redirect(method = "createTeleportTarget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;getRegistryKey()"
                    + "Lnet/minecraft/registry/RegistryKey;"))
    private RegistryKey<World> twm$arenaPretendsVanilla(ServerWorld world) {
        return ArenaManager.vanillaKey(world);
    }

    @Redirect(method = "createTeleportTarget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getWorld(Lnet/minecraft/registry/RegistryKey;)"
                    + "Lnet/minecraft/server/world/ServerWorld;"))
    private ServerWorld twm$arenaDestination(MinecraftServer server, RegistryKey<World> key,
                                             ServerWorld source, Entity entity, BlockPos pos) {
        Arena arena = ArenaManager.of(source);
        return arena == null ? server.getWorld(key) : ArenaManager.worldFor(arena, key);
    }
}
