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

import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.input.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.ClientParty;

/**
 * Двигают тело только ноги. Без этого локальный WASD рук соревнуется с принятым движением ног
 * и общее тело продолжает идти до стены после отпускания клавиш.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void twm$dropMovementForPassiveRoles(boolean slowDown, float slowDownFactor, CallbackInfo info) {
        if (!ClientParty.isHands() && !ClientParty.isHead()) {
            return;
        }
        Input self = (Input) (Object) this;
        self.movementForward = 0.0F;
        self.movementSideways = 0.0F;
        self.pressingForward = false;
        self.pressingBack = false;
        self.pressingLeft = false;
        self.pressingRight = false;
        self.jumping = false;
        self.sneaking = false;
    }
}
