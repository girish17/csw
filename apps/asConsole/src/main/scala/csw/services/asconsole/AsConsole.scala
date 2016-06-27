package csw.services.asconsole

import java.io.File

import akka.actor.ActorSystem
import akka.util.Timeout
import ch.qos.logback.classic.Logger
import csw.services.loc.LocationService
import ch.qos.logback.classic._
import csw.services.alarms.AlarmService
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * A command line application that locates the Redis instance used for the Alarm Service (using the Location Service)
 * and performs tasks based on the command line options, such as initialize or display the list of alarms.
 */
object AsConsole extends App {
  // Don't want too much (any?) logging in command line app
  LoggerFactory.getLogger("root").asInstanceOf[Logger].setLevel(Level.ERROR)
  LoggerFactory.getLogger("csw").asInstanceOf[Logger].setLevel(Level.ERROR)

  LocationService.initInterface()

  // Needed for use with Futures
  implicit val system = ActorSystem("AsConsole")

  // Timeout when waiting for a future
  implicit val timeout = Timeout(60.seconds)

  /**
   * Command line options ("asconsole --help" prints a usage message with descriptions of all the options)
   * See val parser below for descriptions of the options.
   */
  private case class Options(
    asName:     Option[String] = None, // Alarm Service name
    ascf:       Option[File]   = None, // Alarm Service Config File (ASCF)
    listAlarms: Boolean        = false,
    shutdown:   Boolean        = false,
    subsystem:  Option[String] = None,
    component:  Option[String] = None,
    name:       Option[String] = None // Alarm name (with wildcards)
  )

  // XXX TODO: Add options for --list output format: pdf, html, json, config, text?

  // XXX TODO: Add option to set/display severity for an alarm

  // Parses the command line options
  private val parser = new scopt.OptionParser[Options]("asconsole") {
    head("asconsole", System.getProperty("CSW_VERSION"))

    opt[String]("as-name") valueName "<name>" action { (x, c) ⇒
      c.copy(asName = Some(x))
    } text "The name that was used to register the Alarm Service Redis instance (Default: 'Alarm Service')"

    opt[File]("init") valueName "<alarm-service-config-file>" action { (x, c) ⇒
      c.copy(ascf = Some(x))
    } text "Initialize the set of available alarms from the given Alarm Service Config File (ASCF)"

    opt[Unit]("list").action((_, c) ⇒
      c.copy(listAlarms = true)).text("Prints a list of all alarms (See other options to filter what is printed)")

    opt[Unit]("shutdown").action((_, c) ⇒
      c.copy(shutdown = true)).text("Shuts down the Alarm Service Redis instance")

    opt[String]('c', "component") valueName "<name>" action { (x, c) ⇒
      c.copy(component = Some(x))
    } text "Limits the alarms returned by --list to the given component (subsystem must also be specified)"

    opt[String]('s', "subsystem") valueName "<subsystem>" action { (x, c) ⇒
      c.copy(subsystem = Some(x))
    } text "Limits the alarms returned by --list to the given subsystem"

    opt[String]('s', "name") valueName "<name>" action { (x, c) ⇒
      c.copy(name = Some(x))
    } text "Limits the alarms returned by --list to those whose name field matches the given value (may contain Redis wildcards)"

    help("help")
    version("version")
  }

  // Parse the command line options
  parser.parse(args, Options()) match {
    case Some(options) ⇒
      try {
        run(options)
      } catch {
        case e: Throwable ⇒
          e.printStackTrace()
          System.exit(1)
      }
    case None ⇒ System.exit(1)
  }

  // Uses the given Alarm Service Redis instance to act on the command line options
  private def run(options: Options): Unit = {
    import AlarmService.Problem

    val alarmService = Await.result(AlarmService(options.asName), timeout.duration)

    // Handle the --init option
    options.ascf foreach { file ⇒
      val problems = Await.result(alarmService.initAlarms(file), timeout.duration)
      Problem.printProblems(problems)
      if (Problem.errorCount(problems) != 0) System.exit(1)
    }

    // Handle the --list option
    if (options.listAlarms) {
      val alarms = Await.result(alarmService.getAlarms(options.subsystem, options.component, options.name), timeout.duration)
      alarms.foreach { alarm ⇒
        // XXX TODO: add format options
        println(s"Alarm: $alarm")
      }
    }

    if (options.shutdown) {
      println(s"Shutting down the alarm service")
      alarmService.shutdown()
    }

    // Shutdown and exit
    system.terminate()
    System.exit(0)
  }
}
