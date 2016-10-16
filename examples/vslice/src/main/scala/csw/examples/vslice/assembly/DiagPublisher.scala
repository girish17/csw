package csw.examples.vslice.assembly

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import csw.examples.vslice.assembly.TromboneAssembly.UpdateTromboneHCD
import csw.examples.vslice.assembly.TrombonePublisher.{AxisStateUpdate, AxisStatsUpdate}
import csw.examples.vslice.hcd.TromboneHCD
import csw.services.loc.LocationService._
import csw.services.loc.TrackerSubscriberClient
import csw.services.ts.TimeService.TimeServiceScheduler
import csw.util.akka.PublisherActor
import csw.util.config.StateVariable.CurrentState

/**
 * DiagPublisher provides diagnostic telemetry in the form of two events. DiagPublisher operaties in the 'OperationsState' or 'DiagnosticState'.
 *
 * DiagPublisher listens in on axis state updates from the HCD and publishes them as a StatusEvent through the assembly's event publisher.
 * In OperationsState, it publishes every 5'th axis state update (set with val operationsSkipCount.
 * In DiagnosticState, it publishes every other axis update (more frequent in diagnostic state).
 *
 * context.become is used to implement a state machine with two states operationsReceive and diagnosticReceive
 *
 * In DiagnosticState, it also publishes an axis statistics event every second. Every one second (diagnosticAxisStatsPeriod), it
 * sends the GetAxisStats message to the HCD. When the data arrives, it is sent to the event publisher.
 *
 * This actor demonstrates a few techniques. First, it has no variables. Each state in the actor is represented by its own
 * receive method. Each method has parameters that can be called from within the function with updated values eliminating the need
 * for variables.
 *
 * This shows how to filter events from the CurrentState stream from the HCD.
 *
 * This shows how to use the TimeService to send periodic messages and how to periodically call another actor and process its
 * response.
 *
 * @param currentStateReceiver a source for CurrentState messages. This can be the actorRef of the HCD itself, or the actorRef of
 *                             a CurrentStateReceiver
 * @param tromboneHCDIn        actorRef of the tromboneHCD as a [[scala.Option]]
 * @param eventPublisher       actorRef of an instance of the TrombonePublisher as [[scala.Option]]
 */
class DiagPublisher(currentStateReceiver: ActorRef, tromboneHCDIn: Option[ActorRef], eventPublisher: Option[ActorRef]) extends Actor with ActorLogging with TimeServiceScheduler with TrackerSubscriberClient {

  import DiagPublisher._
  import TromboneHCD._
  import csw.services.ts.TimeService._

  currentStateReceiver ! PublisherActor.Subscribe
  // It would be nice if this message was in a more general location than HcdController or

  val diagnosticSkipCount = 2
  val operationsSkipCount = 5

  // Following are in units of seconds - could be in a configuration file
  val diagnosticAxisStatsPeriod = 1

  // Start in operations mode - 0 is initial stateMessageCounter value
  def receive: Receive = operationsReceive(currentStateReceiver, 0, tromboneHCDIn: Option[ActorRef])

  /**
   * The receive method in operations state.
   *
   * In operations state every 5th AxisUpdate message from the HCD is published as a status event. It sends an AxisStateUpdate message
   * to the event publisher
   *
   * @param currentStateReceive the source for CurrentState messages
   * @param stateMessageCounter the number of messages received by the diag publisher
   * @param tromboneHCD         the trombone HCD ActorRef as an Option
   *
   * @return Receive partial function
   */
  def operationsReceive(currentStateReceive: ActorRef, stateMessageCounter: Int, tromboneHCD: Option[ActorRef]): Receive = {
    case cs: CurrentState if cs.configKey == TromboneHCD.axisStateCK =>
      if (stateMessageCounter % operationsSkipCount == 0) publishStateUpdate(cs)
      context.become(operationsReceive(currentStateReceive, stateMessageCounter + 1, tromboneHCD))

    case cs: CurrentState if cs.configKey == TromboneHCD.axisStatsCK => // No nothing
    case TimeForAxisStats(_) => // Do nothing, here so it doesn't make an error
    case OperationsState => // Already in operaitons mode

    case DiagnosticState =>
      // If the DiagnosticMode message is received, begin collecting axis stats messages based on a timer and query to HCD
      // The cancelToken allows turning off the timer when
      val cancelToken: Cancellable = scheduleOnce(localTimeNow.plusSeconds(diagnosticAxisStatsPeriod), self, TimeForAxisStats(diagnosticAxisStatsPeriod))
      context.become(diagnosticReceive(currentStateReceive, stateMessageCounter, tromboneHCD, cancelToken))

    case UpdateTromboneHCD(tromboneHCDUpdate) =>
      context.become(operationsReceive(currentStateReceiver, stateMessageCounter, tromboneHCDUpdate))

    case location: Location =>
      location match {
        case l: ResolvedAkkaLocation =>
          log.info(s"operationsReceive updated actorRef: ${l.actorRef}")
          context.become(operationsReceive(currentStateReceive, stateMessageCounter, l.actorRef))
        case h: ResolvedHttpLocation =>
          log.info(s"HTTP: ${h.connection}")
        case t: ResolvedTcpLocation =>
          log.info(s"Service resolved: ${t.connection}")
        case u: Unresolved =>
          log.info(s"Unresolved: ${u.connection}")
          context.become(operationsReceive(currentStateReceive, stateMessageCounter, None))
        case ut: UnTrackedLocation =>
          log.info(s"UnTracked: ${ut.connection}")
      }

    case x => log.error(s"DiagPublisher:operationsReceive received an unexpected message: $x")
  }

