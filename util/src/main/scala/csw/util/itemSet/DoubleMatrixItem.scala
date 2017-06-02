package csw.util.itemSet

import csw.util.itemSet.UnitsOfMeasure.{NoUnits, Units}
import spray.json.DefaultJsonProtocol

import scala.collection.immutable.Vector
import scala.language.implicitConversions

/**
 * A Scala equivalent of a 2d array of Doubles
 */
case class DoubleMatrix(data: Array[Array[Double]]) {
  import ArrayAndMatrixEquality._

  override def toString = (for (l <- data) yield l.mkString("(", ",", ")")).mkString("(", ",", ")")

  /**
   * Gets the value at the given row and column
   */
  def apply(row: Int, col: Int) = data(row)(col)

  override def canEqual(other: Any) = other.isInstanceOf[DoubleMatrix]

  override def equals(other: Any) = other match {
    case that: DoubleMatrix =>
      this.canEqual(that) && deepMatrixValueEquals(this.data, that.data)
    case _ => false
  }
}

case object DoubleMatrix extends DefaultJsonProtocol {
  implicit def format = jsonFormat1(DoubleMatrix.apply)

  implicit def create(value: Array[Array[Double]]): DoubleMatrix = DoubleMatrix(value)
}

/**
 * The type of a value for a DoubleMatrixKey: One or more 2d arrays (implemented as DoubleMatrix)
 *
 * @param keyName the name of the key
 * @param values  the value for the key
 * @param units   the units of the value
 */
final case class DoubleMatrixItem(keyName: String, values: Vector[DoubleMatrix], units: Units) extends Item[DoubleMatrix] {
  override def withUnits(unitsIn: Units) = copy(units = unitsIn)
}

/**
 * A key for DoubleMatrix values
 *
 * @param nameIn the name of the key
 */
final case class DoubleMatrixKey(nameIn: String) extends Key[DoubleMatrix, DoubleMatrixItem](nameIn) {

  override def set(v: Vector[DoubleMatrix], units: Units = NoUnits) = DoubleMatrixItem(keyName, v, units)

  override def set(v: DoubleMatrix*) = DoubleMatrixItem(keyName, v.toVector, units = UnitsOfMeasure.NoUnits)
}

