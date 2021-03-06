package javacsw.services.ccs;

import csw.services.ccs.HcdController;
import csw.util.config.Configurations;
import csw.util.config.StateVariable;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * Parent class of HCD controllers implemented in Java
 * <p>
 * Note: The methods in this class are defined as public although they override protected Scala methods.
 * This is due to differences in the way Scala implements "protected". The methods should still only be called
 * from derived classes.
 * <p>
 * Note: You probably want to use this class instead: {@link javacsw.services.ccs.JHcdController}
 */
abstract public class JHcdController extends AbstractHcdController {

  /**
   * Subscribes the sender
   */
  public static final PublisherActorMessage Subscribe = HcdController.Subscribe$.MODULE$;

  /**
   * Unsubscribes the sender
   */
  public static final PublisherActorMessage Unsubscribe = HcdController.Unsubscribe$.MODULE$;

  /**
   * Message requesting the publisher to publish the current values
   */
  public static final PublisherActorMessage RequestCurrent = HcdController.RequestCurrent$.MODULE$;


  /**
   * Derived classes should process the given config and eventually either call
   * notifySubscribers() or send a CurrentState message to itself
   * (possibly from a worker actor) to indicate changes in the current HCD state.
   */
  @Override
  public /*protected*/ abstract void process(Configurations.SetupConfig config);

  /**
   * A request to the implementing actor to publish the current state value
   * by calling notifySubscribers().
   */
  @Override
  public /*protected*/ void requestCurrent() {

  }

  /**
   * This should be used by the implementer actor's receive method.
   * For example: def receive: Receive = controllerReceive orElse ...
   */
  @Override
  public /*protected*/ PartialFunction<Object, BoxedUnit> controllerReceive() {
    return super.controllerReceive();
  }

  @Override
  /**
   * Notifies all subscribers with the given value (Need to override to keep java happy)
   */
  public /*protected*/ void notifySubscribers(StateVariable.CurrentState a) {
    super.notifySubscribers(a);
  }
}
