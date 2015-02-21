/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.MissingCommandException;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Closeables;
import org.apache.log4j.Level;
import org.apache.log4j.PropertyConfigurator;
import org.kitesdk.cli.commands.CSVImportCommand;
import org.kitesdk.cli.commands.CSVSchemaCommand;
import org.kitesdk.cli.commands.CopyCommand;
import org.kitesdk.cli.commands.CreateColumnMappingCommand;
import org.kitesdk.cli.commands.CreateDatasetCommand;
import org.kitesdk.cli.commands.CreatePartitionStrategyCommand;
import org.kitesdk.cli.commands.DeleteCommand;
import com.google.common.collect.ImmutableSet;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.kitesdk.cli.commands.FlumeConfigCommand;
import org.kitesdk.cli.commands.InfoCommand;
import org.kitesdk.cli.commands.InputFormatImportCommand;
import org.kitesdk.cli.commands.JSONImportCommand;
import org.kitesdk.cli.commands.JSONSchemaCommand;
import org.kitesdk.cli.commands.Log4jConfigCommand;
import org.kitesdk.cli.commands.MergeSchemasCommand;
import org.kitesdk.cli.commands.ObjectSchemaCommand;
import org.kitesdk.cli.commands.SchemaCommand;
import org.kitesdk.cli.commands.ShowRecordsCommand;
import org.kitesdk.cli.commands.TransformCommand;
import org.kitesdk.cli.commands.TarImportCommand;
import org.kitesdk.cli.commands.UpdateDatasetCommand;
import org.kitesdk.data.DatasetIOException;
import org.kitesdk.data.DatasetNotFoundException;
import org.kitesdk.data.ValidationException;
import org.kitesdk.data.spi.DefaultConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Parameters(commandDescription = "Kite dataset management utility")
public class Main extends Configured implements Tool {

  @Parameter(names = {"-v", "--verbose", "--debug"},
      description = "Print extra debugging information")
  private boolean debug = false;

  @Parameter(names = {"--version"},
      description = "Print Kite version and exit")
  private boolean printVersion = false;

  @VisibleForTesting
  @Parameter(names="--dollar-zero",
      description="A way for the runtime path to be passed in", hidden=true)
  String programName = DEFAULT_PROGRAM_NAME;

  @VisibleForTesting
  static final String DEFAULT_PROGRAM_NAME = "kite-dataset";

  private static Set<String> HELP_ARGS = ImmutableSet.of("-h", "-help", "--help", "help");

  private final Logger console;
  private final Help help;

  @VisibleForTesting
  final JCommander jc;

  Main(Logger console) {
    this.console = console;
    this.jc = new JCommander(this);
    this.help = new Help(jc, console);
    jc.setProgramName(DEFAULT_PROGRAM_NAME);
    jc.addCommand("help", help, "-h", "-help", "--help");
    jc.addCommand("create", new CreateDatasetCommand(console));
    jc.addCommand("copy", new CopyCommand(console));
    jc.addCommand("transform", new TransformCommand(console));
    jc.addCommand("update", new UpdateDatasetCommand(console));
    jc.addCommand("delete", new DeleteCommand(console));
    jc.addCommand("schema", new SchemaCommand(console));
    jc.addCommand("info", new InfoCommand(console));
    jc.addCommand("show", new ShowRecordsCommand(console));
    jc.addCommand("merge-schemas", new MergeSchemasCommand(console));
    jc.addCommand("obj-schema", new ObjectSchemaCommand(console));
    jc.addCommand("inputformat-import", new InputFormatImportCommand(console));
    jc.addCommand("csv-schema", new CSVSchemaCommand(console));
    jc.addCommand("csv-import", new CSVImportCommand(console));
    jc.addCommand("json-schema", new JSONSchemaCommand(console));
    jc.addCommand("json-import", new JSONImportCommand(console));
    jc.addCommand("partition-config", new CreatePartitionStrategyCommand(console));
    jc.addCommand("mapping-config", new CreateColumnMappingCommand(console));
    jc.addCommand("log4j-config", new Log4jConfigCommand(console));
    jc.addCommand("flume-config", new FlumeConfigCommand(console));
    jc.addCommand("tar-import", new TarImportCommand(console));
  }

