package csw.examples.vsliceJava.assembly;

import akka.actor.*;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus2;
import csw.services.ccs.CommandStatus2.Invalid;
import csw.services.ccs.CommandStatus2.NoLongerValid;
import csw.services.ccs.CommandStatus2.Error;
import csw.services.ccs.SequentialExecution.SequentialExecutor.*;
import csw.services.ccs.StateMatchers.MultiStateMatcherActor.StartMatch;
import csw.services.ccs.StateMatchers.*;
import csw.services.ccs.Validation.*;
import csw.services.events.EventServiceSettings;
import csw.services.loc.LocationService;
import csw.util.config.BooleanItem;
import csw.util.config.Configurations;
import csw.util.config.Configurations.*;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.DoubleItem;
import csw.util.config.StateVariable.DemandState;
import csw.util.config.UnitsOfMeasure.encoder;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import csw.examples.vsliceJava.hcd.TromboneHCD;
import csw.services.alarms.*;
import csw.util.config.JavaHelpers;
import javacsw.services.pkg.ILocationSubscriberClient;
import javacsw.util.config.JConfigDSL;
import csw.util.config.StateVariable.CurrentState;
import csw.examples.vsliceJava.assembly.TromboneStateActor.TromboneStateClient;
import csw.services.loc.LocationService.*;

import javacsw.services.alarms.IAlarmService;
import javacsw.services.alarms.JAlarmService;
import javacsw.services.ccs.JCommandStatus2;
import javacsw.services.ccs.JHcdController;
import javacsw.util.config.JUnitsOfMeasure;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import java.util.concurrent.TimeUnit;
import scala.concurrent.duration.*;

import static csw.examples.vsliceJava.assembly.TromboneStateActor.*;
import static javacsw.util.config.JItems.*;
import static javacsw.util.config.JConfigDSL.*;

import java.util.Optional;
import java.util.function.Consumer;

import javacsw.services.ccs.JSequentialExecution;

import static akka.pattern.PatternsCS.*;

import static csw.examples.vsliceJava.hcd.TromboneHCD.*;
import static javacsw.services.ccs.JCommandStatus2.Cancelled;
import static javacsw.services.ccs.JCommandStatus2.Completed;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;
import static scala.compat.java8.OptionConverters.toJava;

