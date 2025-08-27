plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" // Actualizar KSP para que sea compatible con Kotlin 2.0.21
    id("kotlin-parcelize")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}
group = "com.eddyslarez"
version = "1.0.0"

android {
    namespace = "com.eddyslarez.siplibrary"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Para procesamiento de audio
//    implementation ("com.arthenica:mobile-ffmpeg-full:4.4.LTS")

    // Para Google Speech-to-Text
//    implementation ("com.google.cloud:google-cloud-speech:4.1.0")

    // Para Whisper local (opcional)
//    implementation ("com.whispercpp:whisper-android:1.0.0")

    // Para procesamiento de señales de audio
    implementation ("org.apache.commons:commons-math3:3.6.1")
    implementation ("com.google.code.gson:gson:2.10.1")

    implementation ("com.alphacephei:vosk-android:0.3.38")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.bluetooth:bluetooth:1.0.0-alpha02")
// OkHttp for WebSocket connection to OpenAI
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    // Audio processing
    implementation("androidx.media:media:1.7.0")
    // Opcional: UI components si los necesitas
    implementation ("io.getstream:stream-webrtc-android-ui:1.3.8")

    // Opcional: Coroutines extensions
    implementation ("io.getstream:stream-webrtc-android-ktx:1.3.8")
    // Room Database - Cambia kapt por ksp
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1") // ← Cambio aquí
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Resto del código igual...
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.eddyslarez"
            artifactId = "sip-library"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("SIP Library for Android")
                description.set("Librería para WebRTC y WebSocket en Android con Compose.")
                url.set("https://github.com/eddyslarez/sip-library")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("eddyslarez")
                        name.set("Eddys Larez")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/eddyslarez/sip-library.git")
                    developerConnection.set("scm:git:ssh://github.com/eddyslarez/sip-library.git")
                    url.set("https://github.com/eddyslarez/sip-library")
                }
            }
        }
    }
}