package org.tmt.csw.apps.sequencer

import scala.tools.nsc._
import scala.tools.nsc.interpreter._
import akka.actor.{Props, ActorSystem}

/**
 * Sequencer REPL shell.
 */
object Sequencer extends App {

  def repl = new ILoop {
    override def createInterpreter(): Unit = {
      super.createInterpreter()
      readInit()
      args.foreach(f => readFile(f))
    }

    // Reads resources/Init.scala, which adds imports for use in the shell
    def readInit(): Unit = {
      import scala.io.Source._
      echo("Initializing...")
      val init = fromInputStream(this.getClass.getResourceAsStream("/Init.scala")).mkString
      intp quietRun init
    }

    // Reads a given file from the command line
    def readFile(fileName: String): Unit = {
      import scala.io.Source._
      echo(s"Reading $fileName ...")
      val file = fromFile(fileName).mkString
      intp quietRun file
    }

    override def prompt = "\nseq> "

    override def printWelcome() {
      echo("Welcome to the TMT sequencer")
    }
  }

  val settings = new Settings
  settings.usejavacp.value = true
  settings.deprecation.value = true
  repl process settings
}