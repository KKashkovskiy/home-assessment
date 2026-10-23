package com.enrola.anna.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The settings {@code application.yml} ships, bound from the file itself as at boot, so a test
 * reads the real defaults rather than a copy of them, and copies of any settings record with one
 * value changed.
 */
public final class SettingsFixture {

  /** The yml's settings: gpt-5.1, 320 and 300 characters, 15 turns, caps of $0.50 and $10. */
  public static final Settings DEFAULTS = yml().settings();

  private SettingsFixture() {}

  /** The yml's {@code anna.*} groups with {@code overrides}, such as {@code "anna.x=y"}, on top. */
  public static SettingsDefaults yml(String... overrides) {
    StandardEnvironment environment = new StandardEnvironment();
    try {
      new YamlPropertySourceLoader()
          .load("application.yml", new ClassPathResource("application.yml"))
          .forEach(environment.getPropertySources()::addLast);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    TestPropertyValues.of(overrides).applyTo(environment);
    return Binder.get(environment).bind("anna", SettingsDefaults.class).get();
  }

  /**
   * {@code settings} with value {@code name} of its section {@code section} set to {@code value}.
   */
  public static Settings with(Settings settings, String section, String name, Object value) {
    return with(settings, section, with((Record) valueOf(settings, section), name, value));
  }

  /**
   * {@code record} with its component {@code name} set to {@code value} and the rest as they were;
   * an unknown name fails the test.
   */
  @SuppressWarnings("unchecked") // the canonical constructor of R's class makes an R
  public static <R extends Record> R with(R record, String name, Object value) {
    RecordComponent[] components = record.getClass().getRecordComponents();
    if (Arrays.stream(components).noneMatch(component -> component.getName().equals(name))) {
      throw new IllegalArgumentException(record.getClass().getSimpleName() + " has no " + name);
    }
    Object[] values =
        Arrays.stream(components)
            .map(
                component ->
                    component.getName().equals(name) ? value : valueOf(record, component.getName()))
            .toArray();
    Class<?>[] types =
        Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
    try {
      return (R) record.getClass().getDeclaredConstructor(types).newInstance(values);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object valueOf(Record record, String name) {
    try {
      return record.getClass().getMethod(name).invoke(record);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }
}
