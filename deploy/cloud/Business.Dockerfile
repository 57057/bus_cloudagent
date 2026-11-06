FROM eclipse-temurin:17-jre
WORKDIR /opt/cloudagent
COPY business.jar ./business.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/opt/cloudagent/business.jar"]
