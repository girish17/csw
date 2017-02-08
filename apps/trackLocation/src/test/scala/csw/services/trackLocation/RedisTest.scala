//package csw.services.trackLocation
//
//import java.nio.file.Paths
//
//import akka.actor.ActorSystem
//import akka.testkit.TestKit
//import akka.util.Timeout
//import com.typesafe.scalalogging.LazyLogging
//import csw.services.events.{BlockingTelemetryService, EventServiceSettings}
//import csw.services.loc.Connection.HttpConnection
//import csw.services.loc.ConnectionType.HttpType
//import csw.services.loc.LocationService.ResolvedHttpLocation
//import csw.services.loc.{ComponentId, ComponentType, LocationService}
//import csw.util.config.Events.StatusEvent
//import csw.util.config.StringKey
//import org.scalatest.{DoNotDiscover, FunSuiteLike}
//
//import scala.concurrent.duration._
//import scala.concurrent.{Await, Future}
//
//object RedisTest {
//  println("\nRedisTest:\n")
//  LocationService.initInterface()
//  private val system = ActorSystem("Test")
//}
//
///**
// * Test the trackLocation app in-line
// */
////@DoNotDiscover
//class RedisTest extends TestKit(RedisTest.system) with FunSuiteLike with LazyLogging {
//  implicit val sys = RedisTest.system
//
//  import system.dispatcher
//
//  implicit val timeout = Timeout(60.seconds)
//
//  test("Test telemetry service looking up redis with location service") {
//    // Do the equivalent of running this from the command line (redisTest.conf is under test/resources):
//    //   tracklocation --name redisTest redisTest.conf
//    val name = "redisTest"
//    val url = getClass.getResource("/redisTest.conf")
//    val configFile = Paths.get(url.toURI).toFile.getAbsolutePath
//    Future {
//      TrackLocation.main(Array("--name", name, configFile))
//    }
//
//    // In another JVM, use a telemetry service based on the Redis instance:
//    val connection = HttpConnection(ComponentId(name, ComponentType.Service))
//    val locationsReady = Await.result(LocationService.resolve(Set(connection)), timeout.duration)
//    logger.debug(s"Found $locationsReady")
//    assert(locationsReady.locations.size == 1)
//    val loc = locationsReady.locations.head
//    assert(loc.isResolved)
//    assert(loc.connection.connectionType == HttpType)
//    assert(loc.connection.componentId.name == name)
//    val httpLoc = loc.asInstanceOf[ResolvedHttpLocation]
//
//    val settings = EventServiceSettings(redisHostname = httpLoc.uri.getHost, redisPort = httpLoc.uri.getPort)
//    val telemetryService = BlockingTelemetryService(timeout.duration, settings)
//    val key = StringKey("testKey")
//    val e1 = StatusEvent("test").add(key.set("Test Passed"))
//    telemetryService.publish(e1)
//    val e2Opt = telemetryService.get("test")
//    assert(e2Opt.isDefined)
//    assert(e1 == e2Opt.get)
//
//    println(e2Opt.get(key))
//    telemetryService.shutdown()
//    println("Redis shutdown completed")
//  }
//}
//
