package com.enrola.anna.web;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Values for the status strip and banner on every page: clock mode and the fixed instant's label,
 * app spend against its cap, the model, effort and active prompt version, whether the agent can
 * answer, and the PII mask.
 */
public record ShellModel(
    String clockMode,
    String fixedAt,
    BigDecimal spend,
    BigDecimal cap,
    String model,
    String effort,
    String promptVersion,
    AgentStatus agent,
    boolean piiMask) {

  private static final BigDecimal CENT = new BigDecimal("0.01");

  /** "$0.0083 of $10.00": four decimals under a cent, so one cheap turn never reads $0.00. */
  public String meterLabel() {
    return money(spend) + " of " + money(cap);
  }

  /** Width of the meter's fill, 0 to 100; a zero cap is a full meter. */
  public int spendPercent() {
    if (cap.signum() <= 0) {
      return 100;
    }
    return spend
        .multiply(BigDecimal.valueOf(100))
        .divide(cap, 0, RoundingMode.HALF_UP)
        .min(BigDecimal.valueOf(100))
        .intValue();
  }

  /** A turn can reach the model, or the boot check has not said otherwise yet. */
  public boolean agentAvailable() {
    return agent == AgentStatus.READY || agent == AgentStatus.CHECKING;
  }

  private static String money(BigDecimal usd) {
    boolean underACent = usd.signum() != 0 && usd.compareTo(CENT) < 0;
    return "$" + usd.setScale(underACent ? 4 : 2, RoundingMode.HALF_UP).toPlainString();
  }

  public boolean agentReady() {
    return agent == AgentStatus.READY;
  }

  public boolean agentMissingKey() {
    return agent == AgentStatus.NO_KEY;
  }

  public boolean agentCheckFailed() {
    return agent == AgentStatus.MODEL_FAILED;
  }

  public boolean agentChecking() {
    return agent == AgentStatus.CHECKING;
  }
}
