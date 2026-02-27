# FROM loxal/kotlin-toolbox:latest
FROM loxal/toolbox:latest

LABEL maintainer="loxal <info@loxal.net>"

# WORKDIR /home/ubuntu
RUN mkdir /home/ubuntu/crawler && chown -R 1000 crawler
COPY page-finder-service/build/libs/*.jar ./
ENV SPRING_CONFIG_NAME=application,prod
# USER minion

EXPOSE 8001
EXPOSE 7443

# CMD ["java", "-jar", "-Xms256m", "-Xmx1024m", "page-finder-service.jar"]
# CMD ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-Xms256m", "-Xmx1024m", "-jar", "page-finder-service.jar"]
# CMD ["java", "--enable-native-access=ALL-UNNAMED", "-XX:-TieredCompilation", "-XX:+UseG1GC", "-Xms256m", "-Xmx1024m", "-jar", "page-finder-service.jar"]
# CMD ["java", "--enable-native-access=ALL-UNNAMED", "-XX:-TieredCompilation", "-XX:+UseShenandoahGC", "-Xms256m", "-Xmx1024m", "-jar", "page-finder-service.jar"]
# CMD ["java", "--enable-native-access=ALL-UNNAMED", "-XX:-TieredCompilation", "-XX:+UseZGC", "-Xms256m", "-Xmx512m", "-Dio.netty.leakDetection.level=DISABLED", "-jar", "page-finder-service.jar"]
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-XX:-TieredCompilation", "-XX:+UseZGC", "-Xms256m", "-Xmx512m", "-jar", "page-finder-service.jar"]