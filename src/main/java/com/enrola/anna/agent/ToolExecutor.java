package com.enrola.anna.agent;

/**
 * Answers one function call the model makes: the tool's name and its arguments JSON in, the JSON
 * the model reads next out. A bad call is answered with an error the model can read, not thrown.
 */
@FunctionalInterface
public interface ToolExecutor {
  String execute(String name, String argumentsJson);
}
