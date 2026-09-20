plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.sayeh.ai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sayeh.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0-final"
    }
    buildFeatures { buildConfig = true }
    val apiKey = project.findProperty("OPENAI_API_KEY")?.toString() ?: ""
    buildConfigField("String", "OPENAI_API_KEY", "\"${apiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
