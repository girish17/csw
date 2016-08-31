package csw.util.config

import csw.util.config.UnitsOfMeasure.{NoUnits, Units}
import spray.json._
import spray.json.DefaultJsonProtocol

import scala.collection.immutable.Vector
import scala.language.implicitConversions

/**
 * Represents a single choice
 */
case class Choice(name: String) {
  override def toString = name
}

/**
 * Provides implicit conversion from String to Choice
 */
object Choice {
  implicit def toChoice(name: String): Choice = new Choice(name)
}

/**
 * Represents a set of choices
 */
case class Choices(values: Set[Choice]) {
  def contains(one: Choice) = values.contains(one)

  override def toString = values.mkString("(", ",", ")")
}

/**
 * Provides a varargs constructor for Choices
 */
object Choices {
  def from(choicesIn: String*):Choices = Choices(choicesIn.map(Choice(_)).toSet)
}

/**
 * The type of a value for a ChoiceKey: One or more Choice objects
 *
 * @param keyName the name of the key
 * @param values  the value for the key
 * @param units   the units of the values
 */
final case class ChoiceItem(keyName: String, choices: Choices, values: Vector[Choice], units: Units) extends Item[Choice] {
  override def withUnits(unitsIn: Units) = copy(units = unitsIn)

  def choice(name: String):Option[Choice] = choices.values.find(_.name == name)
}

/**
 * A key for a choice item similar to an enumeration
 *
 * @param nameIn  the name of the key
 * @param choices the available choices, the values set must be in the choices
 */
final case class ChoiceKey(nameIn: String, choices: Choices) extends Key[Choice, ChoiceItem](nameIn) {

  override def set(v: Vector[Choice], units: Units = NoUnits) = {
    // Check to make sure set values are in the choices -- could be done with type system
    assert(v.forall(choices.contains), s"Bad choice for key: $nameIn which must be one of: $choices")
    ChoiceItem(keyName, choices, v, units)
  }

  override def set(v: Choice*) = {
    // Check to make sure set values are in the choices -- could be done with type system
    assert(v.forall(choices.contains), s"Bad choice for key: $nameIn which must be one of: $choices")
    ChoiceItem(keyName, choices, v.toVector, units = NoUnits)
  }
}

object ChoiceKey {
  /**
    * This allows creating a ChoiceKey with a several Choice
    * @param nameIn the name of this key
    * @param choicesIn varargs sequence of Choice objects
    * @return a new ChoiceKey
    */
  def apply(nameIn: String, choicesIn: Choice*):ChoiceKey = ChoiceKey(nameIn, Choices(choicesIn.toSet))
}
