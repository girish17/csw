package csw.services.loc

import java.net.{Inet6Address, InetAddress, NetworkInterface, URI}
import java.util.Optional
import javax.jmdns._

import akka.actor._
import akka.serialization.Serialization
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import csw.services.loc.Connection.{AkkaConnection, HttpConnection, TcpConnection}
import csw.services.loc.LocationTrackerWorker.LocationsReady
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import collection.JavaConverters._
import scala.compat.java8.OptionConverters._

/**
 * Location Service based on Multicast DNS (AppleTalk, Bonjour).
 *
 * The Location Service is based on Multicast DNS (AppleTalk, Bonjour) and can be used to register and lookup
 * akka and http based services in the local network.
 *
 * Every application using the location service should call the initInterface() method once at startup,
 * before creating any actors.
 *
 * Note: On a mac, you can use the command line tool dns-sd to browse the registered services.
 * On Linux use avahi-browse.
 */
object LocationService {

  private val logger = Logger(LoggerFactory.getLogger(LocationService.getClass))

  // Share the JmDNS instance within this jvm for better performance
  // (Note: Using lazy initialization, since this should run after calling initInterface() below
  private lazy val registry = getRegistry

  // Used to log a warning if initInterface was not called before registering
  private var initialized = false

  /**
   * Sets the "akka.remote.netty.tcp.hostname" and net.mdns.interface system properties, if not already
   * set on the command line (with -D), so that any services or akka actors created will use and publish the correct IP address.
   * This method should be called before creating any actors or web services that depend on the location service.
   *
   * Note that calling this method overrides any setting for akka.remote.netty.tcp.hostname in the akka config file.
   * Since the application config is immutable and cached once it is loaded, I can't think of a way to take the config
   * setting into account here. This should not be a problem, since we don't want to hard code host names anyway.
   */
  def initInterface(): Unit = {
    if (!initialized) {
      initialized = true
      case class Addr(index: Int, addr: InetAddress)
      def defaultAddr = Addr(0, InetAddress.getLocalHost)

      def filter(a: Addr): Boolean = {
        // Don't use ipv6 addresses yet, since it seems to not be working with the current akka version
        !a.addr.isLoopbackAddress && !a.addr.isInstanceOf[Inet6Address]
      }

      // Get this host's primary IP address.
      // Note: The trick to getting the right one seems to be in sorting by network interface index
      // and then ignoring the loopback address.
      // I'm assuming that the addresses are sorted by network interface priority (which seems to be the case),
      // although this is not documented anywhere.
      def getIpAddress: String = {
        import scala.collection.JavaConverters._
        val addresses = for {
          i <- NetworkInterface.getNetworkInterfaces.asScala.filter(iface => iface.isUp && iface.supportsMulticast)
          a <- i.getInetAddresses.asScala
        } yield Addr(i.getIndex, a)
        addresses.toList.sortWith(_.index < _.index).find(filter).getOrElse(defaultAddr).addr.getHostAddress
      }

      val cswHost = Option(System.getenv("CSW_HOST"))
      val akkaKey = "akka.remote.netty.tcp.hostname"
      val mdnsKey = "net.mdns.interface"
      val mdnsHost = Option(System.getProperty(mdnsKey))
      mdnsHost.foreach(h => logger.debug(s"Found system property for $mdnsKey: $h"))
      val akkaHost = Option(System.getProperty(akkaKey))
      akkaHost.foreach(h => logger.debug(s"Found system property for: $akkaKey: $h"))
      val host = cswHost.getOrElse(akkaHost.getOrElse(mdnsHost.getOrElse(getIpAddress)))
      logger.debug(s"Using $host as listening IP address")
      System.setProperty(akkaKey, host)
      System.setProperty(mdnsKey, host)
    }
  }

  // Get JmDNS instance
  private def getRegistry: JmDNS = {
    if (!initialized) logger.warn("LocationService.initInterface() should be called once before using this class or starting any actors!")
    val hostname = Option(System.getProperty("akka.remote.netty.tcp.hostname"))
    val registry = if (hostname.isDefined) {
      val addr = InetAddress.getByName(hostname.get)
      JmDNS.create(addr, hostname.get)
    } else {
      JmDNS.create()
    }
    logger.debug(s"Using host = ${registry.getHostName} (${registry.getInetAddress})")
    sys.addShutdownHook(registry.close())
    registry
  }

  /**
   * Represents a registered connection to a service
   */
  sealed trait Registration {
    def connection: Connection
  }

