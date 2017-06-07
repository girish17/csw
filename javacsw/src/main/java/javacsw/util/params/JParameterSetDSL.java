package javacsw.util.params;

import csw.util.param.Parameters.*;
import csw.util.param.Parameter;
import csw.util.param.StateVariable.*;
import csw.util.param.Struct;

import static javacsw.util.params.JParameters.*;

/**
 * A Java DSL for working with parameter sets
 */
public class JParameterSetDSL {
    /**
     * Returns a new Setup
     * @param info information associated with the setup
     * @param prefix identifies the target subsystem
     * @param parameters one or more parameters (keys with values and units)
     */
    public static Setup sc(CommandInfo info, String prefix, Parameter<?>... parameters) {
        return jadd((new Setup(info, prefix)), parameters);
    }

    /**
     * Returns a new Observe
     * @param info information associated with the observe
     * @param prefix identifies the target subsystem
     * @param parameters one or more parameters (keys with values and units)
     */
    public static Observe oc(CommandInfo info, String prefix, Parameter<?>... parameters) {
        return jadd((new Observe(info, prefix)), parameters);
    }

    /**
     * Returns a new CurrentState
     * @param prefix identifies the target subsystem
     * @param parameters one or more parameters (keys with values and units)
     */
    public static CurrentState cs(String prefix, Parameter<?>... parameters) {
        return jadd((new CurrentState(prefix)), parameters);
    }

    /**
     * Returns a new DemandState
     * @param prefix identifies the target subsystem
     * @param parameters one or more parameters (keys with values and units)
     */
    public static DemandState ds(String prefix, Parameter<?>... parameters) {
        return jadd((new DemandState(prefix)), parameters);
    }

    /**
     * Returns a new DemandState
     * @param name the name of the struct
     * @param parameters one or more parameters (keys with values and units)
     */
    public static Struct struct(String name, Parameter<?>... parameters) {
        return jadd((new Struct(name)), parameters);
    }
}
