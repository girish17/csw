package javacsw.services.events

import java.util.concurrent.CompletableFuture

import akka.actor.ActorRefFactory
import csw.services.events.TelemetryServiceAdmin
import scala.compat.java8.FutureConverters._

import scala.concurrent.ExecutionContext

object JTelemetryServiceAdmin {
  /**
   * Starts a redis instance on a random free port (redis-server must be in your shell path)
   * and registers it with the location service.
   * This is the equivalent of running this from the command line:
   *   {{{
   *   tracklocation --name "Telemetry Service Test" --command "redis-server --port %port" --no-exit
   *   }}}
   *
   * This method is mainly for use by tests. In production, you would use the tracklocation app
   * to start Redis once.
   *
   * @param name The name to use to register the telemetry service with the location service
   * @param noExit if true, do not exit the application when redis exists
   * @param ec required for futures
   * @return a future that completes when the redis server exits
   */
  def startTelemetryService(name: String, noExit: Boolean, ec: ExecutionContext): CompletableFuture[Unit] = {
    TelemetryServiceAdmin.startTelemetryService(name, noExit)(ec).toJava.toCompletableFuture
  }
}

/**
 * Implements the java admin API for Telemetry Service
 */
class JTelemetryServiceAdmin(telemetryService: ITelemetryService, system: ActorRefFactory) extends ITelemetryServiceAdmin {

  import system.dispatcher
  implicit val sys = system

  val telemetryAdmin = TelemetryServiceAdmin(telemetryService.asInstanceOf[JTelemetryService].ts)

  override def shutdown(): CompletableFuture[Unit] = telemetryAdmin.shutdown().toJava.toCompletableFuture
}
