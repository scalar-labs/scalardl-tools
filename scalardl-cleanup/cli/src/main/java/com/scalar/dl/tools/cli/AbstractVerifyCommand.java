package com.scalar.dl.tools.cli;

import com.scalar.dl.tools.common.VerificationResult;
import java.util.Properties;

/**
 * Base class for the verify subcommands, which only read the database and keep no checkpoint. It
 * delegates to {@link #verify(Properties)} and translates the verdict into the JSON output and the
 * exit code.
 */
public abstract class AbstractVerifyCommand extends AbstractCommand {

  /**
   * Exit code returned when the verification found something that still needs finalization. It
   * differs from {@link #FAILURE_EXIT_CODE}, which means that no verdict was reached.
   */
  public static final int VERIFICATION_FAILED_EXIT_CODE = 2;

  @Override
  final Integer run(Properties props) throws Exception {
    VerificationResult result = verify(props);
    // The verdict is reported as a return value, not an exception, so that it is not logged with a
    // stack trace like a failure to reach a verdict.
    if (result == VerificationResult.FAILED) {
      Common.printVerificationFailure(failureMessage());
      return VERIFICATION_FAILED_EXIT_CODE;
    }
    Common.printOutput(null);
    return SUCCESS_EXIT_CODE;
  }

  /**
   * Runs the verification against the loaded configuration. The implementation must release its
   * resources before returning so that a failure while releasing them cannot follow a verdict.
   *
   * @param props the loaded properties
   * @return the verdict
   * @throws Exception if no verdict was reached
   */
  protected abstract VerificationResult verify(Properties props) throws Exception;

  /** Returns the message that tells the operator what was found and what to do about it. */
  protected abstract String failureMessage();
}
