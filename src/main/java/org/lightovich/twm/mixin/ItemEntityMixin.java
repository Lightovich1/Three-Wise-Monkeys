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

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.party.PartyManager;

/**
 * Предмет с земли принадлежит общему инвентарю рук. Три сущности команды стоят в одной точке,
 * поэтому касание головы или ног может оказаться первым за тик — его нужно не отменить,
 * а передать телу, иначе предмет просто застревает под ногами.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void twm$redirectPickupToBody(PlayerEntity player, CallbackInfo info) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)
                || PartyManager.canInteract(serverPlayer.getUuid())) {
            return;
        }
        info.cancel();
        PartyManager.bodyOf(serverPlayer).ifPresent(body -> {
            ItemEntity self = (ItemEntity) (Object) this;
            if (self.isAlive()) {
                self.onPlayerCollision(body);
            }
        });
    }
}
