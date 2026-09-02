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

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.ClientParty;

/**
 * Клиентская половина серверных правил модели тела. Невыбираемость прицелом переехала в
 * {@link LivingEntityClientMixin} — у `LivingEntity` свой `canHit` без вызова `super`.
 */
@Mixin(Entity.class)
public abstract class EntityClientMixin {

    /**
     * Расталкивание считает каждая сторона у себя, и решающая здесь — клиентская: движение
     * игрока клиент-авторитетно, поэтому дрейф ног рождался именно на их клиенте. Серверная
     * проверка о составе команды тут недоступна, зеркало состояния знает ровно то же самое.
     */
    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void twm$noPushInsideParty(Entity other, CallbackInfo info) {
        Entity self = (Entity) (Object) this;
        if (self.getWorld().isClient() && twm$inLocalParty(self) && twm$inLocalParty(other)) {
            info.cancel();
        }
    }

    private static boolean twm$inLocalParty(Entity entity) {
        if (entity == MinecraftClient.getInstance().player) {
            return ClientParty.inParty();
        }
        return ClientParty.isHiddenTeammate(entity.getUuid());
    }
}
