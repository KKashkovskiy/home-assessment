package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.NoReply;

/**
 * One item in the chat thread: a bubble, a tool chip or a system line, each rendered by the
 * fragment of {@code fragments/turn.html} that {@link #fragment()} names. A turn reads in {@link
 * #order()}: the lead's message, the tools it set off, then the agent's reply or the line saying
 * none was sent.
 */
public sealed interface ThreadItem {

  int turnNo();

  /** Position inside a turn. */
  int order();

  /** The fragment that renders this item. */
  String fragment();

  /**
   * An SMS. {@code source} is null for the lead's; {@code near} marks a reply over the target
   * length; {@code at} is the lead-local clock time.
   */
  record Bubble(
      Direction direction,
      String text,
      int chars,
      int maxChars,
      boolean near,
      ReplySource source,
      boolean repaired,
      int turnNo,
      String at)
      implements ThreadItem {

    public boolean lead() {
      return direction == Direction.IN;
    }

    @Override
    public int order() {
      return lead() ? 0 : 2;
    }

    @Override
    public String fragment() {
      return "bubble";
    }
  }

  /** A function call the model made on this turn, with a one-line summary of what came back. */
  record ToolChip(int turnNo, String name, String summary) implements ThreadItem {

    @Override
    public int order() {
      return 1;
    }

    @Override
    public String fragment() {
      return "toolChip";
    }
  }

  /**
   * A turn that sent nothing, and why, with the state it left, so the thread never shows an empty
   * gap. {@code reason} is null for a turn stored before reasons were.
   */
  record SystemLine(int turnNo, NoReply reason, EndReason endReason, HandoffReason handoffReason)
      implements ThreadItem {

    @Override
    public int order() {
      return 2;
    }

    @Override
    public String fragment() {
      return "systemLine";
    }
  }
}
