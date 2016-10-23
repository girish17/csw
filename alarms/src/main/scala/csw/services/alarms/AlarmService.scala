package csw.services.alarms

import akka.actor.{ActorRef, ActorRefFactory, PoisonPill}
import akka.util.Timeout
import com.typesafe.scalalogging.slf4j.Logger
import csw.services.alarms.AlarmModel.{AlarmStatus, CurrentSeverity, Health, HealthStatus, SeverityLevel}
import csw.services.alarms.AlarmState.{AcknowledgedState, ActivationState, LatchedState, ShelvedState}
import csw.services.loc.{ComponentId, ComponentType, LocationService}
import csw.services.loc.Connection.{HttpConnection, TcpConnection}
import csw.services.loc.LocationService.{ResolvedHttpLocation, ResolvedTcpLocation}
import org.slf4j.LoggerFactory
import redis._

import scala.concurrent.Future

/**
 * Static definitions for the Alarm Service
 */
object AlarmService {
  private[alarms] val logger = Logger(LoggerFactory.getLogger(AlarmService.getClass))

  /**
   * The default name that the Alarm Service is registered with
   */
  val defaultName = "Alarm Service"

  /**
   * An alarm's severity should be refreshed every defaultRefreshSecs seconds
   * to make sure it does not expire and become "Disconnected" (after maxMissedRefresh missed refreshes)
   */
  val defaultRefreshSecs = 5

  /**
   * The default number of refreshes that may be missed before an alarm's severity is expired
   * and becomes "Disconnected"
   */
  val maxMissedRefresh = 3

  // Lookup the alarm service redis instance with the location service
  private def locateAlarmService(asName: String = "")(implicit system: ActorRefFactory, timeout: Timeout): Future[RedisClient] = {
    import system.dispatcher
    val connection = TcpConnection(ComponentId(asName, ComponentType.Service))
    LocationService.resolve(Set(connection)).map { locationsReady =>
      val loc = locationsReady.locations.head.asInstanceOf[ResolvedTcpLocation]
      RedisClient(loc.host, loc.port)
    }
  }

  /**
   * Looks up the Redis instance for the Alarm Service with the Location Service
   * and then returns an AlarmService instance using it.
   *
   * Note: Applications using the Location Service should call LocationService.initialize() once before
   * accessing any Akka or Location Service methods.
   *
   * @param asName      name used to register the Redis instance with the Location Service (default: "Alarm Service")
   * @param refreshSecs alarm severity should be reset every refreshSecs seconds to avoid being expired and set
   *                    to "Disconnected" (after three missed refreshes)
   * @return a new AlarmService instance
   */
  def apply(asName: String = defaultName, refreshSecs: Int = defaultRefreshSecs)(implicit system: ActorRefFactory, timeout: Timeout): Future[AlarmService] = {
    import system.dispatcher
    for {
      redisClient <- locateAlarmService(asName)
      ok <- redisClient.configSet("notify-keyspace-events", "KEA")
    } yield {
      if (!ok) logger.error("redis configSet notify-keyspace-events failed")
      AlarmServiceImpl(redisClient, refreshSecs)
    }
  }

  /**
   * Returns an AlarmService instance using the Redis instance at the given host and port,
   * using the default "127.0.0.1:6379 if not given.
   *
   * @param host        the Redis host name or IP address
   * @param port        the Redis port
   * @param refreshSecs alarm severity should be reset every refreshSecs seconds to avoid being expired and set
   *                    to "Disconnected" (after three missed refreshes)
   * @return a new AlarmService instance
   */
  def get(host: String = "127.0.0.1", port: Int = 6379, refreshSecs: Int = defaultRefreshSecs)(implicit system: ActorRefFactory, timeout: Timeout): Future[AlarmService] = {
    import system.dispatcher
    val redisClient = RedisClient(host, port)
    for {
      ok <- redisClient.configSet("notify-keyspace-events", "KEA")
    } yield {
      if (!ok) logger.error("redis configSet notify-keyspace-events failed")
      AlarmServiceImpl(redisClient, refreshSecs)
    }
  }

  /**
   * Type of return value from the monitorAlarms methods
   */
  trait AlarmMonitor {
    /**
     * Stops the monitoring actor
     */
    def stop(): Unit

