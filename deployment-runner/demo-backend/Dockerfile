FROM eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar /app/app.jar
COPY health/ /app/health/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","/app/app.jar"]
