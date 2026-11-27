plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    api("org.slf4j:slf4j-api:2.0.16")
    api("com.googlecode.plist:dd-plist:1.30")
    api("net.i2p.crypto:eddsa:0.3.0")
    api("org.whispersystems:curve25519-java:0.5.0")
}
