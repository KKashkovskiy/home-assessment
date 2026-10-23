package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The store over a temporary data directory: defaults, overrides that persist, refused saves. */
class SettingsStoreTest {

  static final Settings DEFAULTS = SettingsFixture.DEFAULTS;

  @TempDir Path dataDir;

  @Test
  void noFileMeansTheYmlDefaults() {
    SettingsStore store = new SettingsStore(SettingsFixture.yml(), dataDir);

    assertThat(store.current()).isEqualTo(DEFAULTS);
    assertThat(store.current().agent().model()).isEqualTo("gpt-5.1");
    assertThat(store.current().calendarPreset()).isEqualTo(Settings.CalendarPreset.NORMAL);
  }

  @Test
  void invalidDefaultsFailStartup() {
    Settings noTurns = SettingsFixture.with(DEFAULTS, "limits", "maxTurns", 0);

    assertThatIllegalStateException()
        .isThrownBy(() -> new SettingsStore(noTurns, dataDir))
        .withMessageContaining("maxTurns");
  }

  @Test
  void saveWritesAndSwaps() {
    SettingsStore store = new SettingsStore(DEFAULTS, dataDir);

    store.save(sevenTurns());

    assertThat(dataDir.resolve("settings.json")).exists();
    assertThat(store.current().limits().maxTurns()).isEqualTo(7);
  }

  /** Every value comes back, the fixed clock and the lowercase preset included. */
  @Test
  void saveSurvivesANewStore() throws IOException {
    Settings saved =
        sevenTurns()
            .withClock(
                new Settings.Clock(Settings.ClockMode.FIXED, LocalDateTime.of(2026, 7, 26, 10, 0)))
            .withCalendarPreset(Settings.CalendarPreset.SLOT_TAKEN);
    new SettingsStore(DEFAULTS, dataDir).save(saved);

    assertThat(new SettingsStore(DEFAULTS, dataDir).current()).isEqualTo(saved);
    assertThat(Files.readString(dataDir.resolve("settings.json")))
        .contains(
            "\"calendarPreset\" : \"slot_taken\"", "\"fixedClock\" : \"2026-07-26T10:00:00\"");
  }

  @Test
  void invalidSaveLeavesCurrentAndFile() throws IOException {
    SettingsStore store = new SettingsStore(DEFAULTS, dataDir);
    store.save(sevenTurns());
    Path file = dataDir.resolve("settings.json");
    FileTime written = Files.getLastModifiedTime(file);

    assertThatThrownBy(() -> store.save(SettingsFixture.with(DEFAULTS, "limits", "maxTurns", 0)))
        .isInstanceOfSatisfying(
            SettingsInvalid.class,
            refused ->
                assertThat(refused.problems())
                    .extracting(SettingsValidation.Problem::field)
                    .containsExactly("maxTurns"));
    assertThat(store.current().limits().maxTurns()).isEqualTo(7);
    assertThat(Files.getLastModifiedTime(file)).isEqualTo(written);
  }

  @Test
  void corruptFileFallsBackToDefaultsAndIsSetAside() throws IOException {
    Files.writeString(dataDir.resolve("settings.json"), "{");

    SettingsStore store = new SettingsStore(DEFAULTS, dataDir);

    assertThat(store.current()).isEqualTo(DEFAULTS);
    assertThat(dataDir.resolve("settings.json.bad")).hasContent("{");
    assertThat(dataDir.resolve("settings.json")).doesNotExist();
  }

  /** A file from a build with fewer settings keeps its values and takes the rest from the yml. */
  @Test
  void fileMissingAValueTakesItFromTheDefaults() throws IOException {
    new SettingsStore(DEFAULTS, dataDir).save(sevenTurns());
    Path file = dataDir.resolve("settings.json");
    Files.writeString(file, Files.readString(file).replace("\"objectionRetries\" : 1,", ""));

    assertThat(new SettingsStore(DEFAULTS, dataDir).current()).isEqualTo(sevenTurns());
    assertThat(dataDir.resolve("settings.json.bad")).doesNotExist();
  }

  /** The file's price table replaces the yml's whole, so a model it dropped stays dropped. */
  @Test
  void fileWithAnotherPriceTableReplacesTheYmlsWhole() {
    Settings.Price price = new Settings.Price(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN);
    Settings gpt5 =
        SettingsFixture.with(
            SettingsFixture.with(DEFAULTS, "agent", "model", "gpt-5"),
            "priceTable",
            Map.of("gpt-5", price));
    new SettingsStore(DEFAULTS, dataDir).save(gpt5);

    assertThat(new SettingsStore(DEFAULTS, dataDir).current().priceTable())
        .containsExactly(Map.entry("gpt-5", price));
  }

  @Test
  void fileThatFailsTheRulesIsSetAside() throws IOException {
    Files.writeString(dataDir.resolve("settings.json"), "{\"limits\": {\"maxTurns\": 0}}");

    assertThat(new SettingsStore(DEFAULTS, dataDir).current()).isEqualTo(DEFAULTS);
    assertThat(dataDir.resolve("settings.json.bad")).exists();
  }

  @Test
  void listenersRunNowAndOnEachSave() {
    SettingsStore store = new SettingsStore(DEFAULTS, dataDir);
    List<Integer> heard = new ArrayList<>();

    store.onChange(settings -> heard.add(settings.limits().maxTurns()));
    store.save(sevenTurns());

    assertThat(heard).containsExactly(15, 7);
  }

  @Test
  void aCheckRefusesTheSaveWithItsProblems() {
    SettingsStore store = new SettingsStore(DEFAULTS, dataDir);
    store.addCheck(
        candidate ->
            candidate.limits().maxTurns() < 10
                ? List.of(new SettingsValidation.Problem("maxTurns", "too few for this prompt"))
                : List.of());

    assertThatThrownBy(() -> store.save(sevenTurns())).isInstanceOf(SettingsInvalid.class);
    assertThat(store.current()).isEqualTo(DEFAULTS);
    assertThat(dataDir.resolve("settings.json")).doesNotExist();
  }

  /** A check that fails the loaded overrides sets them aside and runs the defaults. */
  @Test
  void recheckSetsAsideOverridesTheChecksRefuse() {
    new SettingsStore(DEFAULTS, dataDir).save(sevenTurns());
    SettingsStore store = new SettingsStore(DEFAULTS, dataDir);
    List<Integer> heard = new ArrayList<>();
    store.onChange(settings -> heard.add(settings.limits().maxTurns()));
    store.addCheck(
        candidate ->
            candidate.limits().maxTurns() < 10
                ? List.of(new SettingsValidation.Problem("maxTurns", "too few for this prompt"))
                : List.of());

    store.recheck();

    assertThat(store.current()).isEqualTo(DEFAULTS);
    assertThat(heard).containsExactly(7, 15);
    assertThat(dataDir.resolve("settings.json.bad")).exists();
  }

  /** Two quick changes to different values both land: each reads what the other saved. */
  @Test
  void updatesChangeTheCurrentRecord() {
    SettingsStore store = new SettingsStore(DEFAULTS, dataDir);

    store.update(settings -> SettingsFixture.with(settings, "limits", "maxTurns", 7));
    store.update(settings -> settings.withCalendarPreset(Settings.CalendarPreset.FULLY_BOOKED));

    assertThat(store.current())
        .isEqualTo(sevenTurns().withCalendarPreset(Settings.CalendarPreset.FULLY_BOOKED));
  }

  private static Settings sevenTurns() {
    return SettingsFixture.with(DEFAULTS, "limits", "maxTurns", 7);
  }
}
