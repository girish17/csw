package javacsw.services.pkg;

import akka.actor.ActorRef;
import csw.services.ccs.Validation.*;
import csw.util.config.Configurations;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Supports Java subclasses of AssemblyController and LifecycleHandler
 */
@SuppressWarnings({"unused", "OptionalUsedAsFieldOrParameterType"})
public abstract class JAssemblyControllerWithLifecycleHandler extends AbstractAssemblyControllerWithLifecycleHandler {

    @Override
    public abstract Validation setup(Boolean locationsResolved, Configurations.SetupConfigArg configArg, Optional<ActorRef> replyTo);

    @Override
    public abstract Validation observe(Boolean locationsResolved, Configurations.ObserveConfigArg configArg, Optional<ActorRef> replyTo);

    @Override
    public CompletableFuture<RequestResult> jrequest(Boolean locationsResolved, Configurations.SetupConfig config) {
        return super.jrequest(locationsResolved, config);
    }

    public JAssemblyControllerWithLifecycleHandler(AssemblyInfo info) {
        super(info);
    }

}
