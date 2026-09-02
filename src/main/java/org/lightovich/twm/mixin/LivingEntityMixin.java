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

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.lightovich.twm.party.PartyManager;

/**
 * Голова и ноги невыбираемы прицелом и снарядом.
 *
 * <p>**Цель именно {@code LivingEntity}, а не {@code Entity}.** `LivingEntity.canHit()`
 * перекрывает `Entity.canHit()` и не зовёт `super`, поэтому миксин на `Entity` для игроков
 * не срабатывал вообще: вся невыбираемость была мёртвым кодом. Полноразмерный невидимый
 * напарник перехватывал луч прицела рук — отсюда «руки не ломают блоки и не бьют мобов»
 * (луч упирался в сущность, а не в блок, а удар по своим запрещён) и отскакивающие стрелы
 * ({@code Entity.canBeHitByProjectile} тоже спрашивает `canHit`, а неуязвимую цель снаряд
 * не пробивает, а отбрасывает).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "canHit", at = @At("HEAD"), cancellable = true)
    private void twm$hideAnchorFromRaycast(CallbackInfoReturnable<Boolean> info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (PartyManager.isHiddenAnchor(self.getUuid())) {
            info.setReturnValue(false);
        }
    }
}
