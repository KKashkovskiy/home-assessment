package com.enrola.anna.agent;

/**
 * One item of a call's input, in order: the transcript as user and assistant text and, after a
 * round that called tools, that round's items replayed and each call's output. The client maps each
 * to the SDK's type; no SDK type crosses this boundary.
 */
public sealed interface InputItem {

  /** What the lead sent, or the per-turn context. */
  record UserText(String text) implements InputItem {}

  /** What Anna sent, the template opener included. */
  record AssistantText(String text) implements InputItem {}

  /** A function call the model made, replayed without its item id, which nothing stored names. */
  record FunctionCall(String callId, String name, String argumentsJson) implements InputItem {}

  /** The JSON a function call was answered with, keyed by the call it answers. */
  record FunctionOutput(String callId, String outputJson) implements InputItem {}

  /**
   * An item only the client reads: a reasoning item as the API's own JSON, encrypted content
   * included, replayed unchanged so the next round keeps it.
   */
  record Opaque(String json) implements InputItem {}
}
