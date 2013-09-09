package org.tmt.csw.cs.core

import java.io.File
import scalax.io.Resource
import org.tmt.csw.cs.api.ConfigData

/**
 * Represents a configuration file
 */
case class ConfigFile(file: File) extends ConfigData {
  /**
   * @return a representation of the object as a byte array
   */
  def getBytes: Array[Byte] = {
    Resource.fromFile(file).byteArray
  }

  override def toString: String = {
    Resource.fromFile(file).string
  }
}