    /**
     * A reference to the monitoring actor (could be used to watch the actor to detect if it stops for some reason)
     *
     * @return
     */
    def actorRef: ActorRef
  }

  private[alarms] case class AlarmMonitorImpl(actorRef: ActorRef) extends AlarmMonitor {
    override def stop(): Unit = {
      actorRef ! PoisonPill
    }
  }

  // Information needed about each alarm in order to calculate the health
  private[alarms] case class HealthInfo(alarmKey: AlarmKey, currentSeverity: CurrentSeverity, alarmState: AlarmState)

}

/**
 * Defines the public API to the Alarm Service
 */
trait AlarmService {

  import AlarmService._

  /**
   * Alarm severity should be reset every refreshSecs seconds to avoid being expired (after three missed refreshes)
   */
  def refreshSecs: Int

  /**
   * Gets the alarm information from the database for any matching alarms
   *
   * @param alarmKey a key that may match multiple alarms (via wildcards, see AlarmKey.apply())
   * @return a future sequence of alarm model objects
   */
  def getAlarms(alarmKey: AlarmKey): Future[Seq[AlarmModel]]

  /**
   * Gets the alarm information from the database for the matching Alarm
   *
   * @param key the key for the alarm
   * @return a future alarm model object
   */
  def getAlarm(key: AlarmKey): Future[AlarmModel]

  /**
   * Gets the alarm state from the database for the matching Alarm
   *
   * @param key the key for the alarm
   * @return a future alarm state object
   */
  def getAlarmState(key: AlarmKey): Future[AlarmState]

  /**
   * Sets and publishes the severity level for the given alarm
   *
   * @param alarmKey the key for the alarm
   * @param severity the new value of the severity
   * @return a future indicating when the operation has completed
   */
  def setSeverity(alarmKey: AlarmKey, severity: SeverityLevel): Future[Unit]

  /**
   * Gets the severity level for the given alarm
   * (or the latched severity, if the alarm is latched and unacknowledged)
   *
   * @param alarmKey the key for the alarm
   * @return a future severity level result
   */
  def getSeverity(alarmKey: AlarmKey): Future[CurrentSeverity]

  /**
   * Acknowledges the given alarm, if needed.
   *
   * @param alarmKey the key for the alarm
   * @return a future indicating when the operation has completed
   */
  def acknowledgeAlarm(alarmKey: AlarmKey): Future[Unit]

  /**
   * Resets the latched state of the given alarm, if needed.
   *
   * @param alarmKey the key for the alarm
   * @return a future indicating when the operation has completed
   */
  def resetAlarm(alarmKey: AlarmKey): Future[Unit]

  /**
   * Acknowledges the given alarm and resets the latched state, if needed.
   *
   * @param alarmKey the key for the alarm
   * @return a future indicating when the operation has completed
   */
  def acknowledgeAndResetAlarm(alarmKey: AlarmKey): Future[Unit]

  /**
   * Sets the shelved state of the alarm
   *
   * @param alarmKey     the key for the alarm
   * @param shelvedState the shelved state
   * @return a future indicating when the operation has completed
   */
  def setShelvedState(alarmKey: AlarmKey, shelvedState: ShelvedState): Future[Unit]

  /**
   * Sets the activation state of the alarm
   *
   * @param alarmKey        the key for the alarm
   * @param activationState the activation state
   * @return a future indicating when the operation has completed
   */
  def setActivationState(alarmKey: AlarmKey, activationState: ActivationState): Future[Unit]

  /**
   * Gets the health of the system, subsystem or component, based on the given alarm key.
   *
   * @param alarmKey an AlarmKey matching the set of alarms for a component, subsystem or all subsystems, etc. (Note
   *                 that each of the AlarmKey fields may be specified as None, which is then converted to a wildcard "*")
   * @return the future health value (good, ill, bad)
   */
  def getHealth(alarmKey: AlarmKey): Future[Health]

