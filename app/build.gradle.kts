plugins { id("com.android.application"); kotlin("android"); kotlin("kapt") }
android {
    namespace = "dev.mmp.go"
    compileSdk = 36
    defaultConfig { applicationId = "dev.mmp.go"; minSdk = 26; targetSdk = 36; versionCode = 11; versionName = "0.11.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs.getByName("debug") { storeFile = File(gradle.gradleUserHomeDir, "mmp-go/debug.keystore") }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    lint { abortOnError = true }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.room:room-runtime:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