  /**
   * The receive method in diagnostic state
   *
   * @param currentStateReceive the source for CurrentState messages
   * @param stateMessageCounter the number of messages received by the diag publisher
   * @param tromboneHCD         the trombone HCD ActorRef as an Option
   * @param cancelToken         a token that allows the current timer to be cancelled
   *
   * @return Receive partial function
   */
  def diagnosticReceive(currentStateReceive: ActorRef, stateMessageCounter: Int, tromboneHCD: Option[ActorRef], cancelToken: Cancellable): Receive = {
    case cs: CurrentState if cs.configKey == TromboneHCD.axisStateCK =>
      if (stateMessageCounter % diagnosticSkipCount == 0) publishStateUpdate(cs)
      context.become(diagnosticReceive(currentStateReceive, stateMessageCounter + 1, tromboneHCD, cancelToken))

    case cs: CurrentState if cs.configKey == TromboneHCD.axisStatsCK =>
      // Here when a CurrentState is received with the axisStats configKey, the axis statistics are published as an event
      publishStatsUpdate(cs)

    case TimeForAxisStats(periodInSeconds) =>
      // Here, every period, an Axis statistics is requested, which is then pubilshed for diagnostics when the response arrives
      // This shows how to periodically query the HCD
      tromboneHCD.foreach(_ ! GetAxisStats)
      val canceltoken: Cancellable = scheduleOnce(localTimeNow.plusSeconds(periodInSeconds), self, TimeForAxisStats(periodInSeconds))
      context.become(diagnosticReceive(currentStateReceive, stateMessageCounter, tromboneHCD, canceltoken))

    case DiagnosticState => // Do nothing, already in this mode

    case OperationsState =>
      // Switch to Operations State
      cancelToken.cancel
      context.become(operationsReceive(currentStateReceive, stateMessageCounter, tromboneHCD))

    case UpdateTromboneHCD(tromboneHCDUpdate) =>
      // The actor ref of the trombone HCD has changed
      context.become(diagnosticReceive(currentStateReceiver, stateMessageCounter, tromboneHCDUpdate, cancelToken))

    case x => log.error(s"DiagPublisher:diagnosticReceive received an unexpected message: $x")
  }

  private def publishStateUpdate(cs: CurrentState): Unit = {
    eventPublisher.foreach(_ ! AxisStateUpdate(cs(axisNameKey), cs(positionKey), cs(stateKey), cs(inLowLimitKey), cs(inHighLimitKey), cs(inHomeKey)))
  }

  private def publishStatsUpdate(cs: CurrentState): Unit = {
    eventPublisher.foreach(_ ! AxisStatsUpdate(cs(axisNameKey), cs(datumCountKey), cs(moveCountKey), cs(homeCountKey), cs(limitCountKey), cs(successCountKey), cs(failureCountKey), cs(cancelCountKey)))
  }

}

object DiagPublisher {

  def props(currentStateReceiver: ActorRef, tromboneHCD: Option[ActorRef], eventPublisher: Option[ActorRef]): Props =
    Props(classOf[DiagPublisher], currentStateReceiver, tromboneHCD, eventPublisher)

  trait DiagPublisherMessages

  final case class TimeForAxisStats(periodInseconds: Int) extends DiagPublisherMessages

  final case object DiagnosticState extends DiagPublisherMessages

  final case object OperationsState extends DiagPublisherMessages

}
