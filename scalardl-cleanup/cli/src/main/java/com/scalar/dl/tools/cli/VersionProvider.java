package com.scalar.dl.tools.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies the {@code --version} output. The version number is read from the JAR manifest's {@code
 * Implementation-Version}, which the build populates from the Gradle project version.
 */
public class VersionProvider implements IVersionProvider {

  private static final String PRODUCT_NAME = "ScalarDL Cleanup";

  @Override
  public String[] getVersion() {
    Package pkg = getClass().getPackage();
    String version = pkg == null ? null : pkg.getImplementationVersion();
    return new String[] {PRODUCT_NAME + " " + (version == null ? "(development build)" : version)};
  }
}
