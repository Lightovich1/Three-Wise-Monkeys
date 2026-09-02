/*
 * Three Wise Monkeys — кооперативный мод для Minecraft.
 * Copyright (C) 2026 Lightovich
 *
 * Свободная программа: распространяется и изменяется на условиях GNU Lesser General
 * Public License версии 3 или любой более поздней, опубликованной Free Software
 * Foundation. Поставляется без каких-либо гарантий. Текст лицензии — файл LICENSE,
 * он же https://www.gnu.org/licenses/lgpl-3.0.html
 */
package org.lightovich.twm.client.cef.install;

import java.util.Locale;
import java.util.Map;

/**
 * Контрольные суммы бинарей Chromium, вшитые в сборку.
 *
 * <p>Мод скачивает 128 МБ нативного кода и грузит его в свою же JVM. До сих пор сумма для
 * сверки бралась с того же зеркала, что и архив, — то есть подменивший зеркало подменял и
 * эталон, а сверка ничего не значила. Хуже: для Windows файл сумм отдаётся таблицей
 * PowerShell ({@code Algorithm/Hash/Path}), разбор её не понимал, суммы «не было», и архив
 * принимался по одной лишь целостности gzip. То есть на Windows проверки не было вовсе.
 *
 * <p>Вкладывать суммы в мод можно потому, что сборка java-cef прибита к сборке мода: MCEF
 * лежит внутри jar, его версия фиксирована, а коммит java-cef — часть этой версии. Пока
 * коммит совпадает с {@link #PINNED_COMMIT}, эталон берётся отсюда и сеть на него не влияет.
 *
 * <p>При обновлении MCEF коммит меняется — тогда суммы неизвестны, и мод честно откатывается
 * к прежнему поведению (эталон с зеркала), громко предупреждая в лог. Обновляя MCEF, суммы
 * надо обновить здесь же:
 * {@code curl <зеркало>/java-cef-builds/<коммит>/<платформа>.tar.gz.sha256}.
 */
public final class CefHashes {

    /** Сборка java-cef, к которой прибит вложенный MCEF 2.1.6-1.21.1. */
    public static final String PINNED_COMMIT = "a78e832f9f13c2c688caea3d04d8b84fcd238d94";

    private static final Map<String, String> SHA256 = Map.of(
            "windows_amd64", "e98c385542620f31a594d6fc3c38ed6bca8a547e24ced982a1339bb3333668be",
            "windows_arm64", "9af97d3274976547db58d9fd8db0f8415b855f4f8bd545eba227a20c45a5d353",
            "linux_amd64", "426bf70ccc65cbcb752e4c8015eae032d8631e6d406278605223d07d444bf917",
            "linux_arm64", "9bcf97d13e37dd62de8f29f21f5b330acc9134daccf1de34d60b4c4530c9175b",
            "macos_amd64", "dbaf30cd572d36a3c7aae9092b0cb48a7bce00d781fb082492e2930ee20c3c2c",
            "macos_arm64", "18d986c84258ad58e01266665e8f21159a5a01e1cc59a9f027c216864cec8714");

    private CefHashes() {
    }

    /** Эталон для платформы, если сборка java-cef та же, что вшита. Иначе {@code null}. */
    public static String of(String platform, String commit) {
        if (platform == null || commit == null || !PINNED_COMMIT.equalsIgnoreCase(commit.trim())) {
            return null;
        }
        return SHA256.get(platform.toLowerCase(Locale.ROOT));
    }
}
