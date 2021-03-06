package javacsw.services.loc.tests;


import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.loc.ComponentId;
import csw.services.loc.Connection;
import csw.services.loc.LocationService;
import javacsw.services.loc.JComponentId;
import javacsw.services.loc.JComponentType;
import javacsw.services.loc.JConnection;
import javacsw.services.loc.JLocationService;

/**
 * Registers one or more (dummy) http services in order to test the location service.
 * If a command line arg is given, it should be the number of services to start (default: 1).
 * Each service will have a number appended to its name.
 * You should start the TestServiceClient with the same number, so that it
 * will try to find all the services.
 * The client and service applications can be run on the same or different hosts.
 */
public class TestHttpService extends AbstractActor {
    // Component id for the ith service
    static ComponentId componentId(int i) {
        return JComponentId.componentId("TestHttpService-" + i, JComponentType.Assembly);
    }

    // Connection for the ith service
    public static Connection connection(int i) {
        return JConnection.httpConnection(componentId(i));
    }

    // Used to create the ith TestHttpService actor
    static Props props(int i) {
        return Props.create(new Creator<TestHttpService>() {
            private static final long serialVersionUID = 1L;

            @Override
            public TestHttpService create() throws Exception {
                return new TestHttpService(i);
            }
        });
    }

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);


    // Constructor: registers self with the location service
    public TestHttpService(int i) {
        int port = 9000 + i;
        JLocationService.registerHttpConnection(TestHttpService.componentId(i), port, "", getContext().system());

        receive(ReceiveBuilder.
                matchAny(t -> log.warning("Unknown message received: " + t)).
                build());
    }

    // main: Starts and registers the given number of services (default: 1)
    public static void main(String[] args) {
        int numServices = 1;
        if (args.length != 0)
            numServices = Integer.valueOf(args[0]);

        LocationService.initInterface();
        ActorSystem system = ActorSystem.create();
        for (int i = 0; i < numServices; i++)
            system.actorOf(TestHttpService.props(i+1));
    }
}
