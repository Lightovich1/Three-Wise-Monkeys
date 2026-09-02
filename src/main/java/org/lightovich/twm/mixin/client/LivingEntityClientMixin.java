/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.mixin.client;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.lightovich.twm.client.ClientParty;

/**
 * Клиентская половина невыбираемости, и решающая для рук: прицел наводится клиентским лучом
 * ({@code GameRenderer.updateTargetedEntity} спрашивает ровно {@code canHit}). Пока напарник
 * оставался выбираемым, крестовина рук держалась за невидимую сущность, а не за блок — ломать
 * было нечего, а удар уходил по своим и отбивался сервером.
 *
 * <p>Цель — {@code LivingEntity}: у него собственный `canHit` без вызова `super`.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityClientMixin {

    @Inject(method = "canHit", at = @At("HEAD"), cancellable = true)
    private void twm$hideTeammateFromCrosshair(CallbackInfoReturnable<Boolean> info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.getWorld().isClient() && ClientParty.isHiddenTeammate(self.getUuid())) {
            info.setReturnValue(false);
        }
    }
}
