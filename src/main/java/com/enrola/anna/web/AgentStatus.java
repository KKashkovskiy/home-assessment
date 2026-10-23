package com.enrola.anna.web;

import com.enrola.anna.agent.ModelCheck;

/**
 * Whether the agent can answer, as the status strip and banner show it: no key, the boot check
 * still running or failed, or ready.
 */
public enum AgentStatus {
  READY,
  CHECKING,
  NO_KEY,
  MODEL_FAILED;

  /** The status for a configured key, by how the boot check went. */
  static AgentStatus after(ModelCheck.Result check) {
    return switch (check) {
      case PENDING -> CHECKING;
      case PASSED -> READY;
      case FAILED -> MODEL_FAILED;
    };
  }
}
