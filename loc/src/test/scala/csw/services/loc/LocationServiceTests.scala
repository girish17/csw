package csw.services.loc

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.scalalogging.slf4j.LazyLogging
import csw.services.loc.LocationService.{AkkaRegistration, ComponentRegistered, HttpRegistration, RegistrationTracker}
import csw.util.Components.{AkkaConnection, Assembly, ComponentId, HttpConnection}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuiteLike}

import scala.concurrent.duration._


class LocationServiceTests extends TestKit(ActorSystem("Test"))
  with ImplicitSender with FunSuiteLike with BeforeAndAfterAll with BeforeAndAfterEach with LazyLogging {

  override def afterAll = TestKit.shutdownActorSystem(system)

  test("Test location service register with only") {

    val componentId = ComponentId("TestAss1", Assembly)

    val trackerResponseProbe = TestProbe()

    val actorTestProbe = TestProbe()

    val akkaConnection = AkkaConnection(componentId)
    val akkaRegister = AkkaRegistration(akkaConnection, actorTestProbe.ref, "test.prefix")

    system.actorOf(RegistrationTracker.props(Set(akkaRegister), Some(trackerResponseProbe.ref)))

    var m1 = Seq.empty[ComponentRegistered]
    m1 = m1 :+ trackerResponseProbe.expectMsgType[ComponentRegistered](10.second)

    assert(m1.size == 1)
    assert(m1.contains(ComponentRegistered(akkaConnection)))
  }

  test("Test location service register with both akka and http as sequence") {

    val componentId = ComponentId("TestAss1", Assembly)

    val trackerResponseProbe = TestProbe()

    val actorTestProbe = TestProbe()

    val akkaConnection = AkkaConnection(componentId)
    val httpConnection = HttpConnection(componentId)

    val akkaRegister = AkkaRegistration(akkaConnection, actorTestProbe.ref, "test.prefix")
    val httpRegister = HttpRegistration(httpConnection, 1000, "test.prefix")

    system.actorOf(RegistrationTracker.props(Set(akkaRegister, httpRegister), Some(trackerResponseProbe.ref)))

    var m1 = Seq.empty[ComponentRegistered]
    m1 = m1 :+ trackerResponseProbe.expectMsgType[ComponentRegistered](10.second)
    m1 = m1 :+ trackerResponseProbe.expectMsgType[ComponentRegistered](10.second)

    assert(m1.size == 2)
    assert(m1.contains(ComponentRegistered(akkaConnection)))
    assert(m1.contains(ComponentRegistered(httpConnection)))
  }

  test("Test tracker with one Akka component") {
    import LocationService._

    val componentId = ComponentId("TestAss1", Assembly)
    val testPrefix = "test.prefix"
    val testProbe = TestProbe()
    val actorTestProbe = TestProbe()

    LocationService.registerAkkaConnection(componentId, actorTestProbe.ref, testPrefix)

    val tracker = system.actorOf(LocationTracker.props(Some(testProbe.ref)), "LocationTracker!")

    val ac = AkkaConnection(componentId)

    tracker ! TrackConnection(ac)

    testProbe.expectMsg(15.seconds, Unresolved(ac))

    val ready = testProbe.expectMsgClass(10.seconds, classOf[ResolvedAkkaLocation])
    assert(ready.connection == ac)
    expectNoMsg(5.seconds)
  }

  test("Test tracker with one Akka component - try to add twice") {
    import LocationService._

    val componentId = ComponentId("TestAss1", Assembly)
    val testPrefix = "test.prefix"

    val testProbe = TestProbe()
    val actorTestProbe = TestProbe()

    LocationService.registerAkkaConnection(componentId, actorTestProbe.ref, testPrefix)

    val tracker = system.actorOf(LocationTracker.props(Some(testProbe.ref)))

    val ac = AkkaConnection(componentId)

    tracker ! TrackConnection(ac)

    testProbe.expectMsg(15.seconds, Unresolved(ac))

    val ready = testProbe.expectMsgClass(10.seconds, classOf[ResolvedAkkaLocation])
    assert(ready.connection == ac)

    expectNoMsg(5.seconds)

    tracker ! TrackConnection(ac)
    expectNoMsg(5.seconds)
  }

  test("Test tracker with one HTTP component") {
    import LocationService._

    val componentId = ComponentId("TestAss1", Assembly)
    val testPrefix = "test.prefix"
    val testPort = 1000

    val testProbe = TestProbe()

    LocationService.registerHttpConnection(componentId, testPort, testPrefix)

    val tracker = system.actorOf(LocationTracker.props(Some(testProbe.ref)))

    val hc = HttpConnection(componentId)

    tracker ! TrackConnection(hc)

    testProbe.expectMsg(15.seconds, Unresolved(hc))

    val ready = testProbe.expectMsgClass(10.seconds, classOf[ResolvedHttpLocation])
    assert(ready.connection == hc)

    expectNoMsg(5.seconds)
  }

  test("Test tracker with two components registered before tracker") {
    import LocationService._

    val componentId = ComponentId("TestAss1", Assembly)
    val testPrefix = "test.prefix"
    val testPort = 1000

    val testProbe = TestProbe()

    val actorTestProbe = TestProbe()

    LocationService.registerHttpConnection(componentId, testPort, testPrefix)
    LocationService.registerAkkaConnection(componentId, actorTestProbe.ref, testPrefix)

    val tracker = system.actorOf(LocationTracker.props(Some(testProbe.ref)))

    val ac = AkkaConnection(componentId)
    val hc = HttpConnection(componentId)

    tracker ! TrackConnection(ac)
    testProbe.expectMsg(15.seconds, Unresolved(ac))
    val r1 = testProbe.expectMsgClass(10.seconds, classOf[ResolvedAkkaLocation])
    assert(r1.connection == ac)

    expectNoMsg(5.seconds)  // Give time for all to be registered

    tracker ! TrackConnection(hc)
    testProbe.expectMsg(15.seconds, Unresolved(hc))
    val r2 = testProbe.expectMsgClass(15.seconds, classOf[ResolvedHttpLocation])
    assert(r2.connection == hc)

    // Assure no messages coming for no tracking
    testProbe.expectNoMsg(5.seconds)
  }

  test("Test tracker to ensure no messages without a registered comp") {
    import LocationService._

    val testProbe = TestProbe()

    system.actorOf(LocationTracker.props(Some(testProbe.ref)))

    // Assure no messages coming for no tracking
    testProbe.expectNoMsg(5.seconds)
  }

  test("Test tracker with two components register later") {
    import LocationService._

    val componentId = ComponentId("TestAss1", Assembly)
    val testPrefix = "test.prefix"
    val testPort = 1000

    val testProbe = TestProbe()

    val actorTestProbe = TestProbe()

    val tracker = system.actorOf(LocationTracker.props(Some(testProbe.ref)))

    val ac = AkkaConnection(componentId)
    val hc = HttpConnection(componentId)

    tracker ! TrackConnection(ac)
    tracker ! TrackConnection(hc)
    testProbe.expectMsg(15.seconds, Unresolved(ac))
    testProbe.expectMsg(15.seconds, Unresolved(hc))

    // Assure no messages coming for no tracking
    testProbe.expectNoMsg(5.seconds)

    LocationService.registerAkkaConnection(componentId, actorTestProbe.ref, testPrefix)

    val r1 = testProbe.expectMsgClass(10.seconds, classOf[ResolvedAkkaLocation])
    assert(r1.connection == ac)

    LocationService.registerHttpConnection(componentId, testPort, testPrefix)
    val r2 = testProbe.expectMsgClass(15.seconds, classOf[ResolvedHttpLocation])
    assert(r2.connection == hc)
    // Assure no messages coming for no tracking
    testProbe.expectNoMsg(5.seconds)
  }

  test("Test tracker with two components then remove one") {
    import LocationService._

    val componentId = ComponentId("TestAss1", Assembly)
    val testPrefix = "test.prefix"
    val testPort = 1000

    val testProbe = TestProbe()

    val actorTestProbe = TestProbe()

    val tracker = system.actorOf(LocationTracker.props(Some(testProbe.ref)))

    val ac = AkkaConnection(componentId)
    val hc = HttpConnection(componentId)

    tracker ! TrackConnection(ac)
    tracker ! TrackConnection(hc)
    testProbe.expectMsg(15.seconds, Unresolved(ac))
    testProbe.expectMsg(15.seconds, Unresolved(hc))

    // Assure no messages coming for no tracking
    testProbe.expectNoMsg(5.seconds)

    LocationService.registerAkkaConnection(componentId, actorTestProbe.ref, testPrefix)

    val r1 = testProbe.expectMsgClass(10.seconds, classOf[ResolvedAkkaLocation])
    assert(r1.connection == ac)

    LocationService.registerHttpConnection(componentId, testPort, testPrefix)
    val r2 = testProbe.expectMsgClass(15.seconds, classOf[ResolvedHttpLocation])
    assert(r2.connection == hc)

    tracker ! UnTrackConnection(hc)

    val r3 = testProbe.expectMsgClass(15.seconds, classOf[UnTrackedLocation])
    assert(r3.connection == hc)

    // Re-add it again
    tracker ! TrackConnection(hc)
    testProbe.expectMsg(15.seconds, Unresolved(hc))
    val r4 = testProbe.expectMsgClass(15.seconds, classOf[ResolvedHttpLocation])
    assert(r4.connection == hc)
    // Assure no messages coming for no tracking
    testProbe.expectNoMsg(5.seconds)
  }
}
