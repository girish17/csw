package javacsw.services.ccs;

import csw.util.config.StateVariable;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * Parent class of assembly controllers implemented in Java.
 * <p>
 * Note: The non-static methods here are only defined as public due to interoperability issues between Scala and Java
 * and should normally be protected (Actors only react to messages).
 *
 * Note: You probably want to use this class instead: {@link javacsw.services.pkg.JAssemblyControllerWithLifecycleHandler}
 *
 */
@SuppressWarnings("unused")
abstract public class JAssemblyController extends AbstractAssemblyController {

//    /**
//     * Indicates a valid config (Invalid is a class that takes a reason argument)
//     */
//    public static final Validation Valid = Valid$.MODULE$;
//
//    /**
//     * Indicates an invalid config
//     *
//     * @param reason a description of why the config is invalid
//     * @return the Invalid object
//     */
//    public static Validation Invalid(String reason) {
//        return new Invalid(reason);
//    }

    /**
     * A request to the implementing actor to publish the current state value
     * by calling notifySubscribers().
     */
    @Override
    public /*protected*/ void requestCurrent() {}

    /**
     * This should be used by the implementer actor's receive method.
     * For example: def receive: Receive = controllerReceive orElse ...
     */
    @Override
    public /*protected*/ PartialFunction<Object, BoxedUnit> controllerReceive() {
        return super.controllerReceive();
    }

    /**
     * Notifies all subscribers with the given value (Need to override to keep java happy)
     */
    @Override
    public /*protected*/ void notifySubscribers(StateVariable.CurrentStates a) {
        super.notifySubscribers(a);
    }

}
