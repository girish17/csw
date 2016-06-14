package csw.services.cs.akka

import java.io.File

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigResolveOptions, ConfigFactory}
import csw.services.cs.akka.ConfigServiceActor._
import csw.services.cs.core._

import scala.concurrent.Future

object ConfigServiceClient {
  /**
   * Convenience method that gets the contents of the given file from the config service
   * by first looking up the config service with the location service and
   * then fetching the contents of the file using a config service client.
   * (Use only for small files.)
   *
   * @param csName the name of the config service (the name it was registered with, from it's config file)
   * @param path the path of the file in the config service
   * @param id optional id of a specific version of the file
   * @param system actor system needed to access config service
   * @param timeout time to wait for a reply
   * @return the future contents of the file as a ConfigData object, if found
   */
  def getFromConfigService(csName: String, path: File, id: Option[ConfigId] = None)(implicit system: ActorSystem, timeout: Timeout): Future[Option[ConfigData]] = {
    import system.dispatcher
    for {
      cs ← locateConfigService(csName)
      configDataOpt ← ConfigServiceClient(cs, csName).get(path, id)
    } yield configDataOpt
  }

  /**
   * Convenience method that gets the contents of the given file from the config service
   * by first looking up the config service with the location service and
   * then fetching the contents of the file using a config service client.
   * (Use only for small files.)
   *
   * @param csName the name of the config service (the name it was registered with, from it's config file)
   * @param path the path of the file in the config service
   * @param id optional id of a specific version of the file
   * @param system actor system needed to access config service
   * @param timeout time to wait for a reply
   * @return the future contents of the file as a string, if the file was found
   */
  def getStringFromConfigService(csName: String, path: File, id: Option[ConfigId] = None)(implicit system: ActorSystem, timeout: Timeout): Future[Option[String]] = {
    import system.dispatcher
    getFromConfigService(csName, path, id).flatMap { configDataOpt ⇒
      if (configDataOpt.isDefined)
        configDataOpt.get.toFutureString.map(Some(_))
      else
        Future(None)
    }
  }

  /**
   * Convenience method that gets a Typesafe Config from the config service
   * by first looking up the config service with the location service and
   * then fetching the contents of the given file using a config service client.
   * Finally, the file contents is parsed as a Typesafe config file and the
   * Config object returned.
   *
   * @param csName the name of the config service (the name it was registered with, from it's config file)
   * @param path the path of the file in the config service
   * @param id optional id of a specific version of the file
   * @param system actor system needed to access config service
   * @param timeout time to wait for a reply
   * @return the future config, parsed from the file
   */
  def getConfigFromConfigService(csName: String, path: File, id: Option[ConfigId] = None)(implicit system: ActorSystem, timeout: Timeout): Future[Option[Config]] = {
    import system.dispatcher
    for {
      s ← getStringFromConfigService(csName, path, id)
    } yield s.map(ConfigFactory.parseString(_).resolve(ConfigResolveOptions.noSystem()))
  }
}

/**
 * Adds a convenience layer over the Akka actor interface to the configuration service.
 * Note:Only one instance of this class should exist for a given repository.
 *
 * @param configServiceActor the config service actor reference to use (Get from location service, if needed)
 * @param name               the config service name (defaults to "Config Service")
 * @param system             the caller's actor system
 * @param timeout            amount of time to wait for config service operations to complete
 */
case class ConfigServiceClient(configServiceActor: ActorRef, name: String = "Config Service")(implicit system: ActorSystem, timeout: Timeout) extends ConfigManager {

  //  val settings = ConfigServiceSettings(system)
  //  override val name = settings.name

  import system.dispatcher

  override def create(path: File, configData: ConfigData, oversize: Boolean, comment: String): Future[ConfigId] =
    (configServiceActor ? CreateRequest(path, configData, oversize, comment))
      .mapTo[CreateOrUpdateResult].map(_.configId.get)

  override def update(path: File, configData: ConfigData, comment: String): Future[ConfigId] =
    (configServiceActor ? UpdateRequest(path, configData, comment)).
      mapTo[CreateOrUpdateResult].map(_.configId.get)

  override def createOrUpdate(path: File, configData: ConfigData, oversize: Boolean, comment: String): Future[ConfigId] =
    (configServiceActor ? CreateOrUpdateRequest(path, configData, oversize, comment))
      .mapTo[CreateOrUpdateResult].map(_.configId.get)

  override def get(path: File, id: Option[ConfigId] = None): Future[Option[ConfigData]] =
    (configServiceActor ? GetRequest(path, id)).mapTo[GetResult].map(_.configData.get)

  override def exists(path: File): Future[Boolean] =
    (configServiceActor ? ExistsRequest(path)).mapTo[ExistsResult].map(_.exists.get)

  override def delete(path: File, comment: String): Future[Unit] =
    (configServiceActor ? DeleteRequest(path)).mapTo[UnitResult].map(_.status.get)

  override def list(): Future[List[ConfigFileInfo]] =
    (configServiceActor ? ListRequest).mapTo[ListResult].map(_.list.get)

  override def history(path: File, maxResults: Int = Int.MaxValue): Future[List[ConfigFileHistory]] =
    (configServiceActor ? HistoryRequest(path, maxResults)).mapTo[HistoryResult].map(_.history.get)

  override def setDefault(path: File, id: Option[ConfigId]): Future[Unit] =
    (configServiceActor ? SetDefaultRequest(path, id)).mapTo[UnitResult].map(_.status.get)

  override def resetDefault(path: File): Future[Unit] =
    (configServiceActor ? ResetDefaultRequest(path)).mapTo[UnitResult].map(_.status.get)

  override def getDefault(path: File): Future[Option[ConfigData]] =
    (configServiceActor ? GetDefaultRequest(path)).mapTo[GetResult].map(_.configData.get)
}

