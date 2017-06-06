package csw.util.param

import java.util

import scala.collection.immutable.Vector
import scala.language.implicitConversions
import csw.util.param.UnitsOfMeasure.{NoUnits, Units}
import spray.json.{JsArray, JsObject, JsString, JsValue, JsonFormat}

object GenericParameter {

  /**
   * type of a function that reads JSON and returns a new GenericItem
   */
  type JsonReaderFunc = JsValue => GenericParameter[_]

  // Used to register a JsonFormat instance to use to read and write JSON for a given GenericItem subclass
  private var jsonReaderMap = Map[String, JsonReaderFunc]()

  /**
   * Sets the JSON reader and writer for a GenericItem
   *
   * @param typeName   the tag name in JSON
   * @param jsonReader implements creating this object from JSON
   * @tparam T the (scala) type parameter of the GenericItem
   */
  def register[T](typeName: String, jsonReader: JsonReaderFunc): Unit = jsonReaderMap += (typeName -> jsonReader)

  /**
   * Lookup the JsonFormat for the given type name
   *
   * @param typeName the JSON key
   * @return the JsonFormat, if registered
   */
  def lookup(typeName: String): Option[JsonReaderFunc] = jsonReaderMap.get(typeName)
}

/**
 * The type of a value for an GenericKey
 *
 * @param typeName the name of the type S (for JSON serialization)
 * @param keyName  the name of the key
 * @param values    the value for the key
 * @param units    the units of the value
 */
case class GenericParameter[S: JsonFormat](typeName: String, keyName: String, values: Vector[S], units: Units) extends Parameter[S] {

  /**
   * @return a JsValue representing this item
   */
  def toJson: JsValue = {
    val valueFormat = implicitly[JsonFormat[S]]
    val unitsFormat = ItemSetJson.unitsFormat
    JsObject(
      "keyName" -> JsString(keyName),
      "value" -> JsArray(values.map(valueFormat.write)),
      "units" -> unitsFormat.write(units)
    )
  }

  override def withUnits(unitsIn: Units): Parameter[S /*, S*/ ] = copy(units = unitsIn)
}

/**
 * A key of S values
 *
 * @param typeName the name of the type S (for JSON serialization)
 * @param nameIn   the name of the key
 */
case class GenericKey[S: JsonFormat](typeName: String, nameIn: String) extends Key[S, GenericParameter[S]](nameIn) {

  override def set(v: Vector[S], units: Units = NoUnits): GenericParameter[S] = GenericParameter(typeName, keyName, v, units)

  override def set(v: S*): GenericParameter[S /*, S*/ ] = GenericParameter(typeName, keyName, v.toVector, UnitsOfMeasure.NoUnits)
}

