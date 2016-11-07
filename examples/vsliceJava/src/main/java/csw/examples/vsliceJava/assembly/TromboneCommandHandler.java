package csw.examples.vsliceJava.assembly;

import akka.actor.*;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus2.CommandStatus2;
import csw.services.ccs.CommandStatus2.Invalid;
import csw.services.ccs.CommandStatus2.NoLongerValid;
import csw.services.ccs.CommandStatus2.Error;
import csw.services.ccs.SequentialExecution.SequentialExecutor.*;
import csw.services.ccs.StateMatchers.*;
import csw.services.ccs.Validation.*;
import csw.util.config.BooleanItem;
import csw.util.config.Configurations.*;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.DoubleItem;
import csw.util.config.StateVariable.DemandState;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.examples.vsliceJava.hcd.TromboneHCD;
import javacsw.services.pkg.ILocationSubscriberClient;
import csw.examples.vsliceJava.assembly.TromboneStateActor.TromboneStateClient;
import csw.services.loc.LocationService.*;

import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import java.util.concurrent.TimeUnit;

import static csw.examples.vsliceJava.assembly.TromboneStateActor.*;
import static javacsw.util.config.JItems.*;

import java.util.Optional;
import java.util.function.Consumer;

import javacsw.services.ccs.JSequentialExecution;

import static akka.pattern.PatternsCS.*;

import static csw.examples.vsliceJava.hcd.TromboneHCD.*;
import static javacsw.services.ccs.JCommandStatus2.Cancelled;
import static javacsw.services.ccs.JCommandStatus2.Completed;
import static javacsw.util.config.JItems.jset;
import static scala.compat.java8.OptionConverters.toJava;

/**
 * TMT Source Code: 9/21/16.
 */
@SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "unused", "WeakerAccess"})
class TromboneCommandHandler extends AbstractActor implements TromboneStateClient, ILocationSubscriberClient {
  private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  private final AssemblyContext ac;
  private final Optional<ActorRef> allEventPublisher;

  // Diags
  private ActorRef tromboneHCD;
  // Set the default evaluation for use with the follow command
  private DoubleItem setElevationItem;

  // The actor for managing the persistent assembly state as defined in the spec is here, it is passed to each command
  private final ActorRef tromboneStateActor;

  @SuppressWarnings("FieldCanBeLocal")
  private TromboneStateActor.TromboneState internalState = TromboneStateActor.defaultTromboneState;

  @Override
  public void setCurrentState(TromboneStateActor.TromboneState ts) {
    internalState = ts;
  }

  private TromboneStateActor.TromboneState currentState() {
    return internalState;
  }

  public TromboneCommandHandler(AssemblyContext ac, Optional<ActorRef> tromboneHCDIn, Optional<ActorRef> allEventPublisher) {
    this.ac = ac;
    this.tromboneHCD = tromboneHCDIn.orElse(context().system().deadLetters());
    tromboneStateActor = context().actorOf(TromboneStateActor.props());
    this.allEventPublisher = allEventPublisher;
    setElevationItem = ac.naElevation(ac.calculationConfig.defaultInitialElevation);
    int moveCnt = 0;
    subscribeToLocationUpdates();
    log.info("System  is: " + context().system());

//    receive(ReceiveBuilder.
//      matchAny(t -> log.warning("Unknown message received: " + t)).
//      build());

    context().become(noFollowReceive());
  }

