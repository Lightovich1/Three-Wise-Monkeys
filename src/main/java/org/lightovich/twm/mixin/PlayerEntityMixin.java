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

import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.lightovich.twm.Role;
import org.lightovich.twm.party.PartyManager;

/**
 * Голова — неподвижный якорь камеры: её полноразмерный хитбокс упирался бы в пол и в потолок
 * общего тела. Ноги, наоборот, сохраняют обычные размеры — они физический контроллер движения
 * и должны честно проходить проёмы.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    private static final EntityDimensions TWM$ANCHOR = EntityDimensions.fixed(0.01F, 0.01F);

    /**
     * Голова и ноги не роняют вещи на общей смерти. Разбор — в
     * {@link PartyManager#keepsBelongingsOnDeath}.
     */
    @Inject(method = "dropInventory", at = @At("HEAD"), cancellable = true)
    private void twm$anchorsKeepInventory(CallbackInfo info) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (PartyManager.keepsBelongingsOnDeath(self.getUuid())) {
            info.cancel();
        }
    }

    /** Опыт по той же причине: он тоже не был потерян в бою, потому что боя у них не было. */
    @Inject(method = "getXpToDrop", at = @At("HEAD"), cancellable = true)
    private void twm$anchorsKeepExperience(CallbackInfoReturnable<Integer> info) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (PartyManager.isHiddenAnchor(self.getUuid())) {
            info.setReturnValue(0);
        }
    }

    @Inject(method = "getBaseDimensions", at = @At("HEAD"), cancellable = true)
    private void twm$shrinkHeadAnchor(EntityPose pose, CallbackInfoReturnable<EntityDimensions> info) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (PartyManager.roleOf(self.getUuid()).orElse(null) == Role.HEAD
                && PartyManager.isInActiveParty(self.getUuid())) {
            info.setReturnValue(TWM$ANCHOR);
        }
    }
}
