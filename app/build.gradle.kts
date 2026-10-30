plugins {
  id("com.android.application"); id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp")
}
android {
  namespace = "dev.minedmap.claudedroid"
  compileSdk = 34
  defaultConfig {
    applicationId = "dev.minedmap.claudedroid"
    minSdk = 26; targetSdk = 34; versionCode = 5; versionName = "0.4"
  }
  buildFeatures { compose = true; aidl = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.navigation:navigation-compose:2.8.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
  implementation("androidx.room:room-runtime:2.6.1")
  implementation("androidx.room:room-ktx:2.6.1")
  ksp("androidx.room:room-compiler:2.6.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.26.0")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
}
