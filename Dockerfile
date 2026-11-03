FROM eclipse-temurin:17.0.16_8-jre-jammy@sha256:e90fd2b084488c0ffdd22610d44c1579e63b585c7fb83489fd815c0dc5b4e1f7

WORKDIR /opt/aqa
COPY --chown=0:0 --chmod=0444 aqa/internal-0.0.1-SNAPSHOT.jar /opt/aqa/application.jar
USER 10001:10001

ENTRYPOINT ["java", "-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:MaxDirectMemorySize=32m", "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp", "-Dserver.address=127.0.0.1", "-Dserver.port=8080", "-Dsecret=qazWSXedc", "-Dmock=http://127.0.0.1:8888/", "-jar", "/opt/aqa/application.jar"]
