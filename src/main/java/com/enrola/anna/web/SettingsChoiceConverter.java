package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.stereotype.Component;

/**
 * Reads the settings' choices as the console's forms post them, lowercase as in {@code
 * fully_booked} or {@code medium}; Spring's own enum conversion takes only the constant's name. An
 * unknown choice fails the binding.
 */
@Component
class SettingsChoiceConverter implements GenericConverter {

  private static final Map<Class<?>, Function<String, ?>> CHOICES =
      Map.of(
          Settings.CalendarPreset.class, Settings.CalendarPreset::of,
          Settings.ReasoningEffort.class, Settings.ReasoningEffort::of,
          Settings.ReasoningSummary.class, Settings.ReasoningSummary::of);

  @Override
  public Set<ConvertiblePair> getConvertibleTypes() {
    return CHOICES.keySet().stream()
        .map(choice -> new ConvertiblePair(String.class, choice))
        .collect(Collectors.toSet());
  }

  @Override
  public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
    return source == null
        ? null
        : CHOICES.get(targetType.getType()).apply(((String) source).strip());
  }
}
