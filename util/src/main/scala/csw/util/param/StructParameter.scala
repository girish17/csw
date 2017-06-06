package csw.util.param

import csw.util.param.Parameters.{ParameterSet, Prefix, ParameterSetType}
import csw.util.param.UnitsOfMeasure.{NoUnits, Units}

/**
 * TMT Source Code: 9/28/16.
 */
case class StructParameter(keyName: String, values: Vector[Struct], units: Units) extends Parameter[Struct] {

  override def withUnits(unitsIn: Units) = copy(units = unitsIn)

}

final case class StructKey(nameIn: String) extends Key[Struct, StructParameter](nameIn) {
  override def set(v: Vector[Struct], units: Units = NoUnits) = StructParameter(keyName, v, units)

  override def set(v: Struct*) = StructParameter(keyName, v.toVector, units = NoUnits)
}

/**
 * A configuration for setting telescope and instrument parameters
 *
 * @param name   name for the struct
 * @param items an optional initial set of items (keys with values)
 */
case class Struct(name: String, items: ParameterSet = Set.empty[Parameter[_]]) extends ParameterSetType[Struct] {

  /**
   * This is here for Java to construct with String
   */
  def this(name: String) = this(name, Set.empty[Parameter[_]])

  override def create(data: ParameterSet) = Struct(name, data)

  def dataToString1 = items.mkString("(", ", ", ")")

  override def toString = s"$name { $dataToString1 }"
}