  /**
   * Represents a registered connection to an Akka service
   */
  final case class AkkaRegistration(connection: AkkaConnection, component: ActorRef, prefix: String = "") extends Registration

  /**
   * Represents a registered connection to a HTTP based service
   */
  final case class HttpRegistration(connection: HttpConnection, port: Int, path: String) extends Registration

  /**
   * Represents a registered connection to a TCP based service
   */
  final case class TcpRegistration(connection: TcpConnection, port: Int) extends Registration

  // Multicast DNS service type
  private val dnsType = "_csw._tcp.local."

  // -- Keys used to store values in DNS records --

  // URI path part
  private val PATH_KEY = "path"

  // Akka system name
  private val SYSTEM_KEY = "system"

  // Indicates the part of a command service config that this service is interested in
  private val PREFIX_KEY = "prefix"

  case class ComponentRegistered(connection: Connection, result: RegistrationResult)

  case class TrackConnection(connection: Connection)

  case class UntrackConnection(connection: Connection)

  sealed trait Location {
    def connection: Connection

    val isResolved: Boolean = false
    val isTracked: Boolean = true
  }

  final case class UnTrackedLocation(connection: Connection) extends Location {
    override val isTracked = false
  }

  final case class Unresolved(connection: Connection) extends Location

  final case class ResolvedAkkaLocation(connection: AkkaConnection, uri: URI, prefix: String = "", actorRef: Option[ActorRef] = None) extends Location {
    override val isResolved = true

    /**
     * Java constructor
     */
    def this(connection: AkkaConnection, uri: URI, prefix: String, actorRef: Optional[ActorRef]) = this(connection, uri, prefix, actorRef.asScala)

    /**
     * Java API to get actorRef
     *
     * @return
     */
    def getActorRef: Optional[ActorRef] = actorRef.asJava
  }

  final case class ResolvedHttpLocation(connection: HttpConnection, uri: URI, path: String) extends Location {
    override val isResolved = true
  }

  final case class ResolvedTcpLocation(connection: TcpConnection, host: String, port: Int) extends Location {
    override val isResolved = true
  }

  /**
   * Returned from register calls so that client can close the connection and deregister the service
   */
  trait RegistrationResult {
    /**
     * Unregisters the previously registered service.
     * Note that all services are automatically unregistered on shutdown.
     */
    def unregister(): Unit

    /**
     * Identifies the registered component
     */
    val componentId: ComponentId
  }

  private case class RegisterResult(registry: JmDNS, info: ServiceInfo, componentId: ComponentId) extends RegistrationResult {
    override def unregister(): Unit = registry.unregisterService(info)
  }

  /**
   * Registers a component connection with the location sevice.
   * The component will automatically be unregistered when the vm exists or when
   * unregister() is called on the result of this method.
   *
   * @param reg    component registration information
   * @param system akka system
   * @return a future result that completes when the registration has completed and can be used to unregister later
   */
  def register(reg: Registration)(implicit system: ActorSystem): Future[RegistrationResult] = {
    reg match {
      case AkkaRegistration(connection, component, prefix) =>
        registerAkkaConnection(connection.componentId, component, prefix)

      case HttpRegistration(connection, port, path) =>
        registerHttpConnection(connection.componentId, port, path)

      case TcpRegistration(connection, port) => registerTcpConnection(connection.componentId, port)
    }
  }

  /**
   * Registers the given service for the local host and the given port
   * (The full name of the local host will be used)
   *
   * @param componentId describes the component or service
   * @param actorRef    the actor reference for the actor being registered
   * @param prefix      indicates the part of a command service config that this service is interested in
   */

  def registerAkkaConnection(componentId: ComponentId, actorRef: ActorRef, prefix: String = "")(implicit system: ActorSystem): Future[RegistrationResult] = {
    import system.dispatcher
    val connection = AkkaConnection(componentId)
    Future {
      val uri = getActorUri(actorRef, system)
      val values = Map(
        PATH_KEY -> uri.getPath,
        SYSTEM_KEY -> uri.getUserInfo,
        PREFIX_KEY -> prefix
      )
      val service = ServiceInfo.create(dnsType, connection.toString, uri.getPort, 0, 0, values.asJava)
      registry.registerService(service)
      logger.debug(s"Registered Akka $connection at $uri with $values")
      RegisterResult(registry, service, componentId)
    }
  }

