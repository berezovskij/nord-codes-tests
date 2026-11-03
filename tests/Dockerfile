FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS dependencies
ARG ALLURE_VERSION=2.29.0
ARG SUREFIRE_VERSION=3.5.2
WORKDIR /prepare
COPY pom.xml /prepare/pom.xml
RUN mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=/opt/maven-repository org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline
WORKDIR /prepare-tools
RUN mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=/opt/maven-repository org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.apache.maven.surefire:surefire-junit-platform:${SUREFIRE_VERSION}
RUN mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=/opt/maven-repository org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.apache.maven.surefire:surefire-booter:${SUREFIRE_VERSION}
RUN mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=/opt/maven-repository org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.junit.platform:junit-platform-launcher:1.9.3
RUN mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=/opt/maven-repository org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=io.qameta.allure:allure-commandline:${ALLURE_VERSION}:zip -Dtransitive=false
WORKDIR /opt/allure-dist
RUN jar -xf /opt/maven-repository/io/qameta/allure/allure-commandline/${ALLURE_VERSION}/allure-commandline-${ALLURE_VERSION}.zip
RUN mv allure-${ALLURE_VERSION} /opt/allure
RUN chmod 0555 /opt/allure/bin/allure

FROM eclipse-temurin:17.0.16_8-jdk-jammy@sha256:47d330891da01c4b17fb6af5018d9a7bad33f7a252a91dde890c10d4de5ebd33
WORKDIR /opt/aqa
COPY --chown=0:0 --chmod=0444 docker/run-suite.sh /opt/aqa/run-suite.sh
WORKDIR /workspace
COPY --from=dependencies /usr/share/maven /usr/share/maven
COPY --from=dependencies /opt/allure /opt/allure
COPY --from=dependencies /opt/maven-repository /opt/maven-repository
COPY --chown=0:0 pom.xml /workspace/pom.xml
COPY --chown=0:0 src/test/java/ /workspace/src/test/java/
COPY --chown=0:0 src/test/resources/ /workspace/src/test/resources/
ENV MAVEN_CONFIG=/tmp/maven-config
ENV MAVEN_HOME=/usr/share/maven
USER 10003:10003
ENTRYPOINT ["/bin/sh", "/opt/aqa/run-suite.sh"]
