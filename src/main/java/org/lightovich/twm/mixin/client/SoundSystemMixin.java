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

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lightovich.twm.client.perception.SoundRadar;

/**
 * Единственная точка, где звук становится слышимым, — и потому единственная, где его можно
 * и показать рукам, и отнять у ног.
 *
 * <p>Глухота ног была неполной: голос сервер глушил отменой пакетов, а мир звучал по-прежнему.
 * Отбор здесь общий с кольцом рук — что руки видят дугой, то ноги не слышат вовсе. Обратная
 * связь самих ног (сонарный импульс, волна) идёт категорией {@code MASTER} и под отбор не
 * попадает: отнять у глухого его собственное чувство было бы уже не ролью, а поломкой.
 */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("HEAD"),
            cancellable = true)
    private void twm$perceive(SoundInstance instance, CallbackInfo info) {
        if (SoundRadar.muffledForLegs(instance)) {
            info.cancel();
            return;
        }
        SoundRadar.record(instance);
    }
}
