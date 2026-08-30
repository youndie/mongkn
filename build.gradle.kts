// Every plugin is declared here with `apply false`: otherwise a subproject that names a version of
// its own runs into "plugin is already on the classpath with an unknown version".
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaJvm) apply false
    alias(libs.plugins.sborkaLint) apply false
    alias(libs.plugins.sborkaPublish) apply false
}

// The coordinates, the version and the ktlint wiring used to live here, in an `allprojects` and a
// `subprojects` block. They are `sborka.group` and `version` in `gradle.properties` now, and
// `ru.workinprogress.sborka.lint` pins the formatter — the same 1.8.0 this repository already
// insisted on, and with it the `.editorconfig` the tool reads, which is the half a version number
// cannot pin.
//
// The reason the group was set for every project and not only the root has not gone away: a
// subproject defaults to the ROOT PROJECT'S NAME as its group, so the artefacts would have gone out
// under `mongkn` rather than `io.github.youndie.mongkn`. `sborka.base` sets it per module, which is
// the same fix said once.
