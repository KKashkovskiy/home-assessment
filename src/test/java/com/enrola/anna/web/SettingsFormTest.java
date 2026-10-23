package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The flat form and the settings record, each way, and the price table's rows. */
class SettingsFormTest {

  static final Settings DEFAULTS = SettingsFixture.DEFAULTS;
  static final Settings.Price ONE =
      new Settings.Price(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
  static final Settings.Price TWO =
      new Settings.Price(BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO);

  @Test
  void settingsSurviveTheForm() {
    assertThat(SettingsForm.of(DEFAULTS, "STOP").toSettings()).isEqualTo(DEFAULTS);
  }

  /** As the form lists the rows, a model named twice keeps its last row; blank rows are dropped. */
  @Test
  void lastRowOfAModelWinsAndBlankRowsAreDropped() {
    SettingsForm form =
        withRows(
            List.of(
                row("gpt-5.1", ONE),
                new SettingsForm.PriceRow(" ", BigDecimal.TEN, null, null),
                row(" gpt-5.1 ", TWO)));

    assertThat(form.toSettings().priceTable()).containsExactly(Map.entry("gpt-5.1", TWO));
  }

  /** The table's rows then one for adding; posted rows keep their places, cleared ones too. */
  @Test
  void rowsKeepThePlacesTheyPostedUnder() {
    assertThat(SettingsForm.of(DEFAULTS, "").priceRows())
        .extracting(SettingsForm.PriceRow::model)
        .containsExactly("gpt-5.1", "");
    List<SettingsForm.PriceRow> posted =
        List.of(row("", ONE), row("gpt-5.1", TWO), SettingsForm.PriceRow.BLANK);
    assertThat(withRows(posted).priceRows()).isEqualTo(posted);
  }

  @Test
  void namesAreStripped() {
    SettingsForm form =
        SettingsForm.of(SettingsFixture.with(DEFAULTS, "persona", "company", " Comparato "), "");

    assertThat(form.toSettings().persona().company()).isEqualTo("Comparato");
  }

  private static SettingsForm.PriceRow row(String model, Settings.Price price) {
    return new SettingsForm.PriceRow(model, price.input(), price.cached(), price.output());
  }

  private static SettingsForm withRows(List<SettingsForm.PriceRow> rows) {
    return SettingsFixture.with(SettingsForm.of(DEFAULTS, ""), "prices", rows);
  }
}