  private PartialFunction<Object, BoxedUnit> noFollowReceive() {
    return stateReceive().orElse(ReceiveBuilder.
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
          log.info("Current state: " + currentState());
          ActorRef moveActorRef = context().actorOf(MoveCommand.props(ac, sc, tromboneHCD, currentState(), Optional.of(tromboneStateActor)));
          context().become(actorExecutingReceive(moveActorRef, commandOriginator));
          self().tell(JSequentialExecution.CommandStart(), self());
        } else if (configKey.equals(ac.positionCK)) {
          ActorRef positionActorRef = context().actorOf(PositionCommand.props(ac, sc, tromboneHCD, currentState(), Optional.of(tromboneStateActor)));
          context().become(actorExecutingReceive(positionActorRef, commandOriginator));
          self().tell(JSequentialExecution.CommandStart(), self());
        } else if (configKey.equals(ac.stopCK)) {
          commandOriginator.ifPresent(actorRef ->
            actorRef.tell(new Invalid(new WrongInternalStateIssue("Trombone assembly must be executing a command to use stop")), self()));
        } else if (configKey.equals(ac.setAngleCK)) {
          commandOriginator.ifPresent(actorRef ->
            actorRef.tell(new Invalid(new WrongInternalStateIssue("Trombone assembly must be following for setAngle")), self()));
        } else if (configKey.equals(ac.setElevationCK)) {
          // Setting the elevation state here for a future follow command
          setElevationItem = jitem(sc, ac.naElevationKey);
          log.info("Setting elevation to: " + setElevationItem);
          // Note that units have already been verified here
          ActorRef setElevationActorRef = context().actorOf(SetElevationCommand.props(ac, sc, tromboneHCD, currentState(), Optional.of(tromboneStateActor)));
          context().become(actorExecutingReceive(setElevationActorRef, commandOriginator));
          self().tell(JSequentialExecution.CommandStart(), self());
        } else if (configKey.equals(ac.followCK)) {
          if (cmd(currentState()).equals(cmdUninitialized)
            || (!move(currentState()).equals(moveIndexed) && !move(currentState()).equals(moveMoving))
            || !sodiumLayer(currentState())) {
//            commandOriginator.foreach(_ ! NoLongerValid(WrongInternalStateIssue(s"Assembly state of ${cmd(currentState)}/${move(currentState)}/${sodiumLayer(currentState)} does not allow follow")))
            commandOriginator.ifPresent(actorRef ->
              actorRef.tell(new NoLongerValid(new WrongInternalStateIssue("Assembly state of "
                + cmd(currentState()) + "/" + move(currentState()) + "/" + sodiumLayer(currentState()) + " does not allow follow")), self()));
          } else {
            // No state set during follow
            // At this point, parameters have been checked so direct access is okay
            BooleanItem nssItem = jitem(sc, ac.nssInUseKey);

            log.info("Set elevation is: " + setElevationItem);

            // The event publisher may be passed in
            Props props = FollowCommand.props(ac, setElevationItem, nssItem, Optional.of(tromboneHCD), allEventPublisher, Optional.empty());
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
            actorRef.tell(new Invalid(new UnsupportedCommandInStateIssue("Trombone assembly does not support the command " +
              configKey.prefix() + " in the current state.")), self()));
        }
      }).
      matchAny(t -> log.warning("TromboneCommandHandler2:noFollowReceive received an unknown message: " + t)).
      build());
  }

  private void lookatLocations(Location location) {
    if (location instanceof ResolvedAkkaLocation) {
      ResolvedAkkaLocation l = (ResolvedAkkaLocation) location;
      log.info("Got actorRef: " + l.actorRef());
      tromboneHCD = l.getActorRef().orElse(context().system().deadLetters());
    } else if (location instanceof ResolvedHttpLocation) {
      ResolvedHttpLocation h = (ResolvedHttpLocation) location;
      log.info("Received HTTP Location: " + h.connection());
    } else if (location instanceof ResolvedTcpLocation) {
      ResolvedTcpLocation t = (ResolvedTcpLocation) location;
      log.info("Received TCP Location: " + t.connection());
    } else if (location instanceof Unresolved) {
      Unresolved u = (Unresolved) location;
      log.info("Unresolved: " + u.connection());
    } else if (location instanceof UnTrackedLocation) {
      UnTrackedLocation ut = (UnTrackedLocation) location;
      log.info("Untracked: " + ut.connection());
    }
  }

  private PartialFunction<Object, BoxedUnit> followReceive(ActorRef followActor) {
    return stateReceive().orElse(ReceiveBuilder.
      match(ExecuteOne.class, t -> {
        SetupConfig sc = t.sc();
        Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
        ConfigKey configKey = sc.configKey();
        if (configKey.equals(ac.datumCK) || configKey.equals(ac.moveCK) || configKey.equals(ac.positionCK) || configKey.equals(ac.followCK) || configKey.equals(ac.setElevationCK)) {
          commandOriginator.ifPresent(actorRef ->
            actorRef.tell(new Invalid(new WrongInternalStateIssue("Trombone assembly cannot be following for datum, move, position, setElevation, and follow")), self()));
        } else if (configKey.equals(ac.setAngleCK)) {
          // Unclear what to really do with state here
          // Everything else is the same
          tromboneStateActor.tell(new SetState(cmdBusy, move(currentState()), sodiumLayer(currentState()), nss(currentState())), self());

          // At this point, parameters have been checked so direct access is okay
          // Send the SetElevation to the follow actor
          DoubleItem zenithAngleItem = jitem(sc, ac.zenithAngleKey);
          followActor.tell(new FollowActor.SetZenithAngle(zenithAngleItem), self());
          Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
          executeMatch(context(), idleMatcher(), tromboneHCD, commandOriginator, timeout, status -> {
            if (status == Completed)
              tromboneStateActor.tell(new SetState(cmdContinuous, move(currentState()), sodiumLayer(currentState()), nss(currentState())), self());
            else if (status instanceof Error)
              log.error("setElevation command failed with message: " + ((Error) status).message());
          });
        } else if (configKey.equals(ac.stopCK)) {
          // Stop the follower
          log.info("Just received the stop");
          followActor.tell(new FollowCommand.StopFollowing(), self());
          tromboneStateActor.tell(new SetState(cmdReady, moveIndexed, sodiumLayer(currentState()), nss(currentState())), self());

          // Go back to no follow state
          context().become(noFollowReceive());
          commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
        }
      }).
      build());
  }

  private PartialFunction<Object, BoxedUnit> actorExecutingReceive(ActorRef currentCommand, Optional<ActorRef> commandOriginator) {
    Timeout timeout = new Timeout(5, TimeUnit.SECONDS);

    return stateReceive().orElse(ReceiveBuilder.
      matchEquals(JSequentialExecution.CommandStart(), t -> {

        // Execute the command actor asynchronously, pass the command status back, kill the actor and go back to waiting
        ask(currentCommand, JSequentialExecution.CommandStart(), timeout.duration().toMillis()).
          thenApply(reply -> {
            CommandStatus2 cs = (CommandStatus2) reply;
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
      build());
  }

  private void closeDownMotionCommand(ActorRef currentCommand, Optional<ActorRef> commandOriginator) {
    currentCommand.tell(JSequentialExecution.StopCurrentCommand(), self());
    currentCommand.tell(PoisonPill.getInstance(), self());
    context().become(noFollowReceive());
    commandOriginator.ifPresent(actorRef -> actorRef.tell(Cancelled, self()));
  }

  // --- static defs ---

  public static Props props(AssemblyContext ac, Optional<ActorRef> tromboneHCDIn, Optional<ActorRef> allEventPublisher) {
    return Props.create(new Creator<TromboneCommandHandler>() {
      private static final long serialVersionUID = 1L;

      @Override
      public TromboneCommandHandler create() throws Exception {
        return new TromboneCommandHandler(ac, tromboneHCDIn, allEventPublisher);
      }
    });
  }

  static void executeMatch(ActorContext context, StateMatcher stateMatcher, ActorRef currentStateSource, Optional<ActorRef> replyTo,
                           Timeout timeout, Consumer<CommandStatus2> codeBlock) {

    ActorRef matcher = context.actorOf(MultiStateMatcherActor.props(currentStateSource, timeout));

    ask(matcher, MultiStateMatcherActor.StartMatch.create(stateMatcher), timeout).
      thenApply(reply -> {
        CommandStatus2 cmdStatus = (CommandStatus2) reply;
        codeBlock.accept(cmdStatus);
        replyTo.ifPresent(actorRef -> actorRef.tell(cmdStatus, context.self()));
        return null;
      });
  }

  static DemandMatcher idleMatcher() {
    DemandState ds = jadd(new DemandState(axisStateCK.prefix()), jset(stateKey, TromboneHCD.AXIS_IDLE));
    return new DemandMatcher(ds, false);
  }

  static DemandMatcher posMatcher(int position) {
    DemandState ds = jadd(new DemandState(axisStateCK.prefix()),
      jset(stateKey, TromboneHCD.AXIS_IDLE),
      jset(positionKey, position));
    return new DemandMatcher(ds, false);
  }

}

