// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec.local;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceManager.ResourceHandle;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.ProcessWrapperUtil;
import com.google.devtools.build.lib.shell.ExecutionStatistics;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.shell.TerminationStatus;
import com.google.devtools.build.lib.util.NetUtil;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A class that runs local commands. Each request follows state transitions from "parsing" to
 * completion.
 */
@ThreadSafe
public class LocalSpawnRunner implements SpawnRunner {
  private static final Joiner SPACE_JOINER = Joiner.on(' ');
  private static final String UNHANDLED_EXCEPTION_MSG = "Unhandled exception running a local spawn";
  private static final int LOCAL_EXEC_ERROR = -1;
  private static final int POSIX_TIMEOUT_EXIT_CODE = /*SIGNAL_BASE=*/ 128 + /*SIGALRM=*/ 14;

  private static final Logger logger = Logger.getLogger(LocalSpawnRunner.class.getName());

  private final Path execRoot;
  private final ResourceManager resourceManager;

  private final String hostName;

  private final LocalExecutionOptions localExecutionOptions;

  private final boolean useProcessWrapper;
  private final Path processWrapper;

  private final LocalEnvProvider localEnvProvider;
  private final BinTools binTools;

  private final RunfilesTreeUpdater runfilesTreeUpdater;

  // TODO(b/62588075): Move this logic to ProcessWrapperUtil?
  private static Path getProcessWrapper(BinTools binTools, OS localOs) {
    // We expect binTools to be null only under testing.
    return binTools == null
        ? null
        : binTools.getEmbeddedPath("process-wrapper" + OsUtils.executableExtension(localOs));
  }

  private static boolean processWrapperExists(BinTools binTools) {
    Path wrapper = getProcessWrapper(binTools, OS.getCurrent());
    return wrapper != null && wrapper.exists();
  }

  public LocalSpawnRunner(
      Path execRoot,
      LocalExecutionOptions localExecutionOptions,
      ResourceManager resourceManager,
      boolean useProcessWrapper,
      OS localOs,
      LocalEnvProvider localEnvProvider,
      BinTools binTools,
      RunfilesTreeUpdater runfilesTreeUpdater) {
    this.execRoot = execRoot;
    this.processWrapper = getProcessWrapper(binTools, localOs);
    this.localExecutionOptions = Preconditions.checkNotNull(localExecutionOptions);
    this.hostName = NetUtil.getCachedShortHostName();
    this.resourceManager = resourceManager;
    this.useProcessWrapper = useProcessWrapper;
    this.localEnvProvider = localEnvProvider;
    this.binTools = binTools;
    this.runfilesTreeUpdater = runfilesTreeUpdater;
  }

  public LocalSpawnRunner(
      Path execRoot,
      LocalExecutionOptions localExecutionOptions,
      ResourceManager resourceManager,
      LocalEnvProvider localEnvProvider,
      BinTools binTools,
      RunfilesTreeUpdater runfilesTreeUpdater) {
    this(
        execRoot,
        localExecutionOptions,
        resourceManager,
        OS.getCurrent() != OS.WINDOWS && processWrapperExists(binTools),
        OS.getCurrent(),
        localEnvProvider,
        binTools,
        runfilesTreeUpdater);
  }

  @Override
  public String getName() {
    return "local";
  }

