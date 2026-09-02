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

import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.gen.feature.EndPlatformFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.lightovich.twm.server.arena.Arena;
import org.lightovich.twm.server.arena.ArenaManager;

/**
 * Портал в Энд внутри арены ведёт в Энд той же команды, а обратно — на её же спавн.
 *
 * <p>Здесь, в отличие от портала в незер, путь переписан целиком, а не двумя подменами.
 * Причина в обратной дороге: ваниль возвращает из Энда не в «оверворлд», а в точку
 * возрождения игрока, а она указывает в обычный мир сервера — то есть выбросила бы команду
 * из арены наружу посреди забега. Точка входа в Энд при этом ванильная: та же обсидиановая
 * платформа на {@code END_SPAWN_POS}, построенная тем же ванильным способом.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {

    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void twm$arenaEndPortal(ServerWorld world, Entity entity, BlockPos pos,
                                    CallbackInfoReturnable<TeleportTarget> info) {
        Arena arena = ArenaManager.of(world);
        if (arena == null) {
            return;
        }
        if (world == arena.endWorld()) {
            info.setReturnValue(new TeleportTarget(arena.overworldWorld(),
                    arena.spawn().toBottomCenterPos(), Vec3d.ZERO,
                    entity.getYaw(), entity.getPitch(),
                    TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET));
            return;
        }
        ServerWorld end = arena.endWorld();
        BlockPos platform = ServerWorld.END_SPAWN_POS;
        EndPlatformFeature.generate(end, platform.down(), true);
        info.setReturnValue(new TeleportTarget(end, platform.toBottomCenterPos(), Vec3d.ZERO,
                Direction.WEST.asRotation(), 0.0F,
                TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET));
    }
}
