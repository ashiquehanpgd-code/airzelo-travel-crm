plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    // tests run the engine on plain SQLite (the app uses Android's SQLite through the same SqlDb interface)
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")
}

tasks.test {
    // the desktop parity fixture folder (ios/tools/parity.py); CI sets it and requires the parity tests to run
    environment("AZ_PARITY_DIR", System.getenv("AZ_PARITY_DIR") ?: "")
    environment("AZ_REQUIRE_PARITY", System.getenv("AZ_REQUIRE_PARITY") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    maxHeapSize = "2g"
}
