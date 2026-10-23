package com.enrola.anna.agent;

/**
 * One round trip to the model. Two implementations: {@link OpenAiModelClient}, and the scripted
 * fake the tests use.
 */
public interface ModelClient {
  ModelResponse call(ModelRequest request);
}
