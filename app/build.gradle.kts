plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Phase 8: Room annotation processing for the local video-history database.
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.journeyvisualizer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.journeyvisualizer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Phase 9 fix: icons-core (BOM 2024.10.00 → 1.7.4) ships only 49 icons;
    // the app uses Map, Movie, Speed, Videocam, History, Help, Description
    // and others that live in the extended set (verified against the 1.7.4
    // AAR). Without this dependency the project does not compile.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Interactive map (Phase 4): osmdroid renders real OSM/CARTO/Esri tiles
    // with pan/zoom/rotation, markers, and polylines. No API key, no
    // tracking; tile loading is the only network use (timeline stays local).
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Local video history (Phase 8): the project's first — and only —
    // database. Stores metadata references for generated videos; the MP4
    // files themselves stay in MediaStore and are never copied or uploaded.
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit tests run on the JVM; the real org.json implementation shadows the
    // android.jar stub so TimelineParser can be tested without Robolectric.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