  /**
   * Starts monitoring the health of the system, subsystem or component
   *
   * @param alarmKey     an AlarmKey matching the set of alarms for a component, subsystem or all subsystems, etc. (Note
   *                     that each of the AlarmKey fields may be specified as None, which is then converted to a wildcard "*")
   * @param subscriber   if defined, an actor that will receive a HealthStatus message whenever the health for the given key changes
   * @param notifyAlarm  if defined, a function that will be called with an AlarmStatus object whenever the severity of an alarm changes
   * @param notifyHealth if defined, a function that will be called with a HealthStatus object whenever the total health for key pattern changes
   * @param notifyAll    if true, all severity changes are reported (for example, for logging), otherwise
   *                     only the relevant changes in alarms are reported, for alarms that are not shelved and not out of service,
   *                     and where the latched severity or calculated health actually changed
   * @return an actorRef for the subscriber actor (kill the actor to stop monitoring)
   */
  def monitorAlarms(
    alarmKey:     AlarmKey,
    subscriber:   Option[ActorRef]             = None,
    notifyAlarm:  Option[AlarmStatus => Unit]  = None,
    notifyHealth: Option[HealthStatus => Unit] = None,
    notifyAll:    Boolean                      = false
  ): AlarmMonitor
}

/**
 * Provides methods for working with the Alarm Service database.
 *
 * @param redisClient used to access the Redis instance used by the Alarm Service
 * @param refreshSecs alarm severity should be reset every refreshSecs seconds to avoid being expired (after three missed refreshes)
 */
