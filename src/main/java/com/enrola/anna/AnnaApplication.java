package com.enrola.anna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot entry point. No logic here; features register themselves through component scanning. */
@SpringBootApplication
public class AnnaApplication {

  public static void main(String[] args) {
    SpringApplication.run(AnnaApplication.class, args);
  }
}
