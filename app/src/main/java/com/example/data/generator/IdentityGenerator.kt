package com.example.data.generator

import com.example.data.model.DeviceIdentity
import java.util.UUID
import kotlin.random.Random

object IdentityGenerator {

    data class DevicePreset(
        val manufacturer: String,
        val model: String,
        val buildFingerprintTpl: String,
        val serialPrefix: String,
        val display: String,
        val uaTpl: String,
        val tacPrefix: String,
        val buildNumber: String = "UP1A.231005.007",
        val deviceId: String = "marlin",
        val kernelVersion: String = "5.15.110-android14-11-g89a3f2b",
        val socHardware: String = "Snapdragon 8 Gen 3 (SM8650-AB)",
        val gpuRenderer: String = "Adreno (TM) 750 @ 1000MHz",
        val basebandVersion: String = "g5300g-240301-240502-B-11812984",
        val osVersion: String = "Android 14 (SDK 34) · Patch: 2024-08-01"
    )

    private data class LocationPreset(
        val cityCountry: String,
        val timezone: String,
        val carrier: String,
        val lat: Double,
        val lng: Double,
        val ipSubnet: String,
        val countryCode: String,
        val regionName: String,
        val localeTag: String,
        val mccMnc: String,
        val iccidPrefix: String
    )

    // Tracks recently used models so the app NEVER repeats recent devices
    private val recentModelsQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private const val MAX_RECENT_MODELS = 15