  @Override
  public int run(String[] args) throws Exception {
    if (getConf() != null) {
      DefaultConfiguration.set(getConf());
    }

    try {
      jc.parse(args);
    } catch (MissingCommandException e) {
      console.error(e.getMessage());
      return 1;
    } catch (ParameterException e) {
      help.setProgramName(programName);
      String cmd = jc.getParsedCommand();
      if (args.length == 1) { // i.e., just the command (missing required arguments)
        help.helpCommands.add(cmd);
        help.run();
        return 1;
      } else { // check for variants like 'cmd --help' etc.
        for (String arg : args) {
          if (HELP_ARGS.contains(arg)) {
            help.helpCommands.add(cmd);
            help.run();
            return 0;
          }
        }
      }
      console.error(e.getMessage());
      return 1;
    }

    help.setProgramName(programName);

    if (printVersion) {
      console.info("Kite version \"{}\"", getVersion());
      return 0;
    }

    // configure log4j
    if (debug) {
      org.apache.log4j.Logger console = org.apache.log4j.Logger.getLogger(Main.class);
      console.setLevel(Level.DEBUG);
    }

    String parsed = jc.getParsedCommand();
    if (parsed == null) {
      help.run();
      return 1;
    } else if ("help".equals(parsed)) {
      return help.run();
    }

    Command command = (Command) jc.getCommands().get(parsed).getObjects().get(0);
    if (command == null) {
      help.run();
      return 1;
    }

    try {
      if (command instanceof Configurable) {
        ((Configurable) command).setConf(getConf());
      }
      return command.run();
    } catch (IllegalArgumentException e) {
      if (debug) {
        console.error("Argument error", e);
      } else {
        console.error("Argument error: {}", e.getMessage());
      }
      return 1;
    } catch (IllegalStateException e) {
      if (debug) {
        console.error("State error", e);
      } else {
        console.error("State error: {}", e.getMessage());
      }
      return 1;
    } catch (ValidationException e) {
      if (debug) {
        console.error("Validation error", e);
      } else {
        console.error("Validation error: {}", e.getMessage());
      }
      return 1;
    } catch (DatasetNotFoundException e) {
      if (debug) {
        console.error("Cannot find dataset", e);
      } else {
        // the error message already contains "No such dataset: <name>"
        console.error(e.getMessage());
      }
      return 1;
    } catch (DatasetIOException e) {
      if (debug) {
        console.error("IO error", e);
      } else {
        console.error("IO error: {}", e.getMessage());
      }
      return 1;
    } catch (Exception e) {
      if (debug) {
        console.error("Unknown error", e);
      } else {
        console.error("Unknown error: {}", e.getMessage());
      }
      return 1;
    }
  }

  @SuppressWarnings({"BroadCatchBlock", "TooBroadCatch"})
  private String getVersion() {
    String location = "/META-INF/maven/org.kitesdk/kite-tools/pom.properties";
    String version = "unknown";
    InputStream pomPropertiesStream = null;
    try {
      Properties pomProperties = new Properties();
      pomPropertiesStream = Main.class.getResourceAsStream(location);
      pomProperties.load(pomPropertiesStream);

      version = pomProperties.getProperty("version");
    } catch (Exception ex) {
      if (debug) {
        console.warn("Unable to determine version from the {} file", location);
        console.warn("Exception:", ex);
      } else {
        console.warn("Unable to determine version from the {} file: {}",
            location, ex.getMessage());
      }
    } finally {
      Closeables.closeQuietly(pomPropertiesStream);
    }

    return version;
  }

  public static void main(String[] args) throws Exception {
    // reconfigure logging with the kite CLI configuration
    PropertyConfigurator.configure(
        Main.class.getResource("/kite-cli-logging.properties"));
    Logger console = LoggerFactory.getLogger(Main.class);
    int rc = ToolRunner.run(new Configuration(), new Main(console), args);
    System.exit(rc);
  }
}
