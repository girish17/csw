package csw.util.param

import scala.annotation.varargs
import scala.collection.JavaConverters._
import scala.collection.immutable.Vector
import scala.language.implicitConversions
import csw.util.param.UnitsOfMeasure.{NoUnits, Units}
import scala.compat.java8.OptionConverters._

/**
 * The type of a value for an IntKey
 *
 * @param keyName the name of the key
 * @param values   the value for the key
 * @param units   the units of the value
 */
final case class IntParameter(keyName: String, values: Vector[Int], units: Units) extends Parameter[Int] {

  override def withUnits(unitsIn: Units) = copy(units = unitsIn)
}

/**
 * A key of Int values
 *
 * @param nameIn the name of the key
 */
final case class IntKey(nameIn: String) extends Key[Int, IntParameter](nameIn) {

  override def set(v: Vector[Int], units: Units = NoUnits) = IntParameter(keyName, v, units)

  override def set(v: Int*) = IntParameter(keyName, v.toVector, units = UnitsOfMeasure.NoUnits)
}

