plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.rutacomercial.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.rutacomercial.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 24
        versionName = "0.17.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("owner") {
            dimension = "edition"
            manifestPlaceholders["editionLabel"] = "RutaComercial Owner"
            manifestPlaceholders["assignmentMime"] = "application/vnd.rutacomercial.result"
            buildConfigField("boolean", "WORKER_EDITION", "false")
        }
        create("repartidor") {
            dimension = "edition"
            applicationIdSuffix = ".repartidor"
            manifestPlaceholders["editionLabel"] = "RutaComercial Repartidor"
            manifestPlaceholders["assignmentMime"] = "application/vnd.rutacomercial.task"
            buildConfigField("boolean", "WORKER_EDITION", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.gson)
    implementation(libs.coroutines)
    implementation(libs.play.location)
    // Google Play brings an older Fragment transitively; ActivityResult requires a current version.
    implementation(libs.androidx.fragment)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.test)
    debugImplementation(libs.androidx.compose.tooling)
    debugImplementation(libs.androidx.compose.test.manifest)
}

