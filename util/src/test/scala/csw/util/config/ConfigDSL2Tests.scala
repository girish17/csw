package csw.util.config

import org.scalatest.FunSpec
import csw.util.config.ConfigDSL._
import csw.util.config.Configurations.SetupConfig

/**
 * Test DSL for configs
 */
class ConfigDSL2Tests extends FunSpec {

  describe("checking arrow notation") {
    val zeroPoint = IntKey("zeroPoint")

    it("should work with an sc") {
      val defaultMoveSC = SetupConfig("test").add(zeroPoint -> 0)
    }
  }

  describe("Tests DSL functions") {
    val k1 = IntKey("itest")
    val k2 = DoubleKey("dtest")
    val k3 = StringKey("stest")

    val i1 = set(k1, 1, 2, 3).withUnits(UnitsOfMeasure.Deg)
    val i2 = set(k2, 1.0, 2.0, 3.0).withUnits(UnitsOfMeasure.Meters)
    val i3 = set(k3, "A", "B", "C")

    it("should allow using head(item) and value(item)") {
      assert(head(i1) == 1)
      assert(value(i1, 1) == 2)
      assert(value(i1, 2) == 3)
      assert(get(i1, 3).isEmpty)
      assert(values(i1) == Vector(1, 2, 3))
      assert(i1 == vset(k1, Vector(1, 2, 3), UnitsOfMeasure.Deg))

      assert(head(i2) == 1.0)
      assert(value(i2, 1) == 2.0)
      assert(value(i2, 2) == 3.0)
      assert(get(i2, 3).isEmpty)
      assert(values(i2) == Vector(1.0, 2.0, 3.0))
      assert(i2 == vset(k2, Vector(1.0, 2.0, 3.0), UnitsOfMeasure.Meters))

      assert(head(i3) == "A")
      assert(value(i3, 1) == "B")
      assert(value(i3, 2) == "C")
      assert(get(i3, 3).isEmpty)
      assert(values(i3) == Vector("A", "B", "C"))
      assert(i3 == vset(k3, Vector("A", "B", "C")))
    }

    it("should support key -> value syntax for building configs") {
      val setupConfig1 = sc(
        "test",
        k1 -> Vector(1, 2, 3) withUnits UnitsOfMeasure.Deg,
        k2 -> Vector(1.0, 2.0, 3.0) withUnits UnitsOfMeasure.Meters,
        k3 -> Vector("A", "B", "C")
      )
      assert(setupConfig1.get(k1).get.values == Vector(1, 2, 3))
      assert(setupConfig1.get(k1).get.units == UnitsOfMeasure.Deg)
      assert(setupConfig1.get(k2).get.head == 1.0)
      assert(setupConfig1.get(k2).get.units == UnitsOfMeasure.Meters)
      assert(setupConfig1.get(k3).get.value(1) == "B")

      val setupConfig2 = sc(
        "test",
        k1 -> 1 withUnits UnitsOfMeasure.Deg,
        k2 -> (2.0, UnitsOfMeasure.Meters),
        k3 -> "C"
      )
      assert(get(setupConfig2, k1).get.head == 1)
      assert(get(setupConfig2, k1).get.units == UnitsOfMeasure.Deg)
      assert(setupConfig2.get(k2).get.head == 2.0)
      assert(setupConfig2.get(k2).get.units == UnitsOfMeasure.Meters)
      assert(setupConfig2.get(k3).get.head == "C")
    }
  }
}