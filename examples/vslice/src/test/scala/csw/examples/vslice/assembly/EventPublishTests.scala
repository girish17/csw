package csw.examples.vslice.assembly

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import csw.examples.vslice.assembly.FollowActor.UpdatedEventData
import csw.services.events.{EventService, EventServiceSettings, EventSubscriber}
import csw.services.loc.LocationService
import csw.util.config.Events.{EventTime, SystemEvent}
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, _}

import scala.concurrent.duration._

object EventPublishTests {
  LocationService.initInterface()
  val system = ActorSystem("EventPublishTests")
}
/**
 * TMT Source Code: 8/17/16.
 */
class EventPublishTests extends TestKit(EventPublishTests.system) with ImplicitSender
    with FunSpecLike with ShouldMatchers with BeforeAndAfterAll {

  implicit val execContext = system.dispatcher

  val testEventServiceSettings = EventServiceSettings("localhost", 7777)

  val assemblyContext = AssemblyTestData.TestAssemblyContext
  val calculationConfig = assemblyContext.calculationConfig

  import assemblyContext._

  // This is used for testing and insertion into components for testing
  def eventConnection: EventService = EventService(testEventServiceSettings)

  var testEventService:Option[EventService] = None
  override def beforeAll() = {
    testEventService = Some(eventConnection)
  }

  override def afterAll = {
    //testEventService.foreach(_.shutdown())
    TestKit.shutdownActorSystem(system)
  }

  val initialElevation = 90.0

  // Test subscriber actor for telemetry
  object TestSubscriber {
    def props(prefix: String): Props = Props(new TestSubscriber(prefix))

    case object GetResults

    case class Results(msgs: Vector[SystemEvent])

  }

  class TestSubscriber(prefix: String) extends EventSubscriber(Some(testEventServiceSettings)) {

    import TestSubscriber._

    var msgs = Vector.empty[SystemEvent]

    subscribe(prefix)
    info(s"Test subscriber for prefix: $prefix")

    def receive: Receive = {
      case event: SystemEvent =>
        msgs = msgs :+ event
        log.info(s"Received event: $event")

      case GetResults => sender() ! Results(msgs)
    }
  }

  // Publisher behaves the same whether nss is in use or not so always nssNotInUse
  def newFollower(tromboneControl: Option[ActorRef], publisher: Option[ActorRef]): TestActorRef[FollowActor] = {
    val props = FollowActor.props(assemblyContext, setNssInUse(false), tromboneControl, publisher)
    TestActorRef(props)
  }

  def newTestElPublisher(tromboneControl: Option[ActorRef], eventService: Option[EventService]): TestActorRef[FollowActor] = {
    val testEventServiceProps = TrombonePublisher.props(assemblyContext, eventService)
    val publisherActorRef = system.actorOf(testEventServiceProps)
    // Enable publishing
    newFollower(tromboneControl, Some(publisherActorRef))
  }

  describe("Create follow actor with publisher and subscriber") {
    import TestSubscriber._

    /**
     * Test Description: This test just creates a publisher and checks initialization
     */
    it("should allow me to create actors without error") {

      val fakeTC = TestProbe()
      val ap = newTestElPublisher(Some(fakeTC.ref), testEventService)

      // Ensure it's not sending anything out until needed
      fakeTC.expectNoMsg(100.milli)
      ap.underlyingActor.tromboneControl should be(Some(fakeTC.ref))
    }

    /**
     * Test Description: This test uses a "fakeSubscriber" which is simulating the subscription to TCS and RTC
     * events and ships UpdatedEventData messages to the FollowActor which calculates trombone positions and
     * other things and publishes events. This one checks for the events for AOESW in the form of
     * the System event for AOESW. One event for zenith angle 0 and focus error 0 is used for testing.
     * In this case range distance and elevation are the same, which is initial elevation in this case.
     */
    it("should allow publishing one event simulating event from fake TromboneEventSubscriber") {
      // Create a new publisher with no trombone position actor
      val ap = newTestElPublisher(None, testEventService)

      val resultSubscriber = TestActorRef(TestSubscriber.props(aoSystemEventPrefix))

      val fakeTromboneEventSubscriber = TestProbe()

      // This should result in two messages being sent, one to each actor in the given order
      fakeTromboneEventSubscriber.send(ap, UpdatedEventData(za(0), fe(0), EventTime()))

      // This is to give actors time to run
      expectNoMsg(100.milli)

      // Ask our test subscriber for the published events
      resultSubscriber ! GetResults

      val result = expectMsgClass(classOf[Results])
      result.msgs.size should be(1)
      result.msgs should equal(Vector(SystemEvent(aoSystemEventPrefix).madd(el(calculationConfig.defaultInitialElevation), rd(calculationConfig.defaultInitialElevation))))
    }

    /**
     * Test Description: This test is similar to the last but a set of events are used that vary the zenith angle while holding
     * the focus error constant to see that multiple events are generated. The computed, expected values are computed with
     * AlgorithmData. If you change the algorithm you need to update the test helpers.
     */
    it("should allow publishing several events with fake tromboneEventSubscriber") {
      import AssemblyTestData._

      // Ignoring the messages for TrombonePosition (set to None)
      val ap = newTestElPublisher(None, testEventService)

      // This creates a subscriber to get all aoSystemEventPrefix SystemEvents published
      val resultSubscriber = TestActorRef(TestSubscriber.props(aoSystemEventPrefix))

      val testFE = 10.0

      // These are fake messages for the CalculationActor from the EventSubscriber
      val events = testZenithAngles.map(td => UpdatedEventData(za(td), fe(testFE), EventTime()))

      // This should result in two messages being sent, one to each actor in the given order
      val fakeTromboneSubscriber = TestProbe()
      events.foreach(ev => fakeTromboneSubscriber.send(ap, ev))

      // This is to give actors time to run
      expectNoMsg(100.milli)

      resultSubscriber ! GetResults
      // Check the events received through the Event Service
      val result = expectMsgClass(classOf[Results])

      // Calculate expected events
      val testResult = newRangeAndElData(testFE)

      val aoeswExpected = testResult.map(f => SystemEvent(aoSystemEventPrefix).madd(naElevationKey -> f._2 withUnits naElevationUnits, naRangeDistanceKey -> f._1 withUnits naRangeDistanceUnits))
      //info("aowes: " + aoeswExpected)

      aoeswExpected should equal(result.msgs)
    }

    /**
     * Test Description: This takes it one step further and replaced the fakeTromboneSubscriber with the actual TromboneEventSubscriber
     * and uses the event service to publish events. The focus error of 10 is published then the set of data varying the zenith angle.
     * The TromboneEventSubscriber receives the events forwards them to the follow actor which then receives the events in the resultSubscriber.
     * Note that the EventSubscriber and FollowActor are separate so that the FollowActor can be tested as a standalone actor without the
     * event service as is done in this and the previous tests.
     */
    it("should allow publishing several events through the event service") {
      import AssemblyTestData._
      // Ignoring the messages for TrombonePosition
      // Create the trombone publisher for publishing SystemEvents to AOESW
      val publisherActorRef = system.actorOf(TrombonePublisher.props(assemblyContext, testEventService))
      // Create the calculator actor and give it the actor ref of the publisher for sending calculated events
      val followActorRef = system.actorOf(FollowActor.props(assemblyContext, setNssInUse(false), None, Some(publisherActorRef)))
      // create the subscriber that listens for events from TCS for zenith angle and focus error from RTC
      system.actorOf(TromboneEventSubscriber.props(assemblyContext, setNssInUse(false), Some(followActorRef), testEventService))

      // This creates a local subscriber to get all aoSystemEventPrefix SystemEvents published for testing
      val resultSubscriber = TestActorRef(TestSubscriber.props(aoSystemEventPrefix))

      // This eventService is used to simulate the TCS and RTC publishing zentith angle and focus error
      val tcsRtc = EventService(testEventServiceSettings)

      val testFE = 10.0
      // Publish a single focus error. This will generate a published event
      tcsRtc.publish(SystemEvent(focusErrorPrefix).add(fe(testFE)))

      // These are fake messages for the FollowActor that will be sent to simulate the TCS
      val tcsEvents = testZenithAngles.map(f => SystemEvent(zaConfigKey.prefix).add(za(f)))

      // This should result in the length of tcsEvents being published
      tcsEvents.map(f => tcsRtc.publish(f))

      // This is to give actors time to run and subscriptions to register
      expectNoMsg(250.milli)

      // Ask the local subscriber for all the ao events published for testing
      resultSubscriber ! GetResults
      // Check the events received through the Event Service
      val result = expectMsgClass(classOf[Results])
      info("result: " + result)
      info("resultsize: " + result.msgs.size)

      // Calculate expected events
      val testResult = newRangeAndElData(testFE)

      val firstOne = SystemEvent(aoSystemEventPrefix).madd(naElevationKey -> testResult.head._2 withUnits naElevationUnits, naRangeDistanceKey -> testResult.head._1 withUnits naRangeDistanceUnits)
      info("First: " + firstOne)

      val zaExpected = testResult.map(f => SystemEvent(aoSystemEventPrefix).madd(naElevationKey -> f._2 withUnits naElevationUnits, naRangeDistanceKey -> f._1 withUnits naRangeDistanceUnits))
      val aoeswExpected = firstOne +: zaExpected
      info("aowes: " + aoeswExpected)
      info("aoesw size: " + aoeswExpected.size)

      // Here is the test for equality - total 16 messages
      aoeswExpected should equal(result.msgs)

    }
  }

}