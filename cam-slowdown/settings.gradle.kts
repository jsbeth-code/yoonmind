pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 카카오내비 SDK(KNSDK)와 그 의존성
        maven("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-knsdk-public/") {
            content { includeGroup("com.kakaomobility.knsdk") }
        }
        maven("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-locationsdk-release/") {
            content { includeGroup("com.kakaomobility.location.library") }
        }
        maven("https://devrepo.kakao.com/nexus/content/groups/public/") {
            content { includeGroup("com.kakao.sdk") }
        }
        maven("https://devrepo.kakao.com/nexus/repository/kakaomap-releases/") {
            content { includeGroup("com.kakao.maps.open") }
        }
    }
}
rootProject.name = "cam-slowdown"
include(":app")