  @Override
  public SpawnResult exec(Spawn spawn, SpawnExecutionContext context)
      throws IOException, InterruptedException, ExecException {

    runfilesTreeUpdater.updateRunfilesDirectory(
        execRoot,
        spawn.getRunfilesSupplier(),
        context.getPathResolver(),
        binTools,
        spawn.getEnvironment(),
        context.getFileOutErr());

    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.LOCAL_EXECUTION, spawn.getResourceOwner().getMnemonic())) {
      ActionExecutionMetadata owner = spawn.getResourceOwner();
      context.report(ProgressStatus.SCHEDULING, getName());
      try (ResourceHandle handle =
          resourceManager.acquireResources(owner, spawn.getLocalResources())) {
        context.report(ProgressStatus.EXECUTING, getName());
        context.lockOutputFiles();
        return new SubprocessHandler(spawn, context).run();
      }
    }
  }

  @Override
  public boolean canExec(Spawn spawn) {
    return true;
  }

  protected Path createActionTemp(Path execRoot) throws IOException {
    return execRoot.getRelative(
        java.nio.file.Files.createTempDirectory(
                java.nio.file.Paths.get(execRoot.getPathString()), "local-spawn-runner.")
            .getFileName()
            .toString());
  }

  private final class SubprocessHandler {
    private final Spawn spawn;
    private final SpawnExecutionContext context;

    private final long creationTime = System.currentTimeMillis();
    private long stateStartTime = creationTime;
    private State currentState = State.INITIALIZING;
    private final Map<State, Long> stateTimes = new EnumMap<>(State.class);

    private final int id;

    public SubprocessHandler(Spawn spawn, SpawnExecutionContext context) {
      Preconditions.checkArgument(!spawn.getArguments().isEmpty());
      this.spawn = spawn;
      this.context = context;
      this.id = context.getId();
      setState(State.PARSING);
    }

    public SpawnResult run() throws InterruptedException, IOException {
      try {
        return start();
      } catch (Error e) {
        stepLog(SEVERE, e, UNHANDLED_EXCEPTION_MSG);
        throw e;
      } catch (IOException e) {
        stepLog(SEVERE, e, "Local I/O error");
        throw e;
      } catch (RuntimeException e) {
        stepLog(SEVERE, e, UNHANDLED_EXCEPTION_MSG);
        throw new RuntimeException(UNHANDLED_EXCEPTION_MSG, e);
      }
    }

    @FormatMethod
    private void stepLog(Level level, @FormatString String fmt, Object... args) {
      stepLog(level, /*cause=*/ null, fmt, args);
    }

    @FormatMethod
    private void stepLog(
        Level level, @Nullable Throwable cause, @FormatString String fmt, Object... args) {
      String msg = String.format(fmt, args);
      String toLog = String.format("%s (#%d %s)", msg, id, desc());
      logger.log(level, toLog, cause);
    }

    private String desc() {
      String progressMessage = spawn.getResourceOwner().getProgressMessage();
      return progressMessage != null
          ? progressMessage
          : "ActionType=" + spawn.getResourceOwner().getMnemonic();
    }

    private void setState(State newState) {
      long now = System.currentTimeMillis();
      long totalDelta = now - creationTime;
      long stepDelta = now - stateStartTime;
      stateStartTime = now;

      Long stateTimeBoxed = stateTimes.get(currentState);
      long stateTime = (stateTimeBoxed == null) ? 0 : stateTimeBoxed;
      stateTimes.put(currentState, stateTime + stepDelta);

      logger.info(
          String.format(
              "Step #%d time: %.3f delta: %.3f state: %s --> %s",
              id, totalDelta / 1000f, stepDelta / 1000f, currentState, newState));
      currentState = newState;
    }

    private String debugCmdString() {
      String cmd = SPACE_JOINER.join(spawn.getArguments());
      if (cmd.length() > 500) {
        // Shrink argstr by replacing middle of string with "...".
        return cmd.substring(0, 250) + "..." + cmd.substring(cmd.length() - 250);
      }
      return cmd;
    }

    /** Parse the request and run it locally. */
    private SpawnResult start() throws InterruptedException, IOException {
      logger.info(String.format("starting local subprocess #%d, argv: %s", id, debugCmdString()));

      FileOutErr outErr = context.getFileOutErr();
      String actionType = spawn.getResourceOwner().getMnemonic();
      if (localExecutionOptions.allowedLocalAction != null
          && !localExecutionOptions
              .allowedLocalAction
              .regexPattern()
              .matcher(actionType)
              .matches()) {
        setState(State.PERMANENT_ERROR);
        outErr
            .getErrorStream()
            .write(
                ("Action type "
                        + actionType
                        + " is not allowed to run locally due to regex filter: "
                        + localExecutionOptions.allowedLocalAction.regexPattern()
                        + "\n")
                    .getBytes(UTF_8));
        return new SpawnResult.Builder()
            .setRunnerName(getName())
            .setStatus(Status.EXECUTION_DENIED)
            .setExitCode(LOCAL_EXEC_ERROR)
            .setExecutorHostname(hostName)
            .build();
      }

      if (Spawns.shouldPrefetchInputsForLocalExecution(spawn)) {
        stepLog(INFO, "prefetching inputs for local execution");
        setState(State.PREFETCHING_LOCAL_INPUTS);
        context.prefetchInputs();
      }

      for (ActionInput input : spawn.getInputFiles()) {
        if (input instanceof ParamFileActionInput) {
          VirtualActionInput virtualActionInput = (VirtualActionInput) input;
          Path outputPath = execRoot.getRelative(virtualActionInput.getExecPath());
          if (outputPath.exists()) {
            outputPath.delete();
          }
          outputPath.getParentDirectory().createDirectoryAndParents();
          try (OutputStream outputStream = outputPath.getOutputStream()) {
            virtualActionInput.writeTo(outputStream);
          }
        }
      }

      stepLog(INFO, "running locally");
      setState(State.LOCAL_ACTION_RUNNING);

      Path tmpDir = createActionTemp(execRoot);
      Path statisticsPath = null;
      try {
        Path commandTmpDir = tmpDir.getRelative("work");
        commandTmpDir.createDirectory();
        Map<String, String> environment =
            localEnvProvider.rewriteLocalEnv(
                spawn.getEnvironment(), binTools, commandTmpDir.getPathString());

        SubprocessBuilder subprocessBuilder = new SubprocessBuilder();
        subprocessBuilder.setWorkingDirectory(execRoot.getPathFile());
        subprocessBuilder.setStdout(outErr.getOutputPath().getPathFile());
        subprocessBuilder.setStderr(outErr.getErrorPath().getPathFile());
        subprocessBuilder.setEnv(environment);
        List<String> args;
        if (useProcessWrapper) {
          // If the process wrapper is enabled, we use its timeout feature, which first interrupts
          // the subprocess and only kills it after a grace period so that the subprocess can output
          // a stack trace, test log or similar, which is incredibly helpful for debugging.
          ProcessWrapperUtil.CommandLineBuilder commandLineBuilder =
              ProcessWrapperUtil.commandLineBuilder(
                      processWrapper.getPathString(), spawn.getArguments())
                  .setTimeout(context.getTimeout())
                  .setKillDelay(Duration.ofSeconds(localExecutionOptions.localSigkillGraceSeconds));
          if (localExecutionOptions.collectLocalExecutionStatistics) {
            statisticsPath = tmpDir.getRelative("stats.out");
            commandLineBuilder.setStatisticsPath(statisticsPath);
          }
          args = commandLineBuilder.build();
        } else {
          subprocessBuilder.setTimeoutMillis(context.getTimeout().toMillis());
          args = spawn.getArguments();
        }
        // SubprocessBuilder does not accept relative paths for the first argument, even though
        // Command does. We sometimes get relative paths here, so we need to handle it.
        File argv0 = new File(args.get(0));
        if (!argv0.isAbsolute() && argv0.getParent() != null) {
          args = new ArrayList<>(args);
          args.set(0, new File(execRoot.getPathFile(), args.get(0)).getAbsolutePath());
        }
        subprocessBuilder.setArgv(args);

        long startTime = System.currentTimeMillis();
        TerminationStatus terminationStatus;
        try (SilentCloseable c =
            Profiler.instance()
                .profile(ProfilerTask.PROCESS_TIME, spawn.getResourceOwner().getMnemonic())) {
          Subprocess subprocess = subprocessBuilder.start();
          subprocess.getOutputStream().close();
          try {
            subprocess.waitFor();
            terminationStatus =
                new TerminationStatus(subprocess.exitValue(), subprocess.timedout());
          } catch (InterruptedException e) {
            subprocess.destroy();
            throw e;
          }
        } catch (IOException e) {
          String msg = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
          setState(State.PERMANENT_ERROR);
          outErr
              .getErrorStream()
              .write(
                  ("Action failed to execute: java.io.IOException: " + msg + "\n").getBytes(UTF_8));
          outErr.getErrorStream().flush();
          return new SpawnResult.Builder()
              .setRunnerName(getName())
              .setStatus(Status.EXECUTION_FAILED)
              .setExitCode(LOCAL_EXEC_ERROR)
              .setExecutorHostname(hostName)
              .build();
        }
        setState(State.SUCCESS);
        // TODO(b/62588075): Calculate wall time inside commands instead?
        Duration wallTime = Duration.ofMillis(System.currentTimeMillis() - startTime);
        boolean wasTimeout =
            terminationStatus.timedOut()
                || (useProcessWrapper && wasTimeout(context.getTimeout(), wallTime));
        int exitCode = wasTimeout ? POSIX_TIMEOUT_EXIT_CODE : terminationStatus.getRawExitCode();
        Status status =
            wasTimeout ? Status.TIMEOUT : (exitCode == 0 ? Status.SUCCESS : Status.NON_ZERO_EXIT);
        SpawnResult.Builder spawnResultBuilder =
            new SpawnResult.Builder()
                .setRunnerName(getName())
                .setStatus(status)
                .setExitCode(exitCode)
                .setExecutorHostname(hostName)
                .setWallTime(wallTime);
        if (statisticsPath != null) {
          ExecutionStatistics.getResourceUsage(statisticsPath)
              .ifPresent(
                  resourceUsage -> {
                    spawnResultBuilder.setUserTime(resourceUsage.getUserExecutionTime());
                    spawnResultBuilder.setSystemTime(resourceUsage.getSystemExecutionTime());
                    spawnResultBuilder.setNumBlockOutputOperations(
                        resourceUsage.getBlockOutputOperations());
                    spawnResultBuilder.setNumBlockInputOperations(
                        resourceUsage.getBlockInputOperations());
                    spawnResultBuilder.setNumInvoluntaryContextSwitches(
                        resourceUsage.getInvoluntaryContextSwitches());
                  });
        }
        return spawnResultBuilder.build();
      } finally {
        // Delete the temp directory tree, so the next action that this thread executes will get a
        // fresh, empty temp directory.
        // File deletion tends to be slow on Windows, so deleting this tree may take several
        // seconds. Delete it after having measured the wallTime.
        try {
          tmpDir.deleteTree();
        } catch (IOException ignored) {
          // We can't handle this exception in any meaningful way, nor should we, but let's log it.
          stepLog(
              WARNING,
              "failed to delete temp directory '%s'; this might indicate that the action "
                  + "created subprocesses that didn't terminate and hold files open in that "
                  + "directory",
              tmpDir);
        }
      }
    }

    private boolean wasTimeout(Duration timeout, Duration wallTime) {
      return !timeout.isZero() && wallTime.compareTo(timeout) > 0;
    }
  }

  private enum State {
    INITIALIZING,
    PARSING,
    PREFETCHING_LOCAL_INPUTS,
    LOCAL_ACTION_RUNNING,
    PERMANENT_ERROR,
    SUCCESS;
  }
}