/**
 * TMT Source Code: 9/21/16.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
class TromboneCommandHandler extends TromboneStateClient implements ILocationSubscriberClient {
  private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  private final AssemblyContext ac;
  private final Optional<ActorRef> allEventPublisher;

  private int moveCnt;
  // Diags
  private final ActorRef tromboneHCD;

  // The actor for managing the persistent assembly state as defined in the spec is here, it is passed to each command
  private final ActorRef tromboneStateActor;


  public TromboneCommandHandler(AssemblyContext ac, Optional<ActorRef> tromboneHCDIn, Optional<ActorRef> allEventPublisher) {
    this.ac = ac;
    this.tromboneHCD = tromboneHCDIn.orElse(context().system().deadLetters());
    tromboneStateActor = context().actorOf(TromboneStateActor.props());
    this.allEventPublisher = allEventPublisher;
    moveCnt = 0;
    subscribeToLocationUpdates();

//    receive(ReceiveBuilder.
//      matchAny(t -> log.warning("Unknown message received: " + t)).
//      build());

    context().become(noFollowReceive());
  }


  private PartialFunction<Object, BoxedUnit> noFollowReceive() {
    return ReceiveBuilder.
      match(ExecuteOne.class, t -> {
        SetupConfig sc = t.sc();
        Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
        ConfigKey configKey = sc.configKey();
        if (configKey.equals(ac.initCK)) {
          log.info("Init not yet implemented");
        } else if (configKey.equals(ac.datumCK)) {
          ActorRef datumActorRef = context().actorOf(DatumCommand.props(sc, tromboneHCD, currentState(), Optional.of(tromboneStateActor)));
          context().become(actorExecutingReceive(datumActorRef, commandOriginator));
          self().tell(JSequentialExecution.CommandStart(), self());
        } else if (configKey.equals(ac.moveCK)) {
          ActorRef moveActorRef = context().actorOf(MoveCommand.props(ac, sc, tromboneHCD, currentState(), Optional.of(tromboneStateActor)));
          context().become(actorExecutingReceive(moveActorRef, commandOriginator));
          self().tell(JSequentialExecution.CommandStart(), self());
        } else if (configKey.equals(ac.positionCK)) {
//          val positionActorRef = context.actorOf(PositionCommand.props(ac, sc, tromboneHCD, currentState, Some(tromboneStateActor)))
//          context.become(actorExecutingReceive(positionActorRef, commandOriginator))
//          self ! CommandStart
          ActorRef positionActorRef = PositionCommand(sc, tromboneHCD);
          context().become(actorExecutingReceive(positionActorRef, commandOriginator));
          self().tell(JSequentialExecution.CommandStart(), self());
        } else if (configKey.equals(ac.stopCK)) {
          commandOriginator.ifPresent(actorRef ->
            actorRef.tell(new Invalid(new WrongInternalStateIssue("Trombone assembly must be executing a command to use stop")), self()));
        } else if (configKey.equals(ac.setAngleCK)) {
          commandOriginator.ifPresent(actorRef ->
            actorRef.tell(new Invalid(new WrongInternalStateIssue("Trombone assembly must be following for setAngle")), self()));
        } else if (configKey.equals(ac.setElevationCK)) {
//          // Note that units have already been verified here
//          val setElevationActorRef = context.actorOf(SetElevationCommand.props(ac, sc, tromboneHCD, currentState, Some(tromboneStateActor)))
//          context.become(actorExecutingReceive(setElevationActorRef, commandOriginator))
//          self ! CommandStart
          commandOriginator.ifPresent(actorRef ->
            actorRef.tell(new Invalid(new WrongInternalStateIssue("Trombone assembly must be following for setElevation")), self()));
        } else if (configKey.equals(ac.followCK)) {
//          if (cmd(currentState) == cmdUninitialized || (move(currentState) != moveIndexed && move(currentState) != moveMoving) || !sodiumLayer(currentState)) {
//            commandOriginator.foreach(_ ! NoLongerValid(WrongInternalStateIssue(s"Assembly state of ${cmd(currentState)}/${move(currentState)} does not allow follow")))
//          } else {
//            // No state set during follow
//            // At this point, parameters have been checked so direct access is okay
//            val nssItem = sc(ac.nssInUseKey)
//
//            // The event publisher may be passed in (XXX FIXME? pass in eventService)
//            val props = FollowCommand.props(ac, nssItem, Some(tromboneHCD), allEventPublisher, eventService = None)
//            // Follow command runs the trombone when following
//            val followCommandActor = context.actorOf(props)
//            context.become(followReceive(followCommandActor))
//            // Note that this is where sodiumLayer is set allowing other commands that require this state
//            tromboneStateActor ! SetState(cmdContinuous, moveMoving, sodiumLayer(currentState), nssItem.head)
//            commandOriginator.foreach(_ ! Completed)
//          }
          if (cmd(currentState()).equals(cmdUninitialized)
            || (!move(currentState()).equals(moveIndexed) && !move(currentState()).equals(moveMoving))
            || !sodiumLayer(currentState())) {
            commandOriginator.ifPresent(actorRef ->
              actorRef.tell(new NoLongerValid(new WrongInternalStateIssue("Assembly state of $cmd/$move does not allow follow")), self()));
          } else {
            // No state set during follow
            // At this point, parameters have been checked so direct access is okay
            BooleanItem nssItem = jitem(sc, ac.nssInUseKey);

              // The event publisher may be passed in
            Props props = FollowCommand.props(ac, nssItem, Optional.of(tromboneHCD), allEventPublisher, Optional.empty());
            // Follow command runs the trombone when following
            ActorRef followCommandActor = context().actorOf(props);
            context().become(followReceive(followCommandActor));
            // Note that this is where sodiumLayer is set allowing other commands that require this state
//            state(cmdContinuous, moveMoving, sodiumLayer(), jvalue(nssItem));
            tromboneStateActor.tell(new SetState(cmdContinuous, moveMoving, sodiumLayer(currentState()), nssItem.head()), self());
            commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
          }
        } else {
          log.error("TromboneCommandHandler2:noFollowReceive received an unknown command: " + t);
          commandOriginator.ifPresent(actorRef ->
            actorRef.tell(new Invalid(new UnsupportedCommandInStateIssue("Trombone assembly does not support the command "+
            configKey.prefix() + " in the current state.")), self()));
        }
      }).
      match(TromboneStateActor.TromboneState.class, this::stateReceive).
      matchAny(t -> log.warning("TromboneCommandHandler2:noFollowReceive received an unknown message: " + t)).
      build();
  }

  private void lookatLocations(Location location) {
    if (location instanceof LocationService.ResolvedAkkaLocation) {
      log.info("Got actorRef: " + l.actorRef);
      tromboneHCD = l.actorRef.getOrElse(context().system().deadLetters());
    }
      case h: ResolvedHttpLocation =>
        log.info(s"HTTP: ${h.connection}")
      case t: ResolvedTcpLocation =>
        log.info(s"Received TCP Location: ${t.connection}")
      case u: Unresolved =>
        log.info(s"Unresolved: ${u.connection}")
      case ut: UnTrackedLocation =>
        log.info(s"UnTracked: ${ut.connection}")
    }
  }


  private PartialFunction<Object, BoxedUnit> followReceive(ActorRef followActor) {
    return ReceiveBuilder.
      match(ExecuteOne.class, t -> {
          SetupConfig sc = t.sc();
          Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
          ConfigKey configKey = sc.configKey();
          if (configKey.equals(ac.datumCK) || configKey.equals(ac.moveCK) || configKey.equals(ac.positionCK) || configKey.equals(ac.followCK)) {
            commandOriginator.ifPresent(actorRef ->
              actorRef.tell(new Invalid(new WrongInternalStateIssue("Trombone assembly cannot be following for datum, move, position, and follow")), self()));
          } else if (configKey.equals(ac.setElevationCK)) {
            // Unclear what to really do with state here
            // Everything else is the same
            state(cmdBusy, move(), sodiumLayer(), nss());

            // At this point, parameters have been checked so direct access is okay
            // Send the SetElevation to the follow actor
            DoubleItem elevationItem = jitem(sc, ac.naElevationKey);
            followActor.tell(new FollowActor.SetElevation(elevationItem), self());

              Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
              executeMatch(context(), idleMatcher(), tromboneHCD, commandOriginator, timeout, cmdStatus -> {
              if (cmdStatus == Completed)
                state(cmdContinuous, move(), sodiumLayer(), nss());
              else if (cmdStatus instanceof Error)
                log.error("setElevation command failed with message: " + ((Error)cmdStatus).message());
            });
          } else if (configKey.equals(ac.setAngleCK)) {
            // Unclear what to really do with state here
            // Everything else is the same
            state(cmdBusy, move(), sodiumLayer(), nss());

            // At this point, parameters have been checked so direct access is okay
            // Send the SetAngle to the follow actor
            DoubleItem zenithAngleItem = jitem(sc, ac.zenithAngleKey);
            followActor.tell(new FollowActor.SetZenithAngle(zenithAngleItem), self());
            executeMatch(context, idleMatcher, tromboneHCD, commandOriginator) {
              case Completed =>
                state(cmdContinuous, move(), sodiumLayer(), nss());
              case Error(message) =>
                log.error("setElevation command failed with message: " + message);
            }
          } else if (configKey.equals(ac.stopCK)) {
            // Stop the follower
            log.info("Just received the stop");
            followActor.tell(StopFollowing, self());
            state(cmdReady, move(), sodiumLayer(), nss());
            // Go back to no follow state
            context().become(noFollowReceive());
            commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
        }

      }).
      matchAny(t -> log.warning("TromboneCommandHandler2:noFollowReceive received an unknown message: " + t)).
      build();
  }

  private PartialFunction<Object, BoxedUnit> actorExecutingReceive(ActorRef currentCommand, Optional<ActorRef> commandOriginator) {
    Timeout timeout = new Timeout(5, TimeUnit.SECONDS);

    return ReceiveBuilder.
      matchEquals(JSequentialExecution.CommandStart(), t -> {

        // Execute the command actor asynchronously, pass the command status back, kill the actor and go back to waiting
        ask(currentCommand, JSequentialExecution.CommandStart(), timeout.duration().toMillis()).
          thenApply(reply -> {
            CommandStatus2.CommandStatus2 cs = (CommandStatus2.CommandStatus2) reply;
            commandOriginator.ifPresent(actorRef -> actorRef.tell(cs, self()));
            currentCommand.tell(PoisonPill.getInstance(), self());
            context().become(noFollowReceive());
            return null;
          });
      }).
      matchEquals(JSequentialExecution.StopCurrentCommand(), t -> {
        // This sends the Stop sc to the HCD
        log.debug("actorExecutingReceive STOP STOP");
        closeDownMotionCommand(currentCommand, commandOriginator);
      }).
      match(SetupConfig.class, t -> {
        // This sends the Stop sc to the HCD
        log.debug("actorExecutingReceive: Stop CK");
        closeDownMotionCommand(currentCommand, commandOriginator);
      }).
      matchAny(t -> log.warning("TromboneCommandHandler2:actorExecutingReceive received an unknown message: " + t)).
      build();
  }

  private void closeDownMotionCommand(ActorRef currentCommand, Optional<ActorRef> commandOriginator) {
    currentCommand.tell(JSequentialExecution.StopCurrentCommand(), self());
    currentCommand.tell(PoisonPill.getInstance(), self());
    context().become(noFollowReceive());
    commandOriginator.ifPresent(actorRef -> actorRef.tell(Cancelled, self()));
  }


  // XXX use a props() method?
  ActorRef DatumCommand(SetupConfig sc, ActorRef tromboneHCD) {
    // XXX FIXME allan: This will fail since it is an inner class!
    return context().actorOf(Props.create(DatumCommandActor.class, sc, tromboneHCD, tromboneState));
  }

  // XXX TODO FIXME nonstatic static inner class actor
  class MoveCommandActor extends AbstractActor {
    private final AssemblyContext.TromboneControlConfig controlConfig;
    private final SetupConfig sc;
    private final ActorRef tromboneHCD;

    private MoveCommandActor(AssemblyContext.TromboneControlConfig controlConfig, SetupConfig sc, ActorRef tromboneHCD) {
      this.controlConfig = controlConfig;
      this.sc = sc;
      this.tromboneHCD = tromboneHCD;

      receive(ReceiveBuilder.
        matchEquals(JSequentialExecution.CommandStart(), t -> {
          // Move moves the trombone state in mm but not encoder units
          if (cmd().equals(cmdUninitialized) || (!move().equals(moveIndexed) && !move().equals(moveMoving))) {
            sender().tell(new NoLongerValid(new WrongInternalStateIssue("Assembly state of $cmd/$move does not allow move")), self());
          } else {
            ActorRef mySender = sender();
            DoubleItem stagePosition = jitem(sc, ac.stagePositionKey);

            // Convert to encoder units from mm
            int encoderPosition = Algorithms.stagePositionToEncoder(controlConfig, jvalue(stagePosition));

            log.info("Setting trombone axis to: " + encoderPosition);

            DemandMatcher stateMatcher = posMatcher(encoderPosition);
            // Position key is encoder units
            SetupConfig scOut =  jadd(sc(axisMoveCK.prefix()),
              jset(positionKey, encoderPosition).withUnits(JUnitsOfMeasure.encoder));

            state(cmdBusy, moveMoving, sodiumLayer(), nss());
            tromboneHCD.tell(new HcdController.Submit(scOut), self());
            executeMatch(context(), stateMatcher, tromboneHCD, Optional.of(mySender)) {
              case Completed =>
                state(cmdReady, moveIndexed, sodiumLayer(), nss());
                moveCnt += 1;
                log.info("Done with move at: " + moveCnt);
              case Error(message) =>
                log.error("Move command failed with message: " + message);
            }
          }
        }).
        matchEquals(JSequentialExecution.StopCurrentCommand(), t -> {
          log.info("Move command -- STOP");
          tromboneHCD.tell(new HcdController.Submit(cancelSC), self());
        }).
        matchAny(t -> log.warning("Unknown message received: " + t)).
        build());
    }
  }

  ActorRef MoveCommand(SetupConfig sc, ActorRef tromboneHCD) {
    // XXX FIXME allan: This will fail since it is an inner class!
    return context().actorOf(Props.create(MoveCommandActor.class, ac.controlConfig, sc, tromboneHCD));
  }

  private class PositionCommandActor extends AbstractActor {

    private final AssemblyContext.TromboneControlConfig controlConfig;
    private final SetupConfig sc;
    private final ActorRef tromboneHCD;

    private PositionCommandActor(AssemblyContext.TromboneControlConfig controlConfig, SetupConfig sc, ActorRef tromboneHCD) {
      this.controlConfig = controlConfig;
      this.sc = sc;
      this.tromboneHCD = tromboneHCD;

      receive(ReceiveBuilder.
        matchEquals(JSequentialExecution.CommandStart(), t -> {
          if (cmd().equals(cmdUninitialized) || (!move().equals(moveIndexed) && !move().equals(moveMoving))) {
            sender().tell(new NoLongerValid(new WrongInternalStateIssue("Assembly state of "
              + cmd() + "/" + move() + " does not allow motion"));
          } else {
            ActorRef mySender = sender();

            // Note that units have already been verified here
            DoubleItem rangeDistance = jitem(sc, ac.naRangeDistanceKey);

            // Convert elevation to
            // Convert to encoder units from mm
            double stagePosition = Algorithms.rangeDistanceToStagePosition(jvalue(rangeDistance));
            int encoderPosition = Algorithms.stagePositionToEncoder(controlConfig, stagePosition);

            log.info("Using rangeDistance: " + jvalue(rangeDistance) + " to get stagePosition: "
              + stagePosition + " to encoder: " + encoderPosition);

            DemandMatcher stateMatcher = posMatcher(encoderPosition);
            // Position key is encoder units
            SetupConfig scOut = jadd(sc(axisMoveCK.prefix()), jset(positionKey, encoderPosition).withUnits(JUnitsOfMeasure.encoder));
            state(cmdBusy, moveMoving, sodiumLayer(), nss());
            tromboneHCD.tell(new HcdController.Submit(scOut), self());

            executeMatch(context(), stateMatcher, tromboneHCD, Optional.of(mySender)) {
              case Completed =>
                state(cmdReady, moveIndexed, sodiumLayer(), nss());
                moveCnt += 1;
                log.info("Done with position at: " + moveCnt);
              case Error(message) =>
                log.error("Position command failed with message: " + message);
            }
          }
        }).
        matchEquals(JSequentialExecution.StopCurrentCommand(), t -> {
          log.info("Move command -- STOP");
          tromboneHCD.tell(new HcdController.Submit(cancelSC), self());
        }).
        matchAny(t -> log.warning("Unknown message received: " + t)).
        build());
    }

    @Override
    public void postStop() {
      unsubscribeState();
    }
  }

  private ActorRef PositionCommand(SetupConfig sc, ActorRef tromboneHCD) {
    // XXX FIXME: Won't work in non-static class
    return context().actorOf(Props.create(PositionCommandActor.class, ac.controlConfig, sc, tromboneHCD));
  }

  private void executeMatch(ActorContext context, StateMatcher stateMatcher, ActorRef currentStateSource, Optional<ActorRef> replyTo,
                            Timeout timeout, Consumer<CommandStatus2.CommandStatus2> codeBlock) {
//    implicit val t = Timeout(timeout.duration + 1.seconds)

    ActorRef matcher = context.actorOf(MultiStateMatcherActor.props(currentStateSource, timeout));

    ask(matcher, new StartMatch(stateMatcher)).
      thenApply(reply -> {
        CommandStatus2 cmdStatus = (CommandStatus2)reply;
        codeBlock.accept(cmdStatus);
        replyTo.ifPresent(actorRef -> actorRef.tell(cmdStatus, context.self()));
        return null;
      });
  }

  private DemandMatcher idleMatcher() {
    DemandState ds = jadd(new DemandState(axisStateCK.prefix()), jset(stateKey, TromboneHCD.AXIS_IDLE));
    return new DemandMatcher(ds, false);
  }

  private DemandMatcher posMatcher(int position) {
    DemandState ds = jadd(new DemandState(axisStateCK.prefix()),
      jset(stateKey, TromboneHCD.AXIS_IDLE),
      jset(positionKey, position));
    return new DemandMatcher(ds, false);
  }

  // --- static defs ---

  public static Props props(AssemblyContext ac, ActorRef tromboneHCDIn, Optional<ActorRef> allEventPublisher) {
    return Props.create(new Creator<TromboneCommandHandler>() {
      private static final long serialVersionUID = 1L;

      @Override
      public TromboneCommandHandler create() throws Exception {
        return new TromboneCommandHandler(ac, tromboneHCDIn, allEventPublisher);
      }
    });
  }

}

