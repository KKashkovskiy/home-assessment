package com.enrola.anna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Boot entry point; features register themselves through component scanning. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AnnaApplication {

  public static void main(String[] args) {
    SpringApplication.run(AnnaApplication.class, args);
  }
}