    val devicePresets = listOf(
        // Google Pixel
        DevicePreset(
            manufacturer = "Google",
            model = "Pixel 9 Pro XL",
            buildFingerprintTpl = "google/komodo/komodo:15/AP1A.241105.007/12548231:user/release-keys",
            serialPrefix = "4A211FD",
            display = "1344x2992 @ 486dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro XL Build/AP1A.241105.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "358742",
            buildNumber = "AP1A.241105.007",
            deviceId = "komodo",
            kernelVersion = "6.1.75-android15-11-g7c82e91",
            socHardware = "Google Tensor G4 (Google G5400)",
            gpuRenderer = "Mali-G715 MC10",
            basebandVersion = "g5400-240801-B-12185492",
            osVersion = "Android 15 (SDK 35) · Patch: 2024-11-01"
        ),
        DevicePreset(
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            buildFingerprintTpl = "google/husky/husky:14/UD1A.230803.041/10808477:user/release-keys",
            serialPrefix = "3A141FD",
            display = "1344x2992 @ 489dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UD1A.230803.041; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "353678",
            buildNumber = "UD1A.230803.041",
            deviceId = "husky",
            kernelVersion = "5.15.110-android14-11-g89a3f2b",
            socHardware = "Google Tensor G3 (Google G5300)",
            gpuRenderer = "Mali-G715s",
            basebandVersion = "g5300-230901-B-10812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        ),
        DevicePreset(
            manufacturer = "Google",
            model = "Pixel 8a",
            buildFingerprintTpl = "google/akita/akita:14/UD2A.240505.001.B1/11649987:user/release-keys",
            serialPrefix = "3A291FD",
            display = "1080x2400 @ 430dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; Pixel 8a Build/UD2A.240505.001.B1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "356781",
            buildNumber = "UD2A.240505.001.B1",
            deviceId = "akita",
            kernelVersion = "5.15.110-android14-11-g89a3f2b",
            socHardware = "Google Tensor G3 (Google G5300)",
            gpuRenderer = "Mali-G715s",
            basebandVersion = "g5300-240401-B-11492984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-05-01"
        ),
        DevicePreset(
            manufacturer = "Google",
            model = "Pixel 7 Pro",
            buildFingerprintTpl = "google/cheetah/cheetah:14/UP1A.231005.007/10754064:user/release-keys",
            serialPrefix = "2B091FD",
            display = "1440x3120 @ 512dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7512.98 Mobile Safari/537.36",
            tacPrefix = "354921",
            buildNumber = "UP1A.231005.007",
            deviceId = "cheetah",
            kernelVersion = "5.10.149-android14-9-g4f01ca8",
            socHardware = "Google Tensor G2 (Google GS201)",
            gpuRenderer = "Mali-G710 MC10",
            basebandVersion = "g5201-230801-B-10649987",
            osVersion = "Android 13 (SDK 33) · Patch: 2023-09-01"
        ),

        // Samsung Galaxy
        DevicePreset(
            manufacturer = "Samsung",
            model = "Galaxy S24 Ultra SM-S928B",
            buildFingerprintTpl = "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AXB5:user/release-keys",
            serialPrefix = "R5CN80A",
            display = "1440x3120 @ 505dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; SM-S928B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "352931",
            buildNumber = "S928BXXU1AXB5",
            deviceId = "e3q",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "Snapdragon 8 Gen 3 for Galaxy (SM8650-AC)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "g5300g-240301-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-09-01"
        ),
        DevicePreset(
            manufacturer = "Samsung",
            model = "Galaxy S24+ SM-S926B",
            buildFingerprintTpl = "samsung/e2sxxx/e2s:14/UP1A.231005.007/S926BXXU1AXB5:user/release-keys",
            serialPrefix = "R5CW10M",
            display = "1440x3120 @ 513dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; SM-S926B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "354128",
            buildNumber = "S926BXXU1AXB5",
            deviceId = "e2s",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "Exynos 2400 (S5E9945)",
            gpuRenderer = "Xclipse 940 (AMD RDNA 3)",
            basebandVersion = "shannon5300-240201-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        ),
        DevicePreset(
            manufacturer = "Samsung",
            model = "Galaxy S23 Ultra SM-S918B",
            buildFingerprintTpl = "samsung/dm3qxxx/dm3q:14/UP1A.231005.007/S918BXXS3BWL3:user/release-keys",
            serialPrefix = "R5CT90P",
            display = "1440x3088 @ 500dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7512.98 Mobile Safari/537.36",
            tacPrefix = "357289",
            buildNumber = "S918BXXS3BWL3",
            deviceId = "dm3q",
            kernelVersion = "5.15.78-android14-5-g8712aa1",
            socHardware = "Snapdragon 8 Gen 2 for Galaxy (SM8550-AC)",
            gpuRenderer = "Adreno (TM) 740 @ 680MHz",
            basebandVersion = "g5300b-230901-B-11213421",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-03-01"
        ),
        DevicePreset(
            manufacturer = "Samsung",
            model = "Galaxy Z Fold 6 SM-F956B",
            buildFingerprintTpl = "samsung/q6xxx/q6:14/UP1A.231005.007/F956BXXU1AXFB:user/release-keys",
            serialPrefix = "R5CR70K",
            display = "1856x2160 @ 374dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; SM-F956B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "359142",
            buildNumber = "F956BXXU1AXFB",
            deviceId = "q6",
            kernelVersion = "6.1.55-android14-9-g3918aa0",
            socHardware = "Snapdragon 8 Gen 3 for Galaxy (SM8650-AC)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "g5300g-240501-B-11910243",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-10-01"
        ),
        DevicePreset(
            manufacturer = "Samsung",
            model = "Galaxy A55 5G SM-A556B",
            buildFingerprintTpl = "samsung/a55xxx/a55:14/UP1A.231005.007/A556BXXU1AXB8:user/release-keys",
            serialPrefix = "R5CX20L",
            display = "1080x2340 @ 390dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; SM-A556B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7512.98 Mobile Safari/537.36",
            tacPrefix = "351829",
            buildNumber = "A556BXXU1AXB8",
            deviceId = "a55",
            kernelVersion = "5.15.102-android14-7-gaa9021c",
            socHardware = "Exynos 1480 (S5E8845)",
            gpuRenderer = "Xclipse 530 (AMD RDNA 2)",
            basebandVersion = "shannon5100-240101-B-11612984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-07-01"
        ),

        // OnePlus
        DevicePreset(
            manufacturer = "OnePlus",
            model = "OnePlus 12 CPH2581",
            buildFingerprintTpl = "OnePlus/CPH2581/OP595DL1:14/UP1A.231005.007/T.187311_1:user/release-keys",
            serialPrefix = "1P98AF3",
            display = "1440x3168 @ 510dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; CPH2581 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7512.98 Mobile Safari/537.36",
            tacPrefix = "867492",
            buildNumber = "CPH2581_14.0.0.604",
            deviceId = "OP595DL1",
            kernelVersion = "6.1.68-android14-10-ga88231b",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240101-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-06-01"
        ),
        DevicePreset(
            manufacturer = "OnePlus",
            model = "OnePlus Open CPH2551",
            buildFingerprintTpl = "OnePlus/CPH2551/OP5929L1:14/UP1A.231005.007/T.180429_1:user/release-keys",
            serialPrefix = "1P87BF9",
            display = "2268x2440 @ 426dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; CPH2551 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "869134",
            buildNumber = "CPH2551_14.0.0.501",
            deviceId = "OP5929L1",
            kernelVersion = "5.15.110-android14-11-g89a3f2b",
            socHardware = "Snapdragon 8 Gen 2 (SM8550-AB)",
            gpuRenderer = "Adreno (TM) 740 @ 680MHz",
            basebandVersion = "q_g5200-230901-B-11213421",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-02-01"
        ),
        DevicePreset(
            manufacturer = "OnePlus",
            model = "OnePlus Nord 4 CPH2661",
            buildFingerprintTpl = "OnePlus/CPH2661/OP5D4DL1:14/UP1A.231005.007/T.192384_1:user/release-keys",
            serialPrefix = "1P44AF1",
            display = "1240x2772 @ 450dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; CPH2661 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "863381",
            buildNumber = "CPH2661_14.1.0.300",
            deviceId = "OP5D4DL1",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "Snapdragon 7+ Gen 3 (SM7675-AB)",
            gpuRenderer = "Adreno (TM) 732 @ 950MHz",
            basebandVersion = "q_g5250-240501-B-11492984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        ),

        // Xiaomi & POCO & Redmi
        DevicePreset(
            manufacturer = "Xiaomi",
            model = "Xiaomi 14 Ultra 24030PN60G",
            buildFingerprintTpl = "Xiaomi/aurora_global/aurora:14/UKQ1.231003.002/OS1.0.8.0.UNAMIXM:user/release-keys",
            serialPrefix = "XM982C9",
            display = "1440x3200 @ 522dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; 24030PN60G Build/UKQ1.231003.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "860472",
            buildNumber = "OS1.0.8.0.UNAMIXM",
            deviceId = "aurora",
            kernelVersion = "6.1.55-android14-9-g3918aa0",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240401-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-09-01"
        ),
        DevicePreset(
            manufacturer = "Xiaomi",
            model = "POCO F6 Pro 23113RKC6G",
            buildFingerprintTpl = "POCO/vermeer_global/vermeer:14/UKQ1.230804.001/OS1.0.4.0.UNNMIXM:user/release-keys",
            serialPrefix = "PC771K2",
            display = "1440x3200 @ 526dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; 23113RKC6G Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "865518",
            buildNumber = "OS1.0.4.0.UNNMIXM",
            deviceId = "vermeer",
            kernelVersion = "5.15.110-android14-11-g89a3f2b",
            socHardware = "Snapdragon 8 Gen 2 (SM8550-AB)",
            gpuRenderer = "Adreno (TM) 740 @ 680MHz",
            basebandVersion = "q_g5200-230901-B-11213421",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-07-01"
        ),
        DevicePreset(
            manufacturer = "Xiaomi",
            model = "Redmi Note 13 Pro+ 23090RA98G",
            buildFingerprintTpl = "Redmi/zircon_global/zircon:14/UP1A.231005.007/OS1.0.3.0.UNOEUXM:user/release-keys",
            serialPrefix = "RD554K8",
            display = "1220x2712 @ 446dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; 23090RA98G Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7512.98 Mobile Safari/537.36",
            tacPrefix = "869401",
            buildNumber = "OS1.0.3.0.UNOEUXM",
            deviceId = "zircon",
            kernelVersion = "5.10.168-android14-6-g2910fa8",
            socHardware = "MediaTek Dimensity 7200 Ultra (MT6886)",
            gpuRenderer = "Mali-G610 MC4",
            basebandVersion = "m_g6886-240101-B-11212984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-04-01"
        ),

        // Vivo & iQOO
        DevicePreset(
            manufacturer = "Vivo",
            model = "Vivo X100 Pro V2324A",
            buildFingerprintTpl = "vivo/PD2324/PD2324:14/UP1A.231005.007/compiler03111450:user/release-keys",
            serialPrefix = "VV9082X",
            display = "1260x2800 @ 453dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; V2324A Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "868214",
            buildNumber = "PD2324_A_14.0.12.0",
            deviceId = "PD2324",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "MediaTek Dimensity 9300 (MT6989)",
            gpuRenderer = "Immortalis-G720 MC12",
            basebandVersion = "m_g6989-240201-B-11612984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-05-01"
        ),
        DevicePreset(
            manufacturer = "Vivo",
            model = "iQOO 12 I2220",
            buildFingerprintTpl = "vivo/PD2307/PD2307:14/UP1A.231005.007/compiler12251140:user/release-keys",
            serialPrefix = "IQ7782A",
            display = "1260x2800 @ 453dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; I2220 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "864491",
            buildNumber = "PD2307_A_14.0.10.0",
            deviceId = "PD2307",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240101-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-06-01"
        ),

        // OPPO & Realme
        DevicePreset(
            manufacturer = "OPPO",
            model = "OPPO Find X7 Ultra PHY110",
            buildFingerprintTpl = "OPPO/PHY110/OP5E2BL1:14/UP1A.231005.007/S.1783912_1:user/release-keys",
            serialPrefix = "OP8A951X",
            display = "1440x3168 @ 510dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; PHY110 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7512.98 Mobile Safari/537.36",
            tacPrefix = "861934",
            buildNumber = "PHY110_14.0.1.301",
            deviceId = "PHY110",
            kernelVersion = "6.1.55-android14-9-g3918aa0",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240301-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-07-01"
        ),
        DevicePreset(
            manufacturer = "realme",
            model = "realme GT5 Pro RMX3888",
            buildFingerprintTpl = "realme/RMX3888/RE59F4L1:14/UP1A.231005.007/S.1752839:user/release-keys",
            serialPrefix = "RMX450A0",
            display = "1264x2780 @ 450dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; RMX3888 Build/UP1A.240801.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "863920",
            buildNumber = "RMX3888_14.0.0.700",
            deviceId = "RMX3888",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240101-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-06-01"
        ),

        // Motorola & Sony & ASUS
        DevicePreset(
            manufacturer = "Motorola",
            model = "Motorola Edge 50 Ultra XT2401-1",
            buildFingerprintTpl = "motorola/macan_g/macan:14/U1UHS34.29-65-3/12c1b:user/release-keys",
            serialPrefix = "MT8811A",
            display = "1220x2712 @ 446dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; XT2401-1 Build/U1UHS34.29-65-3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "990016",
            buildNumber = "U1UHS34.29-65-3",
            deviceId = "macan",
            kernelVersion = "6.1.25-android14-6-g11a289f",
            socHardware = "Snapdragon 8s Gen 3 (SM8635)",
            gpuRenderer = "Adreno (TM) 735 @ 1100MHz",
            basebandVersion = "q_g5280-240401-B-11492984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        ),
        DevicePreset(
            manufacturer = "Sony",
            model = "Sony Xperia 1 VI XQ-EC54",
            buildFingerprintTpl = "Sony/XQ-EC54/XQ-EC54:14/69.0.A.2.32/069000A002003204018899140:user/release-keys",
            serialPrefix = "SN9930A",
            display = "1080x2340 @ 396dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; XQ-EC54 Build/69.0.A.2.32; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "358190",
            buildNumber = "69.0.A.2.32",
            deviceId = "XQ-EC54",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240401-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        ),
        DevicePreset(
            manufacturer = "ASUS",
            model = "ROG Phone 8 Pro AI2401",
            buildFingerprintTpl = "asus/WW_AI2401/ASUS_AI2401:14/34.1420.1420.316/316:user/release-keys",
            serialPrefix = "AS772X9",
            display = "1080x2400 @ 388dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; ASUS_AI2401 Build/34.1420.1420.316; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7512.98 Mobile Safari/537.36",
            tacPrefix = "354890",
            buildNumber = "34.1420.1420.316",
            deviceId = "ASUS_AI2401",
            kernelVersion = "6.1.55-android14-9-g3918aa0",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240301-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        ),
        DevicePreset(
            manufacturer = "Nothing",
            model = "Nothing Phone (2a) A142",
            buildFingerprintTpl = "Nothing/Pacman/Pacman:14/Pacman-U2.5.5-240416-2144/2404162144:user/release-keys",
            serialPrefix = "NT2A88Z",
            display = "1080x2412 @ 394dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; A142 Build/Pacman-U2.5.5; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "867721",
            buildNumber = "Pacman-U2.5.5-240416-2144",
            deviceId = "Pacman",
            kernelVersion = "5.15.102-android14-7-gaa9021c",
            socHardware = "MediaTek Dimensity 7200 Pro (MT6886)",
            gpuRenderer = "Mali-G610 MC4",
            basebandVersion = "m_g6886-240301-B-11212984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        ),
        DevicePreset(
            manufacturer = "Honor",
            model = "Honor Magic 6 Pro BVL-N49",
            buildFingerprintTpl = "HONOR/BVL-N49/HNBVL-Q:14/HONORBVL-N49/8.0.0.155C432E5R2P2:user/release-keys",
            serialPrefix = "HN9932B",
            display = "1280x2800 @ 453dpi",
            uaTpl = "Mozilla/5.0 (Linux; Android 14; BVL-N49 Build/HONORBVL-N49; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            tacPrefix = "862279",
            buildNumber = "8.0.0.155C432E5R2P2",
            deviceId = "BVL-N49",
            kernelVersion = "6.1.43-android14-8-g92a101b",
            socHardware = "Snapdragon 8 Gen 3 (SM8650-AB)",
            gpuRenderer = "Adreno (TM) 750 @ 1000MHz",
            basebandVersion = "q_g5300-240401-B-11812984",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01"
        )
    )

    private val recentLocationsQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private const val MAX_RECENT_LOCATIONS = 10

    private val locationPresets = listOf(
        LocationPreset(
            cityCountry = "Toulouse, France",
            timezone = "Europe/Paris",
            carrier = "Free Mobile",
            lat = 43.599062,
            lng = 1.453473,
            ipSubnet = "82.65",
            countryCode = "FR",
            regionName = "France",
            localeTag = "en-FR",
            mccMnc = "20815",
            iccidPrefix = "893315"
        ),
        LocationPreset(
            cityCountry = "Jakarta, Indonesia",
            timezone = "Asia/Jakarta",
            carrier = "Telkomsel 5G",
            lat = -6.2088,
            lng = 106.8456,
            ipSubnet = "180.252",
            countryCode = "ID",
            regionName = "Indonesia",
            localeTag = "en-ID",
            mccMnc = "51010",
            iccidPrefix = "896210"
        ),
        LocationPreset(
            cityCountry = "Dallas, United States",
            timezone = "America/Chicago",
            carrier = "T-Mobile US",
            lat = 32.7767,
            lng = -96.7970,
            ipSubnet = "172.58",
            countryCode = "US",
            regionName = "United States",
            localeTag = "en-US",
            mccMnc = "310260",
            iccidPrefix = "890126"
        ),
        LocationPreset(
            cityCountry = "Los Angeles, United States",
            timezone = "America/Los_Angeles",
            carrier = "Verizon 5G",
            lat = 34.0522,
            lng = -118.2437,
            ipSubnet = "174.192",
            countryCode = "US",
            regionName = "United States",
            localeTag = "en-US",
            mccMnc = "311480",
            iccidPrefix = "891480"
        ),
        LocationPreset(
            cityCountry = "Denver, United States",
            timezone = "America/Denver",
            carrier = "AT&T Mobility",
            lat = 39.7392,
            lng = -104.9903,
            ipSubnet = "107.77",
            countryCode = "US",
            regionName = "United States",
            localeTag = "en-US",
            mccMnc = "310410",
            iccidPrefix = "890141"
        ),
        LocationPreset(
            cityCountry = "Honolulu, United States",
            timezone = "Pacific/Honolulu",
            carrier = "T-Mobile US",
            lat = 21.3069,
            lng = -157.8583,
            ipSubnet = "172.56",
            countryCode = "US",
            regionName = "United States",
            localeTag = "en-US",
            mccMnc = "310260",
            iccidPrefix = "890126"
        ),
        LocationPreset(
            cityCountry = "Toronto, Canada",
            timezone = "America/Toronto",
            carrier = "Rogers Wireless",
            lat = 43.6532,
            lng = -79.3832,
            ipSubnet = "174.112",
            countryCode = "CA",
            regionName = "Canada",
            localeTag = "en-CA",
            mccMnc = "302720",
            iccidPrefix = "893027"
        ),
        LocationPreset(
            cityCountry = "Sao Paulo, Brazil",
            timezone = "America/Sao_Paulo",
            carrier = "Vivo BR 5G",
            lat = -23.5505,
            lng = -46.6333,
            ipSubnet = "177.37",
            countryCode = "BR",
            regionName = "Brazil",
            localeTag = "en-BR",
            mccMnc = "72406",
            iccidPrefix = "895506"
        ),
        LocationPreset(
            cityCountry = "London, United Kingdom",
            timezone = "Europe/London",
            carrier = "Vodafone UK",
            lat = 51.5074,
            lng = -0.1278,
            ipSubnet = "151.224",
            countryCode = "GB",
            regionName = "United Kingdom",
            localeTag = "en-GB",
            mccMnc = "23415",
            iccidPrefix = "894415"
        ),
        LocationPreset(
            cityCountry = "Cairo, Egypt",
            timezone = "Africa/Cairo",
            carrier = "Vodafone EG",
            lat = 30.0444,
            lng = 31.2357,
            ipSubnet = "197.48",
            countryCode = "EG",
            regionName = "Egypt",
            localeTag = "en-EG",
            mccMnc = "60202",
            iccidPrefix = "892002"
        ),
        LocationPreset(
            cityCountry = "Riyadh, Saudi Arabia",
            timezone = "Asia/Riyadh",
            carrier = "STC 5G",
            lat = 24.7136,
            lng = 46.6753,
            ipSubnet = "84.235",
            countryCode = "SA",
            regionName = "Saudi Arabia",
            localeTag = "en-SA",
            mccMnc = "42001",
            iccidPrefix = "899661"
        ),
        LocationPreset(
            cityCountry = "Dubai, United Arab Emirates",
            timezone = "Asia/Dubai",
            carrier = "Etisalat 5G",
            lat = 25.2048,
            lng = 55.2708,
            ipSubnet = "94.200",
            countryCode = "AE",
            regionName = "United Arab Emirates",
            localeTag = "en-AE",
            mccMnc = "42402",
            iccidPrefix = "899710"
        ),
        LocationPreset(
            cityCountry = "Mumbai, India",
            timezone = "Asia/Kolkata",
            carrier = "Jio True5G",
            lat = 19.0760,
            lng = 72.8777,
            ipSubnet = "49.36",
            countryCode = "IN",
            regionName = "India",
            localeTag = "en-IN",
            mccMnc = "405874",
            iccidPrefix = "899187"
        ),
        LocationPreset(
            cityCountry = "Dhaka, Bangladesh",
            timezone = "Asia/Dhaka",
            carrier = "Grameenphone 4G",
            lat = 23.8103,
            lng = 90.4125,
            ipSubnet = "103.108",
            countryCode = "BD",
            regionName = "Bangladesh",
            localeTag = "en-BD",
            mccMnc = "47001",
            iccidPrefix = "898801"
        ),
        LocationPreset(
            cityCountry = "Singapore",
            timezone = "Asia/Singapore",
            carrier = "Singtel 5G",
            lat = 1.3521,
            lng = 103.8198,
            ipSubnet = "118.189",
            countryCode = "SG",
            regionName = "Singapore",
            localeTag = "en-SG",
            mccMnc = "52501",
            iccidPrefix = "896501"
        ),
        LocationPreset(
            cityCountry = "Tokyo, Japan",
            timezone = "Asia/Tokyo",
            carrier = "NTT DOCOMO",
            lat = 35.6762,
            lng = 139.6503,
            ipSubnet = "133.242",
            countryCode = "JP",
            regionName = "Japan",
            localeTag = "en-JP",
            mccMnc = "44010",
            iccidPrefix = "898110"
        ),
        LocationPreset(
            cityCountry = "Sydney, Australia",
            timezone = "Australia/Sydney",
            carrier = "Telstra Mobile",
            lat = -33.8688,
            lng = 151.2093,
            ipSubnet = "120.147",
            countryCode = "AU",
            regionName = "Australia",
            localeTag = "en-AU",
            mccMnc = "50501",
            iccidPrefix = "896101"
        ),
        LocationPreset(
            cityCountry = "Auckland, New Zealand",
            timezone = "Pacific/Auckland",
            carrier = "Spark NZ 5G",
            lat = -36.8485,
            lng = 174.7633,
            ipSubnet = "122.56",
            countryCode = "NZ",
            regionName = "New Zealand",
            localeTag = "en-NZ",
            mccMnc = "53005",
            iccidPrefix = "896405"
        )
    )

    fun generatePhoneNumberForCountry(countryCode: String, seed: Int = Random.nextInt(1000, 9999999)): String {
        val s = (seed and 0x7FFFFFFF).coerceAtLeast(1001)
        val p1 = 201 + (s % 780)
        val p2 = 200 + ((s / 7) % 780)
        val p3 = 1000 + ((s / 13) % 8999)
        val p4 = 100000 + ((s / 17) % 899999)
        return when (countryCode.uppercase()) {
            "BD" -> "+88017${11 + (s % 88)}$p4"
            "IN" -> "+9198${10 + (s % 89)}$p4"
            "ID" -> "+62812${1000 + (s % 8999)}$p3"
            "GB" -> "+4477${10 + (s % 89)}$p4"
            "JP" -> "+8190${1000 + (s % 8999)}$p3"
            "SG" -> "+658${100 + (s % 899)}$p3"
            "DE" -> "+49151${100 + (s % 899)}$p3"
            "FR" -> "+336${10 + (s % 89)}${10 + ((s / 3) % 89)}${10 + ((s / 7) % 89)}${10 + ((s / 11) % 89)}"
            "CA" -> "+1${listOf(416, 647, 437, 905, 604, 514)[s % 6]}$p2$p3"
            "AU" -> "+614${10 + (s % 89)}${100 + ((s / 5) % 899)}${100 + ((s / 9) % 899)}"
            "AE" -> "+97150${100 + (s % 899)}$p3"
            "SA" -> "+96650${100 + (s % 899)}$p3"
            "KR" -> "+8210${1000 + (s % 8999)}$p3"
            "BR" -> "+55119${1000 + (s % 8999)}$p3"
            "EG" -> "+2010${1000 + (s % 8999)}$p3"
            "NZ" -> "+6427${100 + (s % 899)}$p3"
            else -> "+1$p1$p2$p3"
        }
    }

    fun ensureCarrierWithPhone(carrier: String, timezone: String, locationName: String, seedStr: String): String {
        val trimmed = carrier.trim()
        if (trimmed.contains("+")) return trimmed
        val matched = locationPresets.firstOrNull {
            it.timezone.equals(timezone, ignoreCase = true) ||
            it.cityCountry.equals(locationName, ignoreCase = true) ||
            locationName.contains(it.regionName, ignoreCase = true)
        }
        val cc = matched?.countryCode ?: when {
            timezone.contains("Dhaka", true) || locationName.contains("Bangladesh", true) -> "BD"
            timezone.contains("Kolkata", true) || locationName.contains("India", true) -> "IN"
            timezone.contains("Jakarta", true) || locationName.contains("Indonesia", true) -> "ID"
            timezone.contains("London", true) || locationName.contains("United Kingdom", true) -> "GB"
            timezone.contains("Tokyo", true) || locationName.contains("Japan", true) -> "JP"
            timezone.contains("Singapore", true) -> "SG"
            timezone.contains("Berlin", true) || locationName.contains("Germany", true) -> "DE"
            timezone.contains("Paris", true) || locationName.contains("France", true) -> "FR"
            timezone.contains("Toronto", true) || locationName.contains("Canada", true) -> "CA"
            timezone.contains("Sydney", true) || locationName.contains("Australia", true) -> "AU"
            timezone.contains("Dubai", true) || locationName.contains("Emirates", true) -> "AE"
            timezone.contains("Riyadh", true) || locationName.contains("Saudi", true) -> "SA"
            timezone.contains("Seoul", true) || locationName.contains("Korea", true) -> "KR"
            timezone.contains("Sao_Paulo", true) || locationName.contains("Brazil", true) -> "BR"
            timezone.contains("Cairo", true) || locationName.contains("Egypt", true) -> "EG"
            timezone.contains("Auckland", true) || locationName.contains("Zealand", true) -> "NZ"
            else -> "US"
        }
        val seed = (seedStr.hashCode() and 0x7FFFFFFF).coerceAtLeast(1001)
        val phone = generatePhoneNumberForCountry(cc, seed)
        val baseCarrier = trimmed.ifBlank { matched?.carrier ?: "T-Mobile 5G" }
        return "$baseCarrier • $phone"
    }

    fun syncIdentityWithLocationOrTimezone(
        current: DeviceIdentity,
        newLocationOrTz: String,
        isTimezoneEdit: Boolean
    ): DeviceIdentity {
        val clean = newLocationOrTz.trim()
        val matched = if (isTimezoneEdit) {
            locationPresets.firstOrNull { it.timezone.equals(clean, ignoreCase = true) }
                ?: locationPresets.firstOrNull { it.timezone.contains(clean, ignoreCase = true) || clean.contains(it.cityCountry.substringBefore(","), ignoreCase = true) }
        } else {
            locationPresets.firstOrNull { it.cityCountry.equals(clean, ignoreCase = true) }
                ?: locationPresets.firstOrNull {
                    it.cityCountry.contains(clean, ignoreCase = true) ||
                    clean.contains(it.cityCountry.substringBefore(","), ignoreCase = true) ||
                    clean.contains(it.regionName, ignoreCase = true)
                }
        }

        if (matched == null) {
            return if (isTimezoneEdit) current.copy(timezone = clean) else current.copy(locationName = clean)
        }

        val simImsi = buildString {
            append(matched.mccMnc)
            while (length < 15) append(Random.nextInt(0, 10))
        }
        val simIccid = run {
            val sb = StringBuilder(matched.iccidPrefix)
            while (sb.length < 19) sb.append(Random.nextInt(0, 10))
            val s = sb.toString()
            var sum = 0
            for (i in s.indices) {
                var d = s[i] - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d = (d / 10) + (d % 10)
                }
                sum += d
            }
            val check = (10 - (sum % 10)) % 10
            "$s$check"
        }
        val phoneSeed = ((current.androidId + current.imei + simImsi).hashCode() and 0x7FFFFFFF).coerceAtLeast(1001)
        val phoneNum = generatePhoneNumberForCountry(matched.countryCode, phoneSeed)

        return current.copy(
            locationName = if (isTimezoneEdit) matched.cityCountry else clean,
            timezone = if (isTimezoneEdit) clean else matched.timezone,
            latitude = matched.lat + Random.nextDouble(-0.015, 0.015),
            longitude = matched.lng + Random.nextDouble(-0.015, 0.015),
            carrier = "${matched.carrier} • $phoneNum",
            ipAddress = "${matched.ipSubnet}.${Random.nextInt(10, 250)}.${Random.nextInt(2, 254)}",
            simImsi = simImsi,
            simIccid = simIccid,
            region = matched.regionName,
            localeTag = matched.localeTag
        )
    }

    fun generateNewIdentity(
        customName: String? = null,
        excludeModel: String? = null,
        excludeSoc: String? = null,
        excludeTimezone: String? = null,
        excludeLocation: String? = null
    ): DeviceIdentity {
        // Filter out recently used models, excluded models, and excluded SoC to ensure brand-new CPU/GPU & identity every time
        val unvisitedDiffSoc = devicePresets.filter { preset ->
            val isExcluded = !excludeModel.isNullOrBlank() && (
                preset.model.equals(excludeModel, ignoreCase = true) ||
                excludeModel.contains(preset.manufacturer, ignoreCase = true)
            )
            val isSameSoc = !excludeSoc.isNullOrBlank() &&
                preset.socHardware.substringBefore("(").trim().equals(excludeSoc.substringBefore("(").trim(), ignoreCase = true)
            !isExcluded && !isSameSoc && !recentModelsQueue.contains(preset.model)
        }

        val unvisited = if (unvisitedDiffSoc.isNotEmpty()) {
            unvisitedDiffSoc
        } else {
            devicePresets.filter { preset ->
                val isExcluded = !excludeModel.isNullOrBlank() && (
                    preset.model.equals(excludeModel, ignoreCase = true) ||
                    excludeModel.contains(preset.manufacturer, ignoreCase = true)
                )
                !isExcluded && !recentModelsQueue.contains(preset.model)
            }
        }

        val dev = if (unvisited.isNotEmpty()) {
            unvisited.random()
        } else {
            recentModelsQueue.poll()
            devicePresets.filter {
                !excludeModel.isNullOrBlank() && !it.model.equals(excludeModel, ignoreCase = true)
            }.ifEmpty { devicePresets }.random()
        }

        // Add to recent queue
        recentModelsQueue.add(dev.model)
        while (recentModelsQueue.size > MAX_RECENT_MODELS) {
            recentModelsQueue.poll()
        }

        // Always pick a location with a DIFFERENT timezone and live UTC offset (including DST) and not in recentLocationsQueue!
        val nowMs = System.currentTimeMillis()
        val curOffset = if (!excludeTimezone.isNullOrBlank()) {
            try { java.util.TimeZone.getTimeZone(excludeTimezone).getOffset(nowMs) } catch (_: Throwable) { null }
        } else null
        val diffLocs = locationPresets.filter { preset ->
            val sameTz = !excludeTimezone.isNullOrBlank() && preset.timezone.equals(excludeTimezone, ignoreCase = true)
            val sameLoc = !excludeLocation.isNullOrBlank() && preset.cityCountry.equals(excludeLocation, ignoreCase = true)
            val presetOffset = try { java.util.TimeZone.getTimeZone(preset.timezone).getOffset(nowMs) } catch (_: Throwable) { null }
            val sameOffset = curOffset != null && presetOffset != null && curOffset == presetOffset
            !sameTz && !sameLoc && !sameOffset && !recentLocationsQueue.contains(preset.timezone)
        }
        val fallbackLocs = locationPresets.filter { preset ->
            val sameTz = !excludeTimezone.isNullOrBlank() && preset.timezone.equals(excludeTimezone, ignoreCase = true)
            val presetOffset = try { java.util.TimeZone.getTimeZone(preset.timezone).getOffset(nowMs) } catch (_: Throwable) { null }
            val sameOffset = curOffset != null && presetOffset != null && curOffset == presetOffset
            !sameTz && !sameOffset
        }
        val loc = when {
            diffLocs.isNotEmpty() -> diffLocs.random()
            fallbackLocs.isNotEmpty() -> {
                recentLocationsQueue.clear()
                fallbackLocs.random()
            }
            else -> locationPresets.random()
        }
        recentLocationsQueue.add(loc.timezone)
        while (recentLocationsQueue.size > MAX_RECENT_LOCATIONS) {
            recentLocationsQueue.poll()
        }

        val androidId = generateRandomHex(16)
        val advertisingId = UUID.randomUUID().toString()
        val imei = generateValidLuhnImei(dev.tacPrefix)
        val macAddress = generateRandomMac()
        val bluetoothAddress = generateRandomMac()
        val ipAddress = "${loc.ipSubnet}.${Random.nextInt(10, 250)}.${Random.nextInt(2, 254)}"
        val serialNumber = "${dev.serialPrefix}${generateRandomAlphanumeric(5)}${Random.nextInt(10, 99)}"
        val webviewHash = "${generateRandomHex(8)}e${generateRandomHex(8)}"
        val creativeId = "CR${Random.nextLong(1000000000L, 9999999999L)}"
        val campaignId = "${generateRandomHex(8)}c${generateRandomHex(4)}"
        val seatId = "${Random.nextInt(1000, 9999)}"

        val deviceId = dev.deviceId.ifBlank { dev.model.lowercase().replace("galaxy ", "").replace("pixel ", "").replace(" ", "_").take(12) }
        val buildNumber = dev.buildNumber.ifBlank { "UP1A.${Random.nextInt(231001, 240830)}.${Random.nextInt(100, 999)}" }
        val kernelVersion = dev.kernelVersion.ifBlank { "${Random.nextInt(5, 6)}.${Random.nextInt(10, 19)}.${Random.nextInt(50, 180)}-android14-${Random.nextInt(1, 15)}-g${generateRandomHex(8)}" }

        val basebandVersion = run {
            val original = dev.basebandVersion.ifBlank { "g5300g-240301-240502-B-11812984" }
            val prefix = original.substringBeforeLast("-")
            val randomSuffix = Random.nextLong(10000000L, 99999999L).toString()
            "$prefix-$randomSuffix"
        }
        val simImsi = run {
            val sb = java.lang.StringBuilder(loc.mccMnc)
            while (sb.length < 15) {
                sb.append(Random.nextInt(0, 10))
            }
            sb.toString()
        }
        val simIccid = run {
            val sb = java.lang.StringBuilder(loc.iccidPrefix)
            while (sb.length < 19) {
                sb.append(Random.nextInt(0, 10))
            }
            var sum = 0
            val s = sb.toString()
            for (i in s.indices) {
                var digit = s[i] - '0'
                if (i % 2 == 1) {
                    digit *= 2
                    if (digit > 9) digit = (digit / 10) + (digit % 10)
                }
                sum += digit
            }
            val checkDigit = (10 - (sum % 10)) % 10
            "$s$checkDigit"
        }
        val widevineDrmId = generateRandomHex(16)
        val bootloaderVersion = run {
            val devId = deviceId.ifBlank { "marlin" }
            val verMajor = Random.nextInt(0, 3)
            val verMinor = Random.nextInt(1, 9)
            val buildSuffix = Random.nextLong(10000000L, 99999999L).toString()
            "$devId-$verMajor.$verMinor-$buildSuffix"
        }
        val phoneSeed = ((androidId + imei + serialNumber).hashCode() and 0x7FFFFFFF).coerceAtLeast(1001)
        val phoneNumber = generatePhoneNumberForCountry(loc.countryCode, phoneSeed)

        return DeviceIdentity(
            profileName = customName ?: dev.model,
            androidId = androidId,
            advertisingId = advertisingId,
            imei = imei,
            macAddress = macAddress,
            bluetoothAddress = bluetoothAddress,
            ipAddress = ipAddress,
            deviceModel = dev.model,
            manufacturer = dev.manufacturer,
            buildFingerprint = dev.buildFingerprintTpl,
            buildNumber = buildNumber,
            deviceId = deviceId,
            kernelVersion = kernelVersion,
            socHardware = dev.socHardware,
            gpuRenderer = dev.gpuRenderer,
            basebandVersion = basebandVersion,
            simImsi = simImsi,
            simIccid = simIccid,
            widevineDrmId = widevineDrmId,
            bootloaderVersion = bootloaderVersion,
            region = loc.regionName,
            localeTag = loc.localeTag,
            osVersion = dev.osVersion,
            serialNumber = serialNumber,
            locationName = loc.cityCountry,
            latitude = loc.lat + (Random.nextDouble(-0.02, 0.02)),
            longitude = loc.lng + (Random.nextDouble(-0.02, 0.02)),
            timezone = loc.timezone,
            carrier = "${loc.carrier} • $phoneNumber",
            display = dev.display,
            userAgent = dev.uaTpl,
            webviewHash = webviewHash,
            creativeId = creativeId,
            campaignId = campaignId,
            seatId = seatId
        )
    }

    fun applyPresetToIdentity(preset: DevicePreset, current: DeviceIdentity): DeviceIdentity {
        val basebandVersion = run {
            val original = preset.basebandVersion.ifBlank { "g5300g-240301-240502-B-11812984" }
            val prefix = original.substringBeforeLast("-")
            val randomSuffix = Random.nextLong(10000000L, 99999999L).toString()
            "$prefix-$randomSuffix"
        }
        val simImsi = run {
            val sb = java.lang.StringBuilder("310")
            sb.append(listOf("260", "410", "120", "150", "012").random())
            while (sb.length < 15) {
                sb.append(Random.nextInt(0, 10))
            }
            sb.toString()
        }
        val simIccid = run {
            val sb = java.lang.StringBuilder("8901")
            while (sb.length < 19) {
                sb.append(Random.nextInt(0, 10))
            }
            var sum = 0
            val s = sb.toString()
            for (i in s.indices) {
                var digit = s[i] - '0'
                if (i % 2 == 1) {
                    digit *= 2
                    if (digit > 9) digit = (digit / 10) + (digit % 10)
                }
                sum += digit
            }
            val checkDigit = (10 - (sum % 10)) % 10
            "$s$checkDigit"
        }
        val widevineDrmId = generateRandomHex(16)
        val bootloaderVersion = run {
            val devId = preset.deviceId.ifBlank { "marlin" }
            val verMajor = Random.nextInt(0, 3)
            val verMinor = Random.nextInt(1, 9)
            val buildSuffix = Random.nextLong(10000000L, 99999999L).toString()
            "$devId-$verMajor.$verMinor-$buildSuffix"
        }

        return current.copy(
            deviceModel = preset.model,
            manufacturer = preset.manufacturer,
            buildFingerprint = preset.buildFingerprintTpl,
            buildNumber = preset.buildNumber,
            deviceId = preset.deviceId,
            kernelVersion = preset.kernelVersion,
            socHardware = preset.socHardware,
            gpuRenderer = preset.gpuRenderer,
            basebandVersion = basebandVersion,
            simImsi = simImsi,
            simIccid = simIccid,
            widevineDrmId = widevineDrmId,
            bootloaderVersion = bootloaderVersion,
            display = preset.display,
            userAgent = preset.uaTpl,
            osVersion = preset.osVersion,
            serialNumber = "${preset.serialPrefix}${generateRandomAlphanumeric(5)}${Random.nextInt(10, 99)}",
            imei = generateValidLuhnImei(preset.tacPrefix),
            macAddress = generateRandomMac(),
            bluetoothAddress = generateRandomMac()
        )
    }

    fun getInitialDefaultIdentity(): DeviceIdentity {
        return DeviceIdentity(
            profileName = "Default Protected Profile",
            androidId = "8d31fff7331f65f7",
            advertisingId = "af066cbb-313c-4919-8829-80589535484a",
            imei = "352362064514308",
            macAddress = "ca:d2:8f:74:50:4e",
            bluetoothAddress = "3c:5a:41:02:03:5e",
            ipAddress = "192.168.1.100",
            deviceModel = "Pixel 8 Pro",
            manufacturer = "Google",
            buildFingerprint = "google/husky/husky:14/UD1A.230803.041/10808477:user/release-keys",
            buildNumber = "UD1A.230803.041",
            deviceId = "husky",
            kernelVersion = "5.15.110-android14-11-g89a3f2b",
            osVersion = "Android 14 (SDK 34) · Patch: 2024-08-01",
            serialNumber = "3A141FD8291X",
            locationName = "Jakarta, Indonesia",
            latitude = -6.2088,
            longitude = 106.8456,
            timezone = "Asia/Jakarta",
            carrier = "Indosat Ooredoo",
            display = "1344x2992 @ 489dpi",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UD1A.230803.041; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.119 Mobile Safari/537.36",
            webviewHash = "93e2a706734efd18c7fe39fc65d6260abed2a620885a37ef47915bcae836d9b",
            creativeId = "CR52909436",
            campaignId = "699d25ce19d8be8601be9439",
            seatId = "9541"
        )
    }

    private fun generateRandomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun generateRandomAlphanumeric(length: Int): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (1..length).map { chars.random() }.joinToString("")
    }

    /**
     * Computes a mathematically valid 15-digit IMEI with standard Luhn algorithm check digit
     */
    private fun generateValidLuhnImei(tac: String): String {
        val sb = StringBuilder(tac)
        // 14 digits total before check digit
        while (sb.length < 14) {
            sb.append(Random.nextInt(0, 10))
        }
        val first14 = sb.toString().take(14)
        var sum = 0
        for (i in 0 until 14) {
            var digit = first14[i] - '0'
            if (i % 2 == 1) {
                digit *= 2
                if (digit > 9) digit = (digit / 10) + (digit % 10)
            }
            sum += digit
        }
        val checkDigit = (10 - (sum % 10)) % 10
        return "$first14$checkDigit"
    }

    /**
     * Generates standard lowercase locally administered unicast MAC address matching Android format
     */
    fun generateRandomMac(): String {
        // Valid locally administered unicast first octets (b1=0, b2=1)
        val validFirstOctets = arrayOf("02", "06", "0a", "0e", "12", "16", "22", "26", "2a", "32", "36", "4e", "52", "6a", "72", "8a", "9a", "aa", "ba", "ca", "da", "ee", "fe")
        val octet1 = validFirstOctets.random()
        val remaining = (1..5).map { "%02x".format(Random.nextInt(0, 256)) }
        return (listOf(octet1) + remaining).joinToString(":")
    }
}
