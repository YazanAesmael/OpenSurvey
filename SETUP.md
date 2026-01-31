# Setup & White-Labeling Guide

## 🎨 Branding Configuration

### Colors
Modify `core/src/main/java/com/opensurvey/core/ui/theme/Color.kt` to match your client's brand.
- `PrimaryBrandColor`: The main accent color.
- `PrimaryBrandVariant`: A darker shade of the primary color.
- `NeutralSurface`: The dark mode background color.

### Icon
Replace the app icon layers in `app/src/main/res/drawable/`:
- `ic_launcher_foreground.xml`: Your vector logo.
- `ic_launcher_background.xml`: The background solid color.
For best results, also regenerate the `mipmap-*` densities using Android Studio's Image Asset Studio.

### Strings
Update `app/src/main/res/values/strings.xml` to change the `app_name`.

## ⚙️ API Configuration

The Base URL is configured in `data/build.gradle.kts`.

```kotlin
android {
    defaultConfig {
        // Change this URL to your deployment target
        buildConfigField("String", "BASE_URL", "\"https://api.yourdomain.com\"")
    }
}
```

## 📦 Package Name Change
The codebase has been refactored to `com.opensurvey`. If you need a different package name:
1. Find/Replace `com.opensurvey` with your package name in all files.
2. Rename directories recursively.