  /**
   * Registers the given service for the local host and the given port
   * (The full name of the local host will be used)
   *
   * @param componentId describes the component or service
   * @param port        the port the service is running on
   * @param path        the path part of the URI (default: empty)
   * @return an object that can be used to close the connection and unregister the service
   */
  def registerHttpConnection(componentId: ComponentId, port: Int, path: String = "")(implicit system: ActorSystem): Future[RegistrationResult] = {
    import system.dispatcher
    val connection = HttpConnection(componentId)
    Future {
      val values = Map(
        PATH_KEY -> path
      )
      val service = ServiceInfo.create(dnsType, connection.toString, port, 0, 0, values.asJava)
      registry.registerService(service)
      logger.debug(s"Registered HTTP $connection")
      RegisterResult(registry, service, componentId)
    }
  }

  /**
   * Registers the given service as a Service for the local host and the given port
   * (The full name of the local host will be used)
   *
   * @param componentId describes the component or service
   * @param port        the port the service is running on
   * @return h object that can be used to close the connection and unregister the service
   */
  def registerTcpConnection(componentId: ComponentId, port: Int)(implicit system: ActorSystem): Future[RegistrationResult] = {
    import system.dispatcher
    val connection = TcpConnection(componentId)
    Future {
      val values = Map(
        PATH_KEY -> ""
      )
      val service = ServiceInfo.create(dnsType, connection.toString, port, 0, 0, values.asJava)
      println("Service: " + service)
      registry.registerService(service)
      logger.debug(s"Registered TCP $connection")
      RegisterResult(registry, service, componentId)
    }
  }

  /**
   * Unregisters the connection from the location service
   * (Note: it can take some time before the service is removed from the list: see
   * comments in registry.unregisterService())
   */
  def unregisterConnection(connection: Connection): Unit = {
    import scala.collection.JavaConverters._
    logger.debug(s"Unregistered connection: $connection")
    val si = ServiceInfo.create(dnsType, connection.toString, 0, 0, 0, Map.empty[String, String].asJava)
    registry.unregisterService(si)
  }

  /**
   * An Exception representing failure in registering non remote actors
   */
  case class LocalAkkaActorRegistrationNotAllowed(actorRef: ActorRef) extends RuntimeException(
    s"Registration of only remote actors is allowed. Instead local actor $actorRef received."
  )

  // Gets the full URI for the actor
  private def getActorUri(actorRef: ActorRef, system: ActorSystem): URI = {
    val actorPath = ActorPath.fromString(Serialization.serializedActorPath(actorRef))
    actorPath.address match {
      case Address(_, _, None, None) => throw LocalAkkaActorRegistrationNotAllowed(actorRef)
      case _                         => new URI(actorPath.toString)
    }
  }

  /**
   * Convenience method that gets the location service information for a given set of services.
   *
   * @param connections set of requested connections
   * @param system      the caller's actor system
   * @return a future object describing the services found
   */
  def resolve(connections: Set[Connection])(implicit system: ActorRefFactory, timeout: Timeout): Future[LocationsReady] = {
    import akka.pattern.ask
    val actorRef = system.actorOf(LocationTrackerWorker.props(None))
    (actorRef ? LocationTrackerWorker.TrackConnections(connections)).mapTo[LocationsReady]
  }

  object RegistrationTracker {
    /**
     * Used to create the RegistrationTracker actor
     *
     * @param registration Set of registrations to be registered with Location Service
     * @param replyTo      optional actorRef to reply to (default: parent of this actor)
     */
    def props(registration: Set[Registration], replyTo: Option[ActorRef] = None): Props =
      Props(classOf[RegistrationTracker], registration, replyTo)
  }

  /**
   * An actor that tracks registration of one or more connections and replies with a
   * ComponentRegistered message when done.
   *
   * @param registration Set of registrations to be registered with Location Service
   * @param replyTo      optional actorRef to reply to (default: parent of this actor)
   */
  case class RegistrationTracker(registration: Set[Registration], replyTo: Option[ActorRef]) extends Actor with ActorLogging {

    import context.dispatcher

    implicit val system: ActorSystem = context.system

    private val a = self
    private val actorRef = replyTo.getOrElse(context.parent)
    Future.sequence(registration.toList.map(LocationService.register)).onComplete {
      case Success(list) => registration.foreach { r =>
        val c = r.connection
        log.debug(s"Successful register of connection: $c")
        list.find(_.componentId == c.componentId).foreach { result =>
          actorRef ! ComponentRegistered(c, result)
        }
        system.stop(a)
      }
      case Failure(ex) =>
        val failed = registration.map(_.connection)
        log.error(s"Registration failed for $failed", ex)
        system.stop(a)
    }

    def receive: Receive = {
      case x => log.error(s"Received unexpected message: $x")
    }
  }

