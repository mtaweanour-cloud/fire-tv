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

val netty = "4.1.115.Final"

dependencies {
    api(project(":airplay-protocol"))
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    api("io.netty:netty-buffer:$netty")
    api("io.netty:netty-transport:$netty")
    api("io.netty:netty-codec:$netty")
    api("io.netty:netty-codec-http:$netty")
    api("io.netty:netty-handler:$netty")
    api("io.lindstrom:m3u8-parser:0.30")
}
