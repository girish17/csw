package csw.util.param

import csw.util.param.UnitsOfMeasure.{NoUnits, Units}

import scala.collection.immutable.Vector
import scala.language.implicitConversions

/**
 * The type of a value for an CharKey
 *
 * @param keyName the name of the key
 * @param values   the value for the key
 * @param units   the units of the value
 */
final case class CharParameter(keyName: String, values: Vector[Char], units: Units) extends Parameter[Char] {

  override def withUnits(unitsIn: Units) = copy(units = unitsIn)
}

/**
 * A key of Char values
 *
 * @param nameIn the name of the key
 */
final case class CharKey(nameIn: String) extends Key[Char, CharParameter /*Character*/ ](nameIn) {

  override def set(v: Vector[Char], units: Units = NoUnits) = CharParameter(keyName, v, units)

  override def set(v: Char*) = CharParameter(keyName, v.toVector, units = UnitsOfMeasure.NoUnits)
}