private[alarms] case class AlarmServiceImpl(redisClient: RedisClient, refreshSecs: Int)(implicit system: ActorRefFactory, timeout: Timeout)
    extends AlarmService with ByteStringSerializerLowPriority {

  import AlarmService._
  import system.dispatcher

  override def getAlarms(alarmKey: AlarmKey): Future[Seq[AlarmModel]] = {
    val pattern = alarmKey.key
    redisClient.keys(pattern).flatMap { keys =>
      Future.sequence(keys.map(getAlarm)).map(_.flatten)
    }
  }

  override def getAlarm(key: AlarmKey): Future[AlarmModel] = {
    getAlarm(key.key).map { opt =>
      if (opt.isEmpty) {
        throw new RuntimeException(s"No alarm was found for key $key")
      } else opt.get
    }
  }

  // Gets the abbreviated alarm model for the given key
  private def getAlarmSmall(key: AlarmKey): Future[AlarmModelSmall] = {
    redisClient.hmget(key.key, "severityLevels", "acknowledge", "latched").map(AlarmModelSmall(_)).map { opt =>
      if (opt.isEmpty) {
        throw new RuntimeException(s"No alarm was found for key $key")
      } else opt.get
    }
  }

  /**
   * Gets the alarm object matching the given key from Redis
   *
   * @param key the key for the alarm in redis
   * @return the alarm model object, if found
   */
  private[alarms] def getAlarm(key: String): Future[Option[AlarmModel]] = {
    redisClient.hgetall(key).map(AlarmModel(_))
  }

  override def getAlarmState(key: AlarmKey): Future[AlarmState] = {
    redisClient.hgetall(key.stateKey).map { map =>
      if (map.isEmpty) throw new RuntimeException(s"Alarm state for $key not found.")
      AlarmState(map)
    }
  }

  override def setSeverity(alarmKey: AlarmKey, severity: SeverityLevel): Future[Unit] = {
    for {
      alarm <- getAlarmSmall(alarmKey)
      alarmState <- getAlarmState(alarmKey)
      result <- setSeverity(alarmKey, alarm, alarmState, severity)
    } yield result
  }

  // Sets the severity of the alarm, if allowed based on the alarm state
  private def setSeverity(alarmKey: AlarmKey, alarm: AlarmModelSmall, alarmState: AlarmState, severity: SeverityLevel): Future[Unit] = {

    // Check that the severity is listed in the spec (XXX Should this throw an exception?)
    if (severity != SeverityLevel.Disconnected && !alarm.severityLevels.contains(severity))
      logger.warn(s"Alarm $alarmKey is not listed as supporting severity level $severity")

    // If the alarm latched and the latch needs reset, use the latched severity, or the given severity if higher
    val latchedSeverity = if (alarm.latched) {
      if (alarmState.latchedState == LatchedState.Normal) severity
      else {
        if (severity.level > alarmState.latchedSeverity.level) severity
        else alarmState.latchedSeverity
      }
    } else severity

    val s = if (latchedSeverity != severity) s" (latched: $latchedSeverity)" else ""
    logger.debug(s"Setting severity for $alarmKey to $severity$s")

    val redisTransaction = redisClient.transaction()

    // Set the severity key to the component's reported severity, so we have a record of that
    val f1 = redisTransaction.set(alarmKey.severityKey, severity.name, exSeconds = Some(refreshSecs * maxMissedRefresh))

    // Set the latch to NeedsReset, if needed
    val f2 = if (alarm.latched && severity.isAlarm && alarmState.latchedState == LatchedState.Normal) {
      logger.debug(s"Setting latched state for $alarmKey to NeedsReset")
      redisTransaction.hset(alarmKey.stateKey, AlarmState.latchedStateField, LatchedState.NeedsReset.name)
    } else Future.successful(true)

    // Update (increase) the latched severity if needed
    val f3 = if (alarm.latched && severity.isAlarm && latchedSeverity != alarmState.latchedSeverity) {
      logger.debug(s"Setting latched severity for $alarmKey to $latchedSeverity")
      redisTransaction.hset(alarmKey.stateKey, AlarmState.latchedSeverityField, latchedSeverity.name)
    } else Future.successful(true)

    // Update the acknowledged state if needed
    val f4 = if (alarm.acknowledge && severity.isAlarm && alarmState.acknowledgedState == AcknowledgedState.Normal) {
      logger.debug(s"Setting acknowledged state for $alarmKey to NeedsAcknowledge")
      redisTransaction.hset(alarmKey.stateKey, AlarmState.acknowledgedStateField, AcknowledgedState.NeedsAcknowledge.name)
    } else Future.successful(true)

    val f5 = redisTransaction.exec()

    Future.sequence(List(f1, f2, f3, f4, f5)).map(_ => ())
  }

  override def getSeverity(alarmKey: AlarmKey): Future[CurrentSeverity] = {
    for {
      alarm <- getAlarmSmall(alarmKey)
      alarmState <- getAlarmState(alarmKey)
      reportedSeverity <- getReportedSeverity(alarmKey)
    } yield {
      getSeverity(alarmKey, alarm, alarmState, reportedSeverity)
    }
  }

  // Returns the severity level reported by the component, or Disconnected, if the value timed out
  private def getReportedSeverity(alarmKey: AlarmKey): Future[SeverityLevel] = {
    for {
      exists <- redisClient.exists(alarmKey.severityKey)
      sevStrOpt <- if (exists) redisClient.get[String](alarmKey.severityKey) else Future.successful(None)
    } yield {
      sevStrOpt.flatMap(SeverityLevel(_)).getOrElse(SeverityLevel.Disconnected)
    }
  }

  // Returns the reported and calculated severity levels, taking latching into account.
  private def getSeverity(alarmKey: AlarmKey, alarm: AlarmModelSmall, alarmState: AlarmState,
                          reportedSeverity: SeverityLevel): CurrentSeverity = {
    val latchedSeverity = if (alarmState.latchedState == LatchedState.Normal) reportedSeverity else alarmState.latchedSeverity
    CurrentSeverity(reportedSeverity, latchedSeverity)
  }

  override def acknowledgeAlarm(alarmKey: AlarmKey): Future[Unit] = {
    for {
      alarmState <- getAlarmState(alarmKey)
      result <- acknowledgeAlarm(alarmKey, alarmState)
    } yield result
  }

  // acknowledge the given alarm
  private def acknowledgeAlarm(alarmKey: AlarmKey, alarmState: AlarmState): Future[Unit] = {
    val f = if (alarmState.acknowledgedState == AcknowledgedState.NeedsAcknowledge) {
      logger.debug(s"Acknowledging alarm: $alarmKey and resetting to Okay")
      redisClient.hset(alarmKey.stateKey, AlarmState.acknowledgedStateField, AcknowledgedState.Normal.name)
    } else Future.successful(true)
    f.map(_ => ())
  }

  override def resetAlarm(alarmKey: AlarmKey): Future[Unit] = {
    for {
      alarmState <- getAlarmState(alarmKey)
      result <- resetAlarm(alarmKey, alarmState)
    } yield result
  }

  override def acknowledgeAndResetAlarm(alarmKey: AlarmKey): Future[Unit] = {
    Future.sequence(List(acknowledgeAlarm(alarmKey), resetAlarm(alarmKey))).map(_ => ())
  }

  // reset the given alarm and update the alarm state and severity if needed
  private def resetAlarm(alarmKey: AlarmKey, alarmState: AlarmState): Future[Unit] = {
    val f = if (alarmState.latchedState == LatchedState.NeedsReset) {
      logger.debug(s"Resetting latched state for alarm: $alarmKey")
      redisClient.hset(alarmKey.stateKey, AlarmState.latchedStateField, LatchedState.Normal.name)
    } else Future.successful(true)
    f.map(_ => ())
  }

  override def setShelvedState(alarmKey: AlarmKey, shelvedState: ShelvedState): Future[Unit] = {
    for {
      exists <- redisClient.exists(alarmKey.stateKey)
      if exists
      result <- redisClient.hset(alarmKey.stateKey, AlarmState.shelvedStateField, shelvedState.name).map(_ => ())
    } yield {
      if (!exists) throw new RuntimeException(s"Can't set shelved state for unknown key: $alarmKey")
      result
    }
  }

  override def setActivationState(alarmKey: AlarmKey, activationState: ActivationState): Future[Unit] = {

    for {
      exists <- redisClient.exists(alarmKey.stateKey)
      if exists
      result <- redisClient.hset(alarmKey.stateKey, AlarmState.activationStateField, activationState.name).map(_ => ())
    } yield {
      if (!exists) throw new RuntimeException(s"Can't set activation state for unknown key: $alarmKey")
      result
    }
  }

  override def getHealth(alarmKey: AlarmKey): Future[Health] = {
    getHealthInfoMap(alarmKey).map(getHealth)
  }

  // Returns a future map from alarm key to health info for each alarm key matching the given alarm key pattern
  private[alarms] def getHealthInfoMap(alarmKey: AlarmKey): Future[Map[AlarmKey, HealthInfo]] = {
    val pattern = alarmKey.key
    redisClient.keys(pattern).map(_.map(AlarmKey(_))).flatMap { alarmKeys =>
      if (alarmKeys.isEmpty) throw new RuntimeException("Can't get health information: No alarms matched $alarmKey")
      val fs = alarmKeys.map { k =>
        for {
          sev <- getSeverity(k)
          state <- getAlarmState(k)
        } yield {
          k -> HealthInfo(k, sev, state)
        }
      }
      Future.sequence(fs).map(_.toMap)
    }
  }

  // Gets the health value, given a map with the alarm severity and state for all the relevant alarms.
  // Health is good if a component’s alarms all have Normal or Warning severity. If the component has at
  // least one alarm with Major severity, it’s health is Ill. If the component has at least one alarm with
  // Critical severity, it’s health is Bad. Additionally, if the component’s severity is Disconnected or
  // Indeterminate, it’s health is also Bad.
  private[alarms] def getHealth(alarmMap: Map[AlarmKey, HealthInfo]): Health = {
    def active(h: HealthInfo): Boolean = h.alarmState.shelvedState == ShelvedState.Normal && h.alarmState.activationState == ActivationState.Normal
    val severityLevels = alarmMap.values.filter(active).map(_.currentSeverity.latched).toList

    if (severityLevels.contains(SeverityLevel.Critical) || severityLevels.contains(SeverityLevel.Disconnected) || severityLevels.contains(SeverityLevel.Indeterminate))
      Health.Bad
    else if (severityLevels.contains(SeverityLevel.Major))
      Health.Ill
    else Health.Good
  }

  override def monitorAlarms(
    alarmKey:      AlarmKey,
    subscriberOpt: Option[ActorRef]             = None,
    notifyAlarm:   Option[AlarmStatus => Unit]  = None,
    notifyHealth:  Option[HealthStatus => Unit] = None,
    notifyAll:     Boolean                      = false
  ): AlarmMonitor = {
    val actorRef = system.actorOf(HealthMonitorActor.props(this, alarmKey, subscriberOpt, notifyAlarm, notifyHealth, notifyAll)
      .withDispatcher("rediscala.rediscala-client-worker-dispatcher"))
    AlarmMonitorImpl(actorRef)
  }
}
