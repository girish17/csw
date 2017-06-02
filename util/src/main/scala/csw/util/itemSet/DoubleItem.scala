package csw.util.itemSet

import csw.util.itemSet.UnitsOfMeasure.{NoUnits, Units}

import scala.collection.immutable.Vector
import scala.language.implicitConversions

/**
 * The type of a value for an DoubleKey
 *
 * @param keyName the name of the key
 * @param values  the value for the key
 * @param units   the units of the value
 */
final case class DoubleItem(keyName: String, values: Vector[Double], units: Units) extends Item[Double] {

  override def withUnits(unitsIn: Units) = copy(units = unitsIn)
}

/**
 * A key of Double values
 *
 * @param nameIn the name of the key
 */
final case class DoubleKey(nameIn: String) extends Key[Double, DoubleItem](nameIn) {

  override def set(v: Vector[Double], units: Units = NoUnits) = DoubleItem(keyName, v, units)

  override def set(v: Double*) = DoubleItem(keyName, v.toVector, units = UnitsOfMeasure.NoUnits)
}

