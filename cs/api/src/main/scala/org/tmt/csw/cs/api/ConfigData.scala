package org.tmt.csw.cs.api

/**
 * Interface implemented by the configuration data objects being managed
 */
trait ConfigData {
  /**
   * @return a representation of the object as a byte array
   */
  def getBytes : Array[Byte]
}