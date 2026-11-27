import groovy.json.JsonSlurper
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
        versionCode = 32
        versionName = "0.25.0"
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
    productFlavors.configureEach {
        val configRoot = providers.gradleProperty("firebaseConfigRoot").orNull ?: "src"
        val config = file("$configRoot/$name/google-services.json")
        if (config.exists()) {
            val json = JsonSlurper().parse(config) as Map<*, *>
            val info = json["project_info"] as Map<*, *>
            val client = (json["client"] as List<*>).map { it as Map<*, *> }.single {
                val ci = it["client_info"] as Map<*, *>
                (ci["android_client_info"] as Map<*, *>)["package_name"] ==
                    if (name == "owner") "com.rutacomercial.app" else "com.rutacomercial.app.repartidor"
            }
            resValue("string", "google_app_id", (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString())
            resValue("string", "google_api_key", ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"].toString())
            resValue("string", "project_id", info["project_id"].toString())
            // Web OAuth client, present once the Google provider is enabled; it turns on "Conectar con Google".
            (client["oauth_client"] as? List<*>)?.map { it as Map<*, *> }?.firstOrNull { it["client_type"].toString() == "3" }
                ?.let { resValue("string", "default_web_client_id", it["client_id"].toString()) }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
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
    // Real org.json for JVM tests; android.jar only ships stubs.
    testImplementation("org.json:json:20250517")
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.test)
    debugImplementation(libs.androidx.compose.tooling)
    debugImplementation(libs.androidx.compose.test.manifest)
}
