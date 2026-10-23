package com.enrola.anna.eval;

import java.nio.file.Path;
import java.util.List;

/**
 * What a run covers and where it writes: its header, the selected scenarios, the directory for its
 * files, and the app database for its {@code eval_run} row.
 */
record RunPlan(RunHeader header, List<Scenario> selected, Path dir, String appDbUrl) {}
