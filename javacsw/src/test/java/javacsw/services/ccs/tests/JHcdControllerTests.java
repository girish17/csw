package javacsw.services.ccs.tests;

import akka.actor.*;
import akka.testkit.javadsl.TestKit;
import csw.util.param.StringKey;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * Tests the java API of HcdController.
 */
public class JHcdControllerTests {

    static final String testPrefix1 = "wfos.blue.filter";
    static final String testPrefix2 = "wfos.red.filter";
    static final StringKey position = new StringKey("position");

    private static ActorSystem system;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create();
    }

    @AfterClass
    public static void teardown() {
        TestKit.shutdownActorSystem(system);
        system = null;
    }
/*
    static class TestHcdController extends JHcdController {
        ActorRef worker = getContext().actorOf(TestWorker.props());
//        LoggingAdapter log = Logging.getLogger(system, this);

        // Used to create the TestHcdController actor
        public static Props props() {
            return Props.create(new Creator<TestHcdController>() {
                private static final long serialVersionUID = 1L;

                @Override
                public TestHcdController create() throws Exception {
                    return new TestHcdController();
                }
            });
        }

        // Send the config to the worker for processing
        @Override
        public void process(SetupConfig config) {
            worker.tell(config, self());
        }

        // Ask the worker actor to send us the current state (handled by parent trait)
        @Override
        public void requestCurrent() {
            worker.tell(new TestWorker.RequestCurrentState(), self());
        }

        @Override
        public PartialFunction<Object, BoxedUnit> receive() {
            return controllerReceive();
        }
    }
*/
/*
    // -- Test worker actor that simulates doing some work --
    static class TestWorker extends AbstractActor {
        LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        // Simulate getting the initial state from the device
        CurrentState initialState = new CurrentState(testPrefix1).jset(position, "None");

        // Simulated current state
        CurrentState currentState = initialState;

        // Used to create the TestWorker actor
        public static Props props() {
            return Props.create(new Creator<TestWorker>() {
                private static final long serialVersionUID = 1L;

                @Override
                public TestWorker create() throws Exception {
                    return new TestWorker();
                }
            });
        }

        // Message sent when simulated work is done
        static class WorkDone {
            Setup config;

            WorkDone(Setup config) {
                this.config = config;
            }
        }

        // Message to request the current state values
        static class RequestCurrentState {
        }

        public TestWorker() {
            receive(ReceiveBuilder.
                    match(Setup.class, this::handleSetupConfig).
                    match(RequestCurrentState.class, rcs -> handleRequestCurrentState()).
                    match(WorkDone.class, wd -> handleWorkDone(wd.config)).
                    matchAny(t -> log.warning("Unknown message received: " + t)).
                    build());
        }

        // Simulate doing work
        private void handleSetupConfig(Setup config) {
            log.debug("Start processing " + config);
            FiniteDuration d = FiniteDuration.create(2, TimeUnit.SECONDS);
            getContext().system().scheduler().scheduleOnce(d, self(), new WorkDone(config), system.dispatcher(), null);
        }

        private void handleRequestCurrentState() {
            log.debug("Requested current state");
            getContext().parent().tell(currentState, self());
        }

        private void handleWorkDone(Setup config) {
            log.debug("Done processing " + config);
            currentState = new CurrentState(config);
            getContext().parent().tell(currentState, self());
        }
    }
    */


    // Tests sending a DemandState to a test HCD, then starting a matcher actor to subscribe
    // to the current state (a state variable updated by the HCD). When the current state matches
    // the demand state, the matcher actor replies with a message (containing the current state).
    //
    // Note: Test requires that Redis is running externally
  /*
    @Test
    public void testHcdController() throws Exception {
        new JavaTestKit(system) {
            {
                LoggingAdapter log = Logging.getLogger(system, this);
                ActorRef hcdController = system.actorOf(TestHcdController.props());
                // Send a setup config to the HCD
                Setup config = new Setup(testPrefix2).jset(position, "IR3");
                hcdController.tell(new Submit(config), getRef());
                DemandState demand = new DemandState(config);

                // Create an actor to subscribe and wait for the HCD to get to the demand state
                BiFunction<DemandState, CurrentState, Boolean> matcher = (d, c) -> Objects.equals(d.prefix(), c.prefix()) && Objects.equals(d.items(), c.items());
                JHcdStatusMatcherActorFactory.getHcdStatusMatcherActor(
                        system, Collections.singletonList(demand), Collections.singleton(hcdController), getRef(),
                        RunId.create(), new Timeout(5, TimeUnit.SECONDS), matcher);

                new Within(JavaTestKit.duration("10 seconds")) {
                    protected void run() {
                        CommandStatus status = expectMsgClass(CommandStatus.Completed.class);
                        log.debug("Done (2). Received reply from matcher with current state: " + status);
                    }
                };
            }
        };
    }
*/
}
