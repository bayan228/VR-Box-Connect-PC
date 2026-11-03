plugins {
    kotlin("jvm") version "1.9.22"
}

repositories {
    mavenCentral()
}

// Compile the actual client's pure Kotlin code and tests, without AGP or an Android SDK.
kotlin.sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("com/vrbox/client/protocol/**", "com/vrbox/client/network/ConnectionInput.kt",
            "com/vrbox/client/network/SavedConnectionStore.kt", "com/vrbox/client/render/ViewerCalibration.kt",
            "com/vrbox/client/render/SourceLayout.kt")
    }
    test {
        kotlin.srcDir("../app/src/test/java")
        kotlin.include("com/vrbox/client/protocol/**", "com/vrbox/client/network/ConnectionInputTest.kt",
            "com/vrbox/client/network/SavedConnectionStoreTest.kt", "com/vrbox/client/render/ViewerCalibrationTest.kt",
            "com/vrbox/client/render/SourceLayoutTest.kt")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
