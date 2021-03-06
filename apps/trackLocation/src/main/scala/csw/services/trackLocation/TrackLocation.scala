package csw.services.trackLocation

import java.io.File
import java.net.ServerSocket

import akka.actor.ActorSystem
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import csw.services.cs.akka.BlockingConfigServiceClient
import csw.services.loc.{ComponentId, ComponentType, LocationService}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
 * A utility application that starts a given external program, registers it with the location service and
 * unregisters it when the program exits.
 */
object TrackLocation extends App {
  LocationService.initInterface()

  // Needed for use with Futures
  implicit val system = ActorSystem("TrackLocation")

  implicit val timeout = Timeout(10.seconds)

  /**
   * Command line options ("trackLocation --help" prints a usage message with descriptions of all the options)
   * See val parser below for descriptions of the options.
   */
  private case class Options(
    names:         List[String]   = Nil,
    command:       Option[String] = None,
    port:          Option[Int]    = None,
    appConfigFile: Option[File]   = None,
    delay:         Option[Int]    = None,
    noExit:        Boolean        = false
  )

  // Parses the command line options
  private val parser = new scopt.OptionParser[Options]("trackLocation") {
    head("trackLocation", System.getProperty("CSW_VERSION"))

    opt[String]("name") valueName "<name1>[,<name2>,...]" action { (x, c) =>
      c.copy(names = x.split(',').toList)
    } text "Required: The name (or names, separated by comma) used to register the application (also root name in config file)"

    opt[String]('c', "command") valueName "<name>" action { (x, c) =>
      c.copy(command = Some(x))
    } text "The command that starts the target application: use %port to insert the port number (default: use $name.command from config file: Required)"

    opt[Int]('p', "port") valueName "<number>" action { (x, c) =>
      c.copy(port = Some(x))
    } text "Optional port number the application listens on (default: use value of $name.port from config file, or use a random, free port.)"

    arg[File]("<app-config>") optional () maxOccurs 1 action { (x, c) =>
      c.copy(appConfigFile = Some(x))
    } text "optional config file in HOCON format (Options specified as: $name.command, $name.port, etc. Fetched from config service if path does not exist)"

    opt[Int]("delay") action { (x, c) =>
      c.copy(delay = Some(x))
    } text "number of milliseconds to wait for the app to start before registering it with the location service (default: 1000)"

    opt[Unit]("no-exit") action { (_, c) =>
      c.copy(noExit = true)
    } text "for testing: prevents application from exiting after running command"

    help("help")
    version("version")
  }

  // Parse the command line options
  parser.parse(args, Options()) match {
    case Some(options) =>
      try {
        run(options)
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          System.exit(1)
      }
    case None => System.exit(1)
  }

  // Report error and exit
  private def error(msg: String): Unit = {
    println(msg)
    System.exit(1)
  }

  // Gets the application config from the file, if it exists, or from the config service, if it exists there.
  // If neither exist, an error is reported.
  private def getAppConfig(file: File): Config = {
    if (file.exists())
      ConfigFactory.parseFileAnySyntax(file).resolve(ConfigResolveOptions.noSystem())
    else
      getFromConfigService(file)
  }

  // Gets the named config file from the config service or reports an error if it does not exist.
  // Uses the given config service name to find the config service, if not empty.
  private def getFromConfigService(file: File): Config = {
    val configOpt = BlockingConfigServiceClient.getConfigFromConfigService(file)
    if (configOpt.isEmpty)
      error(s"$file not found locally or from the config service")
    configOpt.get
  }

  // Run the application
  private def run(options: Options): Unit = {
    if (options.names.isEmpty) error("Please specify one or more application names, separated by commas")
    //. Get the app config file, if given
    val appConfig = options.appConfigFile.map(getAppConfig)

    // Gets the String value of an option from the command line or the app's config file, or None if not found
    def getStringOpt(opt: String, arg: Option[String] = None, required: Boolean = true): Option[String] = {
      val value = if (arg.isDefined) arg
      else {
        appConfig.flatMap { c =>
          // XXX: Using only first name here
          val path = s"${options.names.head}.$opt"
          if (c.hasPath(path)) Some(c.getString(path)) else None
        }
      }
      if (value.isDefined) value
      else {
        if (required) error(s"Missing required '$opt' option or setting")
        None
      }
    }

    // Gets the Int value of an option from the command line or the config file, or None if not found
    def getIntOpt(opt: String, arg: Option[Int] = None, required: Boolean = true): Option[Int] = {
      getStringOpt(opt, arg.map(_.toString), required).map(_.toInt)
    }

    // Find a random, free port to use
    def getFreePort: Int = {
      val sock = new ServerSocket(0)
      val port = sock.getLocalPort
      sock.close()
      port
    }

    // Use the value of the --port option, or use a random, free port
    val port = getIntOpt("port", options.port, required = false).getOrElse(getFreePort)

    // Replace %port in the command
    val command = getStringOpt("command", options.command).get.replace("%port", port.toString)

    startApp(options.names, command, port, options.delay.getOrElse(1000), options.noExit)
  }

  // Starts the command and registers it with the given name on the given port
  private def startApp(names: List[String], command: String, port: Int, delay: Int, noExit: Boolean): Unit = {
    import scala.sys.process._
    import system.dispatcher

    // Register all the names given for the application with the location service
    def registerNames = Future.sequence(names.map(name => LocationService.registerTcpConnection(ComponentId(name, ComponentType.Service), port)))

    // Insert a delay before registering with the location service to give the app a chance to start
    val f = for {
      _ <- Future { Thread.sleep(delay) }
      reg <- registerNames
    } yield reg

    // Run the command and wait for it to exit
    val exitCode = command.!

    println(s"$command exited with exit code $exitCode")

    // Unregister from the location service and exit
    val registration = Await.result(f, timeout.duration)
    registration.foreach(_.unregister())

    if (!noExit) System.exit(exitCode)
  }

}

