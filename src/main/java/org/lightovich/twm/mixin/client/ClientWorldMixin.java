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

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.perception.Echolocation;

/**
 * Изменение блока в мире — повод починить память эхолокации.
 *
 * <p>Точка выбрана одна на все источники: и пакет с сервера, и предсказание собственного
 * ломания приходят в {@code World#setBlockState}, а тот зовёт {@code updateListeners}.
 * Загрузка чанка сюда не попадает — она меняет секцию целиком, — поэтому залпов на сотни
 * блоков здесь не бывает, а значит и стоимость подписки не растёт с дальностью прорисовки.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    @Inject(method = "updateListeners", at = @At("TAIL"))
    private void twm$onBlockChanged(BlockPos position, BlockState oldState, BlockState newState,
                                    int flags, CallbackInfo info) {
        if (oldState != newState) {
            Echolocation.onBlockChanged((ClientWorld) (Object) this, position);
        }
    }
}
