package com.enrola.anna.lead;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Inserts Lauren, John and Jane when the lead table is empty, so a restart seeds nothing. */
@Component
public class LeadSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LeadSeeder.class);

  private final LeadRepository leads;
  private final Clock clock;

  public LeadSeeder(LeadRepository leads, Clock clock) {
    this.leads = leads;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (leads.count() > 0) {
      return;
    }
    List<Lead> seeded = seededLeads(clock.instant());
    leads.saveAll(seeded);
    log.info("seeded {} leads", seeded.size());
  }

  /** Phones are stored already in E.164. */
  private static List<Lead> seededLeads(Instant now) {
    return List.of(
        new Lead(
            "lauren",
            "Lauren",
            "+61400000001",
            "lauren@example.com",
            "VIC",
            "Bupa",
            null,
            null,
            null,
            now),
        new Lead(
            "john",
            "John",
            "+61400000002",
            "john@example.com",
            "WA",
            "HBF",
            "$350-$450",
            null,
            null,
            now),
        new Lead(
            "jane",
            "Jane",
            "+61400000003",
            "jane@example.com",
            "NSW",
            null,
            null,
            null,
            null,
            now));
  }
}
