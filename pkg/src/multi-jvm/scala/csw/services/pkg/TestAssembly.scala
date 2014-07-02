package csw.services.pkg

import akka.actor.Props
import csw.services.cmd.akka.{AssemblyCommandServiceActor, OneAtATimeCommandQueueController}
import csw.services.ls.LocationServiceActor
import LocationServiceActor.{ServiceType, ServiceId}

object TestAssembly {
  def props(name: String): Props = Props(classOf[TestAssembly], name)
}

// A test assembly
case class TestAssembly(name: String) extends Assembly with AssemblyCommandServiceActor with OneAtATimeCommandQueueController {

  // Get the assembly's HCDs from the location service (which must be running)
  requestServices(List(
    ServiceId("HCD-2A", ServiceType.HCD),
    ServiceId("HCD-2B", ServiceType.HCD)
  ))

  def receive: Receive = receiveComponentMessages orElse receiveCommands

  def initialize(): Unit = {log.info("Assembly1 initialize")}

  def startup(): Unit = {log.info("Assembly1 startup")}

  def run(): Unit = {log.info("Assembly1 run")}

  def shutdown(): Unit = {log.info("Assembly1 shutdown")}

  def uninit(): Unit = {log.info("Assembly1 uninit")}

  def remove(): Unit = {log.info("Assembly1 remove")}
}