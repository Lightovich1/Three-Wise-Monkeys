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

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.lightovich.twm.party.PartyManager;

/**
 * Голова и ноги — якоря камеры внутри общего тела, а не отдельные цели.
 *
 * <p>Это единственная замена целой связке костылей прошлой версии: сжатию хитбоксов,
 * перенаправлению урона и перехвату попаданий стрел. Пока сущность нельзя выбрать,
 * ни прицел рук, ни снаряд, ни моб до неё не доберутся.
 *
 * <p>Вторая половина невыбираемости — `canHit` — живёт в {@link LivingEntityMixin}:
 * `LivingEntity` перекрывает этот метод и не зовёт `super`, поэтому здесь он бесполезен.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "isAttackable", at = @At("HEAD"), cancellable = true)
    private void twm$hideAnchorFromAttacks(CallbackInfoReturnable<Boolean> info) {
        Entity self = (Entity) (Object) this;
        if (PartyManager.isHiddenAnchor(self.getUuid())) {
            info.setReturnValue(false);
        }
    }

    /**
     * В портал входит только тело. Голова и ноги стоят в тех же блоках, и без запрета все
     * трое обращались бы к порталу порознь: каждый считал бы свою точку выхода, первый строил
     * бы парный портал, остальные искали бы его заново и выходили вразнобой.
     *
     * <p>Их и не надо переносить порталом — они и так ходят за телом: ноги подтягивает
     * {@code moveBodyFromLegs}, голову — {@code broadcastBodyState}, обе проверки уже умеют
     * разные измерения.
     */
    @Inject(method = "canUsePortals", at = @At("HEAD"), cancellable = true)
    private void twm$onlyBodyTravels(boolean allowVehicles, CallbackInfoReturnable<Boolean> info) {
        Entity self = (Entity) (Object) this;
        if (PartyManager.isHiddenAnchor(self.getUuid())) {
            info.setReturnValue(false);
        }
    }

    /**
     * Участники команды стоят друг в друге по замыслу — расталкивать их нельзя.
     *
     * <p>Ваниль каждый тик расталкивает пересекающихся игроков: {@code tickCramming} добавляет
     * обеим сторонам скорость в мировых координатах. Тело и голова тянутся за ногами с
     * запаздыванием, то есть всегда оказываются позади — и толкают ноги вперёд по той же оси,
     * куда те шли. Ноги уезжают дальше, тело снова догоняет и снова толкает: получается
     * самоподдерживающийся дрейф в направлении, зафиксированном в момент нажатия, который
     * не меняется от поворота камеры.
     */
    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void twm$noPushInsideParty(Entity other, CallbackInfo info) {
        Entity self = (Entity) (Object) this;
        if (PartyManager.shareParty(self.getUuid(), other.getUuid())) {
            info.cancel();
        }
    }
}
