package com.enrola.anna.agent;

/**
 * One round trip to the model: input items in, output items out. The loop that answers function
 * calls lives in {@link AgentService}. Two implementations: {@link OpenAiModelClient}, and the
 * scripted fake the tests use.
 */
public interface ModelClient {
  ModelResponse call(ModelRequest request);
}
