plugins {
  id("com.android.library"); id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "dev.minedmap.claudedroid.control"
  compileSdk = 34
  defaultConfig { minSdk = 26 }
  buildFeatures { compose = true; aidl = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation(project(":core"))
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  testImplementation("junit:junit:4.13.2")
}