  object LocationTracker {
    /**
     * Used to create the LocationTracker actor
     *
     * @param replyTo optional actorRef to reply to (default: parent of this actor)
     */
    def props(replyTo: Option[ActorRef] = None): Props = Props(classOf[LocationTracker], replyTo)
  }

  /**
   * An actor that notifies the replyTo actor when all the requested services are available.
   * If all services are available, a ServicesReady message is sent. If any of the requested
   * services stops being available, a Disconnected messages is sent.
   *
   * @param replyTo optional actorRef to reply to (default: parent of this actor)
   */
  case class LocationTracker(replyTo: Option[ActorRef]) extends Actor with ActorLogging with ServiceListener {

    // Set of resolved services (Needs to be a var, since the ServiceListener callbacks prevent using akka state)
    // Private loc is for testing
    private[loc] var connections = Map.empty[Connection, Location]

    // The future for this promise completes the next time the serviceResolved() method is called
    private var updateInfo = Promise[Unit]()

    registry.addServiceListener(dnsType, this)

    override def postStop: Unit = {
      registry.removeServiceListener(dnsType, this)
    }

    override def serviceAdded(event: ServiceEvent): Unit = {
      Connection(event.getName).map { connection =>
        if (!connections.contains(connection)) {
          val unc = UnTrackedLocation(connection)
          connections += connection -> unc
          // Should we send an update here?
        }
      }
    }

    override def serviceRemoved(event: ServiceEvent): Unit = {
      log.debug(s"Service Removed Listener: ${event.getName}")
      Connection(event.getInfo.getName).map(removeService)
    }

    // Removes the given service
    // If it isn't in our map, we don't care since it's not being tracked
    // If it is Unresolved, it's still unresolved
    // If it is resolved, we update to unresolved and send a message to the client
    private def removeService(connection: Connection): Unit = {
      def rm(loc: Location): Unit = {
        if (loc.isResolved) {
          val unc = Unresolved(loc.connection)
          connections += (loc.connection -> unc)
          sendLocationUpdate(unc)
        }
      }

      connections.get(connection).foreach(rm)
    }

    // Check to see if a connection is already resolved, and if so, resolve the service
    private def tryToResolve(connection: Connection): Unit = {
      connections.get(connection) match {
        case Some(Unresolved(_)) =>
          val s = Option(registry.getServiceInfo(dnsType, connection.toString))
          s.foreach(resolveService(connection, _))
        case x =>
          log.warning(s"Attempt to track and already tracked connection: $x")
      }
    }

    override def serviceResolved(event: ServiceEvent): Unit = {
      // Complete the promise so that the related future completes, in case the WaitToTrack() method is waiting for it
      Connection(event.getName).map { connection =>
        if (!connections.contains(connection)) {
          log.debug(s"serviceResolved: Resolved service not known yet (adding): $connection")
          //          val unc = UnTrackedLocation(connection)
          //          connections += connection -> unc
          resolveService(connection, event.getInfo)
        }
      }

      updateInfo.success(())
      updateInfo = Promise[Unit]()
    }

    private def resolveService(connection: Connection, info: ServiceInfo): Unit = {
      try {
        // Gets the URI, adding the akka system as user if needed
        def getUri(uriStr: String): Option[URI] = {
          connection match {
            case _: AkkaConnection =>
              val path = info.getPropertyString(PATH_KEY)
              if (path == null) None else getAkkaUri(uriStr, info.getPropertyString(SYSTEM_KEY))
            case _ =>
              Some(new URI(uriStr))
          }
        }

        info.getURLs(connection.connectionType.name).toList.flatMap(getUri).foreach {
          uri =>
            connection match {
              case ac: AkkaConnection =>
                val prefix = info.getPropertyString(PREFIX_KEY)
                // An Akka connection is finished after identify returns
                val rac = ResolvedAkkaLocation(ac, uri, prefix)
                identify(rac)
              case hc: HttpConnection =>
                // An Http connection is finished off here
                val path = info.getPropertyString(PATH_KEY)
                val rhc = ResolvedHttpLocation(hc, uri, path)
                connections += (connection -> rhc)
                log.debug(s"Resolved HTTP: ${connections.values.toList}")
                // Here is where the resolved message is sent for an Http Connection
                sendLocationUpdate(rhc)
              case tcp: TcpConnection =>
                // A TCP-based connection is ended here
                val rtc = ResolvedTcpLocation(tcp, uri.getHost, uri.getPort)
                connections += (connection -> rtc)
                sendLocationUpdate(rtc)
            }
        }
      } catch {
        case e: Exception => log.error(e, "resolveService: resolve error")
      }
    }

    private def getAkkaUri(uriStr: String, userInfo: String): Option[URI] = try {
      val uri = new URI(uriStr)
      //      Some(new URI("akka", userInfo, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment)) // aeron uses akka:
      Some(new URI("akka.tcp", userInfo, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment)) // netty uses akka.tcp
    } catch {
      case e: Exception =>
        // some issue with ipv6 addresses?
        log.error(s"Couldn't make URI from $uriStr and userInfo $userInfo", e)
        None
    }

    // Sends an Identify message to the URI for the actor, which should result in an
    // ActorIdentity reply containing the actorRef.
    private def identify(rs: ResolvedAkkaLocation): Unit = {
      log.debug(s"Attempting to identify actor ${rs.uri.toString}")
      val actorPath = ActorPath.fromString(rs.uri.toString)
      context.actorSelection(actorPath) ! Identify(rs)
    }

    // Called when an actor is identified.
    // Update the resolved map and check if we have everything that was requested.
    private def actorIdentified(actorRefOpt: Option[ActorRef], rs: ResolvedAkkaLocation): Unit = {
      if (actorRefOpt.isDefined) {
        log.debug(s"Resolved: Identified actor $actorRefOpt")
        // Update the table
        val newrc = rs.copy(actorRef = actorRefOpt)
        connections += (rs.connection -> newrc)
        // Watch the actor for death
        context.watch(actorRefOpt.get)
        // Here is where the resolved message is sent for an Akka Connection
        sendLocationUpdate(newrc)
      } else {
        log.warning(s"Could not identify actor for ${rs.connection} ${rs.uri}")
      }
    }

    private def sendLocationUpdate(location: Location): Unit = {
      replyTo.getOrElse(context.parent) ! location
    }

    def waitToTrack(connection: Connection): Unit = {
      import context.dispatcher
      updateInfo.future.onComplete { _ =>
        self ! TrackConnection(connection: Connection)
      }
    }

    // Receive messages
    override def receive: Receive = {

      // Result of sending an Identify message to the actor's URI (actorSelection)
      case ActorIdentity(id, actorRefOpt) =>
        id match {
          case rs: ResolvedAkkaLocation => actorIdentified(actorRefOpt, rs)
          case _                        => log.warning(s"Received unexpected ActorIdentity id: $id")
        }

      case TrackConnection(connection: Connection) =>
        // This is called from outside, so if it isn't in the tracking list, add it
        log.debug("----------------Received track connection: " + connection)
        if (!connections.contains(connection)) {
          waitToTrack(connection)
        } else {
          // In this case, there is some entry already in our table, meaning at least serviceAdded has been called
          // There is a chance that it has already been resolved since this is shared across the JVM?
          connections(connection) match {
            case UnTrackedLocation(_) =>
              val unc = Unresolved(connection)
              connections += (connection -> unc)
              tryToResolve(connection)
            case u: Unresolved =>
              log.error("Should not have an Unresolved connection when initiating tracking: " + u)
            case r @ _ =>
              sendLocationUpdate(r)
          }
        }

      case UntrackConnection(connection: Connection) =>
        // This is called from outside, so if it isn't in the tracking list, ignore it
        if (connections.contains(connection)) {
          // Remove from the map and send an updated Resolved List
          connections += (connection -> UnTrackedLocation(connection))
          // Send Untrack back so state can be updated
          replyTo.getOrElse(context.parent) ! UnTrackedLocation(connection)
        }

      case Terminated(actorRef) =>
        // If a requested Akka service terminates, remove it, just in case it didn't unregister with mDns...
        connections.values.foreach {
          case ResolvedAkkaLocation(c, _, _, Some(otherActorRef)) =>
            log.debug(s"Unresolving terminated actor: $c")
            if (actorRef == otherActorRef) removeService(c)
          case x => // should not happen
            log.warning(s"Received Terminated message for unknown actor: $actorRef")
        }

      case x =>
        log.error(s"Received unexpected message $x")
    }

  }

}
