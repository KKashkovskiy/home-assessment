package com.enrola.anna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Boot entry point; features register themselves through component scanning. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AnnaApplication {

  /**
   * Binds the listener H2's {@code AUTO_SERVER} opens, through which an eval run writes to the app
   * database while the app runs, to this machine only. H2 reads the property once, so it is set
   * before anything connects.
   */
  public static void main(String[] args) {
    System.setProperty("h2.bindAddress", "127.0.0.1");
    SpringApplication.run(AnnaApplication.class, args);
  }
}
