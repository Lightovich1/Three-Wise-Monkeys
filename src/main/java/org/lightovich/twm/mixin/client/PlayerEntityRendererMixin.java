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

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.ClientParty;

/**
 * Напарники по общему телу не рисуются.
 *
 * <p>Все трое стоят в одной точке, поэтому без этого голова смотрит изнутри модели рук и не
 * видит ничего, кроме собственного тела. Серверная невидимость тут не помогает: тело рук —
 * настоящий видимый персонаж, скрывать его нужно только для своих же ролей.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), cancellable = true)
    private void twm$hideTeammates(AbstractClientPlayerEntity player, float yaw, float tickDelta,
                                   MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                   int light, CallbackInfo info) {
        if (ClientParty.isHiddenTeammate(player.getUuid())) {
            info.cancel();
        }
    }
}
