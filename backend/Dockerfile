FROM gradle:8.14.3-jdk21 AS build
WORKDIR /home/gradle/app
COPY --chown=gradle:gradle proto ./proto
COPY --chown=gradle:gradle backend ./backend
WORKDIR /home/gradle/app/backend
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /home/gradle/app/backend/build/libs/app.jar ./app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
