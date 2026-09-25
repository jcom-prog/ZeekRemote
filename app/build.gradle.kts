1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
25
26
27
28
29
30
31
32
33
34
35
36
37
38
39
40
41
42
43
44
45
46
47
48
49
50
51
52
53
54
55
56
57
58
59
60
61
62
63
64
65
66
67
68
69
70
71
72
73
74
75
76
77
78
79
80
81
82
83
84
85
86
87
88
89
90
91
92
93
94
95
96
97
98
99
100
101
102
103
104
105
106
import java.io.FileInputStream
import java.util.Properties


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


// Release signing is read from an UNTRACKED keystore.properties at the repo root (see
// keystore.properties.example). If it's absent, `release` builds unsigned — Studio's
// "Generate Signed Bundle" still works independently.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile)) }


android {
    namespace = "com.openzeekr.app"
    compileSdk = 34


    defaultConfig {
        applicationId = "com.openzeekr.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 36
        versionName = "0.1.19"
        // App-global secrets are baked in :core (SecretsConfig + BuildConfig live there).
    }


    signingConfigs {
        if (keystorePropsFile.exists()) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            keystoreProps.getProperty("storeType")?.let { storeType = it } // e.g. PKCS12 for a .p12
        }
    }


    buildTypes {
        debug {
            // Test build installs beside the upstream release while phone and watch retain the same
            // package id/signing identity required by the Wear Data Layer.
            applicationIdSuffix = ".test"
            versionNameSuffix = "-work"
            // CI writes keystore.properties from the encrypted permanent test-key secret. Binding
            // it explicitly prevents AGP from silently generating a new debug identity.
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle bcprov + bcpkix (pulled in transitively via :core) ship these.
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/versions/**/OSGI-INF/**"
        resources.excludes += "/META-INF/*.SF"
        resources.excludes += "/META-INF/*.DSA"
        resources.excludes += "/META-INF/*.RSA"
    }
}


dependencies {
    // Shared BLE/DK/crypto/cloud logic (also carries okhttp/retrofit/serialization/
    // security-crypto/bouncycastle transitively via `api`).
    implementation(project(":core"))


    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)


    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")


    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")


    // Map (free, no API key): MapLibre GL + OpenFreeMap tiles. Used for the parked-car
    // location; turn-by-turn navigation is handed off to the phone's nav app via deeplink.
    implementation("org.maplibre.gl:android-sdk:11.13.5")


    // Wear Data Layer — clones the digital key to the paired watch on request.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
