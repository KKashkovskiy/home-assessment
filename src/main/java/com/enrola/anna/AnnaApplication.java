package com.enrola.anna;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/** Boot entry point and the app clock; features register themselves through component scanning. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AnnaApplication {

  public static void main(String[] args) {
    SpringApplication.run(AnnaApplication.class, args);
  }

  /** Every timestamp the app writes comes from this clock, so tests can pin it. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